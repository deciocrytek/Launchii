package com.oneuiclone.home.ui

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * The last [MAX] wallpapers set through Launchii (0.263, hold menu with recent wallpapers, like the Pixel Launcher):
 * the sharp picture at display size plus a small thumbnail, newest first. Written when [WallpaperActivity] sets one
 * on Home (Photos' Set as wallpaper, Wallpaper and style › Change wallpapers); not by Blur wallpaper or the live
 * wallpaper switch, which re-set the same picture, nor by lock-screen-only sets. Picking an older one puts it back on
 * the screens it was first set on and moves it to the front; the same picture again never makes a second entry.
 */
object WallpaperHistory {
    const val MAX = 4
    private const val DIR = "wallpaper_history"
    private const val PREFS = "wallpaper_history"
    private const val KEY = "entries"
    private const val THUMB_W = 270

    /**
     * [file] and [thumb] are names in [DIR]; [which] is WallpaperManager's FLAG_SYSTEM / FLAG_LOCK as first set;
     * [accent] is the last observed Android `system_accent1_400` while this wallpaper was active (0 if unknown).
     */
    class Entry(val file: String, val thumb: String, val which: Int, val hash: String, val accent: Int = 0)

    /** Thumbnail + precomputed glass & wallpaper colours for [HomeMenu] tiles. */
    class LoadedTile(
        val entry: Entry,
        val thumb: Bitmap?,
        val glass: Bitmap?,
        val crystal: Bitmap?,
        val bright: Boolean,
        val accent: Int,
    )

    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "wallpaper-history").apply { priority = Thread.NORM_PRIORITY - 1 } }
    private val main = Handler(Looper.getMainLooper())
    private val thumbs = android.util.LruCache<String, Bitmap>(MAX + 2)
    /** Cached (standard, crystal) blurred glass bitmaps per thumbnail key + screen size. */
    private val glasses = android.util.LruCache<String, Pair<Bitmap, Bitmap>>(MAX + 2)
    /** Cached (bright, estimatedAccent) from WallpaperColors.fromBitmap per thumbnail key. */
    private val colorInfo = android.util.LruCache<String, Pair<Boolean, Int>>(MAX + 2)

    private fun dir(context: Context) = File(context.filesDir, DIR).apply { mkdirs() }

    fun entries(context: Context): List<Entry> = synchronized(this) {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return emptyList()
        return try {
            val a = JSONArray(raw)
            (0 until a.length()).map { a.getJSONObject(it) }.map {
                Entry(it.getString("f"), it.getString("t"), it.getInt("w"), it.getString("h"), it.optInt("a", 0))
            }.filter { File(dir(context), it.file).exists() }
        } catch (_: Exception) { emptyList() }
    }

    private fun save(context: Context, list: List<Entry>) {
        val a = JSONArray()
        list.forEach {
            val o = JSONObject().put("f", it.file).put("t", it.thumb).put("w", it.which).put("h", it.hash)
            if (it.accent != 0) o.put("a", it.accent)
            a.put(o)
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, a.toString()).commit()
    }

    /** Records Android's real `system_accent1_400` on the newest entry while Launchii's own wallpaper copy is active. */
    fun recordCurrentAccent(context: Context, accent: Int) {
        if (accent == 0) return
        val app = context.applicationContext
        io.execute {
            if (!WallpaperLayer.hasOwnCopy(app)) return@execute
            synchronized(this) {
                val list = entries(app).toMutableList()
                val first = list.firstOrNull() ?: return@synchronized
                if (first.accent == accent) return@synchronized
                list[0] = Entry(first.file, first.thumb, first.which, first.hash, accent)
                save(app, list)
            }
        }
    }

    /** A fingerprint of the picture: 16×16, 4 bits a channel. Near-identical pictures (a re-encode) match. */
    private fun hash(bmp: Bitmap): String {
        val soft = if (bmp.config == Bitmap.Config.HARDWARE) bmp.copy(Bitmap.Config.ARGB_8888, false) else bmp
        val s = Bitmap.createScaledBitmap(soft, 16, 16, true)
        val px = IntArray(256); s.getPixels(px, 0, 16, 0, 0, 16, 16)
        val bytes = ByteArray(px.size * 2)
        px.forEachIndexed { i, c ->
            bytes[i * 2] = (((c shr 20) and 0xF) shl 4 or ((c shr 12) and 0xF)).toByte()
            bytes[i * 2 + 1] = ((c shr 4) and 0xF).toByte()
        }
        return java.security.MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }
    }

    /** Background thread: records [bmp] (sharp, display-sized) as just set on [which]. */
    fun add(context: Context, bmp: Bitmap, which: Int) = synchronized(this) {
        if (which and WallpaperManager.FLAG_SYSTEM == 0) return
        val h = hash(bmp)
        val list = entries(context).toMutableList()
        val same = list.firstOrNull { it.hash == h }
        if (same != null) {
            list.remove(same)
            list.add(0, Entry(same.file, same.thumb, which, h, same.accent))
        } else {
            val name = "w_${System.currentTimeMillis()}"
            val d = dir(context)
            File(d, "$name.webp").outputStream().use { bmp.compress(Bitmap.CompressFormat.WEBP_LOSSY, 92, it) }
            val th = Bitmap.createScaledBitmap(bmp, THUMB_W, (THUMB_W * bmp.height / bmp.width.toFloat()).toInt().coerceAtLeast(1), true)
            File(d, "$name.t.webp").outputStream().use { th.compress(Bitmap.CompressFormat.WEBP_LOSSY, 88, it) }
            list.add(0, Entry("$name.webp", "$name.t.webp", which, h))
        }
        while (list.size > MAX) list.removeAt(list.size - 1).let {
            File(dir(context), it.file).delete(); File(dir(context), it.thumb).delete()
            thumbs.remove(it.thumb); colorInfo.remove(it.thumb)
        }
        save(context, list)
    }

    /**
     * First use: the wallpaper Launchii already set (before history existed) becomes the first entry, so the menu
     * isn't empty. Background thread.
     */
    fun seed(context: Context) {
        if (entries(context).isNotEmpty() || !WallpaperLayer.hasOwnCopy(context)) return
        val sharp = if (WallpaperBlur.isBlurred(context)) File(context.filesDir, WallpaperBlur.SHARP_FILE) else File(context.filesDir, WallpaperLayer.OWN_FILE)
        val bmp = BitmapFactory.decodeFile(sharp.path) ?: return
        add(context, bmp, WallpaperManager.FLAG_SYSTEM)
    }

    /**
     * The entries (seeded on first use), their thumbnails, cached blurred glass pairs (for non-current tiles), and
     * wallpaper colour info (brightness + saved/estimated Material accent), handed to [done] on the main thread.
     */
    fun loadForMenu(
        context: Context,
        screenW: Int,
        screenH: Int,
        skipFirstGlass: Boolean,
        extraWork: ((List<LoadedTile>) -> Unit)? = null,
        done: (List<LoadedTile>) -> Unit,
    ) {
        val app = context.applicationContext
        io.execute {
            val list = try { seed(app); entries(app) } catch (_: Exception) { emptyList() }
            val out = list.mapIndexed { i, e ->
                val th = thumbs.get(e.thumb)
                    ?: BitmapFactory.decodeFile(File(dir(app), e.thumb).path)?.also { thumbs.put(e.thumb, it) }
                val pair = if (th != null && !(skipFirstGlass && i == 0) && screenW > 0 && screenH > 0) {
                    val gk = "${e.thumb}@${screenW}x$screenH"
                    glasses.get(gk) ?: WidgetGlass.blurredPairFromThumb(th, screenW, screenH)?.also { glasses.put(gk, it) }
                } else null
                val info = if (th != null) {
                    colorInfo.get(e.thumb) ?: try {
                        val wc = android.app.WallpaperColors.fromBitmap(th)
                        val light = (wc.colorHints and android.app.WallpaperColors.HINT_SUPPORTS_DARK_TEXT) != 0
                        val est = com.oneuiclone.home.icons.IconFactory.estimateAccent(wc)
                        (light to est).also { colorInfo.put(e.thumb, it) }
                    } catch (_: Exception) { false to 0 }
                } else false to 0
                val accent = if (e.accent != 0) e.accent else info.second
                LoadedTile(e, th, pair?.first, pair?.second, info.first, accent)
            }
            try { extraWork?.invoke(out) } catch (_: Exception) {}
            main.post { done(out) }
        }
    }

    /** The entries (seeded on first use) and their thumbnails, handed to [done] on the main thread. */
    fun load(context: Context, done: (List<Pair<Entry, Bitmap?>>) -> Unit) {
        val app = context.applicationContext
        io.execute {
            val list = try { seed(app); entries(app) } catch (_: Exception) { emptyList() }
            val out = list.map { e ->
                e to (thumbs.get(e.thumb) ?: BitmapFactory.decodeFile(File(dir(app), e.thumb).path)?.also { thumbs.put(e.thumb, it) })
            }
            main.post { done(out) }
        }
    }

    /**
     * Puts [e] back as the wallpaper, on the screens it was set on, blurred at Blur wallpaper's level, in one change
     * (Launchii's live wallpaper crossfades to it). [done] (main thread) gets whether it worked.
     */
    fun pick(context: Context, e: Entry, blurLevel: Float, done: (Boolean) -> Unit) {
        val app = context.applicationContext
        io.execute {
            val ok = try {
                val bmp = BitmapFactory.decodeFile(File(dir(app), e.file).path) ?: throw IllegalStateException("unreadable")
                LiveWallpaper.crossfadeNext = true
                WallpaperBlur.setPicture(app, bmp, e.which, blurLevel)
                synchronized(this) {
                    val list = entries(app).toMutableList()
                    list.firstOrNull { it.hash == e.hash }?.let { list.remove(it); list.add(0, it) }
                    save(app, list)
                }
                true
            } catch (ex: Exception) {
                android.util.Log.w("OneUiHome", "wallpaper history: pick failed", ex)
                LiveWallpaper.crossfadeNext = false
                false
            }
            main.post { done(ok) }
        }
    }
}
