package org.groundzero.mesh.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
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
import org.groundzero.mesh.app.node.NodeScreen
import org.groundzero.mesh.app.node.NodeViewModel
import org.groundzero.mesh.app.permissions.MeshPermissions
import org.groundzero.mesh.app.service.MeshForegroundService

class MainActivity : ComponentActivity() {

    private val nodeViewModel: NodeViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    var granted by remember { mutableStateOf(MeshPermissions.allGranted(this)) }
                    val requester = rememberLauncherForActivityResult(
                        ActivityResultContracts.RequestMultiplePermissions()
                    ) { result -> granted = result.values.all { it } }

                    if (!granted) {
                        Column(Modifier.padding(16.dp)) {
                            Text("Nearby permissions are required.")
                            Button(onClick = {
                                requester.launch(MeshPermissions.runtimePermissions().toTypedArray())
                            }) { Text("Grant") }
                        }
                    } else {
                        NodeScreen(nodeViewModel)
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
