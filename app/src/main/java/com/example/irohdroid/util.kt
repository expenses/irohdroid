package com.example.irohdroid

import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme.typography
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector


@Composable
fun SimpleIconButton(icon: ImageVector, modifier: Modifier = Modifier, onClick: () -> Unit) {
    IconButton(onClick, modifier) {
        Icon(icon, null)
    }
}

@Composable
fun RowWithButtons(
    start: (@Composable () -> Unit),
    end: (@Composable () -> Unit),
) {
    Row(
        verticalAlignment = Alignment.CenterVertically
    ) {
        start()
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.End
        ) {
            end()
        }
    }
}

fun uriToRealPath(uri: Uri): String? {
    uri.path?.also { path -> run {
        val pathSections = path.split(":")
        return Environment.getExternalStorageDirectory().path + "/" + pathSections.last()
    }}

    return null
}

@Composable
fun TopRowNavigation(states: List<String>, handler: (@Composable (String) -> Unit)) {
    var currentIndex by remember {
        mutableIntStateOf(0)
    }
    TabRow(selectedTabIndex = currentIndex) {
        states.forEachIndexed { index, state ->
            Tab(
                text = { Text(state, style = typography.bodyLarge) },
                selected = index == currentIndex,
                onClick = { currentIndex = index }
            )
        }
    }
    handler(states[currentIndex])
}

@Composable
fun QrCode(string: String, modifier: Modifier = Modifier) {
    val qrcode = iroh.createQrCode(string)
    val height = qrcode.bytes.size / qrcode.width
    val bitmap = Bitmap.createBitmap(qrcode.width, height, Bitmap.Config.ALPHA_8)
    bitmap.setPixels(qrcode.bytes.map { it.toInt() }.toIntArray(), 0, qrcode.width, 0, 0, qrcode.width, height)
    Image(bitmap = bitmap.asImageBitmap(), contentDescription = null, modifier)
}