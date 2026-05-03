package com.example.mgrskor.mgrs

import mil.nga.grid.features.Point
import mil.nga.mgrs.MGRS
import mil.nga.mgrs.grid.GridType
import kotlin.math.max

/**
 * Безпечна обгортка над `mil.nga:mgrs`. Уся бізнес-логіка форматування
 * координат у форматі MGRS — у одному місці. Жодних залежностей від Android.
 */
object MgrsFormatter {

    /**
     * Конвертує координати WGS84 у MGRS-рядок.
     * Параметри названі явно — захист від класичної помилки переплутати lat/lon.
     */
    fun format(latitude: Double, longitude: Double, gridType: GridType): String {
        // ВАЖЛИВО: бібліотека nga.grid очікує (longitude, latitude), а не навпаки.
        val mgrs = MGRS.from(Point.point(longitude, latitude))
        return mgrs.coordinate(gridType)
    }

    /**
     * Підбирає GridType відповідно до 1σ-точності GPS, щоб не показувати
     * фальшиві цифри. Наприклад, при ±30 м немає сенсу показувати 5-значні
     * easting/northing — тільки 3 цифри (100 м).
     */
    fun chooseGridType(accuracyMeters: Float): GridType {
        if (accuracyMeters.isNaN() || accuracyMeters <= 0f) return GridType.METER
        val a = max(accuracyMeters, 0.5f)
        return when {
            a < 1f -> GridType.METER
            a < 10f -> GridType.TEN_METER
            a < 100f -> GridType.HUNDRED_METER
            a < 1000f -> GridType.KILOMETER
            a < 10000f -> GridType.TEN_KILOMETER
            else -> GridType.HUNDRED_KILOMETER
        }
    }
}
