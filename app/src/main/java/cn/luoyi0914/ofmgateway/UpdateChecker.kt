package cn.luoyi0914.ofmgateway

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 版本检查：读应用自身仓库的最新 Release，和当前版本比较。
 *
 * 三条约束（都来自「这是无 root 的常驻服务」这个前提）：
 *  1. 只读公开 API，不带任何凭据，不发设备标识；
 *  2. 超时短、失败静默：网络不通不能影响网关启动与界面；
 *  3. 只提示，不自动下载安装——静默升级一个常驻服务不在本应用权限边界内。
 */
object UpdateChecker {
    private const val LATEST_URL = "https://api.github.com/repos/${IssueReporter.REPO}/releases/latest"
    private const val PREFS = "ofm_update"

    data class Release(val version: String, val url: String, val preRelease: Boolean)

    /** 纯函数：把 `v1.2.3` / `1.2.3-dev` 这类版本串切成可比较的数字段。 */
    fun parseVersion(raw: String): List<Int> =
        raw.trim()
            .removePrefix("v")
            .substringBefore('-')
            .split('.')
            .map { seg -> seg.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }

    /** 是否比当前版本更新；无法解析时按「不更新」处理，避免误提示。 */
    fun isNewer(candidate: String, current: String): Boolean {
        val a = parseVersion(candidate)
        val b = parseVersion(current)
        if (a.isEmpty() || b.isEmpty()) return false
        val size = maxOf(a.size, b.size)
        for (i in 0 until size) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    fun fetchLatest(timeoutMs: Int = 6000): Result<Release> = runCatching {
        val conn = (URL(LATEST_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            requestMethod = "GET"
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "ofm-gateway-android")
        }
        try {
            if (conn.responseCode !in 200..299) {
                throw IllegalStateException("HTTP ${conn.responseCode}")
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            val tag = json.optString("tag_name").removePrefix("v")
            if (tag.isBlank()) throw IllegalStateException("响应缺少 tag_name")
            Release(
                version = tag,
                url = json.optString("html_url").ifBlank { IssueReporter.appRepoUrl() },
                preRelease = json.optBoolean("prerelease", false),
            )
        } finally {
            conn.disconnect()
        }
    }

    /** 距上次检查是否已超过 [intervalMs]；用来避免每次进设置页都打一次 API。 */
    fun shouldCheck(context: android.content.Context, nowMs: Long = System.currentTimeMillis(), intervalMs: Long = 12 * 3600_000L): Boolean {
        val last = context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .getLong(KEY_LAST_CHECK, 0L)
        return nowMs - last > intervalMs
    }

    fun markChecked(context: android.content.Context, nowMs: Long = System.currentTimeMillis()) {
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .edit().putLong(KEY_LAST_CHECK, nowMs).apply()
    }

    private const val KEY_LAST_CHECK = "last_check_ms"
}
