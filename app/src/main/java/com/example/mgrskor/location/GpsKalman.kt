package com.example.mgrskor.location

import kotlin.math.sqrt

/**
 * Простий 1D-Калман незалежно по широті/довготі.
 * Без жодних Android API, тестується на JVM.
 *
 * @property processNoiseMps очікуване стандартне відхилення руху користувача (м/с).
 *   Стою → 0.2; іду → 1.5; авто → 5.
 */
class GpsKalman(var processNoiseMps: Float = 1f) {
    private var lat = 0.0
    private var lon = 0.0
    private var variance = -1.0   // м^2; <0 → ще не ініціалізовано
    private var tMs = 0L

    val isInitialized: Boolean get() = variance >= 0

    /** Поточна оцінка σ у метрах, або NaN, якщо ще немає вимірів. */
    val sigmaMeters: Double get() = if (variance >= 0) sqrt(variance) else Double.NaN

    val latitude: Double get() = lat
    val longitude: Double get() = lon

    fun reset() {
        variance = -1.0
        tMs = 0L
    }

    /**
     * Прийняти новий вимір.
     * @param latitude широта виміру
     * @param longitude довгота виміру
     * @param accuracyMeters 1σ-точність виміру в метрах (>=1)
     * @param timeMs час виміру у мс (epoch або monotonic — важлива лише різниця)
     */
    fun update(latitude: Double, longitude: Double, accuracyMeters: Double, timeMs: Long) {
        val acc = accuracyMeters.coerceAtLeast(1.0)
        if (variance < 0) {
            this.lat = latitude
            this.lon = longitude
            this.variance = acc * acc
            this.tMs = timeMs
            return
        }
        val dt = ((timeMs - tMs).coerceAtLeast(0)) / 1000.0
        variance += dt * processNoiseMps * processNoiseMps
        val k = variance / (variance + acc * acc)
        lat += k * (latitude - lat)
        lon += k * (longitude - lon)
        variance *= (1 - k)
        tMs = timeMs
    }
}
