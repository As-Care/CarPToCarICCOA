package io.github.shaonialife.carptocar.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import io.github.shaonialife.carptocar.SessionController
import io.github.shaonialife.carptocar.bt.CarIdleGuard
import io.github.shaonialife.carptocar.carplus.CarPlusReceiver

/** 直接处理投屏通知中的断开和重连操作。 */
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
