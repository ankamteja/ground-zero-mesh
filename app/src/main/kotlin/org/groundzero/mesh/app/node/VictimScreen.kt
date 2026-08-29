package org.groundzero.mesh.app.node

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.groundzero.mesh.propagation.Severity

/**
 * The victim's phone. One thing to do, and it is the only large thing on the screen.
 *
 * Everything that made the old combined screen useful to a developer — the score, the "why"
 * panel, the sensory slider — is still here, but behind a disclosure that is closed by
 * default. A person deciding whether to press the button is not reading an EMA, and anything
 * competing with that button for attention is a cost paid at exactly the wrong moment.
 */
@Composable
fun VictimScreen(vm: NodeViewModel, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {

        Label("What is happening")
        OptionRow(
            options = severityChoices,
            selected = vm.selectedSeverity,
            onSelect = vm::selectSeverity,
        )

        Button(
            onClick = vm::raiseSos,
            modifier = Modifier
                .fillMaxWidth()
                .height(SOS_BUTTON_HEIGHT),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
            ),
        ) {
            Text("SEND SOS", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        }

        vm.lastSos?.let { sos ->
            // A person who pressed the button must never be shown something that implies help
            // was called when nothing left the phone.
            val fate =
                if (vm.lastSosBroadcast) "sent to the mesh"
                else "NOT SENT — the mesh service is not running"
            Text(
                "${sos.severity.name.lowercase().replace('_', ' ')} · $fate",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }

        HorizontalDivider()

        var showDetails by remember { mutableStateOf(false) }
        TextButton(onClick = { showDetails = !showDetails }) {
            Text(if (showDetails) "Hide details" else "Show details")
        }

        if (showDetails) {
            val ex = vm.currentExplanation()
            Label("Status")
            Text("${ex.state.name}  ·  ${pct(ex.score)}", fontWeight = FontWeight.SemiBold)

            Label("Why")
            Text(ex.reason, style = MaterialTheme.typography.bodyMedium)
            MetricRow("score", pct(ex.score))
            MetricRow("baseline", pct(ex.baseline))
            MetricRow("last signal", pct(ex.lastSignal))
            Text(
                "no AI at this layer — a moving average and two thresholds",
                style = MaterialTheme.typography.bodySmall,
            )

            Label("Sensory signal (demo)")
            var value by remember { mutableFloatStateOf(0f) }
            Slider(
                value = value,
                onValueChange = { value = it; vm.feedSignal(it.toDouble()) },
                valueRange = 0f..1f,
            )
        }
    }
}

@Composable
private fun MetricRow(key: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp), Arrangement.SpaceBetween) {
        Text(key)
        Text(value)
    }
}

/** Big enough to find without looking, and far past the 44dp touch-target floor. */
private val SOS_BUTTON_HEIGHT = 140.dp

private val severityChoices = listOf(
    Severity.DROWNING_IMMINENT to "drowning",
    Severity.STRUCTURAL_ENTRAPMENT to "trapped",
    Severity.OTHER to "other",
)
