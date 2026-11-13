package io.github.shaonialife.carptocar.launcher.settings

import android.content.Context
import io.github.shaonialife.carptocar.carplus.CarPlusVideo
import java.util.Locale

/** ICCOA 分辨率、帧率和车机能力按蓝牙地址保存。 */
object DisplayPrefs {
    private const val PREFS_NAME = "settings"
    private const val KEY_ICCOA_WIDTH = "iccoa_stream_width"
    private const val KEY_ICCOA_HEIGHT = "iccoa_stream_height"
    private const val KEY_ICCOA_HIGH_FPS = "iccoa_high_fps"
    private const val KEY_ICCOA_CAR_MAX_FPS = "iccoa_car_max_fps"
    private const val KEY_LAST_CAR_WIDTH = "last_car_width"
    private const val KEY_LAST_CAR_HEIGHT = "last_car_height"
    private const val KEY_LAST_CAR_FROM_CAR = "last_car_from_car"

    internal fun lastReportedCanvas(context: Context): CarPlusVideo.Size? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_LAST_CAR_FROM_CAR, true)) return null
        return CarPlusVideo.displaySize(
            prefs.getInt(KEY_LAST_CAR_WIDTH, 0),
            prefs.getInt(KEY_LAST_CAR_HEIGHT, 0),
        )
    }

    internal fun carPlusStreamSize(context: Context, address: String?): CarPlusVideo.Size? {
        val key = address?.uppercase(Locale.ROOT) ?: return null
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return CarPlusVideo.manualSize(
            prefs.getInt("${KEY_ICCOA_WIDTH}_$key", 0),
            prefs.getInt("${KEY_ICCOA_HEIGHT}_$key", 0),
        )
    }

    internal fun setCarPlusStreamSize(context: Context, address: String, size: CarPlusVideo.Size?) {
        val key = address.uppercase(Locale.ROOT)
        val widthKey = "${KEY_ICCOA_WIDTH}_$key"
        val heightKey = "${KEY_ICCOA_HEIGHT}_$key"
        val edit = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
        if (size == null) {
            edit.remove(widthKey).remove(heightKey)
        } else {
            edit.putInt(widthKey, size.width).putInt(heightKey, size.height)
        }
        edit.apply()
    }

    internal fun carPlusHighFps(context: Context, address: String?): Boolean {
        val key = address?.uppercase(Locale.ROOT) ?: return false
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean("${KEY_ICCOA_HIGH_FPS}_$key", false)
    }

    internal fun carPlusCarMaxFps(context: Context, address: String?): Int {
        val key = address?.uppercase(Locale.ROOT) ?: return 0
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt("${KEY_ICCOA_CAR_MAX_FPS}_$key", 0)
    }

    internal fun recordCarPlusCarMaxFps(context: Context, address: String, maxFps: Int) {
        if (carPlusCarMaxFps(context, address) == maxFps) return
        val key = address.uppercase(Locale.ROOT)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putInt("${KEY_ICCOA_CAR_MAX_FPS}_$key", maxFps)
            .apply()
    }

    internal fun setCarPlusHighFps(context: Context, address: String, highFps: Boolean) {
        if (highFps && !CarPlusVideo.supportsHighFps(carPlusCarMaxFps(context, address))) return
        val key = address.uppercase(Locale.ROOT)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean("${KEY_ICCOA_HIGH_FPS}_$key", highFps)
            .apply()
    }

    /** Carry the previous global size to the selected receiver once, during upgrade. */
    internal fun migrateCarPlusStreamSize(context: Context, selectedAddress: String?) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_ICCOA_WIDTH) && !prefs.contains(KEY_ICCOA_HEIGHT)) return
        val oldSize = CarPlusVideo.manualSize(
            prefs.getInt(KEY_ICCOA_WIDTH, 0),
            prefs.getInt(KEY_ICCOA_HEIGHT, 0),
        )
        val edit = prefs.edit().remove(KEY_ICCOA_WIDTH).remove(KEY_ICCOA_HEIGHT)
        if (selectedAddress != null && oldSize != null) {
            val key = selectedAddress.uppercase(Locale.ROOT)
            val widthKey = "${KEY_ICCOA_WIDTH}_$key"
            val heightKey = "${KEY_ICCOA_HEIGHT}_$key"
            if (!prefs.contains(widthKey) && !prefs.contains(heightKey)) {
                edit.putInt(widthKey, oldSize.width).putInt(heightKey, oldSize.height)
            }
        }
        edit.apply()
    }
}
