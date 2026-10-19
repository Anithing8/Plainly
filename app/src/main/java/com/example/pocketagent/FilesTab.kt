package com.example.pocketagent

import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import java.io.File

/** Opens the Android share sheet for a file in the workspace. */
fun shareWorkspaceFile(ctx: Context, file: File, mime: String) {
    val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    ctx.startActivity(Intent.createChooser(send, "Share"))
}

/** "Save to phone" and "Share" buttons for a zip the AI made. */
@Composable
fun ZipActions(app: AppState, path: String) {
    val ctx = LocalContext.current
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) app.saveWorkspaceFileTo(uri, path)
    }
    Row {
        TextButton(onClick = { saver.launch(path.substringAfterLast('/')) }) { Text("Save to phone") }
        TextButton(
            onClick = {
                try {
                    shareWorkspaceFile(ctx, app.workspaceFile(path), "application/zip")
                } catch (_: Exception) {
                }
            }
        ) { Text("Share") }
    }
}

@Composable
fun FilesTab(app: AppState) {
    val files = remember(app.filesVersion, app.loading) { app.workspaceFiles() }
    var open by remember { mutableStateOf<String?>(null) }
    var repoUrl by remember { mutableStateOf("") }
    var token by remember { mutableStateOf(app.githubToken) }
    var confirmClear by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) app.addUploads(uris)
    }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Text(
                "This is the AI's private workspace. Files you upload, repos you import and projects it builds all live here.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        item {
            OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) {
                Text("Upload files")
            }
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Import a GitHub repo", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Downloads the repo into the workspace so the AI can read it. You can also just paste a repo link in the chat.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedTextField(
                        value = repoUrl,
                        onValueChange = { repoUrl = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text("https://github.com/owner/repo") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(
                            onClick = { app.importRepo(repoUrl) },
                            enabled = !app.repoBusy && repoUrl.isNotBlank()
                        ) { Text("Import") }
                        if (app.repoBusy) {
                            Spacer(Modifier.width(12.dp))
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        }
                    }
                    app.repoStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    HorizontalDivider()
                    OutlinedTextField(
                        value = token,
                        onValueChange = { token = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("GitHub token (only for private repos)") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                    )
                    Text(
                        "Create one at github.com/settings/tokens with read access to the repo contents. " +
                            "It is stored encrypted and also used when importing skills.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(onClick = { app.saveGithubToken(token) }) { Text("Save token") }
                }
            }
        }

        if (files.isEmpty()) {
            item { Text("No files yet. Upload some, import a repo, or ask the AI to build something.") }
        }

        items(files.take(500), key = { it }) { path ->
            val isZip = path.endsWith(".zip", ignoreCase = true)
            Card(Modifier.fillMaxWidth().then(if (isZip) Modifier else Modifier.clickable { open = path })) {
                Column(Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp, end = 4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(path, Modifier.weight(1f), fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                        IconButton(onClick = { app.deleteWorkspaceFile(path) }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete file")
                        }
                    }
                    if (isZip) ZipActions(app, path)
                }
            }
        }

        if (files.size > 500) {
            item { Text("… and ${files.size - 500} more files.", style = MaterialTheme.typography.bodySmall) }
        }

        if (files.isNotEmpty()) {
            item {
                TextButton(onClick = { confirmClear = true }) {
                    Text("Delete everything in the workspace", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }

    open?.let { path -> FileViewer(app, path) { open = null } }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Delete all workspace files?") },
            text = { Text("This removes uploads, imported repos, built projects and zips. It can't be undone.") },
            confirmButton = {
                TextButton(onClick = { app.clearWorkspace(); confirmClear = false }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun FileViewer(app: AppState, path: String, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val content = remember(path, app.filesVersion) { app.readWorkspaceFile(path) }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(path, fontSize = 16.sp) },
        text = {
            Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                SelectionContainer {
                    Text(content, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } },
        dismissButton = {
            Row {
                TextButton(onClick = { clipboard.setText(AnnotatedString(content)) }) { Text("Copy") }
                TextButton(
                    onClick = {
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, content)
                            putExtra(Intent.EXTRA_SUBJECT, path)
                        }
                        ctx.startActivity(Intent.createChooser(send, "Share file"))
                    }
                ) { Text("Share") }
            }
        }
    )
}
