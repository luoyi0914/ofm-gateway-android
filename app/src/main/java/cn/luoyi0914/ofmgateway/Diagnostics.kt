package cn.luoyi0914.ofmgateway

import java.net.HttpURLConnection
import java.net.URL

/** 应用内诊断：读取网关日志尾部、探测本地端口。 */
object Diagnostics {
    fun logTail(filesDir: String, maxChars: Int = 4000): String {
        val latest = LogStore.latestDay(filesDir)
        if (latest != null) return LogStore.readDay(filesDir, latest, maxChars)
        val legacy = LogStore.legacyLog(filesDir)
        if (legacy.isFile) return runCatching {
            val text = legacy.readText()
            if (text.length <= maxChars) text.ifBlank { "（日志为空）" } else "…（仅显示末尾）\n" + text.takeLast(maxChars)
        }.getOrElse { "读取日志失败：${it.message}" }
        return "（暂无日志：${LogStore.logsDir(filesDir).absolutePath}）"
    }

    fun probePort(port: Int = ServiceInfo.DEFAULT_PORT, timeoutMs: Int = 3000): String {
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
}
