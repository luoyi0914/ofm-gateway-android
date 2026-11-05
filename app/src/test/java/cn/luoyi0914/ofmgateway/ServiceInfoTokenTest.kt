package cn.luoyi0914.ofmgateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 一次性登录令牌的存取。
 *
 * 日志里写的是脱敏后的 `#login=***`，真实令牌必须单独落盘，
 * 否则「打开管理控制台」永远拿不到可用的登录链接。
 */
class ServiceInfoTokenTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun filesDir(): File = temp.newFolder("files")

    @Test
    fun returnsNullWhenTokenFileMissing() {
        assertNull(ServiceInfo.readBootstrapToken(filesDir().absolutePath))
    }

    @Test
    fun readsAndTrimsToken() {
        val dir = filesDir()
        val f = File(dir, "ofm-runtime/bootstrap.token")
        f.parentFile.mkdirs()
        f.writeText("tok3n_abc\n")
        assertEquals("tok3n_abc", ServiceInfo.readBootstrapToken(dir.absolutePath))
    }

    @Test
    fun blankTokenCountsAsMissing() {
        val dir = filesDir()
        val f = File(dir, "ofm-runtime/bootstrap.token")
        f.parentFile.mkdirs()
        f.writeText("   \n")
        assertNull(ServiceInfo.readBootstrapToken(dir.absolutePath))
    }

    @Test
    fun buildsLoginUrlFromTokenFile() {
        val dir = filesDir()
        val f = File(dir, "ofm-runtime/bootstrap.token")
        f.parentFile.mkdirs()
        f.writeText("abc123")
        assertEquals(
            "http://127.0.0.1:15000/#login=abc123",
            ServiceInfo.loginUrl(dir.absolutePath, 15000),
        )
    }

    @Test
    fun fallsBackToUnredactedLegacyLog() {
        val text = "open http://127.0.0.1:16000/#login=legacyTok\n"
        assertEquals(
            "http://127.0.0.1:16000/#login=legacyTok",
            ServiceInfo.loginUrl(filesDir().absolutePath, 15000, text),
        )
    }

    @Test
    fun ageIsNegativeWhenMissing() {
        assertTrue(ServiceInfo.bootstrapTokenAgeMs(filesDir().absolutePath) < 0)
    }
}
