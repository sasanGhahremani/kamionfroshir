package com.ghahremani.kamionfroshir

import android.os.Looper
import android.webkit.CookieManager
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URL
import javax.net.ssl.HttpsURLConnection

class OfflineApiClient(
    private val expectedAccountId: Long
) {
    companion object {
        private const val BASE = "https://kamionfrosh.ir"
        private const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
    }

    data class ApiResult(
        val status: Int,
        val body: JSONObject
    ) {
        val successful: Boolean
            get() = status in 200..299

        val conflict: Boolean
            get() = status == 409
    }

    private var csrfToken: String? = null
    private var sessionVerified = false

    /**
     * این کلاس باید در یک اجرای ترتیبی و روی نخ پس‌زمینه استفاده شود.
     * برای هر همگام‌سازی، یک نمونه تازه بساز.
     */
    fun bootstrap(): JSONObject {
        val result = request(
            method = "GET",
            path = "/api/offline/bootstrap"
        )

        requireSuccess(result)

        val accountId = result.body
            .getJSONObject("account")
            .getLong("accountId")

        if (accountId != expectedAccountId) {
            throw IOException(
                "حساب واردشده در سایت با دفتر محلی یکسان نیست."
            )
        }

        csrfToken = result.body
            .getString("csrfToken")
            .takeIf { it.isNotBlank() }
            ?: throw IOException("توکن امنیتی دریافت نشد.")

        sessionVerified = true

        return result.body
    }

    fun ads(afterId: Long = 0): ApiResult {
        ensureSession()

        return request(
            method = "GET",
            path = "/api/offline/ads?afterId=$afterId"
        ).also(::requireSuccess)
    }

    fun events(afterId: Long = 0): ApiResult {
        ensureSession()

        return request(
            method = "GET",
            path = "/api/offline/events?afterId=$afterId"
        ).also(::requireSuccess)
    }

    /**
     * operationId باید پیش از اولین ارسال در دیتابیس محلی ذخیره شود.
     * هنگام تلاش مجدد، همین JSON و همین operationId را ارسال کن.
     */
    fun saveAd(payload: JSONObject): ApiResult {
        ensureSession()

        return request(
            method = "POST",
            path = "/api/offline/ad",
            payload = payload
        )
    }

    fun saveEvent(payload: JSONObject): ApiResult {
        ensureSession()

        return request(
            method = "POST",
            path = "/api/offline/event",
            payload = payload
        )
    }

    private fun ensureSession() {
        check(sessionVerified && !csrfToken.isNullOrBlank()) {
            "ابتدا bootstrap باید با موفقیت اجرا شود."
        }
    }

    private fun requireSuccess(result: ApiResult) {
        if (!result.successful) {
            throw IOException(
                result.body.optString(
                    "message",
                    "خطای سرور: ${result.status}"
                )
            )
        }
    }

    private fun request(
        method: String,
        path: String,
        payload: JSONObject? = null
    ): ApiResult {
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "همگام‌سازی نباید روی نخ اصلی اجرا شود."
        }

        val url = URL(BASE + path)

        val connection =
            url.openConnection() as HttpsURLConnection

        try {
            connection.requestMethod = method
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 20_000
            connection.readTimeout = 30_000
            connection.useCaches = false

            connection.setRequestProperty(
                "Accept",
                "application/json"
            )

            val cookieManager = CookieManager.getInstance()

            cookieManager.getCookie(BASE)
                ?.takeIf { it.isNotBlank() }
                ?.let {
                    connection.setRequestProperty("Cookie", it)
                }

            if (method == "POST") {
                connection.setRequestProperty(
                    "X-Offline-CSRF",
                    csrfToken ?: throw IOException(
                        "توکن امنیتی موجود نیست."
                    )
                )

                connection.setRequestProperty(
                    "Content-Type",
                    "application/json; charset=utf-8"
                )

                val bytes = (payload ?: JSONObject())
                    .toString()
                    .toByteArray(Charsets.UTF_8)

                connection.doOutput = true
                connection.setFixedLengthStreamingMode(bytes.size)

                connection.outputStream.use {
                    it.write(bytes)
                }
            }

            val status = connection.responseCode

            // کوکی ضدجعل و تمدید احتمالی نشست را نگه می‌داریم.
            connection.headerFields.forEach { (name, values) ->
                if (name?.equals(
                        "Set-Cookie",
                        ignoreCase = true
                    ) == true
                ) {
                    values?.forEach {
                        cookieManager.setCookie(BASE, it)
                    }
                }
            }

            if (status == 401 || status == 403 ||
                status in 300..399
            ) {
                sessionVerified = false

                throw IOException(
                    "نشست ورود معتبر نیست؛ دوباره وارد سایت شو."
                )
            }

            val stream = if (status in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }

            val text = stream?.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)

                while (true) {
                    val count = input.read(buffer)

                    if (count < 0) break

                    if (output.size() + count > MAX_RESPONSE_BYTES) {
                        throw IOException(
                            "پاسخ همگام‌سازی بیش از حد بزرگ است."
                        )
                    }

                    output.write(buffer, 0, count)
                }

                String(
                    output.toByteArray(),
                    Charsets.UTF_8
                )
            }.orEmpty()

            val body = try {
                JSONObject(text)
            } catch (_: Exception) {
                if (status in 200..299) {
                    throw IOException(
                        "پاسخ سرور JSON معتبر نیست."
                    )
                }

                JSONObject()
                    .put("success", false)
                    .put("message", "خطای سرور: $status")
            }

            return ApiResult(status, body)
        } finally {
            connection.disconnect()
        }
    }
}
