package cn.luoyi0914.ofmgateway

import java.io.File
import java.io.RandomAccessFile

/**
 * 按天分文件的网关日志存取。
 *
 * 原生 supervisor 把 Node 的 stdout/stderr 写入
 * `filesDir/ofm-runtime/logs/gateway-YYYY-MM-DD.log`。
 *
 * 读取只取文件尾部（RandomAccessFile），避免长日志整文件读入造成 IO/内存压力。
 * 所有截断都对齐到行边界：从字节中间切会把 UTF-8 多字节字符劈成乱码，
 * 也会把一行诊断信息从中间截断，读起来像另一条日志。
 */
object LogStore {
    private val DAY_PATTERN = Regex("""gateway-(\d{4}-\d{2}-\d{2})\.log""")

    fun logsDir(filesDir: String): File = File(filesDir, "ofm-runtime/logs")
    fun legacyLog(filesDir: String): File = File(filesDir, "ofm-runtime/gateway.log")

    fun listDays(filesDir: String): List<String> =
        runCatching {
            logsDir(filesDir).listFiles()
                ?.mapNotNull { DAY_PATTERN.matchEntire(it.name)?.groupValues?.get(1) }
                ?.sortedDescending()
                ?: emptyList()
        }.getOrElse { emptyList() }

    fun latestDay(filesDir: String): String? = listDays(filesDir).firstOrNull()

    fun dayFile(filesDir: String, day: String): File = File(logsDir(filesDir), "gateway-$day.log")

    /**
     * 读文件尾部最多 [maxChars] 个字符。
     *
     * 先按 `maxChars * 4` 字节留余量（UTF-8 单字符最多 4 字节），解码后丢掉可能被劈开的首行残片，
     * 再取末尾 `maxChars`。这样既不会出现乱码，也不会出现「半条日志」。
     */
    private fun tailFile(file: File, maxChars: Int): String {
        if (!file.isFile) return ""
        return runCatching {
            RandomAccessFile(file, "r").use { raf ->
                val len = raf.length()
                if (len == 0L) return@use ""
                val maxBytes = maxChars.toLong() * 4
                val start = if (len > maxBytes) len - maxBytes else 0L
                raf.seek(start)
                val bytes = ByteArray((len - start).toInt())
                raf.readFully(bytes)
                var text = String(bytes, Charsets.UTF_8)
                if (start > 0) {
                    // 起点落在行中间：丢掉这半行，避免出现半条日志。
                    val nl = text.indexOf('\n')
                    text = if (nl >= 0) text.substring(nl + 1) else text
                }
                if (text.length > maxChars) text = text.takeLast(maxChars)
                text
            }
        }.getOrElse { "" }
    }

    /** 读取某一天的日志（只读尾部，默认 6000 字符，与复制/分享口径一致）。 */
    fun readDay(filesDir: String, day: String, maxChars: Int = 6_000): String {
        val file = dayFile(filesDir, day)
        if (!file.isFile) return "（无 $day 的日志文件）"
        return tailFile(file, maxChars).ifBlank { "（日志为空）" }
    }

    /**
     * 汇总用于复制/分享/提交议题的日志文本（默认取最新一天，也可指定某天）。
     * 已抹掉一次性登录 token，避免公开泄露。
     */
    fun reportLog(filesDir: String, day: String? = null, maxChars: Int = 6_000): String {
        val target = day ?: latestDay(filesDir)
        if (target != null) {
            val text = tailFile(dayFile(filesDir, target), maxChars)
            return "【$target】\n" + ServiceInfo.scrubToken(text).ifBlank { "（日志为空）" }
        }
        val legacy = legacyLog(filesDir)
        if (legacy.isFile) {
            return "【gateway.log（旧版单文件）】\n" +
                ServiceInfo.scrubToken(tailFile(legacy, maxChars)).ifBlank { "（日志为空）" }
        }
        return "（暂无日志：${logsDir(filesDir).absolutePath}）"
    }

    /** 日志级别，用于界面过滤；[ALL] 不筛。 */
    enum class Level(val label: String, private val needles: List<String>) {
        ALL("全部", emptyList()),
        ERROR(
            "错误",
            listOf(
                "error", "Error", "ERROR", "失败", "Cannot", "cannot", "uncaught", "FATAL",
                // Node 的失败路径常写成 "failed" / "Failure"，只匹配 "error" 会漏掉一半真实故障。
                "fail", "Fail", "FAIL", "panic",
            ),
        ),
        WARN("警告", listOf("warn", "Warn", "WARN", "deprecated", "retry")),
        ;

        fun matches(line: String): Boolean =
            this == ALL || needles.any { line.contains(it) }
    }

    /** 按级别过滤多行文本；[Level.ALL] 原样返回。 */
    fun filterLines(text: String, level: Level): String {
        if (level == Level.ALL) return text
        return text.lineSequence()
            .filter { it.isNotBlank() && level.matches(it) }
            .joinToString("\n")
            .ifBlank { "（当日没有匹配「${level.label}」的日志）" }
    }
}
