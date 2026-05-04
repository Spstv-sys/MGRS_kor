package com.example.mgrskor.map

import android.content.Context
import android.text.format.Formatter
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.example.mgrskor.data.AppDatabase
import com.example.mgrskor.data.OfflineRegion
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.slider.Slider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.tileprovider.MapTileProviderBasic
import org.osmdroid.tileprovider.cachemanager.CacheManager
import org.osmdroid.tileprovider.modules.SqlTileWriter
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.views.MapView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Завантаження офлайн-плиток osmdroid для видимої області.
 *
 * Можливості:
 *  - Вибір глибини зуму +N до поточного через slider.
 *  - Опція «усі шари» (OSM + ESRI Imagery + ESRI Reference) одним прогоном.
 *  - Підрахунок приблизного розміру у МБ та підтвердження.
 *  - Сучасний Material-діалог із [LinearProgressIndicator].
 *  - Перегляд та очищення кешу з фактичним розміром на диску.
 *
 * Плитки кешуються у стандартному osmdroid-кеші
 * (`Configuration.osmdroidTileCache`, налаштованому на 500 МБ у MainActivity).
 */
object OfflineTiles {

    /** Орієнтовний середній розмір однієї плитки. */
    private const val AVG_TILE_BYTES = 18_000L // ~18 КБ (PNG/JPG 256x256, в середньому)

    /** Жорсткий «верхній стелі»: захист від випадкового завантаження пів-світу. */
    private const val HARD_TILE_LIMIT = 250_000

    /** Максимальна додаткова глибина зуму, яку можна обрати слайдером. */
    private const val MAX_EXTRA_ZOOM = 5

    fun promptDownloadVisibleArea(context: Context, map: MapView) {
        val zoomMin = map.zoomLevelDouble.toInt().coerceAtLeast(1)
        val maxZoomCap = map.maxZoomLevel.toInt()
        val box = map.boundingBox

        val padding = (16 * context.resources.displayMetrics.density).toInt()
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding / 2, padding, 0)
        }
        val defaultName = "Регіон " + SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            .format(Date())
        val etName = EditText(context).apply {
            hint = "Назва регіону"
            setText(defaultName)
            setSingleLine(true)
        }
        val tvSummary = TextView(context).apply {
            setPadding(0, padding / 2, 0, padding / 2)
        }
        val cbAllLayers = android.widget.CheckBox(context).apply {
            text = "Усі шари (OSM + Супутник + Підписи)"
            isChecked = false
        }
        val tvSlider = TextView(context).apply {
            setPadding(0, padding / 2, 0, 0)
        }
        val slider = Slider(context).apply {
            valueFrom = 0f
            valueTo = MAX_EXTRA_ZOOM.toFloat()
            stepSize = 1f
            value = 2f
        }
        container.addView(etName)
        container.addView(tvSummary)
        container.addView(cbAllLayers)
        container.addView(tvSlider)
        container.addView(slider)

        fun recalc() {
            val extra = slider.value.toInt()
            val zoomMax = (zoomMin + extra).coerceAtMost(maxZoomCap)
            val sourcesCount = if (cbAllLayers.isChecked) 3 else 1

            val perSource = totalTilesInArea(map, box, zoomMin, zoomMax)
            val totalTiles = perSource * sourcesCount
            val approxBytes = totalTiles * AVG_TILE_BYTES
            tvSlider.text = "Глибина зуму: $zoomMin..$zoomMax (поточний +$extra)"
            tvSummary.text = buildString {
                append("Видима область, плиток ≈$totalTiles")
                if (sourcesCount > 1) append(" (×$sourcesCount шари)")
                append("\nРозмір ≈ ")
                append(Formatter.formatShortFileSize(context, approxBytes))
            }
        }
        slider.addOnChangeListener { _, _, _ -> recalc() }
        cbAllLayers.setOnCheckedChangeListener { _, _ -> recalc() }
        recalc()

        MaterialAlertDialogBuilder(context)
            .setTitle("Завантажити офлайн-карту")
            .setView(container)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton("Завантажити") { _, _ ->
                val extra = slider.value.toInt()
                val zoomMax = (zoomMin + extra).coerceAtMost(maxZoomCap)
                val sources = if (cbAllLayers.isChecked) selectedTileSources(allLayers = true)
                else listOf(map.tileProvider.tileSource)

                val totalTiles = totalTilesInArea(map, box, zoomMin, zoomMax) * sources.size
                if (totalTiles > HARD_TILE_LIMIT) {
                    MaterialAlertDialogBuilder(context)
                        .setTitle("Зашироко")
                        .setMessage(
                            "Видимий прямокутник вимагатиме ≈$totalTiles плиток (ліміт " +
                                "$HARD_TILE_LIMIT). Зменшіть видиму область або глибину зуму."
                        )
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                    return@setPositiveButton
                }
                val regionName = etName.text?.toString()?.trim().orEmpty()
                    .ifEmpty { defaultName }
                downloadAreaMultiSource(
                    context, map, box, zoomMin, zoomMax, sources,
                    regionName = regionName,
                    estimatedTiles = totalTiles.toLong(),
                    estimatedBytes = totalTiles.toLong() * AVG_TILE_BYTES
                )
            }
            .show()
    }

    private fun totalTilesInArea(
        map: MapView,
        box: BoundingBox,
        zoomMin: Int,
        zoomMax: Int
    ): Long {
        val cm = CacheManager(map)
        return (zoomMin..zoomMax).sumOf { z -> cm.possibleTilesInArea(box, z, z).toLong() }
    }

    private fun selectedTileSources(allLayers: Boolean): List<ITileSource> {
        if (!allLayers) return listOf(TileSourceFactory.MAPNIK)
        return listOf(
            TileSourceFactory.MAPNIK,
            MapLayers.esriImagery(),
            MapLayers.esriLabels()
        )
    }

    /**
     * Послідовно викачує плитки для усіх обраних джерел.
     * Кожне джерело — окремий [CacheManager] на тимчасовому [MapTileProviderBasic],
     * щоб не змінювати поточний tileSource карти.
     */
    private fun downloadAreaMultiSource(
        context: Context,
        map: MapView,
        box: BoundingBox,
        zoomMin: Int,
        zoomMax: Int,
        sources: List<ITileSource>,
        regionName: String,
        estimatedTiles: Long,
        estimatedBytes: Long
    ) {
        val padding = (16 * context.resources.displayMetrics.density).toInt()
        val view = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, 0)
        }
        val tvStatus = TextView(context)
        val progress = LinearProgressIndicator(context).apply {
            isIndeterminate = false
            max = 100
        }
        view.addView(tvStatus)
        view.addView(progress)

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle("Завантаження плиток…")
            .setView(view)
            .setCancelable(false)
            .setNegativeButton("Сховати", null)
            .create()
        dialog.show()

        var sourceIdx = 0
        var sourceTotal = 0
        var failedTotal = 0

        fun runNext() {
            if (sourceIdx >= sources.size) {
                dialog.dismiss()
                // Записуємо метадані регіону у БД (для подальшого керування / видалення).
                CoroutineScope(Dispatchers.IO).launch {
                    runCatching {
                        OfflineRegions.save(
                            context, regionName, box, zoomMin, zoomMax, sources,
                            tileCount = estimatedTiles,
                            sizeBytesEstimate = estimatedBytes
                        )
                    }
                }
                Toast.makeText(
                    context,
                    if (failedTotal == 0) "Готово, регіон «$regionName» збережено"
                    else "Завершено з помилками: $failedTotal",
                    Toast.LENGTH_LONG
                ).show()
                return
            }
            val source = sources[sourceIdx]
            val tempProvider = MapTileProviderBasic(context.applicationContext, source)
            val cm = CacheManager(tempProvider, SqlTileWriter(), zoomMin, zoomMax)
            sourceTotal = 0

            val cb = object : CacheManager.CacheManagerCallback {
                override fun onTaskComplete() {
                    sourceIdx++
                    runNext()
                }
                override fun onTaskFailed(errors: Int) {
                    failedTotal += errors
                    sourceIdx++
                    runNext()
                }
                override fun updateProgress(
                    progressValue: Int,
                    currentZoomLevel: Int,
                    zoomMinV: Int,
                    zoomMaxV: Int
                ) {
                    progress.max = sourceTotal.coerceAtLeast(1)
                    progress.setProgressCompat(progressValue, true)
                    tvStatus.text = "Шар ${sourceIdx + 1}/${sources.size}: ${source.name()}\n" +
                        "zoom $currentZoomLevel · $progressValue/$sourceTotal"
                }
                override fun downloadStarted() { /* no-op */ }
                override fun setPossibleTilesInArea(total: Int) {
                    sourceTotal = total
                    progress.max = total.coerceAtLeast(1)
                }
            }
            cm.downloadAreaAsync(context, box, zoomMin, zoomMax, cb)
        }
        runNext()
    }

    /**
     * Менеджер збережених регіонів: показує список з назвою, датою, розміром,
     * діапазоном зумів і дозволяє видалити плитки конкретного регіону.
     */
    fun promptManageRegions(context: Context, map: MapView) {
        CoroutineScope(Dispatchers.Main).launch {
            val dao = AppDatabase.get(context).offlineRegionDao()
            val regions = withContext(Dispatchers.IO) { dao.listAll() }
            if (regions.isEmpty()) {
                MaterialAlertDialogBuilder(context)
                    .setTitle("Офлайн-регіони")
                    .setMessage(
                        "Немає збережених регіонів. Виберіть «Завантажити офлайн-карту» — " +
                            "і вкажіть назву."
                    )
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
                return@launch
            }
            val df = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            val labels = regions.map { r ->
                val size = Formatter.formatShortFileSize(context, r.sizeBytesEstimate)
                val srcCount = r.sources.split(',').size
                "${r.name}\n${df.format(Date(r.createdAtMs))} · z${r.zoomMin}–${r.zoomMax} · " +
                    "≈$size · $srcCount шар(и) · ${r.tileCount} плиток"
            }.toTypedArray()
            MaterialAlertDialogBuilder(context)
                .setTitle("Офлайн-регіони")
                .setItems(labels) { _, idx ->
                    val region = regions[idx]
                    showRegionActions(context, map, region)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun showRegionActions(context: Context, map: MapView, region: OfflineRegion) {
        val sizeText = Formatter.formatShortFileSize(context, region.sizeBytesEstimate)
        MaterialAlertDialogBuilder(context)
            .setTitle(region.name)
            .setMessage(
                "Зум: ${region.zoomMin}–${region.zoomMax}\n" +
                    "Плиток: ${region.tileCount} (≈$sizeText)\n" +
                    "Шари: ${region.sources}"
            )
            .setNeutralButton("Показати на карті") { _, _ ->
                val box = BoundingBox(region.north, region.east, region.south, region.west)
                map.zoomToBoundingBox(box, true, 64)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton("Видалити") { _, _ ->
                MaterialAlertDialogBuilder(context)
                    .setTitle("Видалити регіон?")
                    .setMessage(
                        "Буде видалено плитки в межах прямокутника для цих шарів. " +
                            "Якщо плитки використовуються іншим регіоном — їх можна буде " +
                            "перезавантажити."
                    )
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        CoroutineScope(Dispatchers.IO).launch {
                            runCatching { OfflineRegions.delete(context, region) }
                            withContext(Dispatchers.Main) {
                                Toast.makeText(
                                    context,
                                    "Регіон «${region.name}» видалено",
                                    Toast.LENGTH_SHORT
                                ).show()
                                map.invalidate()
                            }
                        }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
            .show()
    }

    /** Показує фактичний розмір кешу й пропонує очистити. */
    fun promptClearCache(context: Context, map: MapView) {
        val cacheDir: File? = org.osmdroid.config.Configuration.getInstance().osmdroidTileCache
        val sizeText = try {
            val bytes = cacheDir?.let { dirSizeBytes(it) } ?: 0L
            Formatter.formatShortFileSize(context, bytes)
        } catch (_: Throwable) { "—" }

        MaterialAlertDialogBuilder(context)
            .setTitle("Очистити кеш карт?")
            .setMessage("Поточний розмір: $sizeText\nВидалить усі завантажені офлайн-плитки.")
            .setPositiveButton(android.R.string.ok) { _, _ ->
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        if (cacheDir != null && cacheDir.exists()) {
                            cacheDir.deleteRecursively()
                            cacheDir.mkdirs()
                        }
                    } catch (_: Throwable) { /* no-op */ }
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Кеш очищено", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun dirSizeBytes(dir: File): Long {
        var size = 0L
        dir.walkTopDown().forEach { f -> if (f.isFile) size += f.length() }
        return size
    }
}
