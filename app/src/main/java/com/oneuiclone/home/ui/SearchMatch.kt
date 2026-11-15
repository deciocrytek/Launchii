package com.oneuiclone.home.ui

import java.text.Normalizer

/**
 * How a typed query finds an app, forgiving the way Pixel's search is (0.230): a name that starts with it, then
 * a word in it (capitals start words too, so YouTube is "You Tube" and "yt" finds it) or its initials, then another
 * name for it ("gallery" → Photos, "pay" → Wallet), then the letters anywhere in the name or a word of its package
 * name, then one typo (two from 7 letters). Plain Kotlin, so it is unit tested.
 */
object SearchMatch {
    const val START = 0
    const val WORD = 1
    const val ALIAS = 2
    const val INSIDE = 3
    const val TYPO = 4

    private val MARKS = Regex("\\p{Mn}+")
    private val SPACES = Regex("\\s+")
    private val SEPARATORS = Regex("[\\s\\-._&:+/'’()]+")
    /** A capital after a small letter starts a word ("YouTube", "WhatsApp", "ChatGPT"). */
    private val CAMEL = Regex("(?<=\\p{Ll})(?=\\p{Lu})")

    /** Lower case, accents dropped, spaces collapsed. */
    fun normalize(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(MARKS, "").trim().replace(SPACES, " ")

    /** One searchable thing (an app): its name broken down once per search, not per keystroke. */
    class Entry<T>(val item: T, label: String, pkg: String) {
        val name = normalize(label)
        val words: List<String> = normalize(label.replace(CAMEL, " ")).split(SEPARATORS).filter { it.isNotEmpty() }
        val initials = words.joinToString("") { it.take(1) }
        val aliases: List<String> = SearchAliases.of(pkg, name)
        val pkgWords: List<String> = pkg.lowercase().split('.', '_').filter { it.length >= 3 && it !in PKG_NOISE }
    }

    /** How well [q] (already [normalize]d) matches [e]: [START]..[TYPO], or -1 for not at all. */
    fun tier(e: Entry<*>, q: String): Int {
        if (q.isEmpty()) return -1
        if (e.name.startsWith(q)) return START
        if (e.words.any { it.startsWith(q) } || (q.length >= 2 && e.initials.startsWith(q))) return WORD
        // "google p" also matches word by word ("Google Pay"): every typed word starts a word of the name, in order
        if (' ' in q && wordsInOrder(e.words, q.split(' '))) return WORD
        if (q.length >= 2 && e.aliases.any { it.startsWith(q) }) return ALIAS
        if (e.name.contains(q) || e.name.replace(" ", "").startsWith(q.replace(" ", ""))) return INSIDE
        if (q.length >= 3 && e.pkgWords.any { it.startsWith(q) }) return INSIDE
        if (typo(e, q)) return TYPO
        return -1
    }

    private fun wordsInOrder(words: List<String>, typed: List<String>): Boolean {
        var i = 0
        for (t in typed) {
            while (i < words.size && !words[i].startsWith(t)) i++
            if (i == words.size) return false
            i++
        }
        return true
    }

    /**
     * One wrong, missing, extra or swapped letter from 4 letters on, two from 7, against the name or a word in it
     * (never another name: "calc" is not a typo of Phone's "call"). Below 6 letters it must be the whole word and
     * start with the right letter, since a short query is as likely the start of a different word ("calc" isn't a
     * typo of "cale(ndar)", "slack" isn't one of "black"); from 6 letters the start of a longer word counts too.
     */
    private fun typo(e: Entry<*>, q: String): Boolean {
        if (q.length < 4) return false
        val max = if (q.length >= 7) 2 else 1
        val short = q.length < 6
        val candidates = sequenceOf(e.name) + e.words.asSequence().filter { it.length >= 3 }
        for (c in candidates) {
            if (short) {
                if (c.firstOrNull() == q[0] && distance(q, c, max) <= max) return true
                continue
            }
            for (k in (q.length - max).coerceAtLeast(1)..(q.length + max)) {
                if (k > c.length) break
                if (distance(q, c.substring(0, k), max) <= max) return true
            }
        }
        return false
    }

    /** Edits (insert, delete, change, swap two neighbours) from [a] to [b]; stops early past [max]. */
    fun distance(a: String, b: String, max: Int = Int.MAX_VALUE): Int {
        if (kotlin.math.abs(a.length - b.length) > max) return max + 1
        val d = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) d[i][0] = i
        for (j in 0..b.length) d[0][j] = j
        for (i in 1..a.length) {
            var rowMin = Int.MAX_VALUE
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                var v = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost)
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) v = minOf(v, d[i - 2][j - 2] + 1)
                d[i][j] = v
                rowMin = minOf(rowMin, v)
            }
            if (rowMin > max) return max + 1
        }
        return d[a.length][b.length]
    }

    /** Package-name words that say nothing about the app. */
    private val PKG_NOISE = setOf("com", "org", "net", "android", "google", "apps", "app", "mobile", "client", "sec",
        "samsung", "inc", "www", "free", "pro", "lite", "main", "launcher", "activity", "release", "prod")
}

/**
 * Other names people search apps by (Pixel's search knows these too): by package for well-known apps, and by the
 * app's name for the kinds every phone has under some name (camera, clock, calculator…).
 */
object SearchAliases {
    fun of(pkg: String, name: String): List<String> {
        val out = LinkedHashSet<String>()
        BY_PACKAGE[pkg]?.let { out += it }
        BY_NAME[name]?.let { out += it }
        return out.toList()
    }

    private val CAMERA = listOf("photo", "picture", "selfie", "video")
    private val CLOCK = listOf("alarm", "timer", "stopwatch", "world clock")
    private val CALC = listOf("calc", "maths", "math", "sum")
    private val PHONE = listOf("call", "dial", "dialer", "dialler")
    private val SMS = listOf("sms", "text", "texts", "mms", "rcs")
    private val CONTACTS = listOf("people", "address book", "numbers")
    private val GALLERY = listOf("gallery", "pictures", "pics", "images", "photos", "videos")
    private val FILES = listOf("downloads", "documents", "file manager", "files", "storage")
    private val BROWSER = listOf("browser", "internet", "web")
    private val MAIL = listOf("mail", "email", "e-mail", "inbox")
    private val CALENDAR = listOf("agenda", "events", "schedule", "diary")
    private val NOTES = listOf("notes", "memo", "todo", "to do", "lists")
    private val MUSIC = listOf("music", "songs")
    private val SETTINGS = listOf("preferences", "options", "setup")
    private val STORE = listOf("store", "apps", "market", "google play", "install")
    private val RECORDER = listOf("voice recorder", "voice memo", "dictaphone", "recording")
    private val WEATHER = listOf("forecast", "rain", "temperature")
    private val HEALTH = listOf("health", "fitness", "steps", "workout")
    private val AI = listOf("ai", "assistant", "chatbot")

    private val BY_PACKAGE: Map<String, List<String>> = mapOf(
        "com.google.android.apps.walletnfcrel" to listOf("pay", "gpay", "google pay", "cards", "tap to pay", "wallet"),
        "com.google.android.apps.nbu.paisa.user" to listOf("pay", "gpay", "google pay", "upi"),
        "com.samsung.android.spay" to listOf("pay", "samsung pay", "cards", "wallet"),
        "com.paypal.android.p2pmobile" to listOf("pay", "money", "send money"),
        "com.google.android.apps.photos" to GALLERY,
        "com.sec.android.gallery3d" to GALLERY,
        "com.google.android.GoogleCamera" to CAMERA,
        "com.sec.android.app.camera" to CAMERA,
        "com.google.android.deskclock" to CLOCK,
        "com.sec.android.app.clockpackage" to CLOCK,
        "com.google.android.calculator" to CALC,
        "com.sec.android.app.popupcalculator" to CALC,
        "com.google.android.dialer" to PHONE,
        "com.samsung.android.dialer" to PHONE,
        "com.google.android.apps.messaging" to SMS,
        "com.samsung.android.messaging" to SMS,
        "com.google.android.contacts" to CONTACTS,
        "com.samsung.android.app.contacts" to CONTACTS,
        "com.google.android.apps.nbu.files" to FILES,
        "com.sec.android.app.myfiles" to FILES,
        "com.android.vending" to STORE,
        "com.sec.android.app.samsungapps" to listOf("store", "apps", "galaxy store"),
        "com.android.chrome" to BROWSER,
        "com.sec.android.app.sbrowser" to BROWSER,
        "org.mozilla.firefox" to BROWSER,
        "com.brave.browser" to BROWSER,
        "com.google.android.gm" to MAIL,
        "com.microsoft.office.outlook" to MAIL,
        "com.samsung.android.email.provider" to MAIL,
        "com.google.android.calendar" to CALENDAR,
        "com.samsung.android.calendar" to CALENDAR,
        "com.google.android.youtube" to listOf("yt", "videos"),
        "com.google.android.apps.youtube.music" to listOf("ytm", "yt music", "music", "songs"),
        "com.google.android.apps.maps" to listOf("navigation", "directions", "gps", "map"),
        "com.waze" to listOf("navigation", "directions", "gps", "traffic"),
        "com.whatsapp" to listOf("wa", "whats app", "chat"),
        "com.whatsapp.w4b" to listOf("wa business", "whatsapp business"),
        "com.instagram.android" to listOf("ig", "insta"),
        "com.instagram.barcelona" to listOf("threads", "ig threads"),
        "com.facebook.katana" to listOf("fb"),
        "com.facebook.orca" to listOf("fb messenger", "chat"),
        "com.twitter.android" to listOf("twitter", "tweet", "x"),
        "com.snapchat.android" to listOf("snap"),
        "com.zhiliaoapp.musically" to listOf("tik tok", "tt"),
        "org.telegram.messenger" to listOf("tg", "chat"),
        "org.thoughtcrime.securesms" to listOf("chat", "secure messages"),
        "com.discord" to listOf("chat", "voice chat"),
        "com.reddit.frontpage" to listOf("news", "forum"),
        "com.google.android.apps.recorder" to RECORDER,
        "com.sec.android.app.voicenote" to RECORDER,
        "com.google.android.keep" to NOTES,
        "com.samsung.android.app.notes" to NOTES,
        "com.google.android.apps.docs" to listOf("drive", "files", "cloud", "storage"),
        "com.google.android.apps.docs.editors.docs" to listOf("documents", "word"),
        "com.google.android.apps.docs.editors.sheets" to listOf("spreadsheet", "excel"),
        "com.google.android.apps.docs.editors.slides" to listOf("presentation", "powerpoint"),
        "com.google.android.googlequicksearchbox" to listOf("search", "google app", "discover"),
        "com.google.android.apps.bard" to AI + listOf("bard"),
        "com.openai.chatgpt" to AI + listOf("gpt", "openai"),
        "com.anthropic.claude" to AI,
        "com.google.android.apps.translate" to listOf("translator", "translation", "language"),
        "com.google.android.apps.authenticator2" to listOf("2fa", "otp", "codes", "two factor"),
        "com.google.android.apps.subscriptions.red" to listOf("backup", "storage", "google one"),
        "com.google.ar.lens" to listOf("scan", "google lens", "identify"),
        "com.google.android.apps.adm" to listOf("find my phone", "find my device", "lost phone", "find hub"),
        "com.google.android.apps.safetyhub" to listOf("emergency", "sos", "safety"),
        "com.google.android.apps.wellbeing" to listOf("screen time", "wellbeing", "bedtime"),
        "com.google.android.projection.gearhead" to listOf("car", "android auto"),
        "com.google.android.apps.chromecast.app" to listOf("google home", "smart home", "lights", "cast", "nest"),
        "com.google.android.apps.tachyon" to listOf("video call", "duo", "meet"),
        "com.google.android.apps.meetings" to listOf("video call", "meeting"),
        "com.google.android.apps.fitness" to HEALTH,
        "com.fitbit.FitbitMobile" to HEALTH + listOf("sleep"),
        "com.sec.android.app.shealth" to HEALTH,
        "com.google.android.apps.healthdata" to HEALTH,
        "com.spotify.music" to MUSIC + listOf("podcasts"),
        "com.apple.android.music" to MUSIC,
        "com.shazam.android" to listOf("what song", "song", "identify music"),
        "com.google.android.apps.podcasts" to listOf("podcasts"),
        "com.netflix.mediaclient" to listOf("movies", "tv", "shows", "films"),
        "com.disney.disneyplus" to listOf("movies", "disney"),
        "com.amazon.avod.thirdpartyclient" to listOf("prime video", "movies"),
        "com.amazon.mShop.android.shopping" to listOf("shopping", "amazon"),
        "com.ubercab" to listOf("taxi", "ride", "cab"),
        "com.ubercab.eats" to listOf("food", "delivery", "takeaway"),
        "com.google.android.apps.magazines" to listOf("news", "google news"),
        "com.google.android.apps.weather" to WEATHER,
        "com.sec.android.daemonapp" to WEATHER,
        "com.google.android.apps.pixel.weather" to WEATHER,
        "com.google.android.apps.tips" to listOf("tips", "help"),
        "com.google.android.apps.pixel.support" to listOf("help", "support"),
        "com.android.settings" to SETTINGS,
        "com.google.android.apps.youtube.kids" to listOf("yt kids", "kids"),
        "com.linkedin.android" to listOf("jobs", "linked in"),
        "com.pinterest" to listOf("pins", "ideas"),
        "com.duolingo" to listOf("language", "learn"),
        "com.microsoft.teams" to listOf("teams", "meeting", "chat"),
        "us.zoom.videomeetings" to listOf("video call", "meeting"),
        "com.Slack" to listOf("work chat"),
    )

    private val BY_NAME: Map<String, List<String>> = mapOf(
        "camera" to CAMERA,
        "clock" to CLOCK,
        "calculator" to CALC,
        "phone" to PHONE,
        "messages" to SMS,
        "contacts" to CONTACTS,
        "gallery" to GALLERY,
        "photos" to GALLERY,
        "files" to FILES,
        "my files" to FILES,
        "file manager" to FILES,
        "weather" to WEATHER,
        "settings" to SETTINGS,
        "calendar" to CALENDAR,
        "notes" to NOTES,
        "recorder" to RECORDER,
        "voice recorder" to RECORDER,
        "music" to MUSIC,
        "email" to MAIL,
        "mail" to MAIL,
        "browser" to BROWSER,
        "internet" to BROWSER,
    )
}
