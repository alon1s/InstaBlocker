package com.alon.instablock

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Draws a full-screen overlay on top of Instagram using TYPE_ACCESSIBILITY_OVERLAY -
 * a window type accessibility services are allowed to use without a separate
 * "draw over other apps" permission. Only one overlay is active at a time,
 * identified by a "tag" so we don't redraw on every accessibility event
 * (avoids flicker and wasted work).
 */
class OverlayManager(private val service: BlockerAccessibilityService) {
    private val windowManager =
        service.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var overlayRoot: FrameLayout? = null
    private var activeTag: String? = null
    private var countdownLabel: TextView? = null

    private fun dp(value: Int) = (value * service.resources.displayMetrics.density).toInt()

    private fun screenHeightPx(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowManager.currentWindowMetrics.bounds.height()
        } else {
            val m = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(m)
            m.heightPixels
        }

    /**
     * [coverHeightPx] = null -> cover the whole screen.
     * Otherwise the overlay is only that tall, starting at the top, so the
     * area below it (Instagram's tab bar) stays visible and tappable.
     */
    private fun baseParams(coverHeightPx: Int? = null) = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        coverHeightPx ?: WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.OPAQUE
    ).apply { gravity = Gravity.TOP or Gravity.START }

    private fun removeOverlayView() {
        overlayRoot?.let { runCatching { windowManager.removeView(it) } }
        overlayRoot = null
        activeTag = null
        countdownLabel = null
    }

    private fun replaceContent(content: View, tag: String, coverHeightPx: Int? = null) {
        removeOverlayView() // not hide(): no unmute/mute blip while swapping overlays
        val root = FrameLayout(service).apply {
            setBackgroundColor(Color.BLACK)
            addView(
                content,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER
                )
            )
        }
        windowManager.addView(root, baseParams(coverHeightPx))
        overlayRoot = root
        activeTag = tag
        // Whatever is playing underneath (a Reel) must not keep making noise.
        AudioMuteManager.mute(service)
    }

    fun isShowing(tag: String) = activeTag == tag

    fun hide() {
        removeOverlayView()
        AudioMuteManager.unmute(service)
    }

    /**
     * Block screen. It stops above Instagram's tab bar ([navTopPx] = top of
     * the bar if known, otherwise a safe fallback) so you can still tap
     * Home / DMs / Profile, and it has a Back button.
     */
    fun showBlockMessage(tag: String, message: String, navTopPx: Int?, onGoBack: () -> Unit) {
        if (activeTag == tag) return
        val coverHeight = navTopPx?.takeIf { it > 0 } ?: (screenHeightPx() - dp(112))
        val container = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(TextView(service).apply {
                text = message
                setTextColor(Color.WHITE)
                textSize = 18f
                gravity = Gravity.CENTER
            })
            addView(TextView(service).apply {
                text = "You can still use the tabs below."
                setTextColor(Color.GRAY)
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(0, dp(8), 0, dp(20))
            })
            addView(Button(service).apply {
                text = "Go back"
                setOnClickListener { onGoBack() }
            })
        }
        replaceContent(container, tag, coverHeight)
    }

    /** Feature 1: "Why are you here?" screen with selectable buttons. */
    fun showIntentCheck(tag: String, options: List<String>, onOptionSelected: (String) -> Unit) {
        if (activeTag == tag) return
        val container = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        container.addView(TextView(service).apply {
            text = "Why are you here?"
            setTextColor(Color.WHITE)
            textSize = 22f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 48)
        })
        options.forEach { label ->
            container.addView(Button(service).apply {
                text = label
                setOnClickListener { onOptionSelected(label) }
            })
        }
        replaceContent(container, tag)
    }

    /** Feature 2: "Breathe" screen with a countdown. Update the number via updateCountdown. */
    fun showCountdown(tag: String, initialSeconds: Int) {
        if (activeTag == tag) return
        val label = TextView(service).apply {
            text = initialSeconds.toString()
            setTextColor(Color.WHITE)
            textSize = 48f
            gravity = Gravity.CENTER
        }
        val container = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(TextView(service).apply {
                text = "Take a breath before you continue..."
                setTextColor(Color.GRAY)
                textSize = 16f
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, 24)
            })
            addView(label)
        }
        replaceContent(container, tag)
        countdownLabel = label
    }

    fun updateCountdown(secondsLeft: Int) {
        countdownLabel?.text = secondsLeft.toString()
    }

    // --- Floating timer HUD ---
    // A small always-on-top panel, separate from the full-screen overlays
    // above (its own fields, never touched by hide()). Shows the elapsed
    // app time plus one line per blocked target with what's left.
    private var hudContainer: LinearLayout? = null
    private var hudElapsedLabel: TextView? = null
    private var hudSummaryLabel: TextView? = null

    private fun hudParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        // NOT_TOUCHABLE: this panel must never intercept touches meant for Instagram.
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.END
        x = 24
        y = 80
    }

    fun updateFloatingTimer(elapsedText: String, summaries: List<String>) {
        val summaryText = summaries.joinToString("\n")

        val existing = hudContainer
        if (existing != null) {
            hudElapsedLabel?.text = elapsedText
            hudSummaryLabel?.apply {
                text = summaryText
                visibility = if (summaryText.isEmpty()) View.GONE else View.VISIBLE
            }
            return
        }

        val elapsed = TextView(service).apply {
            text = elapsedText
            setTextColor(Color.WHITE)
            textSize = 12f
        }
        val summary = TextView(service).apply {
            text = summaryText
            setTextColor(Color.LTGRAY)
            textSize = 11f
            visibility = if (summaryText.isEmpty()) View.GONE else View.VISIBLE
        }
        val container = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#CC000000"))
            setPadding(24, 14, 24, 14)
            addView(elapsed)
            addView(summary)
        }
        if (runCatching { windowManager.addView(container, hudParams()) }.isSuccess) {
            hudContainer = container
            hudElapsedLabel = elapsed
            hudSummaryLabel = summary
        }
    }

    fun removeFloatingTimer() {
        hudContainer?.let { runCatching { windowManager.removeView(it) } }
        hudContainer = null
        hudElapsedLabel = null
        hudSummaryLabel = null
    }

    /** Remove everything this manager has on screen (used when the service stops). */
    fun destroy() {
        hide()
        removeFloatingTimer()
    }
}
