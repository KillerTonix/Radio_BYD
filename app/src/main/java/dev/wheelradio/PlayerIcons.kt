package dev.wheelradio

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** Vector player icons (same shapes as Material's), so they render identically on every device
 *  instead of depending on the system emoji font. */
object PlayerIcons {
    private fun icon(name: String, block: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit) =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
            .path(fill = SolidColor(Color.Black), pathBuilder = block)
            .build()

    val Play = icon("Play") { moveTo(8f, 5f); verticalLineToRelative(14f); lineToRelative(11f, -7f); close() }
    val Pause = icon("Pause") {
        moveTo(6f, 19f); horizontalLineToRelative(4f); verticalLineTo(5f); horizontalLineTo(6f); close()
        moveTo(14f, 5f); verticalLineToRelative(14f); horizontalLineToRelative(4f); verticalLineTo(5f); close()
    }
    val Next = icon("Next") {
        moveTo(6f, 18f); lineToRelative(8.5f, -6f); lineTo(6f, 6f); close()
        moveTo(16f, 6f); verticalLineToRelative(12f); horizontalLineToRelative(2f); verticalLineTo(6f); close()
    }
    val Previous = icon("Previous") {
        moveTo(6f, 6f); horizontalLineToRelative(2f); verticalLineToRelative(12f); horizontalLineTo(6f); close()
        moveTo(9.5f, 12f); lineToRelative(8.5f, 6f); verticalLineTo(6f); close()
    }
}
