package com.ghahremani.kamionfroshir

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class OfflineActivity : Activity() {

    private lateinit var browser: WebView
    private lateinit var store: DraftStore
    private var ownerId = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ownerId = intent.getStringExtra("ownerId")
            ?.takeIf { it.matches(Regex("[1-9][0-9]*")) }
            ?: ""

        if (ownerId.isEmpty()) {
            android.widget.Toast.makeText(
                this,
                "ابتدا یک‌بار با اینترنت وارد حساب شوید.",
                android.widget.Toast.LENGTH_LONG
            ).show()
            finish()
            return
        }

        window.setFlags(
            android.view.WindowManager.LayoutParams.FLAG_SECURE,
            android.view.WindowManager.LayoutParams.FLAG_SECURE
        )

        store = DraftStore(this)

        browser = WebView(this)
        browser.isSaveEnabled = false
        browser.isSaveFromParentEnabled = false

        browser.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = false
            allowFileAccess = true
            allowContentAccess = false
            cacheMode = WebSettings.LOAD_NO_CACHE
            blockNetworkLoads = true
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)

            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = false

            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = false
        }

        // این WebView فقط فایل محلی خود برنامه را نمایش می‌دهد.
        browser.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean = true

            @Suppress("DEPRECATION")
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                url: String?
            ): Boolean = true
        }

        browser.addJavascriptInterface(LocalBridge(), "OfflineDrafts")
        setContentView(browser)

        browser.loadUrl("file:///android_asset/offline-workspace.html")
    }

    inner class LocalBridge {

        @JavascriptInterface
        fun account(): String = ownerId

        @JavascriptInterface
        fun list(): String {
            return try {
                store.list(ownerId).toString()
            } catch (_: Exception) {
                JSONObject()
                    .put("success", false)
                    .put("message", "خواندن اطلاعات محلی ناموفق بود.")
                    .toString()
            }
        }

        @JavascriptInterface
        fun save(raw: String): String {
            return try {
                require(raw.length <= 30_000) {
                    "حجم متن پیش‌نویس بیش از حد مجاز است."
                }

                val body = JSONObject(raw)
                val kind = body.optString("kind")
                val title = body.optString("title").trim()
                val text = body.optString("text").trim()
                val reference = body.optString("reference").trim()
                val nextDate = body.optString("nextDate").trim()

                require(kind == "ad" || kind == "followup") {
                    "نوع پیش‌نویس معتبر نیست."
                }

                require(title.isNotEmpty() && title.length <= 200) {
                    "عنوان لازم است و باید حداکثر ۲۰۰ کاراکتر باشد."
                }

                require(text.length <= 20_000) {
                    "متن باید حداکثر ۲۰ هزار کاراکتر باشد."
                }

                require(reference.length <= 200) {
                    "شناسه یا نام آگهی طولانی است."
                }

                require(nextDate.length <= 100) {
                    "تاریخ واردشده طولانی است."
                }

                if (kind == "followup") {
                    require(reference.isNotEmpty()) {
                        "شناسه یا نام آگهی مرتبط را وارد کنید."
                    }
                }

                val requestedId = body.optString("id")
                val id = if (requestedId.isBlank()) {
                    UUID.randomUUID().toString()
                } else {
                    require(
                        requestedId.matches(
                            Regex("[0-9a-fA-F-]{36}")
                        )
                    ) { "شناسهٔ پیش‌نویس معتبر نیست." }
                    requestedId
                }

                val payload = JSONObject()
                    .put("title", title)
                    .put("text", text)
                    .put("reference", reference)
                    .put("nextDate", nextDate)

                store.save(ownerId, id, kind, payload.toString())

                JSONObject()
                    .put("success", true)
                    .put("id", id)
                    .toString()

            } catch (e: IllegalArgumentException) {
                JSONObject()
                    .put("success", false)
                    .put("message", e.message ?: "اطلاعات معتبر نیست.")
                    .toString()
            } catch (_: Exception) {
                JSONObject()
                    .put("success", false)
                    .put("message", "ذخیره نشد؛ فضای آزاد گوشی را بررسی کنید.")
                    .toString()
            }
        }

        @JavascriptInterface
        fun delete(id: String): String {
            return try {
                store.delete(ownerId, id)

                JSONObject()
                    .put("success", true)
                    .toString()
            } catch (_: Exception) {
                JSONObject()
                    .put("success", false)
                    .put("message", "حذف پیش‌نویس ناموفق بود.")
                    .toString()
            }
        }

        @JavascriptInterface
        fun close() {
            runOnUiThread { finish() }
        }
    }

    override fun onDestroy() {
        if (::browser.isInitialized) {
            browser.removeJavascriptInterface("OfflineDrafts")
            browser.stopLoading()
            browser.destroy()
        }

        if (::store.isInitialized) {
            store.close()
        }

        super.onDestroy()
    }
}

private class DraftStore(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    "offline_drafts.db",
    null,
    1
) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE drafts (
                owner_id TEXT NOT NULL,
                id TEXT NOT NULL,
                kind TEXT NOT NULL,
                payload TEXT NOT NULL,
                updated_at INTEGER NOT NULL,
                PRIMARY KEY (owner_id, id)
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE INDEX ix_drafts_owner_updated
            ON drafts(owner_id, updated_at DESC)
            """.trimIndent()
        )
    }

    override fun onUpgrade(
        db: SQLiteDatabase,
        oldVersion: Int,
        newVersion: Int
    ) {
        // ارتقای بعدی باید بدون حذف پیش‌نویس‌های کاربران انجام شود.
    }

    @Synchronized
    fun save(
        ownerId: String,
        id: String,
        kind: String,
        payload: String
    ) {
        val values = ContentValues().apply {
            put("owner_id", ownerId)
            put("id", id)
            put("kind", kind)
            put("payload", payload)
            put("updated_at", System.currentTimeMillis())
        }

        val result = writableDatabase.insertWithOnConflict(
            "drafts",
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE
        )

        check(result != -1L) { "ذخیره در پایگاه محلی ناموفق بود." }
    }

    @Synchronized
    fun list(ownerId: String): JSONObject {
        val items = JSONArray()

        readableDatabase.query(
            "drafts",
            arrayOf("id", "kind", "payload", "updated_at"),
            "owner_id = ?",
            arrayOf(ownerId),
            null,
            null,
            "updated_at DESC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                items.put(
                    JSONObject()
                        .put("id", cursor.getString(0))
                        .put("kind", cursor.getString(1))
                        .put("data", JSONObject(cursor.getString(2)))
                        .put("updatedAt", cursor.getLong(3))
                )
            }
        }

        return JSONObject()
            .put("success", true)
            .put("items", items)
    }

    @Synchronized
    fun delete(ownerId: String, id: String) {
        writableDatabase.delete(
            "drafts",
            "owner_id = ? AND id = ?",
            arrayOf(ownerId, id)
        )
    }
}
