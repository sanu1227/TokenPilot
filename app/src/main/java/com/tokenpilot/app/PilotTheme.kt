package com.tokenpilot.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

@Composable
fun PilotTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Color(0xFF126B57), onPrimary = Color.White,
            primaryContainer = Color(0xFFD8F5E9), onPrimaryContainer = Color(0xFF124D40),
            secondary = Color(0xFF52647E), secondaryContainer = Color(0xFFE6ECF5),
            background = Color(0xFFF3F5F8), onBackground = Color(0xFF17263C),
            surface = Color.White, onSurface = Color(0xFF17263C),
            surfaceVariant = Color(0xFFE8EDF3), onSurfaceVariant = Color(0xFF536176),
            outline = Color(0xFF78869A), outlineVariant = Color(0xFFDCE3EC)
        ),
        shapes = Shapes(medium = RoundedCornerShape(20.dp), large = RoundedCornerShape(24.dp)),
        content = content
    )
}

/** Decorative tab marks; NavigationBarItem's visible label supplies accessibility text. */
@Composable
fun TabMark(index: Int) {
    val color = LocalContentColor.current
    Canvas(Modifier.size(24.dp)) {
        val w = size.width
        val stroke = Stroke(2.dp.toPx())
        when (index) {
            0 -> drawPath(Path().apply {
                moveTo(w * .15f, w * .45f); lineTo(w * .5f, w * .15f)
                lineTo(w * .85f, w * .45f); lineTo(w * .85f, w * .85f)
                lineTo(w * .15f, w * .85f); close()
            }, color, style = stroke)
            1 -> {
                drawCircle(color, w * .35f, style = stroke)
                drawLine(color, center, Offset(w * .5f, w * .28f), stroke.width)
                drawLine(color, center, Offset(w * .68f, w * .6f), stroke.width)
            }
            else -> repeat(3) { i ->
                val y = w * (.25f + i * .25f)
                drawLine(color, Offset(w * .15f, y), Offset(w * .85f, y), stroke.width)
                drawCircle(color, w * .07f, Offset(w * (if (i == 1) .65f else .35f), y))
            }
        }
    }
}
