package cn.luoyi0914.ofmgateway

import android.content.Context
import android.os.Build
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** 应用内诊断：读取网关日志尾部、探测本地端口、组装可直接粘贴的诊断信息。 */
object Diagnostics {
    fun logTail(filesDir: String, maxChars: Int = 4_000): String {
        val latest = LogStore.latestDay(filesDir)
        if (latest != null) return LogStore.readDay(filesDir, latest, maxChars)
        val legacy = LogStore.legacyLog(filesDir)
        if (legacy.isFile) {
            return runCatching {
                val text = legacy.readText()
                if (text.length <= maxChars) text.ifBlank { "（日志为空）" }
                else "…（仅显示末尾）\n" + text.takeLast(maxChars)
            }.getOrElse { "读取日志失败：${it.message}" }
        }
        return "（暂无日志：${LogStore.logsDir(filesDir).absolutePath}）"
    }

    fun probePort(port: Int = ServiceInfo.DEFAULT_PORT, timeoutMs: Int = 3_000): String {
        var conn: HttpURLConnection? = null
        val result = runCatching {
            conn = (URL("http://127.0.0.1:$port/").openConnection() as HttpURLConnection).apply {
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                requestMethod = "GET"
            }
            val code = conn!!.responseCode
            val type = conn!!.getHeaderField("content-type") ?: "?"
            "HTTP $code · content-type=$type（端口已监听）"
        }.getOrElse { "连接失败：${it.javaClass.simpleName}: ${it.message}" }
        conn?.disconnect()
        return result
    }

    /**
     * 组装「提交议题」用的运行环境信息。
     *
     * 只放定位问题必需的量：版本、机型、ABI、状态、端口、日志文件名。
     * 不放 API Key、管理密钥、一次性登录链接——议题是公开的。
     */
    fun build(context: Context, state: GatewayUiState): String {
        val pkg = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
        val versionName = pkg?.versionName ?: "?"
        @Suppress("DEPRECATION")
        val versionCode = pkg?.versionCode?.toString() ?: "?"
        val nativeLib = File(context.applicationInfo.nativeLibraryDir)
        val runtimeLibraries = listOf("libnode.so", "libmagpie.so", "libofm_gateway.so")
            .joinToString(", ") { name ->
                val f = File(nativeLib, name)
                if (f.isFile) "$name=${f.length() / 1024}KB" else "$name=缺失"
            }
        return buildString {
            appendLine("应用版本：$versionName ($versionCode)")
            appendLine("设备：Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("ABI：${Build.SUPPORTED_ABIS.joinToString(", ")}")
            appendLine("运行状态：${state.status.label}（key=${state.status.key}）")
            appendLine("运行时已解包：${state.runtimeInstalled}${state.installError?.let { " · 错误：$it" } ?: ""}")
            appendLine("原生库：$runtimeLibraries")
            appendLine("PID：${if (state.pid > 0) state.pid else "—"}")
            appendLine("监听端口：${state.port}")
            appendLine("端口检测：${state.probe ?: "未检测"}")
            appendLine("开机自启：${state.bootAuto}")
            appendLine("通知权限：${if (state.notificationGranted) "已授权" else "未授权"}")
            appendLine("电池优化：${if (state.batteryOptimized) "未加入白名单" else "已加入白名单"}")
            appendLine("数据目录：${context.filesDir}")
            appendLine("当前日志：${state.selectedDay?.let { "gateway-$it.log" } ?: "（无按天日志）"}")
        }
    }
}
