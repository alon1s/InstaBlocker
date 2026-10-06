package com.alon.instablock

import android.view.accessibility.AccessibilityNodeInfo

class Detection(val target: BlockTarget?, val debug: String)

/**
 * Decides which Instagram TAB is selected, instead of looking for views that
 * happen to be on screen.
 *
 * Why: the old check looked for a view id ("reels_tray_container") that also
 * appears on the Home feed and Explore. So Reels was "detected" there too,
 * the block screen appeared on those pages, and tapping Home didn't help.
 * The selected bottom-bar tab is the one signal that's different on each
 * page, and it stays put when you open a reel from Explore or the feed
 * (the Explore / Home tab remains the selected one) - which is exactly the
 * "doesn't count as Reels" behaviour we want.
 */
object TabDetector {
    private const val ID_PREFIX = "com.instagram.android:id/"

    private val knownTabIds = listOf(
        BlockTarget.REELS to listOf("clips_tab", "reels_tab"),
        BlockTarget.EXPLORE to listOf("search_tab", "explore_tab"),
        BlockTarget.FEED to listOf("feed_tab", "home_tab")
    )

    private var lastFallbackAt = 0L
    private var lastFallback: Detection? = null

    fun detect(root: AccessibilityNodeInfo): Detection {
        var tabBarSeen = false
        val sb = StringBuilder()

        for ((target, ids) in knownTabIds) {
            for (id in ids) {
                val nodes = root.findAccessibilityNodeInfosByViewId(ID_PREFIX + id)
                if (nodes.isNullOrEmpty()) continue
                tabBarSeen = true
                val selected = nodes.any { it.isSelected }
                sb.append(id).append(if (selected) "=SELECTED " else "=no ")
                if (selected) return Detection(target, "${sb.toString().trim()} -> $target")
            }
        }

        // Tab bar is there but Reels/Explore/Home aren't selected: Profile, DMs, etc.
        if (tabBarSeen) return Detection(null, "${sb.toString().trim()} -> none (other tab)")

        return fallback(root)
    }

    /** Ids differ on this version: look for ANY selected node whose id ends in "_tab". */
    private fun fallback(root: AccessibilityNodeInfo): Detection {
        val now = System.currentTimeMillis()
        lastFallback?.let { if (now - lastFallbackAt < 400) return it }

        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        var result = Detection(null, "no tab bar found ($visited nodes)")

        while (queue.isNotEmpty() && visited < 700) {
            val node = queue.removeFirst()
            visited++
            val id = node.viewIdResourceName
            if (node.isSelected && id != null && id.endsWith("_tab")) {
                val name = id.substringAfter(":id/")
                val target = when {
                    "clips" in name || "reel" in name -> BlockTarget.REELS
                    "search" in name || "explore" in name -> BlockTarget.EXPLORE
                    "feed" in name || "home" in name -> BlockTarget.FEED
                    else -> null
                }
                result = Detection(target, "fallback: $name=SELECTED -> ${target ?: "none"}")
                break
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it) }
        }
        if (result.target == null && result.debug.startsWith("no tab bar")) {
            result = Detection(null, "no tab bar found ($visited nodes scanned)")
        }
        lastFallback = result
        lastFallbackAt = now
        return result
    }
}
