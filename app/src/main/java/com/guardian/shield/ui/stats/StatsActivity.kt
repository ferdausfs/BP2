package com.guardian.shield.ui.stats

import android.os.Bundle
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.guardian.shield.databinding.ActivityStatsBinding
import com.guardian.shield.viewmodel.StatsViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@AndroidEntryPoint
class StatsActivity : AppCompatActivity() {
    private lateinit var binding: ActivityStatsBinding
    private val viewModel: StatsViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStatsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.title = "Statistics"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        observeStats()
    }

    private fun observeStats() {
        lifecycleScope.launch {
            viewModel.streakDays.collectLatest { days ->
                binding.tvStreakDays.text = days.toString()
            }
        }
        lifecycleScope.launch {
            viewModel.todayStats.collectLatest { stats ->
                stats ?: return@collectLatest
                binding.tvTodayBlocks.text = stats.totalBlocks.toString()
                binding.tvTodayApps.text = stats.uniqueApps.toString()
            }
        }
        lifecycleScope.launch {
            viewModel.weeklyStats.collectLatest { stats ->
                stats ?: return@collectLatest
                binding.tvWeeklyTotal.text = "Total: ${stats.totalBlocks}"
                binding.tvWeeklyAvg.text = "Average: ${stats.averagePerDay}/day"
            }
        }
        lifecycleScope.launch {
            viewModel.monthlyStats.collectLatest { stats ->
                stats ?: return@collectLatest
                binding.tvMonthlyTotal.text = "Total: ${stats.totalBlocks}"
                binding.tvMonthlyApps.text = "Unique Apps: ${stats.uniqueApps}"
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}