package io.github.shaonialife.carptocar.bt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Iap2BluetoothRetryTest {
    @Test
    fun initialListenWindowAllowsDialingAtTheDeadline() {
        var now = 1_000L
        val deadline = now + 12_000L

        assertTrue(awaitIap2DialWindow(deadline, 250L, { now }, { now += it }) { true })
        assertEquals(deadline, now)
    }

    @Test
    fun inboundCarStopsTheInitialWaitBeforeAnyDial() {
        var now = 0L

        assertFalse(awaitIap2DialWindow(12_000L, 250L, { now }, { now += it }) { now < 9_000L })
        assertEquals(9_000L, now)
    }

    @Test
    fun cancelledConnectionCannotDialEvenAfterTheDeadline() {
        assertFalse(awaitIap2DialWindow(12_000L, 250L, { 12_000L }, { error("Cancelled wait") }) { false })
    }

    @Test
    fun firstSuccessSkipsTheWaitAndProfileTeardown() {
        val car = ConnectionAttempts("first")

        assertEquals("first", car.connect())
        assertEquals(listOf("connect"), car.events)
    }

    @Test
    fun secondSuccessKeepsTheBluetoothProfiles() {
        val car = ConnectionAttempts(null, "second")

        assertEquals("second", car.connect())
        assertEquals(listOf("connect", "wait for car", "connect"), car.events)
    }

    @Test
    fun profileTeardownRequiresTwoDirectFailures() {
        val car = ConnectionAttempts(null, null, "fallback")

        assertEquals("fallback", car.connect())
        assertEquals(listOf("connect", "wait for car", "connect", "disconnect profiles", "connect"), car.events)
    }

    @Test
    fun inboundConnectionDuringTheWaitTakesOver() {
        val car = ConnectionAttempts(null)

        assertNull(car.connect(carReturnsDuringWait = true))
        assertEquals(listOf("connect", "wait for car"), car.events)
    }

    @Test
    fun cancellationDuringTheSecondAttemptSkipsProfileTeardown() {
        val car = ConnectionAttempts(null, null)

        assertNull(car.connect(cancelAfterAttempts = 2))
        assertEquals(listOf("connect", "wait for car", "connect"), car.events)
    }

    @Test
    fun unknownAudioDeviceOnlyGetsOneProbe() {
        val car = ConnectionAttempts(null)

        assertNull(car.connect(allowProfileFallback = false))
        assertEquals(listOf("connect"), car.events)
    }

    @Test
    fun profilePreservingModeOnlyDialsOnceAfterTheInitialWait() {
        val car = ConnectionAttempts(null, "fallback")
        var now = 0L

        if (awaitIap2DialWindow(12_000L, 250L, { now }, { now += it }) { true }) {
            assertNull(car.connect(allowProfileFallback = false))
        }
        assertEquals(listOf("connect"), car.events)
    }

    private class ConnectionAttempts(vararg results: String?) {
        private val results = results.iterator()
        val events = mutableListOf<String>()

        fun connect(
            allowProfileFallback: Boolean = true,
            carReturnsDuringWait: Boolean = false,
            cancelAfterAttempts: Int = Int.MAX_VALUE,
        ): String? {
            var attempts = 0
            var inboundConnected = false
            return connectWithProfileFallback(
                allowProfileFallback = allowProfileFallback,
                canAttempt = { !inboundConnected && attempts < cancelAfterAttempts },
                connect = {
                    events += "connect"
                    attempts++
                    results.next()
                },
                waitForCar = {
                    events += "wait for car"
                    inboundConnected = carReturnsDuringWait
                },
                disconnectProfiles = {
                    events += "disconnect profiles"
                    true
                },
            )
        }
    }
}
