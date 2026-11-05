package cn.luoyi0914.ofmgateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 一次性登录令牌脱敏规则的单元测试。
 *
 * 这条规则决定「用户点复制/提交议题时会不会把管理凭据贴到公网」，
 * 因此必须能在不跑真机的情况下被验证。
 */
class TokenScrubberTest {
    @Test
    fun redactsLoginTokenOnly() {
        val line = "open http://127.0.0.1:15000/#login=abcDEF123_- to manage"
        val scrubbed = TokenScrubber.scrub(line)
        assertEquals("open http://127.0.0.1:15000/#login=*** to manage", scrubbed)
    }

    @Test
    fun keepsSurroundingLines() {
        val text = "boot ok\nopen http://127.0.0.1:15000/#login=tok3n\nready"
        val scrubbed = TokenScrubber.scrub(text)
        assertTrue(scrubbed.contains("boot ok"))
        assertTrue(scrubbed.contains("ready"))
        assertFalse(scrubbed.contains("tok3n"))
    }

    @Test
    fun leavesUnrelatedTextUntouched() {
        val text = "listen 127.0.0.1:15000\nmodels loaded: 20"
        assertEquals(text, TokenScrubber.scrub(text))
    }

    @Test
    fun redactsEveryOccurrence() {
        val text = "#login=aaa\n#login=bbb"
        assertEquals("#login=***\n#login=***", TokenScrubber.scrub(text))
    }
}
