package com.example.pocketagent

import android.net.Uri
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Imports "skills" (folders containing a SKILL.md) from a public GitHub repo link.
 * Supported links:
 *   https://github.com/owner/repo
 *   https://github.com/owner/repo/tree/branch/some/folder
 *   https://github.com/owner/repo/blob/branch/path/to/SKILL.md
 */
object SkillImporter {
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val repoRegex =
        Regex("""github\.com/([^/\s]+)/([^/\s#?]+)(?:/(tree|blob)/([^/\s#?]+)(?:/([^\s#?]*))?)?""")

    private var token = ""

    private const val MAX_SKILLS = 40
    private const val MAX_CONTENT = 30_000

    /** Blocking; run on a background thread. Throws IOException with a readable message. */
    fun import(rawUrl: String, token: String = ""): List<Skill> {
        this.token = token
        val m = repoRegex.find(rawUrl.trim()) ?: throw IOException("Paste a github.com repository link.")
        val owner = m.groupValues[1]
        val repo = m.groupValues[2].removeSuffix(".git")
        val kind = m.groupValues[3]
        var ref = m.groupValues[4]
        val path = m.groupValues[5].trim('/')
        val source = "$owner/$repo" + if (path.isNotEmpty()) "/$path" else ""

        if (ref.isEmpty()) {
            ref = JSONObject(get("https://api.github.com/repos/$owner/$repo")).getString("default_branch")
        }

        if (kind == "blob") {
            return listOf(parse(get(rawLink(owner, repo, ref, path)), folderName(path, repo), source))
        }

        val tree = JSONObject(
            get("https://api.github.com/repos/$owner/$repo/git/trees/${Uri.encode(ref)}?recursive=1")
        ).getJSONArray("tree")

        val files = ArrayList<String>()
        for (i in 0 until tree.length()) {
            val o = tree.getJSONObject(i)
            if (o.optString("type") != "blob") continue
            val p = o.optString("path")
            val isSkill = p.equals("SKILL.md", ignoreCase = true) || p.endsWith("/SKILL.md", ignoreCase = true)
            val inScope = path.isEmpty() || p.startsWith("$path/")
            if (isSkill && inScope) files.add(p)
        }

        if (files.isEmpty()) {
            if (path.isEmpty()) {
                val readme = try { get(rawLink(owner, repo, ref, "README.md")) } catch (_: IOException) { null }
                if (readme != null) return listOf(parse(readme, repo, source))
            }
            throw IOException("No SKILL.md files found in $source.")
        }

        return files.take(MAX_SKILLS).map { p ->
            parse(get(rawLink(owner, repo, ref, p)), folderName(p, repo), source)
        }
    }

    private fun rawLink(owner: String, repo: String, ref: String, path: String) =
        "https://raw.githubusercontent.com/$owner/$repo/${Uri.encode(ref, "/")}/${Uri.encode(path, "/")}"

    private fun folderName(path: String, repo: String): String =
        path.substringBeforeLast('/', "").substringAfterLast('/').ifBlank { repo }

    private fun get(url: String): String {
        val builder = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "PocketAgent")
        if (token.isNotBlank()) builder.header("Authorization", "Bearer ${token.trim()}")
        val req = builder.build()
        http.newCall(req).execute().use { r ->
            when {
                r.code == 404 -> throw IOException("Not found. The repo may be private or the link is wrong.")
                r.code == 403 || r.code == 429 -> throw IOException("GitHub rate limit reached. Try again in a few minutes.")
                !r.isSuccessful -> throw IOException("GitHub error ${r.code}")
            }
            return r.body?.string().orEmpty()
        }
    }

    /** Reads optional YAML front matter (name / description) from a SKILL.md. */
    private fun parse(raw: String, fallbackName: String, source: String): Skill {
        var name = fallbackName
        var desc = ""
        var body = raw.trim()

        if (body.startsWith("---")) {
            val end = body.indexOf("\n---", 3)
            if (end > 0) {
                val fm = body.substring(3, end).lines()
                var i = 0
                while (i < fm.size) {
                    val line = fm[i]
                    when {
                        line.startsWith("name:") -> name = line.removePrefix("name:").trim().trim('"', '\'')
                        line.startsWith("description:") -> {
                            var d = line.removePrefix("description:").trim()
                            if (d.isEmpty() || d == ">" || d == "|" || d == ">-" || d == "|-") {
                                val sb = StringBuilder()
                                while (i + 1 < fm.size && (fm[i + 1].startsWith(" ") || fm[i + 1].startsWith("\t"))) {
                                    sb.append(fm[i + 1].trim()).append(' ')
                                    i++
                                }
                                d = sb.toString().trim()
                            }
                            desc = d.trim('"', '\'')
                        }
                    }
                    i++
                }
                body = body.substring(end + 4).trim()
            }
        }

        if (desc.isBlank()) {
            desc = body.lines().map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: ""
        }
        return Skill(
            name = name.ifBlank { fallbackName },
            description = desc.take(300),
            content = body.take(MAX_CONTENT),
            source = source
        )
    }
}
