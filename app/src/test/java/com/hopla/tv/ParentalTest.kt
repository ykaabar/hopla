package com.hopla.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ParentalTest {
    private val catalog = Catalog(
        live = listOf(
            Channel("TF1", "France", "http://h/1"),
            Channel("Hot", "XXX | ADULTES", "http://h/2"),
            Channel("Sans groupe", "", "http://h/3"),
        ),
        movies = listOf(
            Movie("10", "Film", "Comédie", "", "http://h/m10", 0, ""),
            Movie("11", "Autre", "FOR ADULTS +18", "", "http://h/m11", 0, ""),
        ),
        shows = listOf(Show("20", "Série", "Kids", "", "", "", 0), Show("21", "S", "Erotic", "", "", "", 0)),
    )

    @Test fun adultNamesAreRecognised() {
        listOf("XXX", "ADULTES", "Adult 18+", "+18 VOD", "FR| PORN", "Érotique", "EROTIC", "Sexy nights", "Films (18)").forEach {
            assertTrue(it, isAdult(it))
        }
        listOf("France", "Sport", "Kids", "Essex News", "Top 180", "Documentaires", "Middlesex").forEach {
            assertFalse(it, isAdult(it))
        }
    }

    @Test fun adultCategoriesHiddenEverywhereByDefault() {
        val v = catalog.visible(GroupFilter())
        assertEquals(listOf("TF1", "Sans groupe"), v.live.map { it.name })
        assertEquals(listOf("10"), v.movies.map { it.id })
        assertEquals(listOf("20"), v.shows.map { it.id })
        assertEquals(setOf("http://h/2", "http://h/m11", "s:21"), v.hidden)
    }

    @Test fun nothingHiddenKeepsTheSameCatalogue() {
        val clean = Catalog(listOf(Channel("TF1", "France", "u")))
        assertSame(clean, clean.visible(GroupFilter()))
    }

    @Test fun toggleHidesAndShowsAgain() {
        val hidden = GroupFilter().toggle(Kind.LIVE, "France")
        assertTrue(hidden.hidden(Kind.LIVE, "France"))
        assertFalse("same name in another list stays", hidden.hidden(Kind.MOVIES, "France"))
        assertFalse(hidden.toggle(Kind.LIVE, "France").hidden(Kind.LIVE, "France"))
    }

    @Test fun adultCategoryCanBeShownOnPurposeAndHiddenAgain() {
        val shown = GroupFilter().toggle(Kind.MOVIES, "FOR ADULTS +18")
        assertFalse(shown.hidden(Kind.MOVIES, "FOR ADULTS +18"))
        assertTrue("other adult categories stay hidden", shown.hidden(Kind.MOVIES, "XXX"))
        assertTrue(shown.toggle(Kind.MOVIES, "FOR ADULTS +18").hidden(Kind.MOVIES, "FOR ADULTS +18"))
    }

    @Test fun blankCategoryIsNeverHidden() {
        assertFalse(GroupFilter(hide = setOf("l:")).hidden(Kind.LIVE, ""))
    }

    @Test fun groupsCountInPlaylistOrder() {
        assertEquals(listOf("France" to 1, "XXX | ADULTES" to 1), catalog.groups(Kind.LIVE))
    }
}
