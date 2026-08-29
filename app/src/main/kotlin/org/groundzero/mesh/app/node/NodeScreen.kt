package org.groundzero.mesh.app.node

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.groundzero.mesh.propagation.Severity
import java.util.Locale

/**
 * Deliberately plain: labels and values, one divider between sections, no coloured cards.
 * The screen's job is to be readable under stress and to show its own reasoning, not to
 * look designed.
 */
@Composable
fun NodeScreen(vm: NodeViewModel, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Ground-Zero Mesh", style = MaterialTheme.typography.titleLarge)

        Label("Role")
        OptionRow(
            options = MeshRole.entries.map { it to it.name.lowercase() },
            selected = vm.role,
            onSelect = vm::selectRole,
        )

        HorizontalDivider()

        if (vm.role == MeshRole.NODE) {
            Label("Severity")
            OptionRow(
                options = severityChoices,
                selected = vm.selectedSeverity,
                onSelect = vm::selectSeverity,
            )
            Button(onClick = vm::raiseSos, modifier = Modifier.fillMaxWidth()) {
                Text("Send SOS")
            }
            vm.lastSos?.let {
                val fate = if (vm.lastSosBroadcast) "broadcast" else "NOT SENT — mesh service is not running"
                Text(
                    "t=${it.atSeconds}s, severity ${it.severity.name.lowercase()} · $fate",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        } else {
            Text(
                "${vm.role.name.lowercase()}: carry-only, no local SOS",
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        HorizontalDivider()

        Label("Status")
        val ex = vm.currentExplanation()
        Text("${ex.state.name}  ·  ${pct(ex.score)}", fontWeight = FontWeight.SemiBold)

        Label("Why")
        Text(ex.reason, style = MaterialTheme.typography.bodyMedium)
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) { Text("score"); Text(pct(ex.score)) }
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) { Text("baseline"); Text(pct(ex.baseline)) }
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) { Text("last signal"); Text(pct(ex.lastSignal)) }
        Text(
            "no AI at this layer — a moving average and two thresholds",
            style = MaterialTheme.typography.bodySmall,
        )

        HorizontalDivider()

        Label("Sensory signal (demo)")
        var value by remember { mutableFloatStateOf(0f) }
        Slider(
            value = value,
            onValueChange = { value = it; vm.feedSignal(it.toDouble()) },
            valueRange = 0f..1f,
        )
    }
}

@Composable
private fun Label(text: String) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelSmall)
}

@Composable
private fun <T> OptionRow(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        options.forEach { (value, label) ->
            if (value == selected) {
                Button(onClick = { onSelect(value) }) { Text(label) }
            } else {
                TextButton(onClick = { onSelect(value) }) { Text(label) }
            }
        }
    }
}

private val severityChoices = listOf(
    Severity.DROWNING_IMMINENT to "drowning",
    Severity.STRUCTURAL_ENTRAPMENT to "entrapment",
    Severity.OTHER to "other",
)

private fun pct(v: Double): String = String.format(Locale.ROOT, "%.0f%%", v * 100)
