package com.kb.blocker

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.kb.blocker.databinding.ActivityMainBinding
import org.json.JSONArray
import java.io.BufferedReader
import java.io.InputStreamReader

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding

    private lateinit var keywords: MutableList<String>
    private lateinit var keywordAdapter: ArrayAdapter<String>

    private lateinit var whitelistPkgs: MutableList<String>
    private lateinit var whitelistLabels: MutableList<String>
    private lateinit var whitelistAdapter: ArrayAdapter<String>

    private lateinit var dpm: DevicePolicyManager
    private lateinit var adminComponent: ComponentName

    private var sessionUnlocked = false

    // ── Activity result launchers ─────────────────────────────────────────────

    private val filePicker = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK)
            result.data?.data?.let { importJson(it) }
    }

    private val adminLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        updateAdminStatus()
        if (dpm.isAdminActive(adminComponent))
            toast("✅ Device Admin চালু — app uninstall করা যাবে না")
        else
            toast("⚠️ Device Admin চালু না হলে সহজে uninstall করা যাবে")
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        dpm            = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        adminComponent = ComponentName(this, AdminReceiver::class.java)

        setupKeywords()
        setupWhitelist()
        setupSwitches()
        setupAdminSection()

        b.btnAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    override fun onResume() {
        super.onResume()
        updateServiceBanner()
        updateAdminStatus()
        updateStats()

        if (!sessionUnlocked && PinManager.isPinSet(this))
            showPinUnlockDialog()
    }

    // ── Service banner ────────────────────────────────────────────────────────

    private fun updateServiceBanner() {
        if (KeywordService.isRunning) {
            b.tvStatus.text = "✅ Service চালু আছে"
            b.tvStatus.setTextColor(0xFF4CAF50.toInt())
            b.btnAccessibility.visibility = View.GONE
        } else {
            b.tvStatus.text = "⚠️ Service বন্ধ — Accessibility চালু করো"
            b.tvStatus.setTextColor(0xFFFF9800.toInt())
            b.btnAccessibility.visibility = View.VISIBLE
        }
    }

    // ── Device Admin ──────────────────────────────────────────────────────────

    private fun setupAdminSection() {
        b.btnAdminToggle.setOnClickListener {
            if (dpm.isAdminActive(adminComponent)) {
                // Deactivate — confirm first
                AlertDialog.Builder(this)
                    .setTitle("Device Admin বন্ধ করবে?")
                    .setMessage("বন্ধ করলে app সহজে uninstall করা যাবে।")
                    .setPositiveButton("হ্যাঁ, বন্ধ করো") { _, _ ->
                        dpm.removeActiveAdmin(adminComponent)
                        updateAdminStatus()
                    }
                    .setNegativeButton("না", null).show()
            } else {
                // Activate
                val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                    putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
                    putExtra(
                        DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                        "এটা চালু থাকলে Settings থেকে deactivate না করে app uninstall করা যাবে না।"
                    )
                }
                adminLauncher.launch(intent)
            }
        }
    }

    private fun updateAdminStatus() {
        val active = dpm.isAdminActive(adminComponent)
        b.tvAdminStatus.text = if (active) "🔒 চালু আছে — uninstall করা যাবে না"
                               else "🔓 বন্ধ — uninstall করা যাবে"
        b.tvAdminStatus.setTextColor(
            if (active) 0xFF4CAF50.toInt() else 0xFFFF5722.toInt()
        )
        b.btnAdminToggle.text = if (active) "Device Admin বন্ধ করো" else "Device Admin চালু করো"
        b.btnAdminToggle.backgroundTintList = android.content.res.ColorStateList.valueOf(
            if (active) 0xFF37474F.toInt() else 0xFF1B5E20.toInt()
        )
    }

    // ── Stats ─────────────────────────────────────────────────────────────────

    private fun updateStats() {
        val total = BlockStatsManager.getTotalBlocks(this)
        b.tvBlockStats.text = if (total == 0) "🛡️ এখনো কিছু block হয়নি"
                              else "🛡️ মোট block: $total বার"
    }

    fun onViewStatsClick(v: View) {
        val total = BlockStatsManager.getTotalBlocks(this)
        val top   = BlockStatsManager.getTopBlocked(this)
        val msg   = buildString {
            appendLine("মোট block: $total বার\n")
            if (top.isEmpty()) appendLine("এখনো কোনো app block হয়নি।")
            else {
                appendLine("সবচেয়ে বেশি block:")
                top.forEach { (pkg, cnt) ->
                    val label = try {
                        packageManager.getApplicationLabel(
                            packageManager.getApplicationInfo(pkg, 0)
                        ).toString()
                    } catch (_: Exception) { pkg }
                    appendLine("  • $label — $cnt বার")
                }
            }
        }
        AlertDialog.Builder(this)
            .setTitle("📊 Block Statistics")
            .setMessage(msg)
            .setPositiveButton("ঠিক আছে", null)
            .setNegativeButton("Clear Stats") { _, _ ->
                BlockStatsManager.clearStats(this)
                updateStats()
                toast("Stats মুছে গেছে")
            }
            .show()
    }

    // ── PIN ───────────────────────────────────────────────────────────────────

    private fun showPinUnlockDialog() {
        val input = EditText(this).apply {
            hint = "4-digit PIN"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            setPadding(48, 24, 48, 24)
        }
        AlertDialog.Builder(this)
            .setTitle("🔐 PIN দিয়ে unlock করো")
            .setView(input)
            .setCancelable(false)
            .setPositiveButton("Unlock") { _, _ ->
                if (PinManager.verifyPin(this, input.text.toString())) {
                    sessionUnlocked = true
                } else {
                    toast("❌ ভুল PIN")
                    finishAffinity()
                }
            }
            .setNegativeButton("বাতিল") { _, _ -> finishAffinity() }
            .show()
    }

    // ── Keywords ──────────────────────────────────────────────────────────────

    private fun setupKeywords() {
        keywords       = KeywordService.loadKeywords(this)
        keywordAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, keywords)
        b.listKeywords.adapter = keywordAdapter

        b.btnAdd.setOnClickListener { addKeywordDialog() }
        b.btnImportJson.setOnClickListener {
            filePicker.launch(Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "*/*"; addCategory(Intent.CATEGORY_OPENABLE)
            })
        }
        b.btnExportJson.setOnClickListener { exportKeywords() }
        b.btnClearKeywords.setOnClickListener { confirmClearAll() }

        b.listKeywords.setOnItemLongClickListener { _, _, pos, _ ->
            confirmDelete("\"${keywords[pos]}\"") {
                keywords.removeAt(pos)
                keywordAdapter.notifyDataSetChanged()
                KeywordService.saveKeywords(this, keywords)
            }
            true
        }
    }

    private fun addKeywordDialog() {
        val input = EditText(this).apply {
            hint = "যেমন: hot dance"
            inputType = InputType.TYPE_CLASS_TEXT
            setPadding(48, 24, 48, 24)
        }
        AlertDialog.Builder(this)
            .setTitle("🚫 Keyword যোগ করো")
            .setView(input)
            .setPositiveButton("যোগ করো") { _, _ ->
                val w = input.text.toString().trim().lowercase()
                when {
                    w.isEmpty()          -> toast("খালি হলে হবে না!")
                    keywords.contains(w) -> toast("আগেই আছে")
                    else -> {
                        keywords.add(w)
                        keywordAdapter.notifyDataSetChanged()
                        KeywordService.saveKeywords(this, keywords)
                        toast("✅ যোগ হয়েছে")
                    }
                }
            }
            .setNegativeButton("বাতিল", null).show()
    }

    private fun importJson(uri: Uri) {
        try {
            val raw = contentResolver.openInputStream(uri)?.use {
                BufferedReader(InputStreamReader(it)).readText()
            } ?: return
            val arr      = JSONArray(raw.trim())
            val imported = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                val w = arr.getString(i).trim().lowercase()
                if (w.isNotBlank() && !keywords.contains(w)) imported.add(w)
            }
            if (imported.isEmpty()) { toast("কোনো নতুন keyword নেই"); return }
            AlertDialog.Builder(this)
                .setTitle("${imported.size}টা import করবে?")
                .setMessage(
                    imported.take(8).joinToString(", ") +
                    if (imported.size > 8) "... (+${imported.size - 8})" else ""
                )
                .setPositiveButton("Import করো") { _, _ ->
                    keywords.addAll(imported)
                    keywordAdapter.notifyDataSetChanged()
                    KeywordService.saveKeywords(this, keywords)
                    toast("✅ ${imported.size}টা add হয়েছে")
                }
                .setNegativeButton("বাতিল", null).show()
        } catch (_: Exception) {
            toast("Format: [\"word1\", \"word2\"]")
        }
    }

    private fun exportKeywords() {
        if (keywords.isEmpty()) { toast("কোনো keyword নেই export করার"); return }
        try {
            val json     = JSONArray(keywords).toString()
            val filename = "keywords_${System.currentTimeMillis()}.json"
            val values   = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, filename)
                put(MediaStore.Downloads.MIME_TYPE, "application/json")
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            uri?.let {
                contentResolver.openOutputStream(it)?.use { os -> os.write(json.toByteArray()) }
                toast("✅ Downloads/$filename এ save হয়েছে")
            } ?: toast("Export ব্যর্থ")
        } catch (e: Exception) {
            toast("Export error: ${e.message}")
        }
    }

    private fun confirmClearAll() {
        if (keywords.isEmpty()) { toast("কোনো keyword নেই"); return }
        AlertDialog.Builder(this)
            .setTitle("সব keyword মুছবে?")
            .setMessage("${keywords.size}টা keyword মুছে যাবে।")
            .setPositiveButton("হ্যাঁ, মুছো") { _, _ ->
                keywords.clear()
                keywordAdapter.notifyDataSetChanged()
                KeywordService.saveKeywords(this, keywords)
                toast("✅ সব keyword মুছে গেছে")
            }
            .setNegativeButton("না", null).show()
    }

    // ── Whitelist ─────────────────────────────────────────────────────────────

    private fun setupWhitelist() {
        whitelistPkgs    = KeywordService.loadWhitelist(this)
        whitelistLabels  = whitelistPkgs.map { getAppLabel(it) }.toMutableList()
        whitelistAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, whitelistLabels)
        b.listWhitelist.adapter = whitelistAdapter

        b.btnAddWhitelist.setOnClickListener { showAppPicker() }

        b.listWhitelist.setOnItemLongClickListener { _, _, pos, _ ->
            val label = whitelistLabels.getOrElse(pos) { "?" }
            confirmDelete("\"$label\"") {
                whitelistPkgs.removeAt(pos)
                whitelistLabels.removeAt(pos)
                whitelistAdapter.notifyDataSetChanged()
                KeywordService.saveWhitelist(this, whitelistPkgs)
                KeywordService.instance?.whitelistCacheTime = 0L
                toast("✅ সরানো হয়েছে")
            }
            true
        }
    }

    private fun showAppPicker() {
        val pm   = packageManager
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 }
            .sortedBy { pm.getApplicationLabel(it).toString().lowercase() }

        if (apps.isEmpty()) { toast("কোনো app পাওয়া যায়নি"); return }

        val names   = apps.map { pm.getApplicationLabel(it).toString() }.toTypedArray()
        val checked = apps.map { whitelistPkgs.contains(it.packageName) }.toBooleanArray()

        AlertDialog.Builder(this)
            .setTitle("✅ Whitelist-এ যোগ করো")
            .setMultiChoiceItems(names, checked) { _, idx, v -> checked[idx] = v }
            .setPositiveButton("সেভ করো") { _, _ ->
                var added = 0; var removed = 0
                apps.forEachIndexed { idx, app ->
                    val pkg   = app.packageName
                    val label = names[idx]
                    if (checked[idx] && !whitelistPkgs.contains(pkg)) {
                        whitelistPkgs.add(pkg); whitelistLabels.add(label); added++
                    } else if (!checked[idx] && whitelistPkgs.contains(pkg)) {
                        val pos = whitelistPkgs.indexOf(pkg)
                        whitelistPkgs.removeAt(pos); whitelistLabels.removeAt(pos); removed++
                    }
                }
                whitelistAdapter.notifyDataSetChanged()
                KeywordService.saveWhitelist(this, whitelistPkgs)
                KeywordService.instance?.whitelistCacheTime = 0L
                val msg = buildString {
                    if (added   > 0) append("✅ $added টা যোগ হয়েছে")
                    if (removed > 0) append(" • ❌ $removed টা সরানো হয়েছে")
                }
                if (msg.isNotBlank()) toast(msg)
            }
            .setNegativeButton("বাতিল", null).show()
    }

    // ── Switches ──────────────────────────────────────────────────────────────

    private fun setupSwitches() {
        b.switchEnabled.isChecked   = KeywordService.isEnabled(this)
        b.switchAdultText.isChecked = KeywordService.isAdultTextDetectEnabled(this)
        b.switchSoftAdult.isChecked = KeywordService.isSoftAdultEnabled(this)
        b.switchVideoMeta.isChecked = KeywordService.isVideoMetaEnabled(this)

        b.switchEnabled.setOnCheckedChangeListener   { _, c -> KeywordService.setEnabled(this, c) }
        b.switchAdultText.setOnCheckedChangeListener { _, c -> KeywordService.setAdultTextDetect(this, c) }
        b.switchSoftAdult.setOnCheckedChangeListener { _, c -> KeywordService.setSoftAdult(this, c) }
        b.switchVideoMeta.setOnCheckedChangeListener { _, c -> KeywordService.setVideoMeta(this, c) }
    }

    // ── PIN section ───────────────────────────────────────────────────────────

    fun onPinClick(v: View) {
        if (PinManager.isPinSet(this)) {
            AlertDialog.Builder(this)
                .setTitle("🔐 PIN")
                .setItems(arrayOf("PIN পরিবর্তন করো", "PIN সরিয়ে দাও")) { _, which ->
                    if (which == 0) showSetPinDialog()
                    else {
                        PinManager.clearPin(this); toast("PIN সরানো হয়েছে")
                        b.btnPinSetup.text = "🔐 PIN সেট করো"
                    }
                }
                .setNegativeButton("বাতিল", null).show()
        } else showSetPinDialog()
    }

    private fun showSetPinDialog() {
        val input = EditText(this).apply {
            hint = "4-digit PIN দাও"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            setPadding(48, 24, 48, 24)
        }
        AlertDialog.Builder(this)
            .setTitle("🔐 নতুন PIN সেট করো")
            .setView(input)
            .setPositiveButton("সেট করো") { _, _ ->
                val pin = input.text.toString()
                if (pin.length != 4) toast("PIN অবশ্যই 4 সংখ্যার")
                else {
                    PinManager.setPin(this, pin)
                    sessionUnlocked = true
                    b.btnPinSetup.text = "🔐 PIN পরিবর্তন / সরাও"
                    toast("✅ PIN সেট হয়েছে")
                }
            }
            .setNegativeButton("বাতিল", null).show()
    }

    // ── Util ──────────────────────────────────────────────────────────────────

    private fun getAppLabel(pkg: String) = try {
        packageManager.getApplicationLabel(
            packageManager.getApplicationInfo(pkg, 0)
        ).toString()
    } catch (_: Exception) { pkg }

    private fun confirmDelete(what: String, block: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle("$what সরাবে?")
            .setPositiveButton("হ্যাঁ") { _, _ -> block() }
            .setNegativeButton("না", null).show()
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
