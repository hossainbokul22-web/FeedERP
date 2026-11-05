package com.feederp.ui.theme

import android.graphics.BlurMaskFilter
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable

/** নিও (সফট-এমবস) ছায়া: নিচে-ডানে গাঢ়, ওপরে-বামে হালকা আভা। */
@Composable
fun Modifier.neo(radius: Dp = 20.dp, depth: Dp = 6.dp): Modifier {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val shade = if (dark) Color(0xFF000000) else Color(0xFF9CC3C6)
    val light = if (dark) Color(0xFF1D3A3D) else Color(0xFFFFFFFF)
    return this.drawBehind {
        val r = radius.toPx(); val d = depth.toPx()
        drawIntoCanvas { c ->
            val p = android.graphics.Paint().apply { isAntiAlias = true; maskFilter = BlurMaskFilter(d * 1.6f, BlurMaskFilter.Blur.NORMAL) }
            p.color = shade.copy(alpha = 0.55f).toArgb()
            c.nativeCanvas.drawRoundRect(d, d, size.width + d, size.height + d, r, r, p)
            p.color = light.copy(alpha = 0.9f).toArgb()
            c.nativeCanvas.drawRoundRect(-d, -d, size.width - d, size.height - d, r, r, p)
        }
    }
}
