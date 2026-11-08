package com.ghahremani.kamionfroshir

import android.app.Activity
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.webkit.DownloadListener
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.gms.auth.api.phone.SmsRetriever
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Status

class MainActivity : AppCompatActivity() {

    companion object { private const val SITE_URL = "https://kamionfrosh.ir" }

    private lateinit var webView: WebView
    private lateinit var errorLayout: LinearLayout
    private lateinit var errorText: TextView
    private lateinit var retryBtn: Button
    private lateinit var loadingLayout: LinearLayout

    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    // ===== خواندن خودکار کد پیامک (SMS User Consent) =====
    private var smsReceiver: BroadcastReceiver? = null
    private var pendingSmsCode: String? = null
    private val smsHandler = Handler(Looper.getMainLooper())
    private val smsTimeout = Runnable {
        stopSmsListening()
        pendingSmsCode = null
    }

    // نتیجه‌ی دیالوگ تأییدی که اندروید به کاربر نشان می‌دهد
    private val consentLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            ErrorReporter.log("sms_debug", "consent result=${result.resultCode}", null, null)
            if (result.resultCode == Activity.RESULT_OK) {
                val body = result.data?.getStringExtra(SmsRetriever.EXTRA_SMS_MESSAGE)
                val code = if (body != null) extractOtp(body) else null
                ErrorReporter.log("sms_debug", "consent body len=${body?.length} matched=${code != null}", null, null)
                if (code != null) {
                    pendingSmsCode = code
                    deliverSmsCode(code)
                }
            }
        }

    inner class SmsBridge {
        @JavascriptInterface
        fun start() { runOnUiThread { startSmsListening() } }

        @JavascriptInterface
        fun stop() {
            runOnUiThread {
                stopSmsListening()
                pendingSmsCode = null
            }
        }
    }

    private val filePicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (filePathCallback == null) return@registerForActivityResult
        var uris: Array<Uri>? = null
        if (result.resultCode == RESULT_OK && result.data != null) {
            val data = result.data!!
            try {
                val parsed = WebChromeClient.FileChooserParams.parseResult(result.resultCode, data)
                if (parsed != null && parsed.isNotEmpty()) {
                    uris = parsed
                }
            } catch (_: Exception) {}
            if (uris == null) {
                if (data.clipData != null) {
                    val count = data.clipData!!.itemCount
                    val list = mutableListOf<Uri>()
                    for (i in 0 until count) {
                        list.add(data.clipData!!.getItemAt(i).uri)
                    }
                    uris = list.toTypedArray()
                } else if (data.data != null) {
                    uris = arrayOf(data.data!!)
                }
            }
        }
        filePathCallback?.onReceiveValue(uris)
        filePathCallback = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ErrorReporter.init(this)
        ErrorReporter.onAppStart(savedInstanceState != null)

        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
        setContentView(R.layout.activity_main)
        supportActionBar?.hide()

        webView = findViewById(R.id.webView)
        errorLayout = findViewById(R.id.errorLayout)
        errorText = findViewById(R.id.errorText)
        retryBtn = findViewById(R.id.retryBtn)
        loadingLayout = findViewById(R.id.loadingLayout)

        // رفع کرش TransactionTooLargeException
        webView.isSaveEnabled = false
        webView.isSaveFromParentEnabled = false

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = true
            loadWithOverviewMode = true
            useWideViewPort = true
            cacheMode = WebSettings.LOAD_DEFAULT
        }

        webView.addJavascriptInterface(SmsBridge(), "AndroidSms")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                if (url.startsWith("tel:") || url.startsWith("sms:") || url.startsWith("mailto:") ||
                    url.contains("wa.me") || url.startsWith("tg:") || url.contains("t.me") ||
                    url.contains("rubika.ir") || url.contains("eitaa.com")) {
                    try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                    catch (e: ActivityNotFoundException) { Toast.makeText(this@MainActivity, "برنامه‌ای برای باز کردن این لینک نیست", Toast.LENGTH_SHORT).show() }
                    return true
                }
                if (!url.contains("kamionfrosh.ir")) {
                    try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) {}
                    return true
                }
                return false
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                ErrorReporter.currentUrl = url
                if (errorLayout.visibility != View.VISIBLE) {
                    loadingLayout.visibility = View.VISIBLE
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                loadingLayout.visibility = View.GONE
                if (errorLayout.visibility != View.VISIBLE) {
                    webView.visibility = View.VISIBLE
                }
            }

            @RequiresApi(26)
            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                ErrorReporter.log("renderer_gone", "didCrash=${detail?.didCrash()}", null, view?.url)
                return false
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    ErrorReporter.log("web_error", "${error?.errorCode} ${error?.description}", null, request.url?.toString())
                    loadingLayout.visibility = View.GONE
                    if (!isInternetAvailable()) showError("اتصال اینترنتی ندارید")
                    else showError("این صفحه در دسترس نیست")
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(mWebView: WebView?, mFilePathCallback: ValueCallback<Array<Uri>>?, mFileChooserParams: FileChooserParams?): Boolean {
                filePathCallback?.onReceiveValue(null)
                filePathCallback = mFilePathCallback
                try {
                    val accept = mFileChooserParams?.acceptTypes?.firstOrNull { it.isNotBlank() } ?: "image/*"
                    val multiple = mFileChooserParams?.mode == FileChooserParams.MODE_OPEN_MULTIPLE
                    val contentIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = if (accept.contains("image")) "image/*" else "*/*"
                        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multiple)
                        if (accept.isNotBlank()) putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(accept))
                    }
                    val chooser = Intent(Intent.ACTION_CHOOSER).apply {
                        putExtra(Intent.EXTRA_INTENT, contentIntent)
                        putExtra(Intent.EXTRA_TITLE, "انتخاب تصویر")
                    }
                    filePicker.launch(chooser)
                } catch (_: Exception) {
                    filePathCallback?.onReceiveValue(null)
                    filePathCallback = null
                    return false
                }
                return true
            }
        }

        webView.setDownloadListener(DownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            try {
                val req = DownloadManager.Request(Uri.parse(url))
                req.setMimeType(mimeType)
                req.addRequestHeader("User-Agent", userAgent)
                req.setTitle("kamionfrosh download")
                req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "kamionfrosh_" + System.currentTimeMillis())
                (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
                Toast.makeText(this, "دانلود شروع شد", Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {
                try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) {}
            }
        })

        retryBtn.setOnClickListener { loadSite() }

        // به‌جای restoreState (که باعث کرش می‌شد) فقط آخرین آدرس را باز می‌کنیم
        val lastUrl = savedInstanceState?.getString("lastUrl")
        if (lastUrl != null && lastUrl.startsWith(SITE_URL)) {
            hideError()
            webView.loadUrl(lastUrl)
        } else {
            loadSite()
        }
    }

    // ================= خواندن خودکار کد پیامک =================

    private fun startSmsListening() {
        ErrorReporter.log("sms_debug", "start called receiverActive=${smsReceiver != null}", null, null)

        // تمدید زمان شنود (۵ دقیقه)
        smsHandler.removeCallbacks(smsTimeout)
        smsHandler.postDelayed(smsTimeout, 5 * 60 * 1000L)

        // اگر کدی قبلاً رسیده ولی صفحه هنوز آماده نبود، دوباره تحویل بده
        pendingSmsCode?.let { deliverSmsCode(it) }

        registerSmsReceiver()

        // هر بار که start صدا زده شود، شنود جدید (برای یک پیامک) شروع می‌شود
        try {
            SmsRetriever.getClient(this).startSmsUserConsent(null)
                .addOnSuccessListener { ErrorReporter.log("sms_debug", "consent listening", null, null) }
                .addOnFailureListener { ErrorReporter.log("sms_debug", "consent start FAILED: ${it.message}", null, null) }
        } catch (e: Exception) {
            ErrorReporter.log("sms_debug", "consent exception: ${e.message}", null, null)
        }
    }

    private fun registerSmsReceiver() {
        if (smsReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != SmsRetriever.SMS_RETRIEVED_ACTION) return
                val extras = intent.extras ?: return
                val status = extras.get(SmsRetriever.EXTRA_STATUS) as? Status ?: return
                ErrorReporter.log("sms_debug", "onReceive statusCode=${status.statusCode}", null, null)
                if (status.statusCode == CommonStatusCodes.SUCCESS) {
                    val consentIntent = extras.getParcelable<Intent>(SmsRetriever.EXTRA_CONSENT_INTENT)
                    if (consentIntent != null) {
                        try { consentLauncher.launch(consentIntent) }
                        catch (e: Exception) { ErrorReporter.log("sms_debug", "launch FAILED: ${e.message}", null, null) }
                    }
                }
            }
        }
        try {
            ContextCompat.registerReceiver(
                this,
                receiver,
                IntentFilter(SmsRetriever.SMS_RETRIEVED_ACTION),
                SmsRetriever.SEND_PERMISSION,
                null,
                ContextCompat.RECEIVER_EXPORTED
            )
            smsReceiver = receiver
            ErrorReporter.log("sms_debug", "receiver registered", null, null)
        } catch (e: Exception) {
            ErrorReporter.log("sms_debug", "register FAILED: ${e.message}", null, null)
        }
    }

    private fun stopSmsListening() {
        smsHandler.removeCallbacks(smsTimeout)
        smsReceiver?.let { try { unregisterReceiver(it) } catch (_: Exception) {} }
        smsReceiver = null
    }

    private fun normalizeText(s: String): String {
        val sb = StringBuilder(s.length)
        for (ch in s) {
            when (ch) {
                in '۰'..'۹' -> sb.append('0' + (ch - '۰'))
                in '٠'..'٩' -> sb.append('0' + (ch - '٠'))
                'ي' -> sb.append('ی')
                'ك' -> sb.append('ک')
                '\u200c', '\u200f', '\u200e', '\u00a0' -> sb.append(' ')
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    // نمونه پیامک: «کد تایید شما در کامیون فروش 96333لغو11»
    private fun extractOtp(body: String): String? {
        val text = normalizeText(body)
        return Regex("(?<!\\d)(\\d{5})(?!\\d)").find(text)?.groupValues?.get(1)
    }

    private fun deliverSmsCode(code: String) {
        val host = try { Uri.parse(webView.url ?: return).host } catch (_: Exception) { null } ?: return
        if (host != "kamionfrosh.ir" && !host.endsWith(".kamionfrosh.ir")) return
        webView.evaluateJavascript(
            "(function(){return !!(window.onSmsCodeReceived && window.onSmsCodeReceived('$code'));})()"
        ) { res ->
            ErrorReporter.log("sms_debug", "deliver result=$res", null, null)
            if (res == "true") {
                pendingSmsCode = null
                stopSmsListening()
            }
        }
    }

    // ===========================================================

    private fun loadSite() {
        if (!isInternetAvailable()) {
            loadingLayout.visibility = View.GONE
            showError("اتصال اینترنتی ندارید")
            return
        }
        hideError()
        webView.visibility = View.GONE
        loadingLayout.visibility = View.VISIBLE
        webView.loadUrl(SITE_URL)
    }

    private fun showError(msg: String) {
        webView.visibility = View.GONE
        loadingLayout.visibility = View.GONE
        errorLayout.visibility = View.VISIBLE
        errorText.text = msg
    }

    private fun hideError() {
        errorLayout.visibility = View.GONE
        webView.visibility = View.VISIBLE
    }

    private fun isInternetAvailable(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val net = cm.activeNetwork ?: return false
        val cap = cm.getNetworkCapabilities(net) ?: return false
        return cap.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                cap.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                cap.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // فقط آدرس را ذخیره می‌کنیم، نه state کامل وب‌ویو
        outState.putString("lastUrl", webView.url)
    }

    override fun onStop() {
        super.onStop()
        ErrorReporter.markStopped()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // 15 = حافظه بحرانی، 40 به بالا = برنامه در پس‌زمینه و در خطر بسته شدن
        if (level == 15 || level >= 40) ErrorReporter.log("memory_trim", "level=$level", null, null)
    }

    override fun onDestroy() {
        stopSmsListening()
        super.onDestroy()
    }
}
