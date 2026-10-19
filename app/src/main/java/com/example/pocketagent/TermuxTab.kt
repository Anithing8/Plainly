package com.example.pocketagent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private const val TERMUX_COMMANDS =
    "pkg update -y && pkg install -y nodejs\n" +
        "npm install -g @anthropic-ai/claude-code\n" +
        "mkdir -p ~/.termux && echo \"allow-external-apps=true\" >> ~/.termux/termux.properties\n"

@Composable
fun TermuxTab(app: AppState) {
    var workDir by remember { mutableStateOf(app.cc.workDir) }
    var setup by remember { mutableStateOf(app.cc.setup) }
    var flags by remember { mutableStateOf(app.cc.flags) }
    var arKey by remember { mutableStateOf("") }
    val clipboard = LocalClipboardManager.current

    fun save() = app.updateCc(CcSettings(workDir.trim(), setup, flags.trim()))

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Run the real Claude Code through Termux", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Claude Code runs inside the Termux app. This app sends your message to it and shows " +
                            "the answer in the chat.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "1. Install Termux from F-Droid (the Play Store version is outdated).\n" +
                            "2. Tap \"Copy Termux commands\" below, open Termux, paste, press Enter.\n" +
                            "3. Close Termux completely, then open it once more.\n" +
                            "4. Allow Pocket Agent the permission \"Run commands in Termux environment\". " +
                            "The chat asks the first time; otherwise Android Settings → Apps → Pocket Agent → Permissions.\n" +
                            "5. Fill in your login lines below, tap Save and test.\n" +
                            "6. In the chat, turn on the Claude Code chip.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedButton(
                        onClick = { clipboard.setText(AnnotatedString(TERMUX_COMMANDS)) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Copy Termux commands") }
                }
            }
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Using AgentRouter?", fontWeight = FontWeight.SemiBold)
                    Text(
                        "AgentRouter only accepts the real Claude Code, so it works through this Termux mode. " +
                            "Paste your AgentRouter key and tap the button to fill in the login lines.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedTextField(
                        value = arKey,
                        onValueChange = { arKey = it },
                        label = { Text("AgentRouter key") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedButton(
                        onClick = {
                            setup = "export ANTHROPIC_BASE_URL=https://agentrouter.org/\n" +
                                "export ANTHROPIC_AUTH_TOKEN=${arKey.trim()}"
                        },
                        enabled = arKey.isNotBlank(),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Fill in login lines for AgentRouter") }
                }
            }
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = workDir,
                        onValueChange = { workDir = it },
                        label = { Text("Work folder (inside Termux home)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = setup,
                        onValueChange = { setup = it },
                        label = { Text("Login lines (run before Claude Code)") },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        "Example for AgentRouter:\n" +
                            "export ANTHROPIC_BASE_URL=https://agentrouter.org/\n" +
                            "export ANTHROPIC_AUTH_TOKEN=sk-your-key\n" +
                            "These are stored encrypted on this phone.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = flags,
                        onValueChange = { flags = it },
                        label = { Text("Extra Claude Code flags") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        "The default lets Claude Code edit files in the work folder but not run shell commands. " +
                            "To allow commands, add for example: --allowedTools \"Bash\"",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(
                        onClick = { save(); app.testClaudeCode() },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Save and test") }
                    app.ccTest?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }

        item {
            Text(
                "Answers appear when Claude Code finishes, not word by word. Files it creates live in Termux's " +
                    "home folder, not in this app's Files tab. Clearing the chat starts a fresh Claude Code session.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
