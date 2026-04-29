package com.guardian.shield.data.local.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.guardian.shield.domain.model.BlockSchedule
import com.guardian.shield.domain.model.ScheduleConverters

@Database(
    entities = [
        AppRuleEntity::class,
        KeywordRuleEntity::class,
        BlockEventEntity::class,
        BlockSchedule::class
    ],
    version = 2,
    exportSchema = false
)
@TypeConverters(ScheduleConverters::class)
abstract class GuardianDatabase : RoomDatabase() {
    abstract fun appRuleDao(): AppRuleDao
    abstract fun keywordRuleDao(): KeywordRuleDao
    abstract fun blockEventDao(): BlockEventDao
    abstract fun scheduleDao(): ScheduleDao
}