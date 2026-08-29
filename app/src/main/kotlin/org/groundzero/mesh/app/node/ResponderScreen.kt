package org.groundzero.mesh.app.node

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
 * Leaving the responder role must actually stop the server, not merely hide the button — a
 * board still being served by a phone that is no longer the gateway is the kind of stale
 * truth this project is built to avoid. The [DisposableEffect] below is what enforces that.
 */
@Composable
fun ResponderScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var running by remember { mutableStateOf(GatewayController.isRunning) }

    DisposableEffect(Unit) {
        onDispose {
            if (GatewayController.isRunning) GatewayController.stop()
        }
    }

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
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (running) "Stop responder server" else "Start responder server")
        }

        if (running) {
            Label("Dashboard")
            Text(
                "Open this phone's hotspot, join it from the laptop, then browse to " +
                    "http://<this-phone-ip>:${GatewayServer.DEFAULT_PORT}/",
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            Text(
                "The board is not being served.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
