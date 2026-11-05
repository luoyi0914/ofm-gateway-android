package cn.luoyi0914.ofmgateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 原生层状态键到界面语义的映射：未知键必须降级成可诊断状态，不能静默当成正常。 */
class GatewayStatusTest {
    @Test
    fun mapsEveryKnownKey() {
        GatewayStatus.entries
            .filter { it != GatewayStatus.UNKNOWN }
            .forEach { assertEquals(it, GatewayStatus.fromKey(it.key)) }
    }

    @Test
    fun trimsWhitespaceAroundKey() {
        assertEquals(GatewayStatus.RUNNING, GatewayStatus.fromKey("  running\n"))
    }

    @Test
    fun unknownKeyDegradesToUnknown() {
        assertEquals(GatewayStatus.UNKNOWN, GatewayStatus.fromKey("something_new"))
        assertEquals(GatewayStatus.UNKNOWN, GatewayStatus.fromKey(null))
        assertEquals(GatewayStatus.UNKNOWN, GatewayStatus.fromKey(""))
    }

    @Test
    fun onlyRunningStateIsReportedAsRunning() {
        val running = GatewayStatus.entries.filter { it.running }
        assertEquals(listOf(GatewayStatus.RUNNING), running)
    }

    @Test
    fun actionableStatesAreUsable() {
        assertTrue(GatewayStatus.READY.usable)
        assertTrue(GatewayStatus.RUNNING.usable)
        assertFalse(GatewayStatus.RUNTIME_MISSING.usable)
        assertFalse(GatewayStatus.JNI_MISSING.usable)
    }

    @Test
    fun everyStateCarriesAHint() {
        GatewayStatus.entries.forEach { assertTrue(it.hint.isNotBlank()) }
    }
}
