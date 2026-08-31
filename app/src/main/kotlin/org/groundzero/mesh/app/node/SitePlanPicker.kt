package org.groundzero.mesh.app.node

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * "Where are you?", answered by pointing.
 *
 * Drawn rather than rendered from an image: the plan is a handful of rectangles (see
 * [SitePlan]), so a `Canvas` needs no tiles, no map library and no network — which matters
 * because this screen has to work in the hour when none of those exist.
 *
 * Deliberately not doing several things a map app would:
 *
 * - **No pan or zoom.** The whole site is on screen at once. A person in the dark with one
 *   hand free should not have to navigate to answer a question, and a gesture that can put the
 *   marker off screen is a gesture that can lose it.
 * - **No confirm step.** The tap *is* the answer, and it is changed by tapping again. A
 *   confirmation dialog between a frightened person and their location is a place to get
 *   stuck.
 * - **No default marker.** Nothing is selected until someone selects it, because an unset
 *   position must never leave here as a guess someone did not make. [selected] starting null
 *   is what keeps the SOS honest.
 */
@Composable
fun SitePlanPicker(
    plan: SitePlan,
    selected: Offset?,
    onPick: (planX: Float, planY: Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    val outline = MaterialTheme.colorScheme.outline
    val zoneFill = MaterialTheme.colorScheme.surfaceVariant
    val zoneEdge = MaterialTheme.colorScheme.onSurfaceVariant
    val markerColor = MaterialTheme.colorScheme.error
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant

    Column(modifier.fillMaxWidth()) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .aspectRatio(plan.width / plan.height)
                .pointerInput(plan) {
                    detectTapGestures { tap ->
                        // Screen space back to plan space. The Canvas is laid out at the
                        // plan's own aspect ratio, so one scale factor covers both axes.
                        val scale = size.width / plan.width
                        onPick(tap.x / scale, tap.y / scale)
                    }
                },
        ) {
            val scale = size.width / plan.width
            fun px(v: Float) = v * scale

            drawRect(color = zoneFill.copy(alpha = 0.25f), size = Size(size.width, size.height))
            drawRect(color = outline, size = Size(size.width, size.height), style = Stroke(width = 2f))

            for (zone in plan.zones) {
                val topLeft = Offset(px(zone.x), px(zone.y))
                val zoneSize = Size(px(zone.width), px(zone.height))
                drawRect(color = zoneFill, topLeft = topLeft, size = zoneSize)
                drawRect(color = zoneEdge.copy(alpha = 0.5f), topLeft = topLeft, size = zoneSize, style = Stroke(width = 2f))
                labelZone(measurer, zone.name, topLeft, zoneSize, labelColor)
            }

            selected?.let { mark ->
                val at = Offset(px(mark.x), px(mark.y))
                // A ring rather than a filled dot: whatever is underneath stays readable, and
                // the thing a person needs to check is that the marker is on the right block.
                drawCircle(color = markerColor, radius = 14f, center = at, style = Stroke(width = 5f))
                drawCircle(color = markerColor, radius = 4f, center = at)
            }
        }
        Text(
            plan.name,
            style = MaterialTheme.typography.bodySmall,
            color = labelColor,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
    }
}

/** Centred in the rectangle, and simply omitted when the rectangle is too small to hold it. */
private fun DrawScope.labelZone(
    measurer: TextMeasurer,
    name: String,
    topLeft: Offset,
    size: Size,
    color: Color,
) {
    val laid = measurer.measure(name, TextStyle(fontSize = 10.sp, color = color))
    if (laid.size.width > size.width || laid.size.height > size.height) return
    drawText(
        textLayoutResult = laid,
        topLeft = Offset(
            topLeft.x + (size.width - laid.size.width) / 2f,
            topLeft.y + (size.height - laid.size.height) / 2f,
        ),
    )
}

