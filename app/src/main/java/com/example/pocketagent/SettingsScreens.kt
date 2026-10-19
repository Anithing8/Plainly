package com.example.pocketagent

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.UUID

// ---------- SETTINGS SHELL ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(app: AppState, tab: Int, onTab: (Int) -> Unit, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("Settings", fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                )
                ScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp) {
                    listOf("Providers", "Skills", "Files", "Usage", "Termux").forEachIndexed { i, title ->
                        Tab(selected = tab == i, onClick = { onTab(i) }, text = { Text(title) })
                    }
                }
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad)) {
            when (tab) {
                0 -> ProvidersTab(app)
                1 -> SkillsTab(app)
                2 -> FilesTab(app)
                3 -> UsageTab(app)
                else -> TermuxTab(app)
            }
        }
    }
}

// ---------- PROVIDERS ----------

@Composable
fun ProvidersTab(app: AppState) {
    var editing by remember { mutableStateOf<Provider?>(null) }
    var adding by remember { mutableStateOf(false) }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Auto-switch on limits", fontWeight = FontWeight.SemiBold)
                        Text(
                            "If the active provider hits a rate limit, runs out of credit or fails, the request " +
                                "is retried on the next provider below that has \"Use as fallback\" on (top to bottom).",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(checked = app.autoSwitch, onCheckedChange = { app.setAutoSwitchEnabled(it) })
                }
            }
        }

        itemsIndexed(app.providers, key = { _, p -> p.id }) { i, p ->
            val active = p.id == app.selected?.id
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = active, onClick = { app.select(p.id) })
                        Column(Modifier.weight(1f).clickable { editing = p }) {
                            Text(
                                if (active) "${p.name} (active)" else p.name,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                "${p.model} · ${Uri.parse(p.baseUrl).host ?: p.baseUrl}",
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                "Key ••••${p.apiKey.takeLast(4)}",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                        IconButton(onClick = { editing = p }) { Icon(Icons.Default.Edit, "Edit") }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = p.fallback, onCheckedChange = { app.upsertProvider(p.copy(fallback = it)) })
                        Spacer(Modifier.width(8.dp))
                        Text("Use as fallback", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        IconButton(onClick = { app.moveProvider(p.id, -1) }, enabled = i > 0) {
                            Icon(Icons.Default.KeyboardArrowUp, "Move up")
                        }
                        IconButton(onClick = { app.moveProvider(p.id, 1) }, enabled = i < app.providers.size - 1) {
                            Icon(Icons.Default.KeyboardArrowDown, "Move down")
                        }
                    }
                }
            }
        }

        item {
            Button(onClick = { adding = true }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Add provider")
            }
        }

        item {
            Text(
                "Keys are stored encrypted on this phone and are only sent to the provider you add them for. " +
                    "Fallback is meant for different providers (for example OpenRouter, then DeepSeek, then Gemini). " +
                    "Rotating several accounts of the same provider to get around its limits may break that " +
                    "provider's terms.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    if (adding) {
        ProviderDialog(
            app = app,
            initial = null,
            onSave = { app.upsertProvider(it); adding = false },
            onDelete = null,
            onDismiss = { adding = false }
        )
    }
    editing?.let { p ->
        ProviderDialog(
            app = app,
            initial = p,
            onSave = { app.upsertProvider(it); editing = null },
            onDelete = { app.deleteProvider(p.id); editing = null },
            onDismiss = { editing = null }
        )
    }
}

@Composable
private fun Field(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    password: Boolean = false,
    keyboard: KeyboardType = KeyboardType.Text
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else keyboard)
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProviderDialog(
    app: AppState,
    initial: Provider?,
    onSave: (Provider) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var url by remember { mutableStateOf(initial?.baseUrl ?: "") }
    var key by remember { mutableStateOf(initial?.apiKey ?: "") }
    var model by remember { mutableStateOf(initial?.model ?: "") }
    var priceIn by remember { mutableStateOf(initial?.priceIn?.takeIf { it > 0 }?.toString() ?: "") }
    var priceOut by remember { mutableStateOf(initial?.priceOut?.takeIf { it > 0 }?.toString() ?: "") }
    var maxTokens by remember { mutableStateOf((initial?.maxTokens ?: 2048).toString()) }
    var showPicker by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf("") }

    val valid = name.isNotBlank() && url.isNotBlank() && key.isNotBlank() && model.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Add provider" else "Edit provider") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (initial == null) {
                    Text("Quick fill", style = MaterialTheme.typography.labelMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        PRESETS.forEach { pr ->
                            AssistChip(
                                onClick = { name = pr.name; url = pr.url; model = pr.model; hint = pr.hint },
                                label = { Text(pr.name) }
                            )
                        }
                    }
                }
                if (hint.isNotBlank()) {
                    Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
                Field(name, { name = it }, "Name")
                Field(url, { url = it }, "Base URL", keyboard = KeyboardType.Uri)
                Field(key, { key = it }, "API key", password = true)
                Field(model, { model = it }, "Model ID")
                TextButton(onClick = { showPicker = true }, enabled = url.isNotBlank() && key.isNotBlank()) {
                    Text("Pick model from provider's list")
                }
                Field(maxTokens, { maxTokens = it }, "Max reply tokens", keyboard = KeyboardType.Number)
                Text(
                    "Lower this if your credit is low: providers reserve credit for the full reply size.",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    "Optional: price per 1M tokens (USD), used to estimate cost for providers that don't report it.",
                    style = MaterialTheme.typography.bodySmall
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.weight(1f)) { Field(priceIn, { priceIn = it }, "Input $", keyboard = KeyboardType.Decimal) }
                    Box(Modifier.weight(1f)) { Field(priceOut, { priceOut = it }, "Output $", keyboard = KeyboardType.Decimal) }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    onSave(
                        Provider(
                            id = initial?.id ?: UUID.randomUUID().toString(),
                            name = name.trim(),
                            baseUrl = url.trim().trimEnd('/').removeSuffix("/chat/completions"),
                            apiKey = key.trim(),
                            model = model.trim(),
                            fallback = initial?.fallback ?: true,
                            priceIn = priceIn.toDoubleOrNull() ?: 0.0,
                            priceOut = priceOut.toDoubleOrNull() ?: 0.0,
                            maxTokens = (maxTokens.toIntOrNull() ?: 2048).coerceAtLeast(16),
                            recent = initial?.recent ?: emptyList()
                        )
                    )
                }
            ) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (onDelete != null) {
                    TextButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )

    if (showPicker) {
        ModelPickerDialog(
            app = app,
            provider = Provider(
                id = initial?.id ?: "new",
                name = name.ifBlank { "provider" },
                baseUrl = url.trim().trimEnd('/').removeSuffix("/chat/completions"),
                apiKey = key.trim(),
                model = model
            ),
            current = model,
            onPick = { model = it; showPicker = false },
            onDismiss = { showPicker = false }
        )
    }
}

// ---------- SKILLS ----------

@Composable
fun SkillsTab(app: AppState) {
    var url by remember { mutableStateOf("") }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Add skills from GitHub", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Paste a public repo or folder link that contains SKILL.md files.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text("https://github.com/owner/repo") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(
                            onClick = { app.importSkills(url) },
                            enabled = !app.skillBusy && url.isNotBlank()
                        ) { Text("Add skills") }
                        if (app.skillBusy) {
                            Spacer(Modifier.width(12.dp))
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        }
                    }
                    app.skillStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }

        if (app.skills.isEmpty()) {
            item {
                Text(
                    "No skills installed yet.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        items(app.skills, key = { it.id }) { s ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(s.name, fontWeight = FontWeight.SemiBold)
                        Text(
                            s.description.ifBlank { "(no description)" },
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(s.source, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                    Switch(checked = s.enabled, onCheckedChange = { app.toggleSkill(s.id, it) })
                    IconButton(onClick = { app.deleteSkill(s.id) }) { Icon(Icons.Default.Delete, "Remove skill") }
                }
            }
        }

        item {
            Text(
                "Skills are text instructions. The model sees each skill's name and description and loads the full " +
                    "text on demand. Nothing from a skill is executed. Only add repos you trust, since their text " +
                    "is passed to the AI model. Type /skill-name in a message to load that skill. For private repos, add a GitHub token in the Files tab.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ---------- USAGE ----------

@Composable
fun UsageTab(app: AppState) {
    val total = app.usage.values.fold(Usage()) { a, u ->
        Usage(
            a.requests + u.requests,
            a.promptTokens + u.promptTokens,
            a.completionTokens + u.completionTokens,
            a.cost + u.cost,
            a.unpriced + u.unpriced
        )
    }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("All providers", fontWeight = FontWeight.SemiBold)
                    Text(
                        if (total.cost > 0 || total.requests == 0) fmtCost(total.cost) else "—",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        "${total.requests} requests · ${fmtTokens(total.promptTokens)} in · ${fmtTokens(total.completionTokens)} out",
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (total.unpriced > 0) {
                        Text(
                            "${total.unpriced} request(s) had no price data, so the total is a minimum.",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
        }

        items(app.providers, key = { it.id }) { p ->
            val u = app.usage[p.id] ?: Usage()
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(p.name, fontWeight = FontWeight.SemiBold)
                    Text(
                        "${u.requests} requests · ${fmtTokens(u.promptTokens)} in · ${fmtTokens(u.completionTokens)} out",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(costLine(u), style = MaterialTheme.typography.bodySmall)
                    if (p.isOpenRouter) {
                        OutlinedButton(onClick = { app.checkBalance(p) }) { Text("Check key credit") }
                        app.balances[p.id]?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        }

        item {
            OutlinedButton(onClick = { app.resetUsage() }, modifier = Modifier.fillMaxWidth()) {
                Text("Reset counters")
            }
        }

        item {
            HorizontalDivider()
            Spacer(Modifier.size(8.dp))
            Text(
                "Cost is exact for OpenRouter (reported with every reply). For other providers, enter token prices " +
                    "in the provider settings to get an estimate. Remaining balances for those are only shown on the " +
                    "provider's own dashboard.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
