package com.example.irohdroid

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MaterialTheme.typography
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import iroh.Iroh
import kotlinx.coroutines.delay
import java.time.Duration

fun formatOptDuration(duration: Duration?): Float? {
    return duration?.let {duration.toMillis() / 1000.0f}  ?: run {null}
}

@Composable
fun ExpandableCard(title: String, inner: (@Composable () -> Unit)) {
    var expanded by remember {
        mutableStateOf(true)
    }

    Card(
        modifier = Modifier
            .padding(8.dp)
            .fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = title,
                    style = typography.headlineSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                SimpleIconButton(if (expanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp) {
                    expanded = !expanded
                }
            }

            if (expanded) {
                inner()
            }
        }
    }
}

@Suppress("NAME_SHADOWING")
@Composable
fun NodeInfo(node: Iroh) {
    var id by remember {
        mutableStateOf("")
    }

    var status by remember {
        mutableStateOf<iroh.NodeStatus?>(null)
    }

    var connections by remember {
        mutableStateOf<List<iroh.ConnectionInfo>>(listOf())
    }

    LaunchedEffect(Unit) {
        id = node.node().nodeId()
        status = node.node().status()

        while (true) {
            delay(100)
            connections = node.node().connections()
        }
    }


    status?.also { status -> run {
        ExpandableCard("Listen Addresses") {
            status.listenAddrs().forEach { addr ->
                run {
                    Text(addr)
                }
            }
        }
    }}

    ExpandableCard("Connections") {
        if (connections.isNotEmpty()) {
            connections.forEach { conn ->
                run {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(conn.nodeId.toString().take(10) + "..")
                        Text(conn.connType.type().toString())
                        Text(formatOptDuration(conn.lastUsed).toString())
                        Text(formatOptDuration(conn.latency).toString())
                    }
                }
            }
        } else {
            Text("No Connections")
        }
    }
}
