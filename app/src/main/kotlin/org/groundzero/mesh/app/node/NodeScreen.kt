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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.util.Locale

/**
 * Role dispatcher. Each role gets its own screen rather than one screen that hides parts of
 * itself, because the three phones in a demo are doing genuinely different jobs and only one
 * of them belongs to someone in trouble.
 *
 * Deliberately plain throughout: labels and values, one divider between sections, no coloured
 * cards. These screens have to be readable under stress and show their own reasoning, not
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

        Label("This phone is")
        OptionRow(
            options = roleChoices,
            selected = vm.role,
            onSelect = vm::selectRole,
        )

        HorizontalDivider()

        when (vm.role) {
            MeshRole.NODE -> VictimScreen(vm)
            MeshRole.RELAY -> RelayScreen()
            MeshRole.GATEWAY -> ResponderScreen()
        }
    }
}

@Composable
internal fun Label(text: String) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelSmall)
}

@Composable
internal fun <T> OptionRow(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
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

internal fun pct(v: Double): String = String.format(Locale.ROOT, "%.0f%%", v * 100)

/**
 * The enum names are the protocol's and stay put; these labels are what the demo calls them.
 * A phone held by someone trapped is a "victim" phone to everyone in the room, and no one
 * asks the person in the stairwell to be a "node".
 */
private val roleChoices = listOf(
    MeshRole.NODE to "victim",
    MeshRole.RELAY to "relay",
    MeshRole.GATEWAY to "responder",
)
