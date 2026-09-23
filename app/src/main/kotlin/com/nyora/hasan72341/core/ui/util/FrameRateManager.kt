package com.nyora.hasan72341.core.ui.util

import android.os.Build
import android.view.Window
import androidx.annotation.RequiresApi

/**
 * Manages display frame rate for premium motion.
 * Requests maximum refresh rate during interactions and animations,
 * ensuring spring physics and transitions render at 120Hz+ on capable devices.
 */
object FrameRateManager {

    /**
     * Boost the frame rate to the display's maximum for the given window.
     * Call once in Activity.onCreate() after setContentView().
     */
    fun boost(window: Window) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            boostApi30(window)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            boostApi35(window)
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun boostApi30(window: Window) {
        // Request highest available refresh rate for the window
        window.attributes = window.attributes.apply {
            preferMinimalPostProcessing = true
        }
        // Request max refresh rate via window layout params
        window.decorView.post {
            try {
                val display = window.context.display ?: return@post
                val maxRate = display.supportedModes
                    .maxByOrNull { it.refreshRate }
                    ?.refreshRate ?: return@post

                if (maxRate > 60f) {
                    // Set preferred display mode to highest refresh rate
                    val bestMode = display.supportedModes
                        .filter { it.refreshRate >= maxRate - 1f }
                        .maxByOrNull { it.refreshRate }
                    bestMode?.let { mode ->
                        window.attributes = window.attributes.apply {
                            preferredDisplayModeId = mode.modeId
                        }
                    }
                }
            } catch (_: Exception) {
                // Display may not be available; non-critical
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    private fun boostApi35(window: Window) {
        try {
            window.setFrameRateBoostOnTouchEnabled(true)
        } catch (_: Throwable) {
            // OEM builds may reject the request; never fail activity creation over a hint.
        }
    }
}
