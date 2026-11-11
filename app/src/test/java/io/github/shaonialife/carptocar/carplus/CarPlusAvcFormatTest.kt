package io.github.shaonialife.carptocar.carplus

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CarPlusAvcFormatTest {
    @Test
    fun strictReceiverRequiresBothMatchingDimensionsAndBaseline() {
        val audi = CarPlusVideo.Size(1412, 654)
        assertTrue(CarPlusVideo.AvcFormat(audi, 66).canForwardTo(audi, false))
        assertFalse(CarPlusVideo.AvcFormat(audi, 100).canForwardTo(audi, false))
        val padded = CarPlusVideo.AvcFormat(CarPlusVideo.Size(1412, 656), 66)
        assertFalse(padded.canForwardTo(audi, false))
        assertFalse(padded.canForwardTo(audi, true))
        assertTrue(CarPlusVideo.AvcFormat(audi, 100).canForwardTo(audi, true))
    }
}
