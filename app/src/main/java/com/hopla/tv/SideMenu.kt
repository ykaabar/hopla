package com.hopla.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Where the household can go from the "hopla." button. */
class Nav(
    val current: Section, val sections: List<Section>, val go: (Section) -> Unit, val settings: () -> Unit, val refresh: () -> Unit = {},
    /** Opens the category manager (hide categories, parental code). */
    val categories: () -> Unit = {},
    /** Label and action of "Mettre à jour Hopla" when a newer version is published, else null. */
    val update: Pair<String, () -> Unit>? = null,
)

/**
 * The one side menu of the app: the "hopla." button switches section (Accueil, Direct, Films, Séries,
 * Paramètres), a search box searches the current section, and the section's categories are listed below,
 * one shown at a time on the right.
 *
 * Folded, it is a thin strip (one focus target, [handle]); when focus arrives (Left from the content, or a
 * request on [handle]) it opens over the content on the current category, and folds again when focus leaves.
 */
@Composable
fun SideMenu(
    nav: Nav,
    query: String, onQuery: (String) -> Unit, searchHint: String,
    categories: List<String>, selected: String?, onSelect: (String) -> Unit,
    handle: FocusRequester, toContent: () -> Unit,
    modifier: Modifier = Modifier,
    pinned: Set<String> = emptySet(), onPin: ((String) -> Unit)? = null,
) {
    var open by remember { mutableStateOf(false) }
    var switcher by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var handleFocused by remember { mutableStateOf(false) }
    var inside by remember { mutableStateOf(false) }
    var entry by remember { mutableIntStateOf(0) }
    val born = remember { android.os.SystemClock.uptimeMillis() }
    val current = remember { FocusRequester() }
    val search = remember { FocusRequester() }
    val searchEdit = remember { FocusRequester() }
    val firstSection = remember { FocusRequester() }
    val top = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val list = rememberLazyListState()
    val keyboard = LocalSoftwareKeyboardController.current

    // Every arrival on the strip (Left from the content, Back, or Left inside the open menu onto the strip
    // underneath) sends the focus into the open menu, on the current category.
    LaunchedEffect(entry) {
        if (entry == 0) return@LaunchedEffect
        val i = categories.indexOf(selected)
        if (i >= 0) { list.scrollToItem(i); current.focusSoon() } else search.focusSoon()
    }
    LaunchedEffect(switcher) { if (switcher) firstSection.focusSoon() }
    // Fold once the focus is really gone (to the content): checked a beat later, because moving from the strip
    // into the menu briefly leaves both unfocused.
    LaunchedEffect(open, inside, handleFocused) {
        if (open && !inside && !handleFocused) {
            delay(150)
            open = false; switcher = false; editing = false
        }
    }
    // Focus alone does not raise the Fire TV keyboard: ask for it, or OK had to be pressed twice.
    // The text field has its own requester: sharing the button's, the request landed on the button just before it
    // was replaced, the focus fell onto the first category and the keyboard never opened.
    LaunchedEffect(editing) { if (editing && searchEdit.focusSoon()) keyboard?.show() }

    Box(modifier.width(63.dp).fillMaxHeight().zIndex(1f)) {
        // Folded strip: a single focus target, so Left from anywhere in the content lands here and opens the menu.
        Column(
            Modifier.fillMaxSize().clip(RoundedCornerShape(21.dp)).background(Hopla.Glass)
                .focusRequester(handle)
                // Never make this unfocusable while it holds focus: Compose then drops focus onto the first item
                // of the screen. It stays focusable and hands the focus on instead.
                // A screen that has just appeared hands its first focus to the first focusable, which is this
                // strip: that is not the household asking for the menu, so it does not open then.
                .onFocusChanged {
                    handleFocused = it.isFocused
                    // While the search field takes over from its button the focus passes through here: that is not
                    // the household reopening the menu (it would put the focus back on the current category).
                    if (it.isFocused && !editing && android.os.SystemClock.uptimeMillis() - born > 800) { open = true; entry++ }
                }
                .focusable()
                .padding(vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row {
                Text("h", color = Hopla.Text, fontFamily = Hopla.Display, fontWeight = FontWeight.ExtraBold, fontSize = 25.sp)
                Text(".", color = Hopla.Accent, fontFamily = Hopla.Display, fontWeight = FontWeight.ExtraBold, fontSize = 25.sp)
            }
            Icon(nav.current.icon, nav.current.label, Modifier.size(18.dp), tint = Hopla.Accent)
            Icon(Icons.Search, "Rechercher", Modifier.size(18.dp), tint = if (query.isNotBlank()) Hopla.Accent else Hopla.TextDim)
        }

        // Drawn over the content, wider than the strip's slot: nothing in the layout moves when it opens.
        if (open) Column(
            Modifier.wrapContentWidth(Alignment.Start, unbounded = true).width(250.dp).fillMaxHeight()
                .onFocusChanged { inside = it.hasFocus }
                .focusGroup()
                .onPreviewKeyEvent {
                    // Back inside the menu: close the section list first, else go back to the content.
                    if (it.key == Key.Back && !editing) {
                        if (it.type == KeyEventType.KeyUp) { if (switcher) switcher = false else toContent() }
                        true
                    } else false
                }
                .shadow(24.dp, RoundedCornerShape(21.dp)).clip(RoundedCornerShape(21.dp)).background(Hopla.GlassSolid)
                .padding(vertical = 16.dp, horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            FocusRow(onClick = { switcher = !switcher }, shape = RoundedCornerShape(13.dp), modifier = Modifier.fillMaxWidth().height(44.dp)) { f ->
                Spacer(Modifier.width(10.dp))
                Text("hopla", color = if (f) Hopla.OnAccent else Hopla.Text, fontFamily = Hopla.Display, fontWeight = FontWeight.ExtraBold, fontSize = 23.sp)
                Text(".", color = if (f) Hopla.OnAccent else Hopla.Accent, fontFamily = Hopla.Display, fontWeight = FontWeight.ExtraBold, fontSize = 23.sp)
                Spacer(Modifier.width(10.dp))
                Text(nav.current.label, Modifier.weight(1f), color = if (f) Hopla.OnAccent else Hopla.TextDim, fontFamily = Hopla.Body,
                    fontWeight = FontWeight.SemiBold, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Icon(Icons.Chevron, null, Modifier.padding(end = 10.dp).size(14.dp), tint = if (f) Hopla.OnAccent else Hopla.TextDim)
            }

            if (switcher) {
                Spacer(Modifier.height(4.dp))
                val rows = nav.sections.map { Triple(it.label, it.icon) { nav.go(it) } } +
                    Triple("Rafraîchir la playlist", Icons.Restart, nav.refresh) + Triple("Catégories masquées", Icons.Lock, nav.categories) +
                    Triple("Paramètres", Icons.Settings, nav.settings) +
                    listOfNotNull(nav.update?.let { (label, go) -> Triple(label, Icons.Plus, go) })
                rows.forEachIndexed { i, (label, icon, go) ->
                    val active = nav.sections.getOrNull(i) == nav.current
                    MenuRow(label, icon, active, if (i == 0) Modifier.focusRequester(firstSection) else Modifier) { switcher = false; go() }
                }
            } else {
                Spacer(Modifier.height(4.dp))
                // The keyboard only opens on OK, never just because the focus passes by.
                if (editing) OutlinedTextField(
                    query, onQuery, Modifier.fillMaxWidth().focusRequester(searchEdit),
                    singleLine = true,
                    placeholder = { Text(searchHint, fontFamily = Hopla.Body, fontSize = 12.sp) },
                    textStyle = TextStyle(fontFamily = Hopla.Body, fontSize = 13.sp, color = Hopla.Text),
                    shape = RoundedCornerShape(13.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onAny = { keyboard?.hide(); editing = false; toContent() }),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Hopla.Accent, unfocusedBorderColor = Hopla.Chip, cursorColor = Hopla.Accent,
                        focusedTextColor = Hopla.Text, unfocusedTextColor = Hopla.Text,
                        focusedPlaceholderColor = Hopla.TextDim, unfocusedPlaceholderColor = Hopla.TextDim,
                    ),
                ) else FocusRow(onClick = { editing = true }, shape = RoundedCornerShape(13.dp), idle = Hopla.Chip,
                    modifier = Modifier.fillMaxWidth().height(40.dp).focusRequester(search)) { f ->
                    Icon(Icons.Search, null, Modifier.padding(start = 12.dp, end = 8.dp).size(15.dp), tint = if (f) Hopla.OnAccent else Hopla.TextDim)
                    Text(query.ifBlank { searchHint }, Modifier.weight(1f), color = if (f) Hopla.OnAccent else if (query.isBlank()) Hopla.TextDim else Hopla.Text,
                        fontFamily = Hopla.Body, fontWeight = if (query.isBlank()) FontWeight.Normal else FontWeight.SemiBold, fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (query.isNotBlank() && !editing) FocusRow(onClick = { onQuery("") }, shape = RoundedCornerShape(13.dp), modifier = Modifier.fillMaxWidth().height(32.dp)) { f ->
                    Text("Effacer la recherche", Modifier.padding(start = 12.dp), color = if (f) Hopla.OnAccent else Hopla.TextDim, fontFamily = Hopla.Body, fontSize = 11.5.sp)
                }
                if (categories.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    if (onPin != null) Text("Appui long OK : épingler en haut", Modifier.padding(start = 12.dp, bottom = 2.dp), color = Hopla.TextDim, fontFamily = Hopla.Body, fontSize = 10.5.sp)
                    Pivot(0.35f) {
                        LazyColumn(state = list, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            itemsIndexed(categories) { i, c ->
                                val isSelected = c == selected && query.isBlank()
                                // Left in the list jumps back to the top (and the pinned categories) in one press.
                                val keys = Modifier.onPreviewKeyEvent {
                                    if (it.key == Key.DirectionLeft) { if (it.type == KeyEventType.KeyDown) scope.launch { list.scrollToItem(0); top.focusSoon() }; true } else false
                                }.then(if (onPin != null) favoriteKeys { onPin(c) } else Modifier)
                                FocusRow(
                                    onClick = { onSelect(c); toContent() }, onFocus = { onSelect(c) },
                                    shape = RoundedCornerShape(11.dp), idle = if (isSelected) Hopla.AccentSoft else Color.Transparent,
                                    modifier = (if (c == selected) Modifier.focusRequester(current) else Modifier)
                                        .then(if (i == 0) Modifier.focusRequester(top) else Modifier).then(keys).fillMaxWidth().height(36.dp),
                                ) { f ->
                                    if (c in pinned) Icon(Icons.Star, "Épinglée", Modifier.padding(start = 12.dp).size(11.dp), tint = if (f) Hopla.OnAccent else Hopla.Accent)
                                    Text(groupLabel(c), Modifier.padding(start = if (c in pinned) 6.dp else 12.dp, end = 12.dp), color = if (f) Hopla.OnAccent else if (isSelected) Hopla.Accent else Hopla.Text,
                                        fontFamily = Hopla.Body, fontWeight = if (f || isSelected) FontWeight.Bold else FontWeight.Medium, fontSize = 12.5.sp,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MenuRow(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, active: Boolean, modifier: Modifier, onClick: () -> Unit) {
    FocusRow(onClick = onClick, shape = RoundedCornerShape(13.dp), idle = if (active) Hopla.AccentSoft else Color.Transparent, modifier = modifier.fillMaxWidth().height(42.dp)) { f ->
        Icon(icon, null, Modifier.padding(start = 12.dp, end = 12.dp).size(17.dp), tint = if (f) Hopla.OnAccent else if (active) Hopla.Accent else Hopla.TextDim)
        Text(label, color = if (f) Hopla.OnAccent else if (active) Hopla.Accent else Hopla.Text, fontFamily = Hopla.Body,
            fontWeight = if (f || active) FontWeight.Bold else FontWeight.Medium, fontSize = 13.5.sp)
    }
}

/** Filter chips: every option visible at once, one press to pick (the old button cycled through them). */
@Composable
fun SortChips(sorts: List<Sort>, sort: Sort, onSort: (Sort) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Trier", Modifier.padding(end = 4.dp), color = Hopla.TextDim, fontFamily = Hopla.Body, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
        sorts.forEach { s ->
            val on = s == sort
            FocusRow(onClick = { onSort(s) }, shape = CircleShape, idle = if (on) Hopla.AccentSoft else Hopla.Chip, modifier = Modifier.height(26.dp)) { f ->
                Text(s.label, Modifier.padding(horizontal = 12.dp), color = if (f) Hopla.OnAccent else if (on) Hopla.Accent else Hopla.Text,
                    fontFamily = Hopla.Body, fontWeight = if (on || f) FontWeight.Bold else FontWeight.Medium, fontSize = 11.sp)
            }
        }
    }
}
