package com.guardian.shield.service.schedule

import com.guardian.shield.data.local.db.ScheduleDao
import com.guardian.shield.domain.model.BlockSchedule
import com.guardian.shield.domain.model.ScheduleType
import kotlinx.coroutines.flow.Flow
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ScheduleManager @Inject constructor(
    private val scheduleDao: ScheduleDao
) {
    fun getAllSchedules(): Flow<List<BlockSchedule>> = scheduleDao.getAllFlow()

    suspend fun addSchedule(schedule: BlockSchedule) {
        scheduleDao.insert(schedule)
    }

    suspend fun updateSchedule(schedule: BlockSchedule) {
        scheduleDao.update(schedule)
    }

    suspend fun deleteSchedule(id: Long) {
        scheduleDao.deleteById(id)
    }

    /**
     * Check if blocking should be active right now based on schedules
     */
    suspend fun isBlockingActiveNow(): ActiveScheduleInfo? {
        val schedules = scheduleDao.getAllEnabled()
        val now = Calendar.getInstance()
        val currentDay = now.get(Calendar.DAY_OF_WEEK) // 1=Sunday..7=Saturday
        val currentMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)

        for (schedule in schedules) {
            if (!schedule.daysOfWeek.contains(currentDay)) continue

            val startMinutes = schedule.startHour * 60 + schedule.startMinute
            val endMinutes = schedule.endHour * 60 + schedule.endMinute

            val isActive = if (endMinutes > startMinutes) {
                currentMinutes in startMinutes..endMinutes
            } else {
                // Crosses midnight
                currentMinutes >= startMinutes || currentMinutes <= endMinutes
            }

            if (isActive) {
                return ActiveScheduleInfo(
                    scheduleId = schedule.id,
                    name = schedule.name,
                    type = schedule.type,
                    blockedApps = schedule.blockedApps,
                    blockAll = schedule.blockAllApps
                )
            }
        }
        return null
    }

    /**
     * Pre-defined Islamic prayer time schedules
     */
    fun getPresetSchedules(): List<BlockSchedule> = listOf(
        BlockSchedule(
            name = "Fajr Prayer",
            type = ScheduleType.PRAYER,
            startHour = 4, startMinute = 30,
            endHour = 5, endMinute = 30,
            daysOfWeek = (1..7).toSet(),
            blockAllApps = true
        ),
        BlockSchedule(
            name = "Dhuhr Prayer",
            type = ScheduleType.PRAYER,
            startHour = 12, startMinute = 30,
            endHour = 13, endMinute = 15,
            daysOfWeek = (1..7).toSet(),
            blockAllApps = true
        ),
        BlockSchedule(
            name = "Asr Prayer",
            type = ScheduleType.PRAYER,
            startHour = 15, startMinute = 45,
            endHour = 16, endMinute = 30,
            daysOfWeek = (1..7).toSet(),
            blockAllApps = true
        ),
        BlockSchedule(
            name = "Maghrib Prayer",
            type = ScheduleType.PRAYER,
            startHour = 18, startMinute = 0,
            endHour = 18, endMinute = 45,
            daysOfWeek = (1..7).toSet(),
            blockAllApps = true
        ),
        BlockSchedule(
            name = "Isha Prayer",
            type = ScheduleType.PRAYER,
            startHour = 19, startMinute = 30,
            endHour = 20, endMinute = 15,
            daysOfWeek = (1..7).toSet(),
            blockAllApps = true
        ),
        BlockSchedule(
            name = "Sleep Time",
            type = ScheduleType.SLEEP,
            startHour = 22, startMinute = 30,
            endHour = 5, endMinute = 0,
            daysOfWeek = (1..7).toSet(),
            blockAllApps = true
        ),
        BlockSchedule(
            name = "Study/Work Hours",
            type = ScheduleType.FOCUS,
            startHour = 9, startMinute = 0,
            endHour = 17, endMinute = 0,
            daysOfWeek = setOf(2, 3, 4, 5, 6), // Mon-Fri
            blockAllApps = false,
            blockedApps = setOf(
                "com.facebook.katana",
                "com.instagram.android",
                "com.twitter.android",
                "com.zhiliaoapp.musically", // TikTok
                "com.google.android.youtube"
            )
        )
    )

    data class ActiveScheduleInfo(
        val scheduleId: Long,
        val name: String,
        val type: ScheduleType,
        val blockedApps: Set<String>,
        val blockAll: Boolean
    )
}