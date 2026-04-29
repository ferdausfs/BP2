package com.guardian.shield.domain.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import androidx.room.TypeConverters

enum class ScheduleType {
    PRAYER, SLEEP, FOCUS, CUSTOM
}

@Entity(tableName = "block_schedules")
@TypeConverters(ScheduleConverters::class)
data class BlockSchedule(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val type: ScheduleType = ScheduleType.CUSTOM,
    val enabled: Boolean = true,
    val startHour: Int,
    val startMinute: Int,
    val endHour: Int,
    val endMinute: Int,
    val daysOfWeek: Set<Int>, // 1=Sunday..7=Saturday
    val blockAllApps: Boolean = false,
    val blockedApps: Set<String> = emptySet(),
    val createdAt: Long = System.currentTimeMillis()
)

class ScheduleConverters {
    @TypeConverter
    fun fromIntSet(set: Set<Int>): String = set.joinToString(",")

    @TypeConverter
    fun toIntSet(value: String): Set<Int> =
        if (value.isBlank()) emptySet()
        else value.split(",").mapNotNull { it.toIntOrNull() }.toSet()

    @TypeConverter
    fun fromStringSet(set: Set<String>): String = set.joinToString("|")

    @TypeConverter
    fun toStringSet(value: String): Set<String> =
        if (value.isBlank()) emptySet()
        else value.split("|").toSet()

    @TypeConverter
    fun fromScheduleType(type: ScheduleType): String = type.name

    @TypeConverter
    fun toScheduleType(value: String): ScheduleType =
        try { ScheduleType.valueOf(value) } catch (e: Exception) { ScheduleType.CUSTOM }
}