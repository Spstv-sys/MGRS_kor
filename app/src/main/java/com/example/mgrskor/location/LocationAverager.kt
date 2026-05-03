package com.example.mgrskor.location

import kotlin.math.cos
import kotlin.math.sqrt
import kotlin.math.PI

/**
 * Чиста математика усереднення GPS-точок: жодного Android API,
 * щоб логіку можна було покрити unit-тестами на JVM.
 */
object LocationAverager {

    /** Один зразок для усереднення. */
    data class Sample(
        val latitude: Double,
        val longitude: Double,
        /** 1σ-точність вимірювання у метрах (>0). */
        val accuracyMeters: Double
    )

    /** Результат усереднення. */
    data class Result(
        val latitude: Double,
        val longitude: Double,
        /** Оцінена 1σ-точність зваженого середнього у метрах. */
        val sigmaMeters: Double,
        /** Скільки точок реально потрапило у фінальне середнє після фільтрації. */
        val samplesUsed: Int
    )

    /**
     * Зважене середнє координат за вагами 1/σ² з відкиданням викидів за правилом 2σ
     * (точки, що віддалені від чорнового центру далі ніж mean+2·sd — викидаються).
     *
     * @param samples список вимірів. Має бути ≥ 1.
     * @param outlierSigma поріг відкидання, у одиницях стандартного відхилення.
     */
    fun weightedAverageWithOutlierRejection(
        samples: List<Sample>,
        outlierSigma: Double = 2.0
    ): Result {
        require(samples.isNotEmpty()) { "samples must not be empty" }

        val rough = weightedMean(samples)
        if (samples.size < 3) {
            // Замало для статистичного відсіювання — повертаємо як є.
            return Result(rough.lat, rough.lon, rough.sigma(), samples.size)
        }

        // Відстань від чорнового центру в метрах (haversine достатньо для GPS-розкиду <1 км).
        val distances = samples.map { haversineMeters(rough.lat, rough.lon, it.latitude, it.longitude) }
        val mean = distances.average()
        val sd = sqrt(distances.map { (it - mean) * (it - mean) }.average())
        val threshold = mean + outlierSigma * sd
        val filtered = samples.filterIndexed { i, _ -> distances[i] <= threshold }
            .ifEmpty { samples }

        val finalMean = weightedMean(filtered)
        return Result(finalMean.lat, finalMean.lon, finalMean.sigma(), filtered.size)
    }

    private data class Mean(val lat: Double, val lon: Double, val wSum: Double) {
        fun sigma(): Double = if (wSum > 0) 1.0 / sqrt(wSum) else Double.NaN
    }

    private fun weightedMean(list: List<Sample>): Mean {
        var wSum = 0.0; var lat = 0.0; var lon = 0.0
        for (s in list) {
            val sigma = s.accuracyMeters.coerceAtLeast(1.0)
            val w = 1.0 / (sigma * sigma)
            wSum += w; lat += s.latitude * w; lon += s.longitude * w
        }
        return Mean(lat / wSum, lon / wSum, wSum)
    }

    /** Велика дуга на сфері Землі, R ≈ 6371000 м — точно достатньо для розкидів десятків метрів. */
    internal fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = (lat2 - lat1) * PI / 180.0
        val dLon = (lon2 - lon1) * PI / 180.0
        val a = kotlin.math.sin(dLat / 2).let { it * it } +
                cos(lat1 * PI / 180.0) * cos(lat2 * PI / 180.0) *
                kotlin.math.sin(dLon / 2).let { it * it }
        val c = 2 * kotlin.math.atan2(sqrt(a), sqrt(1 - a))
        return r * c
    }
}
