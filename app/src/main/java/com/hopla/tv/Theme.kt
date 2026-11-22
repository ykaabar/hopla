package com.hopla.tv

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * "Hopla Verre" design tokens. Sizes are the 1280x720 mockups x 0.75, because a 1080p TV is 960x540 dp.
 * Android 11 (Fire TV) has no live blur, so "glass" is a dense translucent tint over the dark ground.
 */
object Hopla {
    val Ground = Color(0xFF0C0D12)
    // A baked "frosted glass" haze behind the panels was tried and dropped: even as a single draw it doubled the
    // frame time on the Fire TV. Panels stay a dense tint over the dark ground.
    val Glass = Color(0xE01C1E26)
    val Chip = Color(0x1AFFFFFF)
    /** Behind channel logos: light enough for dark logos ("ACTION" in black), dark enough for white ones. */
    val LogoTile = Color(0xFF3A3D48)
    /** The rail when it opens over the content: nearly opaque so labels never sit on busy artwork. */
    val GlassSolid = Color(0xFF181A21)
    val Accent = Color(0xFFFFC247)
    val AccentSoft = Color(0x2EFFC247)
    val OnAccent = Color(0xFF1E1503)
    val OnAccentDim = Color(0xFF4A3608)
    val Text = Color.White
    val TextDim = Color(0xB8FFFFFF)
    val Live = Color(0xFFFF5A4E)

    val Display = FontFamily(
        Font(R.font.bricolage_bold, FontWeight.Bold),
        Font(R.font.bricolage_extrabold, FontWeight.ExtraBold),
    )
    val Body = FontFamily(
        Font(R.font.figtree_regular, FontWeight.Normal),
        Font(R.font.figtree_medium, FontWeight.Medium),
        Font(R.font.figtree_semibold, FontWeight.SemiBold),
        Font(R.font.figtree_bold, FontWeight.Bold),
    )
}

/**
 * The one focus behaviour of the app: the focused element fills with the saffron accent and lifts.
 * Everything selectable with the remote goes through this, so focus always looks the same.
 */
@Composable
fun FocusRow(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(15.dp),
    idle: Color = Color.Transparent,
    onFocus: () -> Unit = {},
    content: @Composable RowScope.(focused: Boolean) -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    LaunchedEffect(focused) { if (focused) onFocus() }
    val scale by animateFloatAsState(if (focused) 1.04f else 1f, label = "focus-lift")
    Row(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .shadow(if (focused) 10.dp else 0.dp, shape)
            .clip(shape)
            .background(if (focused) Hopla.Accent else idle)
            .clickable(interactionSource = source, indication = null, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) { content(focused) }
}

/** Stroke icons from 24x24 SVG path data, so we need no icon library. */
private fun strokeIcon(vararg paths: String) = ImageVector.Builder(
    defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f,
).apply {
    paths.forEach {
        addPath(
            addPathNodes(it), stroke = SolidColor(Color.White), strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
        )
    }
}.build()

object Icons {
    val Tv = strokeIcon("M6 7h12a3 3 0 0 1 3 3v7a3 3 0 0 1-3 3H6a3 3 0 0 1-3-3v-7a3 3 0 0 1 3-3z", "M8 3l4 4 4-4")
    val Settings = strokeIcon(
        "M9 12a3 3 0 1 0 6 0a3 3 0 1 0 -6 0",
        "M12 2v3M12 19v3M4.2 4.2l2.1 2.1M17.7 17.7l2.1 2.1M2 12h3M19 12h3M4.2 19.8l2.1-2.1M17.7 6.3l2.1-2.1",
    )
    val Check = strokeIcon("M5 12.5l4.5 4.5L19 7.5")
    val Home = strokeIcon("M4 11l8-7 8 7v8a1 1 0 0 1-1 1h-4v-6H9v6H5a1 1 0 0 1-1-1z")
    val Film = strokeIcon("M6 4h12a3 3 0 0 1 3 3v10a3 3 0 0 1-3 3H6a3 3 0 0 1-3-3V7a3 3 0 0 1 3-3z", "M8 4v16M16 4v16M3 12h18")
    val Series = strokeIcon("M6 8h12a3 3 0 0 1 3 3v6a3 3 0 0 1-3 3H6a3 3 0 0 1-3-3v-6a3 3 0 0 1 3-3z", "M6 5h12M9 2h6")
    val Play = strokeIcon("M8 5.5v13l10.5-6.5z")
    val Chevron = strokeIcon("M6 9l6 6 6-6")
    val Star = strokeIcon("M12 3l2.7 5.6 6.1.9-4.4 4.3 1 6.1L12 17l-5.4 2.9 1-6.1-4.4-4.3 6.1-.9z")
    val Search = strokeIcon("M4 11a7 7 0 1 0 14 0a7 7 0 1 0 -14 0", "M20 20l-3.5-3.5")
    val Plus = strokeIcon("M12 5v14M5 12h14")
    val Restart = strokeIcon("M4 12a8 8 0 1 0 2.3-5.6", "M4 4v4h4")
    val Trailer = strokeIcon("M5 5h14a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2z", "M10 9v6l5-3z")
    val List = strokeIcon("M8 6h12M8 12h12M8 18h12", "M4 6h.5M4 12h.5M4 18h.5")
    val Lang = strokeIcon("M4 6h16a1 1 0 0 1 1 1v10a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V7a1 1 0 0 1 1-1z", "M7 11h4M7 14h8")
}
