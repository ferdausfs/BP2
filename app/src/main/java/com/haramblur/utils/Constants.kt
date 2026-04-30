package com.haramblur.utils

object Constants {
    const val PREFS_NAME        = "haramblur_prefs"
    const val KEY_ENABLED       = "enabled"
    const val KEY_STRICTNESS    = "strictness"
    const val KEY_BLUR_IMAGES   = "blur_images"
    const val KEY_BLUR_FEMALE   = "blur_female"
    const val KEY_BLUR_MALE     = "blur_male"
    const val KEY_WHITELIST     = "whitelist"
    const val KEY_BLUR_AMOUNT   = "blur_amount"

    const val DEFAULT_STRICTNESS  = 0.5f
    const val DEFAULT_BLUR_AMOUNT = 20

    const val MODEL_FILE          = "nsfw_model.tflite"
    const val MODEL_INPUT_SIZE    = 224
    const val MODEL_OUTPUT_CLASSES = 5

    // Same class indices as HaramBlur extension detector.js
    const val CLASS_DRAWING = 0
    const val CLASS_HENTAI  = 1
    const val CLASS_NEUTRAL = 2
    const val CLASS_PORN    = 3
    const val CLASS_SEXY    = 4

    // Debounce between screenshots on same window (ms)
    const val SCREENSHOT_DEBOUNCE_MS = 600L

    // If >88% pixels are black → DRM/blank frame → skip
    const val BLACK_FRAME_THRESHOLD = 0.88f

    const val NOTIFICATION_CHANNEL_ID = "haramblur_channel"
    const val NOTIFICATION_ID         = 1001
}
