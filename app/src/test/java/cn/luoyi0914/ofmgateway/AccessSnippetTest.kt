package cn.luoyi0914.ofmgateway

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 接入片段。
 *
 * 这类文案最容易出的两个错：`/v1` 被拼两次（客户端连不上），以及模型名缺失时
 * 编一个不存在的名字（用户照着填，报错信息与本应用无关，根本查不到原因）。
 */
class AccessSnippetTest {

    @Test
    fun appendsV1WhenMissing() {
        assertTrue(AccessSnippet.apiBase("http://127.0.0.1:15000/").endsWith("/v1"))
        assertTrue(AccessSnippet.apiBase("http://127.0.0.1:15000").endsWith("/v1"))
    }

    @Test
    fun doesNotDoubleV1() {
        val url = AccessSnippet.apiBase("http://127.0.0.1:15000/v1")
        assertFalse(url.endsWith("/v1/v1"))
        assertTrue(url.endsWith("/v1"))
        // 大写或带斜杠的口径也不该被再拼一次
        assertTrue(AccessSnippet.apiBase("http://127.0.0.1:15000/v1/").endsWith("/v1"))
    }

    @Test
    fun includesKeyAndModelWhenKnown() {
        val text = AccessSnippet.build(
            AccessSnippet.Client.CHATBOX,
            "http://127.0.0.1:15000/",
            "sk-test",
            "gpt-4o-mini",
        )
        assertTrue(text.contains("http://127.0.0.1:15000/v1"))
        assertTrue(text.contains("sk-test"))
        assertTrue(text.contains("gpt-4o-mini"))
        assertTrue(text.contains("ChatBox"))
    }

    @Test
    fun saysToPickModelInsteadOfInventingOne() {
        val text = AccessSnippet.build(
            AccessSnippet.Client.NEXTCHAT,
            "http://127.0.0.1:15000/",
            "sk-test",
            null,
        )
        assertTrue(text.contains("在「模型」页选一个可用模型"))
        // 空白模型名与 null 同义，不能当成模型名写进去。
        val blank = AccessSnippet.build(AccessSnippet.Client.NEXTCHAT, "http://127.0.0.1:15000/", "sk-test", "   ")
        assertTrue(blank.contains("在「模型」页选一个可用模型"))
    }

    @Test
    fun mentionsSameDeviceConstraint() {
        val text = AccessSnippet.build(AccessSnippet.Client.CHATBOX, "http://127.0.0.1:15000/", "k", "m")
        assertTrue(text.contains("同一台手机"))
    }

    @Test
    fun compactListHasThreeLinesAndNoSecrets() {
        val list = AccessSnippet.compact("http://127.0.0.1:15000/", "", null)
        val lines = list.lines()
        assertTrue(lines.size == 3)
        assertTrue(lines[0].startsWith("Base URL: "))
        assertTrue(lines[1].contains("未获取到"))
        assertTrue(lines[2].contains("未选择"))
    }
}
