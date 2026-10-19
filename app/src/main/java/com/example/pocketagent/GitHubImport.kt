package com.example.pocketagent

import android.net.Uri
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/** Safe zip extraction: blocks "../" paths and caps the total size. */
object Zips {
    /** Extracts into [target]. With [stripTop], drops the single top-level folder (GitHub zipballs have one). Returns the file count. */
    fun extract(input: InputStream, target: File, stripTop: Boolean, maxBytes: Long = 150L * 1024 * 1024): Int {
        target.mkdirs()
        val base = target.canonicalPath
        var total = 0L
        var count = 0
        ZipInputStream(BufferedInputStream(input)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                var name = entry.name.replace('\\', '/')
                if (stripTop) name = name.substringAfter('/', "")
                if (name.isNotEmpty() && !entry.isDirectory) {
                    val out = File(target, name).canonicalFile
                    if (out.path.startsWith(base + File.separator)) {
                        out.parentFile?.mkdirs()
                        out.outputStream().use { os ->
                            val buf = ByteArray(8192)
                            var n = zis.read(buf)
                            while (n > 0) {
                                total += n
                                if (total > maxBytes) throw IOException("The archive is too large (over 150 MB).")
                                os.write(buf, 0, n)
                                n = zis.read(buf)
                            }
                        }
                        count++
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        return count
    }
}

/** Downloads a GitHub repository (public, or private with a token) into the workspace. */
object GitHub {
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private val repoRegex =
        Regex("""github\.com/([^/\s]+)/([^/\s#?]+)(?:/(tree|blob)/([^/\s#?]+)(?:/([^\s#?]*))?)?""")

    /** Blocking. Puts the repo in workspace/<repo>/ and returns a short summary for the user or the AI. */
    fun importRepo(ws: Workspace, rawUrl: String, token: String): String {
        val m = repoRegex.find(rawUrl.trim()) ?: throw IOException("Paste a github.com repository link.")
        val owner = m.groupValues[1]
        val repo = m.groupValues[2].removeSuffix(".git")
        val ref = m.groupValues[4]

        val url = "https://api.github.com/repos/$owner/$repo/zipball" + if (ref.isNotEmpty()) "/${Uri.encode(ref)}" else ""
        val builder = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "PocketAgent")
        if (token.isNotBlank()) builder.header("Authorization", "Bearer ${token.trim()}")

        val folder = if (repo == "uploads" || repo == "_zips") "$repo-repo" else repo
        val target = File(ws.root, folder)

        http.newCall(builder.build()).execute().use { r ->
            when {
                r.code == 404 -> throw IOException("Not found. If the repo is private, add a GitHub token in the Files tab.")
                r.code == 401 -> throw IOException("GitHub rejected the token. Check it in the Files tab.")
                r.code == 403 || r.code == 429 -> throw IOException("GitHub rate limit reached. Add a token or try again later.")
                !r.isSuccessful -> throw IOException("GitHub error ${r.code}")
            }
            val body = r.body ?: throw IOException("Empty response from GitHub")
            if (target.exists()) target.deleteRecursively()
            val n = Zips.extract(body.byteStream(), target, stripTop = true)
            val top = target.list()?.sorted()?.take(25)?.joinToString(", ").orEmpty()
            return "Imported $owner/$repo into $folder/ ($n files). Top level: $top"
        }
    }
}
