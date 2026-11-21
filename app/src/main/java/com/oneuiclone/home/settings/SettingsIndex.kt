package com.oneuiclone.home.settings

import android.content.Context
import android.content.Intent
import com.oneuiclone.home.settings.SettingsSectionActivity.Companion as S

/**
 * Settings search (0.263): every option and way in, with where it lives. A result opens its page with
 * [OneUiPage.EXTRA_HIGHLIGHT] set to the row's title there, so the page scrolls to it and flashes it.
 */
object SettingsIndex {

    /** App opening animation styles and their names, in the Motion page's order. */
    val OPEN_STYLES = listOf(Prefs.OPEN_SMOOTH to "Smooth", Prefs.OPEN_ZOOM_IN to "Zoom In", Prefs.OPEN_AURA to "Aura",
        Prefs.OPEN_SHUTTER to "Shutter", Prefs.OPEN_ORBIT to "Orbit", Prefs.OPEN_ANTICIPATE to "Anticipate", Prefs.OPEN_SCAN to "Scan", Prefs.OPEN_MIST to "Mist")

    fun openName(key: String) = OPEN_STYLES.firstOrNull { it.first == key }?.second ?: "Zoom In"

    /**
     * One searchable option: [title] and [summary] as listed, [path] (Section › group), the page it's on ([page], with
     * [section] for a [SettingsSectionActivity]) and the row there to flash ([highlight]; null for none). [words] are
     * extra terms it's found by.
     */
    class Entry(val title: String, val path: String, val summary: String, val glyph: String, private val page: Class<*>,
                private val section: String? = null, val highlight: String? = title, private val words: String = "") {
        val haystack = "$title $path $summary $words".lowercase()

        fun intent(context: Context): Intent = Intent(context, page).apply {
            section?.let { putExtra(S.EXTRA_SECTION, it) }
            highlight?.let { putExtra(OneUiPage.EXTRA_HIGHLIGHT, it) }
        }
    }

    private val WALL = WallpaperStyleActivity::class.java
    private val ICONS = IconThemeActivity::class.java
    private val THEMES = IconThemePickerActivity::class.java
    private val COLOUR = IconColourActivity::class.java
    private val MOTION = OpeningAnimationActivity::class.java
    private val SECTION = SettingsSectionActivity::class.java

    private fun all(context: Context): List<Entry> {
        val app = context.getString(com.oneuiclone.home.R.string.app_name)
        val e = ArrayList<Entry>()
        fun wall(t: String, p: String, s: String, w: String = "", h: String? = t) = e.add(Entry(t, p, s, Glyphs.IMAGE, WALL, null, h, w))
        fun icons(t: String, p: String, s: String, w: String = "", h: String? = t) = e.add(Entry(t, p, s, Glyphs.SHAPES, ICONS, null, h, w))
        fun sec(id: String, glyph: String, t: String, p: String, s: String, w: String = "", h: String? = t) = e.add(Entry(t, p, s, glyph, SECTION, id, h, w))

        // Wallpaper
        wall("Change wallpaper", "Wallpaper", "Pick a photo, move and zoom it, then set it on Home, Lock screen or both", "photo picture background set")
        wall("Lock screen preview", "Wallpaper", "Tap it to choose a photo", "preview", "Change wallpaper")
        wall("Home screen preview", "Wallpaper", "Tap it to choose a photo", "preview", "Change wallpaper")
        wall("Blur wallpaper", "Wallpaper › Effects", "Soften the wallpaper behind your Home screen", "background")
        wall("Blur strength", "Wallpaper › Effects", "How strongly the Home wallpaper is blurred, 10 to 100 %", "blur amount")
        wall("Live wallpaper", "Wallpaper › Effects", "Your wallpaper zooms when apps open and close", "zoom animation")
        wall("Wallpaper effects", "Wallpaper › Effects", "Whether blur and zoom can use your wallpaper", "blur zoom status")
        wall("Colour palette", "Wallpaper › Colours", "Material You colours from your wallpaper. Settings takes its accent from it", "color colours theme accent material you")

        // Icons
        icons("Icon theme", "Icons", "16 themes in Simple, Stylish and Playful", "style look pack")
        IconThemeActivity.STYLES.forEachIndexed { i, name ->
            e.add(Entry(name, "Icons › Icon theme", IconThemeActivity.SUMMARIES[i], Glyphs.SHAPES, THEMES, null, name, "theme style"))
        }
        icons("Icon shape", "Icons › Icon shape", "Squircle, Circle, Square, Cookie or Hexagon", "round")
        icons("Icon colour", "Icons › theme options", "Themed, Frosted and Clear Colour: your palette, a ready-made colour or any colour", "color tint")
        icons("Light or dark", "Icons › theme options", "Dot, Prism, Glass and the Playful themes: Auto, Light or Dark", "dark mode")
        icons("Comic border", "Icons › theme options", "The black ink outline around Comic icons", "outline")
        icons("Icon contour", "Icons › theme options", "The soft light and shading along the edge of each icon", "shine emboss flat rim")
        icons("Apply to Apps screen and search", "Icons › theme options", "Use your icon theme on the Apps screen and in search too", "drawer")
        icons("Icon transparency", "Icons › theme options", "Frosted and Clear Colour: how much blurred wallpaper shows through", "glass blur see-through opacity")
        icons("Icon size", "Icons › Size and labels", "85 to 115 %", "bigger smaller scale")
        icons("App labels on Home screen", "Icons › Size and labels", "On, Off or While touching: names fade in while your finger is on Home", "names text hide peek")
        icons("App labels on Apps screen", "Icons › Size and labels", "App names on the Apps screen", "names text hide drawer")
        e.add(Entry("Ready-made colours", "Icons › Icon colour", "Twelve ready-made icon colours", Glyphs.SHAPES, COLOUR, null, null, "color preset"))
        e.add(Entry("Any colour", "Icons › Icon colour", "Pick any icon colour on the colour square and hue bar", Glyphs.SHAPES, COLOUR, null, null, "color custom picker"))

        // Layout
        sec(S.LAYOUT, Glyphs.APPS, "Home screen grid", "Layout", "4×5, 4×6, 5×5 or 5×6", "columns rows size 4x5 4x6 5x5 5x6")
        sec(S.LAYOUT, Glyphs.APPS, "Apps screen grid", "Layout", "4×5, 4×6, 5×5 or 5×6", "columns rows drawer 4x5 4x6 5x5 5x6")
        sec(S.LAYOUT, Glyphs.APPS, "Hide search bar and page indicators", "Layout › Home screen", "Remove the Search button and page dots from the Home screen", "dots pill")
        sec(S.LAYOUT, Glyphs.APPS, "Add new apps to Home screen", "Layout › Home screen", "New apps get an icon on Home", "install")
        sec(S.LAYOUT, Glyphs.APPS, "Lock Home screen layout", "Layout › Home screen", "Prevent items on the Home screen from being removed or repositioned", "move")
        sec(S.LAYOUT, Glyphs.APPS, "Letter rail", "Layout › Apps screen", "Slide along the letters to jump to apps", "alphabet scroll a-z index drawer")

        // Gestures
        sec(S.GESTURES, Glyphs.TOUCH, "Swipe down", "Gestures", "Notification panel, Search or Nothing", "notifications gesture")
        sec(S.GESTURES, Glyphs.TOUCH, "Swipe up", "Gestures", "Apps screen or Search", "drawer gesture")

        // Search
        sec(S.SEARCH, Glyphs.SEARCH, "Web suggestions", "Search › Results", "Show Google suggestions as you type", "google internet")
        sec(S.SEARCH, Glyphs.SEARCH, "Most used apps first", "Search › Results", "Usage access: the apps you open most come first", "usage ranking frequent")
        sec(S.SEARCH, Glyphs.SEARCH, "Contacts in search", "Search › Results", "Find people from search", "people permission")
        sec(S.SEARCH, Glyphs.SEARCH, "Search keyboard height", "Search › Keyboard", "Learned from your keyboard. Re-learn it", "ime")

        // Motion
        e.add(Entry("App opening animation", "Motion", OPEN_STYLES.joinToString(", ") { it.second }, Glyphs.MOTION, MOTION, null, null, "open launch transition"))
        for ((_, name) in OPEN_STYLES) e.add(Entry(name, "Motion › App opening animation", "An app opening animation", Glyphs.MOTION, MOTION, null, null, "open"))
        e.add(Entry("Settle in on unlock", "Motion", "Your wallpaper eases in and your apps ripple in when you unlock", Glyphs.MOTION, MOTION, null, "Settle in on unlock", "fingerprint lock screen"))

        // Folders & widgets
        sec(S.FOLDERS, Glyphs.FOLDER, "Pop-up folders", "Folders & widgets › Folders", "Open folders in a small window over the Home screen", "popup")
        sec(S.FOLDERS, Glyphs.FOLDER, "Folder icons", "Folders & widgets › Folders", "Inside, Outside or Pixel, for round icons", "circle")
        sec(S.FOLDERS, Glyphs.FOLDER, "Keep folders and widgets squircle", "Folders & widgets › Folders", "Folders and Launchii's widgets keep One UI's shape", "shape")
        sec(S.FOLDERS, Glyphs.WIDGETS, "Add a widget", "Folders & widgets › Widgets", "From Home screen edit mode › Widgets", "widgets")
        sec(S.FOLDERS, Glyphs.TOUCH, "Touch and hold a folder", "Folders & widgets", "Rename, Add apps, Color, Style", "colour blur long press")
        sec(S.FOLDERS, Glyphs.TOUCH, "Touch and hold a widget", "Folders & widgets", "Style: blur and transparency, Align with icons or Centre in row, Settings", "long press transparent")

        // Badges & bubbles
        sec(S.BADGES, Glyphs.BADGE, "App icon badges", "Badges & bubbles › Badges", "Notification badges on app icons", "dot count")
        sec(S.BADGES, Glyphs.BADGE, "Badge style", "Badges & bubbles › Badges", "One UI orange, Matched or Quiet dot", "badge colour color")
        sec(S.BADGES, Glyphs.BADGE, "Show number on badges", "Badges & bubbles › Badges", "The notification count on each badge", "count")
        sec(S.BADGES, Glyphs.BADGE, "Notification access", "Badges & bubbles › Badges", "Badges need it", "permission listener")
        sec(S.BADGES, Glyphs.BUBBLE, "App bubbles", "Badges & bubbles › Bubbles", "Open an app in a floating window from its touch and hold menu", "floating window")
        sec(S.BADGES, Glyphs.BUBBLE, "Notifications and bubbles", "Badges & bubbles › Bubbles", "Bubbles need them", "permission")

        // Permissions & setup
        sec(S.PERMISSIONS, Glyphs.SHIELD, "Home app", "Permissions & setup", "Default Home app: $app", "default launcher")
        sec(S.PERMISSIONS, Glyphs.SHIELD, "Your wallpaper", "Permissions & setup", "Set in $app, so blur and zoom work")
        sec(S.PERMISSIONS, Glyphs.SHIELD, "Usage access", "Permissions & setup", "Most used apps first in search, and split screen closes cleanly", "permission")
        sec(S.PERMISSIONS, Glyphs.SHIELD, "Contacts", "Permissions & setup", "Find people from search", "permission people")
        sec(S.PERMISSIONS, Glyphs.WAND, "Run setup again", "Permissions & setup", "Set up $app: permissions and your wallpaper, all in one place", "setup set up welcome")

        // Backup & advanced
        sec(S.BACKUP, Glyphs.RESTORE, "Restore Home screen layout", "Backup & advanced", "Go back to an earlier layout, saved automatically", "backup snapshot undo")
        sec(S.BACKUP, Glyphs.WRENCH, "Icon check", "Backup & advanced › Diagnostics", "Temporary: shows which icons get white corners, and why", "debug")

        // About
        sec(S.ABOUT, Glyphs.INFO, "Software update", "About", "Update $app to the newest version", "upgrade version github")
        sec(S.ABOUT, Glyphs.INFO, "Open source licences", "About", "Inter typeface", "license font")
        sec(S.ABOUT, Glyphs.INFO, "About $app", "About", "Name and version", "version", null)
        sec(S.ABOUT, Glyphs.INFO, "Developer", "About", "Developed by ${Developer.HANDLE}: Instagram, Threads, X, Email the developer", "contact feedback mail", null)
        return e
    }

    /** The entries with every word of [query] in them, the best title matches first. */
    fun search(context: Context, query: String): List<Entry> {
        val words = query.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        return all(context).filter { e -> words.all { it in e.haystack } }
            .sortedByDescending { e ->
                val t = e.title.lowercase()
                when {
                    t.startsWith(words[0]) -> 3
                    words.all { it in t } -> 2
                    words.any { it in t } -> 1
                    else -> 0
                }
            }
    }
}
