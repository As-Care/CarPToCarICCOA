package io.github.shaonialife.carptocar.bt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Iap2BluetoothRetryTest {
    @Test
    fun listenWindowAllowsDialingAtTheDeadline() {
        var now = 1_000L
        val deadline = now + 6_000L

        assertTrue(awaitIap2DialWindow(deadline, 250L, { now }, { now += it }) { true })
        assertEquals(deadline, now)
    }

    @Test
    fun inboundCarStopsTheWaitBeforeTheNextDial() {
        var now = 0L

        assertFalse(awaitIap2DialWindow(6_000L, 250L, { now }, { now += it }) { now < 4_000L })
        assertEquals(4_000L, now)
    }

    @Test
    fun cancelledConnectionCannotDialEvenAfterTheDeadline() {
        assertFalse(awaitIap2DialWindow(6_000L, 250L, { 6_000L }, { error("Cancelled wait") }) { false })
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
    fun audiReleasesProfilesAfterTheFirstFailureAndRetriesImmediately() {
        val car = ConnectionAttempts(null, "fallback")

        assertEquals("fallback", car.connect(retryBeforeDisconnect = false))
        assertEquals(listOf("connect", "disconnect profiles", "connect"), car.events)
    }

    @Test
    fun audiStillRetriesWhenNoProfilesAreConnected() {
        val car = ConnectionAttempts(null, "fallback")

        assertEquals("fallback", car.connect(retryBeforeDisconnect = false, profilesConnected = false))
        assertEquals(listOf("connect", "disconnect profiles", "connect"), car.events)
    }

    @Test
    fun audiSkipsProfileTeardownWhenInboundWinsDuringTheFirstDial() {
        val car = ConnectionAttempts(null)

        assertNull(car.connect(retryBeforeDisconnect = false, carReturnsAfterFirstDial = true))
        assertEquals(listOf("connect"), car.events)
    }

    private class ConnectionAttempts(vararg results: String?) {
        private val results = results.iterator()
        val events = mutableListOf<String>()

        fun connect(
            allowProfileFallback: Boolean = true,
            retryBeforeDisconnect: Boolean = true,
            profilesConnected: Boolean = true,
            carReturnsAfterFirstDial: Boolean = false,
            carReturnsDuringWait: Boolean = false,
            cancelAfterAttempts: Int = Int.MAX_VALUE,
        ): String? {
            var attempts = 0
            var inboundConnected = false
            return connectWithProfileFallback(
                allowProfileFallback = allowProfileFallback,
                retryBeforeDisconnect = retryBeforeDisconnect,
                canAttempt = { !inboundConnected && attempts < cancelAfterAttempts },
                connect = {
                    events += "connect"
                    attempts++
                    if (attempts == 1 && carReturnsAfterFirstDial) inboundConnected = true
                    results.next()
                },
                waitForCar = {
                    events += "wait for car"
                    inboundConnected = carReturnsDuringWait
                },
                disconnectProfiles = {
                    events += "disconnect profiles"
                    profilesConnected
                },
            )
        }
    }
}
