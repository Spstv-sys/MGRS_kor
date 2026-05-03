package com.example.mgrskor.sensors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AltimeterTest {

    // Передаємо p0 явно, щоб не залежати від Android-константи
    private val p0 = 1013.25f

    @Test
    fun `pressure equal to sea level gives altitude zero`() {
        assertEquals(0.0, Altimeter.altitudeMeters(p0, p0), 0.5)
    }

    @Test
    fun `lower pressure gives positive altitude`() {
        // ~900 hPa ≈ 990 м (приблизно)
        val h = Altimeter.altitudeMeters(900f, p0)
        assertTrue("h=$h", h in 900.0..1100.0)
    }

    @Test
    fun `higher pressure gives negative altitude`() {
        val h = Altimeter.altitudeMeters(1030f, p0)
        assertTrue("h=$h", h < 0.0)
    }

    @Test
    fun `calibration round-trip recovers known altitude`() {
        // Спочатку при p0=1013.25 і p=900 отримуємо якусь висоту H
        val knownH = Altimeter.altitudeMeters(900f, p0) // ~990 м
        // Калібруємо новий p0 за поточним тиском 900 і відомою висотою knownH
        val newP0 = Altimeter.calibrateSeaLevelPressure(900f, knownH)
        // Тоді з новим p0 та тим самим тиском маємо знову knownH
        val recoveredH = Altimeter.altitudeMeters(900f, newP0)
        assertEquals(knownH, recoveredH, 0.5)
    }

    @Test
    fun `extreme values do not throw`() {
        // Дуже високо в горах
        Altimeter.altitudeMeters(500f, p0)
        // На рівні моря у тропічному циклоні
        Altimeter.altitudeMeters(950f, p0)
    }
}
