package io.github.shaonialife.carptocar.bt

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import io.github.shaonialife.carptocar.BuildConfig
import io.github.shaonialife.carptocar.EXTRA_CAR_APPROACH
import io.github.shaonialife.carptocar.MainActivity
import io.github.shaonialife.carptocar.SessionController
import io.github.shaonialife.carptocar.util.Bg
import io.github.shaonialife.carptocar.util.DebugFileLog
import io.github.shaonialife.carptocar.util.SysShell

/**
 * 靠近车机自动启动（2026-10-30）——**静默检测**的实现方式。
 *
 * 为什么用系统 ACL 广播而不是扫描：
 * - 车机（尤其自带热点的车机）在进入范围后会**自己回连手机蓝牙**，系统随即发出
 *   `ACTION_ACL_CONNECTED`；这是「靠近」最准、最省电、且完全无声的信号。
 * - 主动 `startDiscovery()` 会持续占用射频、弹系统扫描提示、耗电，且 Android 12+
 *   还要 BLUETOOTH_SCAN 权限——与「静默」相悖，故本项目不用。
 * - 本接收器是**清单静态注册**：App 没打开、进程没起来也能收（系统投递时拉起进程），
 *   所以不需要常驻前台服务，也就没有常驻通知。
 *
 * ## 命中后做什么：只把 App 叫到前台，连接由前台发起
 *
 * 2026-10-30 实车反馈：本接收器原先还会「不等界面，直接在后台起一轮连接」。
 * 那样确实把链路建了起来，但**会话是残缺的**——车机端收不到任何数据。
 * 原因是整条管线并不只是 iAP2 拨号：ICCOA 接收端、权限引导和
 * 界面侧的启动自检（残留虚拟屏清理、权限门禁）都挂在 Activity 这一路上，
 * 绕开界面直接连，握手能过，出流的那一段却起不来。
 *
 * 所以现在这里只做一件事：**把 App 带到前台**，并在 Intent 里带
 * [EXTRA_CAR_APPROACH]，前台据此重新武装「启动后自动连接」、走完整的
 * `ensureBluetoothReady → startConnection` 流程（含权限、自检、换流）。
 * 界面已经在后台时走 `onNewIntent`，只重新武装一次，不重建界面。
 *
 * 拉起失败（Android 10+ 的后台启动限制 / ROM 禁止自启动）时**不一定能立刻知道**：
 * 系统是静默拒绝的——不报错、界面就是不出现。所以拉起后会隔 2 秒核对
 * [AppUiPresence]：如果主界面没收到启动请求，支持原生 ICCOA 的构建会通过 Root
 * 显式启动本应用。仍未启动时，发通知让用户打开界面。
 *
 * 通知只在**失败**时发；成功时通知栏里那条是保活服务（CarPlayService）的，不会重复。
 */
class CarApproachReceiver : BroadcastReceiver() {
    companion object {
        private const val BLOCKED_CHANNEL_ID = "carplay_approach"

        /** 「被系统拦下」提示的通知 id（[MainActivity] 起来后会主动撤掉它）。 */
        const val BLOCKED_NOTIFICATION_ID = 2
    }

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        when (intent.action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> onConnected(context, intent)
            // 链路断开 = 人走远了（或车机熄火）：解除「手动断开后安静」的抑制，
            // 并起 30 秒计时——到点还没回连就把会话、保活服务和进程一起收掉。
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> onDisconnected(context, intent)
        }
    }

    /**
     * 车机链路断开（2026-10-30 加收摊计时）。
     *
     * 只认**目标车机**：耳机/手表每天都在断连，跟着它们起计时纯属打扰。
     * 30 秒的窗口留给「车机重启、信号抖动后自己回连」——回连时 [onConnected]
     * 会把这个计时撤掉，正在恢复的会话不会被误杀。
     */
    private fun onDisconnected(
        context: Context,
        intent: Intent,
    ) {
        val address = addressOf(context, intent) ?: return
        val target = TargetDevicePrefs.get(context)
        val activeAddress = SessionController.carPlayPeerAddress()
        if (!address.equals(activeAddress ?: target?.address, ignoreCase = true)) return
        if (target != null && TargetDevicePrefs.matches(target, address)) {
            CarApproachPrefs.setAclConnected(context, false)
            if (CarApproachPrefs.suppressed(context) || CarApproachPrefs.lastTriggerAt(context) > 0L) {
                CarApproachPrefs.setSuppressed(context, false)
                CarApproachPrefs.markTriggered(context, 0L)
            }
        }
        DebugFileLog.append(context, "APPROACH: 车机蓝牙链路断开")
        CarIdleGuard.onCarDisconnected(context, address)
    }

    private fun onConnected(
        context: Context,
        intent: Intent,
    ) {
        val address = addressOf(context, intent)
        if (address != null) CarIdleGuard.onCarConnected(context, address)
        val target = TargetDevicePrefs.get(context) ?: return
        // 链路是物理事实：只要连上的是目标车机，就先记状态、撤计时——
        // 哪怕下面判定为「已有会话 / 刚断开过」不拉起界面，这次回连也算数。
        if (address != null && address.equals(target.address, ignoreCase = true)) {
            CarApproachPrefs.setAclConnected(context, true)
        }
        val sessionRunning = SessionController.isSessionRunning() || SessionController.sessionStarting.get()
        val decision =
            CarApproachPolicy.onAclConnected(
                eventAddress = address,
                targetAddress = target.address,
                enabled = CarApproachPrefs.enabled(context),
                sessionRunning = sessionRunning,
                suppressed = CarApproachPrefs.suppressed(context),
                lastTriggerAt = CarApproachPrefs.lastTriggerAt(context),
                now = System.currentTimeMillis(),
            )
        if (decision != CarApproachPolicy.Decision.TRIGGER) {
            if (!TargetDevicePrefs.matches(target, address)) return
            val reason = when (decision) {
                CarApproachPolicy.Decision.DISABLED -> "自动启动已关闭"
                CarApproachPolicy.Decision.SESSION_RUNNING -> "已有会话"
                CarApproachPolicy.Decision.SUPPRESSED -> "刚手动断开过"
                CarApproachPolicy.Decision.THROTTLED -> "本次靠近已触发过"
                else -> return
            }
            DebugFileLog.append(context, "APPROACH: 已收到目标车机蓝牙连接事件，跳过自动启动（$reason）")
            return
        }

        val launchedAt = System.currentTimeMillis()
        CarApproachPrefs.markTriggered(context, launchedAt)
        DebugFileLog.append(context, "APPROACH: 已收到目标车机「${target.name}」蓝牙连接事件，正在请求打开 App")
        launchApp(context, target, launchedAt)
    }

    /**
     * 把 App 带到前台。带 [EXTRA_CAR_APPROACH]，界面侧据此重新武装自动连接；
     * Activity 已在后台时走 `onNewIntent`，不重建界面。
     */
    private fun launchApp(context: Context, target: TargetDevicePrefs.Target, launchedAt: Long) {
        // 后台启动界面的豁免之一就是「持有显示在其他应用上层」——没有它，MagicOS 会
        // 静默拦掉这次启动（不抛异常、界面不出现）。这里先留一条线索，事后看日志能一眼定因。
        if (!android.provider.Settings.canDrawOverlays(context)) {
            DebugFileLog.append(
                context,
                "APPROACH: 未授予「显示在其他应用上层」——系统可能拒绝后台打开界面（静默，不报错）",
            )
        }
        val launch =
            Intent(context, MainActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP,
                )
                putExtra(EXTRA_CAR_APPROACH, true)
            }
        val ok =
            runCatching { context.startActivity(launch) }
                .onFailure {
                    DebugFileLog.append(
                        context,
                        "APPROACH: 常规启动请求失败（${it.javaClass.simpleName}）",
                    )
                }.isSuccess
        verifyBroughtUp(context, target, launch, launchedAt, ok)
    }

    /**
     * 核对「拉起是否真的生效」（2026-10-30）。
     *
     * 系统拦后台启动是**静默**的：不抛异常，界面就是不出现。用户看到的是
     * 「车机连上了、手机什么都没有」。这里 2 秒后比对 [AppUiPresence] 的盖章，
     * 没收到请求时尝试显式启动，再检查是否需要发通知。
     */
    private fun verifyBroughtUp(
        context: Context,
        target: TargetDevicePrefs.Target,
        launch: Intent,
        launchedAt: Long,
        waitForRegularLaunch: Boolean,
    ) {
        // 冷进程只有接收器时，保留广播生命周期直到启动核验结束。
        val pending = goAsync()
        Bg.run("approach-launch") {
            try {
                if (waitForRegularLaunch) Thread.sleep(2_000L)
                if (!shouldRetryLaunch(context, target, launchedAt)) return@run

                if (BuildConfig.CAR_PLUS_RUNTIME) {
                    DebugFileLog.append(context, "APPROACH: 常规启动未打开 App，正在通过 Root 重试")
                    val component = requireNotNull(launch.component).flattenToString()
                    val result = SysShell.execAsRoot(
                        "/system/bin/am start -W --user current " +
                            "-f ${launch.flags} -n $component --ez $EXTRA_CAR_APPROACH true",
                        timeoutMs = 2_500L,
                    )
                    if (!result.ok) {
                        DebugFileLog.append(context, "APPROACH: Root 启动失败（${result.exitCode}）：${result.output.take(250)}")
                    }
                }

                if (shouldRetryLaunch(context, target, launchedAt)) {
                    DebugFileLog.append(context, "APPROACH: 主界面仍未收到启动请求，尝试显示手动打开通知")
                    runCatching { notifyLaunchBlocked(context) }
                }
            } finally {
                pending.finish()
            }
        }
    }

    private fun shouldRetryLaunch(context: Context, target: TargetDevicePrefs.Target, launchedAt: Long): Boolean =
        AppUiPresence.lastBringUpAt < launchedAt &&
            CarApproachPrefs.lastTriggerAt(context) == launchedAt &&
            CarApproachPrefs.enabled(context) &&
            CarApproachPrefs.aclConnected(context) &&
            !CarApproachPrefs.suppressed(context) &&
            TargetDevicePrefs.get(context)?.address.equals(target.address, ignoreCase = true)

    private fun notifyLaunchBlocked(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    BLOCKED_CHANNEL_ID,
                    "靠近车机提示",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = "系统拦下后台打开界面时的提示"
                    setShowBadge(false)
                    setSound(null, null)
                },
            )
        }
        val open =
            PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra(EXTRA_CAR_APPROACH, true)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val hint = "点此打开 App 并连接车机。可在系统设置里检查本应用的自启动和「显示在其他应用上层」权限。"
        manager.notify(
            BLOCKED_NOTIFICATION_ID,
            NotificationCompat
                .Builder(context, BLOCKED_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle("车机已靠近，但系统没让 App 到前台")
                .setContentText(hint)
                .setStyle(NotificationCompat.BigTextStyle().bigText(hint))
                .setContentIntent(open)
                .addAction(0, "打开界面", open)
                .setCategory(NotificationCompat.CATEGORY_ERROR)
                .setAutoCancel(true)
                .build(),
        )
    }

    /** 读事件里的设备地址；缺 BLUETOOTH_CONNECT（Android 12+）时只留一行线索，不打扰。 */
    private fun addressOf(
        context: Context,
        intent: Intent,
    ): String? =
        runCatching { deviceOf(intent)?.address }.getOrElse { error ->
            if (error is SecurityException) {
                DebugFileLog.append(context, "APPROACH: 缺少蓝牙权限，无法识别靠近/离开的车机（请在 App 内授权）")
            }
            null
        }

    @Suppress("DEPRECATION")
    private fun deviceOf(intent: Intent): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }
}
