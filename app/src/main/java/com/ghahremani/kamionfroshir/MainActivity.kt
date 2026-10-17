package com.ghahremani.kamionfroshir

import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.view.WindowManager
import android.webkit.DownloadListener
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
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    companion object { private const val SITE_URL = "https://kamionfrosh.ir" }

    private lateinit var webView: WebView
    private lateinit var errorLayout: LinearLayout
    private lateinit var errorText: TextView
    private lateinit var retryBtn: Button
    private lateinit var loadingLayout: LinearLayout
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

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
            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
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
        if (savedInstanceState != null) webView.restoreState(savedInstanceState) else loadSite()
    }

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
        webView.saveState(outState)
    }
}
