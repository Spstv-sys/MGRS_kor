package com.example.mgrskor.map

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import com.example.mgrskor.R
import org.osmdroid.tileprovider.MapTileProviderBasic
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.TilesOverlay

/**
 * Доступні шари карти.
 *
 * - [OSM] — OpenStreetMap Mapnik. Має назви населених пунктів українською.
 * - [TOPO] — OpenTopoMap. Топографія з горизонталями, рельєфом і стежками—
 *   ідеально для лісу/гір. Обмеження зуму: 1–17.
 * - [SATELLITE] — ESRI World Imagery. Без підписів.
 * - [HYBRID] — ESRI World Imagery + накладений шар назв («Reference»),
 *   зі станами/містами/селами латиницею та частково кирилицею.
 *
 * Усі джерела безкоштовні для використання у польових/некомерційних застосунках,
 * але кожне має свої умови (Attribution).
 */
enum class MapLayer { OSM, TOPO, SATELLITE, HYBRID }

object MapLayers {

    private const val PREFS = "map_layers"
    private const val KEY_LAYER = "current"

    // ---- ESRI World Imagery (супутник) ---------------------------------------
    private val ESRI_IMAGERY: OnlineTileSourceBase = object : XYTileSource(
        "ESRI-WorldImagery",
        1, 19, 256, ".jpg",
        arrayOf("https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/"),
        "© Esri, Maxar, Earthstar Geographics"
    ) {
        override fun getTileURLString(pMapTileIndex: Long): String {
            // ESRI використовує z/y/x порядок
            val z = MapTileIndex.getZoom(pMapTileIndex)
            val x = MapTileIndex.getX(pMapTileIndex)
            val y = MapTileIndex.getY(pMapTileIndex)
            return baseUrl + z + "/" + y + "/" + x
        }
    }

    // ---- ESRI Reference (підписи: міста / села / межі) -----------------------
    private val ESRI_LABELS: OnlineTileSourceBase = object : XYTileSource(
        "ESRI-Reference",
        1, 19, 256, ".png",
        arrayOf("https://server.arcgisonline.com/ArcGIS/rest/services/Reference/World_Boundaries_and_Places/MapServer/tile/"),
        "© Esri"
    ) {
        override fun getTileURLString(pMapTileIndex: Long): String {
            val z = MapTileIndex.getZoom(pMapTileIndex)
            val x = MapTileIndex.getX(pMapTileIndex)
            val y = MapTileIndex.getY(pMapTileIndex)
            return baseUrl + z + "/" + y + "/" + x
        }
    }

    // ---- OpenTopoMap (топографія, горизонталі, рельєф) ------------------------
    // Сервер підтримує зум до 17. Потребує attribution «© OpenTopoMap (CC-BY-SA)».
    private val OPENTOPO: OnlineTileSourceBase = XYTileSource(
        "OpenTopoMap",
        1, 17, 256, ".png",
        arrayOf(
            "https://a.tile.opentopomap.org/",
            "https://b.tile.opentopomap.org/",
            "https://c.tile.opentopomap.org/"
        ),
        "© OpenTopoMap (CC-BY-SA), © OpenStreetMap contributors"
    )

    /** Накладений шар підписів — зберігаємо посилання, щоб можна було видаляти. */
    private var labelsOverlay: TilesOverlay? = null

    /** Доступ до тайл-джерел для офлайн-кешування (див. [OfflineTiles]). */
    fun esriImagery(): OnlineTileSourceBase = ESRI_IMAGERY
    fun esriLabels(): OnlineTileSourceBase = ESRI_LABELS

    fun load(context: Context): MapLayer {
        val prefs = prefs(context)
        return runCatching { MapLayer.valueOf(prefs.getString(KEY_LAYER, MapLayer.OSM.name)!!) }
            .getOrDefault(MapLayer.OSM)
    }

    fun save(context: Context, layer: MapLayer) {
        prefs(context).edit().putString(KEY_LAYER, layer.name).apply()
    }

    /**
     * Застосовує шар до карти. Видаляє попередній labels-overlay (якщо був),
     * виставляє нове джерело тайлів і за потреби додає overlay із підписами.
     *
     * Безпечно викликати багаторазово.
     */
    fun apply(context: Context, map: MapView, layer: MapLayer) {
        // Прибрати старий шар підписів
        labelsOverlay?.let { map.overlays.remove(it) }
        labelsOverlay = null

        when (layer) {
            MapLayer.OSM -> {
                map.setTileSource(TileSourceFactory.MAPNIK)
            }
            MapLayer.TOPO -> {
                map.setTileSource(OPENTOPO)
            }
            MapLayer.SATELLITE -> {
                map.setTileSource(ESRI_IMAGERY)
            }
            MapLayer.HYBRID -> {
                map.setTileSource(ESRI_IMAGERY)
                val provider = MapTileProviderBasic(context.applicationContext, ESRI_LABELS)
                val overlay = TilesOverlay(provider, context.applicationContext).apply {
                    loadingBackgroundColor = Color.TRANSPARENT
                    loadingLineColor = Color.TRANSPARENT
                }
                // Підписи додаємо ПЕРЕД folder’ом точок, щоб маркери та polyline
                // лишались зверху. Якщо overlay’ів ще нема — просто додаємо.
                val firstFolderIdx = map.overlays.indexOfFirst {
                    it is org.osmdroid.views.overlay.FolderOverlay ||
                    it is org.osmdroid.views.overlay.Polyline ||
                    it is org.osmdroid.views.overlay.Marker
                }
                if (firstFolderIdx >= 0) map.overlays.add(firstFolderIdx, overlay)
                else map.overlays.add(overlay)
                labelsOverlay = overlay
            }
        }
        map.invalidate()
    }

    /** Назва шару для tooltip / Toast. */
    fun displayName(layer: MapLayer): String = when (layer) {
        MapLayer.OSM -> "OSM (з назвами)"
        MapLayer.TOPO -> "Топо (OpenTopoMap)"
        MapLayer.SATELLITE -> "Супутник"
        MapLayer.HYBRID -> "Супутник + назви"
    }

    /** Іконка для кнопки. */
    fun iconRes(layer: MapLayer): Int = when (layer) {
        MapLayer.OSM -> R.drawable.ic_map_24
        MapLayer.TOPO -> R.drawable.ic_terrain_24
        MapLayer.SATELLITE -> R.drawable.ic_satellite_24
        MapLayer.HYBRID -> R.drawable.ic_layers_24
    }

    fun next(layer: MapLayer): MapLayer = when (layer) {
        MapLayer.OSM -> MapLayer.TOPO
        MapLayer.TOPO -> MapLayer.SATELLITE
        MapLayer.SATELLITE -> MapLayer.HYBRID
        MapLayer.HYBRID -> MapLayer.OSM
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
