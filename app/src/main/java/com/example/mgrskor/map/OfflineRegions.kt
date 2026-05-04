package com.example.mgrskor.map

import android.content.Context
import com.example.mgrskor.data.AppDatabase
import com.example.mgrskor.data.OfflineRegion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.osmdroid.tileprovider.modules.SqlTileWriter
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.MapTileIndex
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.tan

/**
 * Менеджер «іменованих» офлайн-регіонів карти.
 *
 * Зберігає в Room лише метадані (bounding box × діапазон зумів × джерела тайлів).
 * Самі плитки лежать у спільному osmdroid-кеші. Видалення регіону прицільно
 * вирізає з кешу плитки, що належать саме цьому прямокутнику й цим джерелам.
 *
 * Якщо два регіони перекриваються, видалення одного може зачепити плитки,
 * які теж використовуються другим — це нормально, при наступному перегляді вони
 * просто довантажаться знову (онлайн) або їх можна заново скачати.
 */
object OfflineRegions {

    /** Назва джерела → ITileSource. Підтримуються наші 3 базові онлайн-шари. */
    private fun resolveSource(name: String): ITileSource? = when (name) {
        TileSourceFactory.MAPNIK.name() -> TileSourceFactory.MAPNIK
        MapLayers.esriImagery().name() -> MapLayers.esriImagery()
        MapLayers.esriLabels().name() -> MapLayers.esriLabels()
        else -> null
    }

    fun encodeSources(sources: List<ITileSource>): String =
        sources.joinToString(",") { it.name() }

    fun decodeSources(csv: String): List<ITileSource> =
        csv.split(',').mapNotNull { resolveSource(it.trim()) }

    suspend fun save(
        context: Context,
        name: String,
        box: BoundingBox,
        zoomMin: Int,
        zoomMax: Int,
        sources: List<ITileSource>,
        tileCount: Long,
        sizeBytesEstimate: Long
    ): Long = withContext(Dispatchers.IO) {
        val dao = AppDatabase.get(context).offlineRegionDao()
        dao.insert(
            OfflineRegion(
                name = name.ifBlank { "Регіон" },
                north = box.latNorth,
                south = box.latSouth,
                east = box.lonEast,
                west = box.lonWest,
                zoomMin = zoomMin,
                zoomMax = zoomMax,
                sources = encodeSources(sources),
                tileCount = tileCount,
                sizeBytesEstimate = sizeBytesEstimate,
                createdAtMs = System.currentTimeMillis()
            )
        )
    }

    /**
     * Видаляє з osmdroid-кешу всі плитки, що належать регіону,
     * і потім — сам запис у БД.
     */
    suspend fun delete(context: Context, region: OfflineRegion) = withContext(Dispatchers.IO) {
        val sources = decodeSources(region.sources)
        if (sources.isNotEmpty()) {
            val writer = SqlTileWriter()
            try {
                for (z in region.zoomMin..region.zoomMax) {
                    val (xMin, xMax, yMin, yMax) = tileRange(
                        z, region.north, region.south, region.east, region.west
                    )
                    if (xMin > xMax || yMin > yMax) continue
                    for (x in xMin..xMax) {
                        for (y in yMin..yMax) {
                            val idx = MapTileIndex.getTileIndex(z, x, y)
                            for (src in sources) {
                                runCatching { writer.remove(src, idx) }
                            }
                        }
                    }
                }
            } finally {
                runCatching { writer.onDetach() }
            }
        }
        AppDatabase.get(context).offlineRegionDao().deleteById(region.id)
    }

    private data class TileRange(val xMin: Int, val xMax: Int, val yMin: Int, val yMax: Int)

    /**
     * Координати плиток, що покривають bounding box на зумі [z] (стандарт Web Mercator XYZ).
     */
    private fun tileRange(
        z: Int, north: Double, south: Double, east: Double, west: Double
    ): TileRange {
        val n = 1 shl z
        val xMin = floorClamp((west + 180.0) / 360.0 * n, 0, n - 1)
        val xMax = floorClamp((east + 180.0) / 360.0 * n, 0, n - 1)
        val yMin = floorClamp(latToTileY(north, n), 0, n - 1)
        val yMax = floorClamp(latToTileY(south, n), 0, n - 1)
        return TileRange(xMin, xMax, yMin, yMax)
    }

    private fun latToTileY(latDeg: Double, n: Int): Double {
        val lat = latDeg.coerceIn(-85.05112878, 85.05112878)
        val r = Math.toRadians(lat)
        return (1.0 - ln(tan(r) + 1.0 / cos(r)) / PI) / 2.0 * n
    }

    private fun floorClamp(v: Double, min: Int, max: Int): Int {
        val i = kotlin.math.floor(v).toInt()
        return if (i < min) min else if (i > max) max else i
    }
}
