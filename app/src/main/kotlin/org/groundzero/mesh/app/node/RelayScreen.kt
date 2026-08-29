package org.groundzero.mesh.app.node

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.groundzero.mesh.app.mesh.MeshActivityCounts
import org.groundzero.mesh.app.mesh.MeshActivityEntry
import org.groundzero.mesh.app.mesh.MeshActivityOutcome
import org.groundzero.mesh.app.mesh.MeshStack
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The relay's phone. It has nothing to ask of its user — it exists to prove, on a screen, the
 * one claim a mesh demo lives or dies on: that a report from a phone the responder cannot
 * hear passed through *this* device on its way to the board.
 *
 * So the counters are the content. `received` is deliberately shown next to `relayed` rather
 * than in place of it, because the gap between them is the suppression that keeps the mesh
 * alive under load — a relay that forwarded everything it heard would be the bug.
 */
@Composable
fun RelayScreen(
    modifier: Modifier = Modifier,
    counts: () -> MeshActivityCounts = MeshStack::activityCounts,
    activity: () -> List<MeshActivityEntry> = MeshStack::recentActivity,
) {
    var snapshot by remember { mutableStateOf(counts()) }
    var log by remember { mutableStateOf(activity()) }

    LaunchedEffect(Unit) {
        while (true) {
            snapshot = counts()
            log = activity()
            delay(REFRESH_INTERVAL_MS)
        }
    }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "Carrying other people's reports. No SOS is raised from this phone.",
            style = MaterialTheme.typography.bodyMedium,
        )

        Label("Traffic")
        CountRow("received", snapshot.received)
        CountRow("relayed onward", snapshot.relayed)
        CountRow("duplicates suppressed", snapshot.duplicates)
        CountRow("undecodable, dropped", snapshot.dropped)
        CountRow("buffered for replay", snapshot.stored)

        HorizontalDivider()

        Label("Recent frames")
        if (log.isEmpty()) {
            Text("nothing heard yet", style = MaterialTheme.typography.bodyMedium)
        } else {
            // Newest first: the frame that just arrived is the one being pointed at.
            log.asReversed().forEach { EntryRow(it) }
        }
    }
}

@Composable
private fun CountRow(key: String, value: Int) {
    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
        Text(key)
        Text(value.toString(), fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun EntryRow(entry: MeshActivityEntry) {
    val detail = when (entry.outcome) {
        MeshActivityOutcome.RECEIVED_NEW -> buildString {
            append(entry.zone ?: "unknown zone")
            entry.severity?.let { append(" · ").append(it.name.lowercase().replace('_', ' ')) }
            entry.effectiveTier?.let { append(" · ").append(tierLabel(it.name)) }
        }
        // Not decoded a second time on purpose — see MeshActivityEntry.
        MeshActivityOutcome.DUPLICATE -> "already held — not forwarded again"
        MeshActivityOutcome.DROPPED -> "could not be decoded"
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
            Text(TIME_FORMAT.format(Date(entry.atMs)), style = MaterialTheme.typography.bodySmall)
            Text(
                entry.from?.canonical() ?: "unknown sender",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Text(detail, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun tierLabel(tier: String) = when (tier) {
    "PRATYAKSA" -> "first-hand"
    "ANUMANA" -> "inferred"
    else -> "relayed"
}

private const val REFRESH_INTERVAL_MS = 1_000L

private val TIME_FORMAT = SimpleDateFormat("HH:mm:ss", Locale.ROOT)
