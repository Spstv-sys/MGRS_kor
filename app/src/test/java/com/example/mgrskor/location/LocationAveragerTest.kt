package com.example.mgrskor.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationAveragerTest {

    private fun s(lat: Double, lon: Double, acc: Double) =
        LocationAverager.Sample(lat, lon, acc)

    @Test
    fun `single sample returns itself`() {
        val r = LocationAverager.weightedAverageWithOutlierRejection(
            listOf(s(50.0, 30.0, 5.0))
        )
        assertEquals(50.0, r.latitude, 1e-9)
        assertEquals(30.0, r.longitude, 1e-9)
        assertEquals(1, r.samplesUsed)
    }

    @Test
    fun `equal weight samples produce arithmetic mean`() {
        val list = listOf(
            s(50.0001, 30.0, 5.0),
            s(50.0000, 30.0, 5.0),
            s(49.9999, 30.0, 5.0)
        )
        val r = LocationAverager.weightedAverageWithOutlierRejection(list)
        assertEquals(50.0000, r.latitude, 1e-6)
    }

    @Test
    fun `more accurate sample dominates the average`() {
        // 9 неточних точок (σ=50м) і 1 точна (σ=1м)
        val noisy = (1..9).map { s(50.001 + it * 0.0001, 30.0, 50.0) }
        val precise = s(50.0, 30.0, 1.0)
        val r = LocationAverager.weightedAverageWithOutlierRejection(noisy + precise)
        // Точна (50.0) має домінувати завдяки w=1/1²=1 vs w=1/2500=0.0004
        assertEquals(50.0, r.latitude, 1e-3)
    }

    @Test
    fun `outliers beyond 2 sigma are rejected`() {
        // Кластер 10 точок навколо (50.0, 30.0)
        val cluster = (0..9).map {
            s(50.0 + (it - 5) * 0.000005, 30.0 + (it - 5) * 0.000005, 5.0)
        }
        // Один сильний викид ~500 м осторонь
        val outlier = s(50.005, 30.005, 5.0)
        val r = LocationAverager.weightedAverageWithOutlierRejection(cluster + outlier)
        // Викид має бути відкинутий → результат близько до 50.0
        assertTrue("викид НЕ відкинутий: lat=${r.latitude}", r.latitude < 50.0001)
        assertEquals(cluster.size, r.samplesUsed)
    }

    @Test
    fun `sigma decreases when more samples added`() {
        val one = LocationAverager.weightedAverageWithOutlierRejection(
            listOf(s(50.0, 30.0, 5.0))
        )
        val many = LocationAverager.weightedAverageWithOutlierRejection(
            (0..19).map { s(50.0, 30.0, 5.0) }
        )
        assertTrue(
            "σ при N=20 (${many.sigmaMeters}) має бути < σ при N=1 (${one.sigmaMeters})",
            many.sigmaMeters < one.sigmaMeters
        )
    }

    @Test
    fun `haversine kyiv to lviv approximately 470km`() {
        // Київ ~ 50.45/30.52, Львів ~ 49.84/24.03
        val d = LocationAverager.haversineMeters(50.45, 30.52, 49.84, 24.03)
        assertEquals(470_000.0, d, 5_000.0)
    }
}
