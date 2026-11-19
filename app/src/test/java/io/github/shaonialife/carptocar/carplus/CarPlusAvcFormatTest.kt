package io.github.shaonialife.carptocar.carplus

import io.github.shaonialife.carptocar.video.H264Sps
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test
    fun audiCropRequiresTheVerifiedBaselineSourceAndCanvas() {
        val audi = CarPlusVideo.audiCanvas
        val aligned = CarPlusVideo.AvcFormat(CarPlusVideo.Size(1412, 656), 66)
        assertTrue(aligned.canCropToAudiCanvas(audi))
        assertFalse(aligned.copy(profile = 100).canCropToAudiCanvas(audi))
        assertFalse(aligned.copy(size = CarPlusVideo.Size(1412, 660)).canCropToAudiCanvas(audi))
        assertFalse(aligned.canCropToAudiCanvas(CarPlusVideo.Size(1412, 652)))
    }

    @Test
    fun cropOfNativeCarPlusSpsMatchesFfmpegMetadataRewrite() {
        // Captured Car+ Baseline SPS and the corresponding FFmpeg h264_metadata output.
        val source = bytes("67 42 80 28 da 01 64 14 f9 f9 a8 28 30 28 36 85 09 a8")
        val expected = bytes("67 42 80 28 da 01 64 14 f9 ea 6a 0a 0c 0a 0d a1 42 6a")
        assertEquals(1412 to 656, H264Sps.parseNal(source))
        val cropped = checkNotNull(H264Sps.cropBottom(source, 2))
        assertEquals(1412 to 654, H264Sps.parseNal(cropped))
        assertArrayEquals(expected, cropped)
        assertNull(H264Sps.cropBottom(source, 1))
        assertNull(H264Sps.cropBottom(source.copyOf(8), 2))
    }

    private fun bytes(hex: String): ByteArray = hex.split(' ').map { it.toInt(16).toByte() }.toByteArray()
}
