package org.groundzero.mesh.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.groundzero.mesh.app.mesh.MeshStack
import org.groundzero.mesh.app.node.NodeScreen
import org.groundzero.mesh.app.node.NodeViewModel
import org.groundzero.mesh.app.node.RelayHostStore
import org.groundzero.mesh.app.node.RoleStore
import org.groundzero.mesh.app.permissions.MeshPermissions
import org.groundzero.mesh.app.service.MeshForegroundService

class MainActivity : ComponentActivity() {

    private val nodeViewModel: NodeViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // NodeViewModel seeds its role from MeshStack, which is the right source once the
        // service has installed it — but on a cold start the Activity composes first, and an
        // uninstalled stack reports NODE. The screen then showed a victim's SEND SOS button
        // on a phone whose service was about to restore GATEWAY from RoleStore: exactly the
        // divergence RoleStore exists to prevent, arriving through the one gap it did not
        // cover. Before the stack exists, the persisted role is the honest answer.
        if (!MeshStack.isInstalled) nodeViewModel.selectRole(RoleStore.get(this))
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    var granted by remember { mutableStateOf(MeshPermissions.allGranted(this)) }
                    val requester = rememberLauncherForActivityResult(
                        ActivityResultContracts.RequestMultiplePermissions()
                    ) { result ->
                        granted = result.values.all { it }
                        // onStart already missed its chance to do this: it only checks
                        // permissions once, on the way in, before this dialog has even
                        // shown. Without this, the service never starts until the app is
                        // backgrounded and reopened — a silent gap between "permissions
                        // granted" and "mesh actually running".
                        if (granted) MeshForegroundService.start(this)
                    }

                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        // Reachable before the Nearby permission gate below, not after: the
                        // service reads this once, in onCreate, so it must be set before the
                        // service ever starts, not after the mesh screen is already showing.
                        // Blank means the real radio — see RelayHostStore's own doc.
                        var relayHost by remember { mutableStateOf(RelayHostStore.get(this@MainActivity)) }
                        OutlinedTextField(
                            value = relayHost,
                            onValueChange = {
                                relayHost = it
                                RelayHostStore.set(this@MainActivity, it)
                            },
                            label = { Text("Laptop relay (optional)") },
                            placeholder = { Text("blank = real Nearby radio") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            "host or host:port of a laptop running ./gradlew :core:runRelay — " +
                                "changing this needs an app restart to take effect",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        HorizontalDivider()

                        if (!granted) {
                            Text("Nearby permissions are required.")
                            Button(onClick = {
                                requester.launch(MeshPermissions.runtimePermissions().toTypedArray())
                            }) { Text("Grant") }
                        } else {
                            NodeScreen(nodeViewModel)
                        }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (MeshPermissions.allGranted(this)) MeshForegroundService.start(this)
    }
}
