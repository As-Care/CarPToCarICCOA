package io.github.shaonialife.carptocar.video

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.shaonialife.carptocar.BuildConfig
import io.github.shaonialife.carptocar.carplus.CarPlusVideo
import io.github.shaonialife.carptocar.carplus.CarPlusVideoTap
import java.io.BufferedReader
import java.net.InetAddress
import java.net.ServerSocket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CarPlusWfdCanvasTest {
    @Test
    fun sdkAdvertisesAudiCanvasToWfdSource() {
        advertisedAvc(CarPlusVideo.audiCanvas, CarPlusVideo.FPS)
    }

    @Test
    fun sdkAdvertisesManualCanvasToWfdSource() {
        val size = checkNotNull(CarPlusVideo.manualSize(1380, 880))
        advertisedAvc(size, CarPlusVideo.FPS)
    }

    @Test
    fun sdkAdvertisesSelectedHighFrameRateToWfdSource() {
        advertisedAvc(CarPlusVideo.defaultSize, CarPlusVideo.HIGH_FPS)
    }

    @Test
    fun audiBaselineChangesOnlyAudiWfdAvcCapabilities() {
        assumeTrue(BuildConfig.CAR_PLUS_WFD_PROFILE_CONTROL)
        val audi = CarPlusVideo.audiCanvas
        val fps = CarPlusVideo.FPS
        val original = advertisedAvc(audi, fps)
        assertEquals(0x01 to 0x01, original)
        assertEquals(0x01 to 0x04, advertisedAvc(audi, fps, audiBaseline = true))
        assertEquals(original, advertisedAvc(audi, fps))
        val other = CarPlusVideo.defaultSize
        assertEquals(advertisedAvc(other, fps), advertisedAvc(other, fps, audiBaseline = true))
    }

    private fun advertisedAvc(size: CarPlusVideo.Size, fps: Int, audiBaseline: Boolean = false): Pair<Int, Int> {
        assumeTrue(BuildConfig.CAR_PLUS_VIDEO_RELAY)
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { source ->
            source.soTimeout = 5_000
            val deviceType = Class.forName("com.ucar.vehiclesdk.MDevice")
            val device = deviceType.getConstructor().newInstance()
            deviceType.getMethod("setAddress", String::class.java).invoke(device, "127.0.0.1")
            deviceType.getMethod("setPort", Integer.TYPE).invoke(device, source.localPort)
            val cast = Class.forName("com.ucar.vehiclesdk.cast.CastManager").getConstructor(
                Class.forName("com.ucar.vehiclesdk.audio.UCarAudioManager"),
                deviceType,
                Class.forName("com.ucar.vehiclesdk.cast.CastListener"),
            ).newInstance(null, device, null)
            CarPlusVideoTap.request = CarPlusVideo.Request(size, fps, audiBaseline)
            try {
                CarPlusVideoTap.startWfdServer(cast, 1280, 720, 320, 30, false, false, false)
                source.accept().use { peer ->
                    peer.soTimeout = 5_000
                    val input = peer.getInputStream().bufferedReader(Charsets.US_ASCII)
                    val output = peer.getOutputStream()
                    fun send(message: String) = output.write(message.toByteArray(Charsets.US_ASCII))

                    send("OPTIONS * RTSP/1.0\r\nCSeq: 1\r\nRequire: org.wfa.wfd1.0\r\n\r\n")
                    repeat(2) {
                        val (headers, _) = readRtsp(input)
                        if (headers.first().startsWith("OPTIONS ")) {
                            val sequence = headers.first { it.startsWith("CSeq:", ignoreCase = true) }
                            send("RTSP/1.0 200 OK\r\n$sequence\r\nPublic: org.wfa.wfd1.0, GET_PARAMETER, SET_PARAMETER\r\n\r\n")
                        } else {
                            assertTrue(headers.first().startsWith("RTSP/1.0 200"))
                        }
                    }
                    val fields = "wfd_video_formats\r\nwfd_car_display_mode\r\n"
                    send(
                        "GET_PARAMETER rtsp://127.0.0.1/wfd1.0 RTSP/1.0\r\nCSeq: 2\r\n" +
                            "Content-Type: text/parameters\r\nContent-Length: ${fields.length}\r\n\r\n$fields",
                    )
                    val (headers, body) = readRtsp(input)
                    assertTrue(headers.first(), headers.first().startsWith("RTSP/1.0 200"))
                    assertTrue(body, body.contains("display=${size.width}:${size.height};dpi=320;fps=$fps"))
                    val videoFormats = body.lineSequence().first { it.startsWith("wfd_video_formats:") }
                    val capability = videoFormats.substringAfter(':').trim().split(' ')
                    return capability[2].toInt(16) to capability[3].toInt(16)
                }
            } finally {
                CarPlusVideoTap.request = CarPlusVideo.Request()
                CarPlusVideoTap.resetAdvertisedRequest()
            }
        }
    }

    @Test
    fun displayUsesNativeAspectAndFitsAnUnmatchedSource() {
        val audi = CarPlusVideo.audiCanvas
        assertEquals(CarPlusVideo.Viewport(0, 0, 1412, 654), CarPlusVideo.fit(audi, audi))
        assertEquals(CarPlusVideo.Viewport(125, 0, 1162, 654), CarPlusVideo.fit(CarPlusVideo.defaultSize, audi))
    }

    private fun readRtsp(input: BufferedReader): Pair<List<String>, String> {
        val headers = generateSequence { input.readLine()?.takeUnless(String::isEmpty) }.toList()
        val size = headers.firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
            ?.substringAfter(':')?.trim()?.toInt() ?: 0
        val body = CharArray(size)
        var read = 0
        while (read < size) {
            val count = input.read(body, read, size - read)
            check(count > 0) { "Incomplete RTSP body" }
            read += count
        }
        return headers to body.concatToString()
    }
}
