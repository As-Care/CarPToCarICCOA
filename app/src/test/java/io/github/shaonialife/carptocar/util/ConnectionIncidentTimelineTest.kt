package io.github.shaonialife.carptocar.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionIncidentTimelineTest {
    @Test
    fun keepsFirstLossBetweenScreenHandoverAndRecoveryDespiteRepeatedTransportNoise() {
        val timeline = ConnectionIncidentTimeline()
        fun record(at: Long, message: String) = timeline.record(message, at, at)

        assertNull(record(1_000, "ICCOA: CarPlay 视频通道已就绪"))
        assertNull(record(10_000, "EVENT: changeModes reason=user action"))
        assertNull(record(30_000, "AUDIO: UDP 发送失败 - 10.0.0.1"))
        val loss = requireNotNull(record(35_000, "${ConnectionIncidentTimeline.TRANSPORT_LOSS} · 控制通道结束"))
        assertTrue(loss.contains("reason=user action"))
        assertTrue(loss.contains("AUDIO: UDP 发送失败 - 〈IP〉"))

        repeat(48) { assertNull(record(36_000L + it, "IAPLINK: 控制通道 POST 失败 - Broken pipe")) }
        record(45_000, "${ConnectionIncidentTimeline.TRANSPORT_LOSS} · 视频通道结束")
        val recovery = requireNotNull(record(50_000, "AIRPLAY: ✓ RECORD 完成——会话已激活"))
        assertTrue(recovery.contains(loss))
        assertTrue(recovery.endsWith("AIRPLAY: ✓ RECORD 完成——会话已激活"))
        assertFalse(recovery.contains("Broken pipe"))
    }

    @Test
    fun startsANewTimelineForASeparateLossAfterTheRecoveryWindow() {
        val timeline = ConnectionIncidentTimeline()
        val loss = ConnectionIncidentTimeline.TRANSPORT_LOSS
        timeline.record("$loss · 视频通道结束", 100, 100)
        assertNull(timeline.record("EVENT: 命令 type=requestUI", 70_000, 70_000))
        val next = requireNotNull(timeline.record("$loss · 控制通道结束", 75_000, 75_000))
        assertTrue(next.contains("type=requestUI"))
        assertTrue(next.contains("控制通道结束"))
        assertFalse(next.contains("视频通道结束"))
    }
}
