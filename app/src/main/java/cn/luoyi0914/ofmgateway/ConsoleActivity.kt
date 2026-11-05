package cn.luoyi0914.ofmgateway

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import cn.luoyi0914.ofmgateway.ui.OfmTheme

/**
 * 内置 WebView 管理控制台：加载本地 `http://127.0.0.1:<port>/#login=…`。
 *
 * 一次性登录链接被写进 URL，所以退出时必须清掉 cookie 与 WebStorage：
 * 否则同一台手机上后打开的应用（或下次进入控制台）会沿用上一份 8 小时管理会话。
 */
class ConsoleActivity : ComponentActivity() {

    companion object {
        const val EXTRA_URL = "extra_url"
    }

    private var webView: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val startUrl = intent.getStringExtra(EXTRA_URL) ?: ServiceInfo.baseUrl()

        setContent {
            OfmTheme {
                var error by remember { mutableStateOf<String?>(null) }
                var attempt by remember { mutableIntStateOf(0) }

                Scaffold { padding ->
                    Column(Modifier.fillMaxSize().padding(padding)) {
                        ConsoleHeader(onBack = { finish() })

                        if (error != null) {
                            Column(
                                Modifier.fillMaxSize().padding(24.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text("无法打开控制台", style = MaterialTheme.typography.titleLarge)
                                Text(
                                    error!!,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Button(onClick = {
                                    error = null
                                    attempt++
                                }) { Text("重试") }
                                OutlinedButton(onClick = { finish() }) { Text("返回") }
                            }
                        } else {
                            // attempt 变化时整块重建，等价于「重新加载」。
                            key(attempt) {
                                AndroidView(
                                    modifier = Modifier.fillMaxSize(),
                                    factory = { ctx ->
                                        WebView(ctx).apply {
                                            layoutParams = ViewGroup.LayoutParams(
                                                ViewGroup.LayoutParams.MATCH_PARENT,
                                                ViewGroup.LayoutParams.MATCH_PARENT,
                                            )
                                            configureForConsole()
                                            webViewClient = object : WebViewClient() {
                                                override fun onReceivedError(
                                                    view: WebView,
                                                    request: WebResourceRequest,
                                                    err: WebResourceError,
                                                ) {
                                                    if (request.isForMainFrame) {
                                                        error = "${err.description}（请确认网关已在运行）"
                                                    }
                                                }
                                            }
                                            webChromeClient = WebChromeClient()
                                            webView = this
                                            loadUrl(startUrl)
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val cur = webView
                if (cur != null && cur.canGoBack()) cur.goBack() else finish()
            }
        })
    }

    @Composable
    private fun ConsoleHeader(onBack: () -> Unit) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text("管理控制台", style = MaterialTheme.typography.titleLarge)
            Text(
                "本地页面，登录凭据仅在内存中",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = onBack) { Text("关闭") }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun WebView.configureForConsole() {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        // 管理页是本地 http，决不允许它读本地文件或 content:// 资源。
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.mediaPlaybackRequiresUserGesture = true
    }

    override fun onDestroy() {
        val wv = webView
        webView = null
        if (wv != null) {
            (wv.parent as? ViewGroup)?.removeView(wv)
            wv.stopLoading()
            wv.loadUrl("about:blank")
            wv.clearHistory()
            wv.clearCache(true)
            wv.removeAllViews()
            wv.destroy()
        }
        // 会话与一次性 token 不留在磁盘上。
        runCatching {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
            WebStorage.getInstance().deleteAllData()
        }
        super.onDestroy()
    }
}
