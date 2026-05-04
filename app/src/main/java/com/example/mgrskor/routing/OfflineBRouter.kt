package com.example.mgrskor.routing

import android.content.Context
import android.util.Log
import com.example.mgrskor.routing.Routing.HintKind
import com.example.mgrskor.routing.Routing.Profile
import com.example.mgrskor.routing.Routing.RoutingException
import org.osmdroid.util.GeoPoint
import java.io.File
import java.io.FileOutputStream

/**
 * Повний офлайн-роутинг через вбудоване ядро BRouter (GPLv3).
 *
 * Ядро завантажується **рефлексивно**, щоб застосунок збирався і працював навіть
 * без JAR-ів у `app/libs/`. Якщо ядро відсутнє — `isCoreAvailable()` поверне
 * false, і UI повинен запропонувати або режим "online", або встановити BRouter
 * app через intent.
 *
 * При наявності ядра вимагає три речі:
 *  1) `assets/brouter/lookups.dat` (+ `profiles2/<profile>.brf`) — копіюються
 *     у `filesDir/brouter/` при першому виклику;
 *  2) сегменти `<E|W><lon>_<N|S><lat>.rd5` у
 *     `filesDir/brouter/segments4/` (завантажуються окремо через
 *     [SegmentDownloader]);
 *  3) валідні координати "from"/"to".
 */
object OfflineBRouter {

    private const val TAG = "OfflineBRouter"
    private const val ENGINE_CLASS = "btools.router.RoutingEngine"
    private const val WAYPOINT_CLASS = "btools.router.OsmNodeNamed"
    private const val TRACK_CLASS = "btools.router.OsmTrack"

    /** Чи присутнє ядро у classpath (тобто JAR-и підкладені у app/libs/). */
    fun isCoreAvailable(): Boolean = runCatching {
        Class.forName(ENGINE_CLASS, false, OfflineBRouter::class.java.classLoader)
        true
    }.getOrDefault(false)

    /** Каталог-корінь BRouter у внутрішньому сховищі застосунку. */
    fun rootDir(context: Context): File =
        File(context.filesDir, "brouter").apply { mkdirs() }

    fun segmentsDir(context: Context): File =
        File(rootDir(context), "segments4").apply { mkdirs() }

    fun profilesDir(context: Context): File =
        File(rootDir(context), "profiles2").apply { mkdirs() }

    fun lookupsFile(context: Context): File =
        File(rootDir(context), "lookups.dat")

    /** Копіює `assets/brouter/...` у `filesDir/brouter/...`, якщо ще не скопійовано. */
    fun bootstrap(context: Context) {
        val am = context.assets
        // lookups.dat
        val lookups = lookupsFile(context)
        if (!lookups.exists()) copyAssetIfExists(context, "brouter/lookups.dat", lookups)
        // profiles2/*.brf
        val profilesAssetDir = "brouter/profiles2"
        val profileFiles = runCatching { am.list(profilesAssetDir) ?: emptyArray() }
            .getOrDefault(emptyArray())
        for (name in profileFiles) {
            val target = File(profilesDir(context), name)
            if (!target.exists()) copyAssetIfExists(context, "$profilesAssetDir/$name", target)
        }
    }

    private fun copyAssetIfExists(context: Context, assetPath: String, target: File) {
        try {
            context.assets.open(assetPath).use { input ->
                FileOutputStream(target).use { out -> input.copyTo(out) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Asset $assetPath не знайдено / помилка копіювання: ${e.message}")
        }
    }

    /** Перевіряє, що всі необхідні файли на місці перед обчисленням маршруту. */
    fun checkReady(context: Context, profile: Profile): ReadyState {
        if (!isCoreAvailable()) return ReadyState.NO_CORE
        bootstrap(context)
        if (!lookupsFile(context).exists()) return ReadyState.NO_LOOKUPS
        val pf = File(profilesDir(context), "${profile.id}.brf")
        if (!pf.exists()) return ReadyState.NO_PROFILE
        if (segmentsDir(context).listFiles { f -> f.name.endsWith(".rd5") }
                .isNullOrEmpty()) return ReadyState.NO_SEGMENTS
        return ReadyState.READY
    }

    enum class ReadyState { READY, NO_CORE, NO_LOOKUPS, NO_PROFILE, NO_SEGMENTS }

    /**
     * Обчислює маршрут локально. Кидає [RoutingException] у разі помилки.
     * Виконуйте у IO-диспетчері.
     */
    @Throws(RoutingException::class)
    fun computeRoute(
        context: Context,
        from: GeoPoint,
        to: GeoPoint,
        profile: Profile
    ): Routing.Route {
        when (checkReady(context, profile)) {
            ReadyState.NO_CORE -> throw RoutingException("BRouter-ядро не вбудовано (немає JAR у app/libs/)")
            ReadyState.NO_LOOKUPS -> throw RoutingException("Не знайдено assets/brouter/lookups.dat")
            ReadyState.NO_PROFILE -> throw RoutingException("Немає профілю ${profile.id}.brf")
            ReadyState.NO_SEGMENTS -> throw RoutingException("Немає rd5-сегментів для цього регіону. Завантажте їх.")
            ReadyState.READY -> {}
        }

        return try {
            invokeEngine(context, from, to, profile)
        } catch (e: RoutingException) {
            throw e
        } catch (e: Throwable) {
            throw RoutingException("Помилка офлайн-ядра: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun invokeEngine(
        context: Context,
        from: GeoPoint,
        to: GeoPoint,
        profile: Profile
    ): Routing.Route {
        val cl = OfflineBRouter::class.java.classLoader!!
        val wpClass = Class.forName(WAYPOINT_CLASS, true, cl)
        val engineClass = Class.forName(ENGINE_CLASS, true, cl)

        // Створюємо waypoint-и (OsmNodeNamed має поля ilon/ilat у форматі microdeg+180/+90)
        val wp1 = wpClass.getDeclaredConstructor().newInstance()
        val wp2 = wpClass.getDeclaredConstructor().newInstance()
        wpClass.getField("ilon").setInt(wp1, lonToILon(from.longitude))
        wpClass.getField("ilat").setInt(wp1, latToILat(from.latitude))
        wpClass.getField("name")?.set(wp1, "from")
        wpClass.getField("ilon").setInt(wp2, lonToILon(to.longitude))
        wpClass.getField("ilat").setInt(wp2, latToILat(to.latitude))
        wpClass.getField("name")?.set(wp2, "to")

        val waypoints = java.util.ArrayList<Any>().apply { add(wp1); add(wp2) }

        // Конструктор RoutingEngine(String tracksDir, String logFile, String segmentDir,
        //                          List<OsmNodeNamed> waypoints, RoutingContext rc)
        val rcClass = Class.forName("btools.router.RoutingContext", true, cl)
        val rc = rcClass.getDeclaredConstructor().newInstance()
        // rc.localFunction = шлях до .brf
        rcClass.getField("localFunction").set(
            rc,
            File(profilesDir(context), "${profile.id}.brf").absolutePath
        )

        val engineCtor = engineClass.getDeclaredConstructor(
            String::class.java, String::class.java, String::class.java,
            java.util.List::class.java, rcClass
        )
        val tracksDir = File(rootDir(context), "tracks").apply { mkdirs() }.absolutePath
        val engine = engineCtor.newInstance(
            tracksDir,
            null,
            segmentsDir(context).absolutePath,
            waypoints,
            rc
        )

        // engine.doRun(0)
        engineClass.getMethod("doRun", Long::class.javaPrimitiveType).invoke(engine, 0L)

        // engine.getFoundTrack() : OsmTrack
        val track = engineClass.getMethod("getFoundTrack").invoke(engine)
            ?: throw RoutingException("BRouter не побудував маршрут (getFoundTrack=null)")

        return parseTrack(track, profile)
    }

    private fun parseTrack(track: Any, profile: Profile): Routing.Route {
        val cl = OfflineBRouter::class.java.classLoader!!
        val trackClass = Class.forName(TRACK_CLASS, true, cl)
        val nodes = trackClass.getField("nodes").get(track) as java.util.List<*>

        val pts = ArrayList<GeoPoint>(nodes.size)
        for (n in nodes) {
            val ilon = n!!.javaClass.getField("ilon").getInt(n)
            val ilat = n.javaClass.getField("ilat").getInt(n)
            pts.add(GeoPoint(iLatToLat(ilat), iLonToLon(ilon)))
        }

        val distance = runCatching {
            trackClass.getField("distance").getInt(track).toDouble()
        }.getOrElse {
            // fallback: підрахуємо самі
            var d = 0.0
            for (i in 1 until pts.size) d += pts[i - 1].distanceToAsDouble(pts[i])
            d
        }
        val durationSec = runCatching {
            trackClass.getField("energy").getInt(track).toDouble() // приблизна оцінка
        }.getOrElse { distance / 1.4 }

        // voicehints — додаткова інформація з ядра
        val hints = parseVoiceHintsReflective(track)

        return Routing.Route(
            points = pts,
            distanceMeters = distance,
            durationSeconds = durationSec,
            profile = profile,
            hints = hints
        )
    }

    private fun parseVoiceHintsReflective(track: Any): List<Routing.Hint> {
        return try {
            val voiceList = track.javaClass.getField("voiceHints").get(track) ?: return emptyList()
            // VoiceHintList.list : ArrayList<VoiceHint>
            val list = voiceList.javaClass.getField("list").get(voiceList) as? List<*>
                ?: return emptyList()
            list.mapNotNull { vh ->
                if (vh == null) return@mapNotNull null
                val cmd = runCatching { vh.javaClass.getField("cmd").getInt(vh) }.getOrDefault(0)
                val ix = runCatching { vh.javaClass.getField("indexInTrack").getInt(vh) }
                    .getOrDefault(0)
                val dist = runCatching { vh.javaClass.getField("distanceToNext").getDouble(vh) }
                    .getOrDefault(0.0)
                Routing.Hint(ix, HintKind.fromCommand(cmd), dist)
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // BRouter використовує ціле представлення координат: ilon = (lon+180)*1e6,
    // ilat = (lat+90)*1e6.
    private fun lonToILon(lon: Double): Int = ((lon + 180.0) * 1_000_000.0).toInt()
    private fun latToILat(lat: Double): Int = ((lat + 90.0) * 1_000_000.0).toInt()
    private fun iLonToLon(i: Int): Double = i / 1_000_000.0 - 180.0
    private fun iLatToLat(i: Int): Double = i / 1_000_000.0 - 90.0
}
