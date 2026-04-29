package com.guardian.shield.data.local.db

import androidx.room.*
import com.guardian.shield.domain.model.BlockSchedule
import kotlinx.coroutines.flow.Flow

@Dao
interface AppRuleDao {

    @Query("SELECT * FROM app_rules WHERE isBlocked = 1")
    fun observeBlockedApps(): Flow<List<AppRuleEntity>>

    @Query("SELECT * FROM app_rules WHERE isWhitelisted = 1")
    fun observeWhitelistedApps(): Flow<List<AppRuleEntity>>

    @Query("SELECT * FROM app_rules")
    fun observeAllRules(): Flow<List<AppRuleEntity>>

    @Query("SELECT * FROM app_rules WHERE packageName = :pkg LIMIT 1")
    suspend fun getRule(pkg: String): AppRuleEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: AppRuleEntity)

    @Delete
    suspend fun delete(entity: AppRuleEntity)

    @Query("DELETE FROM app_rules WHERE packageName = :pkg")
    suspend fun deleteByPackage(pkg: String)

    @Query("SELECT packageName FROM app_rules WHERE isBlocked = 1")
    suspend fun getBlockedPackages(): List<String>

    @Query("SELECT packageName FROM app_rules WHERE isWhitelisted = 1")
    suspend fun getWhitelistedPackages(): List<String>
}

@Dao
interface KeywordRuleDao {

    @Query("SELECT * FROM keyword_rules ORDER BY addedAt DESC")
    fun observeAll(): Flow<List<KeywordRuleEntity>>

    @Query("SELECT keyword FROM keyword_rules WHERE isActive = 1")
    suspend fun getActiveKeywords(): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: KeywordRuleEntity): Long

    @Query("UPDATE keyword_rules SET isActive = :active WHERE id = :id")
    suspend fun setActive(id: Long, active: Boolean)

    @Query("DELETE FROM keyword_rules WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT COUNT(*) FROM keyword_rules WHERE isActive = 1")
    suspend fun getActiveCount(): Int
}

@Dao
interface BlockEventDao {

    @Query("SELECT * FROM block_events ORDER BY timestamp DESC LIMIT 100")
    fun observeRecent(): Flow<List<BlockEventEntity>>

    @Query("SELECT * FROM block_events ORDER BY timestamp DESC")
    fun getAllFlow(): Flow<List<BlockEventEntity>>

    @Query("SELECT * FROM block_events WHERE timestamp BETWEEN :start AND :end ORDER BY timestamp DESC")
    fun getEventsBetween(start: Long, end: Long): Flow<List<BlockEventEntity>>

    @Query("SELECT * FROM block_events WHERE timestamp BETWEEN :start AND :end")
    suspend fun getEventsBetweenSync(start: Long, end: Long): List<BlockEventEntity>

    @Insert
    suspend fun insert(entity: BlockEventEntity)

    @Query("SELECT COUNT(*) FROM block_events")
    suspend fun getTotalCount(): Int

    @Query("SELECT COUNT(*) FROM block_events WHERE timestamp >= :startOfDay")
    suspend fun getTodayCount(startOfDay: Long): Int

    @Query("SELECT * FROM block_events ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatest(): BlockEventEntity?

    @Query("DELETE FROM block_events WHERE timestamp < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long)

    @Query("DELETE FROM block_events")
    suspend fun deleteAll()
}

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

    @Query("SELECT COUNT(*) FROM block_schedules")
    suspend fun getCount(): Int
}