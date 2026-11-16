package io.github.shaonialife.carptocar.bt

import android.content.Context

internal object CarIdlePrefs {
    private const val PREFS_NAME = "settings"
    // 沿用旧版本的键，升级后仍能读取离车状态。
    private const val KEY_OFFLINE_CAR = "car_approach_offline_car"

    /** 离车后即使锁屏退出进程，恢复界面也继续等待车机回连。 */
    fun offlineCarAddress(context: Context): String? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_OFFLINE_CAR, null)

    fun setOfflineCarAddress(context: Context, address: String?) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_OFFLINE_CAR, address)
            .apply()
    }
}
