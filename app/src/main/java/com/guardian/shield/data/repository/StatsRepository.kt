package com.guardian.shield.data.repository

import com.guardian.shield.data.local.db.BlockEventDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StatsRepository @Inject constructor(
    private val blockEventDao: BlockEventDao
) {
    fun getTodayStats(): Flow<DayStats> {
        val (start, end) = getTodayRange()
        return blockEventDao.getEventsBetween(start, end).map { events ->
            DayStats(
                date = start,
                totalBlocks = events.size,
                uniqueApps = events.map { it.packageName }.distinct().size,
                topApp = events.groupingBy { it.packageName }.eachCount()
                    .maxByOrNull { it.value }?.key ?: "",
                topReason = events.groupingBy { it.reason }.eachCount()
                    .maxByOrNull { it.value }?.key ?: "",
                events = events
            )
        }
    }

    fun getWeeklyStats(): Flow<WeeklyStats> {
        val (start, end) = getWeekRange()
        return blockEventDao.getEventsBetween(start, end).map { events ->
            val daily = mutableMapOf<Int, Int>()
            val cal = Calendar.getInstance()
            
            events.forEach { event ->
                cal.timeInMillis = event.timestamp
                val dayOfWeek = cal.get(Calendar.DAY_OF_WEEK)
                daily[dayOfWeek] = (daily[dayOfWeek] ?: 0) + 1
            }

            WeeklyStats(
                totalBlocks = events.size,
                dailyBreakdown = daily,
                topApps = events.groupingBy { it.packageName }
                    .eachCount()
                    .toList()
                    .sortedByDescending { it.second }
                    .take(5),
                topKeywords = events.filter { it.reason.startsWith("Keyword:") }
                    .groupingBy { it.reason.removePrefix("Keyword:").trim() }
                    .eachCount()
                    .toList()
                    .sortedByDescending { it.second }
                    .take(10),
                averagePerDay = events.size / 7
            )
        }
    }

    fun getMonthlyStats(): Flow<MonthlyStats> {
        val (start, end) = getMonthRange()
        return blockEventDao.getEventsBetween(start, end).map { events ->
            MonthlyStats(
                totalBlocks = events.size,
                uniqueApps = events.map { it.packageName }.distinct().size,
                averagePerDay = events.size / 30,
                streak = calculateStreak(events.map { it.timestamp })
            )
        }
    }

    fun getStreakDays(): Flow<Int> = blockEventDao.getAllFlow().map { events ->
        calculateStreak(events.map { it.timestamp })
    }

    private fun calculateStreak(timestamps: List<Long>): Int {
        if (timestamps.isEmpty()) return 0
        
        val cal = Calendar.getInstance()
        val today = cal.apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val daysWithActivity = timestamps.map { ts ->
            cal.timeInMillis = ts
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            cal.timeInMillis
        }.toSet()

        var streak = 0
        var checkDay = today
        while (daysWithActivity.contains(checkDay)) {
            streak++
            checkDay -= 24 * 60 * 60 * 1000L
        }
        return streak
    }

    private fun getTodayRange(): Pair<Long, Long> {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val start = cal.timeInMillis
        val end = start + 24 * 60 * 60 * 1000L - 1
        return start to end
    }

    private fun getWeekRange(): Pair<Long, Long> {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val end = cal.timeInMillis + 24 * 60 * 60 * 1000L - 1
        val start = end - 7 * 24 * 60 * 60 * 1000L
        return start to end
    }

    private fun getMonthRange(): Pair<Long, Long> {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val end = cal.timeInMillis + 24 * 60 * 60 * 1000L - 1
        val start = end - 30 * 24 * 60 * 60 * 1000L
        return start to end
    }

    data class DayStats(
        val date: Long,
        val totalBlocks: Int,
        val uniqueApps: Int,
        val topApp: String,
        val topReason: String,
        val events: List<com.guardian.shield.domain.model.BlockEvent>
    )

    data class WeeklyStats(
        val totalBlocks: Int,
        val dailyBreakdown: Map<Int, Int>,
        val topApps: List<Pair<String, Int>>,
        val topKeywords: List<Pair<String, Int>>,
        val averagePerDay: Int
    )

    data class MonthlyStats(
        val totalBlocks: Int,
        val uniqueApps: Int,
        val averagePerDay: Int,
        val streak: Int
    )
}