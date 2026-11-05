package cn.luoyi0914.ofmgateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 日志级别过滤：界面上的「错误/警告」筛选必须只靠这里，不能各页各写一套。 */
class LogFilterTest {
    private val sample = """
        our-free-model-standalone 2.7.1
        listening on 127.0.0.1:15000
        model listing refresh failed: timeout
        WARN deprecated option ignored
        ready
    """.trimIndent()

    @Test
    fun allLevelReturnsInput() {
        assertEquals(sample, LogStore.filterLines(sample, LogStore.Level.ALL))
    }

    @Test
    fun errorLevelKeepsOnlyErrorLines() {
        val filtered = LogStore.filterLines(sample, LogStore.Level.ERROR)
        assertTrue(filtered.contains("model listing refresh failed"))
        assertTrue(!filtered.contains("listening on"))
    }

    @Test
    fun warnLevelKeepsWarnLines() {
        val filtered = LogStore.filterLines(sample, LogStore.Level.WARN)
        assertTrue(filtered.contains("WARN deprecated"))
        assertTrue(!filtered.contains("ready"))
    }

    @Test
    fun emptyResultExplainsItself() {
        val filtered = LogStore.filterLines("ready\nlistening", LogStore.Level.ERROR)
        assertTrue(filtered.contains("没有匹配"))
    }

    @Test
    fun blankLinesAreDropped() {
        val filtered = LogStore.filterLines("a error\n\n\nb error", LogStore.Level.ERROR)
        assertEquals(2, filtered.lines().size)
    }
}
