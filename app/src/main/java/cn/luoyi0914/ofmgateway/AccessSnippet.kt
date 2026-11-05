package cn.luoyi0914.ofmgateway

/**
 * 「接入片段」：把第三方客户端要填的三样东西（接口地址、密钥、模型名）拼成可以直接粘贴的文本。
 *
 * 只做文案组装，不发任何请求，也不保存任何东西——密钥从内存里的 state 来，粘到剪贴板就结束。
 * 覆盖两个国内最常见的客户端：ChatBox（选 OpenAI 兼容）与 NextChat。
 * 不内置它们的截图或截图链接：那些会随客户端改版而过期，写在这里等于制造过期文档。
 */
object AccessSnippet {

    enum class Client(val label: String, val note: String) {
        CHATBOX(
            label = "ChatBox",
            note = "设置 → 模型提供方选「OpenAI 兼容」，按下表填三项。",
        ),
        NEXTCHAT(
            label = "NextChat",
            note = "设置 → 自定义接口，打开「接口地址」与「API Key」的自定义项，按下表填。",
        ),
    }

    /** 补齐成 `/v1` 结尾的接口地址；已经是 `/v1` 时原样返回，避免出现 `/v1/v1`。 */
    fun apiBase(baseUrl: String): String {
        val url = baseUrl.trim().trimEnd('/')
        return if (url.endsWith("/v1")) url else "$url/v1"
    }

    /**
     * 生成片段。
     *
     * @param model 推荐模型名；未知时传 null，片段里会明确写「先去模型页选一个」，
     *              而不是编一个可能不存在的模型名——填错的模型名比空着更难排查。
     */
    fun build(client: Client, baseUrl: String, apiKey: String, model: String?): String {
        val key = apiKey.ifBlank { "（首页「凭据」卡片里查看）" }
        val modelLine = model?.takeIf { it.isNotBlank() } ?: "（在「模型」页选一个可用模型）"
        return buildString {
            appendLine("【${client.label}】")
            appendLine(client.note)
            appendLine()
            appendLine("接口地址 / Base URL：${apiBase(baseUrl)}")
            appendLine("API Key：$key")
            appendLine("模型名：$modelLine")
            appendLine()
            appendLine("说明：网关只监听本机 127.0.0.1，客户端必须装在同一台手机上；")
            appendLine("用电脑或其他设备连不上，这是设计如此，不是配置错误。")
        }.trimEnd()
    }

    /** 复制用的极简清单：只留三项，方便逐项对照着填。 */
    fun compact(baseUrl: String, apiKey: String, model: String?): String = listOf(
        "Base URL: ${apiBase(baseUrl)}",
        "API Key: ${apiKey.ifBlank { "（未获取到）" }}",
        "Model: ${model?.takeIf { it.isNotBlank() } ?: "（未选择）"}",
    ).joinToString("\n")
}
