package io.github.shaonialife.carptocar.bt

import android.content.Intent

/**
 * 「车机走远了，该不该收摊」的判定（2026-10-30）。
 *
 * 30 秒内的回连由 [CarIdleGuard] 取消计时。这里只判定计时到期后是否结束会话。
 *
 * 30 秒是刻意选的值：车机熄火/人下车时蓝牙会断，但**断一下又回连**很常见
 * （车机重启、HFP 重建、走到车尾信号弱）。等 30 秒就是给这段抖动留窗口——
 * 期间只要重新连上就取消计时，不会把正在恢复的会话误杀。
 *
 * ## 为什么判「在推流」而不是「会话在跑」
 *
 * 蓝牙与 Wi-Fi 视频可独立存活，计时到期后仍有画面时继续投屏。
 * 手机界面是否可见只决定是否退出进程。
 */
object CarIdlePolicy {
    /** 蓝牙断开后等这么久再收摊。 */
    const val IDLE_TIMEOUT_MILLIS = 30_000L

    internal fun shouldReconnectAfterAppLaunch(
        action: String?,
        categories: Set<String>?,
        flags: Int,
        restoringActivity: Boolean,
        autoConnectEnabled: Boolean,
    ): Boolean =
        autoConnectEnabled && !restoringActivity &&
            flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY == 0 &&
            (action == null || action == Intent.ACTION_MAIN && categories?.contains(Intent.CATEGORY_LAUNCHER) == true)

    enum class Verdict {
        STOP_SESSION,
        STOP_PROCESS,

        /** 蓝牙断了但画面还在送（镜像走 WiFi）——不杀，用户正在用。 */
        STREAMING,

        WAITING,
    }

    /**
     * @param streaming 画面是否真的在往车机送
     * @param uiVisible 界面此刻是否在前台（用户正看着手机时不该把进程掐掉）
     */
    fun onIdleTimeout(
        disconnectedForMillis: Long,
        streaming: Boolean,
        uiVisible: Boolean,
    ): Verdict =
        when {
            disconnectedForMillis < IDLE_TIMEOUT_MILLIS -> Verdict.WAITING
            streaming -> Verdict.STREAMING
            uiVisible -> Verdict.STOP_SESSION
            else -> Verdict.STOP_PROCESS
        }
}
