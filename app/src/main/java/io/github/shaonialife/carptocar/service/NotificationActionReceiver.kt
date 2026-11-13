package io.github.shaonialife.carptocar.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import io.github.shaonialife.carptocar.SessionController
import io.github.shaonialife.carptocar.bt.CarIdleGuard
import io.github.shaonialife.carptocar.carplus.CarPlusReceiver

/**
 * 通知栏动作的接盘者（2026-10-30）。
 *
 * 为什么需要它：靠近车机自动启动后，连接跑在**后台**，界面上没有任何窗口——
 * 通知栏因此是用户唯一看得见、也唯一点得到的地方。断开这个动作必须能在
 * 「界面不存在 / 进程里只有服务」的情况下独立完成，所以走广播而不是
 * 依赖 MainActivity 的按钮。
 *
 * 2026-10-30 起「断开」= **彻底收摊**：结束会话、停保活服务、清通知，连进程一起退掉。
 * 用户点断开的语义就是「别在后台留东西」，留着空进程与常驻通知没有意义；
 * 而静默检测是清单静态注册的接收器，进程退出不影响下次靠近时被系统重新拉起。
 */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        when (intent.action) {
            CarPlayService.ACTION_DISCONNECT -> CarIdleGuard.shutdown(
                context,
                reason = "用户在通知栏点了「断开」",
                killProcess = true,
                // 车机可能还连着蓝牙：置抑制，避免链路抖动立刻又把 App 拉起来
                // （那会变成「断开 → 自动重连」的死循环）；车机真断开时自动解除。
                suppressAutoStart = true,
            )
            CarPlayService.ACTION_RECONNECT_CAR_PLUS -> {
                // 通知按周期刷新，点击时再次确认仍在等待画面。
                if (!SessionController.canReconnectCarPlusFromNotification(context)) return
                val sent = CarPlusReceiver.requestReconnect()
                val message = if (sent) "正在请求重新连接 Car+" else "Car+ 接收端暂不可用"
                SessionController.appendLog("ICCOA: 通知栏 · $message")
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
        }
    }
}
