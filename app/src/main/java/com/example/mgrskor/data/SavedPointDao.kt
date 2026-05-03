package com.example.mgrskor.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SavedPointDao {

    @Query("SELECT * FROM saved_points ORDER BY timestampMs DESC")
    fun observeAll(): Flow<List<SavedPoint>>

    @Query("SELECT * FROM saved_points WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): SavedPoint?

    @Insert
    suspend fun insert(point: SavedPoint): Long

    @Update
    suspend fun update(point: SavedPoint)

    @Delete
    suspend fun delete(point: SavedPoint)

    @Query("DELETE FROM saved_points WHERE id = :id")
    suspend fun deleteById(id: Long)
}
