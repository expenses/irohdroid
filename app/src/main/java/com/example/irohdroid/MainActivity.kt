@file:Suppress("NAME_SHADOWING")

package com.example.irohdroid

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MaterialTheme.typography
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import iroh.AuthorId
import iroh.Backend
import iroh.IrohNode
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.time.Duration
import kotlin.concurrent.thread


class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        iroh.setLogLevel(iroh.LogLevel.TRACE)

        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(15.dp),
                ) {
                    Text(
                        text = "Irohdroid",
                        style = typography.headlineLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    PermissionsCheck()

                    Node(filesDir)
                }
            }
        }
    }
}

@Composable
fun PermissionsCheck() {
    val hasPermissions = remember {
        mutableStateOf(Environment.isExternalStorageManager())
    }

    if (!hasPermissions.value) {
        val context = LocalContext.current

        val permissions = rememberLauncherForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { _ -> run {
            hasPermissions.value = Environment.isExternalStorageManager()
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

@Composable
fun Node(filesDir: File, modifier: Modifier = Modifier) {
    val backend = remember { mutableStateOf<Backend?>(null)}

    LaunchedEffect(Unit) {
        backend.value = Backend.create(
                filesDir.absolutePath,
            )

    }

    backend.value?.also { backend -> run {
        NodeInfo(backend.node())
        //Authors(iroh_node)
        Docs(backend)
    }} ?: run {
        Text("Node starting...", modifier=modifier)
    }
}

fun formatOptDuration(duration: Duration?): Float? {
    return duration?.let {duration.toMillis() / 1000.0f}  ?: run {null}
}

@Composable
fun NodeInfo(node: IrohNode) {
    val id = node.nodeId()
    val clipboardManager = LocalClipboardManager.current

    val status = remember {
        mutableStateOf<iroh.NodeStatus?>(null)
    }

    val connections = remember {
        mutableStateOf<List<iroh.ConnectionInfo>>(listOf())
    }

    LaunchedEffect(Unit) {
        status.value = node.status()
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(100)
            connections.value = node.connections()
        }
    }

    //var default_author = node?.authorDefault()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Node ID:")
        Button(
            modifier = Modifier.defaultMinSize(minWidth = 1.dp, minHeight = 1.dp),
            onClick = {
                clipboardManager.setText(AnnotatedString(id))
            }
        ) {
            Text(id.take(16) + "..")
        }
    }

    status.value?.also { status -> run {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Version:")
            Text(status.version())
        }

        Text(
            text = "Listen Addresses",
            style = typography.headlineSmall,
            color = MaterialTheme.colorScheme.primary
        )

        status.listenAddrs().forEach { addr ->
            run {
                Text(addr)
            }
        }
    }}

    Text(
        text = "Connections",
        style = typography.headlineSmall,
        color = MaterialTheme.colorScheme.primary
    )

    connections.value.forEach { conn -> run {
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(conn.nodeId.toString().take(16) + "..")
            Text(conn.connType.type().toString())
            Text(formatOptDuration(conn.lastUsed).toString())
            Text(formatOptDuration(conn.latency).toString())
        }}

    }
}

@Composable
fun Authors(node: IrohNode) {
    val authors = remember { mutableStateOf<List<AuthorId>>(listOf())}
    val defaultAuthor = remember { mutableStateOf<AuthorId?>(null)}

    LaunchedEffect(Unit) {
        authors.value = node.authorList()
        defaultAuthor.value = node.authorDefault()
    }

    Text(
        text = "Authors",
        style = typography.headlineMedium,
        color = MaterialTheme.colorScheme.primary
    )

    val scope = rememberCoroutineScope()

    defaultAuthor.value?.also { defaultAuthor -> run {
        authors.value.forEach { author -> run {
            Row {
                Text(author.toString(), style = typography.labelSmall)
                if (!defaultAuthor.equal(author)) {
                    Button(onClick = {
                        scope.launch {
                            node.authorDelete(author)
                            authors.value = node.authorList()
                        }
                    }) {
                        Text("Delete")
                    }
                } else {
                    Text("(default)", style = typography.labelSmall)
                }
            }
        }}
    }}

    Button(
        onClick = {
            scope.launch {
                node.authorCreate()
                authors.value = node.authorList()
            }
        }
    ) {
        Text("New Author")
    }
}

fun uriToRealPath(uri: Uri): String? {
    uri.path?.also { path -> run {
        val pathSections = path.split(":")
        return Environment.getExternalStorageDirectory().path + "/" + pathSections.last()
    }}

    return null
}

class Callback(
    private val aborts: MutableState<Int>,
    private val found: MutableState<Int>,
    private val done: MutableState<Int>
): iroh.DocImportFileCallback {
    override suspend fun progress(progress: iroh.DocImportProgress) {
        Log.d(null, progress.type().toString())

        when (progress.type()) {
            iroh.DocImportProgressType.ABORT -> run {
                val abort = progress.asAbort()
                Log.d(null, abort.error)
                aborts.value += 1
            }
            iroh.DocImportProgressType.ALL_DONE -> run {
                done.value ++
            }
            iroh.DocImportProgressType.FOUND -> run {
                found.value ++
            }
            else -> {}
        }
    }
}

@Composable
fun Document(backend: Backend, defaultAuthor: AuthorId, document: iroh.NamespaceAndCapability, onDelete: suspend () -> Unit) {
    val node = backend.node()

    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current

    val doc = remember {
        mutableStateOf<iroh.Doc?>(null)
    }

    LaunchedEffect(Unit) {
        doc.value = node.docOpen(document.namespace)
    }

    val scope = rememberCoroutineScope()

    val sources = remember {
        mutableStateListOf<DocumentFile>()
    }

    val directoryIntent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)

    val aborts = remember {
        mutableIntStateOf(0)
    }

    val done = remember {
        mutableIntStateOf(0)
    }

    val found = remember {
        mutableIntStateOf(0)
    }

    doc.value?.also  { doc -> run {
        val import = { source: DocumentFile ->
            thread {
                scope.launch {
                    backend.addFileTree(doc, defaultAuthor, uriToRealPath(source.uri).toString(), true, Callback(aborts, found, done))
                }
            }
        }

        val directoryPicker = rememberLauncherForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            result.data?.data?.also { data -> run {
                // https://stackoverflow.com/a/74683972
                val documentFile = DocumentFile.fromTreeUri(context, data)
                documentFile?.let {
                    sources.add(documentFile)
                    import(documentFile)
                }
            }}
        }

        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(document.namespace.take(16) + "..")
                Text(document.capability.toString())
                Text("${found.intValue} / ${done.intValue} (${aborts.intValue})")
            }
            sources.forEach { source -> Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(source.name.toString())
                IconButton(onClick = {
                    import(source)
                }) {
                    Icon(Icons.Default.Refresh, null)
                }
                IconButton(onClick = { sources.remove(source) }) {
                    Icon(Icons.Default.Close, null)
                }
            }}
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Button(onClick = {
                    directoryPicker.launch(directoryIntent)
                }) {
                    Text("Add Source")
                }
                IconButton(onClick = {
                    scope.launch {
                        val ticket = doc.share(
                            iroh.ShareMode.WRITE,
                            iroh.AddrInfoOptions.RELAY_AND_ADDRESSES
                        )
                        clipboardManager.setText(AnnotatedString(ticket))
                    }
                }) {
                    Icon(Icons.Default.Share, null)
                }
                IconButton(onClick = {
                    scope.launch {
                        node.docDrop(document.namespace)
                        onDelete()
                    }
                }) {
                    Icon(Icons.Default.Close, null)
                }
            }
        }
    }}
}

@Composable
fun Docs(backend: Backend) {
    val node = backend.node()

    val documents = remember { mutableStateOf<List<iroh.NamespaceAndCapability>>(listOf())}
    val defaultAuthor = remember {
        mutableStateOf<AuthorId?>(null)
    }

    LaunchedEffect(Unit) {
            documents.value = node.docList()
        defaultAuthor.value = node.authorDefault()

    }

    defaultAuthor.value?.also { defaultAuthor ->
        run {
            Text(
                text = "Documents",
                style = typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary
            )
            documents.value.forEach { document ->
                run {
                    Document(backend, defaultAuthor, document, onDelete = {
                        documents.value = node.docList()
                    })
                }
            }
        }
    }

    val scope = rememberCoroutineScope()

    Row {
        Button(onClick = {scope.launch {
            node.docCreate()
            documents.value = node.docList()
        }}) {
            Text("New Document")
        }
    }
}