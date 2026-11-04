package cn.luoyi0914.ofmgateway

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** 本地网关 API 客户端：读取可用模型列表（OpenAI 兼容 /v1/models）。 */
object ApiClient {
    fun fetchModels(
        baseUrl: String,
        apiKey: String? = null,
        timeoutMs: Int = 5000,
    ): Result<List<String>> = runCatching {
        val url = URL(baseUrl.trimEnd('/') + "/models")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            requestMethod = "GET"
            if (!apiKey.isNullOrBlank()) {
                setRequestProperty("Authorization", "Bearer $apiKey")
            }
        }
        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                throw IllegalStateException("HTTP $code：${body.take(180).ifBlank { "无响应体" }}")
            }
            val data = JSONObject(body).optJSONArray("data")
                ?: throw IllegalStateException("响应缺少 data 字段")
            buildList {
                for (i in 0 until data.length()) {
                    val id = data.optJSONObject(i)?.optString("id").orEmpty()
                    if (id.isNotBlank()) add(id)
                }
            }
        } finally {
            conn.disconnect()
        }
    }
}
