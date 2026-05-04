package com.example.mgrskor.routing

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.example.mgrskor.export.GpxExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.util.GeoPoint
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Прокладання маршруту по дорогах OpenStreetMap (включно з ґрунтовими/польовими).
 *
 * Працює з [BRouter](https://brouter.de/) — open-source маршрутизатором,
 * орієнтованим на outdoor-навігацію. Серверні профілі типу `car-fast`,
 * `trekking`, `hiking-mountain`, `shortest` охоче ведуть по `highway=track`
 * (польові, лісові), `unclassified`, стежках тощо.
 *
 * Два режими (контролюються викликачем):
 *  - **Online**: публічний BRouter API `https://brouter.de/brouter`, без ключа.
 *  - **Offline**: локально встановлений застосунок BRouter (`btools.routingapp`)
 *    із завантаженими rd5-сегментами на регіон. Доступний через intent.
 */
object Routing {

    enum class Profile(val id: String, val displayName: String) {
        CAR_FAST("car-fast", "Авто (усі дороги)"),
        TREKKING("trekking", "Велосипед / треки"),
        SHORTEST("shortest", "Найкоротший"),
        HIKING("hiking-beta", "Пішки")
    }

    /** Розшифровані команди поворотів (BRouter VoiceHint). */
    enum class HintKind(val ua: String) {
        STRAIGHT("прямо"),
        TURN_LEFT("ліворуч"),
        TURN_RIGHT("праворуч"),
        SHARP_LEFT("різко ліворуч"),
        SHARP_RIGHT("різко праворуч"),
        SLIGHT_LEFT("трохи ліворуч"),
        SLIGHT_RIGHT("трохи праворуч"),
        KEEP_LEFT("тримайтеся ліворуч"),
        KEEP_RIGHT("тримайтеся праворуч"),
        U_TURN("розворот"),
        ROUNDABOUT_LEFT("кільце ліворуч"),
        ROUNDABOUT_RIGHT("кільце праворуч"),
        OFF_ROUTE("поза маршрутом"),
        ARRIVE("прибули");
        companion object {
            fun fromCommand(id: Int): HintKind = when (id) {
                1 -> TURN_LEFT
                2 -> SLIGHT_LEFT
                3 -> SHARP_LEFT
                4 -> TURN_RIGHT
                5 -> SLIGHT_RIGHT
                6 -> SHARP_RIGHT
                7 -> KEEP_LEFT
                8 -> KEEP_RIGHT
                9 -> U_TURN
                10 -> ROUNDABOUT_LEFT
                11 -> ROUNDABOUT_RIGHT
                12 -> OFF_ROUTE
                13, 14 -> ARRIVE
                else -> STRAIGHT
            }
        }
    }

    /** Один підказник: на якій точці маршруту, який маневр. */
    data class Hint(
        val pointIndex: Int,
        val kind: HintKind,
        /** Орієнтовна відстань ДО цього маневру від попереднього (м). */
        val distanceMeters: Double
    )

    data class Route(
        val points: List<GeoPoint>,
        val distanceMeters: Double,
        val durationSeconds: Double,
        val profile: Profile,
        val hints: List<Hint>
    )

    class RoutingException(message: String, cause: Throwable? = null) : IOException(message, cause)

    // ---- Online -------------------------------------------------------------

    suspend fun computeRouteOnline(from: GeoPoint, to: GeoPoint, profile: Profile): Route =
        withContext(Dispatchers.IO) {
            val url = URL(
                "https://brouter.de/brouter" +
                    "?lonlats=${from.longitude},${from.latitude}|${to.longitude},${to.latitude}" +
                    "&profile=${profile.id}" +
                    "&alternativeidx=0&format=geojson"
            )
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 45_000
                requestMethod = "GET"
                setRequestProperty("Accept", "application/geo+json,application/json")
                setRequestProperty("User-Agent", "mgrs_kor/1.0 (Android)")
            }
            try {
                val code = conn.responseCode
                if (code !in 200..299) {
                    val err = runCatching {
                        conn.errorStream?.bufferedReader()?.use { it.readText() }
                    }.getOrNull().orEmpty()
                    throw RoutingException("BRouter HTTP $code: ${err.take(200).ifBlank { "немає деталей" }}")
                }
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                parseGeoJson(text, profile)
            } catch (e: RoutingException) {
                throw e
            } catch (e: Throwable) {
                throw RoutingException("Не вдалося отримати маршрут: ${e.message ?: e.javaClass.simpleName}", e)
            } finally {
                runCatching { conn.disconnect() }
            }
        }

    // ---- Offline (BRouter app) ---------------------------------------------

    /** Чи встановлено офлайн-застосунок BRouter (`btools.routingapp`). */
    fun isOfflineAvailable(context: Context): Boolean = runCatching {
        context.packageManager.getPackageInfo("btools.routingapp", 0)
        true
    }.getOrDefault(false)

    /**
     * Запускає офлайн-застосунок BRouter для розрахунку маршруту між двома точками.
     * Це викличе UI BRouter; результат користувач експортує/побачить там само.
     *
     * Повноцінна інтеграція «маршрут назад у наш UI» через intent у нинішніх
     * версіях BRouter обмежена; натомість ми передаємо координати і даємо
     * користувачу швидкий доступ. Якщо потрібен 100% безшовний режим —
     * рекомендується використовувати онлайн.
     */
    fun launchOfflineApp(context: Context, from: GeoPoint, to: GeoPoint, profile: Profile) {
        val params = android.os.Bundle().apply {
            putDoubleArray("lats", doubleArrayOf(from.latitude, to.latitude))
            putDoubleArray("lons", doubleArrayOf(from.longitude, to.longitude))
            putString("v", profile.id)
            putString("fast", "1")
            putString("trackFormat", "gpx")
            putString("turnInstructionMode", "9")
        }
        val intent = Intent("btools.routingapp.BR_REQUEST").apply {
            setPackage("btools.routingapp")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra("params", params)
        }
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            // На деяких збірках BRouter це broadcast.
            try { context.sendBroadcast(intent) } catch (_: Throwable) { /* no-op */ }
        }
    }

    /** Відкриває Play Store / сторінку BRouter для встановлення офлайн-застосунку. */
    fun openOfflineInstall(context: Context) {
        val playUri = Uri.parse("market://details?id=btools.routingapp")
        val intent = Intent(Intent.ACTION_VIEW, playUri)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            context.startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://brouter.de/brouter/offline.html")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    // ---- Offline (вбудоване BRouter-ядро) ----------------------------------

    /**
     * Чи присутнє BRouter-ядро у classpath (JAR-и в `app/libs/`)
     * для повного офлайн-роутингу з малюванням у нашому UI.
     */
    fun isOfflineCoreAvailable(): Boolean = OfflineBRouter.isCoreAvailable()

    /**
     * Обчислює маршрут локально через вбудоване BRouter-ядро.
     * Виконується у IO-диспетчері. Повертає таку ж [Route], що й онлайн-режим,
     * — її можна одразу малювати, експортувати в GPX і дивитися turn-by-turn.
     */
    suspend fun computeRouteOfflineCore(
        context: Context,
        from: GeoPoint,
        to: GeoPoint,
        profile: Profile
    ): Route = withContext(Dispatchers.IO) {
        OfflineBRouter.computeRoute(context, from, to, profile)
    }


    // ---- GeoJSON ------------------------------------------------------------

    private fun parseGeoJson(text: String, profile: Profile): Route {
        val root = JSONObject(text)
        val features = root.optJSONArray("features")
            ?: throw RoutingException("Маршрут не повернуто (немає features)")
        if (features.length() == 0) throw RoutingException("Маршрут не знайдено")
        val feature = features.getJSONObject(0)
        val geom = feature.optJSONObject("geometry")
            ?: throw RoutingException("Невалідний GeoJSON (геометрія)")
        val coords = geom.optJSONArray("coordinates")
            ?: throw RoutingException("Невалідний GeoJSON (координати)")
        val points = ArrayList<GeoPoint>(coords.length())
        for (i in 0 until coords.length()) {
            val pt = coords.getJSONArray(i)
            // [lon, lat, alt?]
            points.add(GeoPoint(pt.getDouble(1), pt.getDouble(0)))
        }
        if (points.size < 2) throw RoutingException("Маршрут порожній")
        val props = feature.optJSONObject("properties") ?: JSONObject()
        val dist = props.optString("track-length", "0").toDoubleOrNull() ?: 0.0
        val time = props.optString("total-time", "0").toDoubleOrNull() ?: 0.0
        val hints = parseVoiceHints(props.optJSONArray("voicehints"))
        return Route(points, dist, time, profile, hints)
    }

    private fun parseVoiceHints(arr: JSONArray?): List<Hint> {
        if (arr == null) return emptyList()
        val out = ArrayList<Hint>(arr.length())
        for (i in 0 until arr.length()) {
            val a = arr.optJSONArray(i) ?: continue
            // BRouter format: [indexInTrack, commandId, exitNumber, distance, angle]
            if (a.length() < 4) continue
            val idx = a.optInt(0, -1)
            val cmd = a.optInt(1, 0)
            val dist = a.optDouble(3, 0.0)
            if (idx < 0) continue
            out.add(Hint(idx, HintKind.fromCommand(cmd), dist))
        }
        return out
    }

    // ---- GPX ----------------------------------------------------------------

    /** Експорт маршруту як GPX-route (`<rte>`). */
    fun routeToGpx(route: Route, name: String = "MGRS Kor route"): String {
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val sb = StringBuilder(256 + route.points.size * 80)
        sb.append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        sb.append(
            """<gpx version="1.1" creator="MGRS Kor" xmlns="http://www.topografix.com/GPX/1/1">"""
        ).append('\n')
        sb.append("  <metadata>\n")
        sb.append("    <name>").append(GpxExporter.escapeXml(name)).append("</name>\n")
        sb.append("    <time>").append(iso.format(Date())).append("</time>\n")
        sb.append("    <desc>profile=").append(route.profile.id)
            .append(", length=").append(String.format(Locale.US, "%.0f", route.distanceMeters))
            .append(" m, time=").append(String.format(Locale.US, "%.0f", route.durationSeconds))
            .append(" s</desc>\n")
        sb.append("  </metadata>\n")
        sb.append("  <rte>\n")
        sb.append("    <name>").append(GpxExporter.escapeXml(name)).append("</name>\n")
        for (p in route.points) {
            sb.append("    <rtept lat=\"")
                .append(GpxExporter.formatCoord(p.latitude))
                .append("\" lon=\"")
                .append(GpxExporter.formatCoord(p.longitude))
                .append("\" />\n")
        }
        sb.append("  </rte>\n")
        sb.append("</gpx>\n")
        return sb.toString()
    }
}
