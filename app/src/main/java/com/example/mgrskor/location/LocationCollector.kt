package com.example.mgrskor.location

import android.content.Context
import com.example.mgrskor.GnssQualityMonitor
import com.example.mgrskor.mgrs.MgrsFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import mil.nga.mgrs.grid.GridType

/**
 * Процес-скоповий координатор збору координат.
 *
 * Чому синглтон: збір MUST виживати поза життєвим циклом Activity/ViewModel —
 * користувач може покласти телефон у кишеню і йти лісом. У такому разі
 * `LocationCollectorService` (Foreground Service) тримає процес живим,
 * а вся логіка живе тут і веде свій StateFlow, на який підписані і VM, і Service.
 */
object LocationCollector {

    // ===== Профіль "ліс" =====
    private const val TARGET_SAMPLES = 30
    private const val MAX_SAMPLES = 90
    private const val MAX_ACCEPTABLE_ACC_M = 50f
    private const val MAX_FIX_AGE_MS = 4000L
    private const val COLLECT_TIMEOUT_MS = 90_000L
    private const val UPDATE_INTERVAL_MS = 1000L
    private const val WARMUP_SAMPLES = 3

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var collectJob: Job? = null
    private var prewarmJob: Job? = null
    private var locationStream: LocationStream? = null
    private var gnssMonitor: GnssQualityMonitor? = null

    private val kalman = GpsKalman(processNoiseMps = 0.2f)
    private val rawSamples = mutableListOf<LocationAverager.Sample>()
    /** Зразки висоти GPS (м) із вагами 1/σ². */
    private val altSamples = mutableListOf<AltSample>()

    private data class AltSample(val altitude: Double, val sigma: Double)

    private val _state = MutableStateFlow(Snapshot())
    val state: StateFlow<Snapshot> = _state.asStateFlow()

    fun setMovingMode(moving: Boolean) {
        kalman.processNoiseMps = if (moving) 1.5f else 0.2f
    }

    fun startGnssMonitoring(context: Context) {
        if (gnssMonitor == null) {
            gnssMonitor = GnssQualityMonitor(context.applicationContext) { stats ->
                _state.update { it.copy(gnss = stats) }
            }
        }
        gnssMonitor?.start()
    }

    fun stopGnssMonitoring() {
        gnssMonitor?.stop()
    }

    fun startPrewarm(context: Context) {
        ensureLocationStream(context)
        if (prewarmJob?.isActive == true) return
        if (locationStream?.hasLocationPermission() != true) return
        prewarmJob = scope.launch {
            try {
                locationStream!!.updates(intervalMs = 5000L, waitForAccurate = false)
                    .collect { /* keep chip warm */ }
            } catch (_: SecurityException) { /* ignore */ }
        }
    }

    fun stopPrewarm() {
        prewarmJob?.cancel()
        prewarmJob = null
    }

    fun isCollecting(): Boolean = collectJob?.isActive == true

    /** Стартує збір. Якщо вже йде — нічого не робить. */
    fun startCollecting(context: Context) {
        ensureLocationStream(context)
        if (collectJob?.isActive == true) return
        if (locationStream?.hasLocationPermission() != true) {
            _state.update { it.copy(phase = Phase.PermissionRequired) }
            return
        }

        rawSamples.clear()
        altSamples.clear()
        kalman.reset()
        var warmupSeen = 0
        _state.update {
            it.copy(
                phase = Phase.Collecting(0, TARGET_SAMPLES, currentSigmaMeters = null),
                lastResult = null
            )
        }

        collectJob = scope.launch {
            try {
                withTimeoutOrNull(COLLECT_TIMEOUT_MS) {
                    locationStream!!.updates(UPDATE_INTERVAL_MS, waitForAccurate = true).collect { loc ->
                        if (locationStream!!.fixAgeMs(loc) > MAX_FIX_AGE_MS) return@collect
                        if (!loc.hasAccuracy() || loc.accuracy > MAX_ACCEPTABLE_ACC_M) return@collect

                        if (warmupSeen < WARMUP_SAMPLES) {
                            warmupSeen++
                            kalman.update(loc.latitude, loc.longitude, loc.accuracy.toDouble(), loc.time)
                            _state.update {
                                it.copy(phase = Phase.WarmingUp(warmupSeen, WARMUP_SAMPLES))
                            }
                            return@collect
                        }

                        kalman.update(loc.latitude, loc.longitude, loc.accuracy.toDouble(), loc.time)
                        rawSamples += LocationAverager.Sample(
                            loc.latitude, loc.longitude, loc.accuracy.toDouble()
                        )
                        if (loc.hasAltitude()) {
                            // verticalAccuracy доступна з API 26; до неї — беремо грубу оцінку 1.5 * acc.
                            val vAcc = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O
                                && loc.hasVerticalAccuracy()
                            ) loc.verticalAccuracyMeters.toDouble()
                            else (loc.accuracy * 1.5).toDouble()
                            altSamples += AltSample(loc.altitude, vAcc.coerceAtLeast(1.0))
                        }

                        _state.update {
                            it.copy(
                                phase = Phase.Collecting(
                                    rawSamples.size, TARGET_SAMPLES,
                                    currentSigmaMeters = kalman.sigmaMeters
                                ),
                                preview = LiveCoord(kalman.latitude, kalman.longitude)
                            )
                        }

                        if (rawSamples.size >= TARGET_SAMPLES || rawSamples.size >= MAX_SAMPLES) {
                            finalizeCollecting(timedOut = false)
                        }
                    }
                } ?: finalizeCollecting(timedOut = true)
            } catch (_: SecurityException) {
                _state.update { it.copy(phase = Phase.PermissionRequired) }
            }
        }
    }

    fun stopCollecting() {
        val job = collectJob
        if (job?.isActive == true) {
            // Скасовуємо джобу і обнуляємо посилання НЕГАЙНО — щоб
            // isCollecting() та `phase`-перевірки одразу віддавали коректний
            // стан, доки фоновий finalizeCollecting() рахує середнє.
            collectJob = null
            job.cancel()
            scope.launch { finalizeCollecting(timedOut = false, alreadyCancelled = true) }
        }
    }

    private suspend fun finalizeCollecting(timedOut: Boolean, alreadyCancelled: Boolean = false) {
        if (!alreadyCancelled) {
            // Нормальне завершення приходить із самої collectJob-корутини,
            // тому тут не можна cancel() цієї ж job — інакше зависнемо на фазі
            // Finalizing і не дійдемо до оновлення стану в Idle.
            collectJob = null
        }

        if (rawSamples.isEmpty()) {
            _state.update { it.copy(phase = Phase.NoFix) }
            return
        }

        _state.update { it.copy(phase = Phase.Finalizing) }

        val result = withContext(Dispatchers.Default) {
            LocationAverager.weightedAverageWithOutlierRejection(rawSamples.toList())
        }
        val finalSigma = listOfNotNull(
            result.sigmaMeters.takeIf { !it.isNaN() },
            kalman.sigmaMeters.takeIf { !it.isNaN() }
        ).minOrNull()?.toFloat() ?: result.sigmaMeters.toFloat()

        val gridType = MgrsFormatter.chooseGridType(finalSigma)
        val mgrs = MgrsFormatter.format(result.latitude, result.longitude, gridType)

        // Зважене середнє висоти за вагами 1/σ²; null якщо жодного виміру.
        val altPair: Pair<Double, Float>? = if (altSamples.isEmpty()) null else {
            var w = 0.0; var alt = 0.0
            for (s in altSamples) {
                val ww = 1.0 / (s.sigma * s.sigma)
                w += ww; alt += s.altitude * ww
            }
            val sigma = (1.0 / kotlin.math.sqrt(w)).toFloat()
            (alt / w) to sigma
        }

        _state.update {
            it.copy(
                phase = Phase.Idle,
                lastResult = ResultState(
                    latitude = result.latitude,
                    longitude = result.longitude,
                    accuracyMeters = finalSigma,
                    mgrs = mgrs,
                    gridType = gridType,
                    samplesUsed = result.samplesUsed,
                    timedOut = timedOut,
                    gnssAtFix = it.gnss,
                    altitudeMeters = altPair?.first,
                    altitudeAccuracyMeters = altPair?.second
                ),
                preview = LiveCoord(result.latitude, result.longitude)
            )
        }
    }

    /** Повністю прибрати ресурси (виклик з Application/test cleanup). */
    fun shutdown() {
        scope.coroutineContext.cancelChildren()
        gnssMonitor?.stop()
        gnssMonitor = null
        locationStream = null
    }

    // ===== Трек-логер (запис треку для GPX) =====

    private const val TRACK_INTERVAL_MS = 2000L
    private const val TRACK_MIN_DIST_M = 5.0   // не писати якщо змістились < 5 м
    private const val TRACK_MAX_ACC_M = 30f    // ігнорувати "піщані" фікси

    private var trackJob: Job? = null
    private val trackPoints = mutableListOf<TrackPoint>()
    @Volatile private var lastTrackPoint: TrackPoint? = null

    /** Окремий потік списку точок треку — щоб карта могла перемальовувати polyline. */
    private val _trackFlow = MutableStateFlow<List<TrackPoint>>(emptyList())
    val trackFlow: StateFlow<List<TrackPoint>> = _trackFlow.asStateFlow()

    fun isTracking(): Boolean = trackJob?.isActive == true
    fun trackPointCount(): Int = synchronized(trackPoints) { trackPoints.size }

    fun startTracking(context: Context) {
        ensureLocationStream(context)
        if (trackJob?.isActive == true) return
        if (locationStream?.hasLocationPermission() != true) {
            _state.update { it.copy(phase = Phase.PermissionRequired) }
            return
        }
        synchronized(trackPoints) { trackPoints.clear() }
        lastTrackPoint = null
        _trackFlow.value = emptyList()
        _state.update { it.copy(tracking = true, trackedCount = 0) }
        trackJob = scope.launch {
            try {
                locationStream!!.updates(TRACK_INTERVAL_MS, waitForAccurate = false).collect { loc ->
                    if (!loc.hasAccuracy() || loc.accuracy > TRACK_MAX_ACC_M) return@collect
                    val prev = lastTrackPoint
                    if (prev != null) {
                        val out = FloatArray(1)
                        android.location.Location.distanceBetween(
                            prev.latitude, prev.longitude, loc.latitude, loc.longitude, out
                        )
                        if (out[0] < TRACK_MIN_DIST_M) return@collect
                    }
                    val tp = TrackPoint(
                        latitude = loc.latitude,
                        longitude = loc.longitude,
                        altitudeMeters = if (loc.hasAltitude()) loc.altitude else null,
                        accuracyMeters = loc.accuracy,
                        timestampMs = loc.time
                    )
                    val newSize = synchronized(trackPoints) {
                        trackPoints += tp
                        trackPoints.size
                    }
                    lastTrackPoint = tp
                    _trackFlow.value = synchronized(trackPoints) { trackPoints.toList() }
                    _state.update { it.copy(trackedCount = newSize) }
                }
            } catch (_: SecurityException) {
                _state.update { it.copy(phase = Phase.PermissionRequired) }
            }
        }
    }

    fun stopTracking() {
        trackJob?.cancel(); trackJob = null
        _state.update { it.copy(tracking = false) }
    }

    /** Повертає копію треку та НЕ очищує його. */
    fun snapshotTrack(): List<TrackPoint> =
        synchronized(trackPoints) { trackPoints.toList() }

    fun clearTrack() {
        synchronized(trackPoints) { trackPoints.clear() }
        lastTrackPoint = null
        _trackFlow.value = emptyList()
        _state.update { it.copy(trackedCount = 0) }
    }

    data class TrackPoint(
        val latitude: Double,
        val longitude: Double,
        val altitudeMeters: Double?,
        val accuracyMeters: Float,
        val timestampMs: Long
    )

    private fun ensureLocationStream(context: Context) {
        if (locationStream == null) {
            locationStream = LocationStream(context.applicationContext)
        }
    }

    // ===== Моделі =====

    data class Snapshot(
        val phase: Phase = Phase.Idle,
        val lastResult: ResultState? = null,
        val preview: LiveCoord? = null,
        val gnss: GnssQualityMonitor.Stats = GnssQualityMonitor.Stats(),
        val tracking: Boolean = false,
        val trackedCount: Int = 0
    )

    sealed interface Phase {
        data object Idle : Phase
        data object PermissionRequired : Phase
        data object NoFix : Phase
        data object Finalizing : Phase
        data class WarmingUp(val seen: Int, val total: Int) : Phase
        data class Collecting(val collected: Int, val target: Int, val currentSigmaMeters: Double?) : Phase
    }

    data class LiveCoord(val latitude: Double, val longitude: Double)

    data class ResultState(
        val latitude: Double,
        val longitude: Double,
        val accuracyMeters: Float,
        val mgrs: String,
        val gridType: GridType,
        val samplesUsed: Int,
        val timedOut: Boolean,
        val gnssAtFix: GnssQualityMonitor.Stats,
        /** GPS-висота (м WGS84 еліпсоїд), null якщо жоден фікс не мав hasAltitude. */
        val altitudeMeters: Double? = null,
        /** 1σ-точність висоти, м. */
        val altitudeAccuracyMeters: Float? = null
    )
}
