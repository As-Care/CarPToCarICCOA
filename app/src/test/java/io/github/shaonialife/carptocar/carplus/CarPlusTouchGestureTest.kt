package io.github.shaonialife.carptocar.carplus

import android.view.MotionEvent
import io.github.shaonialife.carptocar.input.TouchReports
import io.github.shaonialife.carptocar.input.TouchReports.Contact
import org.junit.Assert.assertEquals
import org.junit.Test

class CarPlusTouchGestureTest {
    private data class Event(val action: Int, val contacts: List<Contact>)

    private val events = mutableListOf<Event>()
    private val gesture = CarPlusTouchGesture { action, contacts ->
        events += Event(action, contacts.toList())
        true
    }
    private val viewport = CarPlusVideo.Viewport(0, 0, CarPlusVideo.WIDTH, CarPlusVideo.HEIGHT)

    @Test
    fun twoSlotReportsPreservePointerIdsWhenFirstFingerLifts() {
        fun report(firstDown: Boolean, secondDown: Boolean, firstX: Int, secondX: Int) {
            val data = byteArrayOf(
                0, if (firstDown) 1 else 0, firstX.toByte(), 0, 30, 0,
                1, if (secondDown) 1 else 0, secondX.toByte(), 0, 40, 0,
            )
            gesture.update(TouchReports.parseMultiSlot(data), viewport)
        }

        report(true, false, 60, 0)
        report(true, true, 60, 90)
        report(true, true, 50, 100)
        report(false, true, 50, 100)
        report(false, false, 50, 110)

        assertEquals(
            listOf(
                MotionEvent.ACTION_DOWN,
                MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                MotionEvent.ACTION_MOVE,
                MotionEvent.ACTION_POINTER_UP,
                MotionEvent.ACTION_UP,
            ),
            events.map { it.action },
        )
        assertEquals(listOf(listOf(0), listOf(0, 1), listOf(0, 1), listOf(0, 1), listOf(1)),
            events.map { event -> event.contacts.map { it.slot } })
        assertEquals(listOf(50, 100), events[2].contacts.map { it.xPixels })
    }

    @Test
    fun singleTouchIgnoresBarsAndKeepsLastPositionOnZeroedRelease() {
        val letterboxed = viewport.copy(x = 100)
        fun report(down: Boolean, x: Int, y: Int) {
            val data = byteArrayOf(if (down) 1 else 0, x.toByte(), 0, y.toByte(), 0)
            gesture.update(TouchReports.parseSingleTouch(data), letterboxed)
        }
        report(true, 40, 20)
        report(false, 0, 0)
        report(true, 130, 30)
        report(true, 155, 40)
        report(false, 0, 0)
        assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP), events.map { it.action })
        assertEquals(55, events.last().contacts.single().xPixels)
        assertEquals(40, events.last().contacts.single().yPixels)
    }

    @Test
    fun simultaneousReleaseSendsIndexedPointerUpBeforeFinalUp() {
        gesture.update(listOf(Contact(0, 50, 50, true), Contact(1, 60, 60, true)), viewport)
        gesture.update(listOf(Contact(0, 0, 0, false), Contact(1, 0, 0, false)), viewport)

        assertEquals(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), events[2].action)
        assertEquals(listOf(50, 60), events[2].contacts.map { it.xPixels })
        assertEquals(MotionEvent.ACTION_UP, events[3].action)
    }

    @Test
    fun cancelReleasesBothPointersAndStartsTheNextGestureFresh() {
        val both = listOf(Contact(0, 50, 50, true), Contact(1, 60, 60, true))
        gesture.update(both, viewport)
        gesture.cancel()
        gesture.update(listOf(both.last()), viewport)

        assertEquals(MotionEvent.ACTION_CANCEL, events[2].action)
        assertEquals(2, events[2].contacts.size)
        assertEquals(MotionEvent.ACTION_DOWN, events[3].action)
        assertEquals(1, events[3].contacts.single().slot)
    }
}
