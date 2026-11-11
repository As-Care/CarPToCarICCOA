package io.github.shaonialife.carptocar.carlife

import org.junit.Assert.assertEquals
import org.junit.Test

class CarLifeHardKeyTest {
    @Test
    fun voiceHardKeyIsKeycode33() {
        val payload = CarLifeProtocol.carHardKeyCode(CarLifeProtocol.HardKey.VR_START)
        assertEquals(2, payload.size)
        assertEquals(0x08, payload[0].toInt())
        assertEquals(0x21, payload[1].toInt())
        assertEquals(33, CarLifeProtocol.HardKey.VR_START)
        assertEquals(34, CarLifeProtocol.HardKey.VR_STOP)
    }

    @Test
    fun messageIdMatchesOfficialSdk() {
        assertEquals(0x00068008, CarLifeProtocol.TOUCH_CAR_HARD_KEY_CODE)
    }
}
