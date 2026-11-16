package io.github.shaonialife.carptocar.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.shaonialife.carptocar.bt.CarIdleGuard
import io.github.shaonialife.carptocar.util.DebugFileLog

/** 接收本应用闹钟投递的离车超时事件。 */
class CarIdleReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != CarIdleGuard.ACTION_IDLE_TIMEOUT) return
        DebugFileLog.append(context, "IDLE: 30 秒计时到点，开始判定是否收摊")
        CarIdleGuard.onTimeout(context)
    }
}
