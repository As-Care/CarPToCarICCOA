package io.github.shaonialife.carptocar.carplus

import android.os.Handler
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import io.github.shaonialife.carptocar.SessionController
import io.github.shaonialife.carptocar.video.H264Sps
import io.github.shaonialife.carptocar.video.VideoSender
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.IOException

/** Reads Annex B access units and cast start markers from the ICCOA process. */
internal class CarPlusEncodedVideo(
    private val input: ParcelFileDescriptor,
    private val sender: VideoSender,
    private val outputSize: CarPlusVideo.Size,
    private val allowOtherProfiles: Boolean,
    private val main: Handler,
    private val cropAudiStream: Boolean = false,
    private val onFailure: (CarPlusEncodedVideo, String, CarPlusVideo.AvcFormat?) -> Unit,
) {
    @Volatile private var cancelled = false
    @Volatile private var waitingForKeyFrame = false
    @Volatile private var timeoutReason: String? = null
    private val timeout = Runnable {
        if (waitingForKeyFrame && !cancelled) {
            timeoutReason = "8 秒内未收到可用的 H.264 关键帧"
            interruptRead()
        }
    }

    fun start() {
        Thread(::read, "iccoa-video-in").apply {
            isDaemon = true
            start()
        }
    }

    fun close() {
        cancelled = true
        main.removeCallbacks(timeout)
        interruptRead()
    }

    private fun interruptRead() {
        runCatching { Os.shutdown(input.fileDescriptor, OsConstants.SHUT_RDWR) }
    }

    private fun read() {
        var sourceSps: ByteArray? = null
        var sps: ByteArray? = null
        var pps: ByteArray? = null
        var failure = "ICCOA 直通管道已关闭"
        var incompatibleFormat: CarPlusVideo.AvcFormat? = null
        try {
            DataInputStream(BufferedInputStream(ParcelFileDescriptor.AutoCloseInputStream(input))).use { stream ->
                while (!cancelled && sender.isRunning) {
                    val size = stream.readInt()
                    if (size == 0) {
                        sourceSps = null
                        sps = null
                        pps = null
                        awaitKeyFrame()
                        continue
                    }
                    if (size !in 1..CarPlusVideoTap.MAX_FRAME_BYTES) throw IOException("ICCOA 帧长度异常")
                    val timestampUs = stream.readLong()
                    val frame = ByteArray(size).also(stream::readFully)
                    if (!waitingForKeyFrame && sps == null) continue
                    val units = sender.splitAnnexB(frame)
                    val newSps = units.firstOrNull { nalType(it) == 7 }
                    val newPps = units.firstOrNull { nalType(it) == 8 }
                    if (newSps != null && !newSps.contentEquals(sourceSps)) {
                        val dimensions = H264Sps.parseNal(newSps) ?: throw IOException("无法解析源 H.264 SPS")
                        val format = CarPlusVideo.AvcFormat(
                            CarPlusVideo.Size(dimensions.first, dimensions.second), newSps[1].toInt() and 0xff,
                        )
                        val compatibleSps = when {
                            format.canForwardTo(outputSize, allowOtherProfiles) -> newSps
                            cropAudiStream && format.canCropToAudiCanvas(outputSize) ->
                                H264Sps.cropBottom(newSps, format.size.height - outputSize.height)
                            else -> null
                        }
                        if (compatibleSps == null) {
                            incompatibleFormat = format
                            throw IOException("源 H.264 档位不兼容（$format，目标 ${outputSize.width}×${outputSize.height}）")
                        }
                        sourceSps = newSps
                        sps = compatibleSps
                        awaitKeyFrame()
                        val cropLog = if (compatibleSps === newSps) "" else " · SPS 裁切至 ${outputSize.width}×${outputSize.height}"
                        SessionController.appendLog("ICCOA: 源 H.264 SPS $format$cropLog")
                    }
                    if (newPps != null && !newPps.contentEquals(pps)) {
                        pps = newPps
                        awaitKeyFrame()
                    }
                    val idr = units.any { nalType(it) == 5 }
                    if (waitingForKeyFrame && (!idr || sps == null || pps == null)) continue
                    if (units.none { nalType(it) in 1..5 }) continue
                    val croppedSps = sps?.takeIf { it !== sourceSps }
                    if (newSps != null && croppedSps != null &&
                        units.any { nalType(it) == 7 && !it.contentEquals(newSps) }
                    ) {
                        throw IOException("源 H.264 包含多个 SPS，无法裁切直通")
                    }
                    val nals = when {
                        idr && sps != null && pps != null && (newSps == null || newPps == null) ->
                            listOf(sps, pps) + units.filter { nalType(it) != 7 && nalType(it) != 8 }
                        newSps != null && croppedSps != null -> units.map { if (nalType(it) == 7) croppedSps else it }
                        else -> units
                    }
                    if (sender.submitH264Nals(nals, idr, timestampUs / 1_000) && waitingForKeyFrame) {
                        waitingForKeyFrame = false
                        main.removeCallbacks(timeout)
                        SessionController.appendLog("ICCOA: H.264 直通首个 IDR 已送达 CarPlay 视频通道")
                    }
                }
            }
        } catch (error: IOException) {
            failure = error.message ?: failure
        } finally {
            main.removeCallbacks(timeout)
            if (!cancelled) main.post {
                if (!cancelled) onFailure(this, timeoutReason ?: failure, incompatibleFormat)
            }
        }
    }

    private fun awaitKeyFrame() {
        if (waitingForKeyFrame) return
        waitingForKeyFrame = true
        main.postDelayed(timeout, 8_000)
    }

    private fun nalType(nal: ByteArray) = nal[0].toInt() and 0x1f
}
