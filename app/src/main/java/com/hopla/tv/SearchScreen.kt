package com.hopla.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private val FOLD: Map<Char, String> = buildMap {
    listOf("àáâãäåāăą" to "a", "çćč" to "c", "ď" to "d", "èéêëēėęě" to "e", "ìíîïīį" to "i",
        "ł" to "l", "ñńň" to "n", "òóôõöøōő" to "o", "ùúûüūůűų" to "u", "ýÿ" to "y",
        "žźż" to "z", "śšş" to "s", "ţť" to "t", "ŕř" to "r", "œ" to "oe", "æ" to "ae", "ß" to "ss",
    ).forEach { (from, to) -> from.forEach { put(it, to) } }
}

/**
 * "Écosse" and "ecosse" must match: lower case, accents removed. A table lookup, not Normalizer + regex:
 * 161 000 film names have to be keyed in a second, not a minute.
 */
fun searchKey(s: String): String {
    val out = StringBuilder(s.length)
    for (ch in s) {
        val c = ch.lowercaseChar()
        if (c.code < 128) out.append(c) else out.append(FOLD[c] ?: c.toString())
    }
    return out.toString().trim()
}

/** Matches every word of the query, in any order. */
fun <T> searchIn(items: List<Pair<T, String>>, query: String, limit: Int = 30): List<T> {
    val words = searchKey(query).split(' ').filter { it.isNotEmpty() }
    if (words.isEmpty() || words.sumOf { it.length } < 2) return emptyList()
    return items.asSequence().filter { (_, key) -> words.all { it in key } }.map { it.first }.take(limit).toList()
}

/** True once a query is long enough to search: the screens switch to results from there. */
fun isSearching(query: String) = searchKey(query).replace(" ", "").length >= 2

/** Results of the home screen's search, across channels, films and series. */
@Composable
fun SearchResults(
    catalog: Catalog, numbers: Map<Channel, Int>, query: String, first: FocusRequester,
    onChannel: (Channel) -> Unit, onMovie: (Movie) -> Unit, onShow: (Show) -> Unit,
) {
    // Normalising 30 000 names and scanning them happens off the main thread.
    val index by produceState<Triple<List<Pair<Channel, String>>, List<Pair<Movie, String>>, List<Pair<Show, String>>>?>(null, catalog) {
        value = withContext(Dispatchers.Default) {
            Triple(catalog.live.map { it to searchKey(it.name) }, catalog.movies.map { it to searchKey(it.name) }, catalog.shows.map { it to searchKey(it.name) })
        }
    }
    val found by produceState(Triple(emptyList<Channel>(), emptyList<Movie>(), emptyList<Show>()), query, index) {
        val i = index ?: return@produceState
        delay(200) // don't search on every keystroke
        value = withContext(Dispatchers.Default) { Triple(searchIn(i.first, query), searchIn(i.second, query), searchIn(i.third, query)) }
    }
    val (live, movies, shows) = found
    if (live.isEmpty() && movies.isEmpty() && shows.isEmpty()) {
        Text("Aucun résultat pour « ${query.trim()} ».", Modifier.padding(top = 24.dp), color = Hopla.TextDim, fontFamily = Hopla.Body, fontSize = 13.sp)
        return
    }
    Box(Modifier.fillMaxSize()) {
        Pivot(0f, 30.dp) {
            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(18.dp), contentPadding = PaddingValues(bottom = 300.dp)) {
                if (live.isNotEmpty()) item {
                    Shelf("Chaînes", Modifier.focusRequester(first)) { items(live) { ch -> ChannelCard(numbers[ch] ?: 0, ch, onClick = { onChannel(ch) }) } }
                }
                if (movies.isNotEmpty()) item {
                    Shelf("Films", if (live.isEmpty()) Modifier.focusRequester(first) else Modifier) { items(movies) { m -> PosterCard(m.title, m.poster, onClick = { onMovie(m) }) } }
                }
                if (shows.isNotEmpty()) item {
                    Shelf("Séries", if (live.isEmpty() && movies.isEmpty()) Modifier.focusRequester(first) else Modifier) { items(shows) { s -> PosterCard(s.title, s.cover, onClick = { onShow(s) }) } }
                }
            }
        }
    }
}
