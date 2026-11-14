package com.oneuiclone.home.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Insets
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsAnimation
import android.view.WindowInsetsAnimationControlListener
import android.view.WindowInsetsAnimationController
import android.view.animation.AnimationUtils
import android.view.animation.LinearInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.oneuiclone.home.device.DeviceProfile
import com.oneuiclone.home.model.AppInfo
import com.oneuiclone.home.model.AppKey
import com.oneuiclone.home.motion.Easing
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.math.roundToInt

/**
 * Apps-screen search (One UI 8.5 floating search bar → Finder-style results).
 *
 * The bar grows out of whatever was tapped ([Origin]: the apps-screen pill or the Home screen Search button):
 * size, colour, corners, glyph and hint all morph into the full-width bar, which settles on top of the
 * keyboard on the keyboard's own curve. The keyboard height is remembered between sessions, so the bar heads
 * straight for where the keyboard will end; once the keyboard animates, its exact height and its per-frame
 * position take over, and the bar never sits behind it.
 *
 * Results stack upwards from the bar, best match nearest the thumb: up to 8 apps right above it, Google
 * suggestions above those (as many as fit, at least one), contacts on top (2-column chips that open the contact
 * page). Before anything is typed it shows the 8 most used apps that aren't on Home page 1 under three rows of
 * suggested contacts stretched to fill the space; both come from [SearchSources.snapshot], taken as the search
 * opens, so nothing reshuffles on screen. Everything is sized to fit above the bar and nothing ever scrolls, so a
 * swipe down anywhere goes back Home. The results fade and slide up together as the bar opens; while typing,
 * whatever changed just fades in. The result IME "go" opens is highlighted (Pixel launcher): the first app, else
 * contact, else the typed query on Google.
 *
 * From Home it opens by Lift-Off ([beginLift]): the launcher feeds it the finger's progress ([setLift]) and then a
 * spring's, and the bar, the results (growing out of the bar from 92%, rising into focus) and the keyboard, which it
 * holds through [requestImeControl], all follow; [endLift] settles it either way.
 */
@SuppressLint("ViewConstructor")
class AppsSearch(
    context: Context,
    private val dp: DeviceProfile,
    private val apps: () -> List<AppInfo>,
    private val onLaunch: (IconView, AppInfo) -> Unit,
    /** Gives result icons the apps screen's icon (theme icons or not) and its long-press / quick options / drag handling. */
    private val configureIcon: (IconView, AppInfo) -> Unit,
    /** Suggestions (taken as the search opens) and contact matches; outlives the search. */
    private val sources: SearchSources,
    /** Apps already on Home page 1 (its icons, folders and the dock): left out of the most-used row. */
    private val firstPage: () -> Set<AppKey>,
    /** The blurred wallpaper the open bar is frosted with (fixed to the window); null for a plain translucent bar. */
    private val glass: WidgetGlass? = null,
    /** Contacts: the launcher starts [Intent] with its open animation. */
    private val onOpenIntent: (View, Intent) -> Unit,
    private val onRequestContacts: () -> Unit,
    /** A swipe down may close (not while a result is long-pressed or dragged). */
    private val canSwipeHome: () -> Boolean,
    private val onSwipeHome: () -> Unit,
    /** A swipe up: on to the Apps screen. */
    private val onSwipeUp: () -> Unit,
    /** Tap outside / web search: the launcher decides where the bar goes back to ([close]). */
    private val onDismiss: () -> Unit,
    private val onClose: () -> Unit,
) : FrameLayout(context) {

    /** A pill the bar morphs out of and back into: [rect] in root coordinates, the rest relative to its left. */
    class Origin(
        val rect: RectF,
        val fill: Int,
        val stroke: Int,
        val elevation: Float,
        val glyphLeft: Float,
        val glyphBox: Float,
        val textStart: Float,
        val textSizePx: Float,
        /** The glass the pill shows (Home's folder-glass pill), carried into the bar as it grows; null for none. */
        val glass: WidgetGlass? = null,
        /** The pill's hint and glyph colour; null for the bar's own. */
        val ink: Int? = null,
        /** The pill isn't on screen: the bar stays where it rests and fades with the results instead of morphing. */
        val fade: Boolean = false,
    )

    private val field = EditText(context)
    private val pill = FrameLayout(context)
    private val pillBg = GradientDrawable()
    private val colors = SearchColors.of(context)
    private val glyph = MagnifierView(context, colors.hint)
    private val results = CellLayout(context, 4, 2)
    private val contactsBox = LinearLayout(context)
    private val web = LinearLayout(context)
    private val content = LinearLayout(context)
    /** The snapshot this search opened with. */
    private var scores: Map<AppKey, Double> = emptyMap()
    private var suggested: List<SearchSources.Contact> = emptyList()
    /** What there is to show; [render] fits it above the bar. */
    private var appsList: List<AppInfo> = emptyList()
    private var contactsList: List<SearchSources.Contact> = emptyList()
    private var webList: List<String> = emptyList()
    /** What's on screen, so only what changed is rebuilt (and fades in). */
    private var shownApps: List<AppKey> = emptyList()
    private var shownContacts: List<Long> = emptyList()
    private var shownChipHeight = 0
    private var shownWeb: List<String> = emptyList()
    /** The first render of this search: the whole results area rises in instead of each item fading. */
    private var opening = false
    private var pendingSources: Runnable? = null
    private val searchPrefs = context.getSharedPreferences("search", Context.MODE_PRIVATE)
    /** The result IME "go" opens. */
    private var enterTarget: View? = null
    private lateinit var origin: Origin
    /** The bar carries a blur ([layoutPill], a fading bar in Lift-Off). */
    private var pillBlurred = false
    /** Glass drawn under the pill's fill: the pill's own ([Origin.glass]), else the bar's frost ([glass]). */
    private var pillGlass: WidgetGlass? = null
    /** How much of [pillGlass] shows (0..1): a pill without glass gains it as it grows into the frosted bar. */
    private var glassAlpha = 0f
    /** The frosted bar's top-lit rim (0..1), coming in with the morph. */
    private var sheen = 0f
    /**
     * How much of the Apps screen's dim ([FROST_DIM]) lies over the glass (0..1): it comes and goes with the morph,
     * so a pill landing on Home's (whose glass has no dim) matches it exactly. 0.215–0.225 dimmed Home-glass pills all the
     * way down, and the bar snapped 16 % lighter as Home's own pill took over (user video 6924).
     */
    private var frostDim = 0f
    /** The frosted bar's ink ([pickFrostInk]): its hint and glyph, and the typed text. */
    private var frostHint = colors.hint
    private var frostText = colors.text
    /** Where the pill was last drawn on screen: the glass is fixed to the window, so moving means redrawing. */
    private val pillAt = android.graphics.Matrix()
    private val pillNow = android.graphics.Matrix()

    /**
     * The pill's background: [pillGlass] (fixed to the window, like the Home pill it grows from) in the pill's shape,
     * then the fill and stroke, then a rim lit from above that makes the open bar read as a pane of frosted glass.
     */
    private inner class GlassPill : android.graphics.drawable.DrawableWrapper(pillBg) {
        private val glassPaint = GlassPaint()
        private val rect = RectF()
        private val rimMatrix = android.graphics.Matrix()
        private val dim = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF000000.toInt() }
        /** A unit-tall gradient, stretched to the pill each frame (its height changes all through the morph). */
        private val rim = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = dp.dp(1f)
            shader = android.graphics.LinearGradient(0f, 0f, 0f, 1f, intArrayOf(colors.frostStroke, 0x0DFFFFFF, 0x1AFFFFFF),
                floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        }
        override fun draw(canvas: android.graphics.Canvas) {
            val r = pillBg.cornerRadius
            pillAt.reset(); pill.transformMatrixToGlobal(pillAt)
            val g = pillGlass
            if (g != null && glassAlpha > 0f && glassPaint.aim(g, pillAt)) {
                glassPaint.paint.alpha = (glassAlpha * 255).roundToInt()
                rect.set(bounds)
                canvas.drawRoundRect(rect, r, r, glassPaint.paint)
                // the Apps screen's dim, so the frost sits on the same scene as the blur around it
                dim.alpha = (frostDim * FROST_DIM * 255).roundToInt()
                canvas.drawRoundRect(rect, r, r, dim)
            }
            super.draw(canvas)
            if (sheen > 0f) {
                val half = rim.strokeWidth / 2f
                rect.set(bounds); rect.inset(half, half)
                rimMatrix.setScale(1f, rect.height()); rimMatrix.postTranslate(0f, rect.top)
                rim.shader.setLocalMatrix(rimMatrix)
                rim.alpha = (sheen * 255).roundToInt()
                canvas.drawRoundRect(rect, r - half, r - half, rim)
            }
        }
    }

    // the glass redraws the views it lists when it's rebuilt (a new wallpaper)
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        glass?.views?.add(pill)
    }

    override fun onDetachedFromWindow() {
        glass?.views?.remove(pill)
        super.onDetachedFromWindow()
    }

    /**
     * The frosted bar's ink for where it rests: white over most wallpapers, like the Home pill and the results; dark
     * when the frost there comes out bright enough that white would wash out.
     */
    private fun pickFrostInk(animate: Boolean = false) {
        val night = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        val light = !night && frostLuminance() > FROST_DARK_INK_ABOVE
        val hint = if (light) FROST_HINT_DARK else FROST_HINT
        val text = if (light) colors.text else FROST_TEXT
        inkAnim?.cancel()
        if (!animate || (hint == frostHint && text == frostText)) {
            frostHint = hint; frostText = text
            field.setTextColor(text)
            return
        }
        // the bar settled somewhere else (no keyboard after all): its ink crossfades instead of flipping
        val fromHint = frostHint; val fromText = frostText
        inkAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = FADE_MS
            interpolator = Easing.BASIC
            addUpdateListener {
                val t = it.animatedFraction
                frostHint = argb.evaluate(t, fromHint, hint) as Int
                frostText = argb.evaluate(t, fromText, text) as Int
                field.setTextColor(frostText)
                layoutPill()
            }
            start()
        }
    }
    private var inkAnim: ValueAnimator? = null

    /** Relative luminance (0..1) of the frost where the bar comes to rest ([kbTarget]): the glass there, dimmed, under [frostTint]. */
    private fun frostLuminance(): Float {
        val gl = glass ?: return 0f
        val b = gl.bitmap ?: return 0f                            // no glass: the frost is over the apps screen's dimmed blur
        val s = gl.scale
        val top = barTop(kbTarget, dp.searchBarHeight.toFloat())
        val x0 = (dp.dp(16f) / s).toInt().coerceIn(0, b.width - 1)
        val x1 = ((dp.widthPx - dp.dp(16f)) / s).toInt().coerceIn(x0 + 1, b.width)
        val y0 = (top / s).toInt().coerceIn(0, b.height - 1)
        val y1 = ((top + dp.searchBarHeight) / s).toInt().coerceIn(y0 + 1, b.height)
        val tint = frostTint()
        val a = android.graphics.Color.alpha(tint) / 255f
        fun lin(c: Float) = if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)
        val tr = android.graphics.Color.red(tint) / 255f; val tg = android.graphics.Color.green(tint) / 255f; val tb = android.graphics.Color.blue(tint) / 255f
        var sum = 0f; var n = 0
        val stepX = maxOf(1, (x1 - x0) / 24); val stepY = maxOf(1, (y1 - y0) / 4)
        var y = y0
        while (y < y1) {
            var x = x0
            while (x < x1) {
                val p = b.getPixel(x, y)
                // the dim and the tint over the glass, then to linear light
                val k = (1 - FROST_DIM) * (1 - a)
                val r = lin(android.graphics.Color.red(p) / 255f * k + tr * a)
                val g = lin(android.graphics.Color.green(p) / 255f * k + tg * a)
                val bl = lin(android.graphics.Color.blue(p) / 255f * k + tb * a)
                sum += 0.2126f * r + 0.7152f * g + 0.0722f * bl; n++
                x += stepX
            }
            y += stepY
        }
        return if (n == 0) 0f else sum / n
    }

    /** The open bar's tint: lighter over the glass, milkier without it. */
    private fun frostTint() = if (glass?.bitmap != null) colors.frost else colors.frostBare
    var isOpen = false; private set

    /** Morph progress: 0 = [origin], 1 = full-width bar on the keyboard. */
    private var f = 0f
    private var anim: ValueAnimator? = null
    /** Keyboard height the bar heads for (remembered, then exact); eased when it changes. */
    private var kb = 0f
    private var kbTarget = 0f
    private var kbAnim: ValueAnimator? = null
    /** Lift-Off is driving it ([setLift]); [lift] is where it is (0 = the Home pill, 1 = open). */
    var isLifting = false; private set
    private var lift = 0f
    /** Results rise in (1) or drop in / rise away (−1). */
    private var liftSign = 1f
    /** The keyboard, held so it follows the finger; null until the IME hands it over (or never: it then comes up at the end). */
    private var imeControl: WindowInsetsAnimationController? = null
    private var imeCancel: CancellationSignal? = null
    private var imeTries = 0
    /**
     * The bar's landing bounce ([bounceBar]): the overshoot asked for (px), until the bar's own spring takes over
     * from the lift; then [bounceAnim] runs that spring (from [bounceD0] px below its rest at [bounceV0] px/s, at
     * [bounceT0] ms).
     */
    private var bounceWant = 0f
    private var bounceD0 = 0f
    private var bounceV0 = 0f
    private var bounceT0 = 0L
    private var bounceAnim: ValueAnimator? = null
    /** The bar's way in without the bounce: its top (px) at the first layout of the last frame ([baseAt], ms), and its speed (px/s, down). */
    private var baseTop = Float.NaN
    private var baseAt = 0L
    private var baseV = Float.NaN
    /** A keyboard handed over late eases onto the finger's height instead of jumping there. */
    private var imeBlend = 1f
    private var imeBlendAnim: ValueAnimator? = null
    /** No blur on low-RAM (Go) phones. */
    private val liftBlurs = !context.getSystemService(android.app.ActivityManager::class.java).isLowRamDevice
    /** Keyboard inset on screen right now (every frame while it animates). */
    private var ime = 0
    private var imeAnimating = false

    private val openGlyphLeft = dp.dp(16f)
    private val openGlyphBox = dp.dp(24f)
    private val openTextStart = dp.dp(16f + 24f + 6f)
    private val openTextSize = 15f * resources.displayMetrics.scaledDensity     // search_bar_text_size
    private val argb = ArgbEvaluator()
    private val keyboardPrefs = context.getSharedPreferences("keyboard", Context.MODE_PRIVATE)
    private val keyboardKey = "ime_height_${dp.widthPx}x${dp.heightPx}"

    private val main = Handler(Looper.getMainLooper())
    private val net = Executors.newSingleThreadExecutor { r -> Thread(r, "web-suggest") }
    private var pendingFetch: Runnable? = null

    private val imeCallback = object : WindowInsetsAnimation.Callback(DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
        private fun WindowInsetsAnimation.isIme() = (typeMask and WindowInsets.Type.ime()) != 0

        override fun onPrepare(animation: WindowInsetsAnimation) {
            if (animation.isIme()) imeAnimating = true
        }

        override fun onStart(animation: WindowInsetsAnimation, bounds: WindowInsetsAnimation.Bounds): WindowInsetsAnimation.Bounds {
            // a held keyboard (Lift-Off) goes where the finger puts it, not to an end state
            if (animation.isIme() && isOpen && !isLifting) {
                // the hierarchy already holds the end state: the keyboard's exact final height
                val end = rootWindowInsets?.getInsets(WindowInsets.Type.ime())?.bottom ?: bounds.upperBound.bottom
                // showing: ease onto the exact height on the keyboard's own timing; hiding: ride it down
                if (end > 0) {
                    keyboardComing = true
                    retargetKeyboard(end.toFloat(), animation.durationMillis.takeIf { it > 0 } ?: Easing.IME_DURATION)
                } else retargetKeyboard(0f, 0L)
            }
            return bounds
        }

        override fun onProgress(insets: WindowInsets, running: MutableList<WindowInsetsAnimation>): WindowInsets {
            if (running.any { it.isIme() }) {
                ime = insets.getInsets(WindowInsets.Type.ime()).bottom
                layoutPill()
            }
            return insets
        }

        override fun onEnd(animation: WindowInsetsAnimation) {
            if (!animation.isIme()) return
            imeAnimating = false
            ime = rootWindowInsets?.getInsets(WindowInsets.Type.ime())?.bottom ?: 0
            layoutPill()
        }
    }

    init {
        isClickable = true
        pill.background = GlassPill()
        field.apply {
            background = null
            hint = "Search"
            setHintTextColor(colors.hint)
            setTextColor(colors.text)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, openTextSize)
            typeface = Fonts.ui(context, 400)
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_GO or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            setPadding(openTextStart.roundToInt(), 0, dp.dpi(16f), 0)
            gravity = Gravity.CENTER_VERTICAL
            pivotX = 0f
        }
        // always the open bar's width: sized to the shrinking pill, the hint would be cut ("Sear") near the end of
        // the morph; the pill clips it instead, and the scaled hint fits inside it at every step
        pill.addView(field, LayoutParams(dp.widthPx - 2 * dp.dpi(16f), LayoutParams.MATCH_PARENT))
        pill.addView(glyph, LayoutParams(dp.dpi(24f), dp.dpi(24f), Gravity.START or Gravity.CENTER_VERTICAL).apply {
            leftMargin = dp.dpi(16f)
        })
        glyph.pivotX = 0f
        glyph.pivotY = dp.dpi(24f) / 2f
        // bottom-up: apps resting on the bar, Google above them, contacts on top; fitted, never scrolled
        content.orientation = LinearLayout.VERTICAL
        content.gravity = Gravity.BOTTOM
        content.clipChildren = false
        for (box in listOf(contactsBox, web)) {
            box.orientation = LinearLayout.VERTICAL
            box.clipChildren = false
            content.addView(box, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                leftMargin = dp.dpi(14f); rightMargin = dp.dpi(14f); bottomMargin = dp.dpi(SECTION_GAP_DP)
            })
            box.visibility = GONE
        }
        results.setPadding(dp.appsSidePadding, 0, dp.appsSidePadding, 0)
        content.addView(results, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0))
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply { topMargin = dp.insets.top + dp.dpi(24f) })
        addView(pill, LayoutParams(dp.searchBarWidth, dp.searchBarHeight))

        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = filter(s?.toString().orEmpty())
        })
        field.setOnEditorActionListener { _, action, ev ->
            if (action == EditorInfo.IME_ACTION_GO || ev?.keyCode == KeyEvent.KEYCODE_ENTER) {
                val target = enterTarget
                if (target != null) target.performClick() else field.text.toString().trim().takeIf { it.isNotEmpty() }?.let { webSearch(it) }
                true
            } else false
        }
        setOnApplyWindowInsetsListener { _, insets ->
            // while the keyboard animates these are already its end state; the callback below drives the bar
            if (!imeAnimating) {
                ime = insets.getInsets(WindowInsets.Type.ime()).bottom
                if (isOpen && ime > 0) retargetKeyboard(ime.toFloat())
                layoutPill()
            }
            insets
        }
        setWindowInsetsAnimationCallback(imeCallback)
        setOnClickListener { if (isOpen) onDismiss() }
    }

    private fun normalize(s: String) = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(MARKS, "")

    /** Labels normalised once per search session (was: every label, twice, per keystroke). */
    private val index: List<Triple<AppInfo, String, String>> by lazy {
        apps().map { a -> val l = normalize(a.label); Triple(a, l, initials(l)) }
    }

    private fun filter(q: String) {
        if (!isOpen) return                       // closing: the query is cleared into the hint, results stay put
        val query = normalize(q.trim())
        if (query.isEmpty()) {
            pendingFetch?.let { main.removeCallbacks(it) }
            pendingSources?.let { main.removeCallbacks(it) }
            appsList = mostUsed()
            contactsList = suggested
            webList = emptyList()
            render()
            return
        }
        appsList = matches(query)
        // until the new answers land: the typed query, and whatever of the last ones still fits it
        val raw = q.trim()
        webList = listOf(raw) + webList.drop(1).filter { it.startsWith(raw, ignoreCase = true) && !it.equals(raw, ignoreCase = true) }
        contactsList = contactsList.filter { c -> normalize(c.name).split(' ', '-', '.').any { it.startsWith(query) } }.take(SearchSources.MAX_MATCHES)
        render()
        fetchSuggestions(raw)
        querySources(raw)
    }

    /** The [MAX_APPS] most used apps that aren't on Home page 1 (topped up from the rest when there's little history). */
    private fun mostUsed(): List<AppInfo> {
        val skip = firstPage()
        return index.map { it.first }.filter { it.key !in skip }
            .sortedByDescending { scores[it.key] ?: 0.0 }.take(MAX_APPS)
    }

    /** Name starts with the query, then a word in it does ("e" → Uber Eats) or its initials, then anywhere; more used first. */
    private fun matches(query: String): List<AppInfo> = index.mapNotNull { (a, l, ini) ->
        val tier = when {
            l.startsWith(query) -> 0
            l.split(' ', '-', '.').any { it.startsWith(query) } || ini.startsWith(query) -> 1
            l.contains(query) -> 2
            else -> return@mapNotNull null
        }
        Triple(a, tier, scores[a.key] ?: 0.0)
    }.sortedWith(compareBy<Triple<AppInfo, Int, Double>> { it.second }.thenByDescending { it.third }).take(MAX_APPS).map { it.first }

    // ---------------------------------------------------------------------------------------------
    // fitting it all above the bar
    // ---------------------------------------------------------------------------------------------

    /** Height the results have: from under the status bar to just above the search bar. */
    private fun space(): Int {
        val lp = content.layoutParams as LayoutParams
        return dp.heightPx - lp.topMargin - lp.bottomMargin
    }

    /**
     * Lays out what there is so it fits without scrolling: apps first, then (typing) as many Google rows as fit,
     * at least one, with contact rows given up for it if need be; (nothing typed) three rows of suggested contacts
     * stretched over what's left.
     */
    private fun render() {
        showApps(appsList)
        val left = space() - results.layoutParams.height
        val gap = dp.dpi(SECTION_GAP_DP)
        val chipGap = dp.dpi(CHIP_GAP_DP)
        val invite = !sources.contactsAllowed && !searchPrefs.getBoolean(KEY_CONTACTS_DECLINED, false)
        if (field.text.isNullOrBlank()) {
            val chip = ((left - gap - (SUGGESTED_ROWS - 1) * chipGap) / SUGGESTED_ROWS).coerceIn(dp.dpi(CHIP_DP), dp.dpi(CHIP_MAX_DP))
            val rows = ((left - gap + chipGap) / (chip + chipGap)).coerceIn(0, SUGGESTED_ROWS)
            showContacts(if (invite) emptyList() else contactsList.take(rows * 2), invite && rows > 0, chip)
            showWeb(emptyList())
        } else {
            val chip = dp.dpi(CHIP_DP)
            fun height(rows: Int) = if (rows == 0) 0 else rows * chip + (rows - 1) * chipGap + gap
            fun webFits(h: Int) = ((h - gap - 2 * dp.dpi(CARD_PAD_DP)) / dp.dpi(ROW_DP)).coerceIn(0, MAX_WEB)
            var rows = if (invite) 1 else (contactsList.size + 1) / 2
            while (rows > 0 && webFits(left - height(rows)) < 1) rows--
            showContacts(if (invite) emptyList() else contactsList.take(rows * 2), invite && rows > 0, chip)
            showWeb(webList.take(webFits(left - height(rows)).coerceAtLeast(1)))
        }
        updateEnterTarget()
    }

    /** Typing: [v] is new, so it fades in (opening, the whole results area rises in instead). */
    private fun reveal(v: View) {
        if (opening || isLifting) return
        v.animate().cancel()
        v.alpha = 0f
        v.animate().alpha(1f).setDuration(FADE_MS).setInterpolator(Easing.BASIC).start()
    }

    private fun showApps(list: List<AppInfo>) {
        val keys = list.map { it.key }
        if (keys == shownApps && results.childCount == list.size) return
        val old = shownApps
        shownApps = keys
        results.removeAllViews()
        if (enterTarget is IconView) enterTarget = null      // went with the old grid
        results.rows = ((list.size + 3) / 4).coerceAtLeast(1)
        list.forEachIndexed { i, a ->
            val v = IconView(context, dp).apply {
                label = a.label; contentDescription = a.label
                setOnClickListener { onLaunch(this, a) }
                configureIcon(this, a)
            }
            results.addCell(v, i % 4, i / 4)
            if (old.getOrNull(i) != a.key) reveal(v)
        }
        results.layoutParams = results.layoutParams.apply { height = if (list.isEmpty()) 0 else results.rows * dp.cellHeight }
    }

    private fun initials(s: String) = s.split(' ', '-', '.').filter { it.isNotEmpty() }.joinToString("") { it.take(1) }

    // ---------------------------------------------------------------------------------------------
    // Google results
    // ---------------------------------------------------------------------------------------------

    private fun fetchSuggestions(q: String) {
        pendingFetch?.let { main.removeCallbacks(it) }
        if (q.isEmpty() || !com.oneuiclone.home.settings.Prefs(context).webSuggestions) return
        val r = Runnable {
            net.execute {
                val list = try { suggest(q) } catch (_: Exception) { emptyList() }
                main.post { if (isOpen && field.text.toString().trim() == q && list.isNotEmpty()) setWeb(q, list) }
            }
        }
        pendingFetch = r
        main.postDelayed(r, 150)
    }

    /** Google's public autocomplete endpoint: `["query", ["suggestion", …]]`. */
    private fun suggest(q: String): List<String> {
        val lang = Locale.getDefault().toLanguageTag()
        val url = URL("https://suggestqueries.google.com/complete/search?client=firefox&ie=UTF-8&oe=UTF-8&hl=$lang&q=" +
            URLEncoder.encode(q, "UTF-8"))
        val c = url.openConnection() as HttpURLConnection
        c.connectTimeout = 2500; c.readTimeout = 2500
        return try {
            val body = c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val arr = org.json.JSONArray(body).getJSONArray(1)
            (0 until arr.length()).map { arr.getString(it) }
        } finally {
            c.disconnect()
        }
    }

    /** The typed query, then Google's suggestions; [render] decides how many show. */
    private fun setWeb(q: String, suggestions: List<String>) {
        webList = listOf(q) + suggestions.filter { !it.equals(q, ignoreCase = true) }.take(MAX_WEB - 1)
        render()
    }

    private fun showWeb(list: List<String>) {
        if (list == shownWeb) return
        val old = shownWeb
        shownWeb = list
        web.removeAllViews()
        web.visibility = if (list.isEmpty()) GONE else VISIBLE
        if (list.isEmpty()) return
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { cornerRadius = dp.dp(26f); setColor(0x1FFFFFFF) }
            setPadding(0, dp.dpi(CARD_PAD_DP), 0, dp.dpi(CARD_PAD_DP))
            clipToOutline = true
        }
        list.forEachIndexed { i, s ->
            val r = row(MagnifierView(context, 0xCCFAFAFA.toInt()), 20f, s, null, true) { webSearch(s) }
            card.addView(r, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp.dpi(ROW_DP)))
            if (old.getOrNull(i) != s) reveal(r)
        }
        web.addView(card)
        if (old.isEmpty()) reveal(card)
    }

    // ---------------------------------------------------------------------------------------------
    // contacts
    // ---------------------------------------------------------------------------------------------

    /** Contacts come from a worker; the previous answers stay up until the new ones land. */
    private fun querySources(raw: String) {
        pendingSources?.let { main.removeCallbacks(it) }
        val r = Runnable {
            sources.query(raw) { ct ->
                if (!isOpen || field.text.toString().trim() != raw) return@query
                contactsList = ct
                render()
            }
        }
        pendingSources = r
        main.postDelayed(r, 60)
    }

    /** Two chips a row, [chip] px tall; [invite]: one wide chip asking for contacts access instead. */
    private fun showContacts(list: List<SearchSources.Contact>, invite: Boolean, chip: Int) {
        val keys = if (invite) listOf(INVITE_ID) else list.map { it.id }
        if (keys == shownContacts) {
            if (chip != shownChipHeight) { shownChipHeight = chip; forEachChip { sizeChip(it, chip) } }
            return
        }
        val old = shownContacts
        shownContacts = keys
        shownChipHeight = chip
        val chips = if (invite) {
            listOf(chip(ContactAvatar(context, null, "", 0xFF3E91FF.toInt()), "Allow access to show contacts", false) { onRequestContacts() })
        } else list.map { c ->
            chip(ContactAvatar(context, c.photo, c.name, avatarColor(c.name)), c.name, true) {
                sources.contactOpened(c)
                onOpenIntent(it, Intent(Intent.ACTION_VIEW, c.uri))     // the contact's own page
            }
        }
        contactsBox.removeAllViews()
        contactsBox.visibility = if (chips.isEmpty()) GONE else VISIBLE
        val lines = chips.chunked(if (invite) 1 else 2)
        lines.forEachIndexed { i, pair ->
            val line = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false }
            pair.forEachIndexed { j, c ->
                line.addView(c, LinearLayout.LayoutParams(0, chip, 1f).apply { if (j > 0) leftMargin = dp.dpi(CHIP_GAP_DP) })
                sizeChip(c, chip)
                if (old.getOrNull(i * 2 + j) != keys[i * 2 + j]) reveal(c)
            }
            if (pair.size == 1 && !invite) line.addView(View(context), LinearLayout.LayoutParams(0, 0, 1f).apply { leftMargin = dp.dpi(CHIP_GAP_DP) })
            contactsBox.addView(line, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                if (i > 0) topMargin = dp.dpi(CHIP_GAP_DP)
            })
        }
    }

    private fun forEachChip(block: (View) -> Unit) {
        for (i in 0 until contactsBox.childCount) {
            val line = contactsBox.getChildAt(i) as? ViewGroup ?: continue
            for (j in 0 until line.childCount) line.getChildAt(j).takeIf { it.tag == CHIP }?.let(block)
        }
    }

    /** Height, corners and avatar for a chip [h] px tall (suggestions stretch to fill the space). */
    private fun sizeChip(c: View, h: Int) {
        (c.layoutParams as? LinearLayout.LayoutParams)?.let { if (it.height != h) { it.height = h; c.layoutParams = it } }
        if (c !== enterTarget) c.background = chipBackground(h)
        val avatar = (c as ViewGroup).getChildAt(0)
        val size = (h - dp.dpi(12f)).coerceIn(dp.dpi(32f), dp.dpi(40f))
        avatar.layoutParams = avatar.layoutParams.apply { width = size; height = size }
        c.setPadding(((h - size) / 2).coerceAtLeast(dp.dpi(6f)), 0, dp.dpi(14f), 0)
    }

    private fun chip(avatar: View, name: String, enterable: Boolean, onClick: (View) -> Unit): View =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            tag = CHIP
            setTag(R_ENTERABLE, enterable)
            setOnClickListener { onClick(this) }
            addView(avatar, LinearLayout.LayoutParams(dp.dpi(32f), dp.dpi(32f)))
            addView(TextView(context).apply {
                text = name
                setTextColor(0xFFFAFAFA.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                typeface = Fonts.ui(context, 400)
                isSingleLine = true
                ellipsize = android.text.TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp.dpi(10f) })
        }

    private fun chipBackground(h: Int): android.graphics.drawable.Drawable {
        val glass = GradientDrawable().apply { cornerRadius = h / 2f; setColor(0x1FFFFFFF) }
        return android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x33FFFFFF), glass, glass)
    }

    /** After the READ_CONTACTS prompt: search again with the answer, and bring the keyboard back. */
    fun onContactsPermission(granted: Boolean) {
        if (!isOpen) return
        if (!granted) searchPrefs.edit().putBoolean(KEY_CONTACTS_DECLINED, true).apply()
        val raw = field.text.toString().trim()
        render()                                       // the invitation goes either way
        // asked for from this search: its suggestions may come in now
        if (granted) sources.refresh(force = true) {
            suggested = sources.snapshot.contacts
            if (isOpen && field.text.isNullOrBlank()) { contactsList = suggested; render() }
        }
        if (raw.isNotEmpty()) querySources(raw)
        field.requestFocus()
        context.getSystemService(InputMethodManager::class.java)?.showSoftInput(field, 0)
    }

    private fun avatarColor(name: String) = AVATAR_COLORS[(name.hashCode() and 0x7FFFFFFF) % AVATAR_COLORS.size]

    /** [enterable]: IME "go" may open it. */
    private fun row(leading: View, leadingDp: Float, title: String, subtitle: String?, enterable: Boolean, onClick: (View) -> Unit): View =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp.dpi(18f), 0, dp.dpi(18f), 0)
            isClickable = true
            tag = if (leadingDp > 24f) ROW_TALL else null
            setTag(R_ENTERABLE, enterable)
            background = rowRipple()
            setOnClickListener { onClick(this) }
            addView(leading, LinearLayout.LayoutParams(dp.dpi(leadingDp), dp.dpi(leadingDp)))
            val texts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(TextView(context).apply {
                text = title
                setTextColor(0xFFFAFAFA.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                typeface = Fonts.ui(context, 400)
                isSingleLine = true
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            if (subtitle != null) texts.addView(TextView(context).apply {
                text = subtitle
                setTextColor(0x99FAFAFA.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                typeface = Fonts.ui(context, 400)
                isSingleLine = true
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = dp.dpi(if (leadingDp > 24f) 14f else 16f)
            })
        }

    private fun rowRipple() = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x33FFFFFF), null, null)

    // ---------------------------------------------------------------------------------------------
    // enter target (Pixel launcher's highlighted first result)
    // ---------------------------------------------------------------------------------------------

    private fun firstEnterable(box: ViewGroup): View? {
        if (box.visibility != VISIBLE) return null
        for (i in 0 until box.childCount) {
            val c = box.getChildAt(i)
            if (c.getTag(R_ENTERABLE) == true) return c
            if (c is ViewGroup) firstEnterable(c)?.let { return it }
        }
        return null
    }

    /** Nothing is highlighted before a query is typed: "go" has nothing to open then. */
    private fun updateEnterTarget() {
        val target = if (field.text.isNullOrBlank()) null
            else (results.getChildAt(0) as? IconView) ?: firstEnterable(contactsBox) ?: firstEnterable(web)
        if (target === enterTarget) return
        enterTarget?.let { it.background = when { it is IconView -> null; it.tag == CHIP -> chipBackground(shownChipHeight); else -> rowRipple() } }
        enterTarget = target
        if (target == null) return
        val mark = GradientDrawable().apply {
            setColor(0x33FFFFFF)
            cornerRadius = if (target is IconView) dp.dp(22f) else if (target.tag == CHIP) shownChipHeight / 2f else dp.dp(18f)
        }
        val inset = dp.dpi(if (target is IconView) 2f else 6f)
        val bg = android.graphics.drawable.InsetDrawable(mark, inset, if (target is IconView) inset else dp.dpi(2f), inset, if (target is IconView) inset else dp.dpi(2f))
        target.background = when {
            target is IconView -> bg
            target.tag == CHIP -> android.graphics.drawable.LayerDrawable(arrayOf(chipBackground(shownChipHeight), mark))
            else -> android.graphics.drawable.LayerDrawable(arrayOf(bg, rowRipple()))
        }
        android.animation.ObjectAnimator.ofInt(mark, "alpha", 0, 255).setDuration(150).start()
    }

    // ---------------------------------------------------------------------------------------------
    // swipe down → Home
    // ---------------------------------------------------------------------------------------------

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var swipedHome = false

    /** A swipe down anywhere goes back Home, a swipe up on to the Apps screen (the results never scroll). */
    private fun checkSwipeHome(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = ev.rawX; downY = ev.rawY; swipedHome = false }
            // UP too: a fast or late-delivered swipe can lift without any MOVE in between, and must not tap a row
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> if (!swipedHome && isOpen && canSwipeHome()) {
                val dy = ev.rawY - downY
                if (abs(dy) > slop && abs(dy) > abs(ev.rawX - downX) * 1.2f) {
                    swipedHome = true
                    if (dy > 0) onSwipeHome() else onSwipeUp()
                }
            }
        }
        return swipedHome
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = checkSwipeHome(ev) || super.onInterceptTouchEvent(ev)

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        val before = swipedHome
        if (checkSwipeHome(ev)) {
            // the touch began on empty space: it's a swipe now, not a tap-outside
            if (!before) MotionEvent.obtain(ev).also { it.action = MotionEvent.ACTION_CANCEL; super.onTouchEvent(it); it.recycle() }
            return true
        }
        return super.onTouchEvent(ev)
    }

    /** Hands the query to the Google app (or the browser when it isn't installed). */
    private fun webSearch(q: String) {
        val search = Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, q).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(search)
        } catch (_: Exception) {
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(q)))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) {}
        }
        onDismiss()
    }

    // ---------------------------------------------------------------------------------------------
    // morph + keyboard
    // ---------------------------------------------------------------------------------------------

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    /** Top of a bar of height [h] resting 12dp above a keyboard [keyboard] px tall (or the navigation bar). */
    private fun barTop(keyboard: Float, h: Float) = dp.heightPx - maxOf(keyboard, dp.insets.bottom.toFloat()) - dp.dp(12f) - h

    private fun layoutPill() {
        if (!::origin.isInitialized) return
        val o = origin
        val r = o.rect
        // Lift-Off: it reads as the bar early on (size and colour lead), while its height follows the finger
        val g = if (o.fade) 1f else if (isLifting) 1f - (1f - minOf(1f, f * 1.12f)).pow(2.2f) else f
        val h = lerp(r.height(), dp.searchBarHeight.toFloat(), g)
        val margin = dp.dp(16f)
        val w = lerp(r.width(), dp.widthPx - 2 * margin, g)
        val own = lerp(r.top, barTop(kb, dp.searchBarHeight.toFloat()), if (o.fade) 1f else f)
        // whichever is higher: the bar's own motion, or the keyboard pushing it up (never behind the keyboard)
        val top = if (ime > 0) minOf(own, barTop(ime.toFloat(), h)) else own
        val lp = pill.layoutParams
        if (lp.width != w.roundToInt() || lp.height != h.roundToInt()) {
            lp.width = w.roundToInt(); lp.height = h.roundToInt()
            pill.layoutParams = lp
        }
        pill.x = lerp(r.left, margin, g)
        // a fading bar comes and goes with the results: in Lift-Off it rises (or drops), grows, sharpens and fades in
        // exactly as they do; otherwise it fades with the morph's progress
        val q = if (o.fade && isLifting) liftFocus(f) else 1f
        pill.y = landed(top) + dp.dp(LIFT_RISE_DP) * (1f - q) * liftSign
        pill.alpha = if (!o.fade) 1f else if (isLifting) liftFade(f) else f
        val sc = 0.92f + 0.08f * q
        pill.scaleX = sc; pill.scaleY = sc
        val blur = if (liftBlurs) dp.dp(LIFT_BLUR_DP) * (1f - q) else 0f
        // DECAL: past the pill's edge the blur fades to nothing (CLAMP smeared its edges out into a rectangle around it)
        if (blur >= 0.5f) { pill.setRenderEffect(RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.DECAL)); pillBlurred = true }
        else if (pillBlurred) { pill.setRenderEffect(null); pillBlurred = false }
        pillBg.cornerRadius = h / 2f
        // frosted glass: a pill with its own glass (Home's) keeps it all the way; one without (the Apps screen's) has
        // the glass come up under its fill as the fill thins, so it's solid where it lands and frosted when open
        pillBg.setColor(argb.evaluate(g, o.fill, frostTint()) as Int)
        pillBg.setStroke(dp.dpi(1f), argb.evaluate(g, o.stroke, 0x14FFFFFF) as Int)
        pillGlass = o.glass ?: glass
        glassAlpha = if (o.glass != null) 1f else g
        frostDim = g
        sheen = g
        val ink = argb.evaluate(g, o.ink ?: colors.hint, frostHint) as Int
        glyph.color = ink
        field.setHintTextColor(ink)
        // the glass is fixed to the window: wherever the pill moves (keyboard, bounce, lift), it's redrawn there
        pillNow.reset(); pill.transformMatrixToGlobal(pillNow)
        if (pillNow != pillAt) pill.invalidate()
        // search_bar_elevation; a fading bar's shadow (drawn sharp, never blurred) comes in only as the bar sharpens
        pill.elevation = lerp(o.elevation, dp.dp(8f), g) * q
        val gs = lerp(o.glyphBox, openGlyphBox, g) / openGlyphBox
        glyph.scaleX = gs; glyph.scaleY = gs
        glyph.translationX = lerp(o.glyphLeft, openGlyphLeft, g) - openGlyphLeft
        val ts = lerp(o.textSizePx, openTextSize, g) / openTextSize
        field.scaleX = ts; field.scaleY = ts
        field.pivotY = h / 2f
        field.translationX = lerp(o.textStart, openTextStart, g) - openTextStart * ts
    }

    /** Results end just above where the bar settles. */
    private fun updateContentInset() {
        val lp = content.layoutParams as LayoutParams
        val m = (dp.heightPx - barTop(kbTarget, dp.searchBarHeight.toFloat()) + dp.dp(8f)).roundToInt()
        if (lp.bottomMargin != m) { lp.bottomMargin = m; content.layoutParams = lp }
    }

    private fun rememberedKeyboard(): Float =
        keyboardPrefs.getInt(keyboardKey, 0).takeIf { it > 0 }?.toFloat() ?: (dp.heightPx * 0.38f)   // first time: a typical phone keyboard

    /** The bar heads for a keyboard [h] px tall, eased over [duration] ms (0 = at once); shown heights are remembered. */
    private fun retargetKeyboard(h: Float, duration: Long = Easing.IME_DURATION) {
        if (h > 0f && keyboardPrefs.getInt(keyboardKey, 0) != h.roundToInt()) keyboardPrefs.edit().putInt(keyboardKey, h.roundToInt()).apply()
        if (h == kbTarget) return
        kbTarget = h
        kbAnim?.cancel()
        updateContentInset()
        if (isOpen) pickFrostInk(animate = true)          // it rests somewhere else now: the frost there may differ
        if (isOpen) render()                           // more or less room: refit
        if (duration <= 0L) { kb = h; layoutPill(); return }
        kbAnim = ValueAnimator.ofFloat(kb, h).apply {
            this.duration = duration
            interpolator = Easing.IME
            addUpdateListener { kb = it.animatedValue as Float; layoutPill() }
            start()
        }
    }

    /** No keyboard came up (hardware keyboard, IME refused): settle at the bottom rather than float. */
    private val noKeyboard = Runnable { if (isOpen && ime == 0 && !imeAnimating) retargetKeyboard(0f) }

    /** [fromAbove]: a swipe down opened it, so the results drop into place instead of rising (same fade, length and curve). */
    fun open(from: Origin, fromAbove: Boolean = false) {
        sources.snapshot.let { scores = it.scores; suggested = it.contacts }
        origin = from
        isOpen = true
        visibility = VISIBLE
        kb = rememberedKeyboard(); kbTarget = kb
        pickFrostInk()
        f = 0f
        layoutPill()
        updateContentInset()
        opening = true
        filter("")
        opening = false
        // everything at once: a fade and a slide (up, or down after a swipe down), with the bar's morph
        content.alpha = 0f
        content.translationY = dp.dp(OPEN_RISE_DP) * if (fromAbove) -1f else 1f
        content.animate().alpha(1f).translationY(0f).setDuration(OPEN_MS).setInterpolator(Easing.IME)
            .withEndAction { showCursor() }.start()
        animateTo(1f) { showCursor() }
        field.isCursorVisible = false
        field.requestFocus()
        keyboardComing = false
        askForKeyboard()
        main.postDelayed(noKeyboard, 2500)
    }

    /**
     * Keeps asking until the keyboard is on its way. Just back from an app that had it up, the IME is still
     * handing over and turns the field down (not its target yet) for a few hundred ms, then attaches without
     * showing: one retry on the next frame was too soon.
     */
    private fun askForKeyboard(until: Long = SystemClock.uptimeMillis() + KEYBOARD_RETRY_MS) {
        main.removeCallbacks(keyboardRetry)
        keyboardDeadline = until
        keyboardRetry.run()
    }

    private var keyboardDeadline = 0L
    /** Our keyboard has started coming up this session: stop asking (and don't undo the user hiding it). */
    private var keyboardComing = false
    private val keyboardRetry: Runnable = object : Runnable {
        override fun run() {
            // not the insets: mid hand-over they can still say "visible" for the app's keyboard going down
            if (!isOpen || keyboardComing) return
            context.getSystemService(InputMethodManager::class.java)?.showSoftInput(field, 0)
            if (SystemClock.uptimeMillis() < keyboardDeadline) main.postDelayed(this, 80)
        }
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        // the window only now became the IME's target: ask again
        if (hasWindowFocus && isOpen && !isLifting) askForKeyboard()
    }

    /**
     * Morphs back into [to] (by default where it came from) while the keyboard goes down. Results stay in
     * place: one of them may be mid-drag to the Home screen. [lift]: a swipe up closed it, so they rise away as
     * they fade (the drop-in after a swipe down, reversed).
     */
    fun close(to: Origin = origin, lift: Boolean = false) {
        if (!isOpen) return
        if (isLifting) dropLift()
        origin = to
        shutDown()
        content.animate().alpha(0f).translationY(if (lift) -dp.dp(OPEN_RISE_DP) else content.translationY).setDuration(150).start()
        dissolveQuery()
        animateTo(0f) { onClose() }
    }

    /** No longer open: pending work stops and the keyboard goes down. */
    private fun shutDown() {
        isOpen = false
        bounceWant = 0f; bounceAnim?.cancel()
        main.removeCallbacks(noKeyboard)
        main.removeCallbacks(keyboardRetry)
        pendingFetch?.let { main.removeCallbacks(it) }
        pendingSources?.let { main.removeCallbacks(it) }
        net.shutdownNow()
        context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(field.windowToken, 0)
        field.isCursorVisible = false
    }

    /** The query dissolves into the hint, so the pill lands showing "Search" like the one it becomes. */
    private fun dissolveQuery() {
        if (field.length() > 0) field.animate().alpha(0f).setDuration(110).withEndAction {
            field.text = null
            field.animate().alpha(1f).setDuration(150).start()
        }.start()
    }

    // ---------------------------------------------------------------------------------------------
    // Lift-Off
    // ---------------------------------------------------------------------------------------------

    /** How far the bar rises in Lift-Off: from its pill to where it rests on the keyboard. */
    val liftTravel get() = if (::origin.isInitialized) origin.rect.top - barTop(kb, dp.searchBarHeight.toFloat()) else 0f

    /**
     * Opens from Home's pill like [open], but nothing moves on its own: [setLift] places the bar, the results and the
     * keyboard until [endLift]. [fromAbove]: a swipe down, so the results drop in instead of rising.
     */
    fun beginLift(from: Origin, fromAbove: Boolean = false) {
        sources.snapshot.let { scores = it.scores; suggested = it.contacts }
        anim?.cancel()
        origin = from
        isOpen = true
        isLifting = true
        liftSign = if (fromAbove) -1f else 1f
        visibility = VISIBLE
        kb = rememberedKeyboard(); kbTarget = kb
        pickFrostInk()
        updateContentInset()
        opening = true
        filter("")
        opening = false
        content.animate().cancel()
        setLift(0f)
        field.isCursorVisible = false
        field.requestFocus()
        keyboardComing = false
        imeTries = 0
        // the IME only takes the field a frame or two after focus
        main.post { requestImeControl() }
    }

    /** Lift-Off back to Home: [setLift] runs down to 0, then [endLift]. [away]: a swipe up closed it, so the results rise away. */
    fun beginLiftClose(to: Origin, away: Boolean = false) {
        if (!isOpen) return
        anim?.cancel()
        origin = to
        shutDown()
        isLifting = true
        lift = f
        if (away) liftSign = -1f
        content.animate().cancel()
        dissolveQuery()
    }

    /** Lift-Off at [p] (0 = Home's pill, 1 = open; a little over 1 while the finger pulls past the end). */
    fun setLift(p: Float) {
        if (!isLifting) return
        lift = p
        f = p.coerceIn(0f, 1f)
        layoutPill()
        // the results grow out of the bar from 92%, rise 38 dp (or drop) and come into focus over the last 90%
        val q = liftFocus(p)
        content.pivotX = dp.widthPx / 2f
        content.pivotY = barTop(kbTarget, dp.searchBarHeight.toFloat()) + dp.searchBarHeight / 2f - (content.layoutParams as LayoutParams).topMargin
        val sc = 0.92f + 0.08f * q
        content.scaleX = sc; content.scaleY = sc
        content.translationY = dp.dp(LIFT_RISE_DP) * (1f - q) * liftSign
        content.alpha = liftFade(p)
        val r = if (liftBlurs) dp.dp(LIFT_BLUR_DP) * (1f - q) else 0f
        content.setRenderEffect(if (r < 0.5f) null else RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP))
        steerKeyboard(p)
    }

    /** How far into focus the results are at lift [p]: from 10 % on, eased. */
    private fun liftFocus(p: Float) = Easing.SINE_IN_OUT_80.getInterpolation(((p - 0.1f) / 0.9f).coerceIn(0f, 1f))

    /** The results' opacity at lift [p] (a fading bar's too): fully in by 80 % focus. */
    private fun liftFade(p: Float) = (liftFocus(p) / 0.8f).coerceAtMost(1f)

    /** Lift-Off settled: [open] leaves search up (keyboard and all), otherwise it is gone and [onClose] runs. */
    fun endLift(open: Boolean) {
        if (!isLifting) return
        if (open && isOpen) {
            isLifting = false
            clearLiftLook()
            f = 1f; layoutPill()
            bounceWant = 0f                                          // landed without the bar taking over: no late bounce
            imeBlendAnim?.cancel()
            val c = imeControl
            if (c != null) c.finish(true)
            else { imeCancel?.cancel(); askForKeyboard() }          // never got hold of it: it comes up on its own
            main.postDelayed(noKeyboard, 2500)
            showCursor()
            return
        }
        dropLift()
        f = 0f; layoutPill()
        if (isOpen) shutDown()
        onClose()
    }

    /**
     * The bar will overshoot its resting place by about [amount] px as it lands (0: no bounce). It isn't added
     * after the landing (that read as a second push once everything had stopped): while the lift carries it in,
     * the bar hands itself over to its own looser spring, keeping its speed, at the moment that spring would
     * overshoot by [amount]. So it sails past the keyboard's stop and settles back as everything else arrives.
     */
    fun bounceBar(amount: Float) {
        bounceAnim?.cancel()
        baseTop = Float.NaN; baseV = Float.NaN
        // a fading bar never travelled, so it has nothing to land from
        bounceWant = if (amount < 0.5f || !isOpen || (::origin.isInitialized && origin.fade)) 0f else amount
        if (bounceWant > 0f) field.isCursorVisible = false
    }

    /** The bar at [top] (where the lift and keyboard put it), or where its own landing spring has it. */
    private fun landed(top: Float): Float {
        val rest = barTop(kb, dp.searchBarHeight.toFloat())
        val now = AnimationUtils.currentAnimationTimeMillis()
        if (bounceWant > 0f && bounceAnim == null) {
            // speed: from the first layout of one frame to the next (the lift and the keyboard each lay it out)
            if (baseTop.isNaN()) { baseTop = top; baseAt = now }
            else if (now > baseAt) {
                val v = (top - baseTop) * 1000f / (now - baseAt)
                baseV = if (baseV.isNaN()) v else baseV + (v - baseV) * 0.5f
                baseTop = top; baseAt = now
                if (overshoot(top - rest, baseV) <= bounceWant) startLanding(top - rest, baseV, now)
            }
        }
        if (bounceAnim == null) return top
        val y = rest + landing(bounceD0, bounceV0, (now - bounceT0) / 1000f)
        // never lower than the lift and keyboard put it (a taller keyboard than remembered still pushes it up),
        // except for the spring's small dip below its rest after the overshoot
        return minOf(top + maxOf(0f, y - rest), y)
    }

    private fun startLanding(d0: Float, v0: Float, now: Long) {
        bounceWant = 0f
        bounceD0 = d0; bounceV0 = v0; bounceT0 = now
        val wd = BAR_LAND_OMEGA * sqrt(1f - BAR_LAND_ZETA * BAR_LAND_ZETA)
        val reach = maxOf(abs(d0), abs((v0 + BAR_LAND_ZETA * BAR_LAND_OMEGA * d0) / wd), 0.3f)
        // still until it's within 0.3 px
        val settle = (ln(reach / 0.3f) / (BAR_LAND_ZETA * BAR_LAND_OMEGA)).coerceIn(0.2f, 0.9f)
        bounceAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2000L
            interpolator = LinearInterpolator()
            addUpdateListener {
                layoutPill()
                // the lift may still be easing out (the bar is held at it): end only once it is done
                if ((AnimationUtils.currentAnimationTimeMillis() - bounceT0) / 1000f >= settle && !isLifting) it.cancel()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (bounceAnim !== animation) return
                    bounceAnim = null; layoutPill(); showCursor()
                }
            })
            start()
        }
    }

    /** The bar's landing spring, [t] s after it took over from [d0] px below its rest moving at [v0] px/s (down): px below its rest. */
    private fun landing(d0: Float, v0: Float, t: Float): Float {
        val zw = BAR_LAND_ZETA * BAR_LAND_OMEGA
        val wd = BAR_LAND_OMEGA * sqrt(1f - BAR_LAND_ZETA * BAR_LAND_ZETA)
        return exp(-zw * t) * (d0 * cos(wd * t) + (v0 + zw * d0) / wd * sin(wd * t))
    }

    /** How far past its rest the landing spring would carry a bar [d0] px below it moving at [v0] px/s (down). */
    private fun overshoot(d0: Float, v0: Float): Float {
        var low = 0f
        var t = 0f
        while (t < 0.6f) { low = minOf(low, landing(d0, v0, t)); t += 0.004f }
        return -low
    }

    /** The typing cursor, once the bar has stopped moving (morph, results, lift and landing bounce all done). */
    private fun showCursor() {
        if (!isOpen || isLifting || anim?.isRunning == true || bounceAnim?.isRunning == true) return
        if (content.alpha < 1f) return                               // the results are still coming in
        field.isCursorVisible = true
    }

    /** Lets go of a lift part-way (a held keyboard goes back down). */
    private fun dropLift() {
        isLifting = false
        imeBlendAnim?.cancel()
        clearLiftLook()
        val c = imeControl
        if (c != null) c.finish(false) else imeCancel?.cancel()
    }

    private fun clearLiftLook() {
        content.setRenderEffect(null)
        content.scaleX = 1f; content.scaleY = 1f; content.translationY = 0f; content.alpha = 1f
    }

    /**
     * Takes hold of the keyboard so it follows the finger. The IME refuses until it serves the field (a frame or two
     * after focus), so a refusal is retried for a while; without it the keyboard comes up by itself at [endLift].
     */
    private fun requestImeControl() {
        if (!isLifting || !isOpen || imeControl != null || imeCancel != null) return
        val wic = windowInsetsController ?: return
        val cancel = CancellationSignal()
        imeCancel = cancel
        wic.controlWindowInsetsAnimation(WindowInsets.Type.ime(), -1L, LinearInterpolator(), cancel,
            object : WindowInsetsAnimationControlListener {
                override fun onReady(controller: WindowInsetsAnimationController, types: Int) {
                    if (!isLifting) { controller.finish(isOpen); return }
                    // no height to give it (not laid out yet, or a floating keyboard): let it come up by itself
                    if (controller.shownStateInsets.bottom <= 0) { controller.finish(true); return }
                    imeControl = controller
                    keyboardComing = true
                    retargetKeyboard(controller.shownStateInsets.bottom.toFloat())
                    imeBlendAnim?.cancel()
                    imeBlend = 0f
                    imeBlendAnim = ValueAnimator.ofFloat(0f, 1f).apply {
                        duration = if (lift < 0.1f) 0L else IME_BLEND_MS
                        interpolator = Easing.IME
                        addUpdateListener { imeBlend = it.animatedValue as Float; if (isLifting) steerKeyboard(lift) }
                        start()
                    }
                }

                override fun onFinished(controller: WindowInsetsAnimationController) {
                    imeControl = null; imeCancel = null
                }

                override fun onCancelled(controller: WindowInsetsAnimationController?) {
                    imeControl = null; imeCancel = null
                    if (isLifting && isOpen && ++imeTries < IME_CONTROL_TRIES) main.postDelayed({ requestImeControl() }, 50)
                }
            })
    }

    /** The held keyboard rises over 5–60% of the lift, never over the bar (which rests 12 dp above it). */
    private fun steerKeyboard(p: Float) {
        val c = imeControl ?: return
        val full = c.shownStateInsets.bottom.toFloat()
        val t = ((p - 0.05f) / 0.55f).coerceIn(0f, 1f)
        val room = dp.heightPx - (pill.y + pill.layoutParams.height) - dp.dp(12f)
        val inset = minOf(full * t * t * (3 - 2 * t) * imeBlend, room).coerceIn(0f, full)
        c.setInsetsAndAlpha(Insets.of(0, 0, 0, inset.roundToInt()), 1f, if (full > 0f) inset / full else 1f)
    }

    private fun animateTo(to: Float, end: (() -> Unit)? = null) {
        anim?.cancel()
        anim = ValueAnimator.ofFloat(f, to).apply {
            duration = (MORPH_MS * abs(to - f)).toLong().coerceAtLeast(120L)
            interpolator = Easing.IME
            addUpdateListener { f = it.animatedValue as Float; layoutPill() }
            if (end != null) addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: Animator) { cancelled = true }
                override fun onAnimationEnd(animation: Animator) { if (!cancelled) end() }
            })
            start()
        }
    }

    companion object {
        /** Bar morph (open / close); the Home ⇄ Apps transition that goes with it uses the same length. */
        const val MORPH_MS = 300L
        private val MARKS = Regex("\\p{Mn}+")
        private const val KEY_CONTACTS_DECLINED = "contacts_declined"
        private const val ROW_TALL = "tall"
        private const val CHIP = "chip"
        private const val INVITE_ID = -1L
        private const val CHIP_DP = 44f
        private const val CHIP_MAX_DP = 72f
        private const val CHIP_GAP_DP = 8f
        private const val SECTION_GAP_DP = 10f
        private const val ROW_DP = 48f
        private const val CARD_PAD_DP = 6f
        private const val MAX_APPS = 8
        private const val MAX_WEB = 5
        private const val SUGGESTED_ROWS = SearchSources.MAX_SUGGESTED / 2
        /** Results rising in with the bar (MORPH_MS); the Apps screen's grid comes in the same way after a swipe up. */
        const val OPEN_MS = 425L
        const val OPEN_RISE_DP = 48f
        /** Lift-Off: how far the results rise into place, and how blurred they start. */
        private const val LIFT_RISE_DP = 38f
        private const val LIFT_BLUR_DP = 9f
        /** Requests for the keyboard, 50 ms apart, before it is left to come up by itself. */
        private const val IME_CONTROL_TRIES = 12
        private const val IME_BLEND_MS = 180L
        /**
         * [bounceBar]'s landing spring (stiffness ~1000, ζ 0.7): firm enough that the bar takes itself over while
         * still moving fast (2–3 px/ms on a Pixel 10 Pro XL) and peaks before the keyboard stops; one overshoot, no
         * visible dip. 0.190's loose wobble (ζ 0.44) started only once the lift had all but stopped (user: the
         * bounce "happens after the animation is supposed to be over").
         */
        private const val BAR_LAND_OMEGA = 32f
        private const val BAR_LAND_ZETA = 0.7f
        private const val FADE_MS = 150L
        /** The frosted bar's ink: white (as the Home pill and the results), or dark over frost brighter than this. */
        private const val FROST_DARK_INK_ABOVE = 0.30f
        /** The Apps screen's dim (#A6000000 at 25 %) laid over the frost's glass. */
        private const val FROST_DIM = 0.16f
        private const val FROST_HINT = 0xD9FFFFFF.toInt()
        private const val FROST_TEXT = 0xFFFFFFFF.toInt()
        private const val FROST_HINT_DARK = 0xFF48484D.toInt()
        /** How long [askForKeyboard] keeps trying (the IME hand-over from an app has taken ~400 ms). */
        private const val KEYBOARD_RETRY_MS = 1500L
        private val R_ENTERABLE = com.oneuiclone.home.R.id.search_enterable
        private val AVATAR_COLORS = intArrayOf(0xFF5B8DEF.toInt(), 0xFF3FAE7A.toInt(), 0xFFE0794A.toInt(), 0xFFB065D6.toInt(), 0xFFD9A93A.toInt(), 0xFF4BA8B8.toInt())
    }

    /** Contact photo in a circle, or the initial on a colour. */
    private class ContactAvatar(context: Context, private val photo: Bitmap?, name: String, private val color: Int) : View(context) {
        private val initial = name.trim().take(1).uppercase()
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG)
        private val path = android.graphics.Path()
        override fun onDraw(canvas: android.graphics.Canvas) {
            val r = width / 2f
            if (photo != null) {
                path.reset(); path.addCircle(r, r, r, android.graphics.Path.Direction.CW)
                canvas.save(); canvas.clipPath(path)
                canvas.drawBitmap(photo, null, android.graphics.RectF(0f, 0f, width.toFloat(), height.toFloat()), paint)
                canvas.restore()
                return
            }
            paint.color = color
            canvas.drawCircle(r, r, r, paint)
            paint.color = 0xFFFFFFFF.toInt()
            if (initial.isEmpty()) {
                // person glyph (permission row)
                canvas.drawCircle(r, r * 0.78f, r * 0.30f, paint)
                canvas.drawArc(r * 0.42f, r * 1.18f, r * 1.58f, r * 2.2f, 180f, 180f, true, paint)
                return
            }
            paint.textSize = r * 0.95f
            paint.textAlign = android.graphics.Paint.Align.CENTER
            paint.typeface = Fonts.ui(context, 600)
            val fm = paint.fontMetrics
            canvas.drawText(initial, r, r - (fm.ascent + fm.descent) / 2f, paint)
        }
    }
}
