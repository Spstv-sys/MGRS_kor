package com.example.mgrskor.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Метадані про збережений офлайн-регіон карти.
 *
 * Самі плитки зберігаються у спільному osmdroid-кеші
 * ([org.osmdroid.config.Configuration.osmdroidTileCache]).
 * Цей запис описує лише, які плитки належать цьому «регіону»
 * (bounding box × діапазон зумів × перелік джерел тайлів),
 * щоб згодом можна було вибірково видалити їх або заново довантажити.
 */
@Entity(tableName = "offline_regions")
data class OfflineRegion(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,

    // Прямокутник у градусах WGS84.
    val north: Double,
    val south: Double,
    val east: Double,
    val west: Double,

    val zoomMin: Int,
    val zoomMax: Int,

    /** CSV з імен джерел тайлів (`ITileSource.name()`), напр. "Mapnik,ESRI-WorldImagery". */
    val sources: String,

    /** Орієнтовна кількість плиток у регіоні (сума по всіх джерелах і зумах). */
    val tileCount: Long,

    /** Орієнтовний розмір на диску в байтах (на момент завантаження). */
    val sizeBytesEstimate: Long,

    /** Час створення (epoch ms). */
    val createdAtMs: Long
)
