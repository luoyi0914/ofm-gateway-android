package cn.luoyi0914.ofmgateway

/** How bad a state is, for colour decisions in the UI. */
enum class Severity { OK, NEUTRAL, WARN, ERROR }

/**
 * Single mapping point between the native status keys and everything the UI shows.
 *
 * The native layer never sends localised text, so adding a state here is the only
 * way a new state can reach the screen — and an unmapped key degrades to [UNKNOWN]
 * with an error tint instead of silently showing English to the user.
 */
enum class GatewayStatus(
    val key: String,
    val label: String,
    val running: Boolean,
    val usable: Boolean,
    val severity: Severity,
    val hint: String,
) {
    RUNNING("running", "运行中", true, true, Severity.OK, "网关正在本机提供服务"),
    READY("ready", "就绪（未启动）", false, true, Severity.NEUTRAL, "运行时已就绪，点启动开始服务"),
    RUNTIME_MISSING("runtime_missing", "运行时未安装", false, false, Severity.ERROR, "应用内置运行时缺失，请重新安装 App"),
    NODE_MISSING("node_missing", "缺少 Node 库", false, false, Severity.ERROR, "原生库不完整，请反馈此问题"),
    MAGPIE_MISSING("magpie_missing", "缺少 Magpie 库", false, false, Severity.ERROR, "引擎库不完整，请反馈此问题"),
    JNI_MISSING("jni_missing", "JNI 未加载", false, false, Severity.ERROR, "原生桥接加载失败，该设备可能不受支持"),
    INVALID_PATH("invalid_path", "运行时路径无效", false, false, Severity.ERROR, "应用数据目录异常，请重装应用"),
    INTERNAL_ERROR("internal_error", "原生层异常", false, false, Severity.ERROR, "原生层发生异常，已记录到日志"),
    UNKNOWN("unknown", "未知状态", false, false, Severity.WARN, "状态无法识别，请查看日志");

    companion object {
        fun fromKey(key: String?): GatewayStatus =
            entries.firstOrNull { it.key == key?.trim() } ?: UNKNOWN
    }
}
