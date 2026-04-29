package com.guardian.shield.data.local.db

import androidx.room.*
import com.guardian.shield.domain.model.BlockSchedule
import kotlinx.coroutines.flow.Flow

@Dao
interface ScheduleDao {
    @Query("SELECT * FROM block_schedules ORDER BY startHour, startMinute")
    fun getAllFlow(): Flow<List<BlockSchedule>>

    @Query("SELECT * FROM block_schedules WHERE enabled = 1")
    suspend fun getAllEnabled(): List<BlockSchedule>

    @Query("SELECT * FROM block_schedules WHERE id = :id")
    suspend fun getById(id: Long): BlockSchedule?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(schedule: BlockSchedule): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(schedules: List<BlockSchedule>)

    @Update
    suspend fun update(schedule: BlockSchedule)

    @Query("DELETE FROM block_schedules WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE block_schedules SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)
}