package com.oneuiclone.home.ui

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.provider.Settings

/**
 * Where the keyboard sits, learned once and locked (0.230). Up to 0.229 search kept the last keyboard height it saw,
 * whatever it was (one caught partway up, or the size it still had from the app just left), so one odd height sent
 * the bar to the wrong spot on the next open and it corrected mid-flight.
 *
 * Now only a keyboard that has fully settled under the search field counts ([sample]); a height that differs from
 * the locked one must be seen [CONFIRM] times in a row before it replaces it (a new keyboard app, a number row turned
 * on), so a single odd one changes nothing. Heights are kept per keyboard app and per portrait / landscape, not per
 * pixel size of the window, so they survive a display size change.
 */
class KeyboardSpot(private val context: Context, private val prefs: SharedPreferences = prefs(context)) {

    /** The keyboard app's package (the user can switch keyboards: each has its own height). */
    private fun ime(): String = try {
        Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)?.substringBefore('/') ?: "?"
    } catch (_: Exception) { "?" }

    private fun base(): String {
        val land = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        return "${ime()}|${if (land) "land" else "port"}"
    }

    /** The locked height in px, or null before the first settled keyboard (0.229's last height is taken over once). */
    fun height(legacyKey: String? = null): Int? {
        val b = base()
        prefs.getInt(LOCK + b, 0).takeIf { it > 0 }?.let { return it }
        val legacy = legacyKey?.let { prefs.getInt(it, 0) }?.takeIf { it > 0 } ?: return null
        prefs.edit().putInt(LOCK + b, legacy).putInt(SEEN + b, 0).apply()
        return legacy
    }

    /** A keyboard [h] px tall has fully settled under the search field. Returns the (possibly new) locked height. */
    fun sample(h: Int): Int {
        if (h <= 0) return height() ?: 0
        val b = base()
        val locked = prefs.getInt(LOCK + b, 0)
        val e = prefs.edit()
        val result = when {
            // nothing yet, or only 0.229's last-seen height (seen 0 times here; it may be one of the bad ones): take it
            locked <= 0 || prefs.getInt(SEEN + b, 0) == 0 -> { e.putInt(LOCK + b, h).putInt(SEEN + b, 1).remove(CAND + b).remove(CAND_N + b); h }
            near(h, locked) -> { e.putInt(SEEN + b, prefs.getInt(SEEN + b, 0) + 1).remove(CAND + b).remove(CAND_N + b); locked }
            near(h, prefs.getInt(CAND + b, 0)) -> {
                val n = prefs.getInt(CAND_N + b, 0) + 1
                if (n >= CONFIRM) { e.putInt(LOCK + b, h).putInt(SEEN + b, n).remove(CAND + b).remove(CAND_N + b); h }
                else { e.putInt(CAND_N + b, n); locked }
            }
            else -> { e.putInt(CAND + b, h).putInt(CAND_N + b, 1); locked }
        }
        e.apply()
        return result
    }

    /** Settings › Re-learn: every keyboard's height is forgotten and learned again on the next open. */
    fun relearn() {
        val e = prefs.edit()
        for (k in prefs.all.keys) if (k.startsWith(LOCK) || k.startsWith(SEEN) || k.startsWith(CAND) || k.startsWith(CAND_N) || k.startsWith("ime_height_")) e.remove(k)
        e.apply()
    }

    /** For Settings: the keyboard app's name, its locked height in dp and how many opens it has been seen at, or null. */
    fun describe(): Triple<String, Int, Int>? {
        val b = base()
        val h = prefs.getInt(LOCK + b, 0).takeIf { it > 0 } ?: return null
        val pkg = ime()
        val name = try {
            context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (_: Exception) { "your keyboard" }
        return Triple(name, (h / context.resources.displayMetrics.density).toInt(), prefs.getInt(SEEN + b, 0))
    }

    private fun near(a: Int, b: Int) = b > 0 && kotlin.math.abs(a - b) <= SAME_PX

    companion object {
        /** Times in a row a different height must be seen before it takes over. */
        const val CONFIRM = 3
        /** Heights this close (px) are the same keyboard (rounding of the insets). */
        private const val SAME_PX = 3
        private const val LOCK = "lock|"
        private const val SEEN = "seen|"
        private const val CAND = "cand|"
        private const val CAND_N = "candn|"
        fun prefs(context: Context): SharedPreferences = context.getSharedPreferences("keyboard", Context.MODE_PRIVATE)
    }
}
