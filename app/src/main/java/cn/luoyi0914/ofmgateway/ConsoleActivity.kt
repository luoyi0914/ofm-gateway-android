package cn.luoyi0914.ofmgateway

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/** 内置 WebView 管理控制台：加载本地 http://127.0.0.1:<port>/#login=… 页面。 */
class ConsoleActivity : ComponentActivity() {

    companion object {
        const val EXTRA_URL = "extra_url"
    }

    private var webView: WebView? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val url = intent.getStringExtra(EXTRA_URL) ?: ServiceInfo.baseUrl()

        val root = FrameLayout(this)
        val wv = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame) {
                        showError("加载失败：${error.description}（请确认网关已启动）")
                    }
                }
            }
        }
        webView = wv
        root.addView(wv)
        setContentView(root)

        // 系统栏避让（edge-to-edge 下否则顶部/底部被裁切）。
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        wv.loadUrl(url)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val cur = webView
                if (cur != null && cur.canGoBack()) cur.goBack() else finish()
            }
        })
    }

    private fun showError(message: String) {
        val tv = TextView(this).apply {
            text = message
            setTextColor(Color.DKGRAY)
            textSize = 15f
            setPadding(48, 96, 48, 48)
        }
        setContentView(tv)
    }

    override fun onDestroy() {
        val wv = webView
        webView = null
        if (wv != null) {
            (wv.parent as? ViewGroup)?.removeView(wv)
            wv.stopLoading()
            wv.loadUrl("about:blank")
            wv.clearHistory()
            wv.removeAllViews()
            wv.destroy()
        }
        super.onDestroy()
    }
}
