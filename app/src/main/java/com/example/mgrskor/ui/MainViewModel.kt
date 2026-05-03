package com.example.mgrskor.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.mgrskor.LocationCollectorService
import com.example.mgrskor.data.AppDatabase
import com.example.mgrskor.data.SavedPoint
import com.example.mgrskor.location.LocationCollector
import com.example.mgrskor.mgrs.MgrsFormatter
import com.example.mgrskor.sensors.Altimeter
import com.example.mgrskor.sensors.BarometerReader
import com.example.mgrskor.sensors.CompassReader
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import mil.nga.mgrs.grid.GridType

/**
 * Тонкий адаптер між UI і [LocationCollector].
 *
 * VM не володіє станом збору — він живе в синглтоні, бо має пережити навіть
 * знищення Activity (Foreground Service тримає процес).
 * VM лише пробрасує `state` як StateFlow та керує сервісом + БД.
 */
class MainViewModel(app: Application) : AndroidViewModel(app) {

    val ui: StateFlow<LocationCollector.Snapshot> = LocationCollector.state
        .stateIn(viewModelScope, SharingStarted.Eagerly, LocationCollector.state.value)

    private val dao = AppDatabase.get(app).savedPointDao()
    val savedPoints: StateFlow<List<SavedPoint>> = dao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Live-список точок треку для polyline. */
    val trackPoints: StateFlow<List<LocationCollector.TrackPoint>> = LocationCollector.trackFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, LocationCollector.trackFlow.value)

    /** Чи показувати збережені вейпойнти на карті. */
    private val _showWaypointsOnMap = MutableStateFlow(true)
    val showWaypointsOnMap: StateFlow<Boolean> = _showWaypointsOnMap.asStateFlow()
    fun toggleWaypointsOnMap() { _showWaypointsOnMap.value = !_showWaypointsOnMap.value }

    /** Обрана ціль для навігації (Go-to), null якщо немає. */
    private val _navTarget = MutableStateFlow<NavTarget?>(null)
    val navTarget: StateFlow<NavTarget?> = _navTarget.asStateFlow()
    fun setNavTarget(p: SavedPoint?) {
        _navTarget.value = p?.let { NavTarget(it.id, it.name, it.latitude, it.longitude) }
    }
    fun clearNavTarget() { _navTarget.value = null }

    data class NavTarget(
        val id: Long,
        val name: String,
        val latitude: Double,
        val longitude: Double
    )

    // ===== Сенсори =====
    private val compass = CompassReader(app)
    private val barometer = BarometerReader(app)
    private var compassJob: Job? = null
    private var barometerJob: Job? = null

    private val _sensors = MutableStateFlow(SensorState())
    val sensors: StateFlow<SensorState> = _sensors.asStateFlow()

    data class SensorState(
        val azimuthDeg: Float? = null,
        val declinationDeg: Float = 0f,
        val pressureHpa: Float? = null,
        val altitudeMeters: Double? = null,
        val seaLevelPressureHpa: Float = Altimeter.P0_STANDARD,
        val compassAvailable: Boolean = false,
        val barometerAvailable: Boolean = false
    )

    fun startSensors() {
        _sensors.update {
            it.copy(
                compassAvailable = compass.isAvailable,
                barometerAvailable = barometer.isAvailable
            )
        }
        if (compassJob?.isActive != true && compass.isAvailable) {
            // Беремо lat/lon з останнього результату для true-north декларації, якщо є.
            val r = ui.value.lastResult
            compassJob = viewModelScope.launch {
                compass.headings(
                    currentLatitude = r?.latitude,
                    currentLongitude = r?.longitude,
                    altitudeMeters = (_sensors.value.altitudeMeters ?: 0.0).toFloat()
                ).collect { h ->
                    _sensors.update {
                        it.copy(azimuthDeg = h.azimuthDeg, declinationDeg = h.declinationDeg)
                    }
                }
            }
        }
        if (barometerJob?.isActive != true && barometer.isAvailable) {
            barometerJob = viewModelScope.launch {
                barometer.pressuresHpa().collect { p ->
                    val alt = Altimeter.altitudeMeters(p, _sensors.value.seaLevelPressureHpa)
                    _sensors.update { it.copy(pressureHpa = p, altitudeMeters = alt) }
                }
            }
        }
    }

    fun stopSensors() {
        compassJob?.cancel(); compassJob = null
        barometerJob?.cancel(); barometerJob = null
    }

    /** Калібрувати тиск на рівні моря за останньою GPS-висотою. */
    fun calibrateAltimeterFromGps(): Boolean {
        val p = _sensors.value.pressureHpa ?: return false
        val gpsAlt = ui.value.lastResult?.altitudeMeters ?: return false
        val newP0 = Altimeter.calibrateSeaLevelPressure(p, knownAltitudeM = gpsAlt)
        _sensors.update {
            it.copy(
                seaLevelPressureHpa = newP0,
                altitudeMeters = Altimeter.altitudeMeters(p, newP0)
            )
        }
        return true
    }

    fun setMovingMode(moving: Boolean) = LocationCollector.setMovingMode(moving)

    fun toggleCollecting() {
        // Перевіряємо ВИДИМУ фазу (а не лише `collectJob.isActive`), тому що кнопка
        // показує "Зупинити збір" поки phase=Collecting/WarmingUp. Інакше виникала
        // гонитва: collectJob уже null (фіналізація на Dispatchers.Default), а
        // phase ще Collecting → клік помилково стартував новий збір з нуля.
        val phase = LocationCollector.state.value.phase
        val showingStop = phase is LocationCollector.Phase.Collecting ||
                phase is LocationCollector.Phase.WarmingUp ||
                LocationCollector.isCollecting()
        if (showingStop) {
            LocationCollectorService.stop(getApplication())
        } else {
            LocationCollectorService.start(getApplication())
        }
    }

    fun startGnssMonitoring() = LocationCollector.startGnssMonitoring(getApplication())
    fun stopGnssMonitoring() = LocationCollector.stopGnssMonitoring()
    fun startPrewarm() = LocationCollector.startPrewarm(getApplication())
    fun stopPrewarm() = LocationCollector.stopPrewarm()

    // ===== Трек =====
    fun toggleTracking() {
        val ctx = getApplication<android.app.Application>()
        if (LocationCollector.isTracking()) {
            LocationCollector.stopTracking()
            // Сервіс продовжує жити лише якщо ще йде збір; інакше зупиняємо.
            if (!LocationCollector.isCollecting()) {
                LocationCollectorService.stop(ctx)
            }
        } else {
            LocationCollectorService.startTrackingOnly(ctx)
            LocationCollector.startTracking(ctx)
        }
    }
    fun snapshotTrack() = LocationCollector.snapshotTrack()
    fun clearTrack() = LocationCollector.clearTrack()

    /** Зберігає поточний результат у Room. */
    fun saveCurrentResult(name: String) {
        val r = ui.value.lastResult ?: return
        savePoint(
            name = name,
            latitude = r.latitude,
            longitude = r.longitude,
            mgrs = r.mgrs,
            accuracyMeters = r.accuracyMeters,
            satellitesUsed = r.gnssAtFix.usedInFix,
            avgCn0 = r.gnssAtFix.avgCn0
        )
    }

    /** Зберігає довільну точку, зокрема виставлену по центру карти/прицілу. */
    fun savePoint(
        name: String,
        latitude: Double,
        longitude: Double,
        mgrs: String = MgrsFormatter.format(latitude, longitude, GridType.METER),
        accuracyMeters: Float = Float.NaN,
        satellitesUsed: Int = 0,
        avgCn0: Float = Float.NaN
    ) {
        viewModelScope.launch {
            dao.insert(
                SavedPoint(
                    name = name.ifBlank { defaultPointName() },
                    latitude = latitude,
                    longitude = longitude,
                    mgrs = mgrs,
                    accuracyMeters = accuracyMeters,
                    satellitesUsed = satellitesUsed,
                    avgCn0 = avgCn0,
                    timestampMs = System.currentTimeMillis()
                )
            )
        }
    }

    fun deletePoint(point: SavedPoint) {
        viewModelScope.launch { dao.delete(point) }
    }

    fun renamePoint(point: SavedPoint, newName: String) {
        val trimmed = newName.trim()
        viewModelScope.launch {
            dao.update(point.copy(name = trimmed.ifBlank { defaultPointName() }))
        }
    }

    private fun defaultPointName(): String {
        val ts = System.currentTimeMillis() / 1000
        return "WP-$ts"
    }
}
