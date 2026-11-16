package io.github.shaonialife.carptocar.bt

import android.app.AlarmManager
import android.app.KeyguardManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import io.github.shaonialife.carptocar.SessionController
import io.github.shaonialife.carptocar.service.CarIdleReceiver
import io.github.shaonialife.carptocar.service.CarPlayService
import io.github.shaonialife.carptocar.util.DebugFileLog

/** 车机断开 30 秒且投屏结束后，停止本次会话。 */
object CarIdleGuard {
    /** 与 [CarIdleReceiver] 约好的内部动作（只在应用内显式广播，不对外导出）。 */
    const val ACTION_IDLE_TIMEOUT = "io.github.shaonialife.carptocar.action.CAR_IDLE_TIMEOUT"

    private const val REQUEST_CODE = 4101
    private data class Departure(val address: String, val disconnectedAt: Long)
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private var departure: Departure? = null
    private var scheduledCheck: Runnable? = null
    private var shutdownGeneration = 0

    /** 只在本应用进程运行时监控车机链路，用于离车清理。 */
    fun registerBluetoothMonitor(context: Context) {
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        ContextCompat.registerReceiver(
            context,
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (!SessionController.hasCarWorkToStop()) return
                    val address = runCatching {
                        IntentCompat.getParcelableExtra(
                            intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java,
                        )?.address
                    }.getOrNull() ?: return
                    when (intent.action) {
                        BluetoothDevice.ACTION_ACL_CONNECTED -> {
                            if (address.equals(departure?.address, ignoreCase = true)) cancel(context)
                        }
                        BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                            val carAddress = SessionController.carPlayPeerAddress()
                                ?: TargetDevicePrefs.get(context)?.address
                            if (address.equals(carAddress, ignoreCase = true)) onCarDisconnected(context, address)
                        }
                    }
                }
            },
            filter,
            ContextCompat.RECEIVER_EXPORTED,
        )
    }

    private fun onCarDisconnected(context: Context, address: String) {
        if (address.equals(departure?.address, ignoreCase = true)) return
        DebugFileLog.append(context, "IDLE: 车机蓝牙链路断开，开始 30 秒计时")
        val now = SystemClock.elapsedRealtime()
        departure = Departure(address, now)
        schedule(context.applicationContext, now + CarIdlePolicy.IDLE_TIMEOUT_MILLIS)
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
        val verdict = CarIdlePolicy.onIdleTimeout(
            disconnectedForMillis = SystemClock.elapsedRealtime() - pending.disconnectedAt,
            streaming = SessionController.isCarProjectionActive(),
            uiVisible = hasUnlockedUi(context),
        )
        when (verdict) {
            CarIdlePolicy.Verdict.STOP_SESSION -> finishDeparture(context, pending.address, killProcess = false)
            CarIdlePolicy.Verdict.STOP_PROCESS -> finishDeparture(context, pending.address, killProcess = true)
            CarIdlePolicy.Verdict.STREAMING -> cancelScheduledChecks(context) // 视频结束会再次检查。
            CarIdlePolicy.Verdict.WAITING -> Unit
        }
    }

    private fun finishDeparture(context: Context, address: String, killProcess: Boolean) {
        if (!SessionController.hasCarWorkToStop()) {
            cancel(context)
            return
        }
        CarIdlePrefs.setOfflineCarAddress(context, address)
        shutdown(
            context,
            reason = "车机蓝牙断开 30 秒未回连，投屏已结束",
            killProcess = killProcess,
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

    /** 结束会话、停服务、清通知，可在收尾后退出进程。 */
    fun shutdown(
        context: Context,
        reason: String,
        killProcess: Boolean,
        carDeparted: Boolean = false,
    ) {
        cancel(context)
        DebugFileLog.append(
            context,
            "IDLE: $reason——结束会话、停止保活服务" + if (killProcess) "，随后退出进程" else "",
        )
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
        }
        if (killProcess) scheduleExit(context, carDeparted)
    }

    private fun scheduleExit(context: Context, carDeparted: Boolean) {
        val generation = shutdownGeneration
        handler.postDelayed(
            {
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
