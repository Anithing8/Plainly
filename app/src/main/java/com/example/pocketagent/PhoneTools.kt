package com.example.pocketagent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.json.JSONObject

/**
 * Tools that let the AI do things on this phone.
 * Shortcut tools use normal Android intents (alarms, timers, links, drafts).
 * Screen tools (read_screen, tap, type_text, scroll, press) need the Pocket Agent accessibility service,
 * which the user enables by hand in Android settings.
 */
object PhoneTools {
    val NAMES = setOf(
        "open_app", "open_url", "web_search", "dial", "compose_sms", "compose_email", "set_alarm",
        "set_timer", "open_maps", "device_info", "read_screen", "tap", "type_text", "scroll", "press", "wait"
    )

    fun schema(): List<JSONObject> = listOf(
        Tools.tool("open_app", "Open an installed app by its name.",
            JSONObject().put("name", Tools.str("App name, for example YouTube")), listOf("name")),
        Tools.tool("open_url", "Open a web link in the browser.",
            JSONObject().put("url", Tools.str("The link")), listOf("url")),
        Tools.tool("web_search", "Search the web in the browser.",
            JSONObject().put("query", Tools.str("Search text")), listOf("query")),
        Tools.tool("dial", "Open the phone dialer with a number. The user must press call.",
            JSONObject().put("number", Tools.str("Phone number")), listOf("number")),
        Tools.tool("compose_sms", "Open the messages app with a draft. The user must press send.",
            JSONObject().put("number", Tools.str("Phone number")).put("text", Tools.str("Message text")),
            listOf("number", "text")),
        Tools.tool("compose_email", "Open the email app with a draft. The user must press send.",
            JSONObject().put("to", Tools.str("Email address")).put("subject", Tools.str("Subject"))
                .put("body", Tools.str("Body text")),
            listOf("to")),
        Tools.tool("set_alarm", "Set an alarm.",
            JSONObject().put("hour", Tools.integer("Hour, 0 to 23")).put("minute", Tools.integer("Minute, 0 to 59"))
                .put("label", Tools.str("Alarm label")),
            listOf("hour", "minute")),
        Tools.tool("set_timer", "Start a countdown timer.",
            JSONObject().put("seconds", Tools.integer("Length in seconds")).put("label", Tools.str("Timer label")),
            listOf("seconds")),
        Tools.tool("open_maps", "Search for a place in the maps app.",
            JSONObject().put("query", Tools.str("Place or address")), listOf("query")),
        Tools.tool("device_info", "Get the time, battery level and phone model.", JSONObject(), emptyList()),
        Tools.tool("read_screen", "Read what is on the screen right now as a numbered list of elements.",
            JSONObject(), emptyList()),
        Tools.tool("tap", "Tap an element from the latest read_screen list.",
            JSONObject().put("index", Tools.integer("Element number")), listOf("index")),
        Tools.tool("type_text", "Type text into an editable element from the latest read_screen list.",
            JSONObject().put("index", Tools.integer("Element number")).put("text", Tools.str("Text to type")),
            listOf("index", "text")),
        Tools.tool("scroll", "Scroll the screen.",
            JSONObject().put("direction", Tools.str("up, down, left or right")), listOf("direction")),
        Tools.tool("press", "Press a system button.",
            JSONObject().put("button", Tools.str("back, home, recents or notifications")), listOf("button")),
        Tools.tool("wait", "Wait a moment for the screen to update.",
            JSONObject().put("seconds", Tools.integer("1 to 15")), listOf("seconds"))
    )

    suspend fun run(ctx: Context, name: String, args: JSONObject): String = try {
        when (name) {
            "open_app" -> openApp(ctx, args.text("name"))
            "open_url" -> launch(ctx, Intent(Intent.ACTION_VIEW, Uri.parse(fixUrl(args.text("url")))), "Opened the link.")
            "web_search" -> launch(
                ctx,
                Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(args.text("query")))),
                "Opened a web search."
            )
            "dial" -> launch(
                ctx, Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", args.text("number"), null)),
                "Opened the dialer. The user must press call."
            )
            "compose_sms" -> launch(
                ctx,
                Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", args.text("number"), null))
                    .putExtra("sms_body", args.text("text")),
                "Opened a message draft. The user must press send."
            )
            "compose_email" -> launch(
                ctx,
                Intent(Intent.ACTION_SENDTO, Uri.fromParts("mailto", args.text("to"), null))
                    .putExtra(Intent.EXTRA_SUBJECT, args.text("subject"))
                    .putExtra(Intent.EXTRA_TEXT, args.text("body")),
                "Opened an email draft. The user must press send."
            )
            "set_alarm" -> launch(
                ctx,
                Intent(AlarmClock.ACTION_SET_ALARM)
                    .putExtra(AlarmClock.EXTRA_HOUR, args.optInt("hour"))
                    .putExtra(AlarmClock.EXTRA_MINUTES, args.optInt("minute"))
                    .putExtra(AlarmClock.EXTRA_MESSAGE, args.text("label"))
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, true),
                "Alarm set."
            )
            "set_timer" -> launch(
                ctx,
                Intent(AlarmClock.ACTION_SET_TIMER)
                    .putExtra(AlarmClock.EXTRA_LENGTH, args.optInt("seconds", 60))
                    .putExtra(AlarmClock.EXTRA_MESSAGE, args.text("label"))
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, true),
                "Timer started."
            )
            "open_maps" -> launch(
                ctx, Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(args.text("query")))),
                "Opened maps."
            )
            "device_info" -> deviceInfo(ctx)
            "wait" -> {
                delay(args.optInt("seconds", 1).coerceIn(1, 15) * 1000L)
                "Waited."
            }
            else -> screenTool(name, args)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        "Error: ${e.message}"
    }

    private fun fixUrl(u: String): String {
        val t = u.trim()
        return if (t.startsWith("http://") || t.startsWith("https://")) t else "https://$t"
    }

    private fun launch(ctx: Context, intent: Intent, ok: String): String {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            (PhoneService.instance ?: ctx).startActivity(intent)
            ok
        } catch (e: ActivityNotFoundException) {
            "No app on this phone can do that."
        }
    }

    private fun openApp(ctx: Context, name: String): String {
        val q = name.trim()
        if (q.isEmpty()) return "Error: no app name given."
        val pm = ctx.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val labeled = pm.queryIntentActivities(launcher, 0).map { it to it.loadLabel(pm).toString() }
        val hit = labeled.firstOrNull { it.second.equals(q, ignoreCase = true) }
            ?: labeled.firstOrNull { it.second.contains(q, ignoreCase = true) }
            ?: return "No installed app matches \"$q\". Installed apps include: " +
                labeled.map { it.second }.sorted().take(40).joinToString(", ")
        val intent = pm.getLaunchIntentForPackage(hit.first.activityInfo.packageName)
            ?: return "Can't launch ${hit.second}."
        return launch(ctx, intent, "Opened ${hit.second}. Call wait(1) then read_screen to see it.")
    }

    private fun deviceInfo(ctx: Context): String {
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val now = java.text.DateFormat.getDateTimeInstance().format(java.util.Date())
        return "Time: $now\nBattery: $level%\nPhone: ${Build.MANUFACTURER} ${Build.MODEL}\n" +
            "Android: ${Build.VERSION.RELEASE}\nScreen control enabled: ${PhoneService.instance != null}"
    }

    private fun screenTool(name: String, args: JSONObject): String {
        val svc = PhoneService.instance
            ?: return "Screen control is off. Ask the user to enable Pocket Agent in Android Settings, Accessibility."
        return when (name) {
            "read_screen" -> svc.describeScreen()
            "tap" -> svc.tapNode(args.optInt("index", -1))
            "type_text" -> svc.typeText(args.optInt("index", -1), args.text("text"))
            "scroll" -> svc.scroll(args.text("direction"))
            "press" -> svc.press(args.text("button"))
            else -> "Unknown tool: $name"
        }
    }
}

/** Lets the AI read the screen and tap, type and scroll. Does nothing until the user turns it on in Android settings. */
class PhoneService : AccessibilityService() {
    @Volatile private var nodes: List<AccessibilityNodeInfo> = emptyList()
    private var overlay: View? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    fun describeScreen(): String {
        val root = rootInActiveWindow ?: return "Can't read the screen right now. Try wait(1) and read_screen again."
        val list = ArrayList<AccessibilityNodeInfo>()
        val sb = StringBuilder("App: ${root.packageName}\n")

        fun walk(n: AccessibilityNodeInfo?) {
            if (n == null || list.size >= 150) return
            if (n.isVisibleToUser) {
                val text = (n.text ?: n.contentDescription)?.toString()?.trim().orEmpty()
                val interactive = n.isClickable || n.isEditable || n.isCheckable || n.isScrollable
                if (text.isNotEmpty() || interactive) {
                    list.add(n)
                    val kind = (n.className?.toString() ?: "").substringAfterLast('.')
                    sb.append("[${list.size - 1}] $kind")
                    if (text.isNotEmpty()) sb.append(" \"${text.take(80)}\"")
                    if (n.isEditable) sb.append(" (editable)") else if (n.isClickable) sb.append(" (clickable)")
                    if (n.isScrollable) sb.append(" (scrollable)")
                    sb.append('\n')
                }
            }
            for (i in 0 until n.childCount) walk(n.getChild(i))
        }

        walk(root)
        nodes = list
        return sb.toString()
    }

    fun tapNode(index: Int): String {
        val n = nodes.getOrNull(index) ?: return "No element [$index]. Call read_screen again."
        n.refresh()
        var target: AccessibilityNodeInfo? = n
        while (target != null && !target.isClickable) target = target.parent
        if (target != null && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            Thread.sleep(300)
            return "Tapped [$index]."
        }
        val r = Rect()
        n.getBoundsInScreen(r)
        return if (tapAt(r.exactCenterX(), r.exactCenterY())) "Tapped [$index] at its center." else "Couldn't tap [$index]."
    }

    fun typeText(index: Int, text: String): String {
        val n = nodes.getOrNull(index) ?: return "No element [$index]. Call read_screen again."
        n.refresh()
        n.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        return if (n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) "Typed into [$index]." else "Couldn't type into [$index]. Is it an editable field?"
    }

    fun scroll(direction: String): String {
        val m = resources.displayMetrics
        val w = m.widthPixels.toFloat()
        val h = m.heightPixels.toFloat()
        val pts = when (direction.trim().lowercase()) {
            "down" -> listOf(w / 2, h * 0.75f, w / 2, h * 0.25f)
            "up" -> listOf(w / 2, h * 0.25f, w / 2, h * 0.75f)
            "left" -> listOf(w * 0.8f, h / 2, w * 0.2f, h / 2)
            "right" -> listOf(w * 0.2f, h / 2, w * 0.8f, h / 2)
            else -> return "Direction must be up, down, left or right."
        }
        return if (swipe(pts[0], pts[1], pts[2], pts[3])) "Scrolled $direction." else "Couldn't scroll."
    }

    fun press(button: String): String {
        val action = when (button.trim().lowercase()) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents" -> GLOBAL_ACTION_RECENTS
            "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
            else -> return "Button must be back, home, recents or notifications."
        }
        val ok = performGlobalAction(action)
        Thread.sleep(400)
        return if (ok) "Pressed $button." else "Couldn't press $button."
    }

    private fun tapAt(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 60L)).build()
        val ok = dispatchGesture(gesture, null, null)
        Thread.sleep(300)
        return ok
    }

    private fun swipe(x1: Float, y1: Float, x2: Float, y2: Float): Boolean {
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 350L)).build()
        val ok = dispatchGesture(gesture, null, null)
        Thread.sleep(500)
        return ok
    }

    /** A floating "Stop AI" button that stays on top of other apps while the AI works. */
    fun showStop(onStop: () -> Unit) {
        Handler(Looper.getMainLooper()).post {
            if (overlay == null) {
                val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
                val button = Button(this)
                button.text = "Stop AI"
                button.setOnClickListener {
                    onStop()
                    hideStop()
                }
                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT
                )
                params.gravity = Gravity.TOP or Gravity.END
                params.y = 200
                try {
                    wm.addView(button, params)
                    overlay = button
                } catch (_: Exception) {
                }
            }
        }
    }

    fun hideStop() {
        Handler(Looper.getMainLooper()).post {
            val v = overlay
            if (v != null) {
                try {
                    (getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v)
                } catch (_: Exception) {
                }
                overlay = null
            }
        }
    }

    companion object {
        @Volatile
        var instance: PhoneService? = null
    }
}
