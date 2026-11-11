package io.github.shaonialife.carptocar.carplus

import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import android.util.Log
import android.view.Surface
import androidx.annotation.Keep
import java.io.DataOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicReference

/** Entry points called by the SDK bundled in our APK, before it queues a frame for decoding. */
@Keep
internal object CarPlusVideoTap {
    const val MAX_FRAME_BYTES = 2 * 1024 * 1024
    @Volatile private var target: Target? = null
    @Volatile var request = CarPlusVideo.Request()
    @Volatile var advertisedFps: Int? = null
        private set
    @Volatile var onSourceSize: ((CarPlusVideo.Size) -> Unit)? = null
    private val nextFrame = AtomicReference<(() -> Unit)?>(null)
    private val originalStart by lazy {
        Class.forName("com.ucar.vehiclesdk.cast.CastManager").getMethod(
            "startCast", Surface::class.java, Integer.TYPE, Integer.TYPE, java.lang.Boolean.TYPE,
            java.lang.Long.TYPE, Class.forName("com.ucar.vehiclesdk.IAppSurfaceStateListener"),
        )
    }
    private val originalQueue by lazy {
        Class.forName("com.ucar.vehiclesdk.player.VideoQueueManager")
            .getMethod("putData", ByteBuffer::class.java, java.lang.Long.TYPE)
    }
    private val originalWfdStart by lazy {
        Class.forName("com.ucar.vehiclesdk.cast.CastManager").getMethod(
            "startWfdServer", Integer.TYPE, Integer.TYPE, Integer.TYPE, Integer.TYPE,
            java.lang.Boolean.TYPE, java.lang.Boolean.TYPE, java.lang.Boolean.TYPE,
        )
    }

    fun setTarget(descriptor: ParcelFileDescriptor?) {
        target?.close()
        target = descriptor?.let(::Target)
    }

    @JvmStatic
    fun startCast(
        manager: Any, surface: Surface?, width: Int, height: Int,
        lowLatency: Boolean, lastShown: Long, listener: Any?,
    ): Boolean {
        CarPlusVideo.displaySize(width, height)?.let { onSourceSize?.invoke(it) }
        if (surface == null) return target?.start() == true
        return originalStart.invoke(manager, surface, width, height, lowLatency, lastShown, listener) == true
    }

    @JvmStatic
    @Suppress("UNUSED_PARAMETER") // The hook keeps the SDK method signature.
    fun startWfdServer(
        manager: Any, width: Int, height: Int, dpi: Int, fps: Int,
        hevc: Boolean, fusion: Boolean, rawPackets: Boolean,
    ) {
        val requested = request
        val canvas = requested.canvas ?: CarPlusVideo.Size(width, height)
        val sourceFps = requested.fps
        advertisedFps = sourceFps
        Log.i("CarPlusVideo", "ICCOA 向 Car+ 申请原生画布 ${canvas.width}×${canvas.height}@$sourceFps")
        originalWfdStart.invoke(manager, canvas.width, canvas.height, dpi, sourceFps, hevc, fusion, rawPackets)
    }

    fun resetAdvertisedFps() {
        advertisedFps = null
    }

    fun onNextFrame(callback: (() -> Unit)?) {
        nextFrame.set(callback)
    }

    @JvmStatic
    fun queueFrame(queue: Any, buffer: ByteBuffer, timestampUs: Long) {
        val size = buffer.remaining()
        if (size > 0 && nextFrame.get() != null) nextFrame.getAndSet(null)?.invoke()
        val pipe = target
        if (pipe == null) {
            originalQueue.invoke(queue, buffer, timestampUs)
            return
        }
        if (size == 0) return
        if (size in 1..MAX_FRAME_BYTES) {
            val bytes = ByteArray(size)
            buffer.duplicate().get(bytes)
            pipe.offer(Frame(bytes, timestampUs))
        } else {
            Log.w("CarPlusVideo", "Oversized ICCOA access unit: $size")
            pipe.close()
        }
    }

    private class Frame(val bytes: ByteArray, val timestampUs: Long)

    private class Target(private val descriptor: ParcelFileDescriptor) {
        private val frames = ArrayBlockingQueue<Frame>(12)
        @Volatile private var closed = false
        private val marker = Frame(ByteArray(0), 0)
        private val writer = Thread(::write, "iccoa-video-out").apply { isDaemon = true; start() }

        fun start(): Boolean {
            if (closed) return false
            offer(marker)
            return !closed
        }

        fun offer(frame: Frame) {
            if (!closed && !frames.offer(frame)) {
                Log.w("CarPlusVideo", "Encoded video pipe queue is full")
                close()
            }
        }

        fun close() {
            closed = true
            runCatching { Os.shutdown(descriptor.fileDescriptor, OsConstants.SHUT_RDWR) }
            writer.interrupt()
        }

        private fun write() {
            try {
                DataOutputStream(ParcelFileDescriptor.AutoCloseOutputStream(descriptor)).use { output ->
                    while (!closed) {
                        val frame = frames.take()
                        output.writeInt(frame.bytes.size)
                        if (frame !== marker) {
                            output.writeLong(frame.timestampUs)
                            output.write(frame.bytes)
                        }
                    }
                }
            } catch (_: IOException) {
                // The sender closed the pipe or the CarPlay session ended.
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                closed = true
                frames.clear()
            }
        }
    }
}
