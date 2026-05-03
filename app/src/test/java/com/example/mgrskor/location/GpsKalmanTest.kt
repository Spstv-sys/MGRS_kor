package com.example.mgrskor.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GpsKalmanTest {

    @Test
    fun `first update initializes state to that location`() {
        val k = GpsKalman(processNoiseMps = 0.5f)
        k.update(50.4501, 30.5234, accuracyMeters = 5.0, timeMs = 1000)
        assertEquals(50.4501, k.latitude, 1e-9)
        assertEquals(30.5234, k.longitude, 1e-9)
        assertEquals(5.0, k.sigmaMeters, 1e-9)
    }

    @Test
    fun `repeated identical measurements reduce variance`() {
        val k = GpsKalman(processNoiseMps = 0.0f) // прибираємо процесний шум для чистоти
        var t = 1000L
        repeat(20) {
            k.update(50.0, 30.0, accuracyMeters = 5.0, timeMs = t)
            t += 1000
        }
        // 20 однакових вимірів по σ=5 → σ_середнього = 5/√20 ≈ 1.12
        assertTrue("σ повинна суттєво впасти, було ${k.sigmaMeters}", k.sigmaMeters < 1.5)
    }

    @Test
    fun `noisy measurements converge towards mean`() {
        val k = GpsKalman(processNoiseMps = 0.0f)
        val noisy = listOf(50.0001, 49.9999, 50.0002, 49.9998, 50.0001, 49.9999, 50.0000)
        var t = 1000L
        for (lat in noisy) {
            k.update(lat, 30.0, accuracyMeters = 5.0, timeMs = t)
            t += 1000
        }
        // Очікуємо збіжність до ~50.0 (середнє вибірки)
        assertEquals(50.0, k.latitude, 1e-3)
    }

    @Test
    fun `process noise prevents variance collapse during motion`() {
        // Спочатку — багато статичних вимірів, σ має дуже впасти
        val still = GpsKalman(processNoiseMps = 0.0f)
        var t = 0L
        repeat(50) { still.update(50.0, 30.0, 5.0, t); t += 1000 }
        // Тепер — той самий сценарій, але з рухом 5 м/с
        val moving = GpsKalman(processNoiseMps = 5.0f)
        t = 0L
        repeat(50) { moving.update(50.0, 30.0, 5.0, t); t += 1000 }
        // У "moving" σ має бути більшою — модель не дозволяє довіряти старим точкам
        assertTrue(
            "moving σ=${moving.sigmaMeters} має бути > still σ=${still.sigmaMeters}",
            moving.sigmaMeters > still.sigmaMeters
        )
    }

    @Test
    fun `reset clears state`() {
        val k = GpsKalman()
        k.update(50.0, 30.0, 5.0, 0)
        assertTrue(k.isInitialized)
        k.reset()
        assertTrue(!k.isInitialized)
    }
}
