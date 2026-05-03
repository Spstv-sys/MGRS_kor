package com.example.mgrskor.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Збережена точка маршруту (waypoint).
 * Зберігаємо «сирий» lat/lon WGS84 + готовий MGRS,
 * щоб не виконувати конвертацію при кожному рендері списку.
 */
@Entity(tableName = "saved_points")
data class SavedPoint(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val mgrs: String,
    /** 1σ-точність у метрах на момент збереження. */
    val accuracyMeters: Float,
    val satellitesUsed: Int,
    val avgCn0: Float,
    /** Час збереження (epoch ms). */
    val timestampMs: Long,
    val note: String? = null
)
