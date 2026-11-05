package cn.luoyi0914.ofmgateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 版本比较。
 *
 * 只提示「有更新」这件事必须在版本号上可靠：`v` 前缀、`-dev` 后缀、
 * 段数不一致都要处理对，否则要么漏提示，要么一直提示同一个版本。
 */
class UpdateCheckerTest {
    @Test
    fun parsesVeePrefixedVersions() {
        assertEquals(listOf(0, 9, 0), UpdateChecker.parseVersion("v0.9.0"))
        assertEquals(listOf(0, 9, 0), UpdateChecker.parseVersion("0.9.0"))
    }

    @Test
    fun ignoresSuffixAfterDash() {
        assertEquals(listOf(0, 9, 0), UpdateChecker.parseVersion("0.9.0-dev"))
        assertEquals(listOf(1, 2, 3), UpdateChecker.parseVersion("v1.2.3-rc1"))
    }

    @Test
    fun comparesByNumericSegmentsNotStrings() {
        // 字符串比较会把 "0.10.0" 判成小于 "0.9.0"。
        assertTrue(UpdateChecker.isNewer("0.10.0", "0.9.0"))
        assertFalse(UpdateChecker.isNewer("0.9.0", "0.10.0"))
    }

    @Test
    fun equalVersionsAreNotNewer() {
        assertFalse(UpdateChecker.isNewer("0.9.0", "v0.9.0"))
        assertFalse(UpdateChecker.isNewer("1.0", "1.0.0"))
    }

    @Test
    fun missingSegmentsCountAsZero() {
        assertTrue(UpdateChecker.isNewer("1.0.1", "1.0"))
        assertFalse(UpdateChecker.isNewer("1.0", "1.0.0"))
    }

    @Test
    fun garbageNeverLooksNewer() {
        // 解析不出数字时宁可沉默，也不要在界面上谎报新版本。
        assertFalse(UpdateChecker.isNewer("latest", "0.9.0"))
    }
}
