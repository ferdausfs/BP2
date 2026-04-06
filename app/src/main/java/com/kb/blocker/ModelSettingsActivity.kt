package com.kb.blocker

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/**
 * AI Model Settings Screen
 *
 * এখান থেকে:
 * - Model import/remove করা যাবে
 * - Model type সেট করা যাবে (5-class / 2-class)
 * - Threshold adjust করা যাবে
 * - AI scan চালু/বন্ধ করা যাবে
 * - Model info দেখা যাবে
 */
class ModelSettingsActivity : AppCompatActivity() {

    private lateinit var tvModelName   : TextView
    private lateinit var tvModelInfo   : TextView
    private lateinit var tvModelStatus : TextView
    private lateinit var switchAiScan  : Switch
    private lateinit var spinnerType   : Spinner
    private lateinit var seekThreshold : SeekBar
    private lateinit var tvThreshold   : TextView
    private lateinit var btnImport     : Button
    private lateinit var btnRemove     : Button
    private lateinit var btnTestScan   : Button
    private lateinit var btnBack       : Button
    private lateinit var btnDownloadInfo: Button

    private val modelPicker = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri -> importModel(uri) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_model_settings)

        tvModelName    = findViewById(R.id.tvModelName)
        tvModelInfo    = findViewById(R.id.tvModelInfo)
        tvModelStatus  = findViewById(R.id.tvModelStatus)
        switchAiScan   = findViewById(R.id.switchAiScan)
        spinnerType    = findViewById(R.id.spinnerModelType)
        seekThreshold  = findViewById(R.id.seekThreshold)
        tvThreshold    = findViewById(R.id.tvThreshold)
        btnImport      = findViewById(R.id.btnImportModel)
        btnRemove      = findViewById(R.id.btnRemoveModel)
        btnTestScan    = findViewById(R.id.btnTestScan)
        btnBack        = findViewById(R.id.btnBack)
        btnDownloadInfo = findViewById(R.id.btnDownloadInfo)

        setupSpinner()
        setupThreshold()
        setupButtons()
        refreshUI()
    }

    override fun onResume() {
        super.onResume()
        refreshUI()
    }

    // ── UI Refresh ────────────────────────────────────────────────────────────

    private fun refreshUI() {
        val hasModel = NsfwModelManager.hasAnyModel(this)
        val isLoaded = NsfwModelManager.isModelLoaded()

        tvModelName.text = NsfwModelManager.getActiveModelName(this)

        tvModelStatus.text = when {
            !hasModel  -> "❌ কোনো model নেই"
            isLoaded   -> "✅ Model loaded — scanning ready"
            else       -> "⚠️ Model আছে কিন্তু load হয়নি"
        }
        tvModelStatus.setTextColor(when {
            !hasModel -> 0xFFFF5252.toInt()
            isLoaded  -> 0xFF4CAF50.toInt()
            else      -> 0xFFFF9800.toInt()
        })

        tvModelInfo.text = if (hasModel) {
            val type = NsfwModelManager.getModelType(this)
            val size = NsfwModelManager.getInputSize(this)
            val typeLabel = when (type) {
                NsfwModelManager.TYPE_5CLASS -> "5-class (drawings/hentai/neutral/porn/sexy)"
                NsfwModelManager.TYPE_2CLASS -> "2-class (sfw/nsfw)"
                else                         -> "Custom"
            }
            "Type: $typeLabel\nInput size: ${size}×${size}"
        } else {
            "Model import করো বা নিচের link থেকে ডাউনলোড করো"
        }

        switchAiScan.isChecked = NsfwModelManager.isEnabled(this)
        btnRemove.visibility   = if (NsfwModelManager.getCustomModelPath(this) != null)
            View.VISIBLE else View.GONE
        btnTestScan.visibility = if (isLoaded) View.VISIBLE else View.GONE

        // Spinner
        val typeIdx = when (NsfwModelManager.getModelType(this)) {
            NsfwModelManager.TYPE_5CLASS -> 0
            NsfwModelManager.TYPE_2CLASS -> 1
            else -> 2
        }
        spinnerType.setSelection(typeIdx)

        // Threshold
        val thresh = NsfwModelManager.getThreshold(this)
        seekThreshold.progress = (thresh * 100).toInt()
        tvThreshold.text       = "%.0f%%".format(thresh * 100)
    }

    // ── Spinner (model type) ──────────────────────────────────────────────────

    private fun setupSpinner() {
        val options = arrayOf(
            "5-class (drawings/hentai/neutral/porn/sexy)",
            "2-class (sfw/nsfw)",
            "Custom"
        )
        spinnerType.adapter = ArrayAdapter(this,
            android.R.layout.simple_spinner_dropdown_item, options)

        spinnerType.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                val type = when (pos) {
                    0 -> NsfwModelManager.TYPE_5CLASS
                    1 -> NsfwModelManager.TYPE_2CLASS
                    else -> NsfwModelManager.TYPE_CUSTOM
                }
                NsfwModelManager.setModelType(this@ModelSettingsActivity, type)
                NsfwModelManager.unloadModel()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    // ── Threshold Seek Bar ────────────────────────────────────────────────────

    private fun setupThreshold() {
        seekThreshold.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val thresh = progress / 100f
                tvThreshold.text = "%.0f%%".format(progress.toFloat())
                if (fromUser) NsfwModelManager.setThreshold(
                    this@ModelSettingsActivity, thresh.coerceIn(0.1f, 1.0f))
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
    }

    // ── Buttons ───────────────────────────────────────────────────────────────

    private fun setupButtons() {
        btnBack.setOnClickListener { finish() }

        switchAiScan.setOnCheckedChangeListener { _, checked ->
            NsfwModelManager.setEnabled(this, checked)
            if (checked && !NsfwModelManager.isModelLoaded()) {
                if (!NsfwModelManager.loadModel(this)) {
                    toast("⚠️ Model load হয়নি। আগে model import করো।")
                    switchAiScan.isChecked = false
                    NsfwModelManager.setEnabled(this, false)
                } else {
                    toast("✅ AI scan চালু হয়েছে")
                    // KeywordService এ notify করো
                    KeywordService.instance?.onNsfwModelChanged()
                }
            }
            if (!checked) {
                NsfwScanService.stop()
                toast("AI scan বন্ধ হয়েছে")
            }
            refreshUI()
        }

        btnImport.setOnClickListener {
            modelPicker.launch(Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "*/*"
                addCategory(Intent.CATEGORY_OPENABLE)
            })
        }

        btnRemove.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Model সরাবে?")
                .setMessage("Custom model সরানো হবে। Asset model থাকলে সেটা use হবে।")
                .setPositiveButton("হ্যাঁ") { _, _ ->
                    NsfwModelManager.removeCustomModel(this)
                    toast("✅ Custom model সরানো হয়েছে")
                    refreshUI()
                }
                .setNegativeButton("না", null).show()
        }

        btnTestScan.setOnClickListener {
            toast("Test: screenshot নেওয়া হচ্ছে...")
            KeywordService.instance?.requestTestScan() ?: toast("⚠️ Service চালু নেই")
        }

        btnDownloadInfo.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("📥 Model Download করবে কীভাবে?")
                .setMessage(
                    "Step 1: Browser এ যাও\n\n" +
                    "Step 2: এই URL থেকে model ডাউনলোড করো:\n\n" +
                    "🔗 5-class model (recommended):\n" +
                    "github.com/rockyzhengwu/nsfw-resnet-tflite\n" +
                    "→ nsfw_model.tflite ডাউনলোড করো\n\n" +
                    "🔗 2-class model (smaller):\n" +
                    "github.com/topics/nsfw-detection\n\n" +
                    "Step 3: App এ ফিরে এসো\n" +
                    "Step 4: 'Model Import করো' চাপো\n" +
                    "Step 5: ডাউনলোড করা .tflite file select করো\n\n" +
                    "⚠️ File এর extension অবশ্যই .tflite হতে হবে"
                )
                .setPositiveButton("বুঝেছি", null)
                .show()
        }
    }

    // ── Import ────────────────────────────────────────────────────────────────

    private fun importModel(uri: Uri) {
        toast("⏳ Model validate করা হচ্ছে...")

        Thread {
            val info = NsfwModelManager.validateModel(this, uri)
            runOnUiThread {
                if (info == null) {
                    toast("❌ Invalid model! .tflite file দাও।")
                    return@runOnUiThread
                }

                val typeLabel = when (info.suggestedType) {
                    NsfwModelManager.TYPE_5CLASS -> "5-class (recommended)"
                    NsfwModelManager.TYPE_2CLASS -> "2-class"
                    else -> "Custom (${info.outputSize} outputs)"
                }

                AlertDialog.Builder(this)
                    .setTitle("✅ Valid Model পাওয়া গেছে!")
                    .setMessage(
                        "📊 Model Info:\n" +
                        "• Input size: ${info.inputSize}×${info.inputSize}\n" +
                        "• Output classes: ${info.outputSize}\n" +
                        "• File size: ${"%.1f".format(info.fileSizeMb)} MB\n" +
                        "• Suggested type: $typeLabel\n\n" +
                        "Import করবে?"
                    )
                    .setPositiveButton("Import করো") { _, _ ->
                        val success = NsfwModelManager.importModelFromUri(
                            this, uri, info.suggestedType)
                        if (success) {
                            NsfwModelManager.setInputSize(this, info.inputSize)
                            NsfwModelManager.loadModel(this)
                            KeywordService.instance?.onNsfwModelChanged()
                            toast("✅ Model import হয়েছে!")
                        } else {
                            toast("❌ Import failed!")
                        }
                        refreshUI()
                    }
                    .setNegativeButton("বাতিল", null)
                    .show()
            }
        }.start()
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
