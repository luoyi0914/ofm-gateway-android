package cn.luoyi0914.ofmgateway

import org.json.JSONArray
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
    // 不能用 /releases/latest：本项目所有版本都按 pre-release 发布（观察窗后才手动提升），
    // 而该端点只认正式版，会稳定返回 404，导致检查更新永远静默失败。
    private const val RELEASES_URL = "https://api.github.com/repos/${IssueReporter.REPO}/releases?per_page=30"
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
        val conn = (URL(RELEASES_URL).openConnection() as HttpURLConnection).apply {
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
            val releases = parseReleases(body)
            if (releases.isEmpty()) throw IllegalStateException("响应里没有可识别的版本")
            // 取版本号最大的那个，而不是「列表第一个」：列表可能混入 runtime-1 这类非应用制品。
            releases.maxByOrNull { parseVersion(it.version).fold(0) { acc, n -> acc * 1000 + n } }
                ?: error("unreachable")
        } finally {
            conn.disconnect()
        }
    }

    /**
     * 纯函数：把 Releases 列表 JSON 切成版本条目。
     *
     * 只收「tag 以数字开头」的条目——`runtime-1` 是运行时制品，不是应用版本，
     * 让它参与比较只会制造假提示。草稿一律丢弃。
     */
    fun parseReleases(body: String): List<Release> {
        val arr = org.json.JSONArray(body)
        val out = ArrayList<Release>(arr.length())
        for (i in 0 until arr.length()) {
            val json = arr.optJSONObject(i) ?: continue
            if (json.optBoolean("draft", false)) continue
            val tag = json.optString("tag_name").trim().removePrefix("v").removePrefix("V")
            if (tag.isEmpty() || !tag.first().isDigit()) continue
            out += Release(
                version = tag,
                url = json.optString("html_url").ifBlank { IssueReporter.appRepoUrl() },
                preRelease = json.optBoolean("prerelease", false),
            )
        }
        return out
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
