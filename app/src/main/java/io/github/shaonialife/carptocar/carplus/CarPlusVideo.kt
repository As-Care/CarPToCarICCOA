package io.github.shaonialife.carptocar.carplus

internal object CarPlusVideo {
    const val WIDTH = 1280
    const val HEIGHT = 720
    const val FPS = 30
    const val HIGH_FPS = 60

    data class Size(val width: Int, val height: Int)
    data class Request(val canvas: Size? = null, val fps: Int = FPS)

    fun supportsHighFps(carMaxFps: Int): Boolean = carMaxFps >= HIGH_FPS

    fun targetFps(highFpsRequested: Boolean, carMaxFps: Int): Int =
        when {
            highFpsRequested && supportsHighFps(carMaxFps) -> HIGH_FPS
            carMaxFps in 15 until FPS -> carMaxFps
            else -> FPS
        }

    /** NotifyFrameInfo.carFrameInfo (1) wraps CarFrameInfo.requestFps (1).
     * The default requestParty and absent castChannelId select the main display's FPS.
     */
    fun frameRateRequestBody(fps: Int): ByteArray {
        require(fps in 15..HIGH_FPS)
        return byteArrayOf(0x0a, 0x02, 0x08, fps.toByte())
    }

    data class AvcFormat(val size: Size, val profile: Int) {
        fun canForwardTo(output: Size, acceptsOtherProfiles: Boolean): Boolean =
            size == output && (profile == 66 || acceptsOtherProfiles)

        override fun toString(): String = "${size.width}×${size.height}，profile $profile"
    }

    data class Viewport(val x: Int, val y: Int, val width: Int, val height: Int)

    val defaultSize = Size(WIDTH, HEIGHT)

    fun displaySize(width: Int, height: Int): Size? =
        if (width in 320..4096 && height in 180..2304) Size(width, height) else null

    fun manualSize(width: Int, height: Int): Size? =
        if (width % 2 == 0 && height % 2 == 0) displaySize(width, height) else null

    /** Coordinates are relative to the top-left of the output. Leave bars if the source declines our aspect ratio. */
    fun fit(source: Size, output: Size): Viewport {
        val width = minOf(output.width, (output.height.toLong() * source.width / source.height).toInt())
        val height = minOf(output.height, (output.width.toLong() * source.height / source.width).toInt())
        return Viewport((output.width - width) / 2, (output.height - height) / 2, width, height)
    }
}
