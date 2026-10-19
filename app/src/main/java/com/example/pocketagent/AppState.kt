package com.example.pocketagent

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID

/** A question shown to the user while the AI is working. Complete [result] with true (allow) or false (deny). */
class PendingConfirm(val text: String, val result: CompletableDeferred<Boolean>)

class AppState(
    private val ctx: Context,
    private val store: Store,
    private val workspace: Workspace,
    private val scope: CoroutineScope
) {
    val providers = mutableStateListOf<Provider>().apply { addAll(store.loadProviders()) }
    val skills = mutableStateListOf<Skill>().apply { addAll(store.loadSkills()) }
    val history = mutableStateListOf<JSONObject>()
    val chats = mutableStateListOf<Chat>().apply { addAll(store.loadChats()) }
    val usage = mutableStateMapOf<String, Usage>().apply { putAll(store.loadUsage()) }
    val balances = mutableStateMapOf<String, String>()
    val attachments = mutableStateListOf<String>()

    var currentChatId by mutableStateOf("")
    var selectedId by mutableStateOf(store.loadSelectedId())
    var autoSwitch by mutableStateOf(store.loadAutoSwitch())
    var thinking by mutableStateOf(store.loadThinking())
    var githubToken by mutableStateOf(store.loadGithubToken())

    var agentMode by mutableStateOf(false)
    var phoneMode by mutableStateOf(false)
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var pendingConfirm by mutableStateOf<PendingConfirm?>(null)

    var skillBusy by mutableStateOf(false)
    var skillStatus by mutableStateOf<String?>(null)
    var repoBusy by mutableStateOf(false)
    var repoStatus by mutableStateOf<String?>(null)

    // model picker
    val modelList = mutableStateListOf<String>()
    var modelsLoading by mutableStateOf(false)
    var modelsError by mutableStateOf<String?>(null)

    // bumps whenever workspace files may have changed
    var filesVersion by mutableIntStateOf(0)

    private var job: Job? = null

    init {
        if (chats.isEmpty()) {
            chats.add(Chat(UUID.randomUUID().toString(), "New chat", System.currentTimeMillis()))
            store.saveChats(chats.toList())
        }
        val saved = store.loadCurrentChatId()
        currentChatId = (chats.firstOrNull { it.id == saved } ?: chats.sortedByDescending { it.updated }.first()).id
        history.addAll(store.loadHistory(currentChatId))
    }

    val selected: Provider?
        get() = providers.firstOrNull { it.id == selectedId } ?: providers.firstOrNull()

    val canRetry: Boolean
        get() = history.isNotEmpty() && history.last().text("role") != "assistant"

    // ---------- providers ----------

    fun select(id: String) {
        selectedId = id
        store.saveSelectedId(id)
    }

    fun setAutoSwitchEnabled(v: Boolean) {
        autoSwitch = v
        store.saveAutoSwitch(v)
    }

    fun setThinking(v: String) {
        thinking = v
        store.saveThinking(v)
    }

    fun upsertProvider(p: Provider) {
        val i = providers.indexOfFirst { it.id == p.id }
        if (i >= 0) providers[i] = p else providers.add(p)
        Cooldowns.clear(p.id)
        if (providers.size == 1 || selectedId == null) select(p.id)
        store.saveProviders(providers.toList())
    }

    fun deleteProvider(id: String) {
        providers.removeAll { it.id == id }
        if (selectedId == id) select(providers.firstOrNull()?.id ?: "")
        store.saveProviders(providers.toList())
    }

    fun moveProvider(id: String, delta: Int) {
        val i = providers.indexOfFirst { it.id == id }
        if (i < 0) return
        val j = (i + delta).coerceIn(0, providers.size - 1)
        if (i == j) return
        providers.add(j, providers.removeAt(i))
        store.saveProviders(providers.toList())
    }

    // ---------- chats ----------

    private fun busy(): Boolean {
        if (loading) {
            error = "Wait for the reply to finish, or press Stop."
            return true
        }
        return false
    }

    private fun persistChat() {
        val i = chats.indexOfFirst { it.id == currentChatId }
        if (i >= 0) {
            var title = chats[i].title
            if (title == "New chat") {
                val first = history.firstOrNull { it.text("role") == "user" }?.text("content").orEmpty()
                val body = if (first.startsWith("Attached files")) first.substringAfter("\n\n", first) else first
                title = body.trim().lines().firstOrNull().orEmpty().take(40).ifBlank { "New chat" }
            }
            chats[i] = chats[i].copy(title = title, updated = System.currentTimeMillis())
            store.saveChats(chats.toList())
        }
        store.saveHistory(currentChatId, history.toList())
    }

    private fun switchTo(id: String) {
        persistChat()
        currentChatId = id
        store.saveCurrentChatId(id)
        history.clear()
        history.addAll(store.loadHistory(id))
        error = null
        attachments.clear()
        ccStarted = false
        store.saveCcStarted(false)
    }

    fun newChat() {
        if (busy()) return
        if (history.isEmpty()) return
        val c = Chat(UUID.randomUUID().toString(), "New chat", System.currentTimeMillis())
        chats.add(0, c)
        store.saveChats(chats.toList())
        switchTo(c.id)
    }

    fun openChat(id: String) {
        if (id == currentChatId || busy()) return
        switchTo(id)
    }

    fun deleteChat(id: String) {
        if (busy()) return
        store.deleteChatFile(id)
        chats.removeAll { it.id == id }
        if (chats.isEmpty()) {
            chats.add(Chat(UUID.randomUUID().toString(), "New chat", System.currentTimeMillis()))
        }
        store.saveChats(chats.toList())
        if (id == currentChatId) {
            val next = chats.sortedByDescending { it.updated }.first().id
            currentChatId = next
            store.saveCurrentChatId(next)
            history.clear()
            history.addAll(store.loadHistory(next))
            error = null
            attachments.clear()
        }
    }

    // ---------- sending ----------

    /** Typing /skill-name in a message loads that skill's full text into the message. */
    private fun withSkillRefs(text: String): String {
        val active = skills.filter { it.enabled }
        if (active.isEmpty()) return text
        val hits = Regex("(?<![\\w/])/([A-Za-z0-9._-]+)").findAll(text)
            .mapNotNull { m -> active.firstOrNull { it.name.equals(m.groupValues[1], ignoreCase = true) } }
            .distinct().toList()
        if (hits.isEmpty()) return text
        return text + "\n\n" + hits.joinToString("\n\n") { "[Skill: ${it.name}]\n${it.content}" }
    }

    fun send(text: String) {
        var t = text.trim()
        if (loading) return
        if (t.isEmpty() && (ccMode || attachments.isEmpty())) return
        if (!ccMode) {
            // a GitHub link needs the agent's tools to be read
            if (t.contains("github.com/", ignoreCase = true)) agentMode = true
            t = withSkillRefs(t)
            if (attachments.isNotEmpty()) {
                t = "Attached files (in the workspace): " + attachments.joinToString(", ") + "\n\n" + t
                attachments.clear()
                agentMode = true
            }
        }
        history.add(JSONObject().put("role", "user").put("content", t.trim()))
        if (ccMode) runCc(t) else runLoop()
    }

    fun retry() {
        if (loading) return
        if (ccMode) {
            val last = history.lastOrNull { it.text("role") == "user" }
            if (last != null) runCc(last.text("content"))
        } else {
            runLoop()
        }
    }

    fun stop() {
        Api.cancelCurrent()
        job?.cancel()
        val req = ccReq
        if (req != null) {
            TermuxBridge.cancel(req)
            ccReq = null
            loading = false
            error = "Stopped waiting. The command may still be running in Termux."
        }
    }

    private suspend fun confirm(text: String): Boolean {
        val d = CompletableDeferred<Boolean>()
        pendingConfirm = PendingConfirm(text, d)
        return try {
            d.await()
        } finally {
            pendingConfirm = null
        }
    }

    private fun runLoop() {
        val primary = selected
        if (primary == null) {
            error = "Add a provider in Settings first."
            return
        }
        error = null
        loading = true

        val agent = agentMode
        val phone = phoneMode
        val activeSkills = skills.filter { it.enabled }
        val inline = Api.skillsInline(activeSkills)
        val system = Api.systemPrompt(agent, phone, activeSkills)
        val tools = Tools.schema(agent, activeSkills.isNotEmpty() && !inline, phone)
        val providerSnapshot = providers.toList()
        val auto = autoSwitch
        val think = thinking
        val maxSteps = if (phone) 30 else 12

        job = scope.launch {
            var phoneAllowed: Boolean? = null
            try {
                for (step in 0 until maxSteps) {
                    val snapshot = history.toList()
                    val msg = withContext(Dispatchers.IO) {
                        Api.chat(primary, providerSnapshot, auto, snapshot, tools, system, think)
                    }
                    history.add(msg)
                    recordUsage(msg.optJSONObject("meta"))

                    val calls = msg.optJSONArray("tool_calls")
                    if (calls == null || calls.length() == 0) break

                    for (i in 0 until calls.length()) {
                        val call = calls.getJSONObject(i)
                        val fn = call.getJSONObject("function")
                        val name = fn.getString("name")
                        val args = try { JSONObject(fn.getString("arguments")) } catch (_: Exception) { JSONObject() }

                        val result: String
                        if (name in PhoneTools.NAMES) {
                            if (!phone) {
                                result = "Phone control is off."
                            } else {
                                if (phoneAllowed == null) {
                                    val ok = confirm(
                                        "Let the AI control your phone for this request? It can open apps, tap, " +
                                            "type and scroll. A floating \"Stop AI\" button appears while it works."
                                    )
                                    phoneAllowed = ok
                                    if (ok) PhoneService.instance?.showStop { stop() }
                                }
                                result = if (phoneAllowed == true) {
                                    withContext(Dispatchers.IO) { PhoneTools.run(ctx, name, args) }
                                } else {
                                    "The user did not allow phone control for this request."
                                }
                            }
                        } else {
                            result = withContext(Dispatchers.IO) { runTool(name, args, agent, activeSkills) }
                        }

                        history.add(
                            JSONObject().put("role", "tool")
                                .put("tool_call_id", call.getString("id"))
                                .put("name", name)
                                .put("content", result)
                        )
                    }
                }
            } catch (e: CancellationException) {
                error = "Stopped."
            } catch (e: Exception) {
                error = e.message ?: "Request failed"
            } finally {
                PhoneService.instance?.hideStop()
                repairHistory()
                loading = false
                filesVersion += 1
                persistChat()
            }
        }
    }

    private fun runTool(name: String, args: JSONObject, agent: Boolean, active: List<Skill>): String {
        if (name == "use_skill") {
            val want = args.optString("name").trim()
            val s = active.firstOrNull { it.name.equals(want, ignoreCase = true) }
                ?: return "Unknown skill '$want'. Available: " + active.joinToString { it.name }
            return s.content
        }
        if (!agent) return "Tool '$name' is not available. Ask the user to turn on Agent mode."
        if (name == "github_import") {
            return try {
                GitHub.importRepo(workspace, args.optString("url"), githubToken)
            } catch (e: Exception) {
                "Error: ${e.message}"
            }
        }
        return workspace.run(name, args)
    }

    /** If a run was interrupted after tool calls were requested, answer them so the history stays valid. */
    private fun repairHistory() {
        var idx = -1
        for (i in history.indices.reversed()) {
            val m = history[i]
            if (m.text("role") == "assistant" && (m.optJSONArray("tool_calls")?.length() ?: 0) > 0) {
                idx = i
                break
            }
        }
        if (idx < 0) return
        val calls = history[idx].getJSONArray("tool_calls")
        val answered = mutableSetOf<String>()
        for (i in idx + 1 until history.size) {
            if (history[i].text("role") == "tool") answered.add(history[i].text("tool_call_id"))
        }
        for (i in 0 until calls.length()) {
            val id = calls.getJSONObject(i).getString("id")
            if (id !in answered) {
                history.add(
                    JSONObject().put("role", "tool").put("tool_call_id", id)
                        .put("name", calls.getJSONObject(i).getJSONObject("function").text("name"))
                        .put("content", "(cancelled)")
                )
            }
        }
    }

    // ---------- Claude Code via Termux ----------

    var ccMode by mutableStateOf(false)
    var cc by mutableStateOf(store.loadCc())
    var ccTest by mutableStateOf<String?>(null)
    private var ccStarted = store.loadCcStarted()
    private var ccReq: Int? = null

    fun updateCc(s: CcSettings) {
        cc = s
        store.saveCc(s)
    }

    /** Wraps [body] with the user's login lines, the work folder and a check that Claude Code is installed. */
    private fun ccScript(body: String): String {
        val raw = cc.workDir.trim().trim('/')
        val dir = if (raw.isNotEmpty() && Regex("[A-Za-z0-9._/-]+").matches(raw) && !raw.contains("..")) raw else "claude-work"
        val sb = StringBuilder()
        sb.append("export TERM=dumb\n")
        sb.append("command -v termux-wake-lock >/dev/null 2>&1 && termux-wake-lock\n")
        sb.append("trap 'command -v termux-wake-unlock >/dev/null 2>&1 && termux-wake-unlock' EXIT\n")
        if (cc.setup.isNotBlank()) sb.append(cc.setup.trim()).append('\n')
        sb.append("mkdir -p \"\$HOME/$dir\" && cd \"\$HOME/$dir\" || exit 90\n")
        sb.append("command -v claude >/dev/null 2>&1 || { echo 'claude: command not found' >&2; exit 127; }\n")
        sb.append(body).append('\n')
        return sb.toString()
    }

    private fun runCc(prompt: String) {
        error = null
        loading = true
        val b64 = Base64.encodeToString(prompt.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val cont = if (ccStarted) "--continue " else ""
        val script = ccScript("claude -p \"\$(printf %s '$b64' | base64 -d)\" $cont${cc.flags.trim()} < /dev/null")
        try {
            ccReq = TermuxBridge.run(ctx, script) { res -> onCcResult(res) }
        } catch (e: Exception) {
            loading = false
            error = e.message ?: "Couldn't start Termux"
        }
    }

    private fun onCcResult(res: TermuxBridge.Result) {
        ccReq = null
        val out = res.stdout.trim()
        val errText = res.stderr.trim()
        val termuxError = !res.errmsg.isNullOrBlank()
        val failed = termuxError || (res.exitCode ?: 0) != 0
        val base = when {
            termuxError -> "Termux error: ${res.errmsg}"
            res.exitCode == 127 -> "Claude Code isn't installed in Termux yet. See Settings → Termux."
            failed -> listOf(errText, out).filter { it.isNotBlank() }.joinToString("\n")
                .ifBlank { "Claude Code exited with code ${res.exitCode}." }
            else -> out.ifBlank { "(Claude Code returned no output)" }
        }
        val text = if (res.truncated) base + "\n\n(Output was cut off by Termux.)" else base
        if (!failed && !ccStarted) {
            ccStarted = true
            store.saveCcStarted(true)
        }
        history.add(
            JSONObject().put("role", "assistant").put("content", text).put(
                "meta", JSONObject().put("provider", if (failed) "Claude Code (Termux) · failed" else "Claude Code (Termux)")
            )
        )
        loading = false
        persistChat()
    }

    fun testClaudeCode() {
        ccTest = "Running… (the first run can take a few seconds)"
        try {
            TermuxBridge.run(ctx, ccScript("claude --version < /dev/null")) { res ->
                val msg = listOf(res.stdout.trim(), res.stderr.trim(), res.errmsg.orEmpty().trim())
                    .filter { it.isNotBlank() }.joinToString("\n")
                ccTest = if ((res.exitCode ?: 0) == 0 && res.errmsg.isNullOrBlank()) {
                    "OK: " + msg.ifBlank { "Claude Code ran." }
                } else {
                    "Failed (exit ${res.exitCode}): " + msg.ifBlank { "no details" }
                }
            }
        } catch (e: Exception) {
            ccTest = e.message ?: "Couldn't start Termux"
        }
    }

    // ---------- models ----------

    fun loadModels(p: Provider) {
        modelsLoading = true
        modelsError = null
        modelList.clear()
        scope.launch {
            try {
                val ids = withContext(Dispatchers.IO) { Api.listModels(p) }
                modelList.addAll(ids)
                if (ids.isEmpty()) modelsError = "The provider returned no models. Type a model ID instead."
            } catch (e: Exception) {
                modelsError = "Couldn't load the list: ${e.message}. You can still type a model ID."
            } finally {
                modelsLoading = false
            }
        }
    }

    /** Sets the provider's model and remembers it in the quick-switch list. */
    fun setModel(providerId: String, model: String) {
        val i = providers.indexOfFirst { it.id == providerId }
        val m = model.trim()
        if (i >= 0 && m.isNotBlank()) {
            val p = providers[i]
            val recent = (listOf(m, p.model) + p.recent).filter { it.isNotBlank() }.distinct().take(8)
            providers[i] = p.copy(model = m, recent = recent)
            store.saveProviders(providers.toList())
        }
    }

    // ---------- workspace files ----------

    fun workspaceFiles(): List<String> = workspace.listAll()
    fun readWorkspaceFile(path: String): String = workspace.read(path)
    fun workspaceFile(path: String): File = workspace.fileFor(path)

    fun deleteWorkspaceFile(path: String) {
        workspace.remove(path)
        filesVersion += 1
    }

    fun clearWorkspace() {
        workspace.clearAll()
        filesVersion += 1
    }

    /** Copies picked files into the workspace and lists them as attachments for the next message. */
    fun addUploads(uris: List<Uri>) {
        if (ccMode) {
            error = "Attachments work with Agent mode, not Claude Code mode."
            return
        }
        scope.launch {
            for (u in uris) {
                try {
                    val saved = withContext(Dispatchers.IO) { workspace.saveUpload(ctx.contentResolver, u) }
                    attachments.add(saved)
                } catch (e: Exception) {
                    error = "Upload failed: ${e.message}"
                }
            }
            if (attachments.isNotEmpty()) agentMode = true
            filesVersion += 1
        }
    }

    fun saveWorkspaceFileTo(uri: Uri, path: String) {
        scope.launch {
            val msg = try {
                withContext(Dispatchers.IO) {
                    val src = workspace.fileFor(path)
                    val out = ctx.contentResolver.openOutputStream(uri) ?: throw IOException("Can't write there")
                    out.use { o -> src.inputStream().use { it.copyTo(o) } }
                }
                "Saved"
            } catch (e: Exception) {
                "Couldn't save: ${e.message}"
            }
            Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
        }
    }

    fun saveGithubToken(token: String) {
        githubToken = token.trim()
        store.saveGithubToken(githubToken)
    }

    fun importRepo(url: String) {
        if (repoBusy || url.isBlank()) return
        repoBusy = true
        repoStatus = "Downloading…"
        scope.launch {
            try {
                repoStatus = withContext(Dispatchers.IO) { GitHub.importRepo(workspace, url, githubToken) }
                filesVersion += 1
            } catch (e: Exception) {
                repoStatus = e.message ?: "Import failed"
            } finally {
                repoBusy = false
            }
        }
    }

    // ---------- usage ----------

    private fun recordUsage(meta: JSONObject?) {
        if (meta == null) return
        val id = meta.text("providerId")
        val old = usage[id] ?: Usage()
        val hasCost = meta.has("cost")
        usage[id] = old.copy(
            requests = old.requests + 1,
            promptTokens = old.promptTokens + meta.optLong("pt"),
            completionTokens = old.completionTokens + meta.optLong("ct"),
            cost = old.cost + (if (hasCost) meta.optDouble("cost", 0.0) else 0.0),
            unpriced = old.unpriced + (if (hasCost) 0 else 1)
        )
        store.saveUsage(usage.toMap())
    }

    fun resetUsage() {
        usage.clear()
        store.saveUsage(emptyMap())
    }

    fun sessionTotals(): Totals {
        var tokens = 0L
        var cost = 0.0
        var unpriced = 0
        for (m in history) {
            val meta = m.optJSONObject("meta") ?: continue
            if (!meta.has("pt")) continue
            tokens += meta.optLong("pt") + meta.optLong("ct")
            if (meta.has("cost")) cost += meta.optDouble("cost", 0.0) else unpriced++
        }
        return Totals(tokens, cost, unpriced)
    }

    fun checkBalance(p: Provider) {
        balances[p.id] = "Checking…"
        scope.launch {
            balances[p.id] = try {
                withContext(Dispatchers.IO) { Api.openRouterBalance(p) }
            } catch (e: Exception) {
                "Could not fetch: ${e.message}"
            }
        }
    }

    // ---------- skills ----------

    fun importSkills(url: String) {
        if (skillBusy || url.isBlank()) return
        skillBusy = true
        skillStatus = "Fetching from GitHub…"
        scope.launch {
            try {
                val found = withContext(Dispatchers.IO) { SkillImporter.import(url, githubToken) }
                var added = 0
                for (s in found) {
                    val i = skills.indexOfFirst { it.source == s.source && it.name == s.name }
                    if (i >= 0) {
                        skills[i] = s.copy(id = skills[i].id, enabled = skills[i].enabled)
                    } else {
                        skills.add(s)
                        added++
                    }
                }
                store.saveSkills(skills.toList())
                skillStatus = "Found ${found.size} skill(s): $added new, ${found.size - added} updated."
            } catch (e: Exception) {
                skillStatus = e.message ?: "Import failed"
            } finally {
                skillBusy = false
            }
        }
    }

    fun toggleSkill(id: String, enabled: Boolean) {
        val i = skills.indexOfFirst { it.id == id }
        if (i >= 0) {
            skills[i] = skills[i].copy(enabled = enabled)
            store.saveSkills(skills.toList())
        }
    }

    fun deleteSkill(id: String) {
        skills.removeAll { it.id == id }
        store.saveSkills(skills.toList())
    }
}
