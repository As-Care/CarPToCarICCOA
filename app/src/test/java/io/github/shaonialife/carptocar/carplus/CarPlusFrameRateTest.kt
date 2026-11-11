package io.github.shaonialife.carptocar.carplus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class CarPlusFrameRateTest {
    @Test
    fun defaultsTo30EvenWhenCarSupports60() {
        assertEquals(30, CarPlusVideo.targetFps(highFpsRequested = false, carMaxFps = 60))
    }

    @Test
    fun uses60WhenSelectedAndCarAdvertisesSupport() {
        assertEquals(60, CarPlusVideo.targetFps(highFpsRequested = true, carMaxFps = 60))
    }

    @Test
    fun rechecksCurrentCarLimitEvenWithSaved60() {
        assertEquals(30, CarPlusVideo.targetFps(highFpsRequested = true, carMaxFps = 0))
        assertEquals(30, CarPlusVideo.targetFps(highFpsRequested = true, carMaxFps = 50))
        assertEquals(20, CarPlusVideo.targetFps(highFpsRequested = true, carMaxFps = 20))
    }

    @Test
    fun frameRateRequestEncodesMainDisplayFps() {
        assertArrayEquals(byteArrayOf(0x0a, 0x02, 0x08, 30), CarPlusVideo.frameRateRequestBody(30))
        assertArrayEquals(byteArrayOf(0x0a, 0x02, 0x08, 60), CarPlusVideo.frameRateRequestBody(60))
    }
}
