
package com.example.irohdroid

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.MaterialTheme.typography
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import iroh.Backend


class MainActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        iroh.setLogLevel(iroh.LogLevel.TRACE)

        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                Column {
                    var backend by remember { mutableStateOf<Backend?>(null)}

                    LaunchedEffect(Unit) {
                        backend = Backend.create(
                            filesDir.absolutePath,
                        )
                    }

                    var nodeId by remember {
                        mutableStateOf("")
                    }

                    TopAppBar(title = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                "Irohdroid",
                                style = typography.headlineMedium,
                                color = colorScheme.primary
                            )
                            Icon(
                                if (backend != null) Icons.Default.RadioButtonChecked else Icons.Default.RadioButtonUnchecked,
                                contentDescription = null,
                                tint = colorScheme.primary
                            )
                            if (nodeId != "") {
                                Text(
                                    "ID: ${nodeId.take(12)}..", color = colorScheme.primary,
                                )
                            }
                        }
                    })
                    TopRowNavigation(listOf("Documents", "Info")) { state ->
                        run {
                            PermissionsCheck()

                            backend?.also { backend -> run {
                                val node = backend.node()
                                var documents by remember {
                                    mutableStateOf<List<iroh.NamespaceAndCapability>>(
                                        listOf()
                                    )
                                }

                                LaunchedEffect(Unit) {
                                    documents = node.docList()
                                    nodeId = node.nodeId()
                                }
                                when (state) {
                                    "Documents" -> run {
                                        Documents(backend, documents) {
                                            documents = node.docList()
                                        }
                                    }
                                    "Info" -> run {
                                        NodeInfo(node)
                                    }
                                }
                            }}
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PermissionsCheck() {
    var hasPermissions by remember {
        mutableStateOf(Environment.isExternalStorageManager())
    }

    if (!hasPermissions) {
        val context = LocalContext.current

        val permissions = rememberLauncherForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { _ -> run {
            hasPermissions = Environment.isExternalStorageManager()
        }
        }

        Text("The manage all files permission is required")
        
        Button(onClick = {
            permissions.launch(Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:${context.packageName}")
            ))
        }) {
            Text("Get Permissions")
        }
    }
}
