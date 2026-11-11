package io.github.shaonialife.carptocar.util

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Keeps the first transport loss and selected events around it, independent of the console buffer. */
internal class ConnectionIncidentTimeline {
    private data class Event(val uptime: Long, val line: String, val message: String)

    private val recent = ArrayDeque<Event>()
    private val following = ArrayDeque<Event>()
    private var before: List<Event> = emptyList()
    private var loss: Event? = null
    private val stamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun record(message: String, uptime: Long, wallTime: Long): String? {
        if (!isRelevant(message)) return null
        val clean = ConnectionIssueSummary.sanitize(message).take(220)
        if (recent.lastOrNull()?.let { it.message == clean && uptime - it.uptime < 2_000 } == true) return null
        val event = Event(uptime, "[${stamp.format(Date(wallTime))}] $clean", clean)
        while (recent.firstOrNull()?.let { uptime - it.uptime > BEFORE_MS } == true) recent.removeFirst()
        val firstLoss = loss
        val inRecovery = firstLoss != null && uptime - firstLoss.uptime <= AFTER_MS
        val changed = when {
            !inRecovery && message.startsWith(TRANSPORT_LOSS) -> {
                before = recent.toList()
                loss = event
                following.clear()
                true
            }
            inRecovery -> {
                if (following.size == AFTER_LIMIT) following.removeAt(AFTER_LIMIT / 2)
                following.addLast(event)
                true
            }
            else -> false
        }
        if (recent.size == BEFORE_LIMIT) recent.removeFirst()
        recent.addLast(event)
        return if (changed) buildString {
            before.forEach { appendLine(it.line) }
            appendLine(requireNotNull(loss).line)
            following.forEach { appendLine(it.line) }
        }.trimEnd() else null
    }

    private fun isRelevant(message: String): Boolean =
        message.startsWith(TRANSPORT_LOSS) ||
            message.startsWith("EVENT: changeModes") ||
            message.startsWith("EVENT: 命令 type=requestUI") ||
            message.startsWith("ICCOA:") && SOURCE_EVENTS.any(message::contains) ||
            message.startsWith("VIDEO:") && VIDEO_EVENTS.any(message::contains) ||
            message.startsWith("AUDIO:") && AUDIO_EVENTS.any(message::contains) ||
            message.startsWith("CTRL: 车机邀请到达") ||
            message.startsWith("NSD: 服务消失") || message.startsWith("NSD: OK") ||
            message.startsWith("STAGE: 本轮") || message.startsWith("STAGE: ✗") ||
            message.startsWith("AIRPLAY: ✓ RECORD 完成")

    companion object {
        const val TRANSPORT_LOSS = "SESSION: CarPlay 传输断开"
        private const val BEFORE_MS = 40_000L
        private const val AFTER_MS = 60_000L
        private const val BEFORE_LIMIT = 12
        private const val AFTER_LIMIT = 18
        private const val PREFS = "connection_incident"
        private const val KEY = "latest_timeline"
        private val SOURCE_EVENTS = listOf(
            "会话状态", "视频通道", "后台重连", "切回 Surface", "直接使用 Surface",
            "直通首个 IDR", "实际源画面", "直接交给编码器",
        )
        private val VIDEO_EVENTS = listOf("推流中止", "直通送帧失败", "直通通道已失效")
        private val AUDIO_EVENTS = listOf("发送失败", "相机退出", "已启动直通模式")

        fun save(context: Context, timeline: String) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, timeline).apply()
        }

        fun latest(context: Context): String? =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
    }
}
