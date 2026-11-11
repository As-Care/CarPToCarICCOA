package io.github.shaonialife.carptocar.carplus

import android.content.Context
import android.view.Surface
import io.github.shaonialife.carptocar.input.TouchReports.Contact
import io.github.shaonialife.carptocar.video.VideoSender

@Suppress("UNUSED_PARAMETER")
internal object CarPlusReceiver {
    val status: String get() = ""
    val isSourceConnected: Boolean get() = false
    val isRunning: Boolean get() = false

    fun start(context: Context, refresh: Boolean = false) = Unit

    fun hasSystemCarPlus(context: Context): Boolean = false

    fun openSystemCarPlus(context: Context, onFailure: () -> Unit) = Unit

    fun requestReconnect() = false

    fun attach(surface: Surface) = Unit

    fun startVideo(sender: VideoSender, receiverIdentity: String, width: Int, height: Int, receiverAddress: String? = null) = false

    fun setVideoConfig(width: Int, height: Int, fps: Int) = Unit

    fun stopVideo(sender: VideoSender?) = Unit

    fun detach(surface: Surface) = Unit

    fun onTouch(contact: Contact, displayWidth: Int, displayHeight: Int) = Unit

    fun startAudio() = Unit

    fun stopAudio() = Unit

    fun hasRecentMusic() = false

    fun stop(stopNative: Boolean = false) = Unit
}
