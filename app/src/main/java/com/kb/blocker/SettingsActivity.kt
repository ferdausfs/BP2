package com.kb.blocker

import android.app.Activity
import android.app.TimePickerDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/**
 * Advanced Settings Screen
 * - Safe Search
 * - VPN Block
 * - App Install Block
 * - Theme
 * - Backup/Restore
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var switchSafeSearch  : Switch
    private lateinit var switchVpnBlock    : Switch
    private lateinit var switchInstallBlock: Switch
    private lateinit var spinnerTheme      : Spinner
    private lateinit var btnExport         : Button
    private lateinit var btnImport         : Button
    private lateinit var btnBack           : Button
    private lateinit var tvScheduleInfo    : TextView
    private lateinit var switchSchedule    : Switch
    private lateinit var btnScheduleSet    : Button

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK)
            result.data?.data?.let { doExport(it) }
    }

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK)
            result.data?.data?.let { doImport(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        switchSafeSearch   = findViewById(R.id.switchSafeSearch)
        switchVpnBlock     = findViewById(R.id.switchVpnBlock)
        switchInstallBlock = findViewById(R.id.switchInstallBlock)
        spinnerTheme       = findViewById(R.id.spinnerTheme)
        btnExport          = findViewById(R.id.btnExportSettings)
        btnImport          = findViewById(R.id.btnImportSettings)
        btnBack            = findViewById(R.id.btnSettingsBack)
        tvScheduleInfo     = findViewById(R.id.tvScheduleInfo)
        switchSchedule     = findViewById(R.id.switchScheduleSettings)
        btnScheduleSet     = findViewById(R.id.btnScheduleSet)

        setupThemeSpinner()
        setupSwitches()
        setupButtons()
        refreshSchedule()
    }

    override fun onResume() {
        super.onResume()
        refreshAll()
    }

    private fun refreshAll() {
        switchSafeSearch.isChecked   = SafeSearchManager.isEnabled(this)
        switchVpnBlock.isChecked     = VpnDetector.isBlockEnabled(this)
        switchInstallBlock.isChecked = AppInstallBlocker.isEnabled(this)
        refreshSchedule()
    }

    private fun refreshSchedule() {
        val on = ScheduleManager.isScheduleEnabled(this)
        switchSchedule.isChecked = on
        if (on) {
            val (sh,sm) = ScheduleManager.getStartTime(this)
            val (eh,em) = ScheduleManager.getEndTime(this)
            tvScheduleInfo.text = "Allowed: ${ScheduleManager.formatTime(sh,sm)} — ${ScheduleManager.formatTime(eh,em)}"
        } else {
            tvScheduleInfo.text = "Schedule বন্ধ আছে"
        }
    }

    private fun setupThemeSpinner() {
        val options = arrayOf("Dark (default)", "Light", "System")
        spinnerTheme.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, options)
        spinnerTheme.setSelection(ThemeManager.getTheme(this))
        spinnerTheme.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                ThemeManager.setTheme(this@SettingsActivity, pos)
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    private fun setupSwitches() {
        switchSafeSearch.setOnCheckedChangeListener { _, c ->
            SafeSearchManager.setEnabled(this, c)
            toast(if (c) "✅ Safe Search চালু — Google/Bing এ safe=active enforce হবে" else "Safe Search বন্ধ")
        }
        switchVpnBlock.setOnCheckedChangeListener { _, c ->
            VpnDetector.setBlockEnabled(this, c)
            toast(if (c) "✅ VPN চালু থাকলে block হবে" else "VPN block বন্ধ")
        }
        switchInstallBlock.setOnCheckedChangeListener { _, c ->
            if (c && !PinManager.isPinSet(this)) {
                toast("⚠️ প্রথমে PIN সেট করো")
                switchInstallBlock.isChecked = false
                return@setOnCheckedChangeListener
            }
            AppInstallBlocker.setEnabled(this, c)
            toast(if (c) "✅ নতুন app install এ PIN লাগবে" else "Install block বন্ধ")
        }
        switchSchedule.setOnCheckedChangeListener { _, c ->
            if (c && !ScheduleManager.isScheduleEnabled(this)) showSchedulePicker()
            else { ScheduleManager.setScheduleEnabled(this, c); refreshSchedule() }
        }
    }

    private fun setupButtons() {
        btnBack.setOnClickListener { finish() }

        btnScheduleSet.setOnClickListener { showSchedulePicker() }

        btnExport.setOnClickListener {
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/json"
                putExtra(Intent.EXTRA_TITLE, "content_blocker_backup.json")
            }
            exportLauncher.launch(intent)
        }

        btnImport.setOnClickListener {
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "*/*"; addCategory(Intent.CATEGORY_OPENABLE)
            }
            importLauncher.launch(intent)
        }
    }

    private fun showSchedulePicker() {
        val (sh,sm) = ScheduleManager.getStartTime(this)
        val (eh,em) = ScheduleManager.getEndTime(this)
        AlertDialog.Builder(this)
            .setTitle("⏰ Allowed সময় সেট করো")
            .setMessage("Browser/video এই সময়ের মধ্যে চলবে। বাইরে block হবে।\n\nবর্তমান: ${ScheduleManager.formatTime(sh,sm)} — ${ScheduleManager.formatTime(eh,em)}")
            .setPositiveButton("সময় সেট করো") { _,_ ->
                TimePickerDialog(this, { _,h,m ->
                    ScheduleManager.setStartTime(this, h, m)
                    TimePickerDialog(this, { _,eh2,em2 ->
                        ScheduleManager.setEndTime(this, eh2, em2)
                        ScheduleManager.setScheduleEnabled(this, true)
                        refreshSchedule()
                        toast("✅ Schedule সেট হয়েছে")
                    }, eh, em, false).show()
                }, sh, sm, false).show()
            }
            .setNegativeButton("বাতিল") { _,_ ->
                if (!ScheduleManager.isScheduleEnabled(this)) switchSchedule.isChecked = false
            }.show()
    }

    private fun doExport(uri: Uri) {
        val ok = BackupManager.export(this, uri)
        toast(if (ok) "✅ Backup সেভ হয়েছে" else "❌ Export failed")
    }

    private fun doImport(uri: Uri) {
        val result = BackupManager.restore(this, uri)
        AlertDialog.Builder(this)
            .setTitle(if (result.success) "✅ Restore সফল" else "❌ Restore failed")
            .setMessage(result.message)
            .setPositiveButton("OK") { _,_ -> if (result.success) recreate() }
            .show()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
