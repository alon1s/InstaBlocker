package com.alon.instablock

import android.content.Context
import java.util.Calendar

object PrefsManager {
    private const val PREFS_NAME = "instablock_prefs"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // --- Basic block toggle ---
    fun isBlocked(context: Context, target: BlockTarget): Boolean =
        prefs(context).getBoolean(target.prefsKey, false)

    fun setBlocked(context: Context, target: BlockTarget, value: Boolean) {
        prefs(context).edit().putBoolean(target.prefsKey, value).apply()
    }

    // --- Daily minute quota (0 = full block) ---
    fun dailyLimitMinutes(context: Context, target: BlockTarget): Int =
        prefs(context).getInt("${target.prefsKey}_limit", 0)

    fun setDailyLimitMinutes(context: Context, target: BlockTarget, minutes: Int) {
        prefs(context).edit().putInt("${target.prefsKey}_limit", minutes).apply()
    }

    // --- Rolling hourly minute quota (0 = no hourly limit) ---
    fun hourlyLimitMinutes(context: Context, target: BlockTarget): Int {
        // 0 = no hourly limit, matching the same convention as daily/swipe
        // limits (only a positive number turns the restriction on).
        // Do NOT default this above 0 - that silently blocked everyone
        // after N minutes regardless of whether "Block X" was even on.
        return prefs(context).getInt("hourly_limit_${target.prefsKey}", 0)
    }

    fun setHourlyLimitMinutes(context: Context, target: BlockTarget, minutes: Int) {
        prefs(context).edit().putInt("hourly_limit_${target.prefsKey}", minutes).apply()
    }

    // --- Feature 3: daily swipe quota (0 = unlimited) ---
    fun maxSwipesPerDay(context: Context, target: BlockTarget): Int =
        prefs(context).getInt("${target.prefsKey}_max_swipes", 0)

    fun setMaxSwipesPerDay(context: Context, target: BlockTarget, count: Int) {
        prefs(context).edit().putInt("${target.prefsKey}_max_swipes", count).apply()
    }

    // --- Feature 2: 10-second "breathing" delay before access ---
    fun isFrictionDelayEnabled(context: Context, target: BlockTarget): Boolean =
        prefs(context).getBoolean("${target.prefsKey}_friction", false)

    fun setFrictionDelayEnabled(context: Context, target: BlockTarget, value: Boolean) {
        prefs(context).edit().putBoolean("${target.prefsKey}_friction", value).apply()
    }

    // --- Feature 4: allowed time window (minutes-since-midnight, 0-1440) ---
    fun isScheduleEnabled(context: Context, target: BlockTarget): Boolean =
        prefs(context).getBoolean("${target.prefsKey}_schedule_enabled", false)

    fun setScheduleEnabled(context: Context, target: BlockTarget, value: Boolean) {
        prefs(context).edit().putBoolean("${target.prefsKey}_schedule_enabled", value).apply()
    }

    fun scheduleStartMinute(context: Context, target: BlockTarget): Int =
        prefs(context).getInt("${target.prefsKey}_schedule_start", 0)

    fun scheduleEndMinute(context: Context, target: BlockTarget): Int =
        prefs(context).getInt("${target.prefsKey}_schedule_end", 24 * 60)

    fun setScheduleWindow(context: Context, target: BlockTarget, startMinute: Int, endMinute: Int) {
        prefs(context).edit()
            .putInt("${target.prefsKey}_schedule_start", startMinute)
            .putInt("${target.prefsKey}_schedule_end", endMinute)
            .apply()
    }

    /**
     * true if we're currently inside the allowed window, or there's no
     * schedule restriction at all. Supports windows that cross midnight
     * (e.g. 22:00-02:00).
     */
    fun isWithinScheduleWindow(context: Context, target: BlockTarget): Boolean {
        if (!isScheduleEnabled(context, target)) return true
        val start = scheduleStartMinute(context, target)
        val end = scheduleEndMinute(context, target)
        val cal = Calendar.getInstance()
        val now = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        return if (start <= end) now in start until end else (now >= start || now < end)
    }

    // --- Feature 1: intent check ("Why are you here?") ---
    fun isIntentCheckEnabled(context: Context): Boolean =
        prefs(context).getBoolean("intent_check_enabled", false)

    fun setIntentCheckEnabled(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean("intent_check_enabled", value).apply()
    }

    /** How many minutes since leaving Instagram count as a "fresh launch" that triggers the prompt. */
    fun intentCheckThresholdMinutes(context: Context): Int =
        prefs(context).getInt("intent_check_threshold_minutes", 5)

    fun setIntentCheckThresholdMinutes(context: Context, minutes: Int) {
        prefs(context).edit().putInt("intent_check_threshold_minutes", minutes).apply()
    }

    fun lastExitMillis(context: Context): Long =
        prefs(context).getLong("last_instagram_exit_millis", 0L)

    fun setLastExitMillis(context: Context, millis: Long) {
        prefs(context).edit().putLong("last_instagram_exit_millis", millis).apply()
    }

    // --- Grayscale ---
    fun isGrayscaleEnabled(context: Context): Boolean =
        prefs(context).getBoolean("grayscale_enabled", false)

    fun setGrayscaleEnabled(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean("grayscale_enabled", value).apply()
    }
}
