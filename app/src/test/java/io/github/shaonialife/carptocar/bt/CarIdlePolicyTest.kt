package io.github.shaonialife.carptocar.bt

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CarIdlePolicyTest {
    private fun reconnectOnOpen(
        action: String = Intent.ACTION_MAIN,
        flags: Int = Intent.FLAG_ACTIVITY_NEW_TASK,
        restoringActivity: Boolean = false,
        autoConnectEnabled: Boolean = true,
    ) = CarIdlePolicy.shouldReconnectAfterLauncherOpen(
        action, setOf(Intent.CATEGORY_LAUNCHER), flags, restoringActivity, autoConnectEnabled,
    )

    @Test
    fun `离车后从桌面重新启动可发起连接`() {
        assertTrue(reconnectOnOpen())
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
        aclConnected: Boolean = false,
        disconnectedForMillis: Long = CarIdlePolicy.IDLE_TIMEOUT_MILLIS,
        streaming: Boolean = false,
        uiVisible: Boolean = false,
    ) = CarIdlePolicy.onIdleTimeout(
        aclConnected = aclConnected,
        disconnectedForMillis = disconnectedForMillis,
        streaming = streaming,
        uiVisible = uiVisible,
    )

    @Test
    fun `链路断开且无画面无界面时收摊`() {
        assertEquals(CarIdlePolicy.Verdict.STOP_PROCESS, verdict())
    }

    @Test
    fun `30 秒内车机回连则保留会话`() {
        assertEquals(
            CarIdlePolicy.Verdict.STILL_CONNECTED,
            verdict(aclConnected = true, disconnectedForMillis = 29_999L),
        )
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
    fun `链路优先于推流与界面判定`() {
        // 回连后画面多半也在重建：只要链路在，结论就不该是「已断开」，
        // 否则会误判为可以收摊。
        assertEquals(
            CarIdlePolicy.Verdict.STILL_CONNECTED,
            verdict(aclConnected = true, streaming = true, uiVisible = true),
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
