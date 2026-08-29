package org.groundzero.mesh.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.groundzero.mesh.app.NodeIdStore
import org.groundzero.mesh.app.permissions.MeshPermissions
import org.groundzero.mesh.app.service.MeshForegroundService

/**
 * Phase 2 shell: request the Nearby permission matrix, then start/stop the mesh service.
 * The real Node / Relay / Gateway UI lands in Phase 1 (`NodeScreen`).
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    var granted by remember { mutableStateOf(MeshPermissions.allGranted(this)) }
                    var running by remember { mutableStateOf(false) }

                    val requester = androidx.activity.compose.rememberLauncherForActivityResult(
                        ActivityResultContracts.RequestMultiplePermissions()
                    ) { result -> granted = result.values.all { it } }

                    Column(
                        modifier = Modifier.fillMaxSize().padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Text("Ground-Zero Mesh", style = MaterialTheme.typography.headlineSmall)
                        Text("Node ${NodeIdStore.get(this@MainActivity)}")
                        Text(if (granted) "Permissions: granted" else "Permissions: missing")

                        if (!granted) {
                            Button(onClick = {
                                requester.launch(MeshPermissions.runtimePermissions().toTypedArray())
                            }) { Text("Grant Nearby permissions") }
                        } else {
                            Button(onClick = {
                                if (running) MeshForegroundService.stop(this@MainActivity)
                                else MeshForegroundService.start(this@MainActivity)
                                running = !running
                            }) { Text(if (running) "Stop mesh" else "Start mesh") }
                        }
                    }
                }
            }
        }
    }
}
