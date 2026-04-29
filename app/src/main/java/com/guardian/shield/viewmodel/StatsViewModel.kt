package com.guardian.shield.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.guardian.shield.data.repository.StatsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class StatsViewModel @Inject constructor(
    private val statsRepo: StatsRepository
) : ViewModel() {

    val todayStats = statsRepo.getTodayStats().stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), null
    )

    val weeklyStats = statsRepo.getWeeklyStats().stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), null
    )

    val monthlyStats = statsRepo.getMonthlyStats().stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), null
    )

    val streakDays = statsRepo.getStreakDays().stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), 0
    )
}