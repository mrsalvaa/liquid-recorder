// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidrecorder.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** Íconos del editor: "[→" marca el comienzo de la selección y "←]" el final. */
object EditIcons {
    private fun icon(name: String, right: Boolean): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            val stroke = SolidColor(Color.Black)
            // Corchete: en el borde izquierdo para el comienzo, en el derecho para el final.
            path(stroke = stroke, strokeLineWidth = 2.2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                if (right) {
                    moveTo(8f, 4.5f); lineTo(4.5f, 4.5f); lineTo(4.5f, 19.5f); lineTo(8f, 19.5f)
                } else {
                    moveTo(16f, 4.5f); lineTo(19.5f, 4.5f); lineTo(19.5f, 19.5f); lineTo(16f, 19.5f)
                }
            }
            // Flecha: hacia adentro de la selección.
            path(stroke = stroke, strokeLineWidth = 2.2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                if (right) {
                    moveTo(8.5f, 12f); lineTo(18f, 12f)
                    moveTo(14f, 8f); lineTo(18f, 12f); lineTo(14f, 16f)
                } else {
                    moveTo(15.5f, 12f); lineTo(6f, 12f)
                    moveTo(10f, 8f); lineTo(6f, 12f); lineTo(10f, 16f)
                }
            }
        }.build()

    val SelectionStart: ImageVector by lazy { icon("SelectionStart", right = true) }
    val SelectionEnd: ImageVector by lazy { icon("SelectionEnd", right = false) }
}
