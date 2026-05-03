package com.example.mgrskor

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.mgrskor.databinding.ActivityMainBinding
import com.example.mgrskor.location.LocationCollector
import com.example.mgrskor.map.MapLayers
import com.example.mgrskor.map.MapRotationOverlay
import com.example.mgrskor.map.OfflineTiles
import com.example.mgrskor.mgrs.MgrsFormatter
import com.example.mgrskor.ui.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mil.nga.mgrs.MGRS
import mil.nga.mgrs.grid.GridType
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.FolderOverlay
import java.io.File
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private var currentMarker: Marker? = null
    private var trackPolyline: Polyline? = null
    private var navPolyline: Polyline? = null
    private val waypointsFolder = FolderOverlay()

    /**
     * «Follow»-режим — карта автоматично центрується на останньому GPS-фіксі / preview.
     * Щойно користувач торкнеться карти вручну (pan / zoom / rotate) — режим вимикається,
     * щоб під час визначення координат можна було дивитися на іншу частину карти.
     * Ре-вмикається кнопкою центрування (fabCenter) або новим пуском збору.
     */
    private var followLocation: Boolean = true

    /** Останній показаний кут стрілки компаса (для вибору коротшого напрямку обертання). */
    private var lastCompassRotation: Float = 0f

    /** Чи розгорнута панель координат (з кнопками й деталями). За замовчуванням — так. */
    private var coordsExpanded: Boolean = true

    private val locationPermissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) {
            viewModel.startGnssMonitoring()
            viewModel.startPrewarm()
            requestNotificationPermissionIfNeeded()
            followLocation = true
            viewModel.toggleCollecting()
        } else {
            Toast.makeText(this, R.string.permission_denied, Toast.LENGTH_LONG).show()
        }
    }

    private val notificationPermissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* якщо відмовлено — сервіс працює, просто без видимої нотифікації */ }

    private val createTrackGpx = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/gpx+xml")
    ) { uri ->
        if (uri != null) writeTrackToUri(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureOsmdroid()

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupMap()
        setupListeners()
        observeState()
    }

    private fun configureOsmdroid() {
        Configuration.getInstance().load(
            applicationContext,
            getSharedPreferences("osmdroid", Context.MODE_PRIVATE)
        )
        Configuration.getInstance().userAgentValue = packageName
        Configuration.getInstance().tileFileSystemCacheMaxBytes = 500L * 1024 * 1024 // 500 MB
    }

    private fun setupMap() {
        binding.map.apply {
            setTileSource(TileSourceFactory.MAPNIK)
            // Жести: pan + pinch-to-zoom двома пальцями
            setMultiTouchControls(true)
            isTilesScaledToDpi = true
            minZoomLevel = 4.0
            maxZoomLevel = 19.0
            controller.setZoom(5.0)
            controller.setCenter(GeoPoint(49.0, 31.0))
            // Контейнер для маркерів збережених точок (легко чистити одним викликом)
            overlays.add(waypointsFolder)
        }
        // Обертання карти двома пальцями + синхронізація стрілки-компаса
        val rotationOverlay = MapRotationOverlay(binding.map) { orientation ->
            // Плавно обертаємо стрілку з вибором коротшого напрямку (обхід 360°).
            animateCompassTo(-orientation)
        }
        rotationOverlay.isEnabled = true
        binding.map.overlays.add(rotationOverlay)

        // Будь-який жест користувача по карті вимикає автоцентрування.
        // Це дозволяє вільно рухати картою, поки йде збір координат.
        binding.map.setOnTouchListener { v, ev ->
            if (ev.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
                followLocation = false
            }
            // false — пропускаємо подію далі до MapView, щоб pan/zoom/rotate працювали.
            v.performClick()
            false
        }
        // Застосовуємо збережений шар карти (OSM / Супутник / Гібрид)
        val savedLayer = MapLayers.load(this)
        MapLayers.apply(this, binding.map, savedLayer)
        binding.btnLayers.setIconResource(MapLayers.iconRes(savedLayer))
        applyNightModeFilter(savedLayer)
        // Початкова орієнтація стрілки компаса
        binding.ivCompass.rotation = -binding.map.mapOrientation
        lastCompassRotation = -binding.map.mapOrientation
        binding.ivCoordsChevron.rotation = 0f
        // Створення офлайн-папки — на IO-потоці
        lifecycleScope.launch(Dispatchers.IO) {
            val dir = File(getExternalFilesDir(null), "osmdroid/tiles")
            if (!dir.exists()) dir.mkdirs()
        }
    }

    private fun setupListeners() {
        binding.btnLocate.setOnClickListener {
            if (!hasLocationPermission()) {
                requestLocationPermissions()
            } else {
                requestNotificationPermissionIfNeeded()
                // Новий запуск збору — повертаємо follow-режим, щоб перший фікс центрувався.
                followLocation = true
                viewModel.toggleCollecting()
            }
        }
        binding.btnMoving.addOnCheckedChangeListener { _, checked ->
            viewModel.setMovingMode(checked)
            binding.btnMoving.contentDescription = getString(
                if (checked) R.string.mode_moving_on else R.string.mode_moving
            )
        }
        binding.btnCopy.setOnClickListener { copyMgrs() }
        binding.btnShare.setOnClickListener { shareLocation() }
        binding.btnGotoCoords.setOnClickListener { promptGoToCoordinates() }
        binding.tvCoords.setOnLongClickListener {
            if (currentMgrsOrNull() != null) { copyMgrs(); true } else false
        }
        // Тап по координатах або «хваталці» — згортає / розгортає панель з кнопками й деталями.
        val toggleCoordsExpand = View.OnClickListener { toggleCoordsExpanded() }
        binding.tvCoords.setOnClickListener(toggleCoordsExpand)
        binding.coordsHandle.setOnClickListener(toggleCoordsExpand)
        binding.ivCoordsChevron.setOnClickListener(toggleCoordsExpand)
        // Свайп вниз/вгору по картці — згортає / розгортає панель.
        attachCoordsSwipeGesture()
        binding.btnSave.setOnClickListener { promptSaveCurrentPoint() }
        binding.btnList.setOnClickListener {
            val opts = androidx.core.app.ActivityOptionsCompat.makeCustomAnimation(
                this, R.anim.slide_in_right, R.anim.fade_out
            )
            startActivity(Intent(this, SavedPointsActivity::class.java), opts.toBundle())
        }
        binding.btnTrack.setOnClickListener { onTrackButtonClick() }
        binding.btnTrack.setOnLongClickListener {
            promptExportTrack()
            true
        }
        binding.btnAddWaypoint.setOnClickListener {
            android.util.Log.i("MGRS_KOR", "btnAddWaypoint clicked")
            promptSaveCrosshairPoint()
        }
        binding.btnToggleWaypoints.setOnClickListener {
            viewModel.toggleWaypointsOnMap()
        }
        binding.btnToggleWaypoints.setOnLongClickListener {
            showMapMenu(it)
            true
        }
        binding.btnLayers.setOnClickListener {
            val next = MapLayers.next(MapLayers.load(this))
            MapLayers.save(this, next)
            MapLayers.apply(this, binding.map, next)
            binding.btnLayers.setIconResource(MapLayers.iconRes(next))
            applyNightModeFilter(next)
            Toast.makeText(this, MapLayers.displayName(next), Toast.LENGTH_SHORT).show()
        }
        binding.btnLayers.setOnLongClickListener {
            showMapMenu(it)
            true
        }
        binding.ivCompass.setOnClickListener {
            // Скидаємо поворот карти на північ та вирівнюємо стрілку.
            binding.map.mapOrientation = 0f
            binding.map.invalidate()
            animateCompassTo(0f, durationMs = 280)
            Toast.makeText(this, R.string.map_north_restored, Toast.LENGTH_SHORT).show()
        }
        binding.fabCenter.setOnClickListener { centerOnMe() }
        binding.fabCenter.setOnLongClickListener { fitAll(); true }
        binding.tvSensors.setOnLongClickListener {
            val ok = viewModel.calibrateAltimeterFromGps()
            Toast.makeText(
                this,
                if (ok) R.string.altimeter_calibrated else R.string.altimeter_calibrate_need_fix,
                Toast.LENGTH_SHORT
            ).show()
            true
        }

        // Підказки при довгому натисканні (TooltipCompat) — корисно у польових умовах,
        // коли іконка незрозуміла без тексту.
        androidx.appcompat.widget.TooltipCompat.setTooltipText(
            binding.btnList, getString(R.string.saved_list_title)
        )
        androidx.appcompat.widget.TooltipCompat.setTooltipText(
            binding.btnTrack, getString(R.string.track_start)
        )
        androidx.appcompat.widget.TooltipCompat.setTooltipText(
            binding.btnAddWaypoint, getString(R.string.crosshair_add_waypoint)
        )
        androidx.appcompat.widget.TooltipCompat.setTooltipText(
            binding.btnToggleWaypoints, getString(R.string.waypoints_toggle)
        )
        androidx.appcompat.widget.TooltipCompat.setTooltipText(
            binding.btnLayers, getString(R.string.layers_toggle_with_offline)
        )
        androidx.appcompat.widget.TooltipCompat.setTooltipText(
            binding.ivCompass, getString(R.string.compass_reset)
        )
        androidx.appcompat.widget.TooltipCompat.setTooltipText(
            binding.btnMoving, getString(R.string.mode_moving)
        )
        androidx.appcompat.widget.TooltipCompat.setTooltipText(
            binding.fabCenter, getString(R.string.center_on_me)
        )
        androidx.appcompat.widget.TooltipCompat.setTooltipText(
            binding.tvCoords, getString(R.string.long_press_to_copy)
        )
        androidx.appcompat.widget.TooltipCompat.setTooltipText(
            binding.btnGotoCoords, getString(R.string.btn_goto_coords)
        )
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.ui.collect { render(it) }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.sensors.collect { renderSensors(it) }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                kotlinx.coroutines.flow.combine(
                    viewModel.ui, viewModel.navTarget, viewModel.sensors
                ) { ui, target, sens -> Triple(ui, target, sens) }
                    .collect { (ui, target, sens) -> renderNav(ui, target, sens) }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.trackPoints.collect { renderTrack(it) }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                kotlinx.coroutines.flow.combine(
                    viewModel.savedPoints,
                    viewModel.showWaypointsOnMap
                ) { pts, show -> pts to show }.collect { (pts, show) ->
                    android.util.Log.i(
                        "MGRS_KOR",
                        "savedPoints flow emit: count=${pts.size} show=$show"
                    )
                    renderWaypoints(if (show) pts else emptyList())
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.saveError.collect { msg ->
                    Toast.makeText(
                        this@MainActivity,
                        "Save error: $msg",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun renderSensors(s: MainViewModel.SensorState) {
        val az = s.azimuthDeg
        val alt = s.altitudeMeters
        val p = s.pressureHpa
        binding.tvSensors.text = when {
            az == null && alt == null -> getString(R.string.sensors_idle)
            else -> {
                val azPart = az?.let {
                    getString(R.string.compass_value, it, cardinal(it))
                } ?: getString(R.string.compass_unavailable)
                val altPart = if (alt != null && p != null) {
                    getString(R.string.altimeter_value, alt, p)
                } else getString(R.string.altimeter_unavailable)
                "$azPart   $altPart"
            }
        }
    }

    private fun cardinal(deg: Float): String {
        val sectors = arrayOf("Пн", "ПнСх", "Сх", "ПдСх", "Пд", "ПдЗх", "Зх", "ПнЗх")
        val idx = (((deg + 22.5f) % 360f) / 45f).toInt().coerceIn(0, 7)
        return sectors[idx]
    }

    /**
     * Плавно обертає стрілку компаса до [targetDeg] обираючи коротший напрямок
     * (через ±180°), щоб уникнути «довгого» обертання при перетині 0°/360°.
     */
    private fun animateCompassTo(targetDeg: Float, durationMs: Long = 160) {
        val current = lastCompassRotation
        var delta = ((targetDeg - current) % 360f + 540f) % 360f - 180f
        val end = current + delta
        binding.ivCompass.animate()
            .rotation(end)
            .setDuration(durationMs)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .start()
        lastCompassRotation = end
    }

    /**
     * Включає INVERT_COLORS на OSM-тайлах у нічному режимі — карта стає темною,
     * що зменшує навантаження на зір. Для супутника/гібриду інверсія не потрібна.
     */
    private fun applyNightModeFilter(layer: com.example.mgrskor.map.MapLayer) {
        val isNight = (resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
        val filter = if (isNight && layer == com.example.mgrskor.map.MapLayer.OSM)
            org.osmdroid.views.overlay.TilesOverlay.INVERT_COLORS else null
        binding.map.overlayManager.tilesOverlay.setColorFilter(filter)
        binding.map.invalidate()
    }

    /**
     * Підсвічує кнопку як «активну» — змінює фоновий тінт на colorPrimaryContainer
     * та іконку на onPrimaryContainer. Скидання — повернення до дефолтного стилю.
     */
    private fun setButtonActive(button: com.google.android.material.button.MaterialButton, active: Boolean) {
        if (active) {
            val bg = com.google.android.material.color.MaterialColors.getColor(
                button, com.google.android.material.R.attr.colorPrimaryContainer
            )
            val fg = com.google.android.material.color.MaterialColors.getColor(
                button, com.google.android.material.R.attr.colorOnPrimaryContainer
            )
            button.backgroundTintList = android.content.res.ColorStateList.valueOf(bg)
            button.iconTint = android.content.res.ColorStateList.valueOf(fg)
        } else {
            // Скидання до дефолтних значень стилю Filled.Tonal.
            button.backgroundTintList = null
            button.iconTint = null
        }
    }

    /**
     * Згортає / розгортає нижню панель з кнопками та деталями (псевдо-bottom-sheet).
     * Згорнутий стан показує лише «хваталку».
     */
    private fun toggleCoordsExpanded() {
        setCoordsExpanded(!coordsExpanded)
    }

    private fun setCoordsExpanded(expanded: Boolean) {
        if (coordsExpanded == expanded) return
        coordsExpanded = expanded
        val cardParams = binding.cardCoords.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
        cardParams.height = if (coordsExpanded) 0 else ViewGroup.LayoutParams.WRAP_CONTENT
        binding.cardCoords.layoutParams = cardParams
        val isLandscape = resources.configuration.orientation ==
                android.content.res.Configuration.ORIENTATION_LANDSCAPE
        binding.scrollCoords.layoutParams = binding.scrollCoords.layoutParams.apply {
            height = if (coordsExpanded && isLandscape) {
                ViewGroup.LayoutParams.MATCH_PARENT
            } else {
                ViewGroup.LayoutParams.WRAP_CONTENT
            }
        }
        androidx.transition.TransitionManager.beginDelayedTransition(
            binding.cardCoords,
            androidx.transition.AutoTransition().apply { duration = 180 }
        )
        val contentVisibility = if (coordsExpanded) View.VISIBLE else View.GONE
        binding.tvCoords.visibility = contentVisibility
        binding.coordsExpandable.visibility = contentVisibility
        binding.ivCoordsChevron.animate()
            .rotation(if (coordsExpanded) 0f else 180f)
            .setDuration(180)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .start()
    }

    /**
     * Підключає розпізнавання вертикального свайпу до картки координат:
     * свайп вниз — згортає, свайп вгору — розгортає панель. Працює і коли
     * палець стартує на «хваталці», і коли — на самій картці.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun attachCoordsSwipeGesture() {
        val detector = android.view.GestureDetector(this,
            object : android.view.GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: android.view.MotionEvent): Boolean = true
                override fun onFling(
                    e1: android.view.MotionEvent?,
                    e2: android.view.MotionEvent,
                    velocityX: Float,
                    velocityY: Float
                ): Boolean {
                    if (e1 == null) return false
                    val dy = e2.y - e1.y
                    val dx = e2.x - e1.x
                    // Має бути переважно вертикальний жест із достатньою швидкістю/довжиною.
                    if (kotlin.math.abs(dy) < kotlin.math.abs(dx)) return false
                    val minDistPx = 24 * resources.displayMetrics.density
                    if (kotlin.math.abs(dy) < minDistPx &&
                        kotlin.math.abs(velocityY) < 400f) return false
                    if (dy > 0 && coordsExpanded) {
                        setCoordsExpanded(false); return true
                    }
                    if (dy < 0 && !coordsExpanded) {
                        setCoordsExpanded(true); return true
                    }
                    return false
                }
            })
        val touch = View.OnTouchListener { v, ev ->
            val handled = detector.onTouchEvent(ev)
            // Не «з'їдаємо» тапи — нехай OnClickListener і далі спрацьовує.
            if (handled) v.performClick()
            handled
        }
        binding.cardCoords.setOnTouchListener(touch)
        binding.coordsHandle.setOnTouchListener(touch)
    }

    private fun renderNav(
        ui: LocationCollector.Snapshot,
        target: MainViewModel.NavTarget?,
        sens: MainViewModel.SensorState
    ) {
        if (target == null) {
            setNavVisible(false)
            removeNavPolyline()
            return
        }
        val from = ui.preview ?: ui.lastResult?.let {
            LocationCollector.LiveCoord(it.latitude, it.longitude)
        }
        if (from == null) {
            setNavVisible(true)
            binding.tvNav.text = getString(R.string.nav_no_fix, target.name)
            removeNavPolyline()
            return
        }
        val dist = com.example.mgrskor.geo.Geo.distanceMeters(
            from.latitude, from.longitude, target.latitude, target.longitude
        )
        val bearing = com.example.mgrskor.geo.Geo.initialBearingDegrees(
            from.latitude, from.longitude, target.latitude, target.longitude
        )
        val rel = sens.azimuthDeg?.let {
            com.example.mgrskor.geo.Geo.relativeBearingDegrees(bearing, it.toDouble())
        }
        setNavVisible(true)
        binding.tvNav.text = if (rel == null) {
            getString(
                R.string.nav_line, target.name,
                bearing, cardinal(bearing.toFloat()), formatDistance(dist)
            )
        } else {
            getString(
                R.string.nav_line_with_rel, target.name,
                bearing, cardinal(bearing.toFloat()), formatDistance(dist), rel
            )
        }
        drawNavPolyline(from.latitude, from.longitude, target.latitude, target.longitude)
    }

    private fun setNavVisible(visible: Boolean) {
        val target = if (visible) View.VISIBLE else View.GONE
        if (binding.tvNav.visibility == target) return
        androidx.transition.TransitionManager.beginDelayedTransition(
            binding.cardCoords,
            androidx.transition.Fade().apply { duration = 180 }
        )
        binding.tvNav.visibility = target
    }

    private fun drawNavPolyline(lat1: Double, lon1: Double, lat2: Double, lon2: Double) {
        val line = navPolyline ?: Polyline(binding.map).apply {
            outlinePaint.color = 0xFF1976D2.toInt()
            outlinePaint.strokeWidth = 6f
            // Пунктир — щоб візуально відрізнявся від треку
            outlinePaint.pathEffect = android.graphics.DashPathEffect(floatArrayOf(20f, 12f), 0f)
            // Малюємо ПІД маркерами
            binding.map.overlays.add(0, this)
            navPolyline = this
        }
        line.setPoints(listOf(GeoPoint(lat1, lon1), GeoPoint(lat2, lon2)))
        binding.map.invalidate()
    }

    private fun removeNavPolyline() {
        navPolyline?.let {
            binding.map.overlays.remove(it)
            navPolyline = null
            binding.map.invalidate()
        }
    }

    private fun currentGeoPoint(): GeoPoint? {
        val s = viewModel.ui.value
        val from = s.preview ?: s.lastResult?.let {
            LocationCollector.LiveCoord(it.latitude, it.longitude)
        } ?: return null
        return GeoPoint(from.latitude, from.longitude)
    }

    private fun showMapMenu(anchor: View) {
        val popup = androidx.appcompat.widget.PopupMenu(this, anchor)
        popup.menuInflater.inflate(R.menu.menu_main, popup.menu)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_offline_download -> {
                    OfflineTiles.promptDownloadVisibleArea(this, binding.map); true
                }
                R.id.action_offline_clear -> {
                    OfflineTiles.promptClearCache(this, binding.map); true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun centerOnMe() {
        val p = currentGeoPoint()
        if (p == null) {
            Toast.makeText(this, R.string.center_no_fix, Toast.LENGTH_SHORT).show()
            return
        }
        // Кнопка центрування відновлює follow-режим.
        followLocation = true
        binding.map.controller.animateTo(p)
        if (binding.map.zoomLevelDouble < 16.0) binding.map.controller.setZoom(17.0)
    }

    private fun fitAll() {
        val pts = mutableListOf<GeoPoint>()
        currentGeoPoint()?.let { pts += it }
        viewModel.navTarget.value?.let { pts += GeoPoint(it.latitude, it.longitude) }
        viewModel.trackPoints.value.forEach { pts += GeoPoint(it.latitude, it.longitude) }
        viewModel.savedPoints.value
            .takeIf { viewModel.showWaypointsOnMap.value }
            ?.forEach { pts += GeoPoint(it.latitude, it.longitude) }

        if (pts.isEmpty()) {
            Toast.makeText(this, R.string.center_no_fix, Toast.LENGTH_SHORT).show()
            return
        }
        if (pts.size == 1) {
            binding.map.controller.animateTo(pts[0])
            if (binding.map.zoomLevelDouble < 16.0) binding.map.controller.setZoom(17.0)
            return
        }
        val box = org.osmdroid.util.BoundingBox.fromGeoPointsSafe(pts)
        // padding 10% від кожного боку
        binding.map.post {
            binding.map.zoomToBoundingBox(box, true, 80)
        }
    }

    private fun formatDistance(m: Double): String =
        if (m < 1000) String.format(Locale.US, "%.0f м", m)
        else String.format(Locale.US, "%.2f км", m / 1000.0)

    private fun render(state: LocationCollector.Snapshot) {
        // GNSS статус (нижній рядок)
        binding.tvGnss.text = getString(
            R.string.gnss_status,
            state.gnss.usedInFix, state.gnss.visible,
            state.gnss.avgCn0,
            if (state.gnss.hasL5) "L1+L5" else "L1"
        )

        // Прев'ю на карті
        state.preview?.let { updateMarker(it.latitude, it.longitude, animate = state.lastResult == null) }

        // Pulse FAB центрування поки немає жодного фіксу/preview
        val haveAnyFix = state.preview != null || state.lastResult != null
        setFabPulsing(!haveAnyFix)

        // Стан кнопки треку
        if (state.tracking) {
            binding.btnTrack.setIconResource(R.drawable.ic_pause_24)
            binding.btnTrack.contentDescription = getString(R.string.track_running, state.trackedCount)
            setButtonActive(binding.btnTrack, true)
        } else {
            binding.btnTrack.setIconResource(R.drawable.ic_play_arrow_24)
            binding.btnTrack.contentDescription =
                if (state.trackedCount > 0) getString(R.string.track_ready_export, state.trackedCount)
                else getString(R.string.track_start)
            setButtonActive(binding.btnTrack, false)
        }

        // Не давати екрану згаснути під час активного збору або запису треку.
        val keepOn = state.tracking ||
                state.phase is LocationCollector.Phase.WarmingUp ||
            state.phase is LocationCollector.Phase.Collecting ||
            state.phase is LocationCollector.Phase.Finalizing
        if (keepOn) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        binding.btnLocate.isEnabled = state.phase !is LocationCollector.Phase.Finalizing

        when (val phase = state.phase) {
            LocationCollector.Phase.Idle -> {
                hideProgress()
                binding.btnLocate.setText(R.string.btn_locate)
                state.lastResult?.let { showResult(it) }
                    ?: run { binding.tvCoords.setText(R.string.press_button_hint) }
                updateActionButtonsVisibility(state.lastResult != null)
            }
            LocationCollector.Phase.PermissionRequired -> {
                hideProgress()
                binding.btnLocate.setText(R.string.btn_locate)
                binding.tvCoords.setText(R.string.permission_denied)
                updateActionButtonsVisibility(false)
            }
            LocationCollector.Phase.NoFix -> {
                hideProgress()
                binding.btnLocate.setText(R.string.btn_locate)
                binding.tvCoords.setText(R.string.no_good_fix)
                updateActionButtonsVisibility(false)
            }
            LocationCollector.Phase.Finalizing -> {
                showProgress(determinate = false)
                binding.btnLocate.setText(R.string.processing_result)
                binding.tvCoords.setText(R.string.processing_result)
                updateActionButtonsVisibility(false)
            }
            is LocationCollector.Phase.WarmingUp -> {
                showProgress(determinate = true, percent = if (phase.total > 0) (phase.seen * 100 / phase.total) else 0)
                binding.btnLocate.setText(R.string.btn_stop)
                binding.tvCoords.text = getString(R.string.warmup, phase.seen, phase.total)
                updateActionButtonsVisibility(false)
            }
            is LocationCollector.Phase.Collecting -> {
                showProgress(determinate = true, percent = if (phase.target > 0) (phase.collected * 100 / phase.target) else 0)
                binding.btnLocate.setText(R.string.btn_stop)
                binding.tvCoords.text = if (phase.currentSigmaMeters != null) {
                    getString(
                        R.string.collecting_with_acc,
                        phase.collected, phase.target,
                        String.format(Locale.US, "%.1f", phase.currentSigmaMeters)
                    )
                } else {
                    getString(R.string.collecting, phase.collected, phase.target)
                }
                updateActionButtonsVisibility(false)
            }
        }
    }

    private fun showResult(r: LocationCollector.ResultState) {
        val accuracyText = if (r.accuracyMeters.isNaN()) "—"
        else "±${kotlin.math.round(r.accuracyMeters).toInt()} м"

        val header = if (r.timedOut) getString(R.string.timeout_prefix) else ""
        val altLine = r.altitudeMeters?.let { a ->
            val acc = r.altitudeAccuracyMeters
            if (acc != null) "\n" + getString(R.string.altitude_with_acc, a, acc)
            else "\n" + getString(R.string.altitude_only, a)
        } ?: ""
        binding.tvCoords.text = header + getString(
            R.string.coords_format,
            r.mgrs,
            getString(precisionLabelRes(r.gridType)),
            String.format(Locale.US, "%.6f", r.latitude),
            String.format(Locale.US, "%.6f", r.longitude),
            accuracyText,
            r.samplesUsed
        ) + altLine
        // Маркер з підписом MGRS
        currentMarker?.title = r.mgrs
        // Центруємо на фінальній точці тільки якщо користувач не відвів карту вбік.
        if (followLocation) {
            binding.map.controller.setZoom(17.5)
            binding.map.controller.animateTo(GeoPoint(r.latitude, r.longitude))
        }
        binding.map.invalidate()
    }

    private fun updateActionButtonsVisibility(hasResult: Boolean) {
        val v = if (hasResult) View.VISIBLE else View.GONE
        // Анімуємо плавне з'явлення/зникнення кнопок та змінення розміру картки
        val needAnim = binding.btnCopy.visibility != v
        if (needAnim) {
            androidx.transition.TransitionManager.beginDelayedTransition(
                binding.cardCoords,
                androidx.transition.AutoTransition().apply { duration = 180 }
            )
        }
        binding.btnCopy.visibility = v
        binding.btnShare.visibility = v
        binding.btnSave.visibility = v
    }

    private fun showProgress(determinate: Boolean, percent: Int = 0) {
        with(binding.progress) {
            if (visibility != View.VISIBLE) {
                show() // вбудована Material-анімація fade-in
            }
            if (determinate) {
                if (isIndeterminate) {
                    // Перемикання потребує hide()/setIndeterminate/show()
                    hide()
                    isIndeterminate = false
                    show()
                }
                setProgressCompat(percent.coerceIn(0, 100), true)
            } else {
                if (!isIndeterminate) {
                    hide()
                    isIndeterminate = true
                    show()
                }
            }
        }
    }

    private fun hideProgress() {
        binding.progress.hide()
    }

    private val pulseAnimator: android.animation.ObjectAnimator by lazy {
        android.animation.ObjectAnimator.ofFloat(binding.fabCenter, "alpha", 1f, 0.35f, 1f).apply {
            duration = 1400
            repeatCount = android.animation.ValueAnimator.INFINITE
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
        }
    }

    private fun setFabPulsing(pulse: Boolean) {
        if (pulse) {
            if (!pulseAnimator.isStarted) pulseAnimator.start()
        } else {
            if (pulseAnimator.isStarted) {
                pulseAnimator.cancel()
                binding.fabCenter.alpha = 1f
            }
        }
    }

    private fun updateMarker(lat: Double, lon: Double, animate: Boolean) {
        val gp = GeoPoint(lat, lon)
        val justCreated = currentMarker == null
        if (currentMarker == null) {
            currentMarker = Marker(binding.map).apply {
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                alpha = 0f
                binding.map.overlays.add(this)
            }
            // Перший preview — центруємо лише якщо користувач не перемістив карту вручну.
            if (animate && followLocation) {
                binding.map.controller.setZoom(17.0)
                binding.map.controller.animateTo(gp)
            }
        }
        currentMarker?.position = gp
        if (justCreated) animateMarkerAppearance(currentMarker!!)
        binding.map.invalidate()
    }

    /**
     * Плавна поява маркера: fade-in (0→1) + лёгке «bounce» вгору-вниз через зсув якоря.
     * Реалізовано через ValueAnimator, який щораз інвалідовує мапу.
     */
    private fun animateMarkerAppearance(marker: Marker) {
        val animator = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 280
            interpolator = android.view.animation.OvershootInterpolator(1.6f)
            addUpdateListener { va ->
                val t = va.animatedValue as Float
                marker.alpha = t.coerceIn(0f, 1f)
                // «Стрибок» — піднімаємо якір трохи вище і повертаємо до ANCHOR_BOTTOM.
                val anchorY = Marker.ANCHOR_BOTTOM + (1f - t) * 0.25f
                marker.setAnchor(Marker.ANCHOR_CENTER, anchorY)
                binding.map.invalidate()
            }
        }
        animator.start()
    }

    private fun renderTrack(points: List<LocationCollector.TrackPoint>) {
        if (points.size < 2) {
            trackPolyline?.let { binding.map.overlays.remove(it) }
            trackPolyline = null
            binding.map.invalidate()
            return
        }
        val line = trackPolyline ?: Polyline(binding.map).also {
            it.outlinePaint.color = 0xFFEA4335.toInt()
            it.outlinePaint.strokeWidth = 8f
            // Малюємо ПІД маркерами: вставляємо на позицію 0
            binding.map.overlays.add(0, it)
            trackPolyline = it
        }
        line.setPoints(points.map { GeoPoint(it.latitude, it.longitude) })
        binding.map.invalidate()
    }

    private fun renderWaypoints(points: List<com.example.mgrskor.data.SavedPoint>) {
        waypointsFolder.items.clear()
        // Активний стан кнопки вейпойнтів — якщо вони відображаються на карті.
        setButtonActive(binding.btnToggleWaypoints, points.isNotEmpty())
        for (p in points) {
            val pos = GeoPoint(p.latitude, p.longitude)
            val m = Marker(binding.map).apply {
                position = pos
                title = p.name
                snippet = p.mgrs
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                icon = androidx.core.content.ContextCompat.getDrawable(
                    this@MainActivity, R.drawable.ic_location_on_24
                )?.mutate()?.also { d ->
                    // Контрастний до зеленої мапи маджента-рожевий — добре видно
                    // як на світлих, так і на темних ділянках.
                    androidx.core.graphics.drawable.DrawableCompat.setTint(
                        d, android.graphics.Color.parseColor("#FF1493")
                    )
                }
                setOnMarkerClickListener { _, _ ->
                    promptNavigateTo(p)
                    true
                }
            }
            waypointsFolder.add(m)

            // Підпис із назвою орієнтира — окремий «маркер»-бітмап нижче піна.
            val labelBitmap = buildWaypointLabelBitmap(p.name)
            val label = Marker(binding.map).apply {
                position = pos
                setAnchor(Marker.ANCHOR_CENTER, 0f)
                icon = android.graphics.drawable.BitmapDrawable(resources, labelBitmap)
                setInfoWindow(null)
                setOnMarkerClickListener { _, _ ->
                    promptNavigateTo(p)
                    true
                }
            }
            waypointsFolder.add(label)
        }
        binding.map.invalidate()
    }

    /**
     * Бітмап з назвою орієнтира: білий текст з чорною обводкою —
     * добре читається і на світлих, і на темних ділянках мапи.
     */
    private fun buildWaypointLabelBitmap(text: String): android.graphics.Bitmap {
        val density = resources.displayMetrics.density
        val textSizePx = 13f * density
        val padX = 4f * density
        val padY = 2f * density
        val strokeWidth = 3f * density

        val fillPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textSize = textSizePx
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            textAlign = android.graphics.Paint.Align.CENTER
        }
        val strokePaint = android.graphics.Paint(fillPaint).apply {
            style = android.graphics.Paint.Style.STROKE
            this.strokeWidth = strokeWidth
            color = android.graphics.Color.BLACK
        }

        val width = (fillPaint.measureText(text) + padX * 2f).toInt().coerceAtLeast(1)
        val fm = fillPaint.fontMetrics
        val height = (fm.descent - fm.ascent + padY * 2f).toInt().coerceAtLeast(1)

        val bmp = android.graphics.Bitmap.createBitmap(
            width, height, android.graphics.Bitmap.Config.ARGB_8888
        )
        val canvas = android.graphics.Canvas(bmp)
        val baseline = padY - fm.ascent
        val cx = width / 2f
        canvas.drawText(text, cx, baseline, strokePaint)
        canvas.drawText(text, cx, baseline, fillPaint)
        return bmp
    }

    private fun promptNavigateTo(p: com.example.mgrskor.data.SavedPoint) {
        val current = viewModel.navTarget.value?.id == p.id
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(p.name)
            .setMessage(p.mgrs)
            .setPositiveButton(
                if (current) R.string.nav_clear else R.string.nav_set
            ) { _, _ ->
                if (current) viewModel.clearNavTarget()
                else viewModel.setNavTarget(p)
            }
            .setNeutralButton(R.string.waypoint_rename_action) { _, _ ->
                promptRenameWaypoint(p)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun promptRenameWaypoint(point: com.example.mgrskor.data.SavedPoint) {
        val input = createWaypointNameInput().apply {
            setText(point.name)
            setSelection(text?.length ?: 0)
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.waypoint_rename_title)
            .setView(input)
            .setPositiveButton(R.string.waypoint_rename_action) { _, _ ->
                viewModel.renamePoint(point, input.text?.toString().orEmpty())
                Toast.makeText(this, R.string.waypoint_renamed, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun copyMgrs() {
        val r = viewModel.ui.value.lastResult ?: return
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("MGRS", r.mgrs))
        Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
    }

    private fun currentMgrsOrNull(): String? = viewModel.ui.value.lastResult?.mgrs

    private fun promptGoToCoordinates() {
        val input = android.widget.EditText(this).apply {
            hint = getString(R.string.goto_coords_hint)
            setSingleLine(false)
            maxLines = 2
            setText(currentMgrsOrNull().orEmpty())
            setSelection(text?.length ?: 0)
        }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.goto_coords_title)
            .setMessage(R.string.goto_coords_message)
            .setView(input)
            .setPositiveButton(R.string.btn_goto_coords) { _, _ ->
                val parsed = parseCoordinateInput(input.text?.toString().orEmpty())
                if (parsed == null) {
                    Toast.makeText(this, R.string.goto_coords_invalid, Toast.LENGTH_SHORT).show()
                } else {
                    moveMapToCoordinates(parsed.first, parsed.second)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun parseCoordinateInput(raw: String): Pair<GeoPoint, String>? {
        val input = raw.trim()
        if (input.isEmpty()) return null
        parseLatLonInput(input)?.let { return it to formatLatLonLabel(it) }
        parseMgrsInput(input)?.let { return it }
        return null
    }

    private fun parseLatLonInput(input: String): GeoPoint? {
        val commaSeparated = input.replace(';', ',').split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val parts = if (commaSeparated.size == 2) {
            commaSeparated
        } else {
            input.trim().split(Regex("\\s+"))
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        }
        if (parts.size != 2) return null
        val latitude = parts[0].replace(',', '.').toDoubleOrNull() ?: return null
        val longitude = parts[1].replace(',', '.').toDoubleOrNull() ?: return null
        if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return null
        return GeoPoint(latitude, longitude)
    }

    private fun parseMgrsInput(input: String): Pair<GeoPoint, String>? {
        val normalized = input.uppercase(Locale.US).replace(Regex("\\s+"), "")
        return runCatching {
            val mgrs = MGRS.parse(normalized)
            val point = mgrs.toPoint()
            GeoPoint(point.latitude, point.longitude) to mgrs.coordinate(mgrs.precision())
        }.getOrNull()
    }

    private fun formatLatLonLabel(point: GeoPoint): String = String.format(
        Locale.US,
        "%.6f, %.6f",
        point.latitude,
        point.longitude
    )

    private fun moveMapToCoordinates(point: GeoPoint, label: String) {
        followLocation = false
        binding.map.controller.animateTo(point)
        if (binding.map.zoomLevelDouble < 16.0) binding.map.controller.setZoom(17.0)
        Toast.makeText(this, getString(R.string.goto_coords_moved, label), Toast.LENGTH_SHORT).show()
    }

    private fun shareLocation() {
        val r = viewModel.ui.value.lastResult ?: return
        val text = getString(
            R.string.share_text,
            r.mgrs,
            String.format(Locale.US, "%.6f", r.latitude),
            String.format(Locale.US, "%.6f", r.longitude)
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.share_title)))
    }

    private fun promptSaveCurrentPoint() {
        if (viewModel.ui.value.lastResult == null) return
        val input = createWaypointNameInput()
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.save_title)
            .setView(input)
            .setPositiveButton(R.string.save_action) { _, _ ->
                viewModel.saveCurrentResult(input.text?.toString().orEmpty())
                viewModel.setWaypointsVisible(true)
                Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun promptSaveCrosshairPoint() {
        val center = currentMapCenter()
        val mgrs = MgrsFormatter.format(center.latitude, center.longitude, GridType.METER)
        val input = createWaypointNameInput()
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.crosshair_save_title)
            .setMessage(
                getString(
                    R.string.crosshair_save_message,
                    mgrs,
                    formatLatLonLabel(center)
                )
            )
            .setView(input)
            .setPositiveButton(R.string.save_action) { _, _ ->
                viewModel.savePoint(
                    name = input.text?.toString().orEmpty(),
                    latitude = center.latitude,
                    longitude = center.longitude,
                    mgrs = mgrs
                )
                viewModel.setWaypointsVisible(true)
                Toast.makeText(this, R.string.crosshair_saved, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun createWaypointNameInput(): EditText = EditText(this).apply {
        hint = getString(R.string.save_name_hint)
        setSingleLine()
    }

    private fun currentMapCenter(): GeoPoint {
        val center = binding.map.mapCenter
        return GeoPoint(center.latitude, center.longitude)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionRequest.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun precisionLabelRes(g: GridType): Int = when (g) {
        GridType.METER -> R.string.precision_1m
        GridType.TEN_METER -> R.string.precision_10m
        GridType.HUNDRED_METER -> R.string.precision_100m
        GridType.KILOMETER -> R.string.precision_1km
        GridType.TEN_KILOMETER -> R.string.precision_10km
        GridType.HUNDRED_KILOMETER -> R.string.precision_100km
        else -> R.string.precision_1m
    }

    private fun hasLocationPermission(): Boolean {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED ||
                checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun requestLocationPermissions() {
        locationPermissionRequest.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )
    }

    // ===== Трек =====

    private fun onTrackButtonClick() {
        if (!hasLocationPermission()) {
            requestLocationPermissions()
            return
        }
        requestNotificationPermissionIfNeeded()
        viewModel.toggleTracking()
        if (LocationCollector.isTracking()) {
            Toast.makeText(this, R.string.track_started, Toast.LENGTH_SHORT).show()
        } else {
            val n = LocationCollector.trackPointCount()
            Toast.makeText(
                this, getString(R.string.track_stopped, n), Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun promptExportTrack() {
        if (LocationCollector.isTracking()) {
            Toast.makeText(this, R.string.track_stop_first, Toast.LENGTH_SHORT).show()
            return
        }
        val n = LocationCollector.trackPointCount()
        if (n < 2) {
            Toast.makeText(this, R.string.track_too_short, Toast.LENGTH_SHORT).show()
            return
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.track_export_title)
            .setMessage(getString(R.string.track_export_msg, n))
            .setPositiveButton(R.string.track_export_save) { _, _ ->
                val ts = java.text.SimpleDateFormat("yyyyMMdd-HHmm", Locale.US)
                    .format(java.util.Date())
                createTrackGpx.launch("track-$ts.gpx")
            }
            .setNeutralButton(R.string.track_clear) { _, _ ->
                viewModel.clearTrack()
                Toast.makeText(this, R.string.track_cleared, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun writeTrackToUri(uri: android.net.Uri) {
        val pts = viewModel.snapshotTrack()
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val gpx = com.example.mgrskor.export.GpxExporter.buildTrackGpx(pts)
                    contentResolver.openOutputStream(uri, "w")?.use {
                        it.write(gpx.toByteArray())
                    } ?: error("openOutputStream returned null")
                }.isSuccess
            }
            Toast.makeText(
                this@MainActivity,
                if (ok) R.string.export_done else R.string.export_failed,
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    override fun onResume() {
        super.onResume()
        binding.map.onResume()
        if (hasLocationPermission()) {
            viewModel.startGnssMonitoring()
            viewModel.startPrewarm()
        }
        viewModel.startSensors()
    }

    override fun onPause() {
        super.onPause()
        binding.map.onPause()
        viewModel.stopGnssMonitoring()
        viewModel.stopPrewarm()
        viewModel.stopSensors()
        // ВАЖЛИВО: збір НЕ зупиняється — він живе у viewModelScope і виживає поворот.
        // Якщо потрібен фоновий збір при заблокованому екрані — Foreground Service (Раунд 2).
    }
}
