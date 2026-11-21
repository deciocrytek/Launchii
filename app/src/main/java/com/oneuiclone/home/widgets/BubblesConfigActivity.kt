package com.oneuiclone.home.widgets

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.UserManager
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.oneuiclone.home.AppBubbles
import com.oneuiclone.home.icons.IconFactory
import com.oneuiclone.home.model.AppKey
import com.oneuiclone.home.settings.OneUiPage
import com.oneuiclone.home.ui.Fonts
import java.text.Collator
import java.util.concurrent.Executors

/**
 * The Bubbles widget's configuration (when it's added, and from its long-press › Configure): pick up to
 * [BubblesWidget.MAX] apps, numbered in the order they were picked; the first opens, the rest wait in the stack.
 * Every tap is saved at once. Leaving without picking anything when the widget is first added cancels adding it.
 */
class BubblesConfigActivity : OneUiPage() {

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private val chosen = ArrayList<AppKey>()
    private lateinit var apps: List<Pair<AppKey, LauncherActivityInfo>>
    private val worker = Executors.newSingleThreadExecutor()
    private val factory by lazy { IconFactory(this) }
    private val main = Handler(Looper.getMainLooper())
    private var summary: TextView? = null
    private val marks = HashMap<AppKey, OrderMark>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appWidgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }
        setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
        chosen += BubblesWidget.apps(this, appWidgetId)
        val serial = getSystemService(UserManager::class.java).getSerialNumberForUser(Process.myUserHandle())
        val collator = Collator.getInstance()
        apps = getSystemService(LauncherApps::class.java).getActivityList(null, Process.myUserHandle())
            .filter { it.componentName.packageName != packageName }
            .map { AppKey(it.componentName, serial) to it }
            .sortedWith { a, b -> collator.compare(a.second.label.toString(), b.second.label.toString()) }
    }

    override fun onDestroy() {
        super.onDestroy()
        worker.shutdownNow()
    }

    override fun finish() {
        // every pick is saved as it's made ([toggle]), so a widget with a saved choice (even an emptied one) is kept
        if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID && BubblesWidget.isConfigured(this, appWidgetId)) {
            setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
            val context = applicationContext
            val id = appWidgetId
            Thread { BubblesWidget.updateRemote(context, id) }.start()
        }
        super.finish()
    }

    private var built = false
    private var setupRow: LinearLayout? = null
    private val rows = ArrayList<Pair<String, View>>()

    /** Built once (the search and scroll position survive the bubble settings); on later resumes only the setup row. */
    override fun build() {
        if (!built) { built = true; layout() }
        val missing = AppBubbles.missing(this)
        setupRow?.visibility = if (missing == null) View.GONE else View.VISIBLE
        ((setupRow as? LinearLayout)?.getChildAt(0) as? LinearLayout)?.getChildAt(1)?.let { (it as TextView).text = missing }
    }

    private fun layout() {
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(bg) }

        // fixed: the app bar with its Done check, and the search field
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12f), 0, dp(12f), dp(12f)) }
        appBar("Bubbles", CheckGlyph(this).apply {
            contentDescription = "Done"
            background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(if (night) 0x33FFFFFF else 0x1A000000), null, null)
            setOnClickListener { finish() }
        })
        list.addView(searchField(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48f)))
        page.addView(list)

        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12f), 0, dp(12f), dp(24f)) }
        card {
            val s = row("Apps to open in bubbles", countText(), sub, null, null)
            summary = (s.getChildAt(0) as LinearLayout).getChildAt(1) as TextView
            setupRow = row("Turn on bubbles", AppBubbles.missing(this), primary, null, { AppBubbles.setUp(this) })
        }
        card {
            for ((key, info) in apps) {
                val mark = OrderMark(this)
                marks[key] = mark
                val label = info.label.toString()
                val r = row(label, null, sub, mark, { toggle(key) })
                r.addView(ImageView(this).apply { loadIcon(key, info, this) },
                    0, LinearLayout.LayoutParams(dp(40f), dp(40f)).apply { marginEnd = dp(16f) })
                mark.layoutParams = LinearLayout.LayoutParams(dp(26f), dp(26f)).apply { marginStart = dp(12f) }
                rows += label to r
            }
        }
        refreshMarks()
        val scroll = ScrollView(this).apply { addView(list); isVerticalScrollBarEnabled = false; clipToPadding = false }
        page.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        page.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val ime = insets.getInsets(WindowInsets.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, 0)
            scroll.setPadding(0, 0, 0, maxOf(bars.bottom, ime.bottom))
            insets
        }
        setContentView(page)
    }

    /** One UI's search field: a rounded pill with a magnifier; filters the apps by name as you type. */
    private fun searchField() = android.widget.EditText(this).apply {
        hint = "Search apps"
        setSingleLine()
        imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
        setTextColor(this@BubblesConfigActivity.text)
        setHintTextColor(sub)
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16f)
        typeface = com.oneuiclone.home.ui.Fonts.ui(this@BubblesConfigActivity, 400)
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = 100f * d; setColor(if (night) 0xFF2A2A2C.toInt() else 0xFFE9E9EC.toInt())
        }
        setPadding(dp(16f), 0, dp(16f), 0)
        compoundDrawablePadding = dp(10f)
        setCompoundDrawablesRelativeWithIntrinsicBounds(SearchGlyph(sub, dp(18f), d), null, null, null)
        addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) = filter(s?.toString().orEmpty())
        })
        setOnEditorActionListener { v, _, _ ->
            getSystemService(android.view.inputmethod.InputMethodManager::class.java).hideSoftInputFromWindow(v.windowToken, 0); true
        }
    }

    /** Shows the apps whose name has [query] in it, ignoring case and accents. */
    private fun filter(query: String) {
        val q = fold(query.trim())
        rows.forEach { (label, r) -> r.visibility = if (q.isEmpty() || q in fold(label)) View.VISIBLE else View.GONE }
    }

    private fun fold(s: String) = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "").lowercase()

    private fun countText() = when (chosen.size) {
        0 -> "Choose up to ${BubblesWidget.MAX}. The first one opens, the others wait in the bubble stack."
        else -> "${chosen.size} of ${BubblesWidget.MAX} chosen. The first one opens, the others wait in the bubble stack."
    }

    private fun toggle(key: AppKey) {
        if (key in chosen) chosen.remove(key)
        else if (chosen.size >= BubblesWidget.MAX) {
            Toast.makeText(this, "You can choose up to ${BubblesWidget.MAX} apps", Toast.LENGTH_SHORT).show(); return
        } else chosen += key
        BubblesWidget.setApps(this, appWidgetId, chosen)
        refreshMarks()
    }

    private fun refreshMarks() {
        marks.forEach { (k, m) -> m.number = chosen.indexOf(k) + 1 }
        summary?.text = countText()
    }

    private fun loadIcon(key: AppKey, info: LauncherActivityInfo, into: ImageView) {
        val size = dp(40f)
        val dpi = resources.displayMetrics.densityDpi
        worker.execute {
            val b = try { factory.create(info.getIcon(dpi), size) } catch (_: Exception) { return@execute }
            main.post { into.setImageBitmap(b) }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == AppBubbles.REQUEST_NOTIFICATIONS) {
            AppBubbles.onPermissionResult(this, grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED)
            build()
        }
    }

    /** An empty ring, or a filled circle with the app's place in the order. */
    private inner class OrderMark(context: android.content.Context) : View(context) {
        var number = 0
            set(v) { if (field != v) { field = v; contentDescription = if (v > 0) "Chosen, number $v" else "Not chosen"; invalidate() } }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f * d }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = primary }
        private val digits = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            // the accent follows the wallpaper palette: a light one (dark mode) takes dark digits
            color = if (android.graphics.Color.luminance(primary) > 0.5f) 0xFF1A1A1A.toInt() else 0xFFFFFFFF.toInt(); textAlign = Paint.Align.CENTER; textSize = 14f * resources.displayMetrics.scaledDensity
            typeface = Fonts.ui(context, 600)
        }

        override fun onDraw(canvas: Canvas) {
            val r = minOf(width, height) / 2f
            if (number == 0) {
                ring.color = sub
                canvas.drawCircle(width / 2f, height / 2f, r - ring.strokeWidth, ring)
            } else {
                canvas.drawCircle(width / 2f, height / 2f, r, fill)
                val fm = digits.fontMetrics
                canvas.drawText(number.toString(), width / 2f, height / 2f - (fm.ascent + fm.descent) / 2f, digits)
            }
        }
    }

    /** The app bar's Done: a check mark. */
    private inner class CheckGlyph(context: android.content.Context) : View(context) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
            strokeWidth = 2.2f * d; color = this@BubblesConfigActivity.text
        }
        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f; val cy = height / 2f; val u = 6f * d
            val path = android.graphics.Path().apply { moveTo(cx - 1.5f * u, cy); lineTo(cx - 0.4f * u, cy + 1.1f * u); lineTo(cx + 1.6f * u, cy - 1.1f * u) }
            canvas.drawPath(path, p)
        }
    }

    /** The search field's magnifier. */
    private class SearchGlyph(color: Int, private val sizePx: Int, density: Float) : android.graphics.drawable.Drawable() {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color; style = Paint.Style.STROKE; strokeWidth = 1.8f * density; strokeCap = Paint.Cap.ROUND
        }
        override fun draw(canvas: Canvas) {
            val b = bounds; val r = b.width() * 0.32f
            val cx = b.left + b.width() * 0.42f; val cy = b.top + b.height() * 0.42f
            canvas.drawCircle(cx, cy, r, p)
            canvas.drawLine(cx + r * 0.72f, cy + r * 0.72f, b.right - b.width() * 0.08f, b.bottom - b.height() * 0.08f, p)
        }
        override fun getIntrinsicWidth() = sizePx
        override fun getIntrinsicHeight() = sizePx
        override fun setAlpha(alpha: Int) { p.alpha = alpha }
        override fun setColorFilter(cf: android.graphics.ColorFilter?) { p.colorFilter = cf }
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
    }
}
