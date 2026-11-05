package cn.luoyi0914.ofmgateway

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import org.json.JSONObject
import java.io.File

/** 服务对外信息与系统开关：管理入口、API Key、开机自启。 */
object ServiceInfo {
    const val HOST = "127.0.0.1"
    const val DEFAULT_PORT = 15000

    /**
     * 端口唯一来源：优先读 Node 写下的 `standalonePort`。
     *
     * 这里刻意不缓存：用户可能在管理页改端口，首次启动前文件也还不存在。
     * 每次取值都从磁盘读，调用方负责放到 IO 线程。
     */
    fun apiPort(filesDir: String): Int = runCatching {
        val f = File(filesDir, "ofm-data/settings.json")
        if (!f.isFile) return@runCatching DEFAULT_PORT
        val p = JSONObject(f.readText()).optInt("standalonePort", 0)
        if (p in 1..65535) p else DEFAULT_PORT
    }.getOrDefault(DEFAULT_PORT)

    fun baseUrl(port: Int = DEFAULT_PORT): String = "http://$HOST:$port/"
    fun apiUrl(port: Int = DEFAULT_PORT): String = "http://$HOST:$port/v1"

    private val LOGIN_RE = Regex("""https?://[^\s"'<>]+#login=[A-Za-z0-9_\-]+""")

    /** 一次性登录令牌的落盘位置（原生层写入，权限 0600）。 */
    private fun bootstrapTokenFile(filesDir: String): File =
        File(filesDir, "ofm-runtime/bootstrap.token")

    /**
     * 读取一次性登录令牌。
     *
     * 日志里存的是脱敏后的 `#login=***`（日志要能安全截图/贴议题），
     * 所以真实令牌只能从原生层单独落盘的 0600 文件里取。
     */
    fun readBootstrapToken(filesDir: String): String? = runCatching {
        val f = bootstrapTokenFile(filesDir)
        if (!f.isFile) return@runCatching null
        f.readText().trim().ifBlank { null }
    }.getOrNull()

    /** 令牌签发时间（用于提示「已过期，需重启网关换新链接」）。 */
    fun bootstrapTokenAgeMs(filesDir: String): Long = runCatching {
        val f = bootstrapTokenFile(filesDir)
        if (!f.isFile) -1L else System.currentTimeMillis() - f.lastModified()
    }.getOrDefault(-1L)

    /**
     * 组装管理控制台登录链接。
     *
     * 优先用脱敏前的令牌文件；兼容旧版本日志（旧日志里还留着完整链接）。
     */
    fun loginUrl(filesDir: String, port: Int, logText: String = ""): String? {
        val token = readBootstrapToken(filesDir)
        if (token != null) return "${baseUrl(port)}#login=$token"
        return findLoginUrl(logText)
    }

    /** 从日志文本里提取登录链接（仅用于尚未脱敏的旧日志）。 */
    fun findLoginUrl(logText: String): String? =
        LOGIN_RE.findAll(logText).lastOrNull()?.value

    /** 抹掉一次性登录 token，避免复制/分享/提交到公开议题时泄露。 */
    fun scrubToken(text: String): String = TokenScrubber.scrub(text)

    fun readApiKey(filesDir: String): String? =
        readSettings(filesDir)?.optString("forwardKey")?.ifBlank { null }

    fun readManagementKey(filesDir: String): String? =
        readSettings(filesDir)?.optString("managementKey")?.ifBlank { null }

    private fun readSettings(filesDir: String): JSONObject? = runCatching {
        val f = File(filesDir, "ofm-data/settings.json")
        if (!f.isFile) return@runCatching null
        JSONObject(f.readText())
    }.getOrNull()

    private val VERSION_RE = Regex("""our-free-model-standalone\s+([0-9][\w.\-]*)""")
    fun findVersion(logText: String): String? = VERSION_RE.find(logText)?.groupValues?.getOrNull(1)

    fun mask(key: String): String =
        if (key.length <= 8) "••••••••" else key.take(4) + "••••••••" + key.takeLast(4)

    fun isBootAutoStart(context: Context): Boolean =
        context.packageManager.getComponentEnabledSetting(
            ComponentName(context, BootReceiver::class.java),
        ) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED

    fun setBootAutoStart(context: Context, enabled: Boolean) {
        context.packageManager.setComponentEnabledSetting(
            ComponentName(context, BootReceiver::class.java),
            if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP,
        )
    }
}

/**
 * 一次性登录令牌的脱敏规则。
 *
 * 单独成类有两个目的：能在 JVM 单元测试里直接跑（不依赖 Android framework），
 * 以及与原生层 `scrub_login_token` 保持同一套匹配口径。
 */
object TokenScrubber {
    private val TOKEN_RE = Regex("""#login=[A-Za-z0-9_\-]+""")

    fun scrub(text: String): String = TOKEN_RE.replace(text, "#login=***")
}
