package com.alon.instablock

import android.content.Context
import android.provider.Settings

/**
 * Toggles the device-wide grayscale (Accessibility > Color correction)
 * setting. This is a whole-device setting, not just Instagram - there's no
 * way to filter just one other app's rendering without system-level access.
 *
 * Requires WRITE_SECURE_SETTINGS, granted once via adb (see README).
 *
 * Writes only on real transitions. Re-writing the setting on every
 * accessibility event (e.g. while typing) makes the display re-apply its
 * color transform each time, which shows up as flicker. It also remembers
 * whether THIS app turned grayscale on, so it never switches off a color
 * correction mode you enabled yourself.
 */
object GrayscaleManager {
    private const val KEY = "accessibility_display_daltonizer_enabled"
    private const val PREFS = "instablock_grayscale"
    private const val FLAG_APPLIED_BY_APP = "applied_by_app"

    fun setEnabled(context: Context, enabled: Boolean) {
        val resolver = context.contentResolver
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        try {
            val current = Settings.Secure.getInt(resolver, KEY, 0) == 1
            if (enabled) {
                if (!current) {
                    Settings.Secure.putInt(resolver, KEY, 1)
                    prefs.edit().putBoolean(FLAG_APPLIED_BY_APP, true).apply()
                }
            } else {
                if (current && prefs.getBoolean(FLAG_APPLIED_BY_APP, false)) {
                    Settings.Secure.putInt(resolver, KEY, 0)
                }
                prefs.edit().putBoolean(FLAG_APPLIED_BY_APP, false).apply()
            }
        } catch (e: SecurityException) {
            // WRITE_SECURE_SETTINGS hasn't been granted yet - see README
        }
    }
}
