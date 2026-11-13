package io.github.shaonialife.carptocar.carplus

import android.view.MotionEvent
import io.github.shaonialife.carptocar.input.TouchReports.Contact

/** Translates a full HID contact report into ordered Android-style UIBC events. */
internal class CarPlusTouchGesture(
    private val send: (action: Int, pointers: List<Contact>) -> Boolean,
) {
    private val active = mutableListOf<Contact>()

    fun update(contacts: List<Contact>, viewport: CarPlusVideo.Viewport) {
        if (viewport.width <= 0 || viewport.height <= 0) return

        for (index in active.indices) {
            val contact = contacts.firstOrNull { it.slot == active[index].slot } ?: continue
            // Some head units zero both coordinates in the release report.
            if (contact.down || contact.xPixels != 0 || contact.yPixels != 0) {
                active[index] = map(contact, viewport)
            }
        }

        var changed = false
        for (index in active.indices.reversed()) {
            if (contacts.any { it.slot == active[index].slot && it.down }) continue
            val action = if (active.size == 1) {
                MotionEvent.ACTION_UP
            } else {
                MotionEvent.ACTION_POINTER_UP or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
            }
            if (!send(action, active)) {
                cancel()
                return
            }
            active.removeAt(index)
            changed = true
        }

        for (contact in contacts) {
            if (!contact.down || active.any { it.slot == contact.slot }) continue
            if (contact.xPixels - viewport.x !in 0 until viewport.width ||
                contact.yPixels - viewport.y !in 0 until viewport.height
            ) continue
            val index = active.size
            val action = if (index == 0) {
                MotionEvent.ACTION_DOWN
            } else {
                MotionEvent.ACTION_POINTER_DOWN or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
            }
            active += map(contact, viewport)
            if (!send(action, active)) active.removeAt(index)
            changed = true
        }

        if (!changed && active.isNotEmpty()) send(MotionEvent.ACTION_MOVE, active)
    }

    fun cancel() {
        if (active.isNotEmpty()) send(MotionEvent.ACTION_CANCEL, active)
        active.clear()
    }

    private fun map(contact: Contact, viewport: CarPlusVideo.Viewport) = contact.copy(
        xPixels = ((contact.xPixels - viewport.x) * CarPlusVideo.WIDTH / viewport.width)
            .coerceIn(0, CarPlusVideo.WIDTH - 1),
        yPixels = ((contact.yPixels - viewport.y) * CarPlusVideo.HEIGHT / viewport.height)
            .coerceIn(0, CarPlusVideo.HEIGHT - 1),
    )
}
