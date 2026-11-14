package com.example.notes

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** A quiet, non-interactive hint; reaching the bottom removes it entirely. */
@Composable
internal fun ForwardScrollIndicator(moreBelow: Boolean, progress: Float, modifier: Modifier = Modifier) {
    if (!moreBelow) return
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(modifier.testTag("scroll-more")) {
        val width = 2.dp.toPx()
        val height = minOf(24.dp.toPx(), size.height)
        drawRoundRect(color, Offset(size.width - 4.dp.toPx(), (size.height - height) * progress.coerceIn(0f, 1f)),
            Size(width, height), CornerRadius(width))
    }
}
