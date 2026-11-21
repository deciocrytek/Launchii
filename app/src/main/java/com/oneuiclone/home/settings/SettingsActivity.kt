package com.oneuiclone.home.settings

import android.app.WallpaperManager
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.oneuiclone.home.AppBubbles
import com.oneuiclone.home.AppUpdate
import com.oneuiclone.home.BadgeService
import com.oneuiclone.home.icons.IconShape
import com.oneuiclone.home.ui.Fonts
import com.oneuiclone.home.ui.LiveWallpaper

/**
 * Settings (0.263, the approved "Settings hub" mockup): the Launchii title (with Home's Update pill when there's an
 * update), Search settings, a Your Home summary, then Wallpaper and Icons as two big tiles, the four Home screen tiles
 * (Layout, Gestures, Search, Motion) and a card of the other sections. Every option of the old single page lives in
 * one of those ([SettingsSectionActivity], [WallpaperStyleActivity], [IconThemeActivity], [OpeningAnimationActivity]);
 * typing in Search settings lists matching options from all of them ([SettingsIndex]).
 */
class SettingsActivity : OneUiPage() {

    private lateinit var preview: StylePreview
    private var built = false
    private lateinit var content: LinearLayout
    private lateinit var search: EditText
    private lateinit var updatePill: TextView

    /** The previews last drawn, shown straight away when the hub is rebuilt (resume), until fresh ones come. */
    private var wallBitmap: Bitmap? = null
    private var iconsBitmap: Bitmap? = null
    private var homeBitmap: Bitmap? = null
    private var wallViews: List<ImageView> = emptyList()
    private var iconsView: ImageView? = null
    private var homeView: PreviewCard? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preview = StylePreview(this)
        AppUpdate.init(this)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN)
        toSetupIfNotHome()
    }

    override fun onDestroy() {
        super.onDestroy()
        preview.destroy()
    }

    override fun onResume() {
        super.onResume()
        toSetupIfNotHome()   // the drawer can bring this task back without a new onCreate
        AppUpdate.onResumed(this)
    }

    /**
     * Opened from another launcher's app list while Launchii isn't Home: setup instead, which asks for Home along with
     * everything else, every time (not just the first).
     */
    private fun toSetupIfNotHome() {
        if (isFinishing || !intent.hasCategory(Intent.CATEGORY_LAUNCHER) || SetupActivity.isHome(this)) return
        startActivity(Intent(this, SetupActivity::class.java))
        finish()
    }

    override fun build() {
        if (!built) { built = true; layout() }
        refresh()
        preview.load { drawPreviews() }
    }

    /** The title, Update pill and search field, laid out once; [content] under them is the hub or the results. */
    private fun layout() {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(12f), 0, dp(12f), dp(24f))
            // takes the first focus, so the search field doesn't (and no keyboard comes up on opening)
            isFocusableInTouchMode = true
        }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12f), dp(36f), dp(4f), 0) }
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(TextView(this).apply {
            text = getString(com.oneuiclone.home.R.string.app_name)
            setTextColor(this@SettingsActivity.text); setTextSize(TypedValue.COMPLEX_UNIT_SP, 34f)
            typeface = Fonts.ui(this@SettingsActivity, 700)
            letterSpacing = -0.02f
        })
        titles.addView(TextView(this).apply {
            text = "Home screen settings"
            setTextColor(sub); setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Fonts.ui(this@SettingsActivity, 400)
        })
        top.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        // Home's Update pill (AppUpdate), here too while a newer release is out
        // (About first, where the download shows; then the install, whose "Install unknown apps" page, if any, comes on top)
        updatePill = pill("Update", glyph = Glyphs.DOWNLOAD) {
            startActivity(SettingsSectionActivity.intent(this, SettingsSectionActivity.ABOUT).putExtra(EXTRA_HIGHLIGHT, "Software update"))
            AppUpdate.install(this)
        }
        top.addView(updatePill)
        page.addView(top)

        // Search settings
        val field = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply { cornerRadius = 100f * d; setColor(if (night) 0xFF1E1E20.toInt() else 0xFFE9E9EC.toInt()) }
            setPadding(dp(18f), 0, dp(6f), 0)
        }
        field.addView(ImageView(this).apply { setImageDrawable(GlyphDrawable(Glyphs.SEARCH, sub, dp(22f))) }, LinearLayout.LayoutParams(dp(22f), dp(22f)))
        search = EditText(this).apply {
            hint = "Search settings"
            setHintTextColor(sub); setTextColor(this@SettingsActivity.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Fonts.ui(this@SettingsActivity, 400)
            background = null
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setPadding(0, 0, 0, 0)
            textCursorDrawable?.setTint(primary)
            setOnEditorActionListener { v, _, _ -> hideKeyboard(v); true }
        }
        field.addView(search, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { marginStart = dp(12f) })
        val clear = ImageView(this).apply {
            setImageDrawable(GlyphDrawable(Glyphs.CLOSE, sub, dp(20f)))
            scaleType = ImageView.ScaleType.CENTER
            contentDescription = "Clear search"
            background = android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(if (night) 0x33FFFFFF else 0x1A000000), null, null)
            visibility = View.GONE
            setOnClickListener { search.setText(""); hideKeyboard(search) }
        }
        field.addView(clear, LinearLayout.LayoutParams(dp(40f), dp(40f)))
        page.addView(field, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52f)).apply {
            topMargin = dp(20f); bottomMargin = dp(20f); marginStart = dp(4f); marginEnd = dp(4f)
        })
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                clear.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
                refresh()
            }
        })

        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.addView(content)
        setPage(page)
        // the cards and rows below go into the hub (or the results)
        list = content
    }

    private fun hideKeyboard(v: View) {
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(v.windowToken, 0)
        v.clearFocus()
    }

    /** The hub, or the results for what's typed; and the Update pill while there's an update. */
    private fun refresh() {
        updatePill.visibility = if (AppUpdate.available != null) View.VISIBLE else View.GONE
        content.removeAllViews()
        wallViews = emptyList(); iconsView = null; homeView = null
        val q = search.text.toString().trim()
        if (q.isEmpty()) hub() else results(q)
    }

    // ---------------------------------------------------------------- the hub

    private fun openSection(s: String) = startActivity(SettingsSectionActivity.intent(this, s))

    private fun hub() {
        yourHome()
        heroes()
        groupHeader("Home screen")
        val down = when { prefs.swipeDownSearch -> "search"; prefs.swipeDownNotifications -> "notifications"; else -> "nothing" }
        val gestures = "Down: $down" + if (!prefs.swipeDownSearch && prefs.swipeUpSearch) " · Up: search" else ""
        tilePair(
            tile(Glyphs.APPS, "Layout", "${prefs.homeCols}×${prefs.homeRows} · Apps ${prefs.appsCols}×${prefs.appsRows}") { openSection(SettingsSectionActivity.LAYOUT) },
            tile(Glyphs.TOUCH, "Gestures", gestures) { openSection(SettingsSectionActivity.GESTURES) })
        tilePair(
            tile(Glyphs.SEARCH, "Search", "Web suggestions ${if (prefs.webSuggestions) "on" else "off"}") { openSection(SettingsSectionActivity.SEARCH) },
            tile(Glyphs.MOTION, "Motion", "Opening: ${SettingsIndex.openName(prefs.openAnimation)}") {
                startActivity(Intent(this, OpeningAnimationActivity::class.java))
            })
        list.addView(View(this), LinearLayout.LayoutParams(1, dp(4f)))

        val app = getString(com.oneuiclone.home.R.string.app_name)
        card {
            withIcon(linkRow("Folders & widgets", "Pop-up folders ${if (prefs.popupFolders) "on" else "off"}") { openSection(SettingsSectionActivity.FOLDERS) }, Glyphs.FOLDER)
            val granted = BadgeService.isEnabled(this)
            val badges = when {
                !prefs.badges || !granted -> "Badges off"
                prefs.badgeStyle == Prefs.BADGE_QUIET -> "Badge dots"
                prefs.badgeNumbers -> "Badges with number"
                else -> "Badges without number"
            }
            val bubbles = if (prefs.appBubbles && AppBubbles.ready(this)) "on" else "off"
            withIcon(linkRow("Badges & bubbles", "$badges · Bubbles $bubbles") { openSection(SettingsSectionActivity.BADGES) }, Glyphs.BADGE)
            val perms = SettingsSectionActivity.permissions(this)
            withIcon(linkRow("Permissions & setup", "${perms.count { it }} of ${perms.size} allowed · Home app: ${if (perms[0]) app else "not set"}") {
                openSection(SettingsSectionActivity.PERMISSIONS)
            }, Glyphs.SHIELD)
            withIcon(linkRow("Backup & advanced", "Restore an earlier layout · Icon check") { openSection(SettingsSectionActivity.BACKUP) }, Glyphs.RESTORE)
            val version = packageManager.getPackageInfo(packageName, 0).versionName
            withIcon(linkRow("About $app", "$version" + (AppUpdate.available?.let { " · ${it.version} is ready" } ?: "")) {
                openSection(SettingsSectionActivity.ABOUT)
            }, Glyphs.INFO)
        }
        list.addView(TextView(this).apply {
            text = "Developed by ${Developer.HANDLE}"
            setTextColor(sub); setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Fonts.ui(this@SettingsActivity, 400)
            gravity = Gravity.CENTER
            setPadding(0, dp(4f), 0, dp(8f))
            setOnClickListener { openSection(SettingsSectionActivity.ABOUT) }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    /** Your Home: Home's first page, small, with the four things that define it and the palette the accent comes from. */
    private fun yourHome() = card {
        val c = current ?: return@card
        c.orientation = LinearLayout.HORIZONTAL
        c.gravity = Gravity.CENTER_VERTICAL
        c.setPadding(dp(14f), dp(14f), dp(16f), dp(14f))
        val w = dp(76f)
        val mini = PreviewCard(this, dp(12f).toFloat(), bg).apply { bitmap = homeBitmap; contentDescription = "Your Home screen" }
        homeView = mini
        c.addView(mini, LinearLayout.LayoutParams(w, (w * preview.height / preview.width.toFloat()).toInt()))
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(this).apply {
            text = "Your Home"; setTextColor(this@SettingsActivity.text); setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = Fonts.ui(this@SettingsActivity, 650)
        })
        val labels = when { prefs.homeLabels -> "labels on"; prefs.homeLabelsPeek -> "labels while touching"; else -> "labels off" }
        val bullets = listOf(
            "${IconThemeActivity.STYLES[IconThemeActivity.style(prefs)]} icons · ${IconShape.name(prefs.iconShape)}",
            "${prefs.homeCols}×${prefs.homeRows} · $labels",
            "Opens with ${SettingsIndex.openName(prefs.openAnimation)}",
            "Live wallpaper ${if (LiveWallpaper.isActive(this)) "on" else "off"}",
        )
        for (b in bullets) {
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(6f), 0, 0) }
            r.addView(View(this).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(primary) } },
                LinearLayout.LayoutParams(dp(6f), dp(6f)).apply { marginEnd = dp(8f) })
            r.addView(TextView(this).apply {
                text = b; setTextColor(this@SettingsActivity.text); alpha = 0.85f; setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                typeface = Fonts.ui(this@SettingsActivity, 400)
            })
            col.addView(r)
        }
        // the accent and the wallpaper's own colours
        val wc = try { WallpaperManager.getInstance(this).getWallpaperColors(WallpaperManager.FLAG_SYSTEM) } catch (_: Exception) { null }
        val swatches = (listOf(primary) + listOfNotNull(wc?.primaryColor, wc?.secondaryColor, wc?.tertiaryColor).map { it.toArgb() } +
            listOf(getColor(android.R.color.system_accent2_200), getColor(android.R.color.system_accent3_200))).take(4)
        val sw = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(12f), 0, 0) }
        for (colour in swatches) sw.addView(View(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(colour); setStroke(dp(1f), 0x2E808080) }
        }, LinearLayout.LayoutParams(dp(20f), dp(20f)).apply { marginEnd = dp(6f) })
        sw.addView(TextView(this).apply {
            text = "from your wallpaper"; setTextColor(sub); setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Fonts.ui(this@SettingsActivity, 400); setPadding(dp(2f), 0, 0, 0)
        })
        col.addView(sw)
        c.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(16f) })
        c.foreground = ripple()
        c.setOnClickListener { startActivity(Intent(this, WallpaperStyleActivity::class.java)) }
    }

    private fun heroWidth() = (resources.displayMetrics.widthPixels - dp(12f) * 2 - dp(10f)) / 2

    /** Wallpaper and Icons, side by side and a third of the screen tall: the wallpaper itself, and four of your icons on it. */
    private fun heroes() {
        val h = minOf((resources.displayMetrics.heightPixels * 0.33f).toInt(), (heroWidth() * 1.9f).toInt())
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val blur = prefs.homeBlur
        val live = LiveWallpaper.isActive(this)
        val wallSummary = "Blur ${if (blur) "on" else "off"} · Live wallpaper ${if (live) "on" else "off"} · Colour palette"
        val wall = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setImageBitmap(wallBitmap) }
        row.addView(hero("Wallpaper", wallSummary, listOf(wall)) { startActivity(Intent(this, WallpaperStyleActivity::class.java)) },
            LinearLayout.LayoutParams(0, h, 1f).apply { marginEnd = dp(5f) })

        val iconsBg = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP; setImageBitmap(wallBitmap)
            setRenderEffect(RenderEffect.createBlurEffect(dp(10f).toFloat(), dp(10f).toFloat(), Shader.TileMode.CLAMP))
            setColorFilter(0x26000000)
        }
        val icons = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_START; setImageBitmap(iconsBitmap) }
        wallViews = listOf(wall, iconsBg)
        iconsView = icons
        val style = IconThemeActivity.STYLES[IconThemeActivity.style(prefs)]
        row.addView(hero("Icons", "$style · ${IconShape.name(prefs.iconShape)} · size ${prefs.iconScale}%", listOf(iconsBg, icons)) {
            startActivity(Intent(this, IconThemeActivity::class.java))
        }, LinearLayout.LayoutParams(0, h, 1f).apply { marginStart = dp(5f) })
        list.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(20f) })
    }

    /** A big tile: [layers] filling it, a dark fade at the foot with [title] and [summary] on it, and a round chevron. */
    private fun hero(title: String, summary: String, layers: List<View>, onClick: () -> Unit): View {
        val radius = dp(28f).toFloat()
        val f = FrameLayout(this).apply {
            setBackgroundColor(cardColor)
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) = outline.setRoundRect(0, 0, view.width, view.height, radius)
            }
            clipToOutline = true
            contentDescription = title
            foreground = android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), null, null)
            setOnClickListener { onClick() }
        }
        for (l in layers) f.addView(l, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        f.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.TRANSPARENT, 0x9E000000.toInt()))
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(150f), Gravity.BOTTOM))
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16f), 0, dp(52f), dp(16f)) }
        texts.addView(TextView(this).apply {
            text = title; setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            typeface = Fonts.ui(this@SettingsActivity, 700); letterSpacing = -0.02f
        })
        texts.addView(TextView(this).apply {
            text = summary; setTextColor(0xD9FFFFFF.toInt()); setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Fonts.ui(this@SettingsActivity, 400); setPadding(0, dp(2f), 0, 0)
        })
        f.addView(texts, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        f.addView(ImageView(this).apply {
            setImageDrawable(Chevron(Color.WHITE, dp(12f), d, right = true))
            scaleType = ImageView.ScaleType.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x52000000) }
        }, FrameLayout.LayoutParams(dp(34f), dp(34f), Gravity.BOTTOM or Gravity.END).apply { marginEnd = dp(12f); bottomMargin = dp(16f) })
        return f
    }

    /** A Home screen tile: a round glyph, its name and its current values in the accent. */
    private fun tile(glyph: String, title: String, summary: String, onClick: () -> Unit): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16f), dp(16f), dp(14f), dp(16f))
        background = GradientDrawable().apply { cornerRadius = 24f * d; setColor(cardColor) }
        foreground = android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(if (night) 0x33FFFFFF else 0x1A000000), null,
            GradientDrawable().apply { cornerRadius = 24f * d; setColor(Color.WHITE) })
        contentDescription = title
        setOnClickListener { onClick() }
        addView(View(this@SettingsActivity).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(primarySoft) }
            foreground = GlyphDrawable(glyph, primary, dp(20f))
        }, LinearLayout.LayoutParams(dp(38f), dp(38f)))
        addView(TextView(this@SettingsActivity).apply {
            text = title; setTextColor(this@SettingsActivity.text); setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            typeface = Fonts.ui(this@SettingsActivity, 600); setPadding(0, dp(12f), 0, 0)
        })
        addView(TextView(this@SettingsActivity).apply {
            text = summary; setTextColor(primary); setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Fonts.ui(this@SettingsActivity, 400); setPadding(0, dp(2f), 0, 0)
        })
    }

    private fun tilePair(a: View, b: View) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(a, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { marginEnd = dp(5f) })
        row.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { marginStart = dp(5f) })
        list.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10f) })
    }

    /** The wallpaper, four of your icons in your theme (clear, over the blurred wallpaper) and the small Home. */
    private fun drawPreviews() {
        if (isDestroyed) return
        preview.wallpaper()?.let { b -> wallBitmap = b; wallViews.forEach { it.setImageBitmap(b) } }
        val w = heroWidth()
        preview.themeTiles(listOf(StylePreview.Look.of(prefs)), w, w, clear = true) { _, b -> iconsBitmap = b; iconsView?.setImageBitmap(b) }
        val mini = dp(76f)
        preview.render(StylePreview.Look.of(prefs), mini * 2, mini) { home, _ -> homeBitmap = home; homeView?.bitmap = home }
    }

    // ---------------------------------------------------------------- search results

    private fun results(q: String) {
        val hits = SettingsIndex.search(this, q)
        groupHeader(if (hits.size == 1) "1 result" else "${hits.size} results")
        if (hits.isEmpty()) {
            caption("No settings match “$q”.")
            return
        }
        val words = q.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        card {
            for (h in hits) {
                val summary = SpannableStringBuilder().apply {
                    append(h.path, ForegroundColorSpan(primary), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    append("\n")
                    append(marked(h.summary, words))
                }
                val r = row(h.title, summary, sub, null, {
                    hideKeyboard(search)
                    startActivity(h.intent(this))
                })
                ((r.getChildAt(0) as LinearLayout).getChildAt(0) as TextView).text = marked(h.title, words)
                withIcon(r, h.glyph)
            }
        }
    }

    /** [s] with each of [words] in it in the accent and bold. */
    private fun marked(s: String, words: List<String>): CharSequence {
        val out = SpannableStringBuilder(s)
        val low = s.lowercase()
        for (w in words) {
            var i = low.indexOf(w)
            while (i >= 0) {
                out.setSpan(ForegroundColorSpan(primary), i, i + w.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                out.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), i, i + w.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                i = low.indexOf(w, i + w.length)
            }
        }
        return out
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == AppBubbles.REQUEST_NOTIFICATIONS) {
            AppBubbles.onPermissionResult(this, grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED)
            build()
        }
    }
}
