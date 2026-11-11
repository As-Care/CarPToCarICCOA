package io.github.shaonialife.carptocar.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CarPlayResourceModesTest {
    private fun resource(id: Long, transfer: Long) = mapOf("resourceID" to id, "transferType" to transfer)

    @Test
    fun audiMediaSwitchGrantsAndReturnsScreenAndAudioIndependently() {
        val phone = CarPlayResourceModes()
        val screenTaken = phone.applyRequest(listOf(resource(1, 1)))
        assertEquals(
            listOf(
                mapOf("resourceID" to 1L, "entity" to 2L, "permanentEntity" to 2L),
                mapOf("resourceID" to 2L, "entity" to 1L, "permanentEntity" to 1L),
            ),
            screenTaken.report(),
        )
        assertFalse(screenTaken.mainAudio.carOwns)

        val bothTaken = screenTaken.applyRequest(listOf(resource(2, 1)))
        assertTrue(bothTaken.mainAudio.carOwns)
        assertEquals(2L, bothTaken.report()[1]["permanentEntity"])
        assertEquals(bothTaken, bothTaken.applyRequest(listOf(resource(2, 1))))

        val screenReturned = bothTaken.applyRequest(listOf(resource(1, 2)))
        assertFalse(screenReturned.mainScreen.carOwns)
        assertTrue(screenReturned.mainAudio.carOwns)
        assertEquals(phone, screenReturned.applyRequest(listOf(resource(2, 2))))
    }

    @Test
    fun unborrowRestoresThePermanentOwner() {
        val phone = CarPlayResourceModes()
        val borrowed = phone.applyRequest(listOf(resource(2, 3)))
        assertTrue(borrowed.mainAudio.carOwns)
        assertFalse(borrowed.mainScreen.carOwns)
        assertEquals(2L, borrowed.report()[1]["entity"])
        assertEquals(1L, borrowed.report()[1]["permanentEntity"])
        assertFalse(borrowed.applyRequest(listOf(resource(2, 4))).mainAudio.carOwns)

        val car = phone.applyRequest(listOf(resource(2, 1)))
        assertEquals(car, car.applyRequest(listOf(resource(2, 3), resource(2, 4))))
    }

    @Test
    fun screenBorrowKeepsThePermanentOwnerAndDoesNotPauseAudio() {
        val phone = CarPlayResourceModes()
        val borrowed = phone.applyRequest(listOf(resource(1, 3), resource(99, 1)))
        assertTrue(borrowed.mainScreen.carOwns)
        assertFalse(borrowed.mainAudio.carOwns)
        assertEquals(1L, borrowed.report()[0]["permanentEntity"])
        assertEquals(phone, borrowed.applyRequest(listOf(resource(1, 4))))
    }
}
