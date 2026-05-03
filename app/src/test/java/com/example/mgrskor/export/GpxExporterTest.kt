package com.example.mgrskor.export

import com.example.mgrskor.data.SavedPoint
import com.example.mgrskor.location.LocationCollector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GpxExporterTest {

    private fun pt(
        id: Long = 1, name: String = "WP-1",
        lat: Double = 50.0, lon: Double = 30.0,
        mgrs: String = "36UYA1234567890",
        acc: Float = 5f, sats: Int = 12, cn0: Float = 35f,
        ts: Long = 1_700_000_000_000L, note: String? = null
    ) = SavedPoint(id, name, lat, lon, mgrs, acc, sats, cn0, ts, note)

    @Test
    fun `header and root element are valid GPX 1_1`() {
        val gpx = GpxExporter.buildWaypointsGpx(listOf(pt()))
        assertTrue(gpx.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"))
        assertTrue(gpx.contains("""<gpx version="1.1""""))
        assertTrue(gpx.contains("""xmlns="http://www.topografix.com/GPX/1/1""""))
        assertTrue(gpx.endsWith("</gpx>\n"))
    }

    @Test
    fun `each point produces one wpt with lat lon`() {
        val gpx = GpxExporter.buildWaypointsGpx(listOf(pt(), pt(id = 2, lat = 49.5, lon = 25.1)))
        val count = "<wpt ".toRegex().findAll(gpx).count()
        assertEquals(2, count)
        assertTrue(gpx.contains("lat=\"50.0000000\""))
        assertTrue(gpx.contains("lon=\"30.0000000\""))
        assertTrue(gpx.contains("lat=\"49.5000000\""))
    }

    @Test
    fun `xml special chars in name are escaped`() {
        val gpx = GpxExporter.buildWaypointsGpx(listOf(pt(name = "Test <foo> & \"bar\"")))
        assertTrue(gpx.contains("Test &lt;foo&gt; &amp; &quot;bar&quot;"))
        assertFalse(gpx.contains("<foo>"))
    }

    @Test
    fun `note is included as cmt only if present`() {
        val withNote = GpxExporter.buildWaypointsGpx(listOf(pt(note = "склад")))
        val noNote = GpxExporter.buildWaypointsGpx(listOf(pt(note = null)))
        assertTrue(withNote.contains("<cmt>склад</cmt>"))
        assertFalse(noNote.contains("<cmt>"))
    }

    @Test
    fun `coord formatting uses dot decimal`() {
        // не залежить від локалі (Locale.US у генераторі)
        val s = GpxExporter.formatCoord(50.123456789)
        assertEquals("50.1234568", s)
    }

    @Test
    fun `empty list still produces valid wrapper`() {
        val gpx = GpxExporter.buildWaypointsGpx(emptyList())
        assertTrue(gpx.contains("<metadata>"))
        assertEquals(0, "<wpt ".toRegex().findAll(gpx).count())
    }

    // ===== buildTrackGpx =====

    private fun tp(
        lat: Double = 50.0, lon: Double = 30.0,
        alt: Double? = 200.0, acc: Float = 6f,
        ts: Long = 1_700_000_000_000L
    ) = LocationCollector.TrackPoint(lat, lon, alt, acc, ts)

    @Test
    fun `track gpx contains trk trkseg trkpt`() {
        val gpx = GpxExporter.buildTrackGpx(listOf(tp(), tp(lat = 50.0001)))
        assertTrue(gpx.contains("<trk>"))
        assertTrue(gpx.contains("<trkseg>"))
        assertEquals(2, "<trkpt ".toRegex().findAll(gpx).count())
    }

    @Test
    fun `track gpx writes ele only if altitude present`() {
        val withEle = GpxExporter.buildTrackGpx(listOf(tp(alt = 312.7)))
        val noEle = GpxExporter.buildTrackGpx(listOf(tp(alt = null)))
        assertTrue(withEle.contains("<ele>312.7</ele>"))
        assertFalse(noEle.contains("<ele>"))
    }

    @Test
    fun `track gpx escapes track name`() {
        val gpx = GpxExporter.buildTrackGpx(listOf(tp()), trackName = "<bad>&")
        assertTrue(gpx.contains("&lt;bad&gt;&amp;"))
    }
}
