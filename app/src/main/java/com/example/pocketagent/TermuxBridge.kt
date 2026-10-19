package com.example.pocketagent

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Runs shell commands inside the Termux app (https://github.com/termux/termux-app) and returns the output.
 * Uses Termux's documented RUN_COMMAND intent. Requirements on the phone:
 *   1. Termux installed from F-Droid or GitHub
 *   2. allow-external-apps=true in ~/.termux/termux.properties
 *   3. This app granted "Run commands in Termux environment"
 */
object TermuxBridge {
    const val PERMISSION = "com.termux.permission.RUN_COMMAND"

    private const val BASH = "/data/data/com.termux/files/usr/bin/bash"
    private const val HOME = "/data/data/com.termux/files/home"

    data class Result(
        val stdout: String,
        val stderr: String,
        val exitCode: Int?,
        val errmsg: String?,
        val truncated: Boolean
    )

    private val nextId = AtomicInteger(1)
    private val callbacks = ConcurrentHashMap<Int, (Result) -> Unit>()

    fun isInstalled(ctx: Context): Boolean = try {
        ctx.packageManager.getPackageInfo("com.termux", 0)
        true
    } catch (_: Exception) {
        false
    }

    fun hasPermission(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, PERMISSION) == PackageManager.PERMISSION_GRANTED

    /**
     * Starts [script] in Termux (background, login bash). [onResult] is called on the main thread when it ends.
     * Returns a request id that can be passed to [cancel]. Throws IOException with a readable message on failure.
     */
    fun run(ctx: Context, script: String, onResult: (Result) -> Unit): Int {
        if (!isInstalled(ctx)) {
            throw IOException("Termux isn't installed. Get it from F-Droid (not the Play Store), then see Settings → Termux.")
        }
        if (!hasPermission(ctx)) {
            throw IOException(
                "Permission needed: Android Settings → Apps → Pocket Agent → Permissions → " +
                    "\"Run commands in Termux environment\"."
            )
        }

        val id = nextId.getAndIncrement()
        callbacks[id] = onResult

        val resultIntent = Intent(ctx, TermuxResultReceiver::class.java).putExtra("req", id)
        val pending = PendingIntent.getBroadcast(
            ctx, id, resultIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )

        val intent = Intent().apply {
            setClassName("com.termux", "com.termux.app.RunCommandService")
            action = "com.termux.RUN_COMMAND"
            putExtra("com.termux.RUN_COMMAND_PATH", BASH)
            putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-lc", script))
            putExtra("com.termux.RUN_COMMAND_WORKDIR", HOME)
            putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
            putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", pending)
        }

        try {
            ctx.startService(intent)
        } catch (e: SecurityException) {
            callbacks.remove(id)
            throw IOException(
                "Termux refused the command. In Termux run: " +
                    "echo \"allow-external-apps=true\" >> ~/.termux/termux.properties  then close and reopen Termux."
            )
        } catch (e: Exception) {
            callbacks.remove(id)
            throw IOException("Couldn't reach Termux: ${e.message}")
        }
        return id
    }

    /** Stop waiting for a result. The command itself may keep running inside Termux. */
    fun cancel(id: Int) {
        callbacks.remove(id)
    }

    internal fun deliver(intent: Intent) {
        val id = intent.getIntExtra("req", -1)
        val callback = callbacks.remove(id) ?: return
        val b = intent.getBundleExtra("result")
        val stdout = b?.getString("stdout").orEmpty()
        val stderr = b?.getString("stderr").orEmpty()
        val exit = if (b != null && b.containsKey("exitCode")) b.getInt("exitCode") else null
        val original = b?.getString("stdout_original_length")?.toIntOrNull()
        callback(
            Result(
                stdout = stdout,
                stderr = stderr,
                exitCode = exit,
                errmsg = b?.getString("errmsg"),
                truncated = original != null && original > stdout.length
            )
        )
    }
}

/** Receives the result that Termux sends back through our PendingIntent. */
class TermuxResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        TermuxBridge.deliver(intent)
    }
}
