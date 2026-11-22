package com.hopla.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaylistTest {
    @Test fun xtreamLink() {
        val x = parseXtream("http://h.tv:8080/get.php?username=ab&password=c%26d&type=m3u_plus&output=ts")
        assertEquals(Xtream("http://h.tv:8080", "ab", "c&d"), x)
        assertEquals("http://h.tv:8080/live/ab/c%26d/42.ts", x!!.live(42))
    }

    @Test fun xtreamKeepsFolder() {
        assertEquals("http://h/iptv", parseXtream("http://h/iptv/get.php?username=a&password=b")!!.base)
    }

    @Test fun plainM3uIsNotXtream() {
        assertNull(parseXtream("https://iptv-org.github.io/iptv/countries/fr.m3u"))
        assertNull(parseXtream("not a link"))
    }

    @Test fun m3uParsing() {
        val m3u = """
            #EXTM3U
            #EXTINF:-1 tvg-id="a" group-title="News, FR",France 24, English
            http://x/1.m3u8
            #EXTVLCOPT:http-user-agent=foo
            #EXTINF:-1,Plain
            http://x/2.ts
            http://x/3.ts
        """.trimIndent()
        assertEquals(
            listOf(
                Channel("France 24, English", "News, FR", "http://x/1.m3u8", epgId = "a"),
                Channel("Plain", "", "http://x/2.ts"),
                Channel("http://x/3.ts", "", "http://x/3.ts"),
            ),
            parseM3u(m3u.lineSequence())
        )
    }
}

class XtreamParsersTest {
    private val x = Xtream("http://h:80", "u", "p")

    @Test fun m3uLogo() {
        val ch = parseM3u("#EXTINF:-1 tvg-logo=\"http://l/a.png\" group-title=\"News\",A\nhttp://x/1".lineSequence())
        assertEquals(Channel("A", "News", "http://x/1", "http://l/a.png"), ch.single())
    }

    @Test fun moviesAndCategories() {
        val cats = parseCategories("""[{"category_id":"5","category_name":"Action"}]""")
        val m = parseMovies("""[{"stream_id":12,"name":"Film","category_id":"5","stream_icon":"http://i/p.jpg","container_extension":"mkv","added":"1700000000","rating":"7.1"}]""", cats, x)
        assertEquals(Movie("12", "Film", "Action", "http://i/p.jpg", "http://h:80/movie/u/p/12.mkv", 1700000000, "7.1"), m.single())
    }

    @Test fun objectInsteadOfArray() {
        assertEquals(2, parseCategories("""{"a":{"category_id":"1","category_name":"X"},"b":{"category_id":"2","category_name":"Y"}}""").size)
    }

    @Test fun showInfoSortsEpisodes() {
        val json = """{"info":{"backdrop_path":["http://b/1.jpg"]},"episodes":{"2":[{"id":"21","episode_num":1,"season":2,"title":"S2E1","container_extension":"mp4"}],
            "1":[{"id":"12","episode_num":2,"season":1,"title":"S1E2"},{"id":"11","episode_num":1,"season":1,"title":"S1E1","info":{"plot":"P"}}]}}"""
        val info = parseShowInfo(json, x)
        assertEquals(listOf("11", "12", "21"), info.episodes.map { it.id })
        assertEquals("http://h:80/series/u/p/11.mp4", info.episodes[0].url)
        assertEquals("P", info.episodes[0].plot)
        assertEquals("http://b/1.jpg", info.backdrop)
    }

    @Test fun liveExtensionFollowsAllowedFormats() {
        assertEquals("ts", liveExtension(null))
        assertEquals("m3u8", liveExtension(org.json.JSONObject("""{"allowed_output_formats":["m3u8"]}""")))
        assertEquals("ts", liveExtension(org.json.JSONObject("""{"allowed_output_formats":["m3u8","ts"]}""")))
    }
}

class GuideAndSearchTest {
    private val xml = """<?xml version="1.0" encoding="UTF-8"?>
        <tv>
          <channel id="6ter.fr"><display-name>6ter</display-name></channel>
          <programme start="20261121200000 +0100" stop="20261121210000 +0100" channel="6ter.fr">
            <title lang="fr">Le journal</title><title lang="en">The news</title><desc lang="fr">Infos du soir.</desc>
          </programme>
          <programme start="20261121210000 +0100" stop="20261121223000 +0100" channel="6ter.fr"><title>Film</title></programme>
          <programme start="20261121200000 +0100" stop="20261121210000 +0100" channel="other.fr"><title>Ignored</title></programme>
          <programme start="20261101200000 +0100" stop="20261101210000 +0100" channel="6ter.fr"><title>Too old</title></programme>
        </tv>"""

    private fun parse(now: Long): Epg {
        val p = org.kxml2.io.KXmlParser()
        p.setInput(java.io.StringReader(xml))
        return parseXmltv(p, setOf("6ter.fr"), now)
    }

    @Test fun guideNowAndNext() {
        val now = java.text.SimpleDateFormat("yyyyMMddHHmm Z").parse("202611212030 +0100")!!.time
        val ch = Channel("6ter", "", "u", epgId = guideId("6ter.fr@SD"))
        val epg = parse(now)
        assertEquals("Le journal", epg.now(ch, now)?.title)       // first title wins
        assertEquals("Infos du soir.", epg.now(ch, now)?.desc)
        assertEquals("Film", epg.next(ch, now)?.title)
        assertEquals(1, epg.size)                                   // other.fr filtered, old programme dropped
    }

    @Test fun m3uHeaderAndTvgId() {
        assertEquals("http://g/a.xml", m3uGuideUrl("#EXTM3U url-tvg=\"http://g/a.xml,http://g/b.xml\""))
        assertEquals("", m3uGuideUrl("#EXTM3U"))
        val ch = parseM3u("#EXTINF:-1 tvg-id=\"TF1.fr@HD\",TF1\nhttp://x/1".lineSequence()).single()
        assertEquals("tf1.fr", ch.epgId)
    }

    @Test fun searchIgnoresAccentsCaseAndWordOrder() {
        val items = listOf("Terres d'Écosse", "France 24", "Écosse sauvage").map { it to searchKey(it) }
        assertEquals(listOf("Terres d'Écosse", "Écosse sauvage"), searchIn(items, "ecosse"))
        assertEquals(listOf("Écosse sauvage"), searchIn(items, "SAUVAGE écosse"))
        assertEquals(emptyList<String>(), searchIn(items, "e"))   // one letter is too vague
    }
}

class BrokenGuideTest {
    @Test fun badEntityKeepsTheRest() {
        val xml = """<tv><programme start="20261121200000 +0000" stop="20261121210000 +0000" channel="a"><title>Before</title></programme>
            <programme start="20261121210000 +0000" stop="20261121220000 +0000" channel="b"><title>Voyages & Saveurs</title></programme></tv>"""
        val p = org.kxml2.io.KXmlParser()
        p.setInput(java.io.StringReader(xml))
        val now = java.text.SimpleDateFormat("yyyyMMddHHmm Z").parse("202611212030 +0000")!!.time
        val epg = parseXmltv(p, emptySet(), now)
        assertEquals("Before", epg.now(Channel("A", "", "u", epgId = "a"), now)?.title)
    }
}

class CatalogueSortTest {
    private fun t(name: String, added: Long = 0, rating: Double = 0.0, year: Int = 0) = Tile(name, name, "", "", added, rating, year)

    @Test fun sorts() {
        val list = listOf(t("Écume", added = 3, rating = 6.0, year = 2001), t("avatar", added = 1, rating = 8.1, year = 0), t("Brazil", added = 2, rating = 0.0, year = 1985))
        assertEquals(listOf("Écume", "Brazil", "avatar"), sortTiles(list, Sort.ADDED).map { it.name })
        assertEquals(listOf("avatar", "Brazil", "Écume"), sortTiles(list, Sort.NAME).map { it.name })      // accents and case ignored
        assertEquals(listOf("Écume", "Brazil", "avatar"), sortTiles(list, Sort.YEAR).map { it.name })      // unknown year last
        assertEquals(listOf("avatar", "Écume", "Brazil"), sortTiles(list, Sort.RATING).map { it.name })    // unrated last
    }

    @Test fun onlySortsTheDataSupports() {
        assertEquals(listOf(Sort.ADDED, Sort.NAME), availableSorts(listOf(t("a"), t("b"))))
        assertEquals(Sort.entries, availableSorts(listOf(t("a", rating = 7.0, year = 2020))))
    }

    @Test fun yearsRatingsDurations() {
        val x = Xtream("http://h", "u", "p")
        val m = parseMovies("""[{"stream_id":1,"name":"Alpha (2019)","rating_5based":"3.5"},{"stream_id":2,"name":"Beta","releaseDate":"2021-05-03","rating":"7,2"}]""", emptyMap(), x)
        assertEquals(listOf(2019, 2021), m.map { it.year })
        assertEquals(listOf("7.0", "7.2"), m.map { it.rating })
        assertEquals("1 h 52", fmtDuration("01:52:00"))
        assertEquals("45 min", fmtDuration("45"))
        assertEquals("", fmtDuration(""))
    }
}

class CatalogStoreTest {
    @Test fun roundTrip() {
        val c = Catalog(
            listOf(Channel("Écran ✓", "News;Sports", "http://x/1", "http://l", "a.fr")),
            listOf(Movie("1", "Film", "Action", "p", "http://x/m/1.mkv", 1700000000, "7.5", 2019)),
            listOf(Show("2", "Série", "Docs", "c", "Un résumé\nsur deux lignes", "8", 5, 2021, "b", "Documentaire")),
            "http://g/xmltv.php",
        )
        val out = java.io.ByteArrayOutputStream()
        writeCatalog(c, out)
        assertEquals(c, readCatalog(java.io.ByteArrayInputStream(out.toByteArray())))
    }
}

class EpisodeTitleTest {
    @Test fun dropsShowNameAndCode() {
        assertEquals("Phoebe et Max", cleanEpisodeTitle("Les Thunderman - S03E01 - Phoebe et Max"))
        assertEquals("Le pilote", cleanEpisodeTitle("S1 E2: Le pilote"))
        assertEquals("Épisode 4", cleanEpisodeTitle("Épisode 4"))
        assertEquals("", cleanEpisodeTitle("Série - S02E10"))
    }
}

class StreamingParseTest {
    private val x = Xtream("http://h", "u", "p")

    @Test fun moviesFromArrayOrObjectWithNumbersAndNulls() {
        val asArray = """[{"stream_id":5,"name":"A","category_id":1,"added":1700000000,"rating":7.5,"stream_icon":null,"container_extension":"mkv"}]"""
        val asObject = """{"5":{"stream_id":"5","name":"A","category_id":"1","added":"1700000000","rating":"7.5","stream_icon":"","container_extension":"mkv"}}"""
        val cats = mapOf("1" to "Action")
        val expected = Movie("5", "A", "Action", "", "http://h/movie/u/p/5.mkv", 1700000000, "7.5", 0)
        assertEquals(listOf(expected), parseMovies(asArray, cats, x))
        assertEquals(listOf(expected), parseMovies(asObject, cats, x))
    }

    @Test fun liveAndShowsStream() {
        val live = parseLive("""[{"stream_id":1,"name":"TF1","category_id":"2","stream_icon":"l","epg_channel_id":"TF1.fr"}]""".reader(), mapOf("2" to "Info"), x, "ts")
        assertEquals(Channel("TF1", "Info", "http://h/live/u/p/1.ts", "l", "tf1.fr"), live.single())
        val shows = parseShows("""[{"series_id":3,"name":"S (2020)","category_id":"9","cover":"c","plot":"p","rating_5based":4,"backdrop_path":["http://b"],"genre":"Drame"}]""".reader(), emptyMap())
        assertEquals(Show("3", "S (2020)", "", "c", "p", "8.0", 0, 2020, "http://b", "Drame"), shows.single())
    }

    @Test fun providerTitlesAreCleaned() {
        assertEquals("Comment braquer une banque", cleanTitle("VOSTFR/HDCAM - Comment braquer une banque (2026)"))
        assertEquals("VOSTFR · HDCAM", titleTags("VOSTFR/HDCAM - Comment braquer une banque (2026)"))
        assertEquals("Dune", cleanTitle("[FR] Dune (2021)"))
        assertEquals("Dune", cleanTitle("|4K| Dune - 2021"))
        assertEquals("24 Hours with Gaspar", cleanTitle("24 Hours with Gaspar (2023)"))
        assertEquals("Frankenstein", cleanTitle("FR: Frankenstein"))
        assertEquals("1917", cleanTitle("1917"))
        assertEquals("Hors-série", cleanTitle("Hors-série"))
        assertEquals("", titleTags("Dune (2021)"))
        assertEquals("FR · NETFLIX · Autres", groupLabel("FR | NETFLIX | Uncategorized"))
    }

    @Test fun castAndTrailer() {
        val m = parseMovieInfo("""{"info":{"plot":"p","cast":"Timothée Chalamet,  Zendaya ,","director":"Denis Villeneuve","youtube_trailer":"n9xhJrPXop4"}}""")
        assertEquals("Timothée Chalamet, Zendaya", m.cast)
        assertEquals("Denis Villeneuve", m.director)
        assertEquals("https://www.youtube.com/watch?v=n9xhJrPXop4", m.trailer)
        assertEquals("", parseMovieInfo("""{"info":{"youtube_trailer":""}}""").trailer)
        assertEquals("https://youtu.be/x", parseMovieInfo("""{"info":{"trailer":"https://youtu.be/x","actors":"A"}}""").trailer)
    }

    @Test fun xtreamLinkFromThreeFields() {
        val x = parseXtream(xtreamLink(" panel.exemple.tv:8080/ ", "bob", "p@ss word"))!!
        assertEquals("http://panel.exemple.tv:8080", x.base)
        assertEquals("bob", x.user)
        assertEquals("p@ss word", x.pass)
        assertEquals("https://h.tv/iptv", parseXtream(xtreamLink("https://h.tv/iptv", "u", "p"))!!.base)
        // a whole link pasted into the server field is kept as it is
        val pasted = "http://h.tv:80/get.php?username=u&password=p&type=m3u_plus&output=mpegts"
        assertEquals(pasted, xtreamLink(pasted, "", ""))
    }

    @Test fun updateOnlyWhenNewer() {
        val json = """{"tag_name":"v0.3","assets":[{"name":"notes.txt","browser_download_url":"x"},
            {"name":"hopla-42.apk","browser_download_url":"https://github.com/o/hopla/releases/download/v0.3/hopla-42.apk"}]}"""
        val u = parseRelease(json, current = 1)!!
        assertEquals(42L, u.versionCode)
        assertEquals("0.3", u.name)
        assertEquals("https://github.com/o/hopla/releases/download/v0.3/hopla-42.apk", u.apk)
        assertNull(parseRelease(json, current = 42))
        assertNull(parseRelease("""{"tag_name":"v1","assets":[]}""", current = 1))
    }
}
