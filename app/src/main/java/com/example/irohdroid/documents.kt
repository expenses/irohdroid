package com.example.irohdroid

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FileDownloadDone
import androidx.compose.material.icons.filled.FilePresent
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme.typography
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableLongState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import iroh.Backend
import iroh.IrohNode
import iroh.ShareMode
import kotlinx.coroutines.launch


@Composable
fun Source(source: String, onDelete: () -> Unit) {
    Card(modifier = Modifier.padding(8.dp, 0.dp)) {
        RowWithButtons(start = {
            Text(
                source,
                modifier = Modifier.padding(8.dp, 0.dp)
            )
        }, end = {
            SimpleIconButton(icon = Icons.Default.Close) {
                onDelete()
            }
        })
    }
}

@Composable
fun Picker(intent: String, onSelect: (Uri) -> Unit, wrapper: (@Composable (() -> Unit) -> Unit)) {
    val directoryPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        result.data?.data?.also { uri -> onSelect(uri)}
    }
    wrapper {
        directoryPicker.launch(Intent(intent))
    }
}

@Composable
fun AddSourceButton(backend: Backend, namespace: String, onUpdate: () -> Unit) {
    Picker(
        intent = Intent.ACTION_OPEN_DOCUMENT_TREE,
        onSelect = {
            uri -> run {
            backend.addSourceToDocument(namespace, uriToRealPath(uri).toString())
            onUpdate()
        }},
            wrapper = {
                onClick -> SimpleIconButton(Icons.Default.Add, onClick=onClick)
            }
    )
}

@Composable
fun ShareButton(node: IrohNode, namespace: String) {
    val scope = rememberCoroutineScope()
    var dialogOpen by remember {
        mutableStateOf(false)
    }
    var shareWrite by remember {
         mutableStateOf(false)
    }

    var ticket by remember {
        mutableStateOf("")
    }

    val onClose = {
        dialogOpen = false
    }

    val onUpdate = {
        dialogOpen = true
        scope.launch {
            val doc = node.docOpen(namespace)
            doc?.share(
                if (shareWrite) ShareMode.WRITE else ShareMode.READ,
                iroh.AddrInfoOptions.RELAY_AND_ADDRESSES
            )?.also { shareTicket ->
                run {
                    ticket = shareTicket
                }
            }}
    }

    SimpleIconButton(icon = Icons.Default.Share, onClick= {
        onUpdate()
    })

    if (dialogOpen) {
        ShareDialog(ticket, onClose, shareWrite, onChangeShareWrite = {
                shareWrite = it
                onUpdate()
        })
    }
}

@Composable
fun ShareDialog(ticket: String, onClose: () -> Unit, shareWrite: Boolean, onChangeShareWrite: (Boolean) -> Unit) {
    val clipboardManager = LocalClipboardManager.current

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
            ,
            shape = RoundedCornerShape(16.dp),

            ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("Share Document")
                QrCode(ticket, modifier = Modifier
                    .width(400.dp)
                    .height(400.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        ) {
                        Text("Share Write: ")
                        Switch(checked = shareWrite, onCheckedChange = onChangeShareWrite)
                    }
                    Row {
                        SimpleIconButton(Icons.Default.ContentCopy, onClick = {
                            clipboardManager.setText(AnnotatedString(ticket))
                        })
                        SimpleIconButton(Icons.Default.Close, onClick=onClose)
                    }
                }
            }
        }
    }
}

@Composable
fun Sources(backend: Backend, namespace: String, onAdd: () -> Unit) {
    var sources by remember {
        mutableStateOf(backend.sourcesForDocument(namespace))
    }

    sources.forEach { source ->
        run {
            Source(source) {
                backend.removeSourceFromDocument(namespace, source)
                sources = backend.sourcesForDocument(namespace)
            }
        }
    }
    AddSourceButton(backend, namespace) {
        sources = backend.sourcesForDocument(namespace)
        onAdd()
    }
}

@Composable
fun InlineIcon(icon: ImageVector, text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, Modifier.size(16.dp))
        Text(text, style = typography.labelMedium)
    }
}

@Composable
fun Document(backend: Backend, namespace: String, onUpdate: suspend () -> Unit) {
    val node = backend.node()

    var showSources by remember {
        mutableStateOf(false)
    }

    val scope = rememberCoroutineScope()

    val totalFiles = remember {
        mutableLongStateOf(backend.numFilesForDocument(namespace).toLong())
    }

    val progress = remember {
        mutableLongStateOf(0)
    }

    val toUpdate = remember {
        mutableLongStateOf(0)
    }

    @Suppress("NAME_SHADOWING")
    val update = {
        scope.launch {
            val doc = node.docOpen(namespace)
            doc?.also {
                    doc -> run {
                backend.addFileTree(doc, namespace, node.authorDefault(),
                    inPlace = true,
                    recheck = false,
                    cb = Callback(progress, toUpdate, totalFiles)
                )
            }
            }
        }
    }

    Card(
        modifier = Modifier.padding(8.dp)
    ) {
        RowWithButtons(start = {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(namespace.take(10) + "..", style = typography.titleMedium)
                Row {
                    InlineIcon(
                        Icons.Default.FilePresent,
                        totalFiles.longValue.toString()
                    )
                    if (toUpdate.longValue != 0.toLong()) {
                        InlineIcon(Icons.Default.FileDownloadDone, "${progress.longValue}/${toUpdate.longValue}")
                    }
                }
            }
        }, end = {
            ShareButton(node, namespace)
            SimpleIconButton(icon = Icons.Default.Refresh, onClick= {
                update()
            })
            SimpleIconButton(icon = Icons.Default.Close) {
                scope.launch {
                    node.docDrop(namespace)
                    onUpdate()
                }
            }
            SimpleIconButton(icon = if (showSources) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown) {
                showSources = !showSources
            }
        })
        if (showSources) {
            Sources(backend, namespace, onAdd = {
                update()
            })
        }
    }
}

@Composable
fun Documents(backend: Backend, documents: List<iroh.NamespaceAndCapability>, onUpdate: suspend () -> Unit) {
    val scope = rememberCoroutineScope()

    LazyColumn {
        documents.forEach { document ->
            run {
                item {
                    Document(backend, document.namespace, onUpdate)
                }
            }
        }
    }
    SimpleIconButton(icon = Icons.Default.Add) {
        scope.launch { backend.node().docCreate()
            onUpdate()}
    }
}


class Callback(
    private val progress: MutableLongState,
    private val toUpdate: MutableLongState,
    private val total: MutableLongState
): iroh.ImportTreeCallback {
    override suspend fun progress() {
        this.progress.longValue++
    }

    override suspend fun toUpdate(toUpdate: ULong) {
        this.toUpdate.longValue = toUpdate.toLong()
    }

    override suspend fun total(total: ULong) {
        this.total.longValue = total.toLong()
    }
}
