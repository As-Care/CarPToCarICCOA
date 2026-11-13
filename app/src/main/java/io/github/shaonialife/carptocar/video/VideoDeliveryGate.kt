package io.github.shaonialife.carptocar.video

/**
 * Tracks queue age and socket write time for forwarded H.264 frames.
 * Sustained backlog waits for a new IDR so the receiver can resume with valid references.
 * Queue age is measured relative to the smallest observed clock offset.
 */
internal class VideoDeliveryGate(
    private val staleMs: Long = STALE_MS,
    private val hardStaleMs: Long = HARD_STALE_MS,
    private val sustainMs: Long = SUSTAIN_MS,
    private val slowWriteMs: Long = SLOW_WRITE_MS,
    private val stallWriteMs: Long = STALL_WRITE_MS,
    private val resyncCooldownMs: Long = RESYNC_COOLDOWN_MS,
    private val summaryIntervalMs: Long = SUMMARY_INTERVAL_MS,
) {
    /** 送完一帧之后的处置结果。 */
    class Verdict(
        val resync: Boolean,
        val reason: String? = null,
    )

    /** 一个窗口（默认 5 秒）的送帧画像。 */
    class Summary(
        val windowMs: Long,
        val frames: Int,
        val backlogP50Ms: Long,
        val backlogP95Ms: Long,
        val backlogMaxMs: Long,
        val writeP95Ms: Long,
        val writeMaxMs: Long,
        val slowWrites: Int,
        val stalls: Int,
        val resyncs: Int,
        val timestampUsable: Boolean,
        val backlogSamples: Int,
    ) {
        fun describe(): String {
            val seconds = "%.1f".format(windowMs / 1000.0)
            val queue =
                when {
                    !timestampUsable -> "排队未测（帧时间戳不可信）"
                    backlogSamples == 0 -> "排队未测（本窗口没有帧时间戳）"
                    else -> "排队 p50=${backlogP50Ms} p95=${backlogP95Ms} max=${backlogMaxMs}ms"
                }
            return "VIDEO: 送帧统计 $seconds" + "s/${frames}帧 $queue" +
                "；写入 p95=${writeP95Ms} max=${writeMaxMs}ms" +
                "；慢写 $slowWrites 次" +
                (if (stalls > 0) "（其中卡顿 $stalls 次）" else "") +
                "；重同步 $resyncs 次"
        }
    }

    // ---- 时间戳基线 ----

    /** `now - ts` 的历史最小值；[Long.MAX_VALUE] = 还没样本。 */
    private var baselineOffset = Long.MAX_VALUE
    private var lastSourceTs = Long.MIN_VALUE
    private val probeTimestamps = HashSet<Long>()
    private var probeFrames = 0
    private var nonMonotonic = 0

    /** 时间戳是否可信。一旦判死本流不再复活（同一路码流的字段语义不会中途变）。 */
    private var timestampUsable = true

    // ---- 状态机 ----

    private var staleSince = 0L
    private var lastResyncAt = Long.MIN_VALUE
    private var resyncTotal = 0

    // ---- 当前统计窗口 ----

    private var windowStart = 0L
    private val windowBacklog = ArrayList<Long>(128)
    private val windowWrite = ArrayList<Long>(128)
    private var windowSlow = 0
    private var windowStall = 0

    /**
     * 记一帧并给出处置。
     *
     * @param nowMs 送完这一帧的时刻（`SystemClock.uptimeMillis()`）
     * @param sourceTsMs 源视频帧时间戳（毫秒）；`null`/`<=0` = 取不到
     * @param writeMs 本帧写入 socket + flush 的耗时
     * @param keyFrame 本帧是否关键帧（关键帧本身不触发重同步——重同步就是在等它）
     */
    @Synchronized
    fun onFrame(
        nowMs: Long,
        sourceTsMs: Long?,
        writeMs: Long,
        keyFrame: Boolean,
    ): Verdict {
        if (windowStart == 0L) windowStart = nowMs
        windowWrite.add(writeMs)
        if (writeMs >= slowWriteMs) windowSlow++
        if (writeMs >= stallWriteMs) windowStall++

        val backlog = backlogOf(nowMs, sourceTsMs)
        if (backlog != null) windowBacklog.add(backlog)

        if (backlog == null) {
            staleSince = 0L
            return PASS
        }
        if (backlog < staleMs) {
            staleSince = 0L
            return PASS
        }
        if (staleSince == 0L) staleSince = nowMs
        val sustained = nowMs - staleSince
        val cooled = lastResyncAt == Long.MIN_VALUE || nowMs - lastResyncAt >= resyncCooldownMs
        if (backlog >= hardStaleMs && sustained >= sustainMs && !keyFrame && cooled) {
            val reason =
                "排队延迟 ${backlog}ms（阈值 ${hardStaleMs}ms）已持续 ${sustained}ms" +
                    "——画面比编码器慢这么多，跳到下一个 IDR 重新对齐"
            lastResyncAt = nowMs
            resyncTotal++
            // 跳到 IDR 之后延迟会归零，旧基线作废：留着重同步完的第一帧就会算出巨量延迟。
            baselineOffset = Long.MAX_VALUE
            lastSourceTs = Long.MIN_VALUE
            staleSince = 0L
            return Verdict(resync = true, reason = reason)
        }
        return PASS
    }

    /** 新会话 / 源重启时清空基线：新一路码流的时间戳基准与排队情况都从头算。 */
    @Synchronized
    fun reset() {
        baselineOffset = Long.MAX_VALUE
        lastSourceTs = Long.MIN_VALUE
        probeTimestamps.clear()
        probeFrames = 0
        nonMonotonic = 0
        timestampUsable = true
        staleSince = 0L
        resetWindow(0L)
    }

    /** 到点就返回本窗口画像并开新窗口；没到点返回 null。 */
    @Synchronized
    fun summaryIfDue(nowMs: Long): Summary? {
        if (windowStart == 0L) return null
        val span = nowMs - windowStart
        if (span < summaryIntervalMs) return null
        val summary =
            Summary(
                windowMs = span,
                frames = windowWrite.size,
                backlogP50Ms = percentile(windowBacklog, 0.50),
                backlogP95Ms = percentile(windowBacklog, 0.95),
                backlogMaxMs = windowBacklog.maxOrNull() ?: 0L,
                writeP95Ms = percentile(windowWrite, 0.95),
                writeMaxMs = windowWrite.maxOrNull() ?: 0L,
                slowWrites = windowSlow,
                stalls = windowStall,
                resyncs = resyncTotal,
                timestampUsable = timestampUsable,
                backlogSamples = windowBacklog.size,
            )
        resyncTotal = 0
        resetWindow(nowMs)
        return summary
    }

    private fun resetWindow(startedAt: Long) {
        windowStart = startedAt
        windowBacklog.clear()
        windowWrite.clear()
        windowSlow = 0
        windowStall = 0
    }

    private fun backlogOf(
        nowMs: Long,
        sourceTsMs: Long?,
    ): Long? {
        if (!timestampUsable) return null
        val ts = sourceTsMs ?: return null

        if (probeFrames < PROBE_FRAMES) {
            probeFrames++
            // 0 也计入：字段恒为 0 正是「这不是时间戳」最典型的样子。
            probeTimestamps.add(ts)
            if (probeFrames == PROBE_FRAMES && probeTimestamps.size < MIN_DISTINCT_TS) {
                // 20 帧里只有一两个取值：不是时间戳。
                timestampUsable = false
                return null
            }
        }
        if (ts <= 0L) return null
        if (lastSourceTs != Long.MIN_VALUE && ts < lastSourceTs) {
            nonMonotonic++
            if (nonMonotonic > NON_MONOTONIC_LIMIT) {
                timestampUsable = false
                return null
            }
        }
        lastSourceTs = ts

        val offset = nowMs - ts
        if (offset < baselineOffset) {
            // 基线只会往下走：最小 offset 代表「这一帧几乎没排队」的时刻。
            baselineOffset = offset
            return 0L
        }
        val backlog = offset - baselineOffset
        if (backlog > SANITY_MAX_MS) {
            // 半分钟级别的「排队」不是排队，是字段根本不是这个意思。停用，别拿它动画面。
            timestampUsable = false
            return null
        }
        return backlog
    }

    private fun percentile(
        values: List<Long>,
        fraction: Double,
    ): Long {
        if (values.isEmpty()) return 0L
        val sorted = values.sorted()
        val index = ((sorted.size - 1) * fraction).toInt().coerceIn(0, sorted.size - 1)
        return sorted[index]
    }

    companion object {
        /** 超过这个帧龄就算「已经不新鲜」。取 CarVeX `VideoDeliveryGate.MAX_AGE_MS`。 */
        const val STALE_MS = 150L

        /**
         * 硬阈值：延迟真的到这一步就不是抖动，是排队排死了。
         *
         * 比 CarVeX 的 150ms 宽一个数量级——他们敢在 150ms 丢帧是因为丢了能马上要 IDR，
         * 我们只能等，动作代价完全不同。
         */
        const val HARD_STALE_MS = 1_500L

        /** 必须持续这么久才算「结构性」，避免一次 GC 停顿就重同步。 */
        const val SUSTAIN_MS = 2_000L

        /** 单帧写入超过这个时间算一次慢写。取 CarVeX `VideoRatePolicy` 写阻塞判据。 */
        const val SLOW_WRITE_MS = 80L

        /** 单帧写入超过这个时间算一次卡顿（socket 真的堵住了）。 */
        const val STALL_WRITE_MS = 400L

        /** 两次重同步之间的最小间隔：重同步本身要丢 1 个 GOP，不能连着来。 */
        const val RESYNC_COOLDOWN_MS = 10_000L

        /** 送帧统计窗口。 */
        const val SUMMARY_INTERVAL_MS = 5_000L

        private const val PROBE_FRAMES = 20
        private const val MIN_DISTINCT_TS = 3
        private const val NON_MONOTONIC_LIMIT = 5

        /** 排队延迟的物理上限（30 秒）——超过就当字段不可信。 */
        private const val SANITY_MAX_MS = 30_000L

        private val PASS = Verdict(resync = false)
    }
}
