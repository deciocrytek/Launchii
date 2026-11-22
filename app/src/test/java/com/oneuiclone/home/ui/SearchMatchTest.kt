package com.oneuiclone.home.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchMatchTest {
    private val apps = listOf(
        "Chrome" to "com.android.chrome",
        "WhatsApp" to "com.whatsapp",
        "Instagram" to "com.instagram.android",
        "YouTube" to "com.google.android.youtube",
        "YouTube Music" to "com.google.android.apps.youtube.music",
        "Wallet" to "com.google.android.apps.walletnfcrel",
        "Photos" to "com.google.android.apps.photos",
        "Clock" to "com.google.android.deskclock",
        "Calculator" to "com.google.android.calculator",
        "Spotify" to "com.spotify.music",
        "Slack" to "com.Slack",
        "Uber Eats" to "com.ubercab.eats",
        "Gmail" to "com.google.android.gm",
        "Netflix" to "com.netflix.mediaclient",
        "Settings" to "com.android.settings",
        "Waze" to "com.waze",
        "Google Pay" to "com.google.android.apps.nbu.paisa.user",
        "Phone" to "com.google.android.dialer",
        "Calendar" to "com.google.android.calendar",
    ).map { (l, p) -> SearchMatch.Entry(l, l, p) }

    private fun find(q: String) = apps.mapNotNull { e -> SearchMatch.tier(e, SearchMatch.normalize(q)).takeIf { it >= 0 }?.let { e.item to it } }
        .sortedBy { it.second }.map { it.first }

    private fun tierOf(app: String, q: String) = SearchMatch.tier(apps.first { it.item == app }, SearchMatch.normalize(q))

    @Test fun nameStart() = assertEquals(SearchMatch.START, tierOf("Chrome", "chr"))
    @Test fun capitalsStartWords() {
        assertEquals(SearchMatch.WORD, tierOf("YouTube", "tube"))
        assertEquals(SearchMatch.WORD, tierOf("YouTube", "yt"))
        assertEquals(SearchMatch.WORD, tierOf("YouTube Music", "ytm"))
        assertEquals(SearchMatch.WORD, tierOf("WhatsApp", "app"))
    }
    @Test fun spacedWords() = assertEquals(SearchMatch.WORD, tierOf("Google Pay", "goo pay"))
    @Test fun otherNames() {
        assertEquals(SearchMatch.ALIAS, tierOf("Photos", "gallery"))
        assertEquals(SearchMatch.ALIAS, tierOf("Wallet", "pay"))
        assertEquals(SearchMatch.ALIAS, tierOf("Clock", "alarm"))
        assertEquals(SearchMatch.ALIAS, tierOf("Calculator", "maths"))
        assertEquals(SearchMatch.ALIAS, tierOf("Gmail", "email"))
    }
    @Test fun typos() {
        assertEquals(SearchMatch.TYPO, tierOf("Instagram", "instgram"))
        assertEquals(SearchMatch.TYPO, tierOf("Spotify", "spotfy"))
        assertEquals(SearchMatch.TYPO, tierOf("Gmail", "gmial"))
        assertEquals(SearchMatch.TYPO, tierOf("WhatsApp", "whatapp"))
        assertEquals(SearchMatch.TYPO, tierOf("Netflix", "netflx"))
    }
    @Test fun noFalseTypos() {
        assertEquals(-1, tierOf("Slack", "black"))      // first letter must be right below 6 letters
        assertEquals(-1, tierOf("Waze", "wav"))         // no typos below 4 letters
        assertEquals(-1, tierOf("Chrome", "xyzzy"))
        // a short query that starts another word, or is one letter off another name, is not a typo
        assertEquals(-1, tierOf("Phone", "calc"))
        assertEquals(-1, tierOf("Calendar", "calc"))
        assertEquals(listOf("Calculator"), find("calc"))
    }
    @Test fun inside() {
        assertEquals(SearchMatch.INSIDE, tierOf("Instagram", "gram"))
        assertEquals(SearchMatch.INSIDE, tierOf("Uber Eats", "ubereats"))
        assertEquals(SearchMatch.INSIDE, tierOf("Netflix", "mediaclient"))   // package word
    }
    @Test fun accentsAndCase() = assertEquals(SearchMatch.START, tierOf("Settings", "SÉT"))
    @Test fun exactBeatsTypo() {
        val r = find("wall")
        assertEquals("Wallet", r.first())
    }
    @Test fun ytFindsBothYouTubes() = assertEquals(listOf("YouTube", "YouTube Music"), find("yt"))
    @Test fun distance() {
        assertEquals(1, SearchMatch.distance("instgram", "instagram"))
        assertEquals(1, SearchMatch.distance("gmial", "gmail"))
        assertEquals(2, SearchMatch.distance("abc", "cab", 1))
    }
}

class SearchAnswersTest {
    @Test fun sums() {
        assertEquals("96", SearchAnswers.calc("12*8")?.value)
        assertEquals("96", SearchAnswers.calc("12 x 8")?.value)
        assertEquals("49", SearchAnswers.calc("(3+4)^2")?.value)
        assertEquals("2.5", SearchAnswers.calc("5/2")?.value)
        assertEquals("10", SearchAnswers.calc("20%*50")?.value)
        assertEquals("-1", SearchAnswers.calc("2-3")?.value)
        assertEquals("0.3333333333", SearchAnswers.calc("1/3")?.value)
    }
    @Test fun percentOf() = assertEquals("12", SearchAnswers.calc("15% of 80")?.value)
    @Test fun conversions() {
        assertEquals("3.107 mi", SearchAnswers.calc("5 km in miles")?.value)
        assertEquals("22.046 lb", SearchAnswers.calc("10 kg to lbs")?.value)
        assertEquals("21.111 °C", SearchAnswers.calc("70 f in c")?.value)
        assertNull(SearchAnswers.calc("5 km in kg"))
    }
    @Test fun notSums() {
        assertNull(SearchAnswers.calc("2024"))
        assertNull(SearchAnswers.calc("-3"))
        assertNull(SearchAnswers.calc("spotify"))
        assertNull(SearchAnswers.calc("12*"))
        assertNull(SearchAnswers.calc("1/0"))
    }
    @Test fun settings() {
        assertEquals("Wi‑Fi", SearchAnswers.setting("wifi")?.title)
        assertEquals("Wi‑Fi", SearchAnswers.setting("wif")?.title)
        assertEquals("Bluetooth", SearchAnswers.setting("bt")?.title)
        assertEquals("Dark theme", SearchAnswers.setting("dark")?.title)
        assertEquals("Battery", SearchAnswers.setting("battery")?.title)
        assertEquals("Hotspot & tethering", SearchAnswers.setting("hots")?.title)
        assertNull(SearchAnswers.setting("w"))
        assertNull(SearchAnswers.setting("spotify"))
        assertTrue(SearchAnswers.SETTINGS.all { it.actions.isNotEmpty() })
    }
    @Test fun mayBeApp() {
        assertTrue(SearchAnswers.mayBeApp("strava"))
        assertTrue(SearchAnswers.mayBeApp("Clash Royale"))
        assertTrue(SearchAnswers.mayBeApp("Clash of Clans"))
        assertTrue(SearchAnswers.mayBeApp("1.1.1.1"))
        assertTrue(SearchAnswers.mayBeApp("Disney+"))
        assertFalse(SearchAnswers.mayBeApp("what is ozenpic?"))
        assertFalse(SearchAnswers.mayBeApp("what is ozempic"))
        assertFalse(SearchAnswers.mayBeApp("weather tomorrow"))
        assertFalse(SearchAnswers.mayBeApp("how to tie a tie"))
        assertFalse(SearchAnswers.mayBeApp("2026"))
        assertFalse(SearchAnswers.mayBeApp("ab"))
    }
    @Test fun suggestsApp() {
        assertTrue(SearchAnswers.suggestsApp("strava", listOf("strava", "strava login", "strava app")))
        assertTrue(SearchAnswers.suggestsApp("duolingo", listOf("duolingo", "duolingo apk")))
        assertTrue(SearchAnswers.suggestsApp("spotify", listOf("spotify play store")))
        assertFalse(SearchAnswers.suggestsApp("ozempic", listOf("ozempic", "ozempic side effects", "ozempic face")))
        assertFalse(SearchAnswers.suggestsApp("what is ozenpic?", listOf("what is ozempic app")))
        assertFalse(SearchAnswers.suggestsApp("whats", listOf("whatsapp")))
        assertFalse(SearchAnswers.suggestsApp("apple", listOf("apple store", "apple pay")))
    }
    @Test fun parsePlayHero() {
        val heroHtml = """
            <div><a href="/store/apps/details?id=com.duolingo" aria-label="Duolingo: Learn &amp; Play" class="Qfxief"></a>
            <div class="Y4A2mc"><img src="https://example.com/icon.png" alt="Icon image" itemprop="image" />
            <div class="vWM94c">Duolingo: Learn &amp; Play</div></div></div>
        """.trimIndent()
        assertEquals(SearchAnswers.PlayApp("Duolingo: Learn & Play", "com.duolingo"), SearchAnswers.parsePlayHero(heroHtml))

        val fallbackHtml = """
            <a href="/store/apps/details?id=com.strava"></a>
            <div class="vWM94c">Strava: Run, Bike &amp; Walk</div>
        """.trimIndent()
        assertEquals(SearchAnswers.PlayApp("Strava: Run, Bike & Walk", "com.strava"), SearchAnswers.parsePlayHero(fallbackHtml))

        val listOnlyHtml = """
            <a class="Si6A0c Gy4nib" href="/store/apps/details?id=com.example.ozempic">
            <span class="DdYX5">GLP-1 Tracker: Ozempic</span></a>
        """.trimIndent()
        assertNull(SearchAnswers.parsePlayHero(listOnlyHtml))
    }
    @Test fun shouldEnableAutoCorrect() {
        assertFalse(SearchAnswers.shouldEnableAutoCorrect(""))
        assertFalse(SearchAnswers.shouldEnableAutoCorrect("strava"))
        assertFalse(SearchAnswers.shouldEnableAutoCorrect("strava "))
        assertFalse(SearchAnswers.shouldEnableAutoCorrect("clash royale"))
        assertFalse(SearchAnswers.shouldEnableAutoCorrect("what"))
        assertTrue(SearchAnswers.shouldEnableAutoCorrect("what "))
        assertTrue(SearchAnswers.shouldEnableAutoCorrect("how "))
        assertTrue(SearchAnswers.shouldEnableAutoCorrect("clash royale "))
        assertTrue(SearchAnswers.shouldEnableAutoCorrect("best coffee "))
        assertTrue(SearchAnswers.shouldEnableAutoCorrect("what is ozenpic"))
    }
}
