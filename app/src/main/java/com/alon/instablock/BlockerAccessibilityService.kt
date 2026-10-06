package com.alon.instablock

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Rect
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import java.util.Locale

private const val INSTAGRAM_PACKAGE = "com.instagram.android"
// Best-effort id of Instagram's bottom tab bar. If it isn't found, the block
// screen falls back to leaving a fixed strip at the bottom uncovered.
private const val TAB_BAR_ID = "com.instagram.android:id/tab_bar"
private const val FRICTION_DELAY_SECONDS = 10

// A "no target" reading must persist this long before it counts as leaving.
private const val TARGET_DEBOUNCE_MS = 1000L

// The breathing screen only re-appears after you've been away from that target this long.
private const val FRICTION_REENTRY_GAP_MS = 3 * 60_000L

// Leaving Instagram is confirmed after this long (ignores transient windows).
private const val EXIT_DEBOUNCE_MS = 500L

enum class BlockTarget(val prefsKey: String, val label: String) {
    REELS("block_reels", "Reels"),
    EXPLORE("block_explore", "Explore"),
    STORIES("block_stories", "Stories"),
    FEED("block_feed", "Feed")
}

private class BlockReason(val kind: String, val message: String)

class BlockerAccessibilityService : AccessibilityService() {

    private val overlay by lazy { OverlayManager(this) }

    private var instagramSessionActive = false
    private var intentCheckDismissed = true
    private var currentSessionStartMillis = 0L

    private var countdownTimer: CountDownTimer? = null
    private var countdownTarget: BlockTarget? = null
    private val frictionCleared = mutableSetOf<BlockTarget>()
    private val lastSeenOnTargetMillis = mutableMapOf<BlockTarget, Long>()

    private var effectiveTarget: BlockTarget? = null
    private var lastNonNullTargetAtMillis = 0L
    private var lastDebug = ""

    private var grayscaleApplied = false

    private var exitPending = false
    private val exitRunnable = Runnable {
        exitPending = false
        if (instagramSessionActive) endInstagramSession()
    }

    private val timerHandler = Handler(Looper.getMainLooper())
    private val updateTimerRunnable = object : Runnable {
        override fun run() {
            if (!instagramSessionActive) return

            val elapsedMs = System.currentTimeMillis() - currentSessionStartMillis
            val elapsedText = String.format(
                Locale.US, "%02d:%02d", (elapsedMs / 60_000).toInt(), ((elapsedMs / 1000) % 60).toInt()
            )

            // Re-check which tab we're on EVERY tick, not only when Instagram
            // happens to send an event. This is what guarantees the limit
            // screen clears within ~1s of leaving the limited tab, and that a
            // limit crossed mid-session blocks right away.
            if (intentCheckDismissed) {
                val root = rootInActiveWindow
                if (root != null && root.packageName == INSTAGRAM_PACKAGE) {
                    applyTargetState(resolveEffectiveTarget(detect(root)))
                }
            }

            overlay.updateFloatingTimer(elapsedText, buildLimitSummaries())
            timerHandler.postDelayed(this, 1000)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        // Recover if the process was killed while audio/grayscale were changed.
        AudioMuteManager.unmute(this)
        GrayscaleManager.setEnabled(this, false)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val currentPackage = event.packageName?.toString() ?: ""

        if (currentPackage == "com.android.systemui" ||
            currentPackage.contains("inputmethod") ||
            currentPackage.contains("honeyboard") ||
            currentPackage.contains("keyboard") ||
            currentPackage == "com.alon.instablock"
        ) return

        if (isFromInputMethodWindow(event)) return

        if (currentPackage != INSTAGRAM_PACKAGE) {
            if (instagramSessionActive && !exitPending) {
                exitPending = true
                timerHandler.postDelayed(exitRunnable, EXIT_DEBOUNCE_MS)
            }
            return
        }

        // A heads-up notification can report Instagram's package while another
        // app is in front. Make sure Instagram is really the focused app.
        if (!isInstagramActuallyForeground()) return

        if (exitPending) {
            timerHandler.removeCallbacks(exitRunnable)
            exitPending = false
        }

        if (!instagramSessionActive) {
            instagramSessionActive = true
            currentSessionStartMillis = System.currentTimeMillis()
            timerHandler.post(updateTimerRunnable)

            val gapMinutes = (System.currentTimeMillis() - PrefsManager.lastExitMillis(this)) / 60_000
            val isFreshLaunch = gapMinutes >= PrefsManager.intentCheckThresholdMinutes(this)
            intentCheckDismissed = !(PrefsManager.isIntentCheckEnabled(this) && isFreshLaunch)
        }

        updateGrayscale()

        if (!intentCheckDismissed) {
            overlay.showIntentCheck(
                tag = "intent_check",
                options = listOf("Checking DMs", "Posting a Story", "Just browsing")
            ) {
                intentCheckDismissed = true
                overlay.hide()
            }
            return
        }

        val root = rootInActiveWindow ?: return
        val target = resolveEffectiveTarget(detect(root))

        if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED && target != null) {
            UsageTracker.recordSwipe(this, target)
        }

        applyTargetState(target)
    }

    /**
     * Everything that depends on "which target am I on right now". Called from
     * both the event path and the 1-second tick, so the two can never disagree.
     */
    private fun applyTargetState(target: BlockTarget?) {
        if (target != countdownTarget) cancelCountdown()
        if (target != null) noteVisit(target)

        // Not on a limited screen (Home, Explore, DMs, Profile...): lift the block.
        if (target == null || !PrefsManager.isBlocked(this, target)) {
            UsageTracker.stopSession(this)
            overlay.hide()
            return
        }

        blockReason(target)?.let { reason ->
            UsageTracker.stopSession(this)
            showBlock(target, reason)
            return
        }

        if (PrefsManager.isFrictionDelayEnabled(this, target) && target !in frictionCleared) {
            UsageTracker.stopSession(this)
            if (countdownTarget != target) startCountdown(target)
            return
        }

        overlay.hide()
        UsageTracker.startSession(this, target)
    }

    private fun detect(root: AccessibilityNodeInfo): BlockTarget? {
        val d = TabDetector.detect(root)
        if (d.debug != lastDebug) {
            lastDebug = d.debug
            PrefsManager.setLastDetectionDebug(this, d.debug)
        }
        return d.target
    }

    private fun showBlock(target: BlockTarget, reason: BlockReason) {
        val tag = "block:${target.prefsKey}:${reason.kind}"
        if (overlay.isShowing(tag)) return
        overlay.showBlockMessage(tag, reason.message, tabBarTopPx()) {
            performGlobalAction(GLOBAL_ACTION_BACK)
        }
    }

    private fun tabBarTopPx(): Int? {
        val root = rootInActiveWindow ?: return null
        val node = root.findAccessibilityNodeInfosByViewId(TAB_BAR_ID)?.firstOrNull() ?: return null
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        return bounds.top.takeIf { it > 0 }
    }

    override fun onInterrupt() {
        cancelCountdown()
        overlay.hide()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        shutdownCleanly()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        shutdownCleanly()
        super.onDestroy()
    }

    private fun shutdownCleanly() {
        timerHandler.removeCallbacks(updateTimerRunnable)
        timerHandler.removeCallbacks(exitRunnable)
        exitPending = false
        cancelCountdown()
        UsageTracker.stopSession(this)
        overlay.destroy()
        AudioMuteManager.unmute(this)
        GrayscaleManager.setEnabled(this, false)
        grayscaleApplied = false
        instagramSessionActive = false
        effectiveTarget = null
    }

    private fun endInstagramSession() {
        PrefsManager.setLastExitMillis(this, System.currentTimeMillis())
        timerHandler.removeCallbacks(updateTimerRunnable)
        overlay.removeFloatingTimer()
        instagramSessionActive = false
        effectiveTarget = null
        cancelCountdown()
        UsageTracker.stopSession(this)
        overlay.hide()
        GrayscaleManager.setEnabled(this, false)
        grayscaleApplied = false
    }

    private fun updateGrayscale() {
        val wanted = PrefsManager.isGrayscaleEnabled(this)
        if (wanted && !grayscaleApplied) {
            GrayscaleManager.setEnabled(this, true)
            grayscaleApplied = true
        } else if (!wanted && grayscaleApplied) {
            GrayscaleManager.setEnabled(this, false)
            grayscaleApplied = false
        }
    }

    private fun noteVisit(target: BlockTarget) {
        val now = System.currentTimeMillis()
        val last = lastSeenOnTargetMillis[target]
        if (last == null || now - last > FRICTION_REENTRY_GAP_MS) frictionCleared.remove(target)
        lastSeenOnTargetMillis[target] = now
    }

    private fun isFromInputMethodWindow(event: AccessibilityEvent): Boolean {
        val id = event.windowId
        if (id == -1) return false
        return windows?.any { it.id == id && it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD } == true
    }

    private fun isInstagramActuallyForeground(): Boolean {
        val windowList = windows
        if (windowList.isNullOrEmpty()) return true
        return windowList.any { w ->
            w.type == AccessibilityWindowInfo.TYPE_APPLICATION &&
                w.isFocused &&
                w.root?.packageName == INSTAGRAM_PACKAGE
        }
    }

    private fun resolveEffectiveTarget(rawTarget: BlockTarget?): BlockTarget? {
        val now = System.currentTimeMillis()
        if (rawTarget != null) {
            effectiveTarget = rawTarget
            lastNonNullTargetAtMillis = now
            return rawTarget
        }
        val last = effectiveTarget
        return if (last != null && now - lastNonNullTargetAtMillis < TARGET_DEBOUNCE_MS) {
            last
        } else {
            effectiveTarget = null
            null
        }
    }

    private fun blockReason(target: BlockTarget): BlockReason? {
        if (!PrefsManager.isWithinScheduleWindow(this, target)) {
            return BlockReason("schedule", "${target.label} is only available during your set hours")
        }

        val dailyLimit = PrefsManager.dailyLimitMinutes(this, target)
        if (dailyLimit == 0) return BlockReason("full", "${target.label} is blocked")

        if (UsageTracker.liveSecondsUsedToday(this, target) >= dailyLimit * 60) {
            return BlockReason("daily", "${target.label} daily limit reached.\nSee you tomorrow.")
        }

        val hourlyLimit = PrefsManager.hourlyLimitMinutes(this, target)
        if (hourlyLimit > 0 && UsageTracker.liveSecondsUsedThisHour(this, target) >= hourlyLimit * 60) {
            return BlockReason("hourly", "${target.label} hourly limit reached.\nTake a break.")
        }

        val swipeLimit = PrefsManager.maxSwipesPerDay(this, target)
        if (swipeLimit > 0 && UsageTracker.swipesUsedToday(this, target) >= swipeLimit) {
            return BlockReason("swipes", "${target.label} swipe quota reached for today.")
        }
        return null
    }

    private fun buildLimitSummaries(): List<String> {
        return BlockTarget.values().mapNotNull { t ->
            if (!PrefsManager.isBlocked(this, t)) return@mapNotNull null
            val marker = if (t == effectiveTarget) "▶ " else ""

            if (!PrefsManager.isWithinScheduleWindow(this, t)) {
                return@mapNotNull "$marker${t.label}: outside allowed hours"
            }
            val dailyLimit = PrefsManager.dailyLimitMinutes(this, t)
            if (dailyLimit == 0) return@mapNotNull "$marker${t.label}: blocked"

            val dailyLeft = dailyLimit * 60 - UsageTracker.liveSecondsUsedToday(this, t)
            val hourlyLimit = PrefsManager.hourlyLimitMinutes(this, t)
            val hourlyLeft = if (hourlyLimit > 0) {
                hourlyLimit * 60 - UsageTracker.liveSecondsUsedThisHour(this, t)
            } else Int.MAX_VALUE

            val leftSeconds = minOf(dailyLeft, hourlyLeft).coerceAtLeast(0)
            val hourlyIsBinding = hourlyLeft < dailyLeft

            buildString {
                append(marker).append(t.label).append(": ")
                append(String.format(Locale.US, "%d:%02d", leftSeconds / 60, leftSeconds % 60))
                append(" left")
                if (hourlyIsBinding) append(" (hourly)")

                val swipeLimit = PrefsManager.maxSwipesPerDay(this@BlockerAccessibilityService, t)
                if (swipeLimit > 0) {
                    val swipesLeft = (swipeLimit - UsageTracker.swipesUsedToday(this@BlockerAccessibilityService, t))
                        .coerceAtLeast(0)
                    append(" · ").append(swipesLeft).append(" swipes")
                }
            }
        }
    }

    private fun startCountdown(target: BlockTarget) {
        countdownTarget = target
        overlay.showCountdown("countdown:${target.prefsKey}", FRICTION_DELAY_SECONDS)
        countdownTimer = object : CountDownTimer(FRICTION_DELAY_SECONDS * 1000L, 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                overlay.updateCountdown((millisUntilFinished / 1000L).toInt() + 1)
            }

            override fun onFinish() {
                frictionCleared.add(target)
                countdownTarget = null
                countdownTimer = null
                overlay.hide()
            }
        }.start()
    }

    private fun cancelCountdown() {
        if (countdownTimer != null) {
            countdownTimer?.cancel()
            countdownTimer = null
            countdownTarget = null
            overlay.hide()
        }
    }
}
