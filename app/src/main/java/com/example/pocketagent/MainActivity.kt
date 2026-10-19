package com.example.pocketagent

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.json.JSONObject

// ---------- THEME ----------

private val LightColors = lightColorScheme(
    primary = Color(0xFFB4532A),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDBCF),
    onPrimaryContainer = Color(0xFF3A0B00),
    secondary = Color(0xFF77574D),
    tertiary = Color(0xFF6B5E2F),
    background = Color(0xFFFFFBFF),
    onBackground = Color(0xFF201A18),
    surface = Color(0xFFFFFBFF),
    onSurface = Color(0xFF201A18),
    surfaceVariant = Color(0xFFF5DED6),
    onSurfaceVariant = Color(0xFF52443F),
    outline = Color(0xFF85736E)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFFB59A),
    onPrimary = Color(0xFF5B1A00),
    primaryContainer = Color(0xFF853A19),
    onPrimaryContainer = Color(0xFFFFDBCF),
    secondary = Color(0xFFE7BDB0),
    tertiary = Color(0xFFD8C78D),
    background = Color(0xFF1A1110),
    onBackground = Color(0xFFF1DFDA),
    surface = Color(0xFF1A1110),
    onSurface = Color(0xFFF1DFDA),
    surfaceVariant = Color(0xFF53433F),
    onSurfaceVariant = Color(0xFFD8C2BC),
    outline = Color(0xFFA08C87)
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors, content = content)
}

// ---------- ACTIVITY & ROOT ----------

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AppTheme { PocketAgentApp() } }
    }
}

enum class Screen { Chat, Settings }

@Composable
fun PocketAgentApp() {
    val ctx = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val app = remember { AppState(ctx, Store(ctx), Workspace(ctx), scope) }

    var screen by remember { mutableStateOf(if (app.providers.isEmpty()) Screen.Settings else Screen.Chat) }
    var settingsTab by remember { mutableIntStateOf(0) }

    BackHandler(enabled = screen == Screen.Settings) { screen = Screen.Chat }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when (screen) {
            Screen.Chat -> ChatScreen(app) { tab ->
                settingsTab = tab
                screen = Screen.Settings
            }
            Screen.Settings -> SettingsScreen(app, settingsTab, { settingsTab = it }) { screen = Screen.Chat }
        }
    }
}

// ---------- CHAT SCREEN ----------

private fun modelLabel(app: AppState, p: Provider?): String =
    if (app.ccMode) "Claude Code" else (p?.model?.substringAfterLast('/') ?: "Choose a model")

private fun subtitle(app: AppState, p: Provider?, t: Totals): String {
    if (app.ccMode) return "Runs in Termux on this phone"
    if (p == null) return "Open the menu to add a provider"
    val sb = StringBuilder(p.name)
    if (app.thinking.isNotBlank()) sb.append(" · thinking ${app.thinking}")
    if (t.tokens > 0) {
        sb.append(" · ${fmtTokens(t.tokens)} tokens")
        if (t.cost > 0 || t.unpriced == 0) {
            sb.append(" · ${fmtCost(t.cost)}")
            if (t.unpriced > 0) sb.append("+")
        }
    }
    return sb.toString()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(app: AppState, openSettings: (Int) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    var input by remember { mutableStateOf("") }
    var showModels by remember { mutableStateOf(false) }
    var showTools by remember { mutableStateOf(false) }
    var showPhoneSetup by remember { mutableStateOf(false) }
    var browseFor by remember { mutableStateOf<Provider?>(null) }
    val listState = rememberLazyListState()
    val totals = app.sessionTotals()
    val selected = app.selected

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) app.addUploads(uris)
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) {
            app.error = "Permission denied. Open Android Settings → Apps → Pocket Agent → Permissions " +
                "and allow \"Run commands in Termux environment\"."
        }
    }

    BackHandler(enabled = drawerState.isOpen) { scope.launch { drawerState.close() } }

    LaunchedEffect(app.history.size, app.loading, app.currentChatId) {
        if (app.history.isNotEmpty()) listState.animateScrollToItem(app.history.size)
    }

    val onClaudeCode: (Boolean) -> Unit = { on ->
        app.ccMode = on
        if (on) {
            if (!TermuxBridge.isInstalled(ctx)) {
                app.error = "Termux isn't installed. Get it from F-Droid, then see Settings → Termux."
            } else if (!TermuxBridge.hasPermission(ctx)) {
                permLauncher.launch(TermuxBridge.PERMISSION)
            }
        }
    }
    val onPhone: (Boolean) -> Unit = { on ->
        app.phoneMode = on
        if (on && PhoneService.instance == null) {
            showTools = false
            showPhoneSetup = true
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ChatDrawer(app, close = { scope.launch { drawerState.close() } }, openSettings = openSettings)
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = "Chats")
                        }
                    },
                    title = {
                        Column(Modifier.clickable { showModels = true }) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    modelLabel(app, selected),
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                                Icon(Icons.Default.ArrowDropDown, contentDescription = "Switch model")
                            }
                            Text(
                                subtitle(app, selected, totals),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = { app.newChat() }) {
                            Icon(Icons.Default.Add, contentDescription = "New chat")
                        }
                    }
                )
            },
            bottomBar = {
                Composer(
                    app = app,
                    input = input,
                    onInput = { input = it },
                    onTools = { showTools = true },
                    onAttach = { picker.launch(arrayOf("*/*")) },
                    onModels = { showModels = true }
                )
            }
        ) { pad ->
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .padding(pad)
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(vertical = 12.dp)
            ) {
                if (app.history.isEmpty()) {
                    item {
                        EmptyState(app, onSuggest = { text, agent ->
                            input = text
                            if (agent) app.agentMode = true
                        }, openSettings = openSettings)
                    }
                }
                items(app.history) { msg -> MessageItem(msg, app) }
                item {
                    if (app.loading) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                if (app.ccMode) "Claude Code is working…" else "Thinking…",
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    } else {
                        Spacer(Modifier.height(1.dp))
                    }
                }
            }
        }
    }

    // ----- dialogs & sheets -----

    app.pendingConfirm?.let { pc ->
        AlertDialog(
            onDismissRequest = { pc.result.complete(false) },
            title = { Text("Allow this?") },
            text = { Text(pc.text) },
            confirmButton = { TextButton(onClick = { pc.result.complete(true) }) { Text("Allow") } },
            dismissButton = { TextButton(onClick = { pc.result.complete(false) }) { Text("Deny") } }
        )
    }

    if (showPhoneSetup) {
        AlertDialog(
            onDismissRequest = { showPhoneSetup = false },
            title = { Text("Turn on phone control") },
            text = {
                Text(
                    "To tap, type and scroll for you, Pocket Agent needs its accessibility service. " +
                        "Open Accessibility settings, find \"Pocket Agent phone control\" (under Installed or " +
                        "Downloaded apps) and switch it on. Android shows a warning because this is a powerful " +
                        "permission. The AI only acts on requests you send, asks you before each task, and a " +
                        "floating \"Stop AI\" button stays on screen while it works."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showPhoneSetup = false
                        ctx.startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }
                ) { Text("Open settings") }
            },
            dismissButton = { TextButton(onClick = { showPhoneSetup = false }) { Text("Later") } }
        )
    }

    if (showTools) {
        ToolsSheet(
            app = app,
            onDismiss = { showTools = false },
            onAttach = { picker.launch(arrayOf("*/*")) },
            onClaudeCode = onClaudeCode,
            onPhone = onPhone,
            openSettings = openSettings
        )
    }

    if (showModels) {
        ModelSheet(
            app = app,
            onDismiss = { showModels = false },
            onBrowse = { browseFor = it },
            openSettings = openSettings
        )
    }

    browseFor?.let { p ->
        ModelPickerDialog(
            app = app,
            provider = p,
            current = p.model,
            onPick = {
                app.select(p.id)
                app.setModel(p.id, it)
                app.ccMode = false
                browseFor = null
                showModels = false
            },
            onDismiss = { browseFor = null }
        )
    }
}

// ---------- DRAWER ----------

@Composable
fun ChatDrawer(app: AppState, close: () -> Unit, openSettings: (Int) -> Unit) {
    ModalDrawerSheet {
        Text(
            "Pocket Agent",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 24.dp, top = 24.dp, bottom = 12.dp)
        )
        NavigationDrawerItem(
            label = { Text("New chat") },
            icon = { Icon(Icons.Default.Add, contentDescription = null) },
            selected = false,
            onClick = { app.newChat(); close() },
            modifier = Modifier.padding(horizontal = 12.dp)
        )
        HorizontalDivider(Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        Text(
            "Chats",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 28.dp, vertical = 4.dp)
        )
        LazyColumn(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            items(app.chats.sortedByDescending { it.updated }, key = { it.id }) { c ->
                NavigationDrawerItem(
                    label = { Text(c.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    selected = c.id == app.currentChatId,
                    onClick = { app.openChat(c.id); close() },
                    badge = {
                        IconButton(onClick = { app.deleteChat(c.id) }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete chat", modifier = Modifier.size(18.dp))
                        }
                    }
                )
            }
        }
        HorizontalDivider(Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        NavigationDrawerItem(
            label = { Text("Settings") },
            icon = { Icon(Icons.Default.Settings, contentDescription = null) },
            selected = false,
            onClick = { close(); openSettings(0) },
            modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
        )
    }
}

// ---------- EMPTY STATE ----------

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EmptyState(app: AppState, onSuggest: (String, Boolean) -> Unit, openSettings: (Int) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 48.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            Icons.Default.AutoAwesome,
            contentDescription = null,
            modifier = Modifier.size(36.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Text(
            "Hello",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            "How can I help you today?",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        if (app.providers.isEmpty()) {
            Text(
                "Add an API key to get started: OpenRouter, OpenAI, Gemini, DeepSeek or any OpenAI-compatible provider.",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(onClick = { openSettings(0) }) { Text("Add provider") }
        } else {
            val suggestions = listOf(
                "Build a simple landing page for a coffee shop and give me the zip" to true,
                "Explain how neural networks learn, in simple words" to false,
                "Review this GitHub repo: https://github.com/" to true,
                "Write a short story about a robot chef" to false
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                suggestions.forEach { (text, agent) ->
                    SuggestionChip(onClick = { onSuggest(text, agent) }, label = { Text(text) })
                }
            }
        }
    }
}

// ---------- COMPOSER ----------

@Composable
private fun ModeChip(label: String, onRemove: () -> Unit) {
    AssistChip(
        onClick = onRemove,
        label = { Text(label, maxLines = 1) },
        trailingIcon = { Icon(Icons.Default.Close, contentDescription = "Remove", modifier = Modifier.size(16.dp)) }
    )
}

@Composable
fun Composer(
    app: AppState,
    input: String,
    onInput: (String) -> Unit,
    onTools: () -> Unit,
    onAttach: () -> Unit,
    onModels: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.background)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        app.error?.let { err ->
            Surface(color = colors.errorContainer, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        err,
                        color = colors.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .weight(1f)
                            .padding(vertical = 8.dp)
                    )
                    if (app.canRetry && !app.loading) {
                        TextButton(onClick = { app.retry() }) { Text("Retry") }
                    }
                    IconButton(onClick = { app.error = null }) { Icon(Icons.Default.Close, contentDescription = "Dismiss") }
                }
            }
        }

        if (app.agentMode || app.phoneMode || app.ccMode || app.attachments.isNotEmpty()) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (app.agentMode) ModeChip("Agent") { app.agentMode = false }
                if (app.phoneMode) ModeChip("Phone control") { app.phoneMode = false }
                if (app.ccMode) ModeChip("Claude Code") { app.ccMode = false }
                app.attachments.toList().forEach { a ->
                    ModeChip(a.trimEnd('/').substringAfterLast('/')) { app.attachments.remove(a) }
                }
            }
        }

        Surface(shape = RoundedCornerShape(28.dp), color = colors.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 6.dp)) {
                TextField(
                    value = input,
                    onValueChange = onInput,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = {
                        Text(
                            if (app.ccMode) "Message Claude Code…"
                            else if (app.phoneMode) "Tell me what to do on your phone…"
                            else if (app.agentMode) "Ask the agent to work on files…"
                            else "Message"
                        )
                    },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent
                    ),
                    maxLines = 6
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onTools) { Icon(Icons.Default.Add, contentDescription = "Tools") }
                    IconButton(onClick = onAttach) { Icon(Icons.Default.AttachFile, contentDescription = "Attach files") }
                    if (app.thinking.isNotBlank()) {
                        AssistChip(
                            onClick = onModels,
                            label = { Text("Thinking: ${app.thinking}") },
                            leadingIcon = { Icon(Icons.Default.Psychology, contentDescription = null, modifier = Modifier.size(16.dp)) }
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    if (app.loading) {
                        FilledIconButton(onClick = { app.stop() }) { Icon(Icons.Default.Stop, contentDescription = "Stop") }
                    } else {
                        FilledIconButton(
                            onClick = { app.send(input); onInput("") },
                            enabled = input.isNotBlank() || app.attachments.isNotEmpty()
                        ) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send") }
                    }
                }
            }
        }
    }
}

// ---------- SHEETS ----------

@Composable
private fun ToolRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit, trailing: @Composable () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trailing()
    }
}

@Composable
private fun ToggleRow(icon: ImageVector, title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    ToolRow(icon, title, subtitle, onClick = { onChange(!checked) }) {
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolsSheet(
    app: AppState,
    onDismiss: () -> Unit,
    onAttach: () -> Unit,
    onClaudeCode: (Boolean) -> Unit,
    onPhone: (Boolean) -> Unit,
    openSettings: (Int) -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text("Tools", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            ToolRow(
                Icons.Default.AttachFile, "Attach files",
                "Add text, code or zip files for the AI to read",
                onClick = { onDismiss(); onAttach() }
            ) {}
            ToggleRow(
                Icons.Default.Build, "Agent mode",
                "Read and write files, import GitHub repos, build projects as a zip",
                app.agentMode
            ) { app.agentMode = it }
            ToggleRow(
                Icons.Default.PhoneAndroid, "Phone control",
                "Let the AI open apps, tap, type and scroll for you",
                app.phoneMode, onPhone
            )
            ToggleRow(
                Icons.Default.Terminal, "Claude Code (Termux)",
                "Send messages to the real Claude Code running in Termux",
                app.ccMode, onClaudeCode
            )
            ToggleRow(
                Icons.Default.SwapHoriz, "Auto-switch provider",
                "Use the next provider if one hits a limit",
                app.autoSwitch
            ) { app.setAutoSwitchEnabled(it) }
            ToolRow(
                Icons.Default.AutoAwesome, "Skills (${app.skills.count { it.enabled }} on)",
                "Manage skills imported from GitHub",
                onClick = { onDismiss(); openSettings(1) }
            ) {}
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelSheet(
    app: AppState,
    onDismiss: () -> Unit,
    onBrowse: (Provider) -> Unit,
    openSettings: (Int) -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text("Thinking level", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "How hard the model reasons before it answers. Higher is slower and uses more tokens. " +
                    "Not every model supports it; if one doesn't, the app retries without it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("" to "Off", "low" to "Low", "medium" to "Medium", "high" to "High").forEach { (value, label) ->
                    FilterChip(
                        selected = app.thinking == value,
                        onClick = { app.setThinking(value) },
                        label = { Text(label) }
                    )
                }
            }

            Spacer(Modifier.height(20.dp))
            Text("Switch model", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (app.providers.isEmpty()) {
                Text("No providers yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            app.providers.forEach { p ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(p.name, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    TextButton(onClick = { onBrowse(p) }) { Text("Browse all") }
                }
                val models = (listOf(p.model) + p.recent).distinct().take(8)
                models.forEach { m ->
                    val isCurrent = !app.ccMode && p.id == app.selected?.id && m == p.model
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                app.select(p.id)
                                app.setModel(p.id, m)
                                app.ccMode = false
                                onDismiss()
                            }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(m, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (isCurrent) Icon(Icons.Default.Check, contentDescription = "Selected")
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
            }
            TextButton(onClick = { onDismiss(); openSettings(0) }) { Text("Add or edit providers") }
        }
    }
}

// ---------- MESSAGES ----------

@Composable
fun MessageItem(msg: JSONObject, app: AppState) {
    val role = msg.text("role")
    val content = msg.text("content")
    val colors = MaterialTheme.colorScheme
    val clipboard = LocalClipboardManager.current

    when (role) {
        "user" -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Surface(
                color = colors.primaryContainer,
                shape = RoundedCornerShape(20.dp, 20.dp, 4.dp, 20.dp),
                modifier = Modifier.widthIn(max = 320.dp)
            ) {
                SelectionContainer { Text(content, Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) }
            }
        }

        "tool" -> ToolResult(msg, app)

        else -> Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (content.isNotBlank()) {
                SelectionContainer { RichText(content) }
            }
            val calls = msg.optJSONArray("tool_calls")
            if (calls != null) {
                for (i in 0 until calls.length()) {
                    val fn = calls.getJSONObject(i).getJSONObject("function")
                    Text(
                        "🔧 ${fn.text("name")}(${fn.text("arguments").take(100)})",
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        color = colors.onSurfaceVariant
                    )
                }
            }
            val meta = msg.optJSONObject("meta")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    if (meta != null) {
                        val parts = mutableListOf(meta.text("provider"))
                        val tk = meta.optLong("pt") + meta.optLong("ct")
                        if (tk > 0) parts.add("${fmtTokens(tk)} tokens")
                        if (meta.has("cost")) parts.add(fmtCost(meta.optDouble("cost", 0.0)))
                        Text(
                            parts.joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.onSurfaceVariant
                        )
                        val notes = meta.optJSONArray("notes")
                        if (notes != null && notes.length() > 0) {
                            val list = (0 until notes.length()).map { notes.getString(it) }
                            Text(
                                "↪ Switched provider after: " + list.joinToString("; "),
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.primary
                            )
                        }
                    }
                }
                if (content.isNotBlank()) {
                    IconButton(onClick = { clipboard.setText(AnnotatedString(content)) }, modifier = Modifier.size(32.dp)) {
                        Icon(
                            Icons.Default.ContentCopy,
                            contentDescription = "Copy reply",
                            modifier = Modifier.size(16.dp),
                            tint = colors.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolResult(msg: JSONObject, app: AppState) {
    val name = msg.text("name")
    val content = msg.text("content")
    Column {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            shape = RoundedCornerShape(10.dp)
        ) {
            Text(
                "↳ $name: ${content.take(240)}",
                Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (name == "make_zip" && content.startsWith("Created ")) {
            ZipActions(app, content.removePrefix("Created ").substringBefore(" ("))
        }
    }
}

/** Renders **bold** and `code` inside one line. */
private fun inlineMd(s: String, codeBg: Color): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < s.length) {
        if (s.startsWith("**", i)) {
            val j = s.indexOf("**", i + 2)
            if (j > 0) {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(s.substring(i + 2, j)) }
                i = j + 2
            } else {
                append("**")
                i += 2
            }
        } else if (s[i] == '`') {
            val j = s.indexOf('`', i + 1)
            if (j > 0) {
                withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg)) { append(s.substring(i + 1, j)) }
                i = j + 1
            } else {
                append('`')
                i++
            }
        } else {
            append(s[i])
            i++
        }
    }
}

@Composable
private fun MarkdownBlock(text: String) {
    val codeBg = MaterialTheme.colorScheme.surfaceVariant
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        text.lines().forEach { raw ->
            val line = raw.trimEnd()
            when {
                line.isBlank() -> Spacer(Modifier.height(2.dp))
                line.startsWith("### ") ->
                    Text(inlineMd(line.removePrefix("### "), codeBg), fontWeight = FontWeight.Bold)
                line.startsWith("## ") ->
                    Text(
                        inlineMd(line.removePrefix("## "), codeBg),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                line.startsWith("# ") ->
                    Text(
                        inlineMd(line.removePrefix("# "), codeBg),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                line.startsWith("- ") || line.startsWith("* ") -> Text(inlineMd("•  " + line.drop(2), codeBg))
                else -> Text(inlineMd(line, codeBg))
            }
        }
    }
}

/** Text with simple markdown plus fenced ``` code blocks that have a Copy button. */
@Composable
fun RichText(text: String) {
    val colors = MaterialTheme.colorScheme
    val clipboard = LocalClipboardManager.current
    val parts = text.split("```")
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        parts.forEachIndexed { i, part ->
            if (i % 2 == 1) {
                val lang = part.substringBefore('\n', "").trim()
                val code = part.substringAfter('\n', part).trimEnd()
                Surface(color = colors.surfaceVariant, shape = RoundedCornerShape(12.dp)) {
                    Column {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(start = 12.dp, end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                lang.ifBlank { "code" },
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = { clipboard.setText(AnnotatedString(code)) }) { Text("Copy") }
                        }
                        Text(
                            code,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            modifier = Modifier
                                .horizontalScroll(rememberScrollState())
                                .padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
                        )
                    }
                }
            } else if (part.isNotBlank()) {
                MarkdownBlock(part.trim())
            }
        }
    }
}
