package org.groundzero.mesh.app.node

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import org.groundzero.mesh.app.gateway.GatewayController
import org.groundzero.mesh.app.gateway.GatewayServer
import org.groundzero.mesh.app.mesh.MeshStack

/**
 * The responder's phone at the perimeter. It serves the board; the responder reads it on a
 * laptop joined to this phone's hotspot.
 *
 * The server is deliberately **not** stopped when this screen leaves composition. Composition
 * ends on a rotation or when the Activity is backgrounded, and a responder whose board dies
 * because they put their phone in a pocket has lost the incident view mid-rescue. Stopping is
 * tied to leaving the gateway *role* instead — see [NodeScreen].
 */
@Composable
fun ResponderScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var running by remember { mutableStateOf(GatewayController.isRunning) }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "Serving the responder board. This phone does not raise an SOS or sense.",
            style = MaterialTheme.typography.bodyMedium,
        )

        Button(
            onClick = {
                if (running) {
                    GatewayController.stop()
                } else {
                    GatewayController.start(context, clusterSource = MeshStack::rankedBoard)
                }
                running = GatewayController.isRunning
                // Recorded so the board comes back by itself if Android reclaims this
                // process mid-incident — see GatewayStore.
                GatewayStore.setServing(context, running)
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (running) "Stop responder server" else "Start responder server")
        }

        if (running) {
            Label("Dashboard")
            // The board is served by this phone, so this phone can read it. That matters
            // more than it sounds: without this the only way to see the ranked board and the
            // 3D view — the whole output of the system — is a second device joined to a
            // hotspot. One person holding one phone could not see their own board.
            Button(
                onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse("http://127.0.0.1:${GatewayServer.DEFAULT_PORT}/"),
                            ),
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Open the board on this phone") }

            val addresses = remember(running) { LocalAddresses.ipv4() }
            if (addresses.isEmpty()) {
                Text(
                    "No network address yet — open this phone's hotspot, then a laptop that " +
                        "joins it can browse to port ${GatewayServer.DEFAULT_PORT}.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                // The literal "<this-phone-ip>" used to sit here, leaving the responder to go
                // and find their own address in Settings while the incident ran.
                Text(
                    "From a laptop on this phone's hotspot, browse to:",
                    style = MaterialTheme.typography.bodyMedium,
                )
                addresses.forEach { address ->
                    Text(
                        "http://$address:${GatewayServer.DEFAULT_PORT}/",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        } else {
            Text(
                "The board is not being served.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
