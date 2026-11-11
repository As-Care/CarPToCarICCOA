package io.github.shaonialife.carptocar.bt

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import io.github.shaonialife.carptocar.iap2.CarPlayNowPlaying
import io.github.shaonialife.carptocar.iap2.Iap2SenderSession
import io.github.shaonialife.carptocar.transport.Iap2LinkChannel
import io.github.shaonialife.carptocar.transport.StreamDuplexStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * 手机侧（iPhone 角色）的 iAP2 蓝牙 RFCOMM 监听。
 *
 * 真实无线 CarPlay 里 iPhone 配对后监听这个 SDP 服务等待车机回连；
 * 测试台上 DiPlay/xcertplay 会扫描已配对的 PHONE_SMART 设备并主动连入。
 *
 * 连入后：链路协商（marker/同步/ACK）→ 完整发送端 bring-up（Phase 1），
 * 成功时回调 [onEndpoint] 交出热点 SSID/密码与 AirPlay 端点。
 */
class Iap2BluetoothServer(
    private val context: Context,
    private val log: (String) -> Unit,
    private val onEndpoint: (Iap2SenderSession.CarPlayEndpoint) -> Unit = {},
    /** 0x5703 一到即触发（绑 WiFi + 广播 _carplay-ctrl），真车 0x4301 的前置条件。 */
    private val onWifiCredentials: (Iap2SenderSession.WifiConfiguration) -> Unit = {},
    /** Phase 1 之后车机补发 0x4301（CarPlay 窗口晚开）时交给主控热更新端点。 */
    private val onEndpointUpdate: (Iap2SenderSession.CarPlayEndpoint) -> Unit = {},
    /** 目标车机（动态读取：拒绝非目标设备的连入；null=来者不拒）。 */
    private val targetProvider: () -> TargetDevicePrefs.Target? = { null },
    /** 「正在播放」信息提供者（歌名/歌手/专辑，见 [Iap2SenderSession]）。 */
    private val nowPlayingProvider: (() -> CarPlayNowPlaying?)? = null,
    /** 保活泵退出（BT 链路断开）时回调：主控据此释放「会话处理中」（僵尸轮次堵门修复）。 */
    private val onLinkClosed: () -> Unit = {},
    /**
     * 有设备真正连进来（通过目标校验之后）时回调。
     *
     * 「兼容模式」下监听是常开的（App 一启动就发布服务，不等「启动连接」），
     * 主控据此把「只是挂着等」和「真的在跑会话」区分开——否则 `isSessionRunning`
     * 会一直为真，自动连接会被自己挡掉。
     */
    private val onPeerConnected: () -> Unit = {},
) {
    companion object {
        /**
         * iPhone 的 iAP2 服务 UUID（Oligo Security 公布；与 xcertplay
         * CarPlayController.IAP2_IPHONE_UUID 一致）。配件端是 …decacaff。
         */
        val IPHONE_IAP2_UUID: UUID = UUID.fromString("00000000-deca-fade-deca-deafdecacafe")
        private const val SDP_NAME = "iAP2"
        private const val SESSION_TIMEOUT_MILLIS = 60_000L
        // 接收端只回探测字节、不回应 SYN 时尽早让出入站闸门，主动连接才有机会接手。
        private const val LINK_TIMEOUT_MILLIS = 12_000L
        private const val RECONNECT_CHANNEL_TIMEOUT_MILLIS = 60_000L
    }

    private val serverSocket = AtomicReference<BluetoothServerSocket?>()
    private val main = Handler(Looper.getMainLooper())
    private val releasePausedChannel = Runnable {
        synchronized(this) {
            if (!accepting) stop()
        }
    }

    /**
     * 「车机只认苹果设备」兼容模式的占位 SDP 记录（默认关，见 [BtCompatPrefs]）。
     * 生命周期跟着 [start]/[stop] 走——监听在，占位就在。
     */
    private val identityProbe = BtIdentityProbeSockets(context) { line -> log(line) }

    val isProbeRunning: Boolean get() = identityProbe.isRunning

    /**
     * 立刻撤掉占位记录（用户关掉开关时用），**不影响** iAP2 监听。
     *
     * 不能顺手把监听也停掉：这一刀可能落在正在跑的会话上（车机全靠这条 RFCOMM
     * 做控制通道），开关的语义只是「别再声明苹果 UUID」，不是「断开」。
     */
    fun stopProbe() {
        if (!identityProbe.isRunning) return
        identityProbe.stop()
        log("BT-PROBE: 占位 SDP 记录已撤下（iAP2 监听保持）")
    }

    @Volatile
    private var activeChannel: Iap2LinkChannel? = null

    /** 当前保活阶段的发送端会话（bring-up 完成后由 runSenderSession 回填）。 */
    @Volatile
    private var senderSession: Iap2SenderSession? = null

    @Volatile
    private var accepting = false

    val isRunning: Boolean get() = accepting && serverSocket.get() != null

    /** 歌名变化时主动补推 0x5001（转发给当前保活会话）。 */
    fun pushNowPlaying() {
        senderSession?.pushNowPlaying()
    }

    /**
     * 车机 AirPlay 端口一直不开时，让 iAP2 会话重发一次 `0x4e0e + 0x4300` 叫醒它。
     *
     * 返回 false = 当前没有可用的发送端会话（链路已断/还没起来），调用方只能退而重公告 ctrl。
     */
    fun nudgeCarPlayAvailability(reason: String): Boolean =
        senderSession?.nudgeCarPlayAvailability(reason) ?: false

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    @Synchronized
    fun start(): Boolean {
        main.removeCallbacks(releasePausedChannel)
        if (serverSocket.get() != null) {
            if (!accepting) log("BT: 继续监听，复用上次的 RFCOMM 频道")
            accepting = true
            syncIdentityProbe()
            return true
        }
        if (!hasPermission()) {
            log("BT: 缺少 BLUETOOTH_CONNECT 权限")
            return false
        }
        val adapter =
            try {
                context.getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter
            } catch (_: Exception) {
                null
            }
        if (adapter == null || !adapter.isEnabled) {
            log("BT: 蓝牙不可用或未开启")
            return false
        }
        return try {
            val server = adapter.listenUsingRfcommWithServiceRecord(SDP_NAME, IPHONE_IAP2_UUID)
            serverSocket.set(server)
            accepting = true
            log("BT: 监听 $SDP_NAME，等待接收端回连…")
            syncIdentityProbe()
            Thread({ acceptLoop(server) }, "iap2-bt-accept").apply {
                isDaemon = true
                start()
            }
            true
        } catch (error: IOException) {
            log("BT: 监听失败 - ${error.message}")
            false
        }
    }

    /** 占位记录跟随开关：开着补挂、关掉撤下。失败不影响 iAP2 主链路。 */
    private fun syncIdentityProbe() {
        if (BtCompatPrefs.enabled(context)) {
            if (!identityProbe.isRunning) identityProbe.start()
        } else {
            stopProbe()
        }
    }

    @SuppressLint("MissingPermission")
    private fun acceptLoop(server: BluetoothServerSocket) {
        while (serverSocket.get() === server) {
            val socket = try {
                server.accept()
            } catch (error: IOException) {
                synchronized(this) {
                    if (serverSocket.compareAndSet(server, null)) {
                        if (accepting) log("BT: accept 失败 - ${error.message}")
                        accepting = false
                        identityProbe.stop()
                        runCatching { server.close() }
                    }
                }
                return
            }
            if (!accepting) {
                runCatching { socket.close() }
                continue
            }
            val peerName =
                try {
                    socket.remoteDevice?.name
                } catch (_: SecurityException) {
                    null
                } ?: "unknown"
            // 指定了目标车机时拒绝其它设备的连入（用户配对了多台车机：非目标车机
            // 的控制器也会周期回连，放行会抢占/误建会话）。地址比对，大小写不敏感。
            val target = targetProvider()
            val peerAddress =
                try {
                    socket.remoteDevice?.address
                } catch (_: SecurityException) {
                    null
                }
            if (target != null &&
                !BtSessionGate.targetSoftFallback &&
                !TargetDevicePrefs.matches(target, peerAddress)
            ) {
                log(
                    "BT: 忽略非目标设备连入 name=$peerName addr=${peerAddress ?: "?"}" +
                        "（当前目标：「${target.name}」）",
                )
                runCatching { socket.close() }
                continue
            }
            val channel = synchronized(this) {
                if (!accepting || serverSocket.get() !== server || activeChannel != null) {
                    runCatching { socket.close() }
                    null
                } else {
                    try {
                        val stream = StreamDuplexStream(socket.inputStream, socket.outputStream) {
                            runCatching { socket.close() }
                        }
                        Iap2LinkChannel(stream, name = "iap2-bt") { line -> log(line) }.also {
                            activeChannel = it
                            BtSessionGate.inboundActive = true
                            log("BT: 接收端已连入 name=$peerName，开始 iAP2 发送端会话")
                            onPeerConnected()
                        }
                    } catch (error: IOException) {
                        runCatching { socket.close() }
                        log("BT: 无法打开接收端控制通道 - ${error.message}")
                        null
                    }
                }
            } ?: continue
            Thread({ runSession(socket, channel) }, "iap2-bt-session").apply {
                isDaemon = true
                start()
            }
        }
    }

    private fun runSession(socket: BluetoothSocket, channel: Iap2LinkChannel) {
        try {
            channel.runSenderSession(
                context,
                SESSION_TIMEOUT_MILLIS,
                log,
                { if (activeChannel === channel) onEndpoint(it) },
                { if (activeChannel === channel) onWifiCredentials(it) },
                { if (activeChannel === channel) onEndpointUpdate(it) },
                nowPlayingProvider,
                onSessionReady = { session ->
                    synchronized(this) {
                        if (activeChannel === channel) senderSession = session
                    }
                },
                onLinkClosed = { if (activeChannel === channel) onLinkClosed() },
                remoteDevice = socket.remoteDevice,
                linkTimeoutMillis = LINK_TIMEOUT_MILLIS,
            )
        } catch (error: IOException) {
            log("SESSION 失败: ${error.message}")
        } finally {
            synchronized(this) {
                if (activeChannel === channel) {
                    activeChannel = null
                    senderSession = null
                    BtSessionGate.inboundActive = false
                }
            }
            channel.close()
            // 两条完全不同的撤退路径，日志必须能分辨（否则用户以为「蓝牙连上就断 = App 有 bug」）：
            //  - sessionActive=true：AirPlay 已在 WiFi 上跑起来，接收端把 iAP2 迁到控制通道
            //    隧道（VirtualIap2Link）后**主动关掉 RFCOMM**。WinPlay 1.2.4 实测：
            //    20:07:31 连入 → 20:07:32.4 隧道建立 → 32.52 对端 close（read -1）。
            //    这是无线 CarPlay 的正常交接，画面/音频/触摸全程不受影响。
            //  - sessionActive=false：会话在拿到端点前就断了，才是真故障，等重连。
            log(
                if (BtSessionGate.sessionActive) {
                    "SESSION 结束——接收端已断开 RFCOMM，iAP2 已迁到 WiFi 隧道" +
                        "（无线 CarPlay 正常交接，继续推流；BT 监听仍在，需要时会再连）"
                } else {
                    "SESSION 结束，等待接收端重连（BT 监听仍在运行）"
                },
            )
        }
    }

    /** 主动结束当前 BT 控制会话（不影响监听）。用于 AirPlay 配对前复现控制通道退役状态。 */
    @Synchronized
    fun detachCurrentSession() {
        // 断链之前先把 EA 会话收干净：车机侧没被显式停掉的 EAP/VIWI 会话会挡住下一次连接
        senderSession?.closeEaSessions("主动断开控制会话")
        senderSession = null
        activeChannel?.let { channel ->
            activeChannel = null
            BtSessionGate.inboundActive = false
            channel.close()
            log("BT: 已主动断开当前控制会话（模拟 tunnel handoff 后的退役）")
        }
    }

    /** Keep the channel briefly for AllPlay instances configured with a fixed RFCOMM channel. */
    @Synchronized
    fun pauseForReconnect() {
        if (serverSocket.get() == null) return
        accepting = false
        identityProbe.stop()
        detachCurrentSession()
        main.removeCallbacks(releasePausedChannel)
        main.postDelayed(releasePausedChannel, RECONNECT_CHANNEL_TIMEOUT_MILLIS)
        log("BT: 会话已断开，短时保留 AllPlay 的 RFCOMM 频道")
    }

    @Synchronized
    fun stop() {
        main.removeCallbacks(releasePausedChannel)
        accepting = false
        identityProbe.stop()
        serverSocket.getAndSet(null)?.let { runCatching { it.close() } }
        detachCurrentSession()
        log("BT: 监听已停止")
    }
}
