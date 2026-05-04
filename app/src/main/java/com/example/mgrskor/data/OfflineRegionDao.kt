package com.example.mgrskor.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface OfflineRegionDao {

    @Query("SELECT * FROM offline_regions ORDER BY createdAtMs DESC")
    fun observeAll(): Flow<List<OfflineRegion>>

    @Query("SELECT * FROM offline_regions ORDER BY createdAtMs DESC")
    suspend fun listAll(): List<OfflineRegion>

    @Query("SELECT * FROM offline_regions WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): OfflineRegion?

    @Insert
    suspend fun insert(region: OfflineRegion): Long

    @Query("DELETE FROM offline_regions WHERE id = :id")
    suspend fun deleteById(id: Long)
}
