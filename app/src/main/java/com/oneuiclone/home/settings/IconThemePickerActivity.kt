package com.oneuiclone.home.settings

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.oneuiclone.home.ui.Fonts

/**
 * Icons › Icon theme (the user's mockup A, mockups/icon-theme-picker.html): every theme as a card showing four of
 * your own apps in it, two to a row, under Simple, Stylish and Playful. Tapping a card uses it; your dock stays
 * pinned at the top and follows. Back keeps the choice.
 */
class IconThemePickerActivity : OneUiPage() {

    private lateinit var preview: StylePreview
    private lateinit var dockStrip: PreviewCard
    private var built = false
    private val cards = HashMap<Int, View>()
    private val tiles = HashMap<Int, PreviewCard>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preview = StylePreview(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        preview.destroy()
    }

    override fun build() {
        if (!built) { built = true; layout() }
        preview.load {
            refreshDock()
            val base = StylePreview.Look.of(prefs)
            val looks = IconThemeActivity.STYLES.indices.map { IconThemeActivity.look(it, base, prefs) }
            preview.themeTiles(looks, tileWidth(), tileHeight()) { i, b -> tiles[i]?.bitmap = b }
        }
    }

    private fun stripWidth() = resources.displayMetrics.widthPixels - dp(12f) * 2 - dp(14f) * 2
    private fun cardWidth() = (resources.displayMetrics.widthPixels - dp(12f) * 2 - dp(GAP)) / 2
    private fun tileWidth() = cardWidth() - dp(PAD) * 2
    private fun tileHeight() = (tileWidth() * 0.8f).toInt()

    private fun refreshDock() = preview.render(StylePreview.Look.of(prefs), stripWidth() / 3, stripWidth()) { _, dock -> dockStrip.bitmap = dock }

    private fun select(s: Int) {
        val ring = primary
        for ((i, v) in cards) {
            (v.background as GradientDrawable).setStroke(dp(2f), if (i == s) ring else android.graphics.Color.TRANSPARENT)
            v.findViewWithTag<View>("check").visibility = if (i == s) View.VISIBLE else View.GONE
            v.isSelected = i == s
        }
    }

    private fun layout() {
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(bg) }

        // fixed: the app bar and the dock
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12f), 0, dp(12f), 0) }
        appBar("Icon theme")
        card {
            dockStrip = PreviewCard(this@IconThemePickerActivity, dp(20f).toFloat(), bg).apply { contentDescription = "Your dock with this theme" }
            current?.addView(FrameLayout(this@IconThemePickerActivity).apply {
                setPadding(dp(14f), dp(4f), dp(14f), dp(4f))
                addView(dockStrip, FrameLayout.LayoutParams(stripWidth(), preview.dockHeight(stripWidth())))
            })
        }
        page.addView(list)

        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12f), 0, dp(12f), dp(24f)) }
        for ((group, styles) in IconThemeActivity.GROUPS) {
            list.addView(TextView(this).apply {
                text = group; setTextColor(primary); setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                typeface = Fonts.ui(this@IconThemePickerActivity, 600)
                setPadding(dp(12f), dp(4f), dp(12f), dp(10f))
            })
            for (pair in styles.chunked(2)) {
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                pair.forEachIndexed { k, s ->
                    row.addView(themeCard(s).also { register(IconThemeActivity.STYLES[s], it) }, LinearLayout.LayoutParams(cardWidth(), ViewGroup.LayoutParams.MATCH_PARENT).apply {
                        if (k == 1) marginStart = dp(GAP)
                    })
                }
                list.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(GAP)
                })
            }
            list.addView(View(this), LinearLayout.LayoutParams(1, dp(10f)))
        }
        select(IconThemeActivity.style(prefs))
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

    /** Style [s]'s card: its tile, a check when it's the one in use, then its name and what it looks like. */
    private fun themeCard(s: Int): View {
        val tile = PreviewCard(this, dp(16f).toFloat(), if (night) 0xFF242426.toInt() else 0xFFEDEDF0.toInt())
        tiles[s] = tile
        val check = TextView(this).apply {
            tag = "check"
            text = "✓"; gravity = Gravity.CENTER
            setTextColor(onPrimary); setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Fonts.ui(this@IconThemePickerActivity, 700)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(primary) }
            visibility = View.GONE
        }
        val top = FrameLayout(this).apply {
            addView(tile, FrameLayout.LayoutParams(tileWidth(), tileHeight()))
            addView(check, FrameLayout.LayoutParams(dp(22f), dp(22f), Gravity.TOP or Gravity.END).apply { topMargin = dp(8f); marginEnd = dp(8f) })
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(PAD), dp(PAD), dp(PAD), dp(12f))
            background = GradientDrawable().apply { cornerRadius = 22f * d; setColor(cardColor) }
            foreground = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(if (night) 0x33FFFFFF else 0x1A000000), null,
                GradientDrawable().apply { cornerRadius = 22f * d; setColor(android.graphics.Color.WHITE) })
            contentDescription = IconThemeActivity.STYLES[s]
            addView(top)
            addView(TextView(this@IconThemePickerActivity).apply {
                text = IconThemeActivity.STYLES[s]; setTextColor(this@IconThemePickerActivity.text)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f); typeface = Fonts.ui(this@IconThemePickerActivity, 600)
                setPadding(dp(4f), dp(8f), dp(4f), 0)
            })
            addView(TextView(this@IconThemePickerActivity).apply {
                text = IconThemeActivity.SUMMARIES[s]; setTextColor(sub)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f); typeface = Fonts.ui(this@IconThemePickerActivity, 400)
                setPadding(dp(4f), dp(2f), dp(4f), 0)
            })
            setOnClickListener {
                if (isSelected) return@setOnClickListener
                IconThemeActivity.apply(s, prefs)
                select(s)
                refreshDock()
            }
            cards[s] = this
        }
    }

    private companion object {
        /** Space between cards, and inside a card around its tile (dp). */
        const val GAP = 10f
        const val PAD = 8f
    }
}
