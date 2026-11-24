package com.example.filesync

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val ctx = LocalContext.current
            val dark = isSystemInDarkTheme()
            val colors = when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                    if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
                dark -> darkColorScheme()
                else -> lightColorScheme()
            }
            MaterialTheme(colorScheme = colors) {
                Surface(Modifier.fillMaxSize()) { App() }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(vm: SyncViewModel = viewModel()) {
    val ctx = LocalContext.current
    val s by vm.state.collectAsState()

    fun grant(uri: Uri) {
        runCatching {
            ctx.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
    }

    val pickLeft = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) { grant(uri); vm.setFolder(true, uri) }
    }
    val pickRight = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) { grant(uri); vm.setFolder(false, uri) }
    }

    val visible = remember(s.items, s.showEqual) {
        if (s.showEqual) s.items else s.items.filter { it.action != Action.EQUAL }
    }
    val toRight = s.items.count { it.enabled && it.action == Action.TO_RIGHT }
    val toLeft = s.items.count { it.enabled && it.action == Action.TO_LEFT }
    val deletes = s.items.count {
        it.enabled && (it.action == Action.DELETE_LEFT || it.action == Action.DELETE_RIGHT)
    }
    val conflicts = s.items.count { it.action == Action.CONFLICT }
    val busy = s.busy != null

    Scaffold(topBar = { TopAppBar(title = { Text("FileSync") }) }) { pad ->
        Column(
            Modifier.padding(pad).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FolderCard("Left folder (source)", s.left, !busy) { pickLeft.launch(s.left) }
            FolderCard("Right folder (target)", s.right, !busy) { pickRight.launch(s.right) }

            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SyncMode.entries.forEach { m ->
                    FilterChip(
                        selected = s.mode == m,
                        onClick = { vm.setMode(m) },
                        enabled = !busy,
                        label = { Text(m.label) }
                    )
                }
            }
            Text(s.mode.description, style = MaterialTheme.typography.bodySmall)

            OutlinedTextField(
                value = s.excludes,
                onValueChange = vm::setExcludes,
                enabled = !busy,
                singleLine = true,
                label = { Text("Exclude (e.g. *.tmp, .thumbnails)") },
                modifier = Modifier.fillMaxWidth()
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = vm::compare,
                    enabled = s.left != null && s.right != null && !busy,
                    modifier = Modifier.weight(1f)
                ) { Text("Compare") }
                Button(
                    onClick = vm::requestSync,
                    enabled = !busy && (toRight + toLeft + deletes) > 0,
                    modifier = Modifier.weight(1f)
                ) { Text("Synchronize") }
            }

            if (busy) {
                Text(s.busy ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis)
                val p = s.progress
                if (p == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                else LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
            } else if (s.compared) {
                if (s.items.all { it.action == Action.EQUAL }) {
                    Text("Both folders are in sync.")
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "→ $toRight    ← $toLeft    ✕ $deletes    ⚠ $conflicts",
                            modifier = Modifier.weight(1f),
                            fontFamily = FontFamily.Monospace
                        )
                        Text("Show identical", style = MaterialTheme.typography.bodySmall)
                        Checkbox(checked = s.showEqual, onCheckedChange = vm::setShowEqual)
                    }
                }
            }

            HorizontalDivider()
            LazyColumn(Modifier.weight(1f)) {
                items(visible, key = { it.rel }) { item ->
                    ItemRow(item) { vm.tap(item.rel) }
                    HorizontalDivider()
                }
            }
        }
    }

    s.message?.let {
        AlertDialog(
            onDismissRequest = vm::dismissMessage,
            confirmButton = { TextButton(onClick = vm::dismissMessage) { Text("OK") } },
            text = { Text(it) }
        )
    }
    if (s.confirmDelete) {
        AlertDialog(
            onDismissRequest = vm::dismissConfirm,
            title = { Text("Delete files?") },
            text = { Text("$deletes file(s) will be permanently deleted. Continue?") },
            confirmButton = { TextButton(onClick = vm::sync) { Text("Synchronize") } },
            dismissButton = { TextButton(onClick = vm::dismissConfirm) { Text("Cancel") } }
        )
    }
}

@Composable
fun FolderCard(title: String, uri: Uri?, enabled: Boolean, onPick: () -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.labelMedium)
                Text(folderLabel(uri), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            FilledTonalButton(onClick = onPick, enabled = enabled) { Text("Select") }
        }
    }
}

@Composable
fun ItemRow(item: SyncItem, onClick: () -> Unit) {
    val dim = if (item.enabled || item.action == Action.EQUAL) 1f else 0.5f
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).alpha(dim).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            item.action.symbol,
            fontFamily = FontFamily.Monospace,
            fontSize = 18.sp,
            modifier = Modifier.width(52.dp)
        )
        Column(Modifier.weight(1f)) {
            Text(item.rel, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val hint = if (item.conflict) "\nChanged on both sides – tap to choose direction" else ""
            Text(
                "L: ${info(item.left)}\nR: ${info(item.right)}$hint",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (item.action != Action.EQUAL && !item.conflict) {
            Checkbox(checked = item.enabled, onCheckedChange = { onClick() })
        }
    }
}

// ---------- formatting helpers ----------

fun folderLabel(uri: Uri?): String {
    if (uri == null) return "No folder selected"
    return runCatching {
        val id = DocumentsContract.getTreeDocumentId(uri)
        if (id.startsWith("primary:")) "Internal storage/" + id.removePrefix("primary:")
        else id.replaceFirst(":", "/")
    }.getOrDefault(uri.toString())
}

fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var v = bytes / 1024.0
    var i = 0
    while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
    return String.format(Locale.US, "%.1f %s", v, units[i])
}

private fun info(e: FileEntry?): String =
    e?.let {
        val date = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(it.modified))
        "${formatSize(it.size)} · $date"
    } ?: "—"
