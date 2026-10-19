package com.example.pocketagent

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.UUID

// ---------- MODELS ----------

data class Provider(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val fallback: Boolean = true,
    val priceIn: Double = 0.0,   // optional USD per 1M input tokens (0 = unknown)
    val priceOut: Double = 0.0,  // optional USD per 1M output tokens (0 = unknown)
    val maxTokens: Int = 2048,   // cap on reply length; also caps how much credit is reserved
    val recent: List<String> = emptyList()  // recently used models, for the quick switcher
) {
    val isOpenRouter: Boolean get() = baseUrl.contains("openrouter.ai")
}

data class Skill(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val description: String,
    val content: String,
    val source: String,
    val enabled: Boolean = true
)

data class Usage(
    val requests: Int = 0,
    val promptTokens: Long = 0L,
    val completionTokens: Long = 0L,
    val cost: Double = 0.0,
    val unpriced: Int = 0
)

data class CcSettings(
    val workDir: String = "claude-work",
    val setup: String = "",
    val flags: String = "--permission-mode acceptEdits"
)

data class Totals(val tokens: Long, val cost: Double, val unpriced: Int)

data class Chat(val id: String, val title: String, val updated: Long)

data class Preset(val name: String, val url: String, val model: String, val hint: String = "")

val PRESETS = listOf(
    Preset(
        "OpenRouter", "https://openrouter.ai/api/v1", "anthropic/claude-sonnet-5.5",
        "Get a key at openrouter.ai/keys. One key reaches many different models."
    ),
    Preset(
        "OpenAI", "https://api.openai.com/v1", "gpt-6-astra",
        "Get a key at platform.openai.com/api-keys. This is separate from a ChatGPT subscription."
    ),
    Preset(
        "Gemini", "https://generativelanguage.googleapis.com/v1beta/openai", "gemini-3.8-flash",
        "Get a key at aistudio.google.com/apikey (it has a free tier). Your Gemini Pro app subscription can't be used here; the API needs its own key."
    ),
    Preset("DeepSeek", "https://api.deepseek.com", "deepseek-v4-flash", "Get a key at platform.deepseek.com."),
    Preset("Groq", "https://api.groq.com/openai/v1", "openai/gpt-oss-120b", "Get a free key at console.groq.com/keys."),
    Preset("Custom", "", "", "Any OpenAI-compatible service: paste its base URL (usually ends in /v1).")
)

// ---------- HELPERS ----------

fun JSONObject.text(key: String): String = if (isNull(key)) "" else optString(key, "")

fun fmtTokens(n: Long): String = when {
    n >= 1_000_000 -> String.format(Locale.US, "%.2fM", n / 1_000_000.0)
    n >= 1_000 -> String.format(Locale.US, "%.1fk", n / 1_000.0)
    else -> n.toString()
}

fun fmtCost(d: Double): String =
    if (d < 0.01) String.format(Locale.US, "\$%.4f", d) else String.format(Locale.US, "\$%.2f", d)

fun costLine(u: Usage): String {
    if (u.requests == 0) return "No usage yet"
    return buildString {
        if (u.cost > 0 || u.unpriced == 0) append("Cost: ${fmtCost(u.cost)}")
        else append("Cost: unknown (set token prices in the provider)")
        if (u.unpriced > 0 && u.cost > 0) append(" (+${u.unpriced} request(s) without price data)")
    }
}

// ---------- STORAGE ----------
// API keys live in EncryptedSharedPreferences. Chat history and skills are app-private files.

class Store(private val ctx: Context) {
    private val prefs = EncryptedSharedPreferences.create(
        ctx,
        "secure_store",
        MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )
    private val chatsDir = File(ctx.filesDir, "chats").apply { mkdirs() }
    private val chatsIndex = File(ctx.filesDir, "chats.json")
    private val skillsFile = File(ctx.filesDir, "skills.json")

    fun loadProviders(): List<Provider> = try {
        val arr = JSONArray(prefs.getString("providers", "[]") ?: "[]")
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Provider(
                id = o.getString("id"),
                name = o.getString("name"),
                baseUrl = o.getString("baseUrl"),
                apiKey = o.getString("apiKey"),
                model = o.getString("model"),
                fallback = o.optBoolean("fallback", true),
                priceIn = o.optDouble("priceIn", 0.0),
                priceOut = o.optDouble("priceOut", 0.0),
                maxTokens = o.optInt("maxTokens", 2048),
                recent = o.optJSONArray("recent")?.let { a -> (0 until a.length()).map { i -> a.getString(i) } } ?: emptyList()
            )
        }
    } catch (_: Exception) {
        emptyList()
    }

    fun saveProviders(list: List<Provider>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject().put("id", it.id).put("name", it.name).put("baseUrl", it.baseUrl)
                    .put("apiKey", it.apiKey).put("model", it.model).put("fallback", it.fallback)
                    .put("priceIn", it.priceIn).put("priceOut", it.priceOut).put("maxTokens", it.maxTokens)
                    .put("recent", JSONArray(it.recent))
            )
        }
        prefs.edit().putString("providers", arr.toString()).apply()
    }

    fun loadSelectedId(): String? = prefs.getString("selected", null)
    fun saveSelectedId(id: String?) = prefs.edit().putString("selected", id).apply()

    fun loadAutoSwitch(): Boolean = prefs.getBoolean("autoSwitch", true)
    fun saveAutoSwitch(v: Boolean) = prefs.edit().putBoolean("autoSwitch", v).apply()

    fun loadUsage(): Map<String, Usage> = try {
        val o = JSONObject(prefs.getString("usage", "{}") ?: "{}")
        o.keys().asSequence().associateWith { k ->
            val u = o.getJSONObject(k)
            Usage(u.optInt("requests"), u.optLong("pt"), u.optLong("ct"), u.optDouble("cost", 0.0), u.optInt("unpriced"))
        }
    } catch (_: Exception) {
        emptyMap()
    }

    fun saveUsage(map: Map<String, Usage>) {
        val o = JSONObject()
        map.forEach { (k, u) ->
            o.put(
                k,
                JSONObject().put("requests", u.requests).put("pt", u.promptTokens)
                    .put("ct", u.completionTokens).put("cost", u.cost).put("unpriced", u.unpriced)
            )
        }
        prefs.edit().putString("usage", o.toString()).apply()
    }

    fun loadCc(): CcSettings = CcSettings(
        workDir = prefs.getString("ccWorkDir", null) ?: "claude-work",
        setup = prefs.getString("ccSetup", null) ?: "",
        flags = prefs.getString("ccFlags", null) ?: "--permission-mode acceptEdits"
    )

    fun saveCc(s: CcSettings) {
        prefs.edit().putString("ccWorkDir", s.workDir).putString("ccSetup", s.setup)
            .putString("ccFlags", s.flags).apply()
    }

    fun loadCcStarted(): Boolean = prefs.getBoolean("ccStarted", false)
    fun saveCcStarted(v: Boolean) = prefs.edit().putBoolean("ccStarted", v).apply()

    // ----- chats -----

    fun loadChats(): List<Chat> {
        val list = try {
            val arr = JSONArray(if (chatsIndex.exists()) chatsIndex.readText() else "[]")
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Chat(o.getString("id"), o.optString("title", "New chat"), o.optLong("updated"))
            }
        } catch (_: Exception) {
            emptyList()
        }
        if (list.isNotEmpty()) return list
        // carry over the single chat from older versions
        val legacy = File(ctx.filesDir, "chat.json")
        if (legacy.exists()) {
            val chat = Chat(UUID.randomUUID().toString(), "Earlier chat", System.currentTimeMillis())
            try {
                File(chatsDir, chat.id + ".json").writeText(legacy.readText())
                legacy.delete()
            } catch (_: Exception) {
            }
            saveChats(listOf(chat))
            return listOf(chat)
        }
        return emptyList()
    }

    fun saveChats(list: List<Chat>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("id", it.id).put("title", it.title).put("updated", it.updated)) }
        chatsIndex.writeText(arr.toString())
    }

    fun loadHistory(chatId: String): List<JSONObject> = try {
        val f = File(chatsDir, "$chatId.json")
        val arr = JSONArray(if (f.exists()) f.readText() else "[]")
        (0 until arr.length()).map { arr.getJSONObject(it) }
    } catch (_: Exception) {
        emptyList()
    }

    fun saveHistory(chatId: String, list: List<JSONObject>) {
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        File(chatsDir, "$chatId.json").writeText(arr.toString())
    }

    fun deleteChatFile(chatId: String) {
        File(chatsDir, "$chatId.json").delete()
    }

    fun loadCurrentChatId(): String? = prefs.getString("currentChat", null)
    fun saveCurrentChatId(id: String) = prefs.edit().putString("currentChat", id).apply()

    // ----- small settings -----

    fun loadThinking(): String = prefs.getString("thinking", null) ?: ""
    fun saveThinking(v: String) = prefs.edit().putString("thinking", v).apply()

    fun loadGithubToken(): String = prefs.getString("ghToken", null) ?: ""
    fun saveGithubToken(v: String) = prefs.edit().putString("ghToken", v).apply()

    fun loadSkills(): List<Skill> = try {
        val arr = JSONArray(if (skillsFile.exists()) skillsFile.readText() else "[]")
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Skill(
                id = o.getString("id"),
                name = o.getString("name"),
                description = o.optString("description", ""),
                content = o.optString("content", ""),
                source = o.optString("source", ""),
                enabled = o.optBoolean("enabled", true)
            )
        }
    } catch (_: Exception) {
        emptyList()
    }

    fun saveSkills(list: List<Skill>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject().put("id", it.id).put("name", it.name).put("description", it.description)
                    .put("content", it.content).put("source", it.source).put("enabled", it.enabled)
            )
        }
        skillsFile.writeText(arr.toString())
    }
}
