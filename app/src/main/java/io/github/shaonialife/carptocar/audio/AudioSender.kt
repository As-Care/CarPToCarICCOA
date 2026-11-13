package io.github.shaonialife.carptocar.audio

import io.github.shaonialife.carptocar.BuildConfig
import io.github.shaonialife.carptocar.airplay.AirPlayCrypto
import io.github.shaonialife.carptocar.airplay.TimingServer
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean

/**
 * CarPlay 主音频发送端（手机 → 车机，RTP over UDP）。
 *
 * 线格式（对照 DiPlay `AudioStream.kt` 解密路径与 catplay `rtp_cipher_chacha.rs`）：
 * ```
 * [RTP 头 12B][ChaCha20-Poly1305 密封的 PCM 负载 + 16B tag][8B LE nonce]
 * ```
 *  - 加密：ChaCha20-Poly1305；AAD = 头 4..12 字节（timestamp+ssrc 的 BE 表示，
 *    catplay 的 "modern AAD"；DiPlay 解密即取同一范围）
 *  - nonce（12B）= 4 字节零 + 尾 8 字节（小端计数，从 0 逐包递增）→ [AirPlayCrypto.nonce64]
 *  - RTP 字段：version=2、PT=流类型（100=PCM 通路 / 102=AAC-LC 通路；
 *    对照 catplay `payload_type = stream_type`）、
 *    SSRC=0（catplay 同款）、timestamp = 累计样本帧数、seq 逐包递增
 *  - **首个音频包置 Marker 位**（规范：RECORD/FLUSH 之后的首包要打标记，接收端据此
 *    切分播放边界；catplay 恒 false，但我们的会话是「RECORD 之后才加入音频流」，
 *    首包标记能帮接收端重新对齐）
 *
 * 旁路：
 *  - **同步包**：[AudioSyncPacket] 每 1 秒发往该流的 `controlPort`，建立 RTP↔NTP 锚点。
 *    （缺了它接收端只能「按到达时刻猜」，严格的实现干脆不排播——2026-10-20 实测）
 *
 * ICCOA supplies PCM samples through [feedPcm].
 */
class AudioSender(
    private val host: String,
    private val dataPort: Int,
    private val streamKey: ByteArray,
    private val log: (String) -> Unit,
    /**
     * 发送采样率（Hz）：必须与 SETUP 协商出的格式一致（48k 主选，车机只支持
     * 44.1k 时由 AirPlayClient 协商下发）。默认 48k。
     */
    private val sampleRate: Int = 48_000,
    /** 负载编码：PCM（type=100 通路）或 AAC-LC（type=102 通路）。 */
    private val codec: AudioCodec = AudioCodec.PCM,
    /** RTP 流类型，仅用于日志（PT 与 SETUP 的 type 保持一致）。 */
    private val streamType: Long = 100L,
    /** AAC 帧是否补 ADTS 头（见 [AudioTuning.ADTS]，排查车机解码链用）。 */
    private val adtsFrames: Boolean = false,
    /**
     * 该音频流的控制端口（车机在 SETUP 应答里下发）。>0 时每 [SYNC_INTERVAL_MILLIS]
     * 发一个 [AudioSyncPacket] 建立 RTP↔NTP 锚点；0 = 车机没给（旧车机），只发音频。
     */
    private val controlPort: Int = 0,
) {
    companion object {
        const val CHANNELS = 2

        /**
         * 旧版使用 352 帧。ICCOA 在奥迪实测收到 EMSGSIZE，改为每包 288 帧。
         * 16 位立体声负载 1152 字节，加上 RTP、加密、UDP 和 IPv6 头共 1236 字节。
         */
        val SAMPLES_PER_PACKET = if (BuildConfig.CAR_PLUS_AUDIO_RELAY) 288 else 352

        /** 一包负载字节数（16bit 立体声）。 */
        val PACKET_BYTES = SAMPLES_PER_PACKET * CHANNELS * 2

        /** 同步包发送间隔（规范：每秒一个）。 */
        const val SYNC_INTERVAL_MILLIS = 1_000L

        /** 静音保活的检查节拍。 */
        private const val KEEPALIVE_TICK_MILLIS = 100L
        private const val AUDIO_TRAFFIC_CLASS = 46 shl 2 // DSCP EF in the IP traffic class byte.
        private const val AUDIO_SEND_GAP_MS = 80L
        private const val AUDIO_GAP_LOG_INTERVAL_MS = 3_000L

        /**
         * 多久没有真实 PCM 就开始补静音帧。
         *
         * 取 300ms 而不是 0：正常音乐播放时 7~23ms 一帧，压根不会触发；而「暂停/切歌/
         * 导航播报间隙」这种短暂停顿也不至于插静音。真正要救的是「连接后长时间没人
         * 放音乐」这种一分钟级的空转。
         */
        private const val IDLE_FILL_AFTER_MILLIS = 300L

        /** 单拍最多补几帧（≈[KEEPALIVE_TICK_MILLIS]/帧长，留一点追赶余量，避免卡顿后猛灌）。 */
        private const val MAX_KEEPALIVE_FRAMES_PER_TICK = 12

        /** 一次 `sendSyncOnce` 内最多尝试次数（ICMP 端口不可达时就地补发一次）。 */
        private const val SYNC_SEND_ATTEMPTS = 2

        /**
         * 是否是「车机 controlPort 没监听」引起的 ICMP 端口不可达。
         *
         * Android 抛的是 `SocketException: sendto failed: ECONNREFUSED (Connection refused)`，
         * 部分实现抛 [java.net.PortUnreachableException]，两种都算。这类错误对连接式 UDP
         * **是延后一拍上报的**（本次 send 抛出，说的其实是上一发），所以不能当链路故障。
         */
        internal fun isSyncIcmpRejection(error: Exception): Boolean {
            if (error is java.net.PortUnreachableException) return true
            val message = error.message?.uppercase() ?: return false
            return "ECONNREFUSED" in message || "PORT UNREACHABLE" in message
        }

        private const val RTP_HEADER_LEN = 12
        private const val TAG_SIZE = 16
        private const val NONCE_SIZE = 8

        /** 小端 s16 → 大端 s16（每两字节换序）。CarPlay 线格式要求大端样本。 */
        fun bigEndianS16(source: ByteArray): ByteArray {
            val out = source.copyOf()
            var index = 0
            while (index + 1 < out.size) {
                val tmp = out[index]
                out[index] = out[index + 1]
                out[index + 1] = tmp
                index += 2
            }
            return out
        }
    }

    private val running = AtomicBoolean(false)

    @Volatile
    var hadSendFailure = false
        private set

    /** stop() 幂等闸门（多条收尾路径都会调 stop）。 */
    private val stopped = AtomicBoolean(false)
    private var socket: DatagramSocket? = null
    private var syncSocket: DatagramSocket? = null
    private var syncThread: Thread? = null

    /** 同步包序号（跨重试连续，避免车机把重发当成乱序丢掉）。 */
    private var syncSequence = 0

    /** 同步包连续失败次数（诊断 + 决定何时重开套接字）。 */
    private var syncFailures = 0

    /** 成功发出的同步包数（收尾汇总用）。 */
    @Volatile private var syncSent = 0L

    /** 被车机 ICMP 端口不可达拒掉、已就地补发成功的次数（= 车机没监听 controlPort）。 */
    @Volatile private var syncIcmpDeferrals = 0L

    /** 首个同步包（X 位=1）是否已成功发出。 */
    private var syncFirstSent = false

    /** AAC-LC 编码器（仅 [AudioCodec.AAC_LC] 时创建）。 */
    private var aacEncoder: AacLcEncoder? = null

    /**
     * RTP 游标。**同步包线程**要读
     * 「当前/下一包时间戳」，否则锚点里的时间戳永远对不上真实发出的包。
     */
    @Volatile private var sequence = 0

    @Volatile private var timestamp = 0L

    @Volatile private var nonceCounter = 0L

    /** 首个音频包标记（打完就清）；AAC 自检只跑一次。 */
    private var markerPending = true

    /** 每包/每帧对应的 PCM 字节数（PCM 通路=一包的负载；AAC 通路=一帧的输入）。 */
    private val framePcmBytes: Int
        get() = if (codec == AudioCodec.AAC_LC) AacLcEncoder.pcmBytes(CHANNELS) else PACKET_BYTES

    /** 每包推进的样本数，与音频 SETUP 的 spf 一致。 */
    private val samplesPerPacket: Long
        get() = if (codec == AudioCodec.AAC_LC) AacLcEncoder.FRAME_SAMPLES.toLong() else SAMPLES_PER_PACKET.toLong()

    /**
     * RTP SSRC：固定 0（catplay `prepare_header` 在真车上即用 0；DiPlay 收端不校验）。
     * 2026-10-17 实测：此前用随机值，奥迪接收端无声音——部分车机按 SSRC 关联 SETUP
     * 声明的流（非 0 视为未知流丢弃）。
     */
    private val ssrc = 0

    /** 推流统计。 */
    private var packetsSent = 0L
    private var bytesSent = 0L
    private var lastPacketSentAt = 0L
    private var lastSendGapLoggedAt = 0L

    val isRunning: Boolean get() = running.get()

    /** Starts the negotiated RTP stream. ICCOA supplies the samples through [feedPcm]. */
    fun startPassthrough(keepAlive: Boolean = true): Boolean {
        if (running.get()) return true
        stopped.set(false)
        return try {
            val client = DatagramSocket()
            socket = client
            if (BuildConfig.CAR_PLUS_AUDIO_RELAY) {
                runCatching { client.trafficClass = AUDIO_TRAFFIC_CLASS }
                    .onFailure { log("AUDIO: 无法设置 UDP 音频优先级，继续使用默认优先级") }
            }
            client.connect(InetAddress.getByName(host), dataPort)
            pendingPcmBytes = 0
            lastPacketSentAt = 0L
            lastSendGapLoggedAt = 0L

            if (codec == AudioCodec.AAC_LC) {
                val encoder = AacLcEncoder(sampleRate, CHANNELS, wrapAdts = adtsFrames, log = log)
                if (!encoder.start()) {
                    client.close()
                    socket = null
                    return false
                }
                aacEncoder = encoder
            }

            running.set(true)
            log(
                "AUDIO: 已启动直通模式（外部注入 PCM → $host:$dataPort，" +
                    "通路=t$streamType/${codec.name}，目标 ${sampleRate}Hz 立体声，spf=$samplesPerPacket）",
            )
            startSyncLoop()
            if (keepAlive) startSilenceKeepAlive()
            true
        } catch (error: Exception) {
            log("AUDIO: 直通启动失败 - ${error.message}")
            stop()
            false
        }
    }

    /** 保留不足一帧的尾部，下一次注入时继续填充。由 feedPcm 的锁保护。 */
    private val passthroughFrame by lazy { ByteArray(framePcmBytes) }
    private var pendingPcmBytes = 0

    /** 最近一次收到**真实** PCM 的时刻（uptimeMillis）；静音保活据此判断要不要补帧。 */
    @Volatile
    private var lastRealFeedAt = 0L

    private var keepAliveThread: Thread? = null

    /**
     * 静音保活：音频流建立后**不能长时间一个包都不发**。
     *
     * 2026-11-04 奥迪 MIB3 实车（`docs/audi-mib3-ea-tunnel-20261104.md`）：音频流 SETUP
     * 完成后先是 65 秒完全没有 PCM（车机上没人放音乐），第一段音乐（08:27:39，44.1k
     * 立体声、电平 99%）原样编码发出去了，车机一声不响；而同样的音频在重连后的会话里
     * （流一建立 0.4 秒就有包）立刻正常。差别只有「流建立后先静默了多久」。
     *
     * 真 iPhone 的音频流是**连续**的：没声音也一直送静音帧。接收端据此维持解码/播放管线
     * 与 RTP↔NTP 时钟；停一分钟再突然来音频，管线早已被判空闲，包照收不放。
     *
     * 所以这里在「超过 [IDLE_FILL_AFTER_MILLIS] 没有任何真实 PCM」时按实时速率补静音帧：
     * 作用只是维持管线与时钟，用户听不到任何声音（AAC 静音帧十几字节，开销可忽略）。
     * 真实 PCM 一到就立刻停补，避免在音乐中间插入静音帧。
     */
    private fun startSilenceKeepAlive() {
        if (keepAliveThread != null) return
        lastRealFeedAt = android.os.SystemClock.uptimeMillis()
        val frameBytes = framePcmBytes
        val frameMillis = 1000.0 * samplesPerPacket / sampleRate
        val silence = ByteArray(frameBytes)
        val thread =
            Thread(
                {
                    var nextFillAt = 0L
                    var announced = false
                    while (running.get()) {
                        try {
                            Thread.sleep(KEEPALIVE_TICK_MILLIS)
                        } catch (_: InterruptedException) {
                            break
                        }
                        if (!running.get()) break
                        val now = android.os.SystemClock.uptimeMillis()
                        if (now - lastRealFeedAt < IDLE_FILL_AFTER_MILLIS) {
                            nextFillAt = 0L
                            continue
                        }
                        if (nextFillAt == 0L) {
                            nextFillAt = now
                            if (!announced) {
                                announced = true
                                log(
                                    "AUDIO: 无 PCM 超过 ${IDLE_FILL_AFTER_MILLIS}ms——开始补静音帧保活" +
                                        "（避免车机把音频流判空闲，之后第一段音乐被丢）",
                                )
                            }
                        }
                        var guard = 0
                        while (running.get() &&
                            nextFillAt <= now &&
                            guard < MAX_KEEPALIVE_FRAMES_PER_TICK
                        ) {
                            if (android.os.SystemClock.uptimeMillis() - lastRealFeedAt <
                                IDLE_FILL_AFTER_MILLIS
                            ) {
                                break // 真实 PCM 回来了，本拍不再补
                            }
                            dispatchPcm(silence)
                            nextFillAt += frameMillis.toLong().coerceAtLeast(1L)
                            guard++
                        }
                        // 落后太多就不追了：补的静音没必要和历史对齐，重新从当前时刻起算
                        if (nextFillAt < now) nextFillAt = now
                    }
                },
                "audio-silence-keepalive",
            )
        thread.isDaemon = true
        keepAliveThread = thread
        thread.start()
    }

    /** Accumulates ICCOA PCM into negotiated PCM packets or AAC input frames. */
    @Synchronized
    fun feedPcm(
        srcPcm: ByteArray,
        size: Int,
        srcRate: Int = 0,
        srcChannels: Int = 2,
    ) {
        if (!running.get()) return
        if (size <= 0) return
        // 真实 PCM 到场：静音保活立刻让位（这一行必须在 alignPcm 之前，
        // 否则重采样失败时空转判定会误以为「一直没声音」）
        lastRealFeedAt = android.os.SystemClock.uptimeMillis()
        val resampled = alignPcm(srcPcm, size, srcRate, srcChannels) ?: return
        // 累积到固定帧长，凑满一帧才发（PCM 通路逐包、AAC 通路逐帧）
        val frame = passthroughFrame
        var offset = 0
        while (offset < resampled.size && running.get()) {
            val count = minOf(frame.size - pendingPcmBytes, resampled.size - offset)
            resampled.copyInto(frame, pendingPcmBytes, offset, offset + count)
            offset += count
            pendingPcmBytes += count
            if (pendingPcmBytes == frame.size) {
                pendingPcmBytes = 0
                dispatchPcm(frame)
            }
        }
    }

    /**
     * 把一段 PCM 重采样到 [sampleRate] 立体声，返回新数组；无需处理时返回原输入。
     *
     * 只做两种常见比值的线性重采样（44.1k↔48k）+ 声道扩展，避免引入第三方重采样库：
     * 常见输入采样率是 44.1k 或 48k。其它任意比值退化为按 [sampleRate] 直接
     * 截断/复读，保证不出错（宁可有轻微音高偏移，也不中断推流）。
     */
    private fun alignPcm(
        src: ByteArray,
        size: Int,
        srcRate: Int,
        srcChannels: Int,
    ): ByteArray {
        val inRate = if (srcRate > 0) srcRate else sampleRate
        val inCh = if (srcChannels == 1) 1 else 2

        // 先处理声道：单声道 → 立体声（复制到左右）
        var rateAligned: ByteArray
        var rateChannels = inCh
        if (inCh == 1 && CHANNELS == 2) {
            val monoFrames = size / 2
            val out = ByteArray(monoFrames * 4)
            var i = 0
            var o = 0
            while (i + 1 < size) {
                val lo = src[i]
                val hi = src[i + 1]
                out[o] = lo
                out[o + 1] = hi
                out[o + 2] = lo
                out[o + 3] = hi
                i += 2
                o += 4
            }
            rateAligned = out
            rateChannels = 2
        } else {
            rateAligned = if (size == src.size) src else src.copyOf(size)
        }

        // 采样率一致直接返回
        if (inRate == sampleRate) return rateAligned

        // 线性重采样（frame = 所有声道的一组采样）
        val inFrames = rateAligned.size / (rateChannels * 2)
        val outFrames = (inFrames.toLong() * sampleRate / inRate).toInt()
        val out = ByteArray(outFrames * CHANNELS * 2)
        val chBytes = CHANNELS * 2
        val inChBytes = rateChannels * 2
        for (frame in 0 until outFrames) {
            val srcPos = (frame.toLong() * inRate / sampleRate).toInt()
            val srcByte = srcPos * inChBytes
            val dstByte = frame * chBytes
            if (srcByte + inChBytes <= rateAligned.size) {
                System.arraycopy(rateAligned, srcByte, out, dstByte, inChBytes.coerceAtMost(chBytes))
            }
        }
        return out
    }

    /** 走与采集循环一致的封包/编码/发送路径（[feedPcm] 与采集循环共用）。 */
    private fun dispatchPcm(pcm: ByteArray) {
        try {
            val payloads: List<ByteArray> =
                if (codec == AudioCodec.AAC_LC) {
                    val encoder = aacEncoder ?: return
                    encoder.encode(pcm, pcm.size)
                } else {
                    listOf(pcm)
                }
            if (payloads.isEmpty()) return
            var sendFailed = false
            for (payload in payloads) {
                if (!sendPayload(payload)) {
                    sendFailed = true
                    break
                }
            }
            if (sendFailed) stop()
        } catch (error: Throwable) {
            log("AUDIO: 直通注入异常 - ${error.message}")
            stop()
        }
    }

    /** Stops the audio socket, encoder and keep-alive threads once. */
    fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        running.set(false)
        aacEncoder?.stop()
        aacEncoder = null
        runCatching { socket?.close() }
        socket = null
        runCatching { syncSocket?.close() }
        syncSocket = null
        syncThread = null
        keepAliveThread?.interrupt()
        keepAliveThread = null
        if (syncSent > 0 && syncIcmpDeferrals > 0) {
            log(
                "AUDIO: 同步包共发出 $syncSent 个，其中 $syncIcmpDeferrals 次被车机 controlPort 的 " +
                    "ICMP 拒绝（已就地补发，锚点未丢）",
            )
        }
        log("AUDIO: 已停止（共发 $packetsSent 包 / ${bytesSent / 1024}KB）")
    }

    // ---- 音频同步包（RTP 时间戳 ↔ NTP，每秒一个） ----

    /**
     * 打开同步包套接字（连接式 UDP：连上之后才能收到 ICMP 端口不可达）。
     * 失败返回 null（例如车机下发的 controlPort 非法）。
     */
    private fun openSyncSocket(): DatagramSocket? =
        try {
            DatagramSocket().apply { connect(InetAddress.getByName(host), controlPort) }
        } catch (error: Exception) {
            log("AUDIO: 同步包套接字创建失败 - ${error.message}")
            null
        }

    /**
     * 发一个同步包。**失败不终止同步线程，也不放弃锚点**。
     *
     * 2026-10-26 实证（奥迪 MIB3）：音频 SETUP 后 1 秒，第一个同步包引发
     * `ECONNREFUSED`（车机此刻还没在 controlPort 上监听），旧实现 `catch` 后直接
     * 退出线程 —— 整场会话再没有任何同步包，RTP↔NTP 锚点永久缺失。
     *
     * 2026-11-01 实证（小米 → 奥迪 MIB3，10 分钟日志）：现象不是「一次就好」，而是
     * 恒定的「失败 1 次 → 恢复 → 失败 1 次」交替 —— 说明车机 controlPort 全程没监听，
     * 每一包都被回 ICMP 端口不可达；而连接式 UDP 把这个错误**延后一拍**上报（本次 send
     * 抛异常，说的其实是上一发）。后果是锚点密度只有设计值的一半，日志每秒刷两行。
     *
     * 现在的处理：识别出 ICMP 端口不可达后**在同一条套接字上立刻重发一次**——挂起的
     * 错误已被取走，补发必成；只有真正的发送失败（链路断）才计入连续失败。
     */
    @Synchronized
    private fun sendSyncOnce(first: Boolean): Boolean {
        val client = syncSocket ?: return false
        var deferred = false
        for (attempt in 0 until SYNC_SEND_ATTEMPTS) {
            val next = timestamp
            val current = (next - samplesPerPacket).coerceAtLeast(0L)
            try {
                val ntp = TimingServer.ntpFromNanos(System.nanoTime())
                val packet = AudioSyncPacket.build(syncSequence, current, next, ntp, first)
                client.send(DatagramPacket(packet, packet.size))
                syncSequence = (syncSequence + 1) and 0xFFFF
                syncSent++
                if (deferred) {
                    syncIcmpDeferrals++
                    if (syncIcmpDeferrals == 1L) {
                        log(
                            "AUDIO: 车机 controlPort 未监听（ICMP 端口不可达）——每包延后一拍报错，" +
                                "已就地在同一套接字补发，锚点仍每秒一包（此提示只打一次）",
                        )
                    }
                }
                if (syncFailures > 0) {
                    log("AUDIO: 同步包恢复（连续失败 $syncFailures 次后发出）——锚点重建")
                    syncFailures = 0
                }
                return true
            } catch (error: Exception) {
                if (attempt + 1 < SYNC_SEND_ATTEMPTS && isSyncIcmpRejection(error)) {
                    deferred = true
                    continue
                }
                syncFailures++
                if (syncFailures <= 3 || syncFailures % 60 == 0) {
                    log(
                        "AUDIO: 同步包发送失败（第 $syncFailures 次）- ${error.message}" +
                            "——继续每秒重试，不放弃锚点",
                    )
                }
                // 会话已收尾时不要重开（否则 socket 泄漏在 stop 之后）
                if (syncFailures % 10 == 0 && running.get()) {
                    runCatching { client.close() }
                    syncSocket = openSyncSocket()
                }
                return false
            }
        }
        return false
    }

    /**
     * 立刻补发一轮同步包，让车机重新对齐 RTP↔NTP。
     *
     * 用在「引擎音频停过一段又恢复」（来电结束、音频路由切回）之后：车机长时间收不到
     * 音频会把锚点判超时，恢复瞬间只按到达时刻猜，容易出现开头几百毫秒被丢/爆音。
     */
    @Synchronized
    fun resyncAnchor(reason: String) {
        if (!running.get() || controlPort <= 0 || syncSocket == null) return
        var sent = 0
        repeat(3) { if (sendSyncOnce(false)) sent++ }
        log("AUDIO: $reason → 补发 $sent 个同步包重新对齐车机时钟")
    }

    private fun startSyncLoop() {
        if (controlPort <= 0) {
            log("AUDIO: 车机未下发 controlPort——不发同步包（只能靠音频包自带时间戳）")
            return
        }
        val client = openSyncSocket() ?: return
        syncSocket = client
        log("AUDIO: 同步包已启用 → $host:$controlPort（每 ${SYNC_INTERVAL_MILLIS}ms 一个，建立 RTP↔NTP 锚点）")
        val worker =
            Thread(
                {
                    // 注意：**不能**在循环条件里判 `client.isClosed`——连续失败时
                    // sendSyncOnce 会把套接字关掉重开，那个判断会让线程直接退出。
                    while (running.get()) {
                        val sent = sendSyncOnce(first = !syncFirstSent)
                        if (sent && !syncFirstSent) {
                            syncFirstSent = true
                            log(
                                "AUDIO: 首个同步包已发出（X 位=1）——" +
                                    "车机若仍无声，说明它没在等这个锚点",
                            )
                        }
                        try {
                            Thread.sleep(SYNC_INTERVAL_MILLIS)
                        } catch (_: InterruptedException) {
                            break
                        }
                    }
                },
                "audio-sync",
            )
        worker.isDaemon = true
        worker.start()
        syncThread = worker
    }

    /** 封包 + 发送一包（同步更新 seq/ts/nonce 游标）。返回 false 表示链路已断。 */
    @Synchronized
    private fun sendPayload(payload: ByteArray): Boolean {
        val wire = sealPacket(payload, sequence, timestamp, nonceCounter, markerPending)
        markerPending = false
        sequence = (sequence + 1) and 0xFFFF
        timestamp += samplesPerPacket
        nonceCounter++
        val client = socket ?: return false
        return try {
            client.send(DatagramPacket(wire, wire.size))
            if (BuildConfig.CAR_PLUS_AUDIO_RELAY) {
                val now = android.os.SystemClock.elapsedRealtime()
                val gap = now - lastPacketSentAt
                if (lastPacketSentAt != 0L && gap > AUDIO_SEND_GAP_MS &&
                    now - lastSendGapLoggedAt >= AUDIO_GAP_LOG_INTERVAL_MS
                ) {
                    log("AUDIO: UDP 发包间隔 ${gap}ms（seq=${(sequence - 1) and 0xFFFF}）")
                    lastSendGapLoggedAt = now
                }
                lastPacketSentAt = now
            }
            packetsSent++
            bytesSent += wire.size
            true
        } catch (error: IOException) {
            if (running.get()) {
                hadSendFailure = true
                log("AUDIO: UDP 发送失败（${wire.size} 字节，spf=$samplesPerPacket） - ${error.message}")
            }
            false
        }
    }

    /** 组一个 RTP 包：明文 PCM → ChaCha20 密封 → [头][密文+tag][nonce8]。（internal：测试直测） */
    internal fun sealPacket(
        pcm: ByteArray,
        sequence: Int,
        timestamp: Long,
        nonceCounter: Long,
        marker: Boolean = false,
    ): ByteArray {
        val header = buildRtpHeader(sequence, timestamp, marker)
        // AAD = header 的 timestamp+ssrc 段（字节 4..12）——catplay "modern AAD" / DiPlay 解密同款
        val aad = header.copyOfRange(4, RTP_HEADER_LEN)
        // 关键：CarPlay 线格式的 LPCM 是**大端** s16 样本——接收端对收到的负载做
        // byteSwapS16 转回小端再喂 AudioTrack（DiPlay AndroidMediaSink.kt:1687）。
        // ICCOA 提供的 PCM 是小端，因此这里必须换序；漏掉会得到「沙沙声」（2026-10-10 实测）。
        //
        // AAC 通路不做换序：AAC 比特流是字节流，不是 16bit 样本序列，换序会直接把
        // 比特流搅烂（车机解码失败 → 有包无声）。
        val wirePayload = if (codec == AudioCodec.AAC_LC) pcm else bigEndianS16(pcm)
        val sealed = AirPlayCrypto.chachaSeal(streamKey, AirPlayCrypto.nonce64(nonceCounter), wirePayload, aad)
        val wire = ByteArray(RTP_HEADER_LEN + sealed.size + NONCE_SIZE)
        header.copyInto(wire, 0)
        sealed.copyInto(wire, RTP_HEADER_LEN)
        // 尾部 8 字节小端 nonce（与 AirPlayCrypto.nonce64 的低 8 字节一致）
        for (index in 0 until NONCE_SIZE) {
            wire[wire.size - NONCE_SIZE + index] =
                ((nonceCounter ushr (8 * index)) and 0xFF).toByte()
        }
        return wire
    }

    private fun buildRtpHeader(
        sequence: Int,
        timestamp: Long,
        marker: Boolean = false,
    ): ByteArray {
        val header = ByteArray(RTP_HEADER_LEN)
        header[0] = 0x80.toByte() // version=2
        // PT 跟随流类型（catplay `payload_type = stream_type`）：100=PCM 通路、102=AAC 通路
        header[1] = ((if (marker) 0x80 else 0) or (streamType.toInt() and 0x7F)).toByte()
        header[2] = ((sequence ushr 8) and 0xFF).toByte()
        header[3] = (sequence and 0xFF).toByte()
        val ts = timestamp.toInt()
        header[4] = ((ts ushr 24) and 0xFF).toByte()
        header[5] = ((ts ushr 16) and 0xFF).toByte()
        header[6] = ((ts ushr 8) and 0xFF).toByte()
        header[7] = (ts and 0xFF).toByte()
        header[8] = ((ssrc ushr 24) and 0xFF).toByte()
        header[9] = ((ssrc ushr 16) and 0xFF).toByte()
        header[10] = ((ssrc ushr 8) and 0xFF).toByte()
        header[11] = (ssrc and 0xFF).toByte()
        return header
    }
}
