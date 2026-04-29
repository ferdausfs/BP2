package com.guardian.shield.service.detection

import android.content.Context
import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

@Singleton
class AiDetector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val rulesEngine: RulesEngine
) {
    companion object {
        private const val NSFW_CONFIDENCE_THRESHOLD = 0.65f
        
        // ML Kit labels that indicate NSFW/inappropriate content
        private val SUSPICIOUS_LABELS = setOf(
            "Skin", "Lingerie", "Swimwear", "Underwear",
            "Brassiere", "Bikini", "Flesh", "Thigh",
            "Abdomen", "Chest", "Navel", "Bare"
        )
        
        // Sensitive context labels
        private val CONTEXT_LABELS = setOf(
            "Bedroom", "Bed", "Bathroom", "Shower"
        )
    }

    private val imageLabeler by lazy {
        val options = ImageLabelerOptions.Builder()
            .setConfidenceThreshold(0.5f)
            .build()
        ImageLabeling.getClient(options)
    }

    /**
     * Analyze text content for inappropriate keywords/patterns
     */
    suspend fun analyzeText(text: String, packageName: String): DetectionResult =
        withContext(Dispatchers.Default) {
            if (text.isBlank()) return@withContext DetectionResult.Safe
            
            val keywordMatch = rulesEngine.checkKeywords(text)
            if (keywordMatch != null) {
                return@withContext DetectionResult.Blocked(
                    reason = "Keyword: ${keywordMatch.keyword}",
                    confidence = 1.0f,
                    category = keywordMatch.category
                )
            }

            // Pattern-based detection (URLs, suspicious patterns)
            val patternMatch = rulesEngine.checkPatterns(text)
            if (patternMatch != null) {
                return@withContext DetectionResult.Blocked(
                    reason = "Pattern: ${patternMatch.name}",
                    confidence = 0.9f,
                    category = patternMatch.category
                )
            }

            DetectionResult.Safe
        }

    /**
     * Analyze image content using ML Kit
     */
    suspend fun analyzeImage(bitmap: Bitmap): DetectionResult =
        withContext(Dispatchers.Default) {
            try {
                val image = InputImage.fromBitmap(bitmap, 0)
                val labels = labelImage(image)
                
                var suspiciousScore = 0f
                var contextScore = 0f
                val matchedLabels = mutableListOf<String>()

                labels.forEach { label ->
                    when {
                        SUSPICIOUS_LABELS.any { it.equals(label.text, ignoreCase = true) } -> {
                            suspiciousScore = maxOf(suspiciousScore, label.confidence)
                            matchedLabels.add("${label.text}(${(label.confidence * 100).toInt()}%)")
                        }
                        CONTEXT_LABELS.any { it.equals(label.text, ignoreCase = true) } -> {
                            contextScore = maxOf(contextScore, label.confidence)
                        }
                    }
                }

                // Combined score with context
                val finalScore = suspiciousScore + (contextScore * 0.3f)

                if (finalScore >= NSFW_CONFIDENCE_THRESHOLD) {
                    DetectionResult.Blocked(
                        reason = "Image: ${matchedLabels.joinToString()}",
                        confidence = finalScore.coerceAtMost(1f),
                        category = "nsfw_image"
                    )
                } else {
                    DetectionResult.Safe
                }
            } catch (e: Exception) {
                DetectionResult.Safe
            }
        }

    private suspend fun labelImage(image: InputImage): List<com.google.mlkit.vision.label.ImageLabel> =
        suspendCancellableCoroutine { cont ->
            imageLabeler.process(image)
                .addOnSuccessListener { labels -> cont.resume(labels) }
                .addOnFailureListener { cont.resume(emptyList()) }
        }

    sealed class DetectionResult {
        object Safe : DetectionResult()
        data class Blocked(
            val reason: String,
            val confidence: Float,
            val category: String
        ) : DetectionResult()
    }
}