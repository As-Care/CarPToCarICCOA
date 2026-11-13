package io.github.shaonialife.carptocar.carplus

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import android.os.SystemClock
import android.view.Surface
import io.github.shaonialife.carptocar.BuildConfig
import io.github.shaonialife.carptocar.SessionController
import io.github.shaonialife.carptocar.bt.CarIdleGuard
import io.github.shaonialife.carptocar.input.TouchReports.Contact
import io.github.shaonialife.carptocar.video.VideoSender
import java.io.IOException

/** Connects the CarPlay video sender to the isolated ICCOA process. */
internal object CarPlusReceiver {
    private val main = Handler(Looper.getMainLooper())
    private var bindingContext: Context? = null
    private var receiver: Messenger? = null
    private var surface: Surface? = null
    private var scaler: CarPlusSurfaceScaler? = null
    private data class DirectDecoder(val output: Surface, val sender: VideoSender, val size: CarPlusVideo.Size)
    private var directDecoder: DirectDecoder? = null
    @Volatile private var videoRequest = CarPlusVideo.Request()
    @Volatile private var sourceSize = CarPlusVideo.defaultSize
    private var encodedSender: VideoSender? = null
    private var encodedSize = CarPlusVideo.defaultSize
    private var encodedVideo: CarPlusEncodedVideo? = null
    private var allowOtherProfiles = false
    private data class VideoRoute(
        val receiver: String,
        val address: String?,
        val request: CarPlusVideo.Request,
        val output: CarPlusVideo.Size,
    )
    private data class IncompatibleRoute(val route: VideoRoute, val format: CarPlusVideo.AvcFormat, val until: Long)
    private var encodedRoute: VideoRoute? = null
    @Volatile private var incompatibleRoute: IncompatibleRoute? = null
    @Volatile private var audio: CarPlusAudio? = null
    private var refreshPending = false
    private var bindingGeneration = 0
    private var pendingStop = false
    private val replies = Messenger(Handler(main.looper) { message ->
        if (bindingContext != null && message.arg1 == bindingGeneration) {
            when (message.what) {
                CarPlusReceiverService.STOPPED -> if (pendingStop) {
                    unbind()
                }
                CarPlusReceiverService.STATUS -> {
                    updateCastProgress(CarPlusVideo.CastProgress(
                        connected = message.data.getBoolean(CarPlusReceiverService.SOURCE_CONNECTED),
                        startedAt = message.data.getLong(CarPlusReceiverService.CAST_STARTED_AT),
                        sourceFrameSeen = message.data.getBoolean(CarPlusReceiverService.SOURCE_FRAME_SEEN),
                    ))
                    message.data.getString(CarPlusReceiverService.STATUS_TEXT)?.let { status = it }
                    message.data.getString(CarPlusReceiverService.LOG_TEXT)?.let { SessionController.appendLog("ICCOA: $it") }
                }
                CarPlusReceiverService.VIDEO_SOURCE -> CarPlusVideo.displaySize(
                    message.data.getInt(CarPlusReceiverService.VIDEO_WIDTH),
                    message.data.getInt(CarPlusReceiverService.VIDEO_HEIGHT),
                )?.let { size ->
                    sourceSize = size
                    scaler?.sourceSize = size
                    SessionController.appendLog("ICCOA: Car+ 实际源画面 ${size.width}×${size.height}")
                    directDecoder?.takeIf { it.output === surface && it.size != size }?.let {
                        directDecoder = null
                        if (it.sender.isRunning) attachScaled(it.output, it.sender, it.size)
                    }
                }
            }
        }
        true
    })

    @Volatile
    var status = if (BuildConfig.CAR_PLUS_RUNTIME) {
        "正在准备 ICCOA Car+ 接收端"
    } else {
        "ICCOA 编译验证包 · 需要车端 SDK 才能配对"
    }
        private set

    val isRunning: Boolean get() = bindingContext != null && !pendingStop

    @Volatile
    var castProgress = CarPlusVideo.CastProgress()
        private set

    val isSourceConnected: Boolean get() = castProgress.connected

    private fun updateCastProgress(progress: CarPlusVideo.CastProgress = CarPlusVideo.CastProgress()) {
        val ended = isSourceConnected && !progress.connected
        castProgress = if (progress.connected) {
            progress
        } else {
            progress.copy(disconnectedAt = if (ended) SystemClock.elapsedRealtime() else castProgress.disconnectedAt)
        }
        if (ended) bindingContext?.let(CarIdleGuard::onProjectionStopped)
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            if (bindingContext == null) return
            receiver = Messenger(binder)
            startRemote(refreshPending)
            refreshPending = false
            surface?.takeIf { it.isValid }?.let { sendSurface(it) }
            connectEncodedVideo()
            sendAudioTarget()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            if (bindingContext == null) return
            receiver = null
            updateCastProgress()
            status = "ICCOA 接收进程已断开 · 正在恢复"
        }

        override fun onBindingDied(name: ComponentName) {
            unbind()
            status = "ICCOA 接收进程已停止 · 点此重试"
        }

        override fun onNullBinding(name: ComponentName) = onBindingDied(name)
    }

    fun start(context: Context, refresh: Boolean = false) = onMain {
        if (pendingStop) unbind()
        if (!BuildConfig.CAR_PLUS_RUNTIME) {
            SessionController.appendLog("ICCOA: 当前 APK 使用接口存根，未打包运行时 SDK")
        } else if (receiver != null) {
            startRemote(refresh)
        } else {
            refreshPending = refreshPending || refresh
            if (bindingContext == null) {
                val app = context.applicationContext
                bindingGeneration++
                val intent = Intent(app, CarPlusReceiverService::class.java)
                val bound = runCatching { app.bindService(intent, connection, Context.BIND_AUTO_CREATE) }
                    .getOrDefault(false)
                if (bound) {
                    bindingContext = app
                } else {
                    status = "ICCOA 接收进程启动失败 · 点此重试"
                }
            }
        }
    }

    fun attach(surface: Surface) = attach(surface, null)

    private fun attach(surface: Surface, scaling: CarPlusSurfaceScaler?) = onMain {
        if (BuildConfig.CAR_PLUS_RUNTIME && surface.isValid && scaling?.output?.isValid != false) {
            scaler?.close()
            scaler = scaling
            scaling?.sourceSize = sourceSize
            this.surface = surface
            sendSurface(surface)
        } else {
            scaling?.close()
        }
    }

    fun setVideoConfig(width: Int, height: Int, fps: Int) {
        val canvas = CarPlusVideo.displaySize(width, height) ?: return
        val request = CarPlusVideo.Request(canvas, fps)
        videoRequest = request
        onMain {
            if (videoRequest === request) {
                send(Message.obtain(null, CarPlusReceiverService.VIDEO_CONFIG).apply { data = videoBundle(request) })
            }
        }
    }

    fun startVideo(sender: VideoSender, receiverIdentity: String, width: Int, height: Int, receiverAddress: String? = null): Boolean {
        val size = CarPlusVideo.Size(width, height)
        val route = VideoRoute(receiverIdentity, receiverAddress, videoRequest, size)
        val recentMismatch = incompatibleRoute?.takeIf { it.route == route && SystemClock.elapsedRealtime() < it.until }
        if (BuildConfig.CAR_PLUS_VIDEO_RELAY && recentMismatch != null) {
            SessionController.appendLog("ICCOA: 近期源档位不兼容（${recentMismatch.format}）· 直接使用 Surface")
            return sender.start { attachDecoded(it, sender, size, recentMismatch.format.size) }
        }
        if (!BuildConfig.CAR_PLUS_VIDEO_RELAY || !sender.startPassthrough(strictKeyFrames = true)) {
            return sender.start { attachScaled(it, sender, size) }
        }
        onMain {
            if (sender.isRunning) {
                encodedVideo?.close()
                encodedVideo = null
                encodedSender = sender
                encodedSize = size
                encodedRoute = route
                allowOtherProfiles = receiverIdentity.endsWith("AllPlay1,1")
                connectEncodedVideo()
            }
        }
        return true
    }

    private fun attachScaled(output: Surface, sender: VideoSender, size: CarPlusVideo.Size) {
        val scaling = CarPlusSurfaceScaler(output, size.width, size.height, sender::endStream)
        scaling.sourceSize = sourceSize
        val input = runCatching { scaling.open() }.getOrElse {
            SessionController.appendLog("ICCOA: 等比缩放初始化失败 · ${it.message}")
            sender.endStream()
            return
        }
        onMain {
            if (sender.isRunning) {
                directDecoder = null
                attach(input, scaling)
            } else {
                scaling.close()
            }
        }
    }

    private fun attachDecoded(
        output: Surface,
        sender: VideoSender,
        size: CarPlusVideo.Size,
        actual: CarPlusVideo.Size?,
    ) {
        if (actual != size || sourceSize != size) {
            attachScaled(output, sender, size)
            return
        }
        onMain {
            if (!sender.isRunning || !output.isValid) return@onMain
            if (sourceSize != size) {
                attachScaled(output, sender, size)
            } else {
                directDecoder = DirectDecoder(output, sender, size)
                attach(output)
                SessionController.appendLog("ICCOA: 源画面与码流同尺寸 · 解码输出直接交给编码器")
            }
        }
    }

    fun stopVideo(sender: VideoSender?) = onMain {
        if (sender != null && encodedSender === sender) {
            encodedSender = null
            encodedRoute = null
            encodedVideo?.close()
            encodedVideo = null
            sendSurface(null)
        }
    }

    private fun connectEncodedVideo() {
        val sender = encodedSender ?: return
        val remote = receiver ?: return
        if (encodedVideo != null || !sender.isRunning) return
        try {
            val (input, output) = ParcelFileDescriptor.createSocketPair()
            val video = CarPlusEncodedVideo(input, sender, encodedSize, allowOtherProfiles, main) { failed, reason, format ->
                if (encodedVideo === failed) fallBack(sender, reason, format)
            }
            encodedVideo = video
            video.start()
            output.use {
                remote.send(Message.obtain(null, CarPlusReceiverService.VIDEO_TARGET).apply {
                    data = Bundle().apply { putParcelable(CarPlusReceiverService.VIDEO_PIPE, it) }
                })
            }
        } catch (error: IOException) {
            fallBack(sender, error.message ?: "无法创建视频管道")
        } catch (_: RemoteException) {
            receiver = null
            fallBack(sender, "ICCOA 接收进程已断开")
        }
    }

    private fun fallBack(sender: VideoSender, reason: String, format: CarPlusVideo.AvcFormat? = null) {
        if (encodedSender !== sender) return
        if (format != null && sender.isRunning) {
            encodedRoute?.let { route ->
                // A confirmed SPS mismatch can skip the probe on the next quick reconnect.
                incompatibleRoute = IncompatibleRoute(route, format, SystemClock.elapsedRealtime() + SOURCE_PROBE_RETRY_MS)
            }
        }
        encodedRoute = null
        encodedVideo?.close()
        encodedVideo = null
        encodedSender = null
        if (!sender.isRunning) return
        SessionController.appendLog("ICCOA: $reason · 切回 Surface")
        val size = encodedSize
        sender.fallBackToSurface { attachDecoded(it, sender, size, format?.size) }
    }

    fun detach(surface: Surface) = onMain {
        if (this.surface === surface || scaler?.output === surface) {
            this.surface = null
            directDecoder = null
            sendSurface(null)
            scaler?.close()
            scaler = null
        }
    }

    fun onTouch(sender: VideoSender, contacts: List<Contact>, displayWidth: Int, displayHeight: Int) {
        if (!BuildConfig.CAR_PLUS_RUNTIME || displayWidth <= 0 || displayHeight <= 0) return
        onMain {
            if (SessionController.videoSender !== sender || !sender.isRunning) return@onMain
            if (surface != null || encodedSender?.isRunning == true) {
                val display = CarPlusVideo.Size(displayWidth, displayHeight)
                val viewport = if (scaler != null) CarPlusVideo.fit(sourceSize, display)
                else CarPlusVideo.Viewport(0, 0, displayWidth, displayHeight)
                send(CarPlusReceiverService.touchMessage(contacts, viewport))
            }
        }
    }

    fun stop(stopNative: Boolean = false) = onMain {
        if (BuildConfig.CAR_PLUS_RUNTIME) {
            stopAudio()
            stopVideo(encodedSender)
            val generation = bindingGeneration
            pendingStop = stopNative && send(
                Message.obtain(null, CarPlusReceiverService.STOP, 1, generation).apply { replyTo = replies },
            )
            if (pendingStop) {
                main.postDelayed({
                    if (pendingStop && generation == bindingGeneration) unbind()
                }, 400L)
            } else {
                send(Message.obtain(null, CarPlusReceiverService.STOP))
                unbind()
            }
            surface = null
            directDecoder = null
            scaler?.close()
            scaler = null
            refreshPending = false
            videoRequest = CarPlusVideo.Request()
            updateCastProgress()
            status = "ICCOA 接收端已停止 · 点此启动"
        }
    }

    fun startAudio() = onMain {
        if (audio == null && (surface != null || encodedSender?.isRunning == true) &&
            SessionController.audioSender?.isRunning == true
        ) {
            audio = CarPlusAudio(bindingContext)
            sendAudioTarget()
        }
    }

    fun stopAudio() = onMain {
        audio?.close()
        audio = null
        sendAudioTarget()
    }

    fun hasRecentMusic(): Boolean = audio?.hasRecentMusic() == true

    private fun sendAudioTarget() {
        send(Message.obtain(null, CarPlusReceiverService.AUDIO_TARGET).apply { replyTo = audio?.endpoint })
    }

    fun hasSystemCarPlus(context: Context): Boolean =
        BuildConfig.CAR_PLUS_RUNTIME && CarPlusDiscovery.isAvailable(context)

    fun openSystemCarPlus(context: Context, onFailure: () -> Unit) =
        CarPlusDiscovery.openGuide(context, onFailure)

    fun requestReconnect(): Boolean =
        SessionController.videoSender?.isRunning == true &&
            send(Message.obtain(null, CarPlusReceiverService.RECONNECT_IF_PAIRED))

    private fun startRemote(refresh: Boolean) {
        send(Message.obtain(null, CarPlusReceiverService.START, bindingGeneration, if (refresh) 1 else 0).apply {
            replyTo = replies
            data = videoBundle(videoRequest)
        })
    }

    private fun videoBundle(request: CarPlusVideo.Request): Bundle = Bundle().apply {
        request.canvas?.let {
            putInt(CarPlusReceiverService.VIDEO_WIDTH, it.width)
            putInt(CarPlusReceiverService.VIDEO_HEIGHT, it.height)
        }
        putInt(CarPlusReceiverService.VIDEO_FPS, request.fps)
    }

    private fun sendSurface(surface: Surface?) {
        send(Message.obtain(null, CarPlusReceiverService.SURFACE).apply {
            data = Bundle().apply { putParcelable(CarPlusReceiverService.SURFACE_VALUE, surface) }
        })
    }

    private fun send(message: Message): Boolean {
        val remote = receiver ?: return false
        return try {
            remote.send(message)
            true
        } catch (_: RemoteException) {
            receiver = null
            updateCastProgress()
            status = "ICCOA 接收进程已断开 · 正在恢复"
            false
        }
    }

    private fun unbind() {
        pendingStop = false
        updateCastProgress()
        val app = bindingContext
        bindingContext = null
        receiver = null
        if (app != null) app.unbindService(connection)
    }

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == main.looper) action() else main.post { action() }
    }

    private const val SOURCE_PROBE_RETRY_MS = 120_000L
}
