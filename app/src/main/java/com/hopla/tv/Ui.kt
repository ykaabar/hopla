package com.hopla.tv

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil3.compose.AsyncImage

enum class Section(val label: String, val icon: ImageVector) {
    HOME("Accueil", Icons.Home), LIVE("Direct", Icons.Tv), MOVIES("Films", Icons.Film), SHOWS("Séries", Icons.Series)
}

/** Image cards can't be filled with the accent, so their focus is an accent ring plus the same lift. */
@Composable
fun FocusCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(15.dp),
    onFocus: () -> Unit = {},
    tile: Color = Hopla.Chip,
    content: @Composable BoxScope.(focused: Boolean) -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    LaunchedEffect(focused) { if (focused) onFocus() }
    val scale by animateFloatAsState(if (focused) 1.06f else 1f, label = "card-lift")
    Box(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(shape)
            .background(tile)
            .border(if (focused) 3.dp else 0.dp, if (focused) Hopla.Accent else Color.Transparent, shape)
            .clickable(interactionSource = source, indication = null, onClick = onClick),
    ) { content(focused) }
}

/** Remote image. A missing or broken image shows [fallback] (usually the name) instead of an empty card. */
@Composable
fun Img(url: String, modifier: Modifier = Modifier, scale: ContentScale = ContentScale.Crop, fallback: String = "") {
    var failed by remember(url) { mutableStateOf(url.isBlank()) }
    if (!failed) AsyncImage(model = url, contentDescription = null, modifier = modifier, contentScale = scale,
        onError = { failed = true })
    else if (fallback.isNotBlank()) Box(modifier, contentAlignment = Alignment.Center) {
        Text(fallback, Modifier.padding(8.dp), color = Hopla.TextDim, fontFamily = Hopla.Display, fontWeight = FontWeight.Bold,
            fontSize = 13.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun Progress(fraction: Float, modifier: Modifier = Modifier, track: Color = Color(0x4DFFFFFF)) {
    Box(modifier.height(4.dp).clip(CircleShape).background(track)) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(fraction.coerceIn(0f, 1f)).clip(CircleShape).background(Hopla.Accent))
    }
}

private val clock = java.text.SimpleDateFormat("HH:mm", java.util.Locale.FRANCE)

/** "20:30" in the TV's time zone. */
fun hhmm(t: Long): String = clock.format(java.util.Date(t))

fun Programme.progress(now: Long) = if (stop > start) ((now - start).toFloat() / (stop - start)).coerceIn(0f, 1f) else 0f

/**
 * Initial focus. Items inside lazy lists are composed a moment after the screen, so a single request can
 * arrive too early and be lost; retry for up to a second until the target actually takes focus.
 */
suspend fun FocusRequester.focusSoon(): Boolean {
    repeat(20) {
        if (runCatching { requestFocus(androidx.compose.ui.focus.FocusDirection.Enter) }.getOrDefault(false)) return true
        kotlinx.coroutines.delay(50)
    }
    return false
}

/** Long press on OK (or the Menu key) calls [onFavorite]; the release must not also click the row. */
@Composable
fun favoriteKeys(onFavorite: () -> Unit): Modifier {
    var longPress by remember { mutableStateOf(false) }
    return Modifier.onPreviewKeyEvent {
        val center = it.key == Key.DirectionCenter || it.key == Key.Enter || it.key == Key.NumPadEnter
        when {
            it.key == Key.Menu -> { if (it.type == KeyEventType.KeyUp) onFavorite(); true }
            center && it.type == KeyEventType.KeyDown && it.nativeKeyEvent.repeatCount == 1 -> { longPress = true; onFavorite(); true }
            center && it.type == KeyEventType.KeyDown && it.nativeKeyEvent.repeatCount > 1 -> true
            center && it.type == KeyEventType.KeyUp && longPress -> { longPress = false; true }
            else -> false
        }
    }
}

/** Sound of the muted-by-default previews (live list, home hero); the household picks it in Réglages. */
enum class PreviewSound(val label: String) { OFF("Coupé"), DELAYED("Après 2 s"), ON("Toujours") }

/** Applies [mode] to a preview that just started: "Après 2 s" fades the sound in once the viewer stays on it. */
suspend fun androidx.media3.exoplayer.ExoPlayer.previewVolume(mode: PreviewSound) {
    when (mode) {
        PreviewSound.OFF -> volume = 0f
        PreviewSound.ON -> volume = 1f
        PreviewSound.DELAYED -> {
            volume = 0f
            kotlinx.coroutines.delay(2_000)
            for (i in 1..10) { volume = i / 10f; kotlinx.coroutines.delay(60) }
        }
    }
}

/**
 * TV-style scrolling: the focused item settles at a fixed spot ([fraction] of the free space plus [margin])
 * instead of the list creeping by a different few pixels on every press, which reads as the screen shaking.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class) // stable in behaviour since Compose 1.7; only the annotation lags
@Composable
fun Pivot(fraction: Float = 0f, margin: Dp = 0.dp, content: @Composable () -> Unit) {
    val px = with(androidx.compose.ui.platform.LocalDensity.current) { margin.toPx() }
    val spec = remember(fraction, px) {
        object : androidx.compose.foundation.gestures.BringIntoViewSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
                offset - ((containerSize - size) * fraction + px)

            // Short and decisive, like TV menus: fewer frames per key press on the Fire TV's small GPU.
            @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
            override val scrollAnimationSpec: androidx.compose.animation.core.AnimationSpec<Float> =
                androidx.compose.animation.core.tween(220, easing = androidx.compose.animation.core.FastOutSlowInEasing)
        }
    }
    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides spec, content = content)
}

/** Provider group names like "News;Sports" read better as "News · Sports". */
fun groupLabel(g: String) = g.split(';', '|').map { it.trim().trim('★', '☆', '#', '=', '*', '-', '_').trim() }.filter { it.isNotEmpty() }
    .map { if (it.equals("Uncategorized", true) || it.equals("Uncategorised", true) || it.equals("Other", true) || it.equals("Others", true)) "Autres" else it }
    .joinToString(" · ")

/**
 * Category pills. The selected one carries [focus] so long lists can jump here in one press;
 * Down calls [onDown] so the user lands back where they were, not at the top.
 */
@Composable
fun CategoryPills(
    groups: List<String>, group: String, onGroup: (String) -> Unit, state: LazyListState, focus: FocusRequester,
    modifier: Modifier = Modifier, onDown: () -> Unit,
) {
    LazyRow(
        modifier.padding(vertical = 6.dp).onPreviewKeyEvent {
            if (it.key == Key.DirectionDown && it.type == KeyEventType.KeyDown) { onDown(); true } else false
        },
        state = state, horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        items(groups) { g ->
            val selected = g == group
            FocusRow(
                onClick = { onGroup(g) }, onFocus = { onGroup(g) }, shape = CircleShape,
                idle = if (selected) Hopla.AccentSoft else Hopla.Chip,
                modifier = (if (selected) Modifier.focusRequester(focus) else Modifier).height(26.dp),
            ) { focused ->
                Text(
                    groupLabel(g), Modifier.padding(horizontal = 11.dp),
                    color = if (focused) Hopla.OnAccent else if (selected) Hopla.Accent else Hopla.Text,
                    fontFamily = Hopla.Body, fontWeight = if (selected || focused) FontWeight.Bold else FontWeight.Medium, fontSize = 11.sp,
                )
            }
        }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, color = Hopla.Text, fontFamily = Hopla.Display, fontWeight = FontWeight.Bold, fontSize = 16.5.sp)
}

/** Poster card for films and series (2:3). */
@Composable
fun PosterCard(
    title: String, image: String, onClick: () -> Unit, modifier: Modifier = Modifier, progress: Float = 0f, onFocus: () -> Unit = {},
    width: Dp = 120.dp, height: Dp = 180.dp, showTitle: Boolean = true,
) {
    Column(modifier.width(width), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FocusCard(onClick = onClick, onFocus = onFocus, modifier = Modifier.width(width).height(height)) {
            Img(image, Modifier.fillMaxSize(), fallback = title)
            if (progress > 0f) Progress(progress, Modifier.align(Alignment.BottomCenter).padding(8.dp).fillMaxWidth())
        }
        if (showTitle) Text(title, color = Hopla.Text, fontFamily = Hopla.Body, fontWeight = FontWeight.SemiBold, fontSize = 11.5.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Channel card: logo on glass, channel number badge. */
@Composable
fun ChannelCard(number: Int, channel: Channel, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.width(165.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FocusCard(onClick = onClick, modifier = Modifier.width(165.dp).height(93.dp), tile = Hopla.LogoTile) {
            Img(channel.logo, Modifier.align(Alignment.Center).padding(horizontal = 30.dp, vertical = 18.dp).fillMaxSize(), ContentScale.Fit, fallback = channel.name)
            Text("$number", Modifier.padding(6.dp).clip(CircleShape).background(Hopla.Ground.copy(alpha = 0.6f)).padding(horizontal = 7.dp, vertical = 2.dp),
                color = Hopla.Text, fontFamily = Hopla.Body, fontWeight = FontWeight.Bold, fontSize = 10.sp)
        }
        Text(channel.name, color = Hopla.Text, fontFamily = Hopla.Body, fontWeight = FontWeight.SemiBold, fontSize = 11.5.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Wide card (16:9) used for "Reprendre" and episodes. */
@Composable
fun WideCard(title: String, subtitle: String, image: String, progress: Float, onClick: () -> Unit, modifier: Modifier = Modifier, focus: Modifier = Modifier) {
    Column(modifier.width(195.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        FocusCard(onClick = onClick, modifier = focus.width(195.dp).height(110.dp)) {
            Img(image, Modifier.fillMaxSize())
            if (progress > 0f) Progress(progress, Modifier.align(Alignment.BottomCenter).padding(8.dp).fillMaxWidth())
        }
        Text(title, color = Hopla.Text, fontFamily = Hopla.Body, fontWeight = FontWeight.SemiBold, fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (subtitle.isNotBlank()) Text(subtitle, color = Hopla.TextDim, fontFamily = Hopla.Body, fontSize = 10.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Accent pill button (primary) or glass pill (secondary). */
