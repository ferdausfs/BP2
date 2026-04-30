package com.haramblur.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.util.Log
import com.haramblur.R

/**
 * Full-screen blur overlay via WindowManager TYPE_ACCESSIBILITY_OVERLAY.
 * No SYSTEM_ALERT_WINDOW permission needed — works from AccessibilityService.
 */
class BlurOverlayManager(private val context: Context) {

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var overlayView: View? = null
    @Volatile private var isShowing = false

    fun showBlur() {
        if (isShowing) return
        try {
            val view = LayoutInflater.from(context).inflate(R.layout.overlay_blur, null)
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.TOP or Gravity.START }

            wm.addView(view, params)
            overlayView = view
            isShowing = true
            Log.d("HaramBlur.Overlay", "Overlay shown")
        } catch (e: Exception) {
            Log.e("HaramBlur.Overlay", "showBlur failed: ${e.message}")
        }
    }

    fun hideBlur() {
        if (!isShowing) return
        try {
            overlayView?.let { wm.removeView(it) }
            overlayView = null
            isShowing = false
            Log.d("HaramBlur.Overlay", "Overlay hidden")
        } catch (e: Exception) {
            Log.e("HaramBlur.Overlay", "hideBlur failed: ${e.message}")
        }
    }

    fun isVisible() = isShowing

    fun destroy() = hideBlur()
}
