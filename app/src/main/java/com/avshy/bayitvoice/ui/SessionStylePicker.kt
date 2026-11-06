package com.avshy.bayitvoice.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.avshy.bayitvoice.R
import com.avshy.bayitvoice.config.SessionStyle

/** The three ways to show a conversation, each with a small picture of it. */
@Composable
fun SessionStylePicker(selected: SessionStyle, onSelect: (SessionStyle) -> Unit) {
    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SessionStyle.entries.forEach { style ->
            val isSelected = style == selected
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f) else Color.Transparent,
                border = BorderStroke(1.dp, if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(selected = isSelected, role = Role.RadioButton, onClick = { onSelect(style) }),
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StyleThumbnail(style)
                    RadioButton(selected = isSelected, onClick = null)
                    Text(stringResource(style.label), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

private val SessionStyle.label: Int
    get() = when (this) {
        SessionStyle.Wave -> R.string.session_style_wave
        SessionStyle.Futuristic -> R.string.session_style_futuristic
        SessionStyle.FullScreen -> R.string.session_style_full_screen
    }

/** A phone outline with the style drawn in it: a sheet with a wave, a sheet with an orb, or a whole dark screen with an orb. */
@Composable
private fun StyleThumbnail(style: SessionStyle) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        Modifier.size(40.dp, 68.dp)
            .clip(shape)
            .background(if (style == SessionStyle.FullScreen) Color(0xFF0B0C10) else Color(0xFF4A4E62))
            .border(2.dp, MaterialTheme.colorScheme.outline, shape),
        contentAlignment = Alignment.BottomCenter,
    ) {
        if (style == SessionStyle.FullScreen) {
            DottedRing(Modifier.fillMaxSize().padding(9.dp))
        } else {
            Box(
                Modifier.fillMaxWidth().height(24.dp).background(SessionColors.sheet, RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp)),
                contentAlignment = Alignment.Center,
            ) {
                if (style == SessionStyle.Wave) MiniWave() else DottedRing(Modifier.size(12.dp))
            }
        }
    }
}

@Composable
private fun MiniWave() {
    Canvas(Modifier.size(14.dp, 10.dp)) {
        val heights = floatArrayOf(0.4f, 0.8f, 0.5f, 0.9f, 0.3f)
        val step = size.width / heights.size
        heights.forEachIndexed { i, h ->
            val x = step * i + step / 2
            drawLine(SessionColors.accent, Offset(x, size.height * (1 - h) / 2), Offset(x, size.height * (1 + h) / 2), strokeWidth = step * 0.6f)
        }
    }
}

@Composable
private fun DottedRing(modifier: Modifier) {
    Canvas(modifier) {
        val r = size.minDimension / 2 - 1.dp.toPx()
        drawCircle(
            SessionColors.accent,
            radius = r,
            style = Stroke(width = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 2.dp.toPx()))),
        )
    }
}
