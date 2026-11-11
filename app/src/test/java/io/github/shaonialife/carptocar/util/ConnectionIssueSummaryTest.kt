package io.github.shaonialife.carptocar.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionIssueSummaryTest {
    @Test
    fun keepsLatestFailureAndRecentAnomaliesWhileHidingIdentifiersAndCredentials() {
        val logs = """
            [09:00:00.000] STAGE: ✗ 本轮未连上，卡在「蓝牙握手」
            [09:00:01.000] [ERROR] 最早的错误
            [09:00:02.000] [WARN] 视频等待超时
            [09:00:03.000] [ERROR] 连接 192.168.2.3 失败
            [09:00:04.000] [ERROR] 车机 fe80::1234%wlan0 拒绝连接
            [09:00:05.000] [ERROR] password=two words
            [09:00:06.000] RAW [ERROR] 原始报文
            [09:00:07.000] STAGE: ✗ 本轮未连上，卡在「首帧」
        """.trimIndent()
        val result = ConnectionIssueSummary.format(
            "CarPToCar+ICCOA", "1.2.51", "Audi 00:11:22:33:44:55", "未连接", logs,
        )

        assertTrue(result.contains("CarPToCar+ICCOA 1.2.51"))
        assertTrue(result.contains("目标车机 · Audi 〈蓝牙地址〉"))
        assertTrue(result.contains("当前状态 · 未连接"))
        assertTrue(result.contains("最近失败阶段 · 首帧"))
        assertTrue(result.contains("[09:00:02.000] [WARN] 视频等待超时"))
        assertTrue(result.contains("[ERROR] 连接 〈IP〉 失败"))
        assertTrue(result.contains("[ERROR] 车机 〈IP〉 拒绝连接"))
        assertTrue(result.contains("[ERROR] 〈已隐藏〉"))
        for (omitted in listOf("蓝牙握手", "最早的错误", "原始报文", "192.168.2.3", "fe80::1234", "two words")) {
            assertFalse(result.contains(omitted))
        }
    }
}
