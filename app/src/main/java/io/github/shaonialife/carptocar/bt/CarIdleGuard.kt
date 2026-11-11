package io.github.shaonialife.carptocar.bt

import android.app.AlarmManager
import android.app.KeyguardManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import io.github.shaonialife.carptocar.SessionController
import io.github.shaonialife.carptocar.service.CarIdleReceiver
import io.github.shaonialife.carptocar.service.CarPlayService
import io.github.shaonialife.carptocar.util.DebugFileLog

/** Stops the session when the car has been offline for 30 seconds and projection has ended. */
object CarIdleGuard {
    /** 与 [CarIdleReceiver] 约好的内部动作（只在应用内显式广播，不对外导出）。 */
    const val ACTION_IDLE_TIMEOUT = "io.github.shaonialife.carptocar.action.CAR_IDLE_TIMEOUT"

    private const val REQUEST_CODE = 4101
    private data class Departure(val address: String, val disconnectedAt: Long)
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private var departure: Departure? = null
    private var scheduledCheck: Runnable? = null
    private var shutdownGeneration = 0

    fun onCarDisconnected(context: Context, address: String) {
        if (address.equals(departure?.address, ignoreCase = true)) return
        val now = SystemClock.elapsedRealtime()
        departure = Departure(address, now)
        schedule(context.applicationContext, now + CarIdlePolicy.IDLE_TIMEOUT_MILLIS)
    }

    fun onCarConnected(context: Context, address: String) {
        val isTarget = address.equals(TargetDevicePrefs.get(context)?.address, ignoreCase = true)
        val offlineCar = CarApproachPrefs.offlineCarAddress(context)
        val returnedToCar = offlineCar != null && (isTarget || address.equals(offlineCar, ignoreCase = true))
        if (returnedToCar) CarApproachPrefs.setOfflineCarAddress(context, null)
        val pending = departure
        if (address.equals(pending?.address, ignoreCase = true) ||
            (pending == null && (returnedToCar || isTarget))
        ) {
            cancel(context)
        }
    }

    fun onProjectionStopped(context: Context) {
        handler.post { onTimeout(context) }
    }

    /**
     * 结束会话到真正 kill 之间留的时间：disconnect 会在后台线程收尾
     * （RFCOMM close、虚拟屏释放、日志落盘），掐太快会丢最后几行日志，
     * 而这几行正是事后判断「为什么没连上」的唯一线索。
     */
    private const val KILL_DELAY_MILLIS = 600L

    /** 设备唤醒时用 Handler 到点检查，休眠时由系统闹钟补做同一次检查。 */
    private fun schedule(context: Context, deadline: Long) {
        scheduledCheck?.let(handler::removeCallbacks)
        val check = Runnable { onTimeout(context) }
        scheduledCheck = check
        handler.postDelayed(check, (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0L))
        val manager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pending =
            pendingIntent(context, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE) ?: return
        runCatching {
            manager.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                deadline,
                pending,
            )
        }
    }

    /** 车机回连（或用户主动给了新指令）时撤掉计时。 */
    fun cancel(context: Context) {
        departure = null
        shutdownGeneration++
        cancelScheduledChecks(context)
    }

    private fun cancelScheduledChecks(context: Context) {
        scheduledCheck?.let(handler::removeCallbacks)
        scheduledCheck = null
        val manager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pending = pendingIntent(context, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE) ?: return
        runCatching { manager.cancel(pending) }
        runCatching { pending.cancel() }
    }

    /** 闹钟到点：[CarIdleReceiver] 转交进来，这里判定要不要真的收摊。 */
    fun onTimeout(context: Context) {
        val pending = departure ?: return
        val active = SessionController.carPlayPeerAddress()
        if (active != null && !pending.address.equals(active, ignoreCase = true)) {
            cancel(context)
            return
        }
        val target = TargetDevicePrefs.get(context)
        val verdict = CarIdlePolicy.onIdleTimeout(
            aclConnected = target != null && TargetDevicePrefs.matches(target, pending.address) &&
                CarApproachPrefs.aclConnected(context),
            disconnectedForMillis = SystemClock.elapsedRealtime() - pending.disconnectedAt,
            streaming = SessionController.isCarProjectionActive(),
            uiVisible = hasUnlockedUi(context),
        )
        when (verdict) {
            CarIdlePolicy.Verdict.STOP_SESSION -> finishDeparture(context, pending.address, killProcess = false)
            CarIdlePolicy.Verdict.STOP_PROCESS -> finishDeparture(context, pending.address, killProcess = true)
            CarIdlePolicy.Verdict.STREAMING -> cancelScheduledChecks(context) // 视频结束会再次检查。
            CarIdlePolicy.Verdict.STILL_CONNECTED -> cancel(context)
            CarIdlePolicy.Verdict.WAITING -> Unit
        }
    }

    private fun finishDeparture(context: Context, address: String, killProcess: Boolean) {
        if (!SessionController.hasCarWorkToStop()) {
            cancel(context)
            return
        }
        CarApproachPrefs.setOfflineCarAddress(context, address)
        shutdown(
            context,
            reason = "车机蓝牙断开 30 秒未回连，投屏已结束",
            killProcess = killProcess,
            suppressAutoStart = false,
            carDeparted = true,
        )
    }

    fun onUiStopped(context: Context) {
        if (SessionController.isCarOffline && !SessionController.hasCarWorkToStop() && isPhoneLocked(context)) {
            scheduleExit(context.applicationContext, carDeparted = true)
        }
    }

    private fun hasUnlockedUi(context: Context): Boolean = AppUiPresence.isVisible && !isPhoneLocked(context)

    private fun isPhoneLocked(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)?.isInteractive == false ||
            context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true

    /**
     * 结束会话、停服务、清通知，可选地把进程也退掉。
     *
     * @param killProcess true = 收尾后退出进程（静默检测不受影响，见类注释）
     * @param suppressAutoStart true = 置「刚断开过」抑制，避免车机仍连着时链路抖动
     *   立刻又把 App 拉起来（那会变成「断开 → 自动重连」的死循环）；车机真断开时
     *   这个标记会由 [CarApproachReceiver] 自动清除，不影响下次靠近。
     */
    fun shutdown(
        context: Context,
        reason: String,
        killProcess: Boolean,
        suppressAutoStart: Boolean,
        carDeparted: Boolean = false,
    ) {
        cancel(context)
        DebugFileLog.append(
            context,
            "IDLE: $reason——结束会话、停止保活服务" + if (killProcess) "，随后退出进程（静默检测保留）" else "",
        )
        // 只有在车机**还连着**蓝牙时才需要抑制：那种情况下链路抖一下就会重发
        // ACL_CONNECTED，不压一下会变成「关掉 → 立刻又被拉起」。车机已经走了
        // （aclConnected = false）就没什么可抑制的，下次靠近理应能直接触发。
        if (suppressAutoStart && CarApproachPrefs.aclConnected(context)) {
            CarApproachPrefs.setSuppressed(context, true)
        }
        runCatching { SessionController.disconnect(context, carDeparted, graceful = !killProcess) }
            .onFailure {
                DebugFileLog.append(context, "IDLE: 断开过程异常（${it.javaClass.simpleName}），继续收尾")
            }
        // 再显式停一次保活服务：disconnect 中途抛异常时可能没走到停止那步，
        // 而服务是 START_STICKY——「还在跑的时候把进程杀掉」会被系统重新拉起，
        // 表现为「退出后通知栏又冒出来一条」，白忙一场。
        runCatching { context.stopService(Intent(context, CarPlayService::class.java)) }
        // 兜底清通知：服务正常停止时通知会随之消失，但 disconnect 中途抛异常就留住了。
        runCatching {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.cancel(CarPlayService.NOTIFICATION_ID)
            manager.cancel(CarApproachReceiver.BLOCKED_NOTIFICATION_ID)
        }
        if (killProcess) scheduleExit(context, carDeparted)
    }

    private fun scheduleExit(context: Context, carDeparted: Boolean) {
        val generation = shutdownGeneration
        handler.postDelayed(
            {
                // killProcess 而不是 exit：走的是同一套「进程被回收」路径，
                // 系统下一次投递 ACL_CONNECTED 时照样能把静态接收器拉起来。
                if (generation == shutdownGeneration &&
                    (!carDeparted || SessionController.isCarOffline &&
                        !SessionController.hasCarWorkToStop() && !hasUnlockedUi(context))
                ) {
                    Process.killProcess(Process.myPid())
                }
            },
            KILL_DELAY_MILLIS,
        )
    }

    private fun pendingIntent(
        context: Context,
        flags: Int,
    ): PendingIntent? =
        PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(context, CarIdleReceiver::class.java).setAction(ACTION_IDLE_TIMEOUT),
            flags,
        )
}
