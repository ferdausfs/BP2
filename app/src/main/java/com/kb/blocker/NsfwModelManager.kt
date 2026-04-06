package com.kb.blocker

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.net.Uri
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * NSFW Model Manager
 *
 * ── Model Sources (priority order) ──────────────────────────────────────────
 * 1. User-imported model → /data/data/<pkg>/files/nsfw_model.tflite
 * 2. Bundled model       → assets/nsfw_model.tflite
 * 3. No model            → AI scan disabled
 *
 * ── Supported model formats ─────────────────────────────────────────────────
 * Input  : RGB image, 224×224 (standard) বা যেকোনো size (auto-resize হয়)
 * Output : float array — class probabilities
 *
 * ── 5-class model labels (GantMan/rockyzhengwu) ─────────────────────────────
 *   [0] drawings   [1] hentai   [2] neutral   [3] porn   [4] sexy
 *
 * ── 2-class model labels (Yahoo Open NSFW) ──────────────────────────────────
 *   [0] sfw   [1] nsfw
 */
object NsfwModelManager {

    private const val PREFS          = "nsfw_model_prefs"
    private const val KEY_MODEL_PATH = "model_path"   // custom model path
    private const val KEY_MODEL_TYPE = "model_type"   // "5class" | "2class" | "custom"
    private const val KEY_THRESHOLD  = "threshold"    // block threshold 0.0-1.0
    private const val KEY_ENABLED    = "ai_enabled"
    private const val KEY_INPUT_SIZE = "input_size"   // model input size

    private const val DEFAULT_MODEL  = "nsfw_model.tflite"
    private const val DEFAULT_SIZE   = 224
    private const val DEFAULT_THRESH = 0.65f

    // Model types
    const val TYPE_5CLASS = "5class"  // drawings/hentai/neutral/porn/sexy
    const val TYPE_2CLASS = "2class"  // sfw/nsfw
    const val TYPE_CUSTOM = "custom"  // user defined labels

    private var interpreter: Interpreter? = null
    private var inputSize  = DEFAULT_SIZE
    private var modelType  = TYPE_5CLASS

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ── Settings ──────────────────────────────────────────────────────────────

    fun isEnabled(ctx: Context) = prefs(ctx).getBoolean(KEY_ENABLED, false)
    fun setEnabled(ctx: Context, v: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_ENABLED, v).apply()

    fun getThreshold(ctx: Context) = prefs(ctx).getFloat(KEY_THRESHOLD, DEFAULT_THRESH)
    fun setThreshold(ctx: Context, v: Float) =
        prefs(ctx).edit().putFloat(KEY_THRESHOLD, v).apply()

    fun getModelType(ctx: Context) = prefs(ctx).getString(KEY_MODEL_TYPE, TYPE_5CLASS) ?: TYPE_5CLASS
    fun setModelType(ctx: Context, type: String) =
        prefs(ctx).edit().putString(KEY_MODEL_TYPE, type).apply()

    fun getCustomModelPath(ctx: Context) = prefs(ctx).getString(KEY_MODEL_PATH, null)
    fun setCustomModelPath(ctx: Context, path: String?) =
        prefs(ctx).edit().putString(KEY_MODEL_PATH, path).apply()

    fun getInputSize(ctx: Context) = prefs(ctx).getInt(KEY_INPUT_SIZE, DEFAULT_SIZE)
    fun setInputSize(ctx: Context, size: Int) =
        prefs(ctx).edit().putInt(KEY_INPUT_SIZE, size).apply()

    // ── Model info ────────────────────────────────────────────────────────────

    fun getActiveModelName(ctx: Context): String {
        val customPath = getCustomModelPath(ctx)
        return when {
            customPath != null -> File(customPath).name
            hasAssetModel(ctx) -> DEFAULT_MODEL
            else               -> "কোনো model নেই"
        }
    }

    fun hasAnyModel(ctx: Context): Boolean =
        getCustomModelPath(ctx) != null || hasAssetModel(ctx)

    private fun hasAssetModel(ctx: Context): Boolean = try {
        ctx.assets.open(DEFAULT_MODEL).close(); true
    } catch (_: Exception) { false }

    // ── Model lifecycle ───────────────────────────────────────────────────────

    fun loadModel(ctx: Context): Boolean {
        return try {
            unloadModel()
            val buffer = getModelBuffer(ctx) ?: return false
            inputSize = getInputSize(ctx)
            modelType = getModelType(ctx)

            val options = Interpreter.Options().apply {
                numThreads = 2
                // GPU delegate try করো, fail হলে CPU তে fall back
                try {
                    addDelegate(org.tensorflow.lite.gpu.GpuDelegate())
                } catch (_: Exception) { /* CPU fallback */ }
            }
            interpreter = Interpreter(buffer, options)
            true
        } catch (e: Exception) {
            interpreter = null
            false
        }
    }

    fun unloadModel() {
        interpreter?.close()
        interpreter = null
    }

    fun isModelLoaded() = interpreter != null

    // ── Inference ─────────────────────────────────────────────────────────────

    /**
     * Bitmap scan করো — adult content আছে কিনা বলো
     * @return Pair(isAdult, confidence)
     */
    fun scan(ctx: Context, bitmap: Bitmap): Pair<Boolean, Float> {
        val interp = interpreter ?: return Pair(false, 0f)

        return try {
            val resized    = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)
            val inputBuf   = bitmapToByteBuffer(resized, inputSize)
            val outputSize = getOutputSize(interp)
            val output     = Array(1) { FloatArray(outputSize) }

            interp.run(inputBuf, output)
            resized.recycle()

            val probs     = output[0]
            val threshold = getThreshold(ctx)

            when (modelType) {
                TYPE_5CLASS -> {
                    // [0]drawings [1]hentai [2]neutral [3]porn [4]sexy
                    val pornScore  = probs.getOrElse(3) { 0f }
                    val hentaiScore = probs.getOrElse(1) { 0f }
                    val sexyScore  = probs.getOrElse(4) { 0f }
                    val maxAdult   = maxOf(pornScore, hentaiScore, sexyScore)
                    Pair(maxAdult >= threshold, maxAdult)
                }
                TYPE_2CLASS -> {
                    // [0]sfw [1]nsfw
                    val nsfwScore = probs.getOrElse(1) { 0f }
                    Pair(nsfwScore >= threshold, nsfwScore)
                }
                else -> {
                    // custom — সবচেয়ে বেশি score টা দেখো
                    val maxScore = probs.maxOrNull() ?: 0f
                    val maxIdx   = probs.indexOfFirst { it == maxScore }
                    // index 0 কে neutral ধরি, বাকি সব suspicious
                    Pair(maxIdx != 0 && maxScore >= threshold, maxScore)
                }
            }
        } catch (_: Exception) {
            Pair(false, 0f)
        }
    }

    /**
     * Detailed result — কোন class কত score
     */
    fun scanDetailed(bitmap: Bitmap): Map<String, Float>? {
        val interp = interpreter ?: return null
        return try {
            val resized  = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)
            val inputBuf = bitmapToByteBuffer(resized, inputSize)
            val outSize  = getOutputSize(interp)
            val output   = Array(1) { FloatArray(outSize) }
            interp.run(inputBuf, output)
            resized.recycle()

            val labels = when (modelType) {
                TYPE_5CLASS -> listOf("drawings", "hentai", "neutral", "porn", "sexy")
                TYPE_2CLASS -> listOf("sfw", "nsfw")
                else        -> (0 until outSize).map { "class_$it" }
            }

            labels.zip(output[0].toList()).toMap()
        } catch (_: Exception) { null }
    }

    // ── Model import — user নিজে model রাখতে পারবে ───────────────────────────

    /**
     * Uri থেকে model copy করো internal storage এ
     */
    fun importModelFromUri(ctx: Context, uri: Uri, modelType: String): Boolean {
        return try {
            val destFile = File(ctx.filesDir, "nsfw_model.tflite")
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                destFile.outputStream().use { output -> input.copyTo(output) }
            }
            setCustomModelPath(ctx, destFile.absolutePath)
            setModelType(ctx, modelType)
            unloadModel()
            true
        } catch (_: Exception) { false }
    }

    /**
     * Custom model সরিয়ে দাও — asset model এ ফিরে যাও
     */
    fun removeCustomModel(ctx: Context) {
        try { File(ctx.filesDir, "nsfw_model.tflite").delete() } catch (_: Exception) {}
        setCustomModelPath(ctx, null)
        unloadModel()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun getModelBuffer(ctx: Context): MappedByteBuffer? {
        // Priority 1: custom model
        val customPath = getCustomModelPath(ctx)
        if (customPath != null) {
            val file = File(customPath)
            if (file.exists()) {
                return try {
                    FileInputStream(file).channel.map(
                        FileChannel.MapMode.READ_ONLY, 0, file.length()
                    )
                } catch (_: Exception) { null }
            }
        }
        // Priority 2: asset model
        return try {
            val afd    = ctx.assets.openFd(DEFAULT_MODEL)
            val input  = FileInputStream(afd.fileDescriptor)
            input.channel.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength)
        } catch (_: Exception) { null }
    }

    private fun bitmapToByteBuffer(bitmap: Bitmap, size: Int): ByteBuffer {
        val buf = ByteBuffer.allocateDirect(4 * size * size * 3)
        buf.order(ByteOrder.nativeOrder())
        val pixels = IntArray(size * size)
        bitmap.getPixels(pixels, 0, size, 0, 0, size, size)
        for (pixel in pixels) {
            // Normalize 0-255 → 0.0-1.0
            buf.putFloat(((pixel shr 16) and 0xFF) / 255.0f)  // R
            buf.putFloat(((pixel shr  8) and 0xFF) / 255.0f)  // G
            buf.putFloat(( pixel         and 0xFF) / 255.0f)  // B
        }
        return buf
    }

    private fun getOutputSize(interp: Interpreter): Int {
        val shape = interp.getOutputTensor(0).shape()
        return if (shape.size >= 2) shape[1] else shape[0]
    }

    /**
     * Model validate করো — load করা যায় কিনা
     */
    fun validateModel(ctx: Context, uri: Uri): ModelInfo? {
        return try {
            val stream = ctx.contentResolver.openInputStream(uri) ?: return null
            val bytes  = stream.readBytes()
            stream.close()
            if (bytes.size < 8) return null

            // tflite magic bytes check: 18 00 00 00 (flatbuffer)
            val buf     = ByteBuffer.wrap(bytes)
            val options = Interpreter.Options().apply { numThreads = 1 }
            val mapped  = ByteBuffer.allocateDirect(bytes.size)
                .order(ByteOrder.nativeOrder())
                .also { it.put(bytes); it.rewind() }

            val testInterp = Interpreter(mapped as MappedByteBuffer? ?: run {
                // direct buffer থেকে mapped buffer বানাও
                val tmp = createTempFile("validate", ".tflite", ctx.cacheDir)
                tmp.writeBytes(bytes)
                val fis = FileInputStream(tmp)
                val mb  = fis.channel.map(FileChannel.MapMode.READ_ONLY, 0, tmp.length())
                tmp.delete()
                mb
            }, options)

            val inShape  = testInterp.getInputTensor(0).shape()
            val outShape = testInterp.getOutputTensor(0).shape()
            val outSize  = if (outShape.size >= 2) outShape[1] else outShape[0]
            val inputSz  = if (inShape.size >= 3) inShape[1] else DEFAULT_SIZE

            testInterp.close()

            ModelInfo(
                inputSize  = inputSz,
                outputSize = outSize,
                fileSizeMb = bytes.size / (1024 * 1024f),
                suggestedType = when (outSize) {
                    5    -> TYPE_5CLASS
                    2    -> TYPE_2CLASS
                    else -> TYPE_CUSTOM
                }
            )
        } catch (_: Exception) { null }
    }

    data class ModelInfo(
        val inputSize: Int,
        val outputSize: Int,
        val fileSizeMb: Float,
        val suggestedType: String
    )
}
