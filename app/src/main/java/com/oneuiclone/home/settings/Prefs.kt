package com.oneuiclone.home.settings

import android.content.Context
import android.content.SharedPreferences

/** Launcher settings (One UI "Home screen settings"). */
class Prefs(context: Context) {
    val sp: SharedPreferences = context.getSharedPreferences("home_settings", Context.MODE_PRIVATE)

    // whatever reads the settings first (Home, a widget, a settings page) sets the icon shape for this process
    init { com.oneuiclone.home.icons.IconShape.current = iconShape; com.oneuiclone.home.icons.IconFactory.comicBorder = comicBorder
        com.oneuiclone.home.icons.IconFactory.iconShine = iconShine
        com.oneuiclone.home.ui.FolderIconView.style = folderIcons
        com.oneuiclone.home.ui.FolderIconView.popupFolders = popupFolders
        com.oneuiclone.home.icons.IconShape.keepSquircle = keepSquircle }

    var homeCols: Int get() = sp.getInt("home_cols", 4); set(v) = sp.edit().putInt("home_cols", v).apply()
    var homeRows: Int get() = sp.getInt("home_rows", 6); set(v) = sp.edit().putInt("home_rows", v).apply()
    var appsCols: Int get() = sp.getInt("apps_cols", 4); set(v) = sp.edit().putInt("apps_cols", v).apply()
    var appsRows: Int get() = sp.getInt("apps_rows", 6); set(v) = sp.edit().putInt("apps_rows", v).apply()
    /** 85..115 % (Home Up range); stock small/medium/large = 90/100/110. */
    var iconScale: Int get() = sp.getInt("icon_scale", 100); set(v) = sp.edit().putInt("icon_scale", v).apply()
    var homeLabels: Boolean get() = sp.getBoolean("home_labels", true); set(v) = sp.edit().putBoolean("home_labels", v).apply()
    var appsLabels: Boolean get() = sp.getBoolean("apps_labels", true); set(v) = sp.edit().putBoolean("apps_labels", v).apply()
    var swipeDownNotifications: Boolean get() = sp.getBoolean("swipe_down_notif", true); set(v) = sp.edit().putBoolean("swipe_down_notif", v).apply()
    /** Swiping up on Home opens search instead of the Apps screen. */
    /** Search sends what you type to Google for suggestions; off keeps search entirely on the device. */
    var webSuggestions: Boolean get() = sp.getBoolean("web_suggestions", true); set(v) = sp.edit().putBoolean("web_suggestions", v).apply()
    var swipeUpSearch: Boolean get() = sp.getBoolean("swipe_up_search", false); set(v) = sp.edit().putBoolean("swipe_up_search", v).apply()
    /**
     * Swiping down on Home opens search instead of the notification panel, and swiping up always opens the Apps
     * screen (overrides [swipeDownNotifications] and [swipeUpSearch]); off by default.
     */
    var swipeDownSearch: Boolean get() = sp.getBoolean("swipe_down_search", false); set(v) = sp.edit().putBoolean("swipe_down_search", v).apply()
    /** Home shows no Search button and no page dots (their row stays empty); search then fades in rather than grow out of the button. */
    var hideSearchRow: Boolean get() = sp.getBoolean("hide_search_row", false); set(v) = sp.edit().putBoolean("hide_search_row", v).apply()
    var lockLayout: Boolean get() = sp.getBoolean("lock_layout", false); set(v) = sp.edit().putBoolean("lock_layout", v).apply()
    var addNewApps: Boolean get() = sp.getBoolean("add_new_apps", false); set(v) = sp.edit().putBoolean("add_new_apps", v).apply()
    var themedIcons: Boolean get() = sp.getBoolean("themed_icons", false); set(v) = sp.edit().putBoolean("themed_icons", v).apply()
    /** Force a tonal theme icon on apps that don't ship a monochrome layer. */
    var themeAllApps: Boolean get() = sp.getBoolean("theme_all_apps", true); set(v) = sp.edit().putBoolean("theme_all_apps", v).apply()
    /** Dark icons (Icon theme › Dark): every app's icon on a dark plate (IconFactory.createDark); excludes [themedIcons]. */
    var darkIcons: Boolean get() = sp.getBoolean("dark_icons", false); set(v) = sp.edit().putBoolean("dark_icons", v).apply()
    /** Minimal icons (Icon theme › Minimal): themed icons' white glyph alone, no plate (needs [themedIcons]). */
    var minimalIcons: Boolean get() = sp.getBoolean("minimal_icons", false); set(v) = sp.edit().putBoolean("minimal_icons", v).apply()
    /**
     * Dot, Prism, Stencil, Satin, Doodle, Sprinkles, Comic, Groovy or Glass icons (Icon theme, IconFactory.LOOK_*): the themed glyph drawn in that
     * style on a plate of its own; empty for the other styles (needs [themedIcons]).
     */
    var iconLook: String
        // 0.203–0.204 had a Clear theme ("clear"), since removed: it becomes Glass
        get() = (sp.getString("icon_look", "") ?: "").let { if (it == "clear") com.oneuiclone.home.icons.IconFactory.LOOK_IOS else it }
        set(v) = sp.edit().putString("icon_look", v).apply()
    /** Icons › Light or dark for the looks with both (Dot, Prism, the playful ones, Glass): IconFactory.MODE_AUTO (follow dark mode), MODE_LIGHT or MODE_DARK. */
    var iconMode: String get() = sp.getString("icon_mode", com.oneuiclone.home.icons.IconFactory.MODE_AUTO) ?: com.oneuiclone.home.icons.IconFactory.MODE_AUTO
        set(v) = sp.edit().putString("icon_mode", v).apply()
    /** Icons › Icon shape (IconShape.SQUIRCLE, CIRCLE, …). Setting it moves IconShape.current along with it. */
    var iconShape: String get() = sp.getString("icon_shape", com.oneuiclone.home.icons.IconShape.SQUIRCLE) ?: com.oneuiclone.home.icons.IconShape.SQUIRCLE
        set(v) { sp.edit().putString("icon_shape", v).apply(); com.oneuiclone.home.icons.IconShape.current = v }
    /** Icons › Comic border: Comic icons' black ink border. Setting it moves IconFactory.comicBorder along with it. */
    var comicBorder: Boolean get() = sp.getBoolean("comic_border", true)
        set(v) { sp.edit().putBoolean("comic_border", v).apply(); com.oneuiclone.home.icons.IconFactory.comicBorder = v }
    /** Icons › Icon contour: the icons' light rim and edge shading. Setting it moves IconFactory.iconShine along with it. */
    var iconShine: Boolean get() = sp.getBoolean("icon_shine", true)
        set(v) { sp.edit().putBoolean("icon_shine", v).apply(); com.oneuiclone.home.icons.IconFactory.iconShine = v }
    /**
     * Icons › Folder icons (round folders only): FolderIconView.INSIDE, OUTSIDE (the apps poke out past the edge) or
     * PIXEL (four apps, as Pixel Launcher). 0.199 saved Outside as folder_pop_out. Setting it moves FolderIconView.style
     * along with it.
     */
    var folderIcons: String
        get() = sp.getString("folder_icons", null)
            ?: if (sp.getBoolean("folder_pop_out", false)) com.oneuiclone.home.ui.FolderIconView.OUTSIDE else com.oneuiclone.home.ui.FolderIconView.INSIDE
        set(v) { sp.edit().putString("folder_icons", v).remove("folder_pop_out").apply(); com.oneuiclone.home.ui.FolderIconView.style = v }
    /** Icons › Keep folders and widgets squircle: they stay One UI's squircle whatever the icon shape. Setting it moves IconShape.keepSquircle along with it. */
    var keepSquircle: Boolean get() = sp.getBoolean("keep_squircle", false)
        set(v) { sp.edit().putBoolean("keep_squircle", v).apply(); com.oneuiclone.home.icons.IconShape.keepSquircle = v }
    /** Theme icons on the Apps screen and in search too, not just Home (needs [themedIcons]). */
    var themeAppsScreen: Boolean get() = sp.getBoolean("theme_apps_screen", false); set(v) = sp.edit().putBoolean("theme_apps_screen", v).apply()
    /**
     * Glass icons: themed icons whose solid plate is see-through by [glassIconTransparency] (0..100 %) over the
     * blurred wallpaper, like widgets and folders set to Style › Blur.
     */
    var glassIcons: Boolean get() = sp.getBoolean("glass_icons", false); set(v) = sp.edit().putBoolean("glass_icons", v).apply()
    var glassIconTransparency: Int get() = sp.getInt("glass_icon_transparency", 50); set(v) = sp.edit().putInt("glass_icon_transparency", v).apply()
    /** Opacity of a themed icon's plate: below 1 only with glass icons on (and theme icons, which they build on). */
    val iconPlateAlpha: Float get() = if (themedIcons && glassIcons) 1f - glassIconTransparency / 100f else 1f
    /** Icons show the blurred wallpaper under them: glass icons' see-through plate, or Stencil's cut-out symbol. */
    val iconGlass: Boolean get() = iconPlateAlpha < 1f || (themedIcons && iconLook == com.oneuiclone.home.icons.IconFactory.LOOK_STENCIL)
    /** Good Lock Home Up "pop-up folder" style. */
    var popupFolders: Boolean get() = sp.getBoolean("popup_folders", false)
        set(v) { sp.edit().putBoolean("popup_folders", v).apply(); com.oneuiclone.home.ui.FolderIconView.popupFolders = v }
    /** "Bubble" in an app's quick options (see AppBubbles); off until the user turns it on. */
    var appBubbles: Boolean get() = sp.getBoolean("app_bubbles", false); set(v) = sp.edit().putBoolean("app_bubbles", v).apply()
    /** First-run setup (SetupActivity) has been shown; it opens by itself only once. */
    var setupShown: Boolean get() = sp.getBoolean("setup_shown", false); set(v) = sp.edit().putBoolean("setup_shown", v).apply()
    var badges: Boolean get() = sp.getBoolean("badges", true); set(v) = sp.edit().putBoolean("badges", v).apply()
    var badgeNumbers: Boolean get() = sp.getBoolean("badge_numbers", true); set(v) = sp.edit().putBoolean("badge_numbers", v).apply()
    /** Blur the wallpaper behind the Home screen; [homeBlurAmount] is % of the Apps screen blur. */
    var homeBlur: Boolean get() = sp.getBoolean("home_blur", false); set(v) = sp.edit().putBoolean("home_blur", v).apply()
    var homeBlurAmount: Int get() = sp.getInt("home_blur_amount", 40); set(v) = sp.edit().putInt("home_blur_amount", v).apply()
    /** 0..1 of the full blur, 0 when off. */
    val homeBlurLevel: Float get() = if (homeBlur) homeBlurAmount / 100f else 0f

    /**
     * App opening animation: [OPEN_ZOOM_IN] (the icon grows into a card, iOS-style; the default), [OPEN_SMOOTH] (Home
     * shrinks), or one drawn by Launchii's live wallpaper ([openEffect]: Aura, Shutter, Orbit, Anticipate, Scan, Mist;
     * Zoom In without it).
     */
    var openAnimation: String get() = sp.getString("open_animation", OPEN_ZOOM_IN) ?: OPEN_ZOOM_IN; set(v) = sp.edit().putString("open_animation", v).apply()
    val zoomInOpen: Boolean get() = openAnimation == OPEN_ZOOM_IN
    val openEffect: com.oneuiclone.home.ui.LiveWallpaper.Kind? get() = when (openAnimation) {
        OPEN_AURA -> com.oneuiclone.home.ui.LiveWallpaper.Kind.AURA
        OPEN_SHUTTER -> com.oneuiclone.home.ui.LiveWallpaper.Kind.SHUTTER
        OPEN_ORBIT -> com.oneuiclone.home.ui.LiveWallpaper.Kind.ORBIT
        OPEN_ANTICIPATE -> com.oneuiclone.home.ui.LiveWallpaper.Kind.ANTICIPATE
        OPEN_SCAN -> com.oneuiclone.home.ui.LiveWallpaper.Kind.SCAN
        OPEN_MIST -> com.oneuiclone.home.ui.LiveWallpaper.Kind.MIST
        else -> null
    }
    val wallpaperOpen: Boolean get() = openEffect != null

    companion object {
        const val OPEN_SMOOTH = "smooth"
        const val OPEN_ZOOM_IN = "zoom_in"
        const val OPEN_AURA = "aura"
        const val OPEN_SHUTTER = "shutter"
        const val OPEN_ORBIT = "orbit"
        const val OPEN_ANTICIPATE = "anticipate"
        const val OPEN_SCAN = "scan"
        const val OPEN_MIST = "mist"

        /** Keys applied live by the launcher (no view rebuild when only these change). */
        fun isLive(key: String) = key.startsWith("home_blur") || key == "open_animation"
    }
}
