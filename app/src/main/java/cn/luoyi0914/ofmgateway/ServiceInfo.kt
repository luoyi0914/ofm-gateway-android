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

    /** 优先读取 Node 实际写入的监听端口（settings.standalonePort），读不到回退 15000。 */
    fun apiPort(filesDir: String): Int = runCatching {
        val f = File(filesDir, "ofm-data/settings.json")
        if (!f.isFile) return@runCatching DEFAULT_PORT
        val p = JSONObject(f.readText()).optInt("standalonePort", 0)
        if (p in 1..65535) p else DEFAULT_PORT
    }.getOrDefault(DEFAULT_PORT)

    fun baseUrl(port: Int = DEFAULT_PORT): String = "http://$HOST:$port/"
    fun apiUrl(port: Int = DEFAULT_PORT): String = "http://$HOST:$port/v1"

    private val LOGIN_RE = Regex("""https?://[^\s"'<>]+#login=[A-Za-z0-9_\-]+""")
    private val TOKEN_RE = Regex("""#login=[A-Za-z0-9_\-]+""")

    /** 从日志里取最近一次「一次性登录」链接（该链接约 10 分钟有效）。 */
    fun findLoginUrl(logText: String): String? =
        LOGIN_RE.findAll(logText).lastOrNull()?.value

    /** 抹掉一次性登录 token，避免复制/分享/提交到公开议题时泄露。 */
    fun scrubToken(text: String): String = TOKEN_RE.replace(text, "#login=***")

    fun readApiKey(filesDir: String): String? = runCatching {
        val f = File(filesDir, "ofm-data/settings.json")
        if (!f.isFile) return@runCatching null
        JSONObject(f.readText()).optString("forwardKey").ifBlank { null }
    }.getOrNull()

    fun readManagementKey(filesDir: String): String? = runCatching {
        val f = File(filesDir, "ofm-data/settings.json")
        if (!f.isFile) return@runCatching null
        JSONObject(f.readText()).optString("managementKey").ifBlank { null }
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

    fun statusLabel(raw: String): String = when (raw) {
        "gateway running" -> "运行中"
        "runtime ready" -> "就绪（未启动）"
        "runtime app not installed" -> "运行时未安装"
        "Node library missing" -> "缺少 Node 库"
        "Magpie library missing" -> "缺少 Magpie 库"
        "invalid runtime path" -> "运行时路径无效"
        "Rust JNI library missing" -> "JNI 未加载"
        else -> raw
    }
}
