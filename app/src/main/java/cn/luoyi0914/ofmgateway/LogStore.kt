package cn.luoyi0914.ofmgateway

import java.io.File
import java.io.RandomAccessFile

/**
 * 按天分文件的网关日志存取。
 *
 * 原生 supervisor 把 Node 的 stdout/stderr 写入 `filesDir/ofm-runtime/logs/gateway-YYYY-MM-DD.log`。
 * 读取只取文件尾部（RandomAccessFile），避免长日志整文件读入造成 IO/内存压力。
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

    private fun tailFile(file: File, maxChars: Int): String {
        if (!file.isFile) return ""
        return runCatching {
            RandomAccessFile(file, "r").use { raf ->
                val len = raf.length()
                val maxBytes = maxChars.toLong() * 3
                val start = if (len > maxBytes) len - maxBytes else 0L
                raf.seek(start)
                val bytes = ByteArray((len - start).toInt())
                raf.readFully(bytes)
                val text = String(bytes, Charsets.UTF_8)
                if (start > 0) "…（仅显示末尾）\n" + text.takeLast(maxChars) else text
            }
        }.getOrElse { "" }
    }

    /** 读取某一天的日志（只读尾部）。 */
    fun readDay(filesDir: String, day: String, maxChars: Int = 60_000): String {
        val file = File(logsDir(filesDir), "gateway-$day.log")
        if (!file.isFile) return "（无 $day 的日志文件）"
        val text = tailFile(file, maxChars)
        return text.ifBlank { "（日志为空）" }
    }

    /**
     * 汇总用于复制/分享/提交议题的日志文本（默认取最新一天，也可指定某天）。
     * 已抹掉一次性登录 token，避免公开泄露。
     */
    fun reportLog(filesDir: String, day: String? = null, maxChars: Int = 6000): String {
        val target = day ?: latestDay(filesDir)
        if (target != null) {
            val text = tailFile(File(logsDir(filesDir), "gateway-$target.log"), maxChars)
            return "【$target】\n" + ServiceInfo.scrubToken(text).ifBlank { "（日志为空）" }
        }
        val legacy = legacyLog(filesDir)
        if (legacy.isFile) {
            return "【gateway.log（旧版单文件）】\n" + ServiceInfo.scrubToken(tailFile(legacy, maxChars)).ifBlank { "（日志为空）" }
        }
        return "（暂无日志：${logsDir(filesDir).absolutePath}）"
    }
}
