package com.example.mgrskor.geo

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Чисті геодезичні розрахунки (без Android API), щоб мати unit-тести.
 *
 * Формули — sphere-based (Haversine + initial bearing). Похибка проти WGS84-еліпсоїда
 * < 0.5% на дистанціях до сотень км — для лісової навігації цього більш ніж досить.
 */
object Geo {
    private const val R_EARTH_M = 6_371_000.0

    /** Велике-кругова відстань у метрах. */
    fun distanceMeters(
        lat1: Double, lon1: Double,
        lat2: Double, lon2: Double
    ): Double {
        val φ1 = Math.toRadians(lat1)
        val φ2 = Math.toRadians(lat2)
        val dφ = Math.toRadians(lat2 - lat1)
        val dλ = Math.toRadians(lon2 - lon1)
        val a = sin(dφ / 2).let { it * it } +
                cos(φ1) * cos(φ2) * sin(dλ / 2).let { it * it }
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return R_EARTH_M * c
    }

    /** Початковий азимут (true north) у градусах [0..360). */
    fun initialBearingDegrees(
        lat1: Double, lon1: Double,
        lat2: Double, lon2: Double
    ): Double {
        val φ1 = Math.toRadians(lat1)
        val φ2 = Math.toRadians(lat2)
        val dλ = Math.toRadians(lon2 - lon1)
        val y = sin(dλ) * cos(φ2)
        val x = cos(φ1) * sin(φ2) - sin(φ1) * cos(φ2) * cos(dλ)
        val θ = Math.toDegrees(atan2(y, x))
        return (θ + 360.0) % 360.0
    }

    /** Відносний кут «куди йти» = bearing − heading, нормалізований у [-180..180]. */
    fun relativeBearingDegrees(bearingDeg: Double, headingDeg: Double): Double {
        var d = (bearingDeg - headingDeg) % 360.0
        if (d > 180.0) d -= 360.0
        if (d < -180.0) d += 360.0
        return d
    }
}
