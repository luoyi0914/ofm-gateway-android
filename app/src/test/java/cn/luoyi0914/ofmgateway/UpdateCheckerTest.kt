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

    @Test
    fun releaseListSkipsRuntimeArtifactsAndDrafts() {
        // runtime-1 是运行时制品，草稿未发布；两者都不能出现在版本比较里。
        val body = """
            [
              {"tag_name":"runtime-1","draft":false,"prerelease":true,"html_url":"u1"},
              {"tag_name":"v0.9.0","draft":false,"prerelease":true,"html_url":"u2"},
              {"tag_name":"v0.10.1","draft":true,"prerelease":false,"html_url":"u3"},
              {"tag_name":"v0.8.3","draft":false,"prerelease":true,"html_url":"u4"}
            ]
        """.trimIndent()
        val versions = UpdateChecker.parseReleases(body).map { it.version }
        assertEquals(listOf("0.9.0", "0.8.3"), versions)
    }

    @Test
    fun picksHighestVersionFromList() {
        val body = """
            [
              {"tag_name":"v0.8.3","draft":false,"prerelease":true},
              {"tag_name":"v0.10.0","draft":false,"prerelease":true},
              {"tag_name":"v0.9.0","draft":false,"prerelease":true}
            ]
        """.trimIndent()
        val releases = UpdateChecker.parseReleases(body)
        val top = releases.maxByOrNull { UpdateChecker.parseVersion(it.version).fold(0) { a, n -> a * 1000 + n } }
        assertEquals("0.10.0", top?.version)
    }

    @Test
    fun emptyOrNonVersionReleasesYieldNothing() {
        assertTrue(UpdateChecker.parseReleases("[]").isEmpty())
        assertTrue(UpdateChecker.parseReleases("""[{"tag_name":"runtime-1"}]""").isEmpty())
    }
}
