package com.example.mgrskor.export

import com.example.mgrskor.data.SavedPoint
import com.example.mgrskor.location.LocationCollector
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Чистий Kotlin-генератор GPX 1.1. Без Android API, тестується на JVM.
 *
 * Стандарт: https://www.topografix.com/GPX/1/1/
 * Кожна збережена точка → <wpt lat="…" lon="…">…</wpt>.
 */
object GpxExporter {

    private const val CREATOR = "MGRS Kor"

    fun buildWaypointsGpx(points: List<SavedPoint>): String {
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val sb = StringBuilder(256 + points.size * 256)
        sb.append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        sb.append(
            """<gpx version="1.1" creator="$CREATOR" xmlns="http://www.topografix.com/GPX/1/1">"""
        ).append('\n')
        sb.append("  <metadata>\n")
        sb.append("    <name>MGRS Kor waypoints</name>\n")
        sb.append("    <time>").append(iso.format(Date())).append("</time>\n")
        sb.append("  </metadata>\n")

        for (p in points) {
            sb.append("  <wpt lat=\"")
                .append(formatCoord(p.latitude))
                .append("\" lon=\"")
                .append(formatCoord(p.longitude))
                .append("\">\n")
            sb.append("    <time>").append(iso.format(Date(p.timestampMs))).append("</time>\n")
            sb.append("    <name>").append(escapeXml(p.name)).append("</name>\n")
            sb.append("    <desc>")
                .append("MGRS: ").append(escapeXml(p.mgrs))
                .append(" | acc: ").append(String.format(Locale.US, "%.1f", p.accuracyMeters)).append(" m")
                .append(" | sats: ").append(p.satellitesUsed)
                .append(" | CN0avg: ").append(String.format(Locale.US, "%.0f", p.avgCn0))
                .append("</desc>\n")
            // hdop ≈ accuracyMeters/UERE; UERE для побутового GPS ~3 м.
            val hdop = (p.accuracyMeters / 3f).coerceAtLeast(0.1f)
            sb.append("    <hdop>").append(String.format(Locale.US, "%.2f", hdop)).append("</hdop>\n")
            if (!p.note.isNullOrBlank()) {
                sb.append("    <cmt>").append(escapeXml(p.note)).append("</cmt>\n")
            }
            sb.append("  </wpt>\n")
        }

        sb.append("</gpx>\n")
        return sb.toString()
    }

    /**
     * Будує GPX із одним <trk><trkseg>…</trkseg></trk>. Підходить для перегляду
     * у Garmin BaseCamp / OsmAnd / Locus / Google Earth.
     */
    fun buildTrackGpx(
        points: List<LocationCollector.TrackPoint>,
        trackName: String = "MGRS Kor track"
    ): String {
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val sb = StringBuilder(256 + points.size * 160)
        sb.append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        sb.append(
            """<gpx version="1.1" creator="$CREATOR" xmlns="http://www.topografix.com/GPX/1/1">"""
        ).append('\n')
        sb.append("  <metadata>\n")
        sb.append("    <name>").append(escapeXml(trackName)).append("</name>\n")
        sb.append("    <time>").append(iso.format(Date())).append("</time>\n")
        sb.append("  </metadata>\n")
        sb.append("  <trk>\n")
        sb.append("    <name>").append(escapeXml(trackName)).append("</name>\n")
        sb.append("    <trkseg>\n")
        for (p in points) {
            sb.append("      <trkpt lat=\"")
                .append(formatCoord(p.latitude))
                .append("\" lon=\"")
                .append(formatCoord(p.longitude))
                .append("\">\n")
            if (p.altitudeMeters != null) {
                sb.append("        <ele>")
                    .append(String.format(Locale.US, "%.1f", p.altitudeMeters))
                    .append("</ele>\n")
            }
            sb.append("        <time>").append(iso.format(Date(p.timestampMs))).append("</time>\n")
            val hdop = (p.accuracyMeters / 3f).coerceAtLeast(0.1f)
            sb.append("        <hdop>")
                .append(String.format(Locale.US, "%.2f", hdop))
                .append("</hdop>\n")
            sb.append("      </trkpt>\n")
        }
        sb.append("    </trkseg>\n")
        sb.append("  </trk>\n")
        sb.append("</gpx>\n")
        return sb.toString()
    }

    internal fun formatCoord(v: Double): String =
        String.format(Locale.US, "%.7f", v)

    internal fun escapeXml(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (c in s) when (c) {
            '&' -> sb.append("&amp;")
            '<' -> sb.append("&lt;")
            '>' -> sb.append("&gt;")
            '"' -> sb.append("&quot;")
            '\'' -> sb.append("&apos;")
            else -> sb.append(c)
        }
        return sb.toString()
    }
}
