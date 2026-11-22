package com.oneuiclone.home.ui

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/**
 * The one answers row in search (0.230), between the apps and Google: maths and unit conversions worked out on the
 * phone, and settings pages. Plain Kotlin (the launcher turns them into rows and intents), so it is unit tested.
 */
object SearchAnswers {

    /** A worked-out answer: [value] big, [shown] the question as typed, tidied. */
    data class Calc(val value: String, val shown: String)

    /** A settings page: [actions] are tried in order until one opens (panels first, then the page itself). */
    data class Setting(val title: String, val place: String, val keywords: List<String>, val actions: List<String>)

    /** An app confirmed on Google Play (its featured hero match for a search query). */
    data class PlayApp(val title: String, val pkg: String)

    // ---------------------------------------------------------------------------------------------
    // maths
    // ---------------------------------------------------------------------------------------------

    private val PERCENT_OF = Regex("^(-?[\\d.,]+)\\s*%\\s*of\\s*(-?[\\d.,]+)$")
    private val CONVERT = Regex("^(-?\\d+(?:[.,]\\d+)?)\\s*([a-z°/]+(?:\\s[a-z]+)?)\\s+(?:in|to|into|as)\\s+([a-z°/]+(?:\\s[a-z]+)?)$")
    private val MATH_CHARS = Regex("^[\\d\\s.,+\\-*/x×÷^%()]+$")

    /** [q] typed (any case): a sum ("12*8", "(3+4)^2", "20%*50"), "15% of 80", or "5 km in miles"; else null. */
    fun calc(q: String): Calc? {
        val s = q.trim().lowercase()
        if (s.isEmpty()) return null
        PERCENT_OF.matchEntire(s)?.let { m ->
            val p = num(m.groupValues[1]) ?: return null
            val of = num(m.groupValues[2]) ?: return null
            return Calc(format(p * of / 100.0), "${m.groupValues[1]}% of ${m.groupValues[2]}")
        }
        CONVERT.matchEntire(s)?.let { m -> convert(m.groupValues[1], m.groupValues[2], m.groupValues[3])?.let { return it } }
        if (!MATH_CHARS.matches(s)) return null
        // a sum needs an operator between two numbers: "12", "-3" and "2024" are left to the apps and Google
        if (Regex("\\d").findAll(s).none() || !Regex("[\\d)%]\\s*[+\\-*/x×÷^%]\\s*[\\d(.]").containsMatchIn(s) && !Regex("\\d\\s*%$").containsMatchIn(s)) return null
        val v = try { Parser(s.replace(',', '.')).parse() } catch (_: Exception) { return null }
        if (v.isNaN() || v.isInfinite()) return null
        val shown = s.replace(Regex("\\s+"), "").replace(Regex("[*x]"), " × ").replace("÷", " ÷ ").replace("/", " ÷ ")
            .replace("+", " + ").replace(Regex("(?<=[\\d)])-"), " − ")
        return Calc(format(v), shown)
    }

    private fun num(s: String) = s.replace(',', '.').toDoubleOrNull()

    /** Up to 10 significant digits, no trailing zeros, no exponent. */
    fun format(v: Double): String {
        if (v == 0.0) return "0"
        val bd = BigDecimal(v).round(MathContext(10, RoundingMode.HALF_UP)).stripTrailingZeros()
        val plain = bd.toPlainString()
        return if (plain == "-0") "0" else plain
    }

    /** Recursive descent: + − below × ÷ below ^ (right to left), unary minus, brackets, a trailing % = /100. */
    private class Parser(private val s: String) {
        private var i = 0
        fun parse(): Double { val v = sum(); skip(); if (i != s.length) throw IllegalArgumentException(); return v }
        private fun skip() { while (i < s.length && s[i] == ' ') i++ }
        private fun peek(): Char? { skip(); return s.getOrNull(i) }
        private fun sum(): Double {
            var v = product()
            while (true) when (peek()) {
                '+' -> { i++; v += product() }
                '-' -> { i++; v -= product() }
                else -> return v
            }
        }
        private fun product(): Double {
            var v = power()
            while (true) when (peek()) {
                '*', 'x', '×' -> { i++; v *= power() }
                '/', '÷' -> { i++; v /= power() }
                else -> return v
            }
        }
        private fun power(): Double {
            val b = unary()
            return if (peek() == '^') { i++; Math.pow(b, power()) } else b
        }
        private fun unary(): Double = if (peek() == '-') { i++; -unary() } else if (peek() == '+') { i++; unary() } else percent()
        private fun percent(): Double {
            var v = atom()
            while (peek() == '%') { i++; v /= 100.0 }
            return v
        }
        private fun atom(): Double {
            if (peek() == '(') { i++; val v = sum(); if (peek() != ')') throw IllegalArgumentException(); i++; return v }
            skip()
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
            if (start == i) throw IllegalArgumentException()
            return s.substring(start, i).toDouble()
        }
    }

    // ---------------------------------------------------------------------------------------------
    // units
    // ---------------------------------------------------------------------------------------------

    private class Unit(val kind: String, val factor: Double, val label: String)

    private val UNITS: Map<String, Unit> = buildMap {
        fun add(kind: String, factor: Double, label: String, vararg names: String) { for (n in names) put(n, Unit(kind, factor, label)) }
        add("len", 0.001, "mm", "mm", "millimetre", "millimetres", "millimeter", "millimeters")
        add("len", 0.01, "cm", "cm", "centimetre", "centimetres", "centimeter", "centimeters")
        add("len", 1.0, "m", "m", "metre", "metres", "meter", "meters")
        add("len", 1000.0, "km", "km", "kilometre", "kilometres", "kilometer", "kilometers")
        add("len", 0.0254, "in", "in", "inch", "inches")
        add("len", 0.3048, "ft", "ft", "foot", "feet")
        add("len", 0.9144, "yd", "yd", "yard", "yards")
        add("len", 1609.344, "mi", "mi", "mile", "miles")
        add("mass", 1e-6, "mg", "mg", "milligram", "milligrams")
        add("mass", 0.001, "g", "g", "gram", "grams")
        add("mass", 1.0, "kg", "kg", "kilo", "kilos", "kilogram", "kilograms")
        add("mass", 0.45359237, "lb", "lb", "lbs", "pound", "pounds")
        add("mass", 0.028349523125, "oz", "oz", "ounce", "ounces")
        add("mass", 6.35029318, "st", "st", "stone", "stones")
        add("vol", 0.001, "ml", "ml", "millilitre", "millilitres", "milliliter", "milliliters")
        add("vol", 1.0, "L", "l", "litre", "litres", "liter", "liters")
        add("vol", 3.785411784, "gal", "gal", "gallon", "gallons")
        add("vol", 0.2365882365, "cups", "cup", "cups")
        add("speed", 1 / 3.6, "km/h", "kmh", "km/h", "kph")
        add("speed", 0.44704, "mph", "mph")
        add("speed", 1.0, "m/s", "m/s")
        add("temp", 0.0, "°C", "c", "°c", "celsius", "centigrade")
        add("temp", 1.0, "°F", "f", "°f", "fahrenheit")
        add("temp", 2.0, "K", "k", "kelvin")
    }

    private fun convert(amount: String, from: String, to: String): Calc? {
        val a = num(amount) ?: return null
        val f = UNITS[from.trim()] ?: return null
        val t = UNITS[to.trim()] ?: return null
        if (f.kind != t.kind) return null
        val v = if (f.kind == "temp") {
            val c = when (f.factor) { 0.0 -> a; 1.0 -> (a - 32) * 5 / 9; else -> a - 273.15 }
            when (t.factor) { 0.0 -> c; 1.0 -> c * 9 / 5 + 32; else -> c + 273.15 }
        } else a * f.factor / t.factor
        val rounded = BigDecimal(v).setScale(if (kotlin.math.abs(v) >= 100) 1 else 3, RoundingMode.HALF_UP).toDouble()
        return Calc("${format(rounded)} ${t.label}", "$amount ${f.label} in ${t.label}")
    }

    // ---------------------------------------------------------------------------------------------
    // settings
    // ---------------------------------------------------------------------------------------------

    private const val NET = "Network & internet"
    private const val DEV = "Connected devices"

    val SETTINGS: List<Setting> = listOf(
        Setting("Wi‑Fi", NET, listOf("wifi", "wi-fi", "wi fi", "wireless", "wlan"),
            listOf("android.settings.panel.action.WIFI", "android.settings.WIFI_SETTINGS")),
        Setting("Internet", NET, listOf("internet", "mobile data", "data", "connection"),
            listOf("android.settings.panel.action.INTERNET_CONNECTIVITY", "android.settings.WIRELESS_SETTINGS")),
        Setting("Hotspot & tethering", NET, listOf("hotspot", "tethering", "tether", "share internet"),
            listOf("android.settings.TETHER_SETTINGS", "android.settings.WIRELESS_SETTINGS")),
        Setting("Airplane mode", NET, listOf("airplane", "aeroplane", "flight mode", "plane"), listOf("android.settings.AIRPLANE_MODE_SETTINGS")),
        Setting("SIMs & mobile network", NET, listOf("sim", "mobile network", "carrier", "roaming", "esim", "apn"),
            listOf("android.settings.NETWORK_OPERATOR_SETTINGS", "android.settings.DATA_ROAMING_SETTINGS", "android.settings.WIRELESS_SETTINGS")),
        Setting("Data usage", NET, listOf("data usage", "data saver", "data limit"), listOf("android.settings.DATA_USAGE_SETTINGS")),
        Setting("VPN", NET, listOf("vpn"), listOf("android.settings.VPN_SETTINGS")),
        Setting("Bluetooth", DEV, listOf("bluetooth", "bt", "pair", "pairing", "headphones", "earbuds"),
            listOf("android.settings.BLUETOOTH_SETTINGS")),
        Setting("NFC", DEV, listOf("nfc", "contactless", "tap to pay"), listOf("android.settings.panel.action.NFC", "android.settings.NFC_SETTINGS")),
        Setting("Cast", DEV, listOf("cast", "screen cast", "chromecast", "mirror"), listOf("android.settings.CAST_SETTINGS")),
        Setting("Printing", DEV, listOf("print", "printer", "printing"), listOf("android.settings.ACTION_PRINT_SETTINGS")),
        Setting("Display", "Settings", listOf("display", "brightness", "screen timeout", "timeout", "refresh rate", "font size", "screen"),
            listOf("android.settings.DISPLAY_SETTINGS")),
        Setting("Dark theme", "Display", listOf("dark theme", "dark mode", "dark", "night mode", "light mode"),
            listOf("android.settings.DARK_THEME_SETTINGS", "android.settings.DISPLAY_SETTINGS")),
        Setting("Night Light", "Display", listOf("night light", "blue light", "eye comfort", "night shift"),
            listOf("android.settings.NIGHT_DISPLAY_SETTINGS", "android.settings.DISPLAY_SETTINGS")),
        Setting("Volume", "Sound & vibration", listOf("volume", "loud", "louder"), listOf("android.settings.panel.action.VOLUME", "android.settings.SOUND_SETTINGS")),
        Setting("Sound & vibration", "Settings", listOf("sound", "ringtone", "vibration", "vibrate", "notification sound", "silent", "mute"),
            listOf("android.settings.SOUND_SETTINGS")),
        Setting("Do Not Disturb", "Notifications", listOf("do not disturb", "dnd", "focus", "quiet"),
            listOf("android.settings.ZEN_MODE_PRIORITY_SETTINGS", "android.settings.ZEN_MODE_SETTINGS", "android.settings.SOUND_SETTINGS")),
        Setting("Notifications", "Settings", listOf("notifications", "notification"),
            listOf("android.settings.ALL_APPS_NOTIFICATION_SETTINGS", "android.settings.NOTIFICATION_SETTINGS", "android.settings.SETTINGS")),
        Setting("Battery", "Settings", listOf("battery", "power", "charging", "battery usage"),
            listOf("android.intent.action.POWER_USAGE_SUMMARY", "android.settings.BATTERY_SAVER_SETTINGS")),
        Setting("Battery Saver", "Battery", listOf("battery saver", "power saving", "low power"), listOf("android.settings.BATTERY_SAVER_SETTINGS")),
        Setting("Storage", "Settings", listOf("storage", "space", "free up space", "memory"), listOf("android.settings.INTERNAL_STORAGE_SETTINGS")),
        Setting("Location", "Settings", listOf("location", "gps", "location services"), listOf("android.settings.LOCATION_SOURCE_SETTINGS")),
        Setting("Apps", "Settings", listOf("apps", "applications", "app info", "uninstall", "all apps"),
            listOf("android.settings.MANAGE_APPLICATIONS_SETTINGS", "android.settings.APPLICATION_SETTINGS")),
        Setting("Default apps", "Apps", listOf("default apps", "default browser", "default", "home app", "launcher"),
            listOf("android.settings.MANAGE_DEFAULT_APPS_SETTINGS", "android.settings.HOME_SETTINGS")),
        Setting("Usage access", "Apps", listOf("usage access", "usage"), listOf("android.settings.USAGE_ACCESS_SETTINGS")),
        Setting("Accessibility", "Settings", listOf("accessibility", "talkback", "magnification", "captions", "hearing"),
            listOf("android.settings.ACCESSIBILITY_SETTINGS")),
        Setting("Languages", "System", listOf("language", "languages", "locale", "region"), listOf("android.settings.LOCALE_SETTINGS")),
        Setting("Keyboard", "System", listOf("keyboard", "gboard", "input", "spell check", "autocorrect"),
            listOf("android.settings.INPUT_METHOD_SETTINGS")),
        Setting("Date & time", "System", listOf("date", "time", "time zone", "timezone"), listOf("android.settings.DATE_SETTINGS")),
        Setting("System update", "System", listOf("update", "system update", "software update", "android version"),
            listOf("android.settings.SYSTEM_UPDATE_SETTINGS", "android.settings.DEVICE_INFO_SETTINGS")),
        Setting("Developer options", "System", listOf("developer", "developer options", "usb debugging", "adb"),
            listOf("android.settings.APPLICATION_DEVELOPMENT_SETTINGS")),
        Setting("Users", "System", listOf("users", "guest", "multiple users"), listOf("android.settings.USER_SETTINGS")),
        Setting("About phone", "Settings", listOf("about phone", "about", "phone number", "model", "imei", "build number"),
            listOf("android.settings.DEVICE_INFO_SETTINGS")),
        Setting("Security", "Settings", listOf("security", "screen lock", "lock screen", "pin", "password", "fingerprint", "face unlock", "biometrics"),
            listOf("android.settings.SECURITY_SETTINGS")),
        Setting("Privacy", "Settings", listOf("privacy", "permissions", "permission manager", "camera access", "microphone access"),
            listOf("android.settings.PRIVACY_SETTINGS")),
        Setting("Accounts", "Settings", listOf("accounts", "account", "sync", "google account"), listOf("android.settings.SYNC_SETTINGS")),
    )

    /**
     * The settings page [q] (normalised, see [SearchMatch.normalize]) is after: a keyword it spells out ("bt", "vpn"),
     * else one it starts from 3 letters, else a word of the page's name from 3 letters; the shortest keyword wins.
     */
    fun setting(q: String): Setting? {
        if (q.length < 2) return null
        var best: Setting? = null
        var bestScore = Int.MAX_VALUE
        for (s in SETTINGS) {
            for (k in s.keywords) {
                val score = when {
                    k == q -> 0
                    q.length >= 3 && k.startsWith(q) -> 1000 + k.length
                    else -> continue
                }
                if (score < bestScore) { bestScore = score; best = s }
            }
            if (q.length >= 3) {
                val words = SearchMatch.normalize(s.title).split(' ', '&', '-').filter { it.isNotEmpty() }
                if (words.any { it.startsWith(q) } && 2000 < bestScore) { bestScore = 2000; best = s }
            }
        }
        return best
    }

    // ---------------------------------------------------------------------------------------------
    // Play Store app detection (0.284)
    // ---------------------------------------------------------------------------------------------

    private val NON_APP_PUNCT = Regex("[?!,;\"()]")
    private val PURE_NUMBER = Regex("^[\\d\\s\\-/]+$")
    private val AUTOCORRECT_STARTERS = setOf(
        "what", "whats", "what's", "who", "whos", "who's", "where", "wheres", "where's",
        "when", "whens", "when's", "why", "whys", "why's", "how", "hows", "how's",
    )
    private val QUESTION_STARTERS = AUTOCORRECT_STARTERS + setOf(
        "is", "are", "was", "were", "can", "could", "should", "would", "do", "does", "did", "will",
        "define", "meaning", "translate", "weather", "price", "cost", "best", "near", "nearest",
    )
    private val HERO_ANCHOR = Regex("""<a\s+href="/store/apps/details\?id=([A-Za-z0-9._]+)"\s+aria-label="([^"]+)"[^>]*></a>""")
    private val HERO_TITLE = Regex("""class="vWM94c">([^<]+)<""")
    private val DETAILS_HREF = Regex("""href="/store/apps/details\?id=([A-Za-z0-9._]+)"""")
    private val HTML_NUM_ENTITY = Regex("&#(x?[0-9a-fA-F]+);")

    /**
     * Fast local filter: whether [raw] (normalised as [query]) could plausibly be an app name (short, no
     * question/sentence punctuation, not a multi-word question or general lookup phrase).
     */
    fun mayBeApp(raw: String, query: String = SearchMatch.normalize(raw)): Boolean {
        if (query.length < 3 || query.length > 40) return false
        if (NON_APP_PUNCT.containsMatchIn(raw)) return false
        if (PURE_NUMBER.matches(query)) return false
        val words = query.split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty() || words.size > 3) return false
        if (words.size >= 2 && words[0] in QUESTION_STARTERS) return false
        return true
    }

    /**
     * Whether keyboard autocorrect should turn on for raw input [raw] (assuming no installed app or contact matches):
     * once the user finishes 2 words (space into word 3, or 3+ words via swipe/paste), or right after a 1-word
     * question starter ("what ", "how ", "why ", "where ", "when ", "who ").
     */
    fun shouldEnableAutoCorrect(raw: String): Boolean {
        if (raw.isBlank()) return false
        val trailingSpace = raw.last().isWhitespace()
        val words = SearchMatch.normalize(raw).split(' ').filter { it.isNotEmpty() }
        if (words.size >= 3) return true
        if (trailingSpace && words.size >= 2) return true
        if (trailingSpace && words.size == 1 && words[0] in AUTOCORRECT_STARTERS) return true
        return false
    }

    /**
     * True when Google autocomplete [suggestions] for [query] (normalised) indicate the user is looking for an app
     * (e.g. "strava app", "duolingo apk", "spotify play store").
     */
    fun suggestsApp(query: String, suggestions: List<String>): Boolean {
        val q = SearchMatch.normalize(query)
        if (!mayBeApp(q, q)) return false
        return suggestions.any { raw ->
            val s = SearchMatch.normalize(raw)
            if (!s.startsWith(q)) return@any false
            val rest = s.removePrefix(q)
            if (!rest.startsWith(" ")) return@any false
            val after = rest.trimStart()
            after == "app" || after.startsWith("app ") ||
                after == "apk" || after.startsWith("apk ") ||
                after == "mobile app" || after.startsWith("mobile app ") ||
                after == "play store" || after.startsWith("play store ") ||
                after == "google play" || after.startsWith("google play ") ||
                after == "android app" || after.startsWith("android app ")
        }
    }

    /**
     * Extracts the featured top-match app (hero card) from a Google Play Store search HTML page, if present.
     * Play Store only renders this hero card when the query strongly matches a specific app, not for generic queries.
     */
    fun parsePlayHero(html: String): PlayApp? {
        for (m in HERO_ANCHOR.findAll(html)) {
            val end = (m.range.last + 900).coerceAtMost(html.length)
            val tail = html.substring(m.range.last + 1, end)
            if ("itemprop=\"image\"" in tail || "class=\"vWM94c\"" in tail) {
                val pkg = m.groupValues[1]
                val title = unescapeHtml(m.groupValues[2]).trim()
                if (pkg.isNotEmpty() && title.isNotEmpty()) return PlayApp(title, pkg)
            }
        }
        val titleMatch = HERO_TITLE.find(html) ?: return null
        val start = (titleMatch.range.first - 1200).coerceAtLeast(0)
        val head = html.substring(start, titleMatch.range.first)
        val pkg = DETAILS_HREF.findAll(head).lastOrNull()?.groupValues?.get(1) ?: return null
        val title = unescapeHtml(titleMatch.groupValues[1]).trim()
        return if (title.isNotEmpty()) PlayApp(title, pkg) else null
    }

    private fun unescapeHtml(s: String): String {
        if ('&' !in s) return s
        val decoded = HTML_NUM_ENTITY.replace(s) { m ->
            val raw = m.groupValues[1]
            val code = if (raw.startsWith("x", ignoreCase = true)) raw.drop(1).toIntOrNull(16) else raw.toIntOrNull()
            if (code != null && code in 1..0x10FFFF) String(Character.toChars(code)) else m.value
        }
        return decoded
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
    }
}
