package com.oneuiclone.home.model

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
import kotlin.math.pow

/**
 * How often each app is opened, for search's "most used" row. With usage access (Settings › Usage access) the
 * system's own record of app opens over the last [DAYS] days counts, wherever the app was opened from;
 * on top of that (or instead of it) the launcher keeps its own launch counts, which halve every [HALF_LIFE_DAYS].
 */
class AppUsage(private val context: Context) {

    private val prefs = context.getSharedPreferences("app_usage", Context.MODE_PRIVATE)

    val systemAllowed: Boolean
        get() {
            val ops = context.getSystemService(AppOpsManager::class.java) ?: return false
            return ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName) ==
                AppOpsManager.MODE_ALLOWED
        }

    /** Called on every launch from Home, the apps screen or search. */
    fun launched(key: AppKey) {
        val now = System.currentTimeMillis()
        val k = key.flatten()
        prefs.edit().putString(k, "${decayed(prefs.getString(k, null), now) + 1.0}|$now").apply()
    }

    /** When [key] was last opened through the launcher (ms), or 0. */
    fun lastLaunched(key: AppKey): Long = prefs.getString(key.flatten(), null)?.substringAfter('|', "")?.toLongOrNull() ?: 0L

    /** Score per app; higher = opened more. Reads the system's usage events, so call it off the main thread. */
    fun scores(apps: List<AppInfo>): Map<AppKey, Double> {
        val now = System.currentTimeMillis()
        val opens = systemOpens(now)
        return apps.associate { a ->
            a.key to (opens[a.key.component.packageName] ?: 0) + decayed(prefs.getString(a.key.flatten(), null), now)
        }
    }

    /** "score|millis" → the score as of [now]. */
    private fun decayed(stored: String?, now: Long): Double {
        val parts = stored?.split('|') ?: return 0.0
        val score = parts.getOrNull(0)?.toDoubleOrNull() ?: return 0.0
        val at = parts.getOrNull(1)?.toLongOrNull() ?: return score
        return score * 0.5.pow((now - at).coerceAtLeast(0L) / (HALF_LIFE_DAYS * DAY_MS))
    }

    /** Package → times it came to the front (a switch between two activities of one app counts once). */
    private fun systemOpens(now: Long): Map<String, Int> {
        if (!systemAllowed) return emptyMap()
        return try {
            val events = context.getSystemService(UsageStatsManager::class.java)?.queryEvents(now - DAYS * DAY_MS.toLong(), now)
                ?: return emptyMap()
            val e = UsageEvents.Event()
            val out = HashMap<String, Int>()
            var last: String? = null
            while (events.hasNextEvent()) {
                events.getNextEvent(e)
                if (e.eventType != UsageEvents.Event.ACTIVITY_RESUMED) continue
                val p = e.packageName
                if (p != last) { out[p] = (out[p] ?: 0) + 1; last = p }
            }
            out
        } catch (_: Exception) {
            emptyMap()
        }
    }

    /**
     * The other apps that were in front together just before now, replaying the system's activity events since
     * [since] (when Home was left): two or more means split screen. Apps paused by the home gesture itself still
     * count; one that stopped while the other stayed (split dismissed with the divider) doesn't. Home apps and the
     * system's recents screen are left out: the home gesture itself resumes Recents (Pixel Launcher's
     * RecentsActivity) a moment before it pauses the app, which made every close look like split screen. Null
     * without usage access.
     */
    fun frontApps(since: Long): Set<String>? {
        if (!systemAllowed) return null
        return try {
            val now = System.currentTimeMillis()
            val events = context.getSystemService(UsageStatsManager::class.java)
                ?.queryEvents(since.coerceAtLeast(now - FRONT_LOOKBACK_MS), now) ?: return null
            val ignored = systemShells
            val e = UsageEvents.Event()
            val resumed = HashSet<String>()                // package/class
            val visible = HashSet<String>()                // resumed or paused, not yet stopped
            var front = emptySet<String>()
            while (events.hasNextEvent()) {
                events.getNextEvent(e)
                if (e.packageName in ignored) continue
                val k = e.packageName + "/" + e.className
                when (e.eventType) {
                    UsageEvents.Event.ACTIVITY_RESUMED -> {
                        resumed += k; visible += k
                        front = resumed.mapTo(HashSet()) { it.substringBefore('/') }
                    }
                    UsageEvents.Event.ACTIVITY_PAUSED -> resumed -= k
                    // An activity pauses before it stops, so a stop while its class is resumed is another instance
                    // of it (Settings' trampoline): ignore it.
                    UsageEvents.Event.ACTIVITY_STOPPED -> if (k !in resumed) {
                        visible -= k
                        val stillVisible = visible.mapTo(HashSet()) { it.substringBefore('/') }
                        front = front.filterTo(HashSet()) { it in stillVisible }
                    }
                }
            }
            front
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Pairs of apps that were in split screen (in front together for at least [MIN_TOGETHER_MS]) since [since], each
     * with when it was last seen, replaying the system's activity events: in split screen both halves stay resumed,
     * while full screen pauses one app before the next resumes. Packages in [ignore] don't count (apps used in bubbles:
     * an expanded bubble is in front with the app under it). Going Home or to Recents pauses everything, so it starts
     * the count afresh, which also drops an activity whose pause was never logged (its app died). Null without usage
     * access.
     */
    fun splitPairs(since: Long, ignore: Set<String>): Map<Pair<String, String>, Long>? {
        if (!systemAllowed) return null
        return try {
            val now = System.currentTimeMillis()
            val events = context.getSystemService(UsageStatsManager::class.java)?.queryEvents(since, now) ?: return null
            val shells = systemShells
            val homes = homeScreens
            val e = UsageEvents.Event()
            val resumed = HashSet<String>()                         // package/class
            val together = HashMap<Pair<String, String>, Long>()    // pair in front now → since when
            val out = HashMap<Pair<String, String>, Long>()
            fun settle(t: Long) {
                val front = resumed.mapTo(sortedSetOf()) { it.substringBefore('/') }.toList()
                val pairs = HashSet<Pair<String, String>>()
                for (i in front.indices) for (j in i + 1 until front.size) pairs += front[i] to front[j]
                val ended = together.keys.filter { it !in pairs }
                for (p in ended) {
                    if (t - together.remove(p)!! >= MIN_TOGETHER_MS) out[p] = maxOf(out[p] ?: 0L, t)
                }
                for (p in pairs) together.putIfAbsent(p, t)
            }
            while (events.hasNextEvent()) {
                events.getNextEvent(e)
                val k = e.packageName + "/" + e.className
                when (e.eventType) {
                    UsageEvents.Event.ACTIVITY_RESUMED -> when {
                        k in homes || e.packageName == homes.recents -> resumed.clear()
                        e.packageName in shells || e.packageName in ignore -> continue
                        else -> resumed += k
                    }
                    UsageEvents.Event.ACTIVITY_PAUSED -> if (!resumed.remove(k)) continue
                    UsageEvents.Event.DEVICE_SHUTDOWN, UsageEvents.Event.DEVICE_STARTUP -> resumed.clear()
                    else -> continue
                }
                settle(e.timeStamp)
            }
            for ((p, t) in together) if (now - t >= MIN_TOGETHER_MS) out[p] = now
            out
        } catch (_: Exception) {
            null
        }
    }

    /** Home apps' home activities (package/class), and the recents provider's package ([HomeScreens.recents]). */
    private class HomeScreens(val components: Set<String>, val recents: String?) {
        operator fun contains(k: String) = k in components
    }

    private val homeScreens: HomeScreens by lazy {
        val out = HashSet<String>()
        try {
            context.packageManager.queryIntentActivities(
                android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME), 0,
            ).forEach { if (it.priority >= 0) out += it.activityInfo.packageName + "/" + it.activityInfo.name }
        } catch (_: Exception) {}
        HomeScreens(out, recentsPackage())
    }

    private fun recentsPackage(): String? {
        val res = android.content.res.Resources.getSystem()
        @Suppress("DiscouragedApi")
        val id = res.getIdentifier("config_recentsComponentName", "string", "android")
        return if (id != 0) android.content.ComponentName.unflattenFromString(res.getString(id))?.packageName else null
    }

    /** Packages whose activities aren't apps in front: us, every home app, the recents provider and System UI. */
    private val systemShells: Set<String> by lazy {
        val out = hashSetOf(context.packageName, "com.android.systemui")
        try {
            context.packageManager.queryIntentActivities(
                android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME), 0,
            ).forEach { if (it.priority >= 0) out += it.activityInfo.packageName }   // not Settings' FallbackHome (-1000)
        } catch (_: Exception) {}
        recentsPackage()?.let { out += it }
        out
    }

    private companion object {
        /** In front together this long counts as split screen, not a passing overlap as one app hands over to another. */
        const val MIN_TOGETHER_MS = 3_000L
        const val FRONT_LOOKBACK_MS = 6 * 3_600_000L
        const val DAYS = 14
        const val HALF_LIFE_DAYS = 14.0
        const val DAY_MS = 86_400_000.0
    }
}
