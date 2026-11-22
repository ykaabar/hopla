package com.hopla.tv

import android.content.SharedPreferences
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One film or series as the catalogue screens see it. [key] is "m:<id>" or "s:<id>". */
data class Tile(
    val key: String, val name: String, val group: String, val poster: String,
    val added: Long, val rating: Double, val year: Int, val progress: Float = 0f, val tags: String = "",
) {
    /** Accent- and case-free name, computed once: sorting 10 000 titles must not normalise on every comparison. */
    val sortName: String = searchKey(name)
}

/** What the spotlight shows on top of the tile itself; fetched lazily for the focused title. */
data class Spot(val backdrop: String, val plot: String, val genre: String, val duration: String)

enum class Sort(val label: String) { ADDED("Ajout récent"), NAME("Nom"), YEAR("Année"), RATING("Note") }

fun sortTiles(list: List<Tile>, sort: Sort): List<Tile> = when (sort) {
    Sort.ADDED -> list.sortedByDescending { it.added }
    Sort.NAME -> list.sortedBy { it.sortName }
    // Unknown years and ratings go last, not first.
    Sort.YEAR -> list.sortedWith(compareByDescending<Tile> { it.year }.thenBy { it.sortName })
    Sort.RATING -> list.sortedWith(compareByDescending<Tile> { it.rating }.thenBy { it.sortName })
}

/** Only offer the sorts the provider's data can support. */
fun availableSorts(tiles: List<Tile>) = Sort.entries.filter {
    when (it) {
        Sort.YEAR -> tiles.any { t -> t.year > 0 }
        Sort.RATING -> tiles.any { t -> t.rating > 0 }
        else -> true
    }
}

/** "01:52:00" or "112" (minutes) -> "1 h 52". */
fun fmtDuration(raw: String): String {
    val parts = raw.trim().split(':').mapNotNull { it.toIntOrNull() }
    val minutes = when (parts.size) {
        3, 2 -> parts[0] * 60 + parts[1]
        1 -> parts[0]
        else -> return ""
    }
    if (minutes <= 0) return ""
    return if (minutes >= 60) "${minutes / 60} h ${"%02d".format(minutes % 60)}" else "$minutes min"
}

fun ratingLabel(r: Double) = if (r > 0) "%.1f".format(r).replace('.', ',') else ""

private const val FEATURED = "À la une"

/** Everything heavy about the screen, built off the main thread (10 000 titles froze the UI before). */
private class Shelves(val list: List<Pair<String, List<Tile>>>)

private fun buildShelves(tiles: List<Tile>, resume: List<Tile>, sort: Sort) = Shelves(buildList {
    if (resume.isNotEmpty()) add("Reprendre" to resume)
    add("Ajoutés récemment" to sortTiles(tiles, Sort.ADDED).take(20))
    val rated = tiles.filter { it.rating > 0 }
    if (rated.isNotEmpty()) add("Les mieux notés" to sortTiles(rated, Sort.RATING).take(20))
    tiles.groupBy { it.group }.filterKeys { it.isNotBlank() }.forEach { (g, list) -> add(groupLabel(g) to sortTiles(list, Sort.ADDED).take(20)) }
})

/**
 * Films / Séries: a cinematic spotlight for whatever is focused, then either shelves ("À la une") or the
 * full, sortable grid of one category.
 */
@Composable
fun VodHub(
    tiles: List<Tile>, resume: List<Tile>, prefs: SharedPreferences, sortKey: String, searchHint: String,
    spotOf: suspend (Tile) -> Spot?, nav: Nav, onOpen: (Tile) -> Unit,
) {
    val groups = remember(tiles) { listOf(FEATURED) + tiles.mapTo(LinkedHashSet()) { it.group }.filter { it.isNotBlank() } }
    var group by rememberSaveable { mutableStateOf(FEATURED) }
    val sorts = remember(tiles) { availableSorts(tiles) }
    var sort by remember { mutableStateOf(Sort.entries.firstOrNull { it.name == prefs.getString(sortKey, null) }?.takeIf { it in sorts } ?: Sort.ADDED) }
    var query by rememberSaveable { mutableStateOf("") }
    val searching = isSearching(query)
    val keyed = remember(tiles) { tiles.map { it to it.sortName } }
    // The curated shelves keep their own order: a name sort made "À la une" start with "24 Hours…".
    val shelves by produceState<Shelves?>(null, tiles, resume) { value = withContext(Dispatchers.Default) { buildShelves(tiles, resume, Sort.ADDED) } }
    // One category (or the search results) at a time; categories change as the menu focus moves, so wait a beat.
    val grid by produceState<List<Tile>?>(null, tiles, group, sort, query) {
        value = null
        if (isSearching(query)) { delay(200); value = withContext(Dispatchers.Default) { sortTiles(searchIn(keyed, query, limit = 500), sort) } }
        else if (group != FEATURED) { delay(250); value = withContext(Dispatchers.Default) { sortTiles(tiles.filter { it.group == group }, sort) } }
    }
    val showShelves = !searching && group == FEATURED
    var focused by remember { mutableStateOf<Tile?>(null) }
    // The poster that was open when the household left for its page; focus goes back to it on return.
    var lastKey by rememberSaveable { mutableStateOf<String?>(null) }
    var restoring by remember { mutableStateOf(lastKey != null) }
    val restore = remember { FocusRequester() }
    val open: (Tile) -> Unit = { t -> lastKey = t.key; onOpen(t) }
    // Changing category, sort or search shows that list's first title, never the one left behind in another list.
    LaunchedEffect(shelves, grid, showShelves) {
        if (restoring) { focused = tiles.firstOrNull { it.key == lastKey } ?: focused; return@LaunchedEffect }
        focused = if (showShelves) shelves?.list?.firstOrNull()?.second?.firstOrNull() else grid?.firstOrNull()
    }
    val spot by produceState<Pair<String, Spot>?>(null, focused) {
        val t = focused ?: return@produceState
        delay(400) // don't fetch details for every poster the focus flies over
        withContext(Dispatchers.IO) { runCatching { spotOf(t) }.getOrNull() }?.let { value = t.key to it }
    }
    val details = spot?.takeIf { it.first == focused?.key }?.second
    // The backdrop only changes once the focus rests (when the details arrive): cross-fading large images on
    // every key press while flying through a row was the main cost of each frame.
    var backdrop by remember { mutableStateOf("") }
    LaunchedEffect(details) { if (details != null) backdrop = details.backdrop.ifBlank { focused?.poster.orEmpty() } }

    val menu = remember { FocusRequester() }
    val content = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val ready = if (showShelves) shelves != null else grid != null
    LaunchedEffect(ready) {
        if (!ready) return@LaunchedEffect
        if (!(restoring && restore.focusSoon())) content.focusSoon()
        restoring = false
    }
    // Back in the shelves or grid opens the menu (categories, search); Back there returns here.
    val toMenu = Modifier.onPreviewKeyEvent {
        if (it.key == Key.Back) { if (it.type == KeyEventType.KeyUp) menu.requestFocus(); true } else false
    }

    // Plein cadre: the focused title's artwork fills the TV; its words sit low on the left and the posters run
    // along the bottom. The picture only changes once the focus rests (see backdrop above).
    Box(Modifier.fillMaxSize()) {
        FullBackdrop(backdrop)
        Row(Modifier.fillMaxSize().padding(18.dp)) {
            SideMenu(nav, query, { query = it }, searchHint, groups, group, { group = it }, menu, toContent = { scope.launch { content.focusSoon() } })
            Spacer(Modifier.width(26.dp))
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (searching) "« ${query.trim()} »" else groupLabel(group), Modifier.clip(CircleShape).background(Color.White.copy(alpha = 0.14f))
                        .padding(horizontal = 12.dp, vertical = 5.dp), color = Color.White, fontFamily = Hopla.Body, fontWeight = FontWeight.Bold, fontSize = 12.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    grid?.takeIf { !showShelves }?.let {
                        Text("${it.size} titres", Modifier.padding(start = 10.dp), color = Color.White.copy(alpha = 0.75f), fontFamily = Hopla.Body, fontSize = 11.5.sp)
                    }
                    Spacer(Modifier.weight(1f))
                    // Sorting only means something inside one category or a search, not on the curated shelves.
                    if (!showShelves && sorts.size > 1) SortChips(sorts, sort, { sort = it; prefs.edit().putString(sortKey, it.name).apply() })
                }
                Spacer(Modifier.weight(1f))
                Spotlight(focused, details)
                Spacer(Modifier.height(14.dp))
                Box(Modifier.fillMaxWidth().height(232.dp)) {
                    val list = grid
                    when {
                        showShelves && shelves != null -> ShelfList(shelves!!.list, content, lastKey, restore, toMenu, onFocus = { focused = it }, onOpen = open)
                        !showShelves && list != null && list.isEmpty() -> Text(if (searching) "Aucun titre ne correspond." else "Cette catégorie est vide.",
                            Modifier.padding(top = 24.dp), color = Hopla.TextDim, fontFamily = Hopla.Body, fontSize = 13.sp)
                        !showShelves && list != null -> Grid(list, content, lastKey, restore, toMenu, onFocus = { focused = it }, onOpen = open)
                        else -> Text("Chargement…", Modifier.padding(top = 24.dp), color = Hopla.TextDim, fontFamily = Hopla.Body, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun Spotlight(t: Tile?, spot: Spot?) {
    Column(Modifier.width(640.dp), verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.Bottom)) {
        if (t == null) return@Column
        Text(t.name, color = Color.White, fontFamily = Hopla.Display, fontWeight = FontWeight.ExtraBold, fontSize = 42.sp, lineHeight = 43.sp,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (t.rating > 0) {
                Icon(Icons.Star, "Note", Modifier.size(12.dp), tint = Hopla.Accent)
                Spacer(Modifier.width(4.dp))
                Text(ratingLabel(t.rating), color = Hopla.Accent, fontFamily = Hopla.Body, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Spacer(Modifier.width(10.dp))
            }
            val meta = listOfNotNull(t.year.takeIf { it > 0 }?.toString(), spot?.genre?.ifBlank { null }, spot?.duration?.ifBlank { null }, t.tags.ifBlank { null })
            Text(meta.joinToString("  ·  "), color = Color.White, fontFamily = Hopla.Body, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        // Reserve two lines so the layout doesn't jump while the plot loads.
        Text(spot?.plot.orEmpty(), Modifier.height(57.dp), color = Color.White.copy(alpha = 0.84f), fontFamily = Hopla.Body, fontSize = 13.5.sp, lineHeight = 19.sp,
            maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

private val PosterW = 105.dp
private val PosterH = 158.dp

@Composable
private fun ShelfList(
    shelves: List<Pair<String, List<Tile>>>, first: FocusRequester, restoreKey: String?, restore: FocusRequester, keys: Modifier,
    onFocus: (Tile) -> Unit, onOpen: (Tile) -> Unit,
) {
    // Rows settle at the top of the list as the focus moves down, instead of creeping; the margin keeps the
    // row's title in view above the focused poster.
    Pivot(0f, 30.dp) {
        LazyColumn(keys.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(top = 10.dp, bottom = 300.dp)) {
            shelves.forEachIndexed { row, (title, list) ->
                item(key = "$row:$title") {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        SectionTitle(title)
                        Pivot(0f, 8.dp) {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(start = 4.dp, end = 400.dp, top = 6.dp, bottom = 6.dp)) {
                                tiles(list, if (row == 0) first else null, restoreKey, restore, onFocus, onOpen)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun LazyListScope.tiles(
    list: List<Tile>, first: FocusRequester?, restoreKey: String?, restore: FocusRequester, onFocus: (Tile) -> Unit, onOpen: (Tile) -> Unit,
) {
    // No item keys: providers sometimes list the same title twice, and a repeated key crashes a lazy list.
    items(list.size) { i ->
        val t = list[i]
        PosterCard(t.name, t.poster, onClick = { onOpen(t) },
            modifier = (if (i == 0 && first != null) Modifier.focusRequester(first) else Modifier)
                .then(if (t.key == restoreKey) Modifier.focusRequester(restore) else Modifier),
            progress = t.progress, onFocus = { onFocus(t) }, width = PosterW, height = PosterH, showTitle = false)
    }
}

@Composable
private fun Grid(list: List<Tile>, first: FocusRequester, restoreKey: String?, restore: FocusRequester, keys: Modifier, onFocus: (Tile) -> Unit, onOpen: (Tile) -> Unit) {
    val state = rememberLazyGridState()
    // A new category starts at the top; coming back from a poster keeps the place (restored with the state).
    var shown by remember { mutableStateOf<List<Tile>?>(null) }
    LaunchedEffect(list) { if (shown != null) state.scrollToItem(0); shown = list }
    Pivot(0f, 12.dp) {
        LazyVerticalGrid(
            GridCells.Fixed(7), keys.fillMaxSize(), state = state,
            horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(top = 12.dp, bottom = 300.dp, start = 4.dp, end = 4.dp),
        ) {
            items(list.size) { i ->
                val t = list[i]
                PosterCard(t.name, t.poster, onClick = { onOpen(t) },
                    modifier = (if (i == 0) Modifier.focusRequester(first) else Modifier)
                        .then(if (t.key == restoreKey) Modifier.focusRequester(restore) else Modifier),
                    progress = t.progress, onFocus = { onFocus(t) }, width = PosterW, height = PosterH, showTitle = false)
            }
        }
    }
}
