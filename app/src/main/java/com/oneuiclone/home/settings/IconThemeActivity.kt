package com.oneuiclone.home.settings

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView

/**
 * Icons (Home screen edit mode, Settings): the icon shape, the icon theme and the rows that go with it, then icon size
 * and app labels.
 * Rows come and go as the style changes; the dock strip stays fixed at the top and follows every change, the
 * sliders included.
 */
class IconThemeActivity : OneUiPage() {

    companion object {
        // 0 Default: the apps' own icons. 1 Themed: your colour palette. 2 Frosted: themed, with a see-through
        // plate. 3 Dark: every app on a dark plate, in its own colours. 4 Minimal: themed icons' white glyph alone.
        // 5 Dot: the themed glyph as a dot grid. 6 Prism: the themed glyph in a gradient of the app's colour, on white or graphite.
        // 7 Stencil: the themed glyph cut out of a black plate, the wallpaper showing through frosted.
        // 8 Satin: the themed glyph in white, faintly shaded, on a matte black plate.
        // 9–12 Doodle, Sprinkles, Comic, Groovy: the playful looks (IconFactory.LOOK_*).
        // 13 Glass: each app's own icon with a subtle glass finish, light or dark.
        // 14 Clear Colour: each app's symbol in its own colours on clear frosted glass.
        // 15 Minimal Colour: Clear Colour's symbol alone, no plate.
        val STYLES = listOf("Default", "Themed", "Frosted", "Dark", "Minimal", "Dot", "Prism", "Stencil", "Satin",
            "Doodle", "Sprinkles", "Comic", "Groovy", "Glass", "Clear Colour", "Minimal Colour")
        val SUMMARIES = listOf(
            "Each app's own icon",
            "Your colour palette, or a colour you pick, on app icons",
            "Theme icons on see-through glass over your wallpaper",
            "Dark backgrounds with each app's colours, for any app",
            "Just the white symbols, with no icon shape behind them",
            "Symbols drawn in dots, black on paper or white on black",
            "Symbols in a gradient of each app's colour, on white or graphite",
            "Symbols cut out of black, showing your wallpaper frosted",
            "White symbols on matte black, every app in black and white",
            "Drawn by hand with a marker and crayon",
            "Pastel frosting with rainbow sprinkles",
            "Pop-art halftone with bold black ink",
            "70s outlines in warm stripes",
            "Each app's own icon with a subtle glass effect. Dark plates in dark mode",
            "Each app's colours on clear frosted glass over your wallpaper",
            "Just the symbols in each app's own colours, with no shape behind them",
        )
        /** The Icon theme page's groups, in order, and the styles in each. */
        val GROUPS = listOf("Simple" to listOf(0, 1, 2, 14, 3, 4, 15, 13), "Stylish" to (5..8).toList(), "Playful" to (9..12).toList())
        private val THEMED = setOf(1, 2, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15)
        /** The styles with a light and a dark version (Dot, Prism, the playful ones and Glass), which get Light or dark. */
        private val LIGHT_AND_DARK = setOf(5, 6, 9, 10, 11, 12, 13)
        private const val COMIC = 11
        /** The styles drawn in the theme colour (Themed, Frosted and Clear Colour's veil), which get Icon colour. */
        private val COLOURED = setOf(1, 2, 14)
        /** The see-through styles (Frosted's plate, Clear Colour's veil), which get Icon transparency. */
        private val SEE_THROUGH = setOf(2, 14)
        /** The styles whose plate has a contour (One UI's emboss, Satin's, Glass's or Clear Colour's rim), which get Icon contour. */
        private val CONTOURED = setOf(0, 1, 2, 3, 5, 6, 7, 8, 10, 12, 13, 14, 15)
        /** Each look's style index. */
        private val LOOKS = mapOf(
            5 to com.oneuiclone.home.icons.IconFactory.LOOK_DOT,
            6 to com.oneuiclone.home.icons.IconFactory.LOOK_PRISM,
            7 to com.oneuiclone.home.icons.IconFactory.LOOK_STENCIL,
            8 to com.oneuiclone.home.icons.IconFactory.LOOK_SATIN,
            9 to com.oneuiclone.home.icons.IconFactory.LOOK_DOODLE,
            10 to com.oneuiclone.home.icons.IconFactory.LOOK_SPRINKLES,
            11 to com.oneuiclone.home.icons.IconFactory.LOOK_COMIC,
            12 to com.oneuiclone.home.icons.IconFactory.LOOK_GROOVY,
            13 to com.oneuiclone.home.icons.IconFactory.LOOK_IOS,
            14 to com.oneuiclone.home.icons.IconFactory.LOOK_CLEAR_COLOUR,
            15 to com.oneuiclone.home.icons.IconFactory.LOOK_MINIMAL_COLOUR,
        )

        /** Saves style [s] (Default, Themed, …, Glass). The icon shape stays as it is (the user's choice, 0.209). */
        fun apply(s: Int, prefs: Prefs) {
            prefs.themedIcons = s in THEMED
            prefs.glassIcons = s == 2
            prefs.darkIcons = s == 3
            prefs.minimalIcons = s == 4
            prefs.iconLook = LOOKS[s] ?: ""
        }

        /** [base] (the saved look) with style [s] in its place, for previews of the styles not chosen. */
        fun look(s: Int, base: StylePreview.Look, prefs: Prefs) = base.copy(
            themed = s in THEMED, dark = s == 3, minimal = s == 4, look = LOOKS[s] ?: "",
            plateAlpha = if (s in SEE_THROUGH) 1f - prefs.glassIconTransparency / 100f else 1f,
        )
        private val MODES = listOf(
            com.oneuiclone.home.icons.IconFactory.MODE_AUTO to "Auto",
            com.oneuiclone.home.icons.IconFactory.MODE_LIGHT to "Light",
            com.oneuiclone.home.icons.IconFactory.MODE_DARK to "Dark",
        )
        private val MODE_SUMMARIES = listOf(
            "Light or dark with your phone's dark mode",
            "Light icons, in dark mode too",
            "Dark icons, in light mode too",
        )

        fun style(prefs: Prefs) = when {
            prefs.darkIcons -> 3; !prefs.themedIcons -> 0
            prefs.iconLook.isNotEmpty() -> LOOKS.entries.firstOrNull { it.value == prefs.iconLook }?.key ?: 1
            prefs.minimalIcons -> 4; prefs.glassIcons -> 2; else -> 1
        }
    }

    private lateinit var preview: StylePreview
    private lateinit var dockStrip: PreviewCard
    private var built = false
    /** Slider positions while dragged, previewed before they're saved. */
    private var liveTransparency: Int? = null
    private var liveScale: Int? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preview = StylePreview(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        preview.destroy()
    }

    override fun build() {
        // the icon shape changed while this page was away: show the new one in the shape row too
        if (built && prefs.iconShape != shapeShown) { recreate(); return }
        if (!built) { built = true; shapeShown = prefs.iconShape; layout() } else { onReturn?.invoke(style(prefs)); colourShown?.invoke() }
        preview.load { refreshDock() }
    }

    /** The icon shape the page was laid out with. */
    private var shapeShown = ""

    /** Shows the saved theme in the Icon theme row, and the rows that go with it. */
    private var onReturn: ((Int) -> Unit)? = null

    private fun stripWidth() = resources.displayMetrics.widthPixels - dp(12f) * 2 - dp(14f) * 2

    private fun refreshDock() = preview.render(StylePreview.Look.of(prefs, iconScale = liveScale ?: prefs.iconScale,
        transparency = liveTransparency ?: prefs.glassIconTransparency), stripWidth() / 3, stripWidth()) { _, dock -> dockStrip.bitmap = dock }

    /**
     * Icon colour (Themed and Frosted): a row with the colour in a swatch; tapping it opens the Icon colour page
     * ([IconColourActivity]). Returns a function that shows or hides the row; [onReturn] shows a colour picked there.
     */
    private fun colourRow(): (Boolean) -> Unit {
        val paletteColour = getColor(android.R.color.system_accent1_400)
        val shown = { if (prefs.themeColor != 0) prefs.themeColor else paletteColour }
        val swatch = SwatchDot(this).apply { color = shown() }
        val chevron = android.widget.ImageView(this).apply { setImageDrawable(Chevron(sub, dp(10f), d, right = true)) }
        val trailing = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(swatch, LinearLayout.LayoutParams(dp(26f), dp(26f)))
            addView(chevron, LinearLayout.LayoutParams(dp(10f), dp(10f)).apply { marginStart = dp(14f) })
        }
        val r = row("Icon colour", IconColourActivity.name(prefs.themeColor), sub, trailing, {
            startActivity(android.content.Intent(this, IconColourActivity::class.java))
        })
        val summary = (r.getChildAt(0) as LinearLayout).getChildAt(1) as android.widget.TextView
        colourShown = { swatch.color = shown(); summary.text = IconColourActivity.name(prefs.themeColor) }
        return { on -> r.visibility = if (on) View.VISIBLE else View.GONE }
    }

    /** Shows the saved colour in the Icon colour row (after the Icon colour page). */
    private var colourShown: (() -> Unit)? = null

    /**
     * Icon shape: a row with the current shape in a chip; tapping it opens every shape as a tile under it (tap one to
     * use it), and tapping it again closes them. Under it, for any shape but the squircle, Keep folders and widgets
     * squircle; and for round folders, Folder icons: the apps inside the folder's circle, poking out past it, or four
     * as on Pixel.
     */
    private fun shapeCard() = card {
        val c = current ?: return@card
        var chosen = prefs.iconShape
        val glyph = ShapeGlyph(this, text).apply { shape = chosen }
        val chevron = android.widget.ImageView(this).apply { setImageDrawable(Chevron(text, dp(10f), d)) }
        val chip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(14f), dp(9f), dp(12f), dp(9f))
            background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = 100f * d; setColor(if (night) 0xFF2A2A2C.toInt() else 0xFFEDEDF0.toInt()) }
            addView(glyph, LinearLayout.LayoutParams(dp(20f), dp(20f)))
            addView(chevron, LinearLayout.LayoutParams(dp(10f), dp(10f)).apply { marginStart = dp(8f) })
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(12f) }
        }
        val tiles = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12f), dp(2f), dp(12f), dp(14f))
            visibility = View.GONE
        }
        lateinit var r: LinearLayout
        lateinit var folderRow: View
        lateinit var keepRow: View
        fun showRows() {
            keepRow.visibility = if (chosen != com.oneuiclone.home.icons.IconShape.SQUIRCLE) View.VISIBLE else View.GONE
            folderRow.visibility = if (chosen == com.oneuiclone.home.icons.IconShape.CIRCLE && !prefs.keepSquircle) View.VISIBLE else View.GONE
        }
        r = row("Icon shape", com.oneuiclone.home.icons.IconShape.name(chosen), sub, chip, {
            val open = tiles.visibility != View.VISIBLE
            tiles.visibility = if (open) View.VISIBLE else View.GONE
            chevron.animate().rotation(if (open) 180f else 0f).setDuration(200).start()
        })
        val inactive = if (night) 0xFF4A4A4C.toInt() else 0xFFC4C4C8.toInt()
        val picked = (primary and 0x00FFFFFF) or 0x26000000
        fun mark(tile: View, on: Boolean) {
            val box = tile.findViewWithTag<View>("box")
            box.background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 16f * d
                setColor(if (on) picked else android.graphics.Color.TRANSPARENT)
                setStroke(dp(2f), if (on) primary else android.graphics.Color.TRANSPARENT)
            }
            tile.findViewWithTag<ShapeGlyph>("glyph").color = if (on) primary else inactive
            tile.findViewWithTag<android.widget.TextView>("name").setTextColor(if (on) text else sub)
            tile.isSelected = on
        }
        for (id in com.oneuiclone.home.icons.IconShape.ALL) {
            val tile = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER_HORIZONTAL
                contentDescription = com.oneuiclone.home.icons.IconShape.name(id)
                addView(FrameLayout(this@IconThemeActivity).apply {
                    tag = "box"
                    addView(ShapeGlyph(this@IconThemeActivity, inactive).apply { tag = "glyph"; shape = id },
                        FrameLayout.LayoutParams(dp(34f), dp(34f), android.view.Gravity.CENTER))
                }, LinearLayout.LayoutParams(dp(56f), dp(56f)))
                addView(android.widget.TextView(this@IconThemeActivity).apply {
                    tag = "name"
                    text = com.oneuiclone.home.icons.IconShape.name(id)
                    // it spans the column (a vertical LinearLayout's default), so the text is centred in it
                    gravity = android.view.Gravity.CENTER_HORIZONTAL
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
                    typeface = com.oneuiclone.home.ui.Fonts.ui(this@IconThemeActivity, 500)
                    setPadding(0, dp(6f), 0, 0)
                })
                setOnClickListener {
                    if (id == chosen) return@setOnClickListener
                    chosen = id
                    prefs.iconShape = id
                    shapeShown = id
                    glyph.shape = id
                    ((r.getChildAt(0) as LinearLayout).getChildAt(1) as android.widget.TextView).text = com.oneuiclone.home.icons.IconShape.name(id)
                    for (i in 0 until tiles.childCount) tiles.getChildAt(i).let { t -> mark(t, t.contentDescription == contentDescription) }
                    showRows()
                    refreshDock()
                }
            }
            mark(tile, id == chosen)
            tiles.addView(tile, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        c.addView(tiles)
        keepRow = toggle("Keep folders and widgets squircle", "Folders and Launchii's widgets stay One UI's shape. Only app icons change.",
            prefs.keepSquircle) {
            prefs.keepSquircle = it; showRows(); refreshDock()
        }
        val styles = listOf(com.oneuiclone.home.ui.FolderIconView.INSIDE, com.oneuiclone.home.ui.FolderIconView.OUTSIDE, com.oneuiclone.home.ui.FolderIconView.PIXEL)
        folderRow = dropdown("Folder icons", listOf("Inside", "Outside", "Pixel"), styles.indexOf(prefs.folderIcons).coerceAtLeast(0),
            listOf("Apps sit inside the folder's circle", "Apps poke out past the folder's edge",
                "Four apps, sized like Pixel Launcher. They open into the full folder")) {
            prefs.folderIcons = styles[it]; refreshDock()
        }
        showRows()
        c.layoutTransition = android.animation.LayoutTransition().apply { enableTransitionType(android.animation.LayoutTransition.CHANGING) }
    }

    /** An icon shape (IconShape) filled in [color], as big as the view. */
    private class ShapeGlyph(context: android.content.Context, color: Int) : View(context) {
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        private val path = android.graphics.Path()
        var shape = com.oneuiclone.home.icons.IconShape.SQUIRCLE
            set(v) { field = v; invalidate() }
        var color: Int
            get() = paint.color
            set(v) { paint.color = v; invalidate() }

        override fun onDraw(canvas: android.graphics.Canvas) {
            val s = minOf(width, height).toFloat()
            com.oneuiclone.home.icons.IconShape.path(path, width / 2f, height / 2f, s, shape)
            canvas.drawPath(path, paint)
        }
    }

    private fun layout() {
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(bg) }

        // fixed: the app bar and the dock
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12f), 0, dp(12f), 0) }
        appBar("Icons")
        card {
            dockStrip = PreviewCard(this@IconThemeActivity, dp(20f).toFloat(), bg).apply { contentDescription = "Your dock with these icons" }
            current?.addView(FrameLayout(this@IconThemeActivity).apply {
                setPadding(dp(14f), dp(4f), dp(14f), dp(4f))
                addView(dockStrip, FrameLayout.LayoutParams(stripWidth(), preview.dockHeight(stripWidth())))
            })
        }
        page.addView(list)

        // the controls, scrolling only if larger text makes them taller than the screen
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(12f), 0, dp(12f), dp(24f))
            // the cards below slide along as the shape tiles open and close
            layoutTransition = android.animation.LayoutTransition().apply { enableTransitionType(android.animation.LayoutTransition.CHANGING) }
        }
        shapeCard()
        card {
            val style = style(prefs)
            lateinit var modeRows: List<View>
            lateinit var anyRows: List<View>
            lateinit var frostedRows: List<View>
            lateinit var comicRows: List<View>
            lateinit var contourRows: List<View>
            lateinit var colourRows: (Boolean) -> Unit
            fun showFor(s: Int) {
                colourRows(s in COLOURED)
                modeRows.forEach { it.visibility = if (s in LIGHT_AND_DARK) View.VISIBLE else View.GONE }
                anyRows.forEach { it.visibility = if (s > 0) View.VISIBLE else View.GONE }
                frostedRows.forEach { it.visibility = if (s in SEE_THROUGH) View.VISIBLE else View.GONE }
                comicRows.forEach { it.visibility = if (s == COMIC) View.VISIBLE else View.GONE }
                contourRows.forEach { it.visibility = if (s in CONTOURED) View.VISIBLE else View.GONE }
            }
            // the themes have their own page (IconThemePickerActivity); coming back shows the one picked there
            val chevron = android.widget.ImageView(this).apply { setImageDrawable(Chevron(sub, dp(10f), d, right = true)) }
            val r = row("Icon theme", STYLES[style], primary, chevron, {
                startActivity(android.content.Intent(this, IconThemePickerActivity::class.java))
            })
            val summary = (r.getChildAt(0) as LinearLayout).getChildAt(1) as android.widget.TextView
            onReturn = { s -> summary.text = STYLES[s]; showFor(s) }
            colourRows = colourRow()
            modeRows = added {
                val mode = MODES.indexOfFirst { it.first == prefs.iconMode }.coerceAtLeast(0)
                dropdown("Light or dark", MODES.map { it.second }, mode, MODE_SUMMARIES) { m ->
                    prefs.iconMode = MODES[m].first; refreshDock()
                }
            }
            comicRows = added {
                toggle("Comic border", "The black ink outline around each icon.", prefs.comicBorder) {
                    prefs.comicBorder = it; refreshDock()
                }
            }
            contourRows = added {
                toggle("Icon contour", "The soft light and shading along the edge of each icon. Off for a plain, flat look.", prefs.iconShine) {
                    prefs.iconShine = it; refreshDock()
                }
            }
            anyRows = added {
                toggle("Apply to Apps screen and search", "Use these icons on the Apps screen and in search too, not just on Home.", prefs.themeAppsScreen) {
                    prefs.themeAppsScreen = it
                }
            }
            frostedRows = added {
                slider("Icon transparency", prefs.glassIconTransparency, 0, 100, onLive = { liveTransparency = it; refreshDock() }) {
                    prefs.glassIconTransparency = it; liveTransparency = null; refreshDock()
                }
            }
            showFor(style)
            // rows come and go smoothly as the style changes
            current?.layoutTransition = android.animation.LayoutTransition()
        }
        card {
            slider("Icon size", prefs.iconScale, 85, 115, onLive = { liveScale = it; refreshDock() }) {
                prefs.iconScale = it; liveScale = null; refreshDock()
            }
        }
        card {
            header("Show app labels")
            toggle("Home screen", null, prefs.homeLabels) { prefs.homeLabels = it }
            toggle("Apps screen", null, prefs.appsLabels) { prefs.appsLabels = it }
        }
        // temporary (0.168): the white-corner bug
        card { item("Icon check", "Temporary: shows which icons get white corners, and why") {
            startActivity(android.content.Intent(this, IconCheckActivity::class.java))
        } }
        val scroll = ScrollView(this).apply { addView(list); isVerticalScrollBarEnabled = false; clipToPadding = false }
        page.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        page.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            v.setPadding(bars.left, bars.top, bars.right, 0)
            scroll.setPadding(0, 0, 0, bars.bottom)
            insets
        }
        setContentView(page)
    }
}
