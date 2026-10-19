package com.example.pocketagent

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Loads the provider's model list (GET {baseUrl}/models), lets you search it,
 * and lets you type any model ID by hand if the list is missing or incomplete.
 */
@Composable
fun ModelPickerDialog(
    app: AppState,
    provider: Provider,
    current: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf("") }

    LaunchedEffect(provider.id, provider.baseUrl, provider.apiKey) { app.loadModels(provider) }

    val q = query.trim()
    val filtered = app.modelList.filter { it.contains(q, ignoreCase = true) }.take(200)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose model") },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Search, or type a model ID") }
                )
                Spacer(Modifier.height(8.dp))
                if (app.modelsLoading) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Loading models…", style = MaterialTheme.typography.bodySmall)
                    }
                }
                app.modelsError?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    if (q.isNotEmpty() && app.modelList.none { it.equals(q, ignoreCase = true) }) {
                        item { ModelRow("Use \"$q\"", false) { onPick(q) } }
                    }
                    items(filtered) { id -> ModelRow(id, id == current) { onPick(id) } }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun ModelRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (selected) Icon(Icons.Default.Check, contentDescription = "Selected")
    }
}
