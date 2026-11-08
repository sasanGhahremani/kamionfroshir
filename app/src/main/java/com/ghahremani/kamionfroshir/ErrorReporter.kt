package com.ghahremani.kamionfroshir

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.util.Log
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

object ErrorReporter {
    private const val ENDPOINT = "https://kamionfrosh.ir/AppLog/Report"
    private const val PREFS = "error_reporter"
    private const val KEY_QUEUE = "queue"
    private const val KEY_LAST_STOP = "last_stop"
    private const val KEY_LAST_EXIT = "last_exit_ts"
    private const val MAX_QUEUE = 30

    private val lock = Any()
    private val flushing = AtomicBoolean(false)
    private var ctx: Context? = null

    @Volatile
    var currentUrl: String? = null

    fun init(context: Context) {
        if (ctx != null) return
        ctx = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try { enqueue("crash", e.toString(), Log.getStackTraceString(e), currentUrl) } catch (_: Throwable) {}
            previous?.uncaughtException(t, e)
        }
    }

    // هنگام باز شدن برنامه صدا زده شود
        private var processReported = false

    // هنگام باز شدن برنامه صدا زده شود. restored = آیا اندروید وضعیت ذخیره‌شده را برگردانده؟
    fun onAppStart(restored: Boolean) {
        val c = ctx ?: return
        try {
            // فقط یک بار برای هر اجرای برنامه (نه با هر چرخش صفحه)
            if (!processReported) {
                processReported = true
                val sp = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val last = sp.getLong(KEY_LAST_STOP, 0L)
                if (last > 0L) {
                    val gap = (System.currentTimeMillis() - last) / 1000
                    if (gap < 30 * 60) {
                        enqueue("restart_after_background", "gap=${gap}s restoredState=$restored", null, null)
                    }
                }
                logLastExit(c)
            }
        } catch (_: Throwable) {}
        flush()
    }

    fun markStopped() {
        val c = ctx ?: return
        try {
            c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putLong(KEY_LAST_STOP, System.currentTimeMillis()).apply()
        } catch (_: Throwable) {}
    }

    // ثبت یک رویداد و ارسال فوری
    fun log(type: String, message: String?, stack: String?, url: String?) {
        try { enqueue(type, message, stack, url) } catch (_: Throwable) {}
        flush()
    }

    private fun logLastExit(c: Context) {
        if (Build.VERSION.SDK_INT < 30) return
        try {
            val am = c.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val info = am.getHistoricalProcessExitReasons(c.packageName, 0, 1).firstOrNull() ?: return
            val sp = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (info.timestamp <= sp.getLong(KEY_LAST_EXIT, 0L)) return
            sp.edit().putLong(KEY_LAST_EXIT, info.timestamp).apply()
            enqueue(
                "last_exit",
                "reason=${reasonName(info.reason)}(${info.reason}) importance=${info.importance} desc=${info.description}",
                null, null
            )
        } catch (_: Throwable) {}
    }

    private fun reasonName(r: Int): String = when (r) {
        1 -> "EXIT_SELF"
        2 -> "SIGNALED"
        3 -> "LOW_MEMORY"
        4 -> "CRASH"
        5 -> "CRASH_NATIVE"
        6 -> "ANR"
        7 -> "INITIALIZATION_FAILURE"
        8 -> "PERMISSION_CHANGE"
        9 -> "EXCESSIVE_RESOURCE_USAGE"
        10 -> "USER_REQUESTED"
        11 -> "USER_STOPPED"
        12 -> "DEPENDENCY_DIED"
        13 -> "OTHER"
        14 -> "FREEZER"
        15 -> "PACKAGE_STATE_CHANGE"
        16 -> "PACKAGE_UPDATED"
        else -> "UNKNOWN"
    }

    private fun appVersion(c: Context): String = try {
        val p = c.packageManager.getPackageInfo(c.packageName, 0)
        @Suppress("DEPRECATION")
        "${p.versionName} (${p.versionCode})"
    } catch (_: Throwable) { "?" }

    private fun webViewVersion(): String {
        if (Build.VERSION.SDK_INT < 26) return "?"
        return try {
            val p = WebView.getCurrentWebViewPackage()
            if (p != null) "${p.packageName} ${p.versionName}" else "?"
        } catch (_: Throwable) { "?" }
    }

    private fun enqueue(type: String, message: String?, stack: String?, url: String?) {
        val c = ctx ?: return
        val o = JSONObject()
        o.put("eventType", type)
        o.put("message", message ?: "")
        o.put("stack", stack ?: "")
        // پارامترهای آدرس (مثل شماره موبایل) ذخیره نمی‌شوند
        o.put("url", (url ?: "").substringBefore('?').substringBefore('#'))
        o.put("appVersion", appVersion(c))
        o.put("androidVersion", "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        o.put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
        o.put("webView", webViewVersion())
        o.put("clientTime", System.currentTimeMillis().toString())
        synchronized(lock) {
            val sp = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val arr = try { JSONArray(sp.getString(KEY_QUEUE, "[]")) } catch (_: Exception) { JSONArray() }
            arr.put(o)
            val trimmed = JSONArray()
            for (i in maxOf(0, arr.length() - MAX_QUEUE) until arr.length()) trimmed.put(arr.get(i))
            sp.edit().putString(KEY_QUEUE, trimmed.toString()).commit()
        }
    }

    fun flush() {
        val c = ctx ?: return
        if (!flushing.compareAndSet(false, true)) return
        thread(name = "error-flush") {
            try {
                val sp = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val snapshot = synchronized(lock) {
                    try { JSONArray(sp.getString(KEY_QUEUE, "[]")) } catch (_: Exception) { JSONArray() }
                }
                var ok = 0
                for (i in 0 until snapshot.length()) {
                    if (post(snapshot.getJSONObject(i))) ok++ else break
                }
                if (ok > 0) synchronized(lock) {
                    val cur = try { JSONArray(sp.getString(KEY_QUEUE, "[]")) } catch (_: Exception) { JSONArray() }
                    val rest = JSONArray()
                    for (i in ok until cur.length()) rest.put(cur.get(i))
                    sp.edit().putString(KEY_QUEUE, rest.toString()).commit()
                }
            } catch (_: Throwable) {
            } finally {
                flushing.set(false)
            }
        }
    }

    private fun post(o: JSONObject): Boolean {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(ENDPOINT).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
            conn.setRequestProperty("X-App-Client", "kamionfroshir-android")
            val keys = listOf("eventType", "message", "stack", "url", "appVersion", "androidVersion", "device", "webView", "clientTime")
            val body = keys.joinToString("&") { k -> k + "=" + URLEncoder.encode(o.optString(k, ""), "UTF-8") }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            conn.responseCode in 200..299
        } catch (_: Exception) {
            false
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }
}
