package com.kb.blocker

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
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
    private lateinit var dpm: DevicePolicyManager
    private lateinit var adminComponent: ComponentName

    private lateinit var keywords: MutableList<String>
    private lateinit var keywordAdapter: ArrayAdapter<String>
    private lateinit var whitelistPkgs: MutableList<String>
    private lateinit var whitelistLabels: MutableList<String>
    private lateinit var whitelistAdapter: ArrayAdapter<String>

    private val filePicker = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK)
            result.data?.data?.let { importJson(it) }
    }

    private val adminLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { updateAdminStatus() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        dpm = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        adminComponent = ComponentName(this, AdminReceiver::class.java)

        setupKeywords()
        setupWhitelist()
        setupSwitches()
        setupPermissionButtons()
    }

    override fun onResume() {
        super.onResume()
        updateServiceStatus()
        updateAdminStatus()
        updateNotifStatus()
    }

    // ── Status Updates ────────────────────────────────────────────────────────

    private fun updateServiceStatus() {
        if (KeywordService.isRunning) {
            b.tvStatus.text = "✅ Accessibility Service চালু"
            b.tvStatus.setTextColor(0xFF4CAF50.toInt())
            b.btnAccessibility.visibility = View.GONE
        } else {
            b.tvStatus.text = "⚠️ Accessibility Service বন্ধ"
            b.tvStatus.setTextColor(0xFFFF9800.toInt())
            b.btnAccessibility.visibility = View.VISIBLE
        }
    }

    private fun updateAdminStatus() {
        val active = dpm.isAdminActive(adminComponent)
        if (active) {
            b.tvAdminStatus.text = "✅ Admin Protection চালু"
            b.tvAdminStatus.setTextColor(0xFF4CAF50.toInt())
            b.btnAdminEnable.visibility = View.GONE
            b.btnAdminDisable.visibility = View.VISIBLE
        } else {
            b.tvAdminStatus.text = "⚠️ Admin Protection বন্ধ — চালু করলে uninstall করা যাবে না"
            b.tvAdminStatus.setTextColor(0xFFFF9800.toInt())
            b.btnAdminEnable.visibility = View.VISIBLE
            b.btnAdminDisable.visibility = View.GONE
        }
    }

    private fun updateNotifStatus() {
        val enabled = isNotificationListenerEnabled()
        if (enabled) {
            b.tvNotifStatus.text = "✅ Notification Access চালু"
            b.tvNotifStatus.setTextColor(0xFF4CAF50.toInt())
            b.btnNotifAccess.visibility = View.GONE
        } else {
            b.tvNotifStatus.text = "⚠️ Notification Access বন্ধ — video title ধরতে চালু করো"
            b.tvNotifStatus.setTextColor(0xFFFF9800.toInt())
            b.btnNotifAccess.visibility = View.VISIBLE
        }
    }

    private fun isNotificationListenerEnabled(): Boolean {
        val pkgName = packageName
        val flat = Settings.Secure.getString(
            contentResolver, "enabled_notification_listeners"
        ) ?: return false
        return flat.contains(pkgName)
    }

    // ── Permission Buttons ────────────────────────────────────────────────────

    private fun setupPermissionButtons() {
        // Accessibility
        b.btnAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        // Device Admin — enable
        b.btnAdminEnable.setOnClickListener {
            val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
                putExtra(
                    DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    "Admin চালু থাকলে app কে সহজে uninstall বা disable করা যাবে না। " +
                    "এটা parental control এর জন্য জরুরি।"
                )
            }
            adminLauncher.launch(intent)
        }

        // Device Admin — disable (শুধু parent জানবে এই option এর কথা)
        b.btnAdminDisable.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("⚠️ Admin Protection বন্ধ করবে?")
                .setMessage("Admin বন্ধ করলে যে কেউ app uninstall করতে পারবে। নিশ্চিত?")
                .setPositiveButton("হ্যাঁ, বন্ধ করো") { _, _ ->
                    dpm.removeActiveAdmin(adminComponent)
                    updateAdminStatus()
                }
                .setNegativeButton("না", null)
                .show()
        }

        // Notification Access
        b.btnNotifAccess.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
    }

    // ── Keywords ──────────────────────────────────────────────────────────────

    private fun setupKeywords() {
        keywords = KeywordService.loadKeywords(this)
        keywordAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, keywords)
        b.listKeywords.adapter = keywordAdapter

        b.btnAdd.setOnClickListener { addKeywordDialog() }
        b.btnImportJson.setOnClickListener {
            filePicker.launch(Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "*/*"; addCategory(Intent.CATEGORY_OPENABLE)
            })
        }
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
            hint = "যেমন: hot dance, item song"
            inputType = InputType.TYPE_CLASS_TEXT
            setPadding(48, 24, 48, 24)
        }
        AlertDialog.Builder(this)
            .setTitle("🚫 নতুন Keyword")
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
            val arr = JSONArray(raw.trim())
            val imported = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                val w = arr.getString(i).trim().lowercase()
                if (w.isNotBlank() && !keywords.contains(w)) imported.add(w)
            }
            if (imported.isEmpty()) { toast("কোনো নতুন keyword নেই"); return }
            AlertDialog.Builder(this)
                .setTitle("${imported.size}টা import করবে?")
                .setMessage(imported.take(8).joinToString(", ") +
                    if (imported.size > 8) "... (+${imported.size - 8})" else "")
                .setPositiveButton("Import") { _, _ ->
                    keywords.addAll(imported)
                    keywordAdapter.notifyDataSetChanged()
                    KeywordService.saveKeywords(this, keywords)
                    toast("✅ ${imported.size}টা add হয়েছে")
                }
                .setNegativeButton("বাতিল", null).show()
        } catch (_: Exception) { toast("Format: [\"word1\", \"word2\"]") }
    }

    // ── Whitelist ─────────────────────────────────────────────────────────────

    private fun setupWhitelist() {
        whitelistPkgs   = KeywordService.loadWhitelist(this)
        whitelistLabels = whitelistPkgs.map { getAppLabel(it) }.toMutableList()
        whitelistAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, whitelistLabels)
        b.listWhitelist.adapter = whitelistAdapter

        b.btnAddWhitelist.setOnClickListener { showAppPicker() }

        b.listWhitelist.setOnItemLongClickListener { _, _, pos, _ ->
            confirmDelete("\"${whitelistLabels.getOrElse(pos) { "?" }}\"") {
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
            .setTitle("✅ Whitelist App বেছে নাও")
            .setMultiChoiceItems(names, checked) { _, i, v -> checked[i] = v }
            .setPositiveButton("সেভ করো") { _, _ ->
                var added = 0; var removed = 0
                apps.forEachIndexed { i, app ->
                    val pkg = app.packageName; val label = names[i]
                    if (checked[i] && !whitelistPkgs.contains(pkg)) {
                        whitelistPkgs.add(pkg); whitelistLabels.add(label); added++
                    } else if (!checked[i] && whitelistPkgs.contains(pkg)) {
                        val idx = whitelistPkgs.indexOf(pkg)
                        whitelistPkgs.removeAt(idx); whitelistLabels.removeAt(idx); removed++
                    }
                }
                whitelistAdapter.notifyDataSetChanged()
                KeywordService.saveWhitelist(this, whitelistPkgs)
                KeywordService.instance?.whitelistCacheTime = 0L
                val msg = buildString {
                    if (added > 0)   append("✅ $added টা যোগ")
                    if (removed > 0) append(" ❌ $removed টা সরানো")
                }
                if (msg.isNotBlank()) toast(msg)
            }
            .setNegativeButton("বাতিল", null).show()
    }

    // ── Switches ──────────────────────────────────────────────────────────────

    private fun setupSwitches() {
        b.switchEnabled.isChecked = KeywordService.isEnabled(this)
        b.switchEnabled.setOnCheckedChangeListener { _, c -> KeywordService.setEnabled(this, c) }

        b.switchAdultText.isChecked = KeywordService.isAdultTextDetectEnabled(this)
        b.switchAdultText.setOnCheckedChangeListener { _, c -> KeywordService.setAdultTextDetect(this, c) }

        b.switchSoftAdult.isChecked = KeywordService.isSoftAdultEnabled(this)
        b.switchSoftAdult.setOnCheckedChangeListener { _, c -> KeywordService.setSoftAdult(this, c) }

        b.switchVideoMeta.isChecked = KeywordService.isVideoMetaEnabled(this)
        b.switchVideoMeta.setOnCheckedChangeListener { _, c -> KeywordService.setVideoMeta(this, c) }
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
