package com.example.mgrskor.routing

import android.content.Context
import org.osmdroid.util.BoundingBox
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.floor

/**
 * Завантажує rd5-сегменти BRouter (5°×5° тайли) з brouter.de
 * у `filesDir/brouter/segments4/`.
 *
 * Імена файлів: `<E|W><lon>_<N|S><lat>.rd5`, координати — нижній-лівий кут
 * 5°×5° блока, кратні 5 (наприклад `E25_N50.rd5` покриває lon 25..30, lat 50..55).
 */
object SegmentDownloader {

    private const val BASE_URL = "https://brouter.de/brouter/segments4/"

    data class Segment(val lon: Int, val lat: Int) {
        fun fileName(): String {
            val ew = if (lon >= 0) "E" else "W"
            val ns = if (lat >= 0) "N" else "S"
            return "$ew${kotlin.math.abs(lon)}_$ns${kotlin.math.abs(lat)}.rd5"
        }
    }

    fun listSegmentsForBox(box: BoundingBox): List<Segment> {
        val out = LinkedHashSet<Segment>()
        val lonMin = floorTo5(box.lonWest)
        val lonMax = floorTo5(box.lonEast)
        val latMin = floorTo5(box.latSouth)
        val latMax = floorTo5(box.latNorth)
        var lo = lonMin
        while (lo <= lonMax) {
            var la = latMin
            while (la <= latMax) {
                out.add(Segment(lo, la))
                la += 5
            }
            lo += 5
        }
        return out.toList()
    }

    private fun floorTo5(v: Double): Int = (floor(v / 5.0) * 5).toInt()

    sealed class Result {
        data class Ok(val downloaded: Int, val skipped: Int, val totalBytes: Long) : Result()
        data class Failed(val message: String, val partial: Int) : Result()
    }

    /**
     * Завантажує всі сегменти, які перетинають [box]. Викликайте з IO-диспетчера.
     * @param progress (current, total, segmentName) — викликається у тому ж потоці.
     */
    fun download(
        context: Context,
        box: BoundingBox,
        progress: (Int, Int, String) -> Unit = { _, _, _ -> }
    ): Result {
        val segs = listSegmentsForBox(box)
        if (segs.isEmpty()) return Result.Failed("Порожній bbox", 0)
        val dir = OfflineBRouter.segmentsDir(context)
        var downloaded = 0
        var skipped = 0
        var totalBytes = 0L
        for ((idx, s) in segs.withIndex()) {
            val name = s.fileName()
            progress(idx + 1, segs.size, name)
            val target = File(dir, name)
            if (target.exists() && target.length() > 0) {
                skipped++
                continue
            }
            val url = URL(BASE_URL + name)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 60_000
                requestMethod = "GET"
                setRequestProperty("User-Agent", "MgrsKor/1.0 (offline-brouter)")
            }
            try {
                val code = conn.responseCode
                if (code == 404) {
                    // Сегмент не існує (океан / нема даних) — пропускаємо.
                    skipped++
                    continue
                }
                if (code !in 200..299) {
                    return Result.Failed("HTTP $code для $name", downloaded)
                }
                val tmp = File(dir, "$name.part")
                conn.inputStream.use { input ->
                    FileOutputStream(tmp).use { out ->
                        totalBytes += input.copyTo(out)
                    }
                }
                if (!tmp.renameTo(target)) {
                    tmp.delete()
                    return Result.Failed("Не вдалося зберегти $name", downloaded)
                }
                downloaded++
            } catch (e: Exception) {
                return Result.Failed("${e.javaClass.simpleName}: ${e.message}", downloaded)
            } finally {
                conn.disconnect()
            }
        }
        return Result.Ok(downloaded, skipped, totalBytes)
    }
}
