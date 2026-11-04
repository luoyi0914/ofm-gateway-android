package cn.luoyi0914.ofmgateway

import java.net.URLEncoder

/**
 * 组装「提交议题到仓库」的预填链接。
 *
 * 采用无需令牌的方案：把标题、诊断信息与日志尾部 URL 编码后拼到 GitHub 的
 * 新建 issue 页面（`/issues/new?title=&body=`），由用户浏览器里的 GitHub 账号
 * 完成实际提交。应用本身不持有、不内置任何 GitHub 凭据。
 *
 * 说明（诚实边界）：GitHub 的新建议题 URL 只能通过正文传文本，无法真正上传
 * 附件；因此日志以文本形式进入正文并截断到安全长度。需要完整日志时，界面另
 * 提供「复制日志 / 分享日志」。
 */
object IssueReporter {
    /** 应用自身仓库：APK 相关问题默认提交到这里。 */
    const val REPO = "luoyi0914/ofm-gateway-android"
    /** 配套 Magisk / KernelSU 模块仓库：用于两条产品线互相引导。 */
    const val MODULE_REPO = "luoyi0914/ofm-gateway-magisk"
    private const val MAX_LOG_IN_BODY = 4000

    fun moduleRepoUrl(): String = "https://github.com/$MODULE_REPO"
    fun appRepoUrl(): String = "https://github.com/$REPO"

    fun buildIssueUrl(title: String, body: String): String {
        return "https://github.com/$REPO/issues/new" +
            "?title=" + URLEncoder.encode(title, "UTF-8") +
            "&body=" + URLEncoder.encode(body, "UTF-8")
    }

    /** 组装 issue 正文：诊断元信息 + 日志文本。 */
    fun composeBody(diagnostic: String, logText: String): String {
        // 公开议题：一次性 #login= 链接必须先脱敏，避免把管理凭据贴到公网。
        val scrubbed = ServiceInfo.scrubToken(logText)
        val trimmed = if (scrubbed.length <= MAX_LOG_IN_BODY) scrubbed
        else scrubbed.takeLast(MAX_LOG_IN_BODY)
        return buildString {
            appendLine("### 问题描述")
            appendLine("<!-- 请简要描述遇到的问题（启动失败 / 端口不通 / 性能异常等） -->")
            appendLine()
            appendLine("### 运行环境")
            appendLine("```")
            appendLine(diagnostic.trim())
            appendLine("```")
            appendLine()
            appendLine("### 运行日志（应用自动附带）")
            appendLine("```")
            appendLine(trimmed.trim())
            appendLine("```")
            appendLine()
            append("（以上为最近一段日志；如需要完整日志，请在应用中「复制日志」后补充。）")
        }
    }

    fun defaultTitle(): String = "OFM Gateway Android 问题反馈"
}
