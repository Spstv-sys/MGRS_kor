package com.example.mgrskor.geo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoTest {

    // Київ (Майдан) → Львів (центр), ~470 км, азимут ~268°
    private val kyivLat = 50.4501
    private val kyivLon = 30.5234
    private val lvivLat = 49.8397
    private val lvivLon = 24.0297

    @Test
    fun `distance Kyiv to Lviv is around 470 km`() {
        val d = Geo.distanceMeters(kyivLat, kyivLon, lvivLat, lvivLon)
        assertEquals(469_000.0, d, 5_000.0)
    }

    @Test
    fun `bearing Kyiv to Lviv is roughly west`() {
        val b = Geo.initialBearingDegrees(kyivLat, kyivLon, lvivLat, lvivLon)
        assertTrue("bearing=$b", b in 260.0..275.0)
    }

    @Test
    fun `distance to same point is zero`() {
        assertEquals(0.0, Geo.distanceMeters(kyivLat, kyivLon, kyivLat, kyivLon), 0.001)
    }

    @Test
    fun `bearing is in range 0 to 360`() {
        val b1 = Geo.initialBearingDegrees(kyivLat, kyivLon, lvivLat, lvivLon)
        val b2 = Geo.initialBearingDegrees(lvivLat, lvivLon, kyivLat, kyivLon)
        assertTrue(b1 in 0.0..360.0)
        assertTrue(b2 in 0.0..360.0)
    }

    @Test
    fun `relative bearing wraps to minus 180 plus 180`() {
        assertEquals(0.0, Geo.relativeBearingDegrees(90.0, 90.0), 1e-9)
        assertEquals(90.0, Geo.relativeBearingDegrees(180.0, 90.0), 1e-9)
        // ціль на півночі, дивимось на південь → треба ліворуч (або праворуч) на 180
        val v = Geo.relativeBearingDegrees(0.0, 180.0)
        assertTrue(v == 180.0 || v == -180.0)
        // ціль на сході, дивимось на південь → треба ліворуч на 90 (= -90)
        assertEquals(-90.0, Geo.relativeBearingDegrees(90.0, 180.0), 1e-9)
        // ціль на північний-схід, дивимось на північний-захід → треба праворуч на 90
        assertEquals(90.0, Geo.relativeBearingDegrees(45.0, 315.0), 1e-9)
    }

    @Test
    fun `short distance accuracy under 1 percent`() {
        // 1 хвилина дуги по широті ≈ 1852 м
        val d = Geo.distanceMeters(50.0, 30.0, 50.0 + 1.0 / 60.0, 30.0)
        assertEquals(1852.0, d, 20.0) // ±1%
    }
}
