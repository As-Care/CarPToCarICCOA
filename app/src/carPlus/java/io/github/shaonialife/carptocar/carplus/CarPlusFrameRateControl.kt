package io.github.shaonialife.carptocar.carplus

import com.ucar.protocol.CmdCategory
import com.ucar.protocol.DataFormat
import com.ucar.protocol.MessageType
import com.ucar.protocol.SourceDevice
import com.ucar.protocol.UCarMessage
import com.ucar.protocol.channel.SendFutureCallback
import com.ucar.vehiclesdk.UCarAdapter
import java.util.concurrent.Future

/** Sends a session-scoped NotifyFrameInfo over the bundled SDK's authenticated control channel. */
internal object CarPlusFrameRateControl {
    private const val NOTIFY_FRAME_INFO = 0x2b
    private val controlManager by lazy {
        UCarAdapter::class.java.getDeclaredField("mControlManager").apply { isAccessible = true }
    }
    private val controlChannel by lazy {
        controlManager.type.getDeclaredField("mControlSocketChannel").apply { isAccessible = true }
    }
    private val isConnected by lazy { controlChannel.type.getMethod("isConnected") }
    private val send by lazy {
        controlChannel.type.getMethod("send", UCarMessage::class.java, SendFutureCallback::class.java)
    }

    fun request(adapter: UCarAdapter, fps: Int): Future<*>? {
        val manager = controlManager.get(adapter) ?: return null
        val channel = controlChannel.get(manager) ?: return null
        if (isConnected.invoke(channel) != true) return null
        val message = UCarMessage.newBuilder()
            .setSourceDevice(SourceDevice.CAR)
            .setCmdCategory(CmdCategory.CONTROL)
            .setMessageType(MessageType.SEND)
            .setMethodIndex(NOTIFY_FRAME_INFO)
            .setDataFormat(DataFormat.PB3)
            .build(CarPlusVideo.frameRateRequestBody(fps))
        return send.invoke(channel, message, null) as? Future<*>
    }
}
