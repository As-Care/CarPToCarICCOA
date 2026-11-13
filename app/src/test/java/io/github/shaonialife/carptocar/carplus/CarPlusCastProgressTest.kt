package io.github.shaonialife.carptocar.carplus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CarPlusCastProgressTest {
    @Test
    fun reconnectWaitsForNewFrameAfterPreviousCast() {
        val cast = CarPlusVideo.CastProgress(connected = true, startedAt = 3_000, sourceFrameSeen = true)

        assertFalse(cast.hasForwardedFrame(2_999))
        assertTrue(cast.hasForwardedFrame(3_001))
        assertFalse(cast.copy(connected = false).hasForwardedFrame(3_001))
    }

    @Test
    fun waitingAndTimeoutMessagesDistinguishSourceFromForwarding() {
        val disconnected = CarPlusVideo.CastProgress()
        val waitingForSource = disconnected.copy(connected = true)
        val waitingForForwarding = waitingForSource.copy(sourceFrameSeen = true)

        assertEquals("等待手机 Car+ 连接", disconnected.waitingMessage(overdue = false))
        assertEquals("等待 Car+ 首帧", waitingForSource.waitingMessage(overdue = false))
        assertEquals("等待 Car+ 首帧超时", waitingForSource.waitingMessage(overdue = true))
        assertEquals("已收到 Car+ 画面，但未能发给车机", waitingForForwarding.waitingMessage(overdue = true))
    }
}
