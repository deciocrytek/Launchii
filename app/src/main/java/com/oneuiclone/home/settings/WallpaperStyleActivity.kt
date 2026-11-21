package com.oneuiclone.home.settings

import android.app.AlertDialog
import android.app.WallpaperManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.oneuiclone.home.WallpaperActivity

/**
 * One UI's "Wallpaper and style" (Home screen edit mode › Wallpaper and style): lock screen and Home previews, then
 * Change wallpapers (a photo, positioned in [WallpaperActivity]) with Blur wallpaper under it, and Colour palette (the
 * system's Wallpaper & style app, which owns Material You colours). Icons are a page of their own ([IconThemeActivity],
 * opened from Home's edit mode and Settings). The Home preview is Home's real first page ([StylePreview]).
 */
class WallpaperStyleActivity : OneUiPage() {

    companion object {
        private const val REQUEST_PHOTO = 1
        /** Pixel's Wallpaper & style app; AOSP's picker is the fallback. */
        private val PICKERS = listOf("com.google.android.apps.wallpaper", "com.android.wallpaper")
        /** WallpaperPickerDelegate.LAUNCH_SOURCE_LAUNCHER: opens the picker's main screen (wallpaper and colours). */
        private const val EXTRA_LAUNCH_SOURCE = "com.android.wallpaper.LAUNCH_SOURCE"
        private const val LAUNCH_SOURCE_LAUNCHER = "app_launched_launcher"
    }

    private lateinit var preview: StylePreview
    private lateinit var lockCard: PreviewCard
    private lateinit var homeCard: PreviewCard
    private var built = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preview = StylePreview(this)
        WallpaperManager.getInstance(this).addOnColorsChangedListener(wallpaperChanged, android.os.Handler(mainLooper))
    }

    override fun onDestroy() {
        super.onDestroy()
        WallpaperManager.getInstance(this).removeOnColorsChangedListener(wallpaperChanged)
        preview.destroy()
    }

    /** A wallpaper set (or blurred) while the page is open. */
    private val wallpaperChanged = WallpaperManager.OnColorsChangedListener { _, _ -> if (built) build() }

    /** Lays the page out once; every resume re-reads the wallpapers (one may have just been set). */
    override fun build() {
        if (!built) { built = true; layout() }
        refreshLive()
        preview.load { lockCard.bitmap = preview.renderLock(cardWidth()); refreshHome() }
    }

    /** Shows Live wallpaper's state and whether blur and zoom can use the wallpaper (back from the picker, or a wallpaper set). */
    private var refreshLive: () -> Unit = {}


    private fun refreshHome() = preview.render(StylePreview.Look.of(prefs), cardWidth(), cardWidth()) { home, _ -> homeCard.bitmap = home }

    private fun cardWidth() = (resources.displayMetrics.widthPixels - dp(12f) * 2 - dp(20f) * 2 - dp(16f)) / 2
    private fun layout() {
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12f), 0, dp(12f), dp(24f)) }

        appBar("Wallpaper")

        // the two previews, lock screen first
        val ratio = preview.height / preview.width.toFloat()
        val previews = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(20f), dp(8f), dp(20f), 0) }
        lockCard = PreviewCard(this, dp(18f).toFloat(), cardColor).apply { contentDescription = "Lock screen preview" }
        homeCard = PreviewCard(this, dp(18f).toFloat(), cardColor).apply { contentDescription = "Home screen preview" }
        val h = (cardWidth() * ratio).toInt()
        for ((i, pair) in listOf(lockCard to "Lock screen", homeCard to "Home screen").withIndex()) {
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            col.addView(pair.first, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h))
            col.addView(TextView(this).apply {
                text = pair.second; setTextColor(sub); setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
                typeface = com.oneuiclone.home.ui.Fonts.ui(this@WallpaperStyleActivity, 400)
                gravity = android.view.Gravity.CENTER; setPadding(0, dp(8f), 0, 0)
            })
            previews.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (i == 0) marginEnd = dp(8f) else marginStart = dp(8f) })
        }
        for (c in listOf(lockCard, homeCard)) c.setOnClickListener { pickPhoto() }
        list.addView(previews, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(20f)
        })

        // Change wallpaper: a photo, positioned and set on Home, Lock screen or both (WallpaperActivity)
        val change = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER
            background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x33FFFFFF),
                android.graphics.drawable.GradientDrawable().apply { cornerRadius = 100f * d; setColor(primary) }, null)
            contentDescription = "Change wallpaper"
            setOnClickListener { pickPhoto() }
        }
        change.addView(android.widget.ImageView(this).apply { setImageDrawable(GlyphDrawable(Glyphs.IMAGE, onPrimary, dp(22f))) },
            LinearLayout.LayoutParams(dp(22f), dp(22f)).apply { marginEnd = dp(10f) })
        change.addView(TextView(this).apply {
            text = "Change wallpaper"; setTextColor(onPrimary); setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 17f)
            typeface = com.oneuiclone.home.ui.Fonts.ui(this@WallpaperStyleActivity, 600)
        })
        list.addView(change, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56f)).apply {
            marginStart = dp(4f); marginEnd = dp(4f); bottomMargin = dp(16f)
        })
        register("Change wallpaper", change)
        caption("Pick a photo, move and zoom it, then set it on Home, Lock screen or both. Tapping either preview does the same.")

        groupHeader("Effects")
        card {
            var strength: SeekBar? = null
            lateinit var blurRow: LinearLayout
            val blurSummary = "Soften the wallpaper behind your Home screen. Your lock screen stays as it is."
            fun applyBlur() {
                val summary = (blurRow.getChildAt(0) as LinearLayout).getChildAt(1) as TextView
                summary.text = if (prefs.homeBlur) "Blurring your wallpaper…" else "Restoring your wallpaper…"
                com.oneuiclone.home.ui.WallpaperBlur.apply(this@WallpaperStyleActivity, prefs.homeBlurLevel) { r ->
                    summary.text = blurSummary
                    // the wallpaper change reloaded the previews before the blurred and sharp copies were recorded
                    if (r == com.oneuiclone.home.ui.WallpaperBlur.Result.DONE && !isDestroyed) build()
                    if (r == com.oneuiclone.home.ui.WallpaperBlur.Result.NO_ACCESS && prefs.homeBlur) {
                        prefs.homeBlur = false
                        (blurRow.getChildAt(1) as Switch).isChecked = false
                        Toast.makeText(this@WallpaperStyleActivity,
                            "Set your wallpaper with Change wallpapers or Set as wallpaper in your photos app to blur it. " +
                                "Live wallpapers can't be blurred.", Toast.LENGTH_LONG).show()
                    }
                }
            }
            blurRow = toggle("Blur wallpaper", blurSummary, prefs.homeBlur) {
                if (prefs.homeBlur != it) { prefs.homeBlur = it; applyBlur() }
                strength?.isEnabled = it
            }
            strength = slider("Blur strength", prefs.homeBlurAmount, 10, 100) {
                prefs.homeBlurAmount = it
                if (prefs.homeBlur) applyBlur()
            }
            strength.isEnabled = prefs.homeBlur
            liveWallpaperRow()
        }
        groupHeader("Colours")
        card {
            row("Colour palette", "Choose a palette based on colours from your wallpaper. Settings takes its accent from it.", sub,
                Swatch(this@WallpaperStyleActivity).apply { layoutParams = LinearLayout.LayoutParams(dp(34f), dp(56f)).apply { marginStart = dp(16f) } },
                { openColours() }, titleWeight = 600)
        }
        val scroll = ScrollView(this).apply { setBackgroundColor(bg); addView(list); isVerticalScrollBarEnabled = false; clipToPadding = false }
        // the page scrolls under the status bar; this keeps it out from behind it
        val statusScrim = View(this).apply { setBackgroundColor(bg); translationZ = dp(8f).toFloat(); outlineProvider = null }
        val root = android.widget.FrameLayout(this)
        root.addView(scroll)
        root.addView(statusScrim, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0))
        root.setOnApplyWindowInsetsListener { _, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            scroll.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            statusScrim.layoutParams.height = bars.top
            root.requestLayout()
            insets
        }
        setContentView(root)
    }

    /**
     * Launchii's live wallpaper (Settings' row before 0.263, now a switch): the same picture, drawn by Launchii so Home
     * can zoom the real wallpaper on app opens and closes. Wallpapers set in Launchii go on as it until it's turned off
     * here (after a confirm), which puts the picture back as a normal image wallpaper. Under it, whether blur and zoom
     * can use the wallpaper (Settings' Wallpaper effects row).
     */
    private fun liveWallpaperRow() {
        val lw = com.oneuiclone.home.ui.LiveWallpaper
        val app = getString(com.oneuiclone.home.R.string.app_name)
        var syncing = false
        lateinit var sw: Switch
        lateinit var summary: TextView
        val r = toggle("Live wallpaper", "", false) { on ->
            if (syncing) return@toggle
            val live = lw.isActive(this)
            val hasCopy = com.oneuiclone.home.ui.WallpaperLayer.hasOwnCopy(this)
            when {
                live && !on -> AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("Turn off live wallpaper?")
                    .setMessage("Your wallpaper stays the same picture, as a normal wallpaper, and it won't zoom when apps open and close.")
                    // the switch stays off while it's turned off; cancelled, it goes back on
                    .setNegativeButton(android.R.string.cancel) { _, _ -> refreshLive() }
                    .setOnCancelListener { refreshLive() }
                    .setPositiveButton("Turn off") { _, _ ->
                        val ctx = applicationContext
                        lw.setWanted(ctx, false)
                        Thread {
                            val ok = try { lw.turnOff(ctx) } catch (_: Exception) { false }
                            runOnUiThread {
                                if (!ok) Toast.makeText(ctx, "Couldn't turn it off", Toast.LENGTH_SHORT).show()
                                if (!isDestroyed) refreshLive()
                            }
                        }.start()
                    }
                    .show()
                !live && on && hasCopy -> try {
                    lw.setWanted(this, true)
                    // the picker's outcome shows once we're back (build runs on resume)
                    startActivity(lw.pickerIntent(this))
                } catch (_: android.content.ActivityNotFoundException) {
                    Toast.makeText(this, "This phone has no live wallpaper picker", Toast.LENGTH_SHORT).show()
                    refreshLive()
                }
                else -> refreshLive()
            }
        }
        sw = r.getChildAt(r.childCount - 1) as Switch
        summary = (r.getChildAt(0) as LinearLayout).getChildAt(1) as TextView
        // Wallpaper effects: a status line
        val status = row("", null, sub, null, null).apply { setPadding(dp(18f), dp(4f), dp(20f), dp(16f)); minimumHeight = 0 }
        val statusTitle = (status.getChildAt(0) as LinearLayout).getChildAt(0) as TextView
        statusTitle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
        val statusIcon = android.widget.ImageView(this)
        status.addView(statusIcon, 0, LinearLayout.LayoutParams(dp(22f), dp(22f)).apply { marginEnd = dp(12f) })
        register("Wallpaper effects", status)
        refreshLive = {
            val live = lw.isActive(this)
            val hasCopy = com.oneuiclone.home.ui.WallpaperLayer.hasOwnCopy(this)
            syncing = true
            sw.isChecked = live
            syncing = false
            sw.isEnabled = live || hasCopy
            r.isClickable = live || hasCopy
            summary.text = when {
                live -> "On: your wallpaper zooms when apps open and close. Aura, Shutter and the other wallpaper openings need it."
                hasCopy -> "Off. Turn it on so your wallpaper zooms when apps open and close."
                else -> "Set a wallpaper from $app, as above, and it zooms when apps open and close"
            }
            statusTitle.text = if (hasCopy) "Blur and zoom use your wallpaper"
                else "For blur and zoom, set your wallpaper with Change wallpaper, or with Set as wallpaper in your photos app"
            statusTitle.setTextColor(if (hasCopy) text else primary)
            statusIcon.setImageDrawable(GlyphDrawable(if (hasCopy) Glyphs.CHECK else Glyphs.INFO, primary, dp(20f)))
        }
    }

    private fun pickPhoto() {
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(MediaStore.ACTION_PICK_IMAGES).setType("image/*"), REQUEST_PHOTO)
        } catch (_: Exception) {
            Toast.makeText(this, "No photo picker available", Toast.LENGTH_SHORT).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode != REQUEST_PHOTO || resultCode != RESULT_OK || uri == null) return
        // positioned and set there, then back here, where the previews show it
        startActivity(Intent(this, WallpaperActivity::class.java).setData(uri)
            .putExtra(WallpaperActivity.EXTRA_STAY, true)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }

    /** Material You colours belong to the system's Wallpaper & style app; its main screen has the palettes. */
    private fun openColours() {
        val base = Intent(Intent.ACTION_SET_WALLPAPER).putExtra(EXTRA_LAUNCH_SOURCE, LAUNCH_SOURCE_LAUNCHER)
        for (pkg in PICKERS) {
            try { startActivity(Intent(base).setPackage(pkg)); return } catch (_: android.content.ActivityNotFoundException) {}
        }
        try {
            startActivity(Intent.createChooser(base, "Colour palette"))
        } catch (_: Exception) {
            Toast.makeText(this, "No Wallpaper & style app on this phone", Toast.LENGTH_SHORT).show()
        }
    }

    /** The palette as One UI shows it: the accent over a light and a dark tone, in a rounded bar. */
    private class Swatch(context: Context) : View(context) {
        private val colors = intArrayOf(
            context.getColor(android.R.color.system_accent1_400),
            context.getColor(android.R.color.system_accent2_50),
            context.getColor(android.R.color.system_neutral1_800),
        )
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val clip = Path()
        override fun onDraw(canvas: Canvas) {
            val r = width * 0.3f
            clip.reset(); clip.addRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), r, r, Path.Direction.CW)
            canvas.save()
            canvas.clipPath(clip)
            val band = height / colors.size.toFloat()
            colors.forEachIndexed { i, c -> paint.color = c; canvas.drawRect(0f, i * band, width.toFloat(), (i + 1) * band, paint) }
            canvas.restore()
        }
    }
}
