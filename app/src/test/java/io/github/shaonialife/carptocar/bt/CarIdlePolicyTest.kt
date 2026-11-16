package io.github.shaonialife.carptocar.bt

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CarIdlePolicyTest {
    private fun reconnectOnOpen(
        action: String? = Intent.ACTION_MAIN,
        categories: Set<String>? = setOf(Intent.CATEGORY_LAUNCHER),
        flags: Int = Intent.FLAG_ACTIVITY_NEW_TASK,
        restoringActivity: Boolean = false,
        autoConnectEnabled: Boolean = true,
    ) = CarIdlePolicy.shouldReconnectAfterAppLaunch(
        action, categories, flags, restoringActivity, autoConnectEnabled,
    )

    @Test
    fun `离车后从桌面重新启动可发起连接`() {
        assertTrue(reconnectOnOpen())
    }

    @Test
    fun `离车后外部直接打开主界面也可发起连接`() {
        assertTrue(reconnectOnOpen(action = null, categories = null))
        assertFalse(reconnectOnOpen(action = null, categories = null, autoConnectEnabled = false))
        assertFalse(reconnectOnOpen(action = null, categories = null, flags = Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY))
    }

    @Test
    fun `恢复离车前的任务仍保留离线状态`() {
        assertFalse(reconnectOnOpen(restoringActivity = true))
        assertFalse(reconnectOnOpen(flags = Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY))
    }

    @Test
    fun `桌面启动遵从自动连接开关`() {
        assertFalse(reconnectOnOpen(autoConnectEnabled = false))
        assertFalse(reconnectOnOpen(action = Intent.ACTION_VIEW))
    }

    private fun verdict(
        disconnectedForMillis: Long = CarIdlePolicy.IDLE_TIMEOUT_MILLIS,
        streaming: Boolean = false,
        uiVisible: Boolean = false,
    ) = CarIdlePolicy.onIdleTimeout(
        disconnectedForMillis = disconnectedForMillis,
        streaming = streaming,
        uiVisible = uiVisible,
    )

    @Test
    fun `链路断开且无画面无界面时收摊`() {
        assertEquals(CarIdlePolicy.Verdict.STOP_PROCESS, verdict())
    }

    @Test
    fun `画面仍在推送时不退出进程`() {
        assertEquals(CarIdlePolicy.Verdict.STREAMING, verdict(streaming = true))
    }

    @Test
    fun `用户正看着界面时结束会话并保留界面`() {
        assertEquals(CarIdlePolicy.Verdict.STOP_SESSION, verdict(uiVisible = true))
    }

    @Test
    fun `画面提前结束也要等满 30 秒`() {
        assertEquals(CarIdlePolicy.Verdict.WAITING, verdict(disconnectedForMillis = 29_999L))
        assertEquals(
            CarIdlePolicy.Verdict.STOP_PROCESS,
            verdict(disconnectedForMillis = 30_000L),
        )
    }

    @Test
    fun `推流优先于界面可见`() {
        // 画面在送、用户又开着界面：结论落在「别杀」这一侧，具体哪种都不重要。
        assertEquals(
            CarIdlePolicy.Verdict.STREAMING,
            verdict(streaming = true, uiVisible = true),
        )
    }

    @Test
    fun `计时窗口是 30 秒`() {
        assertEquals(30_000L, CarIdlePolicy.IDLE_TIMEOUT_MILLIS)
    }
}
