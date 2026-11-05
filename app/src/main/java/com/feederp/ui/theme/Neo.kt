package com.feederp.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun Modifier.neo(radius: Dp = 20.dp, depth: Dp = 6.dp): Modifier {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val shade = if (dark) Color(0xFF000000) else Color(0xFF7FB0B4)
    val light = if (dark) Color(0xFF1D3A3D) else Color(0xFFFFFFFF)
    return this.drawBehind {
        val r = radius.toPx()
        val d = depth.toPx()
        val steps = 4
        for (i in steps downTo 1) {
            val f = i.toFloat() / steps
            val grow = d * 0.6f * f
            val corner = CornerRadius(r + grow, r + grow)
            drawRoundRect(
                color = shade.copy(alpha = 0.10f),
                topLeft = Offset(d * f - grow, d * f - grow),
                size = Size(size.width + grow * 2, size.height + grow * 2),
                cornerRadius = corner,
            )
            drawRoundRect(
                color = light.copy(alpha = 0.18f),
                topLeft = Offset(-d * f - grow, -d * f - grow),
                size = Size(size.width + grow * 2, size.height + grow * 2),
                cornerRadius = corner,
            )
        }
    }
}
