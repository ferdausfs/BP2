package com.haramblur.ml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import com.haramblur.utils.Constants
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * NSFW classifier — mirrors detector.js getNsfwClasses() + containsNsfw()
 *
 * Classes (index same as HaramBlur extension):
 *   0 = Drawing  → SFW
 *   1 = Hentai   → NSFW
 *   2 = Neutral  → SFW
 *   3 = Porn     → NSFW
 *   4 = Sexy     → NSFW
 */
class NsfwClassifier(private val context: Context) {

    companion object {
        private const val TAG = "HaramBlur.Classifier"

        private val CLASS_NAMES  = arrayOf("Drawing", "Hentai", "Neutral", "Porn", "Sexy")
        private val CLASS_IS_NSFW = booleanArrayOf(false, true, false, true, true)

        /**
         * Mirrors getNsfwClasses(factor) threshold map in detector.js.
         * factor = strictness (0.0 = lenient … 1.0 = aggressive)
         */
        fun thresholdFor(classIndex: Int, factor: Float): Float = when (classIndex) {
            Constants.CLASS_DRAWING -> 0.5f
            Constants.CLASS_HENTAI  -> 0.5f + (1f - factor) * 0.5f
            Constants.CLASS_NEUTRAL -> 0.5f + factor * 0.5f
            Constants.CLASS_PORN    -> 0.1f + (1f - factor) * 0.4f
            Constants.CLASS_SEXY    -> 0.1f + (1f - factor) * 0.4f
            else                    -> 0.5f
        }

        /** Returns true if bitmap is mostly black (DRM / protected content). */
        fun isBlackFrame(bitmap: Bitmap): Boolean {
            val sample = Bitmap.createScaledBitmap(bitmap, 64, 64, false)
            var blackPx = 0
            val total = sample.width * sample.height
            for (x in 0 until sample.width)
                for (y in 0 until sample.height) {
                    val p = sample.getPixel(x, y)
                    if (Color.red(p) < 15 && Color.green(p) < 15 && Color.blue(p) < 15) blackPx++
                }
            sample.recycle()
            return (blackPx.toFloat() / total) > Constants.BLACK_FRAME_THRESHOLD
        }
    }

    data class Prediction(
        val classIndex: Int,
        val className:  String,
        val probability: Float,
        val isNsfw:     Boolean
    )

    data class Result(
        val isNsfw:      Boolean,
        val topClass:    String,
        val confidence:  Float,
        val predictions: List<Prediction>
    )

    private var interpreter: Interpreter? = null
    private var gpuDelegate: GpuDelegate? = null
    @Volatile private var ready = false

    fun init() {
        try {
            val opts = Interpreter.Options()
            try {
                gpuDelegate = GpuDelegate()
                opts.addDelegate(gpuDelegate!!)
            } catch (e: Exception) {
                gpuDelegate?.close(); gpuDelegate = null
            }
            opts.numThreads = 2
            interpreter = Interpreter(loadModel(), opts)
            ready = true
            Log.d(TAG, "TFLite ready (GPU=${gpuDelegate != null})")
        } catch (e: Exception) {
            // Retry CPU-only
            Log.w(TAG, "GPU init failed, falling back to CPU: ${e.message}")
            gpuDelegate?.close(); gpuDelegate = null
            try {
                val opts = Interpreter.Options().apply { numThreads = 2 }
                interpreter = Interpreter(loadModel(), opts)
                ready = true
                Log.d(TAG, "TFLite ready (CPU)")
            } catch (e2: Exception) {
                Log.e(TAG, "TFLite init failed: ${e2.message}")
            }
        }
    }

    fun isReady() = ready

    /**
     * Classify a bitmap.
     * Mirrors nsfwModelClassify() + containsNsfw() from detector.js.
     */
    fun classify(bitmap: Bitmap, strictness: Float = Constants.DEFAULT_STRICTNESS): Result? {
        if (!ready) return null

        val resized = if (bitmap.width != Constants.MODEL_INPUT_SIZE ||
                          bitmap.height != Constants.MODEL_INPUT_SIZE)
            Bitmap.createScaledBitmap(bitmap, Constants.MODEL_INPUT_SIZE, Constants.MODEL_INPUT_SIZE, true)
        else bitmap

        val inputBuf = bitmapToByteBuffer(resized)
        val outputArr = Array(1) { FloatArray(Constants.MODEL_OUTPUT_CLASSES) }

        return try {
            interpreter!!.run(inputBuf, outputArr)
            val scores = outputArr[0]

            val predictions = scores.mapIndexed { i, score ->
                Prediction(i, CLASS_NAMES[i], score, CLASS_IS_NSFW[i])
            }.sortedByDescending { it.probability }

            Result(
                isNsfw      = containsNsfw(predictions, strictness),
                topClass    = predictions[0].className,
                confidence  = predictions[0].probability,
                predictions = predictions
            )
        } catch (e: Exception) {
            Log.e(TAG, "classify error: ${e.message}")
            null
        } finally {
            if (resized != bitmap) resized.recycle()
        }
    }

    /**
     * Mirrors containsNsfw(nsfwDetections, strictness) from detector.js.
     * Compares highest delta-above-threshold of NSFW vs SFW classes.
     */
    private fun containsNsfw(predictions: List<Prediction>, strictness: Float): Boolean {
        var highestNsfwDelta = 0f
        var highestSfwDelta  = 0f
        predictions.forEach { det ->
            val delta = det.probability - thresholdFor(det.classIndex, strictness)
            if (det.isNsfw) highestNsfwDelta = maxOf(highestNsfwDelta, delta)
            else            highestSfwDelta  = maxOf(highestSfwDelta,  delta)
        }
        return highestNsfwDelta > highestSfwDelta
    }

    private fun loadModel(): MappedByteBuffer {
        val fd = context.assets.openFd(Constants.MODEL_FILE)
        return FileInputStream(fd.fileDescriptor).channel.map(
            FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength
        )
    }

    /**
     * Convert bitmap to float ByteBuffer normalised 0-1
     * (same as tfScalar: 255 in extension).
     */
    private fun bitmapToByteBuffer(bitmap: Bitmap): ByteBuffer {
        val buf = ByteBuffer.allocateDirect(
            4 * Constants.MODEL_INPUT_SIZE * Constants.MODEL_INPUT_SIZE * 3
        ).apply { order(ByteOrder.nativeOrder()) }

        val pixels = IntArray(Constants.MODEL_INPUT_SIZE * Constants.MODEL_INPUT_SIZE)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        for (px in pixels) {
            buf.putFloat(Color.red(px)   / 255f)
            buf.putFloat(Color.green(px) / 255f)
            buf.putFloat(Color.blue(px)  / 255f)
        }
        buf.rewind()
        return buf
    }

    fun close() {
        ready = false
        interpreter?.close(); interpreter = null
        gpuDelegate?.close(); gpuDelegate = null
    }
}
