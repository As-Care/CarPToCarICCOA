package io.github.shaonialife.carptocar.carplus

import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import android.view.Surface
import io.github.shaonialife.carptocar.input.TouchReports.Contact

/** Runs the SDK outside the process whose default network is bound to CarPlay. */
internal class CarPlusReceiverService : Service() {
    private val endpoint = Messenger(Handler(Looper.getMainLooper()) { message ->
        when (message.what) {
            START -> {
                val replies = message.replyTo
                val generation = message.arg1
                val request = videoRequestFrom(message)
                CarPlusVideoTap.request = request
                CarPlusVideoTap.onSourceSize = { size ->
                    try {
                        replies.send(Message.obtain(null, VIDEO_SOURCE, generation, 0).apply {
                            data = Bundle().apply {
                                putInt(VIDEO_WIDTH, size.width)
                                putInt(VIDEO_HEIGHT, size.height)
                            }
                        })
                    } catch (_: RemoteException) {
                        // The client has unbound.
                    }
                }
                fun report(status: String?, log: String?) {
                    try {
                        replies.send(Message.obtain(null, STATUS, generation, 0).apply {
                            data = Bundle().apply {
                                putString(STATUS_TEXT, status)
                                putString(LOG_TEXT, log)
                                putBoolean(SOURCE_CONNECTED, CarPlusSdk.isConnected)
                                putLong(CAST_STARTED_AT, CarPlusSdk.castStartedAt)
                                putBoolean(SOURCE_FRAME_SEEN, CarPlusSdk.sourceFrameSeen)
                            }
                        })
                    } catch (_: RemoteException) {
                        // The service is bound to the sender. onDestroy stops the SDK when it goes away.
                    }
                }
                CarPlusSdk.report = ::report
                CarPlusSdk.start(applicationContext, refresh = message.arg2 == 1, request = request)
                report(null, null)
            }
            VIDEO_CONFIG -> CarPlusVideoTap.request = videoRequestFrom(message)
            SURFACE -> {
                val data = message.data
                val surface = if (Build.VERSION.SDK_INT >= 33) {
                    data.getParcelable(SURFACE_VALUE, Surface::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    data.getParcelable<Surface>(SURFACE_VALUE)
                }
                CarPlusVideoTap.setTarget(null)
                CarPlusSdk.setVideoOutput(surface, encoded = false)
            }
            VIDEO_TARGET -> {
                val data = message.data
                val descriptor = if (Build.VERSION.SDK_INT >= 33) {
                    data.getParcelable(VIDEO_PIPE, ParcelFileDescriptor::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    data.getParcelable<ParcelFileDescriptor>(VIDEO_PIPE)
                }
                CarPlusVideoTap.setTarget(descriptor)
                CarPlusSdk.setVideoOutput(null, encoded = descriptor != null)
            }
            STOP -> {
                CarPlusVideoTap.setTarget(null)
                CarPlusSdk.stop(stopNative = message.arg1 == 1)
                try {
                    message.replyTo?.send(Message.obtain(null, STOPPED, message.arg2, 0))
                } catch (_: RemoteException) {
                    // The sender has already stopped.
                }
            }
            AUDIO_TARGET -> CarPlusAudioTap.setTarget(message.replyTo)
            RECONNECT_IF_PAIRED -> CarPlusSdk.reconnectIfPaired(restartCurrent = true)
            TOUCH_REPORT -> {
                val report = message.data.getIntArray(TOUCH_REPORT_VALUE) ?: return@Handler false
                if (report.size !in 8..12 || report.size % 4 != 0) return@Handler false
                val viewport = CarPlusVideo.Viewport(report[0], report[1], report[2], report[3])
                val contacts = (4 until report.size step 4).map { index ->
                    Contact(report[index], report[index + 1], report[index + 2], report[index + 3] != 0)
                }
                CarPlusSdk.onTouch(contacts, viewport)
            }
            else -> return@Handler false
        }
        true
    })

    override fun onBind(intent: Intent): IBinder = endpoint.binder

    private fun videoRequestFrom(message: Message): CarPlusVideo.Request =
        CarPlusVideo.Request(
            CarPlusVideo.displaySize(message.data.getInt(VIDEO_WIDTH), message.data.getInt(VIDEO_HEIGHT)),
            message.data.getInt(VIDEO_FPS, CarPlusVideo.FPS),
        )

    override fun onDestroy() {
        CarPlusAudioTap.setTarget(null)
        CarPlusVideoTap.setTarget(null)
        CarPlusVideoTap.onSourceSize = null
        CarPlusVideoTap.request = CarPlusVideo.Request()
        CarPlusSdk.report = null
        CarPlusSdk.stop()
        super.onDestroy()
    }

    companion object {
        const val START = 1
        const val SURFACE = 2
        const val STOP = 3
        const val STATUS = 4
        private const val TOUCH_REPORT = 5
        const val AUDIO_TARGET = 7
        const val VIDEO_TARGET = 8
        const val VIDEO_CONFIG = 9
        const val VIDEO_SOURCE = 10
        const val RECONNECT_IF_PAIRED = 11
        const val STOPPED = 12
        const val VIDEO_WIDTH = "videoWidth"
        const val VIDEO_HEIGHT = "videoHeight"
        const val VIDEO_FPS = "videoFps"
        const val VIDEO_PIPE = "videoPipe"
        const val SURFACE_VALUE = "surface"
        const val STATUS_TEXT = "status"
        const val LOG_TEXT = "log"
        const val SOURCE_CONNECTED = "sourceConnected"
        const val CAST_STARTED_AT = "castStartedAt"
        const val SOURCE_FRAME_SEEN = "sourceFrameSeen"
        private const val TOUCH_REPORT_VALUE = "touchReport"

        fun touchMessage(contacts: List<Contact>, viewport: CarPlusVideo.Viewport): Message {
            val report = IntArray(4 + contacts.size * 4)
            report[0] = viewport.x
            report[1] = viewport.y
            report[2] = viewport.width
            report[3] = viewport.height
            contacts.forEachIndexed { index, contact ->
                val offset = 4 + index * 4
                report[offset] = contact.slot
                report[offset + 1] = contact.xPixels
                report[offset + 2] = contact.yPixels
                report[offset + 3] = if (contact.down) 1 else 0
            }
            return Message.obtain(null, TOUCH_REPORT).apply {
                data = Bundle().apply { putIntArray(TOUCH_REPORT_VALUE, report) }
            }
        }
    }
}
