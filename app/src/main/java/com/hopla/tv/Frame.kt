package com.hopla.tv

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * "Plein cadre": one thing owns the screen. The picture fills the TV under soft black scrims, the words sit
 * low on the left, and the actions are round buttons stacked on the right.
 */

/** Full-screen artwork with the scrims that keep white text readable on any image. */
@Composable
fun FullBackdrop(url: String) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Crossfade(url, Modifier.fillMaxSize(), animationSpec = tween(450), label = "backdrop") { Img(it, Modifier.fillMaxSize()) }
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Color.Black.copy(alpha = 0.88f), 0.5f to Color.Black.copy(alpha = 0.45f), 0.78f to Color.Transparent)))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.35f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.92f))))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.55f), 0.18f to Color.Transparent)))
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0.8f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.6f))))
    }
}

/** One round action of the right rail. [engaged] fills it in the accent (in my list, favourite). */
@Composable
fun RailButton(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, engaged: Boolean = false) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    Column(
        modifier.onKeyEvent {
            val ok = it.key == Key.DirectionCenter || it.key == Key.Enter || it.key == Key.NumPadEnter
            if (ok && it.type == KeyEventType.KeyUp) { onClick(); true } else ok
        }.focusable(interactionSource = source).width(104.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        val fill = when { focused -> Color.White; engaged -> Hopla.Accent; else -> Color.Black.copy(alpha = 0.5f) }
        Box(
            Modifier.size(52.dp).clip(CircleShape).background(fill)
                .then(if (focused) Modifier.border(3.dp, Hopla.Accent, CircleShape) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, Modifier.size(22.dp), tint = if (focused || engaged) Color(0xFF15120A) else Color.White)
        }
        Text(label, color = if (focused) Color.White else Color.White.copy(alpha = 0.82f), fontFamily = Hopla.Body,
            fontWeight = FontWeight.Bold, fontSize = 11.sp, textAlign = TextAlign.Center, lineHeight = 13.sp, maxLines = 2)
    }
}

/** Opens a trailer (usually YouTube) in whatever app the TV has for it. */
fun openLink(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "Aucune application de la TV ne sait ouvrir cette bande-annonce.", Toast.LENGTH_LONG).show()
    }
}
