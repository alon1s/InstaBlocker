package com.alon.instablock

import android.content.Context
import android.media.AudioManager

/**
 * Mutes the media stream while a full-screen overlay is up, so a blocked
 * Reel doesn't keep playing audio underneath it.
 *
 * - Only unmutes what THIS app muted (flag is persisted, so it also
 *   recovers if the process was killed while muted).
 * - If media was already muted / at zero volume, it leaves it alone.
 */
object AudioMuteManager {
    private const val PREFS = "instablock_audio"
    private const val FLAG_MUTED_BY_APP = "muted_by_app"

    private fun audio(context: Context) =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    fun mute(context: Context) {
        val am = audio(context)
        try {
            if (!am.isStreamMute(AudioManager.STREAM_MUSIC)) {
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putBoolean(FLAG_MUTED_BY_APP, true).apply()
            }
        } catch (e: SecurityException) {
            // Some device/Do-Not-Disturb configurations refuse volume changes.
        }
    }

    fun unmute(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(FLAG_MUTED_BY_APP, false)) return
        try {
            audio(context).adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
        } catch (e: SecurityException) {
        }
        prefs.edit().putBoolean(FLAG_MUTED_BY_APP, false).apply()
    }
}
