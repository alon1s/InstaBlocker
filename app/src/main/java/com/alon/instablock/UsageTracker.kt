package com.alon.instablock

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Tracks usage per target in SECONDS (not whole minutes). Counting whole
 * minutes used to throw away any partial minute every time a session
 * stopped, so frequent stop/start cycles badly undercounted real usage.
 *
 * Known, accepted edge case: a session that straddles midnight or the top
 * of an hour is credited entirely to the bucket it ends in.
 */
object UsageTracker {
    private const val PREFS_NAME = "instablock_usage"
    private var sessionStartMillis: Long? = null
    private var activeTarget: BlockTarget? = null

    private fun today(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    private fun currentHourId(): Long = System.currentTimeMillis() / 3_600_000L

    private fun dailyKey(target: BlockTarget) = "s_${today()}_${target.prefsKey}"
    private fun hourlyKey(target: BlockTarget) = "s_hour_${currentHourId()}_${target.prefsKey}"
    private fun swipesKey(target: BlockTarget) = "${today()}_${target.prefsKey}_swipes"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun currentActiveTarget(): BlockTarget? = activeTarget

    fun startSession(context: Context, target: BlockTarget) {
        if (activeTarget == target) return
        stopSession(context)
        activeTarget = target
        sessionStartMillis = System.currentTimeMillis()
    }

    fun stopSession(context: Context) {
        val start = sessionStartMillis ?: return
        val target = activeTarget ?: return
        val elapsedSeconds = ((System.currentTimeMillis() - start) / 1000).toInt()
        if (elapsedSeconds > 0) {
            val p = prefs(context)
            p.edit()
                .putInt(dailyKey(target), p.getInt(dailyKey(target), 0) + elapsedSeconds)
                .putInt(hourlyKey(target), p.getInt(hourlyKey(target), 0) + elapsedSeconds)
                .apply()
        }
        sessionStartMillis = null
        activeTarget = null
    }

    private fun liveElapsedSeconds(target: BlockTarget): Int {
        if (activeTarget != target) return 0
        val start = sessionStartMillis ?: return 0
        return ((System.currentTimeMillis() - start) / 1000).toInt()
    }

    /** Saved seconds + the session currently in progress (if it's this target). */
    fun liveSecondsUsedToday(context: Context, target: BlockTarget): Int =
        prefs(context).getInt(dailyKey(target), 0) + liveElapsedSeconds(target)

    fun liveSecondsUsedThisHour(context: Context, target: BlockTarget): Int =
        prefs(context).getInt(hourlyKey(target), 0) + liveElapsedSeconds(target)

    /**
     * Feature 3: called on every TYPE_VIEW_SCROLLED event while on this
     * target. An approximation of a swipe - one gesture can fire several
     * scroll events.
     */
    fun recordSwipe(context: Context, target: BlockTarget) {
        val p = prefs(context)
        p.edit().putInt(swipesKey(target), p.getInt(swipesKey(target), 0) + 1).apply()
    }

    fun swipesUsedToday(context: Context, target: BlockTarget): Int =
        prefs(context).getInt(swipesKey(target), 0)
}
