package io.github.shaonialife.carptocar

import android.Manifest
import android.animation.ObjectAnimator
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.method.ScrollingMovementMethod
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import io.github.shaonialife.carptocar.audio.AudioCodec
import io.github.shaonialife.carptocar.bt.AppUiPresence
import io.github.shaonialife.carptocar.bt.BtCompatPrefs
import io.github.shaonialife.carptocar.bt.BtProfiles
import io.github.shaonialife.carptocar.bt.CarIdlePrefs
import io.github.shaonialife.carptocar.bt.CarIdleGuard
import io.github.shaonialife.carptocar.bt.CarIdlePolicy
import io.github.shaonialife.carptocar.bt.TargetDevicePrefs
import io.github.shaonialife.carptocar.carplus.CarPlusReceiver
import io.github.shaonialife.carptocar.carplus.CarPlusVideo
import io.github.shaonialife.carptocar.launcher.VirtualLauncher
import io.github.shaonialife.carptocar.launcher.settings.AutoConnectPrefs
import io.github.shaonialife.carptocar.launcher.settings.CarPlusResolutionHistory
import io.github.shaonialife.carptocar.launcher.settings.DisplayPrefs
import io.github.shaonialife.carptocar.legal.DisclaimerDialog
import io.github.shaonialife.carptocar.legal.DisclaimerPrefs
import io.github.shaonialife.carptocar.legal.needsDisclaimerAtLaunch
import io.github.shaonialife.carptocar.ui.DarkMessageDialog
import io.github.shaonialife.carptocar.ui.PanelWindow
import io.github.shaonialife.carptocar.ui.SignalWavesView
import io.github.shaonialife.carptocar.util.AppVersion
import io.github.shaonialife.carptocar.util.Bg
import io.github.shaonialife.carptocar.util.ConnectionIncidentTimeline
import io.github.shaonialife.carptocar.util.ConnectionIssueSummary
import io.github.shaonialife.carptocar.util.DeviceIdentity
import io.github.shaonialife.carptocar.util.LogDisplayLevel
import io.github.shaonialife.carptocar.util.LogExporter
import io.github.shaonialife.carptocar.util.SysShell
import io.github.shaonialife.carptocar.video.VideoSender
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 手机侧界面（Material 3 暗色重构，设计稿 design/phone-ui-proto.html）：
 * 英雄卡承载连接状态与主操作；连接准备清单化；Shell 状态与日志折叠收纳。
 * 会话管线（对象、日志缓冲）全部由 [SessionController] 持有——Activity 只是挂屏：
 * 销毁重建不影响会话，重进界面日志自动续显；连接状态由 1s 轮询对账。
 */
class MainActivity : AppCompatActivity() {
    private enum class HeroState { IDLE, CONNECTING, CONNECTED }

    /** 连接中的默认副标题（无阶段提示时）。 */
    private companion object {
        const val DEFAULT_CONNECTING_HINT = "正在连接蓝牙和车机热点，准备投屏"
        const val KEY_DISCLAIMER_DEADLINE = "disclaimer_deadline"
        const val KEY_LOG_ISSUES_ONLY = "log_issues_only"
        const val KEY_RESOLUTION_PEER_ADDRESS = "resolution_peer_address"
        const val KEY_RESOLUTION_PEER_NAME = "resolution_peer_name"

        /** 本机蓝牙地址未填写：英雄卡副标题与输入框下方提示共用同一句。 */
        const val HINT_MAC_MISSING = "本机蓝牙地址未填写 —— 请先在下方填写并保存"

        /** 未填写时被拦下的 Snackbar 文案。 */
        const val TIP_MAC_MISSING = "本机蓝牙地址未填写 —— 请先在「连接准备」里填写并保存"

        /** 地址就绪时的英雄卡副标题（与「未填写」提示互斥）。 */
        const val HINT_IDLE_READY = "与车机蓝牙完成配对后，点击下方按钮发起无线连接"

        /** 「启动连接」被门禁拦下时的视觉强度（不透明度）。 */
        const val CTA_BLOCKED_ALPHA = 0.45f
    }

    private lateinit var deviceSubtitle: TextView
    private lateinit var signalWaves: SignalWavesView
    private lateinit var hubPlate: FrameLayout
    private lateinit var hubCar: ImageView
    private lateinit var hubCaption: TextView
    private lateinit var heroStateText: TextView
    private lateinit var heroSubline: TextView
    private lateinit var heroStats: TextView
    private lateinit var heroMediaChannels: TextView
    /** 「显示」卡片里的分辨率行（车机通告值 + 本次码流尺寸）。 */
    private lateinit var displayResolutionNote: TextView
    private var resolutionHistoryPeer: TargetDevicePrefs.Target? = null
    private var resolutionInputPeer: TargetDevicePrefs.Target? = null
    private val displayHistoryListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        renderDisplayHistory()
    }
    private lateinit var heroProgress: View
    private lateinit var ctaButton: LinearLayout
    private lateinit var reconnectCarPlusButton: View
    private lateinit var ctaIcon: ImageView
    private lateinit var ctaLabel: TextView
    private lateinit var readyBluetooth: TextView
    private lateinit var readyWifi: TextView
    private lateinit var ssidInput: EditText
    private lateinit var macInput: EditText
    private lateinit var tvMacHint: TextView
    private lateinit var btDesc: TextView
    private lateinit var btBadge: TextView
    private lateinit var btnPickTarget: TextView
    private lateinit var logBody: View
    private lateinit var logChevron: ImageView
    private lateinit var advancedConnBody: View
    private lateinit var advancedConnChevron: ImageView
    private lateinit var advancedConnSubline: TextView
    private lateinit var switchLog: androidx.appcompat.widget.SwitchCompat
    private lateinit var logText: TextView
    private var logIssuesOnly = false
    private lateinit var carPlusStatus: TextView
    private lateinit var snackbar: TextView

    // ---- 英雄卡状态机（1s 轮询 SessionController 对账）----
    private var heroState: HeroState? = null
    private var heroCarOffline = false
    private var connectedAt = 0L
    private var heroVideoSender: VideoSender? = null
    private var supportsSystemCarPlus = false
    private var hubBlink: ObjectAnimator? = null

    // 英雄卡实时帧率：1s 轮询对 VideoSender 已发帧计数取差值
    private var lastSentFrames = 0L
    private var lastFpsSampleAt = 0L
    private var actualSentFps: Int? = null

    /** 免责声明倒计时截止时刻（elapsedRealtime；旋转重建后透传，不重新计时）。 */
    private var disclaimerDeadlineAt = 0L

    private var activeDisclaimer: androidx.appcompat.app.AlertDialog? = null

    /**
     * 冷启动自动连接请求挂起中：等前置条件齐备（免责声明已接受、权限已授予、
     * 英雄卡空闲）才真正发起。旋转/重建不会置位——[maybeAutoConnect] 全程幂等。
     */
    private var autoConnectRequested = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private val autoConnectCheck = Runnable { maybeAutoConnect() }
    private val heroTick: Runnable =
        object : Runnable {
            override fun run() {
                if (isFinishing || isDestroyed) return
                tickHero()
                mainHandler.postDelayed(this, 1_000L)
            }
        }

    /**
     * 挂屏注册：日志续显（含划掉界面重进时已有会话的历史日志）+ SSID 回填。
     */
    private val uiHintSinkRef: (String) -> Unit = { message ->
        runOnUiThread { if (!isFinishing && !isDestroyed) snack(message, 6_000L) }
    }

    /** 当前打开的「加入热点」确认弹窗：取消连接时联动关闭（触发流程中止）。 */
    private var wifiJoinDialog: androidx.appcompat.app.AlertDialog? = null

    /** MIB3 控制链路的首页监听（onDestroy 摘掉，避免单例持有 Activity）。 */

    private val logListener: (String) -> Unit = { line ->
        runOnUiThread {
            // 与上面 uiHintSink 保持一致：`||` 等于「只要没同时满足两个条件就继续用视图」，
            // finish() 之后到 onDestroy 之间（isFinishing=true、isDestroyed=false）仍会
            // 往正在拆除的控件里追加文本。
            if (!isFinishing && !isDestroyed) appendConsole(line)
        }
    }

    private var autoFilledSsid = ""
    private var carHotspotConnected = false
    private lateinit var carWifiStatus: TextView

    private val ssidSink: () -> Unit = {
        runOnUiThread { if (!isFinishing && !isDestroyed) refreshCarHotspot() }
    }

    private fun refreshCarHotspot() {
        val known = SessionController.knownCarHotspot(this).orEmpty()
        val entered = ssidInput.text.toString()
        if (!ssidInput.hasFocus() && (entered.isEmpty() || entered == autoFilledSsid) && entered != known) {
            autoFilledSsid = known
            ssidInput.setText(known)
        }
        val expected = ssidInput.text.toString().trim()
        val current = SessionController.currentWifiSsid()
        carHotspotConnected = expected.isNotEmpty() && SessionController.isOnCarHotspot(expected)
        val status = when {
            carHotspotConnected -> "已连上车机热点"
            current != null -> "当前 WLAN · $current"
            expected.isNotEmpty() -> "等待连接车机热点"
            else -> "蓝牙握手后识别车机热点"
        }
        if (carWifiStatus.text != status) carWifiStatus.text = status
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            log("BLUETOOTH_CONNECT 授权: $granted")
            val automatic = pendingStartAfterBtPermission
            pendingStartAfterBtPermission = null
            if (granted && automatic != null) requestConnection(automatic)
        }

    private var carPlusStartupAttempted = false
    private var openCarPlusAfterPermission = false

    private val carPlusPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            val openGuide = openCarPlusAfterPermission
            openCarPlusAfterPermission = false
            if (hasCarPlusPermissions()) {
                if (openGuide || !SessionController.isCarOffline) CarPlusReceiver.start(this)
                maybeAutoConnect()
                if (openGuide) openSystemCarPlus()
            } else {
                log("ICCOA: 蓝牙或 WLAN 权限未授予")
            }
        }

    private fun carPlusPermissions(): List<String> =
        listOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.BLUETOOTH_SCAN,
        ) + if (Build.VERSION.SDK_INT >= 33) {
            listOf(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        }

    private fun hasCarPlusPermissions(): Boolean =
        carPlusPermissions().all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }

    private fun startCarPlusReceiver(refresh: Boolean = false) {
        if (!BuildConfig.CAR_PLUS_RUNTIME) {
            CarPlusReceiver.start(this)
            return
        }
        if (hasCarPlusPermissions()) {
            CarPlusReceiver.start(this, refresh)
        } else {
            carPlusPermissionLauncher.launch(carPlusPermissions().toTypedArray())
        }
    }

    private fun startCarPlusOnLaunch() {
        if (!BuildConfig.CAR_PLUS_RUNTIME || carPlusStartupAttempted || isFinishing || isDestroyed) return
        if (SessionController.isCarOffline) return
        if (!DisclaimerPrefs.hasAcceptedCurrent(this)) return
        carPlusStartupAttempted = true
        startCarPlusReceiver()
    }

    private fun openSystemCarPlus() {
        if (!DisclaimerPrefs.hasAcceptedCurrent(this)) return
        if (!hasCarPlusPermissions()) {
            openCarPlusAfterPermission = true
            startCarPlusReceiver()
            return
        }
        CarPlusReceiver.start(this)
        CarPlusReceiver.openSystemCarPlus(this) {
            runOnUiThread {
                Toast.makeText(this, R.string.open_system_car_plus_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** 蓝牙权限授权后自动继续启动连接。 */
    private var pendingStartAfterBtPermission: Boolean? = null

    /** 「取消连接」确认弹窗（防误触）：中止当前连接尝试并断开蓝牙/WiFi 链路。 */
    private fun promptCancelConnecting() {
        DarkMessageDialog.show(
            activity = this,
            title = "取消连接？",
            message = "将停止当前连接尝试：蓝牙与 WiFi 链路都会断开，需重新点「启动连接」。",
            actions =
                listOf(
                    DarkMessageDialog.Action("继续连接") {},
                    DarkMessageDialog.Action("取消连接", DarkMessageDialog.Emphasis.DANGER) {
                        // 关闭仍在等待的「加入热点」确认弹窗（其 onChoice(null) 会与
                        // connectCancelled 一起让 autoJoin 流程立即中止）
                        wifiJoinDialog?.cancel()
                        wifiJoinDialog = null
                        SessionController.cancelConnecting(this)
                    },
                ),
        )
    }

    /**
     * 启动后自动连接（默认开启，开关在「连接准备」卡里）。
     *
     * 满足全部前置条件才发起，任一不满足就**原地返回、保留请求**，由对应时机再调一次：
     * - 免责声明未接受 → 等 [showDisclaimer] 的 onAccept 回调续上；
     * - ICCOA 权限未授予 → 等权限回调续上；
     * - 已在连接/已连接 → 不重复发起。
     *
     * 与「启动连接」共用权限、蓝牙开关和 WLAN 检查，再按自动连接策略启动会话。
     * [SessionController.startConnection] 本身幂等，不会双开。
     */
    private fun maybeAutoConnect() {
        if (!autoConnectRequested || isFinishing || isDestroyed) return
        if (SessionController.isCarOffline) return
        if (!AutoConnectPrefs.enabled(this)) {
            autoConnectRequested = false
            log("AUTOCONNECT: 已关闭——等待手动点「启动连接」")
            return
        }
        if (!DisclaimerPrefs.hasAcceptedCurrent(this)) return
        if (!BuildConfig.CAR_PLUS_RUNTIME || !hasCarPlusPermissions()) return
        if (SessionController.isSessionRunning() || SessionController.sessionStarting.get()) return
        if (heroState != HeroState.IDLE) return

        autoConnectRequested = false
        log("AUTOCONNECT: 打开 App 即自动发起连接（可在「连接准备」里关闭）")
        requestConnection(automatic = true)
    }

    private fun rearmAfterDeparture(launch: Intent?, restoringActivity: Boolean = false): Boolean {
        if (!SessionController.isCarOffline || launch == null) return false
        if (!CarIdlePolicy.shouldReconnectAfterAppLaunch(
                action = launch.action,
                categories = launch.categories,
                flags = launch.flags,
                restoringActivity = restoringActivity,
                autoConnectEnabled = AutoConnectPrefs.enabled(this),
            )
        ) return false
        CarIdleGuard.cancel(this)
        CarIdlePrefs.setOfflineCarAddress(this, null)
        carPlusStartupAttempted = false
        log("AUTOCONNECT: 重新打开 App，发起车机连接")
        return true
    }

    private fun isBatteryExempt(): Boolean =
        getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(packageName) == true

    private fun updateKeepAliveStatus() {
        findViewById<TextView>(R.id.keepAliveStatus).text =
            if (isBatteryExempt()) "已豁免电池优化 · 查看其他设置" else "检查电池和后台锁定"
    }

    private fun showKeepAliveGuide() {
        val batteryStatus = if (isBatteryExempt()) "已豁免" else "尚未豁免"
        DarkMessageDialog.show(
            activity = this,
            title = "保活配置检查",
            message = "电池优化：$batteryStatus。可在应用详情的「耗电管理」允许后台运行。\n\n" +
                "锁定后台：打开最近任务，在本应用卡片的菜单中选择「锁定」。",
            actions = listOf(
                DarkMessageDialog.Action("设置电池与后台运行", DarkMessageDialog.Emphasis.PRIMARY) {
                    if (isBatteryExempt()) openKeepAliveAppDetails() else requestBatteryExemption()
                },
                DarkMessageDialog.Action("查看后台锁定步骤") {
                    DarkMessageDialog.show(
                        activity = this,
                        title = "锁定后台",
                        message = "打开手机的最近任务，找到 CarPToCar+ICCOA，点卡片右上角的菜单并选择「锁定」。卡片出现锁图标后，就设置好了。",
                        actions = listOf(DarkMessageDialog.Action("知道了", DarkMessageDialog.Emphasis.PRIMARY) {}),
                    )
                },
            ),
        )
    }

    private fun openKeepAliveAppDetails() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
        if (runCatching { startActivity(intent) }.isFailure) {
            snack("请在系统设置的应用管理中打开本应用详情", 5_000L)
        }
    }

    /** 拉起系统的「忽略电池优化」授权页；部分 ROM 不认包名直连，退回列表页。 */
    private fun requestBatteryExemption() {
        val direct =
            Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:$packageName"),
            )
        val fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        val opened =
            runCatching { startActivity(direct) }.isSuccess ||
                runCatching { startActivity(fallback) }.isSuccess
        log(
            if (opened) {
                "POWER: 已打开电池优化设置——请把本应用设为「不限制」"
            } else {
                "POWER: 无法打开电池优化设置，请手动在系统设置里允许本应用后台运行"
            },
        )
    }

    /**
     * 启动连接的前置检查：蓝牙权限 → 蓝牙开关 → WLAN 位置权限（仅 Android 12/12L）。
     * 任一不满足都弹窗引导（蓝牙关闭时可一键跳系统蓝牙设置），通过后回调继续。
     */
    private fun ensureBluetoothReady(automatic: Boolean) {
        val granted =
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
        if (!granted) {
            pendingStartAfterBtPermission = automatic
            permissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
            return
        }
        ensureWifiLocationReady(automatic) {
            val on =
                try {
                    (getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager)
                        .adapter
                        ?.isEnabled == true
                } catch (_: SecurityException) {
                    true
                }
            if (!on) {
                DarkMessageDialog.show(
                    activity = this,
                    title = "蓝牙未开启",
                    message = "与车机的无线配对通信依赖蓝牙。请先开启手机蓝牙，并确认已与车机配对。",
                    actions =
                        listOf(
                            DarkMessageDialog.Action("取消") {},
                            DarkMessageDialog.Action("去开启蓝牙", DarkMessageDialog.Emphasis.PRIMARY) {
                                runCatching {
                                    startActivity(
                                        android.content.Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS),
                                    )
                                }.onFailure { log("BT: 无法打开蓝牙设置，请手动开启") }
                            },
                        ),
                )
                log("BT: 蓝牙未开启——本次连接未发起")
                return@ensureWifiLocationReady
            }
            startConnectionNow(automatic)
        }
    }

    /**
     * 应用专属网络（WifiNetworkSpecifier）在 Android 12/12L 需要位置权限与位置服务；
     * 13+ 由 NEARBY_WIFI_DEVICES 接管、不再要求定位。缺权限时系统会直接
     * `requestNetwork` 失败（表现为「未能加入车机热点」，很难自查），因此前置拦截。
     */
    private fun ensureWifiLocationReady(automatic: Boolean, onReady: () -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+：应用专属网络（WifiNetworkSpecifier）与 WiFi 建议
            // （WifiNetworkSuggestion）**都**要求 NEARBY_WIFI_DEVICES 运行时权限。
            // 旧实现以为 13+ 不需要就原地放行——结果 requestNetwork 在无权限时
            // 静默挂起：既不回调 onAvailable 也不回调 onUnavailable，也不抛异常。
            // 2026-10-29 BMW iD9 日志里连续 8 轮「发起加入后一条日志都没有」
            // 就是这个形态，必须在这里前置拦截。
            val granted =
                ContextCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES) ==
                    PackageManager.PERMISSION_GRANTED
            if (!granted) {
                pendingStartAfterLocationPermission = automatic
                nearbyWifiPermissionLauncher.launch(Manifest.permission.NEARBY_WIFI_DEVICES)
                return
            }
            onReady()
            return
        }
        val granted =
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        if (!granted) {
            pendingStartAfterLocationPermission = automatic
            locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            return
        }
        val locationOn =
            try {
                androidx.core.location.LocationManagerCompat
                    .isLocationEnabled(getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager)
            } catch (_: Exception) {
                true
            }
        if (!locationOn) {
            DarkMessageDialog.show(
                activity = this,
                title = "需要开启位置服务",
                message =
                    "Android 12 加入车机热点（应用专属 WiFi）要求位置服务开启——系统用它判定" +
                        "可连接的 WiFi，不会记录你的位置。\n\n请打开位置服务后重试；也可以到" +
                        "WLAN 设置手动连接热点。",
                actions =
                    listOf(
                        DarkMessageDialog.Action("取消") {},
                        DarkMessageDialog.Action("去开启", DarkMessageDialog.Emphasis.PRIMARY) {
                            runCatching {
                                startActivity(
                                    android.content.Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS),
                                )
                            }.onFailure { log("WIFI: 无法打开位置服务设置，请手动开启") }
                        },
                    ),
            )
            log("WIFI: 位置服务未开启——Android 12 下应用专属网络不可用，本次连接未发起")
            return
        }
        onReady()
    }

    /** 位置权限授权后自动继续启动连接（仅 Android 12/12L）。 */
    private var pendingStartAfterLocationPermission: Boolean? = null

    private val locationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            log("ACCESS_FINE_LOCATION 授权: $granted")
            val automatic = pendingStartAfterLocationPermission
            pendingStartAfterLocationPermission = null
            if (granted && automatic != null) requestConnection(automatic)
        }

    /** Android 13+ 的 NEARBY_WIFI_DEVICES 授权后自动续跑（与位置权限共用同一 pending 标志）。 */
    private val nearbyWifiPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            log(
                "NEARBY_WIFI_DEVICES 授权: $granted" +
                    if (granted) "" else "——无法加入车机热点，请到「设置 > 应用 > 权限 > 附近的设备」授予",
            )
            val automatic = pendingStartAfterLocationPermission
            pendingStartAfterLocationPermission = null
            if (granted && automatic != null) requestConnection(automatic)
        }

    /**
     * 「断开连接」确认弹窗（防误触）：断开会立即中断车机画面，
     * 且投屏授权随保活服务停止而失效。
     */
    private fun promptDisconnect() {
        if (SessionController.disconnecting) return
        DarkMessageDialog.show(
            activity = this,
            title = "断开连接？",
            message =
                "将停止车机推流并关闭保活服务，车机画面会立即中断。\n\n" +
                    "注意：投屏授权会随服务停止失效。",
            actions =
                listOf(
                    DarkMessageDialog.Action("取消") {},
                    DarkMessageDialog.Action("断开", DarkMessageDialog.Emphasis.DANGER) {
                        SessionController.disconnect(this)
                    },
                ),
        )
    }

    /**
     * 「启动连接」放行检查。
     *
     * 旧实现这里是 Shizuku 硬门禁（虚拟屏/注入都靠它）。2026-10-24 起 Shizuku 依赖
     * 画面来自本机 ICCOA 接收端。
     *
     * 2026-10-26 起这里加了**本机蓝牙地址硬门禁**：地址是整个链路的设备身份
     * （0x4300/0x4E0E、AirPlay SETUP、ctrl 广播 id 都用它），没有它连上去接收端
     * 只会回 SETUP 403，用户看到的却是一句「连接失败」，无从下手。所以未填写时
     * 直接拦下并提示，而不是让他白等一轮。
     *
     * 放在这里而不是按钮的点击回调里，是因为所有入口（手动点、启动后自动连接、
     * 蓝牙/位置权限授权后续跑）都汇到这一个函数，改一处即可全覆盖。
     */
    private fun requestConnection(automatic: Boolean = false) {
        if (!BuildConfig.CAR_PLUS_RUNTIME) {
            snack(CarPlusReceiver.status)
            return
        }
        if (BuildConfig.CAR_PLUS_RUNTIME && !hasCarPlusPermissions()) {
            startCarPlusReceiver()
            return
        }
        if (!requireBluetoothMac()) return
        ensureBluetoothReady(automatic)
    }

    /**
     * 当前可用的本机蓝牙地址：手填优先 → 已解析缓存。都没有返回 null。
     *
     * 只读缓存（[DeviceIdentity.cachedBluetoothMac]），**不**触发 shell 解析——
     * 本方法在 UI 线程上每次 tick 都可能被调用，绝不能再花 4 秒去 `content query`。
     * onCreate 已经用完整解析跑过一轮，正常机型这里能直接拿到真实地址。
     */
    private fun effectiveBluetoothMac(): String? =
        DeviceIdentity.normalize(macInput.text.toString())
            ?: DeviceIdentity.overrideBluetoothMac(this)
            ?: DeviceIdentity.cachedBluetoothMac()?.takeIf { it != DeviceIdentity.PLACEHOLDER_MAC }

    /**
     * 本机蓝牙地址门禁：未填写就拦下「启动连接」并告知用户。
     *
     * @return true = 地址已就绪，可以继续；false = 未填写，已提示并中止本次启动。
     */
    private fun requireBluetoothMac(): Boolean {
        // 输入框里填了合法地址但忘了点「保存」：这里顺手落盘，
        // 免得出现「我明明填了，还提示我没填」这种最让人恼火的交互
        val typed = DeviceIdentity.normalize(macInput.text.toString())
        if (typed != null && DeviceIdentity.overrideBluetoothMac(this) != typed) {
            DeviceIdentity.setOverride(this, typed)
            log("MAC: 已自动保存输入框中的本机蓝牙地址 $typed")
        }
        val mac = effectiveBluetoothMac()
        refreshMacGate()
        if (mac != null) return true

        snack(TIP_MAC_MISSING, 3_600L)
        log("MAC: 本机蓝牙地址未填写——已拦截本次「启动连接」")
        // 若高级设置处于折叠状态，先自动展开以便用户看清输入框
        if (::advancedConnBody.isInitialized && advancedConnBody.visibility != View.VISIBLE) {
            advancedConnBody.visibility = View.VISIBLE
            if (::advancedConnChevron.isInitialized) {
                advancedConnChevron.rotation = 180f
            }
        }
        // 焦点落到输入框并滚到可视区，用户下一步该做什么一眼可见
        macInput.requestFocus()
        macInput.post {
            macInput.requestRectangleOnScreen(
                android.graphics.Rect(0, 0, macInput.width, macInput.height),
                true,
            )
        }
        return false
    }

    /**
     * 刷新门禁相关的界面：输入框下方提示行 + （IDLE 时）英雄卡副标题与按钮置灰。
     *
     * 「置灰但仍可点」是刻意的：完全 `isEnabled=false` 的话，用户点下去没有任何
     * 反馈，只会以为 App 卡了；保持可点、点击即给出「未填写」提示才是有效引导。
     */
    private fun refreshMacGate() {
        if (!::macInput.isInitialized || !::ctaButton.isInitialized) return
        val mac = effectiveBluetoothMac()
        if (::tvMacHint.isInitialized) {
            tvMacHint.visibility = View.VISIBLE
            if (mac == null) {
                tvMacHint.setTextColor(getColor(R.color.m3_error))
                tvMacHint.text = "未填写 —— 不填写本机蓝牙地址将无法启动连接"
            } else {
                tvMacHint.setTextColor(getColor(R.color.m3_mono_dim))
                tvMacHint.text = if (DeviceIdentity.hasOverride(this)) "已保存（手填）：$mac" else "自动解析：$mac"
            }
        }
        if (::advancedConnSubline.isInitialized) {
            advancedConnSubline.text =
                if (mac == null) "未填写本机 MAC（点击展开配置）"
                else "本机蓝牙地址已设置"
        }
        if (heroState == HeroState.IDLE) {
            ctaButton.alpha = if (mac == null) CTA_BLOCKED_ALPHA else 1f
            heroSubline.text = idleConnectionHint(macMissing = mac == null)
        }
    }

    /** 「启动连接」主体（权限检查留在这里；会话启动交给 [SessionController]，幂等不双开）。 */
    private fun startConnectionNow(automatic: Boolean) {
        runOnUiThread {
            if (SessionController.disconnecting) {
                snack("正在断开，请稍候")
                return@runOnUiThread
            }
            if (BuildConfig.CAR_PLUS_RUNTIME) CarPlusReceiver.start(this)
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                permissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
            }
            // 通知权限（API 33+）：前台服务通知可见；拒绝也不影响保活
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(this, "android.permission.POST_NOTIFICATIONS") !=
                PackageManager.PERMISSION_GRANTED
            ) {
                permissionLauncher.launch("android.permission.POST_NOTIFICATIONS")
            }
            SessionController.startConnection(this, automatic)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        // 旋转/重建：沿用原倒计时截止时刻（onResume 据此续弹）
        disclaimerDeadlineAt = savedInstanceState?.getLong(KEY_DISCLAIMER_DEADLINE, 0L) ?: 0L
        logIssuesOnly = savedInstanceState?.getBoolean(KEY_LOG_ISSUES_ONLY) == true
        resolutionHistoryPeer = savedInstanceState?.getString(KEY_RESOLUTION_PEER_ADDRESS)?.let { address ->
            TargetDevicePrefs.Target(address, savedInstanceState.getString(KEY_RESOLUTION_PEER_NAME) ?: address)
        }

        deviceSubtitle = findViewById(R.id.deviceSubtitle)
        carPlusStatus = findViewById(R.id.carPlusStatus)
        supportsSystemCarPlus = CarPlusReceiver.hasSystemCarPlus(this)
        findViewById<View>(R.id.openSystemCarPlus).apply {
            if (supportsSystemCarPlus) {
                visibility = View.VISIBLE
                setOnClickListener { openSystemCarPlus() }
            }
        }
        carPlusStatus.setOnClickListener { startCarPlusReceiver(refresh = true) }
        signalWaves = findViewById(R.id.signalWaves)
        hubPlate = findViewById(R.id.hubPlate)
        hubCar = findViewById(R.id.hubCar)
        hubCaption = findViewById(R.id.hubCaption)
        heroStateText = findViewById(R.id.heroStateText)
        heroSubline = findViewById(R.id.heroSubline)
        heroStats = findViewById(R.id.heroStats)
        heroMediaChannels = findViewById(R.id.heroMediaChannels)
        displayResolutionNote = findViewById(R.id.displayResolutionNote)
        heroProgress = findViewById(R.id.heroProgress)
        ctaButton = findViewById(R.id.ctaButton)
        reconnectCarPlusButton = findViewById(R.id.btnReconnectCarPlus)
        ctaIcon = findViewById(R.id.ctaIcon)
        ctaLabel = findViewById(R.id.ctaLabel)
        readyBluetooth = findViewById(R.id.readyBluetooth)
        readyWifi = findViewById(R.id.readyWifi)
        ssidInput = findViewById(R.id.ssidInput)
        carWifiStatus = findViewById(R.id.carWifiStatus)
        macInput = findViewById(R.id.macInput)
        tvMacHint = findViewById(R.id.tvMacHint)
        btDesc = findViewById(R.id.btDesc)
        btBadge = findViewById(R.id.btBadge)
        btnPickTarget = findViewById(R.id.btnPickTarget)
        logBody = findViewById(R.id.logBody)
        logChevron = findViewById(R.id.logChevron)
        switchLog = findViewById(R.id.switchLog)
        logText = findViewById(R.id.logText)
        snackbar = findViewById(R.id.snackbar)

        deviceSubtitle.text = deviceName()
        logText.movementMethod = ScrollingMovementMethod()
        // 控制台在长页面 ScrollView 内：按下时申请父级不拦截竖向手势，否则无法手动滚动
        logText.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN ->
                    v.parent.requestDisallowInterceptTouchEvent(true)
                android.view.MotionEvent.ACTION_MOVE,
                android.view.MotionEvent.ACTION_UP,
                -> updateLogBottomButton()
            }
            false
        }
        findViewById<ImageView>(R.id.btnLogBottom).setOnClickListener {
            // 跳到底部：滚动后自动跟底也随之恢复
            scrollConsoleToBottom()
        }

        // 进程重启兜底：若上次会话未正常退役，「常亮」改过的屏幕超时需要恢复
        run {
            val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
            if (prefs.contains("screen_off_timeout_backup") &&
                VirtualLauncher.appDisplayId < 0
            ) {
                val backup = prefs.getInt("screen_off_timeout_backup", 60_000)
                prefs.edit().remove("screen_off_timeout_backup").apply()
                Bg.run("screen-timeout-restore") {
                    SysShell.exec(
                        "settings put system screen_off_timeout $backup",
                    )
                }
            }
        }

        // 会话中枢（进程级）：登记 applicationContext + 创建会话对象；
        // 挂屏注册：日志续显 + SSID 回填
        SessionController.ensure(this)
        SessionController.logListener = logListener
        // 连接流程的重要提示（WiFi 未开启/热点加入限制等）以 Snackbar 长显
        SessionController.uiHintSink = uiHintSinkRef
        // 加入热点确认弹窗：继续自动加入 / 打开 WLAN 设置手动连。
        // 取消连接时 cancel() 联动关闭（触发 onChoice(null) → 流程检查取消标志中止）
        SessionController.dialogSink = { spec, onChoice ->
            runOnUiThread {
                if (isFinishing || isDestroyed) {
                    onChoice(null)
                    return@runOnUiThread
                }
                var answered = false
                // 取消（返回键/点外部/被 cancelConnecting 联动关闭）= null，流程据此中止；
                // 「打开 WLAN」除了回调 false，还要把用户送到系统 WLAN 页手动连。
                val dialog =
                    DarkMessageDialog.show(
                        activity = this,
                        title = spec.title,
                        message = spec.message,
                        actions =
                            listOf(
                                DarkMessageDialog.Action(spec.negative) {
                                    answered = true
                                    runCatching {
                                        startActivity(
                                            android.content.Intent(android.provider.Settings.ACTION_WIFI_SETTINGS),
                                        )
                                    }
                                    onChoice(false)
                                },
                                DarkMessageDialog.Action(
                                    spec.positive,
                                    DarkMessageDialog.Emphasis.PRIMARY,
                                ) {
                                    answered = true
                                    onChoice(true)
                                },
                            ),
                        onCancel = { if (!answered) onChoice(null) },
                    )
                wifiJoinDialog = dialog
            }
        }
        SessionController.ssidSink = ssidSink
        renderConsole(SessionController.logText())

        setUpUiControls()
        refreshCarHotspot()
        // 以横屏启动时不会触发 onConfigurationChanged，这里按当前方向定一次排布。
        // 首次调用时 contentArea 未测量（width=0，只能退回屏宽估算），
        // 布局完成后再精确重算一次，避免英雄卡宽度与右栏让位差几个像素。
        val startLandscape =
            resources.configuration.orientation ==
                android.content.res.Configuration.ORIENTATION_LANDSCAPE
        applyContentLayout(startLandscape)
        findViewById<android.widget.FrameLayout>(R.id.contentArea).post {
            if (!isFinishing && !isDestroyed) applyContentLayout(startLandscape)
        }

        // 免责声明：未接受当前版本时强制弹（不可取消、需倒计时 + 滑到底）。
        // savedState 非空（旋转/重建）时复用原截止时刻，不重新计时。
        val needsDisclaimer =
            savedInstanceState == null &&
                needsDisclaimerAtLaunch(
                    DisclaimerPrefs.acceptedVersion(this),
                    DisclaimerPrefs.CURRENT_VERSION,
                )
        if (needsDisclaimer) {
            showDisclaimer(requireAcceptance = true)
        }

        // Thanox 和桌面都通过主界面启动，恢复旧任务时不重复发起连接。
        rearmAfterDeparture(intent, restoringActivity = savedInstanceState != null)
        if (savedInstanceState == null) autoConnectRequested = true
    }

    /** 底部「免责声明」入口：只读重看（不限制时长、不要求滑到底）。 */
    private fun showDisclaimer(requireAcceptance: Boolean) {
        if (activeDisclaimer?.isShowing == true) return
        if (requireAcceptance && disclaimerDeadlineAt == 0L) {
            disclaimerDeadlineAt = SystemClock.elapsedRealtime() + DisclaimerDialog.COUNTDOWN_MS
        }
        activeDisclaimer =
            DisclaimerDialog
                .show(
                    activity = this,
                    requireAcceptance = requireAcceptance,
                    deadlineAt = disclaimerDeadlineAt,
                    onDecline = {
                        // 放弃使用：结束本任务并退出（不用 System.exit，保留正常生命周期收尾）。
                        // 若已有会话在跑，先正常断开会话。
                        autoConnectRequested = false
                        runCatching { SessionController.disconnect(this) }
                        finishAndRemoveTask()
                    },
                    // 接受后再续上冷启动自动连接（未接受前不能发起任何连接）
                    onAccept = {
                        mainHandler.postDelayed({
                            startCarPlusOnLaunch()
                            maybeAutoConnect()
                        }, 400L)
                    },
                    onDismiss = { activeDisclaimer = null },
                )
    }

    /** onCreate 的 UI 初始化尾部：开关/按钮/折叠卡（会话逻辑不变，仅挂新界面）。 */
    private fun setUpUiControls() {
        val preferences = getSharedPreferences("settings", Context.MODE_PRIVATE)
        // 清理旧版本遗留的模式偏好（模式已固定为「隐形屏」启动器）
        if (preferences.contains("mode")) {
            preferences.edit().remove("mode").apply()
        }

        // ---- 英雄卡主操作 ----
        ctaButton.setOnClickListener {
            when (heroState) {
                HeroState.CONNECTED -> promptDisconnect()
                // 前置检查：蓝牙权限和开关
                HeroState.IDLE -> requestConnection()
                // 连接中可取消：卡在错误网络/等待回连时用户主动脱身（弹窗防误触）
                HeroState.CONNECTING -> promptCancelConnecting()
                null -> {}
            }
        }
        reconnectCarPlusButton.setOnClickListener {
            if (CarPlusReceiver.requestReconnect()) {
                snack("正在请求重新连接 Car+")
            } else {
                snack("Car+ 接收端暂不可用")
            }
        }

        // ---- 连接准备 ----
        // 启动后自动连接：默认开启。打开 App 即发起连接，不必每次点英雄卡；
        // 需要在连之前改「下次连接起效」的项（如分辨率）时关掉它。
        val autoConnectNote = findViewById<TextView>(R.id.autoConnectNote)
        val switchAutoConnect = findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switchAutoConnect)
        switchAutoConnect.isChecked = AutoConnectPrefs.enabled(this)
        autoConnectNote.text =
            if (switchAutoConnect.isChecked) {
                "打开 App 即自动发起连接（默认开启）"
            } else {
                "关闭后需手动点「启动连接」"
            }
        switchAutoConnect.setOnCheckedChangeListener { _, checked ->
            AutoConnectPrefs.setEnabled(this, checked)
            autoConnectNote.text =
                if (checked) "打开 App 即自动发起连接（默认开启）" else "关闭后需手动点「启动连接」"
            log("AUTOCONNECT: 启动后自动连接已${if (checked) "开启" else "关闭"}")
        }

        // 兼容「只认苹果设备」的车机（默认关，2026-11-07）：宝马 iD6 在蓝牙设备列表里
        // 直接给本机打「设备不支持 Apple CarPlay」，那句判定发生在 iAP2 之前——改报文没用，
        // 它看的是发现层（EIR 里的 iPhone CarPlay UUID / SDP 服务），而 Android 应用层
        // 写不了 EIR。这里能做的是多注册两条占位 SDP 记录，并让监听常开
        // （先配对、后开 App 的那一瞥也能看到服务）。见 BtCompatPrefs。
        val switchBtCompat =
            findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switchBtCompat)
        val btCompatNote = findViewById<TextView>(R.id.btCompatNote)
        fun renderBtCompatNote(checked: Boolean) {
            btCompatNote.text =
                if (checked) {
                    "已开启兼容模式，应用会保持蓝牙监听。"
                } else {
                    "车机提示「不支持 Apple CarPlay」时可尝试开启。"
                }
        }
        switchBtCompat.isChecked = BtCompatPrefs.enabled(this)
        renderBtCompatNote(switchBtCompat.isChecked)
        switchBtCompat.setOnCheckedChangeListener { _, checked ->
            BtCompatPrefs.setEnabled(this, checked)
            renderBtCompatNote(checked)
            SessionController.applyBtCompat(checked, fromUser = true)
            log(
                "BT-PROBE: 兼容模式已${if (checked) "开启" else "关闭"}" +
                    if (checked) {
                        "——额外注册 CarPlay 占位 SDP 记录并保持监听常开"
                    } else {
                        "——占位记录已撤下（不影响已建立的控制链路）"
                    },
            )
            if (checked &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                // 这里只申请权限，**不能**走 ensureBluetoothReady：它授权成功后会直接
                // 发起整轮连接（pendingStartAfterBtPermission），而用户只是想开个开关。
                // 授权后由兼容模式看门狗在 30 秒内把占位记录挂上。
                permissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }

        // 本机蓝牙地址：Honor/MagicOS 等机型把 MAC 全面脱敏（连 adb shell 都读不到），
        // 而 WinPlay 系接收端按它做设备过滤——手填是唯一可行来源，存进 settings 偏好，
        // DeviceIdentity 会优先采用（影响 0x4300/0x4E0E、AirPlay SETUP 与 ctrl 广播 id）。
        macInput.setText(DeviceIdentity.overrideBluetoothMac(this) ?: "")
        findViewById<View>(R.id.btnViewMac).setOnClickListener {
            if (runCatching { startActivity(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)) }.isFailure) {
                runCatching { startActivity(Intent(Settings.ACTION_SETTINGS)) }
                    .onFailure { snack("无法打开系统设置") }
            }
        }
        findViewById<TextView>(R.id.btnSaveMac).setOnClickListener {
            val result = DeviceIdentity.setOverride(this, macInput.text.toString())
            val resolved = DeviceIdentity.bluetoothMac(this, forceRetry = true)
            when {
                result == null -> {
                    snack("地址格式不对——需 12 位十六进制，如 AA:BB:CC:DD:EE:FF")
                    log("MAC: 保存失败——输入无法解析为蓝牙地址（${macInput.text}）")
                }
                resolved == DeviceIdentity.PLACEHOLDER_MAC -> {
                    macInput.setText("")
                    snack("已清除手填地址——将使用自动解析结果")
                    log("MAC: 已清除手填地址，当前自动解析=${DeviceIdentity.PLACEHOLDER_MAC}（接收端会拒绝）")
                }
                else -> {
                    macInput.setText(resolved)
                    snack("已保存 $resolved")
                    log("MAC: 本机蓝牙地址已设为 $resolved（0x4300/SETUP/ctrl 广播都用它）")
                }
            }
            // 保存/清除都直接影响「启动连接」门禁，立刻同步提示行与按钮状态
            refreshMacGate()
        }

        findViewById<TextView>(R.id.btnWifi).setOnClickListener {
            val ssid = ssidInput.text.toString().trim()
            // 自动加入不可用时的手动兜底：密码先进剪贴板，系统 WLAN 页粘贴即可
            val psk = SessionController.hotspotPassword(this, ssid)
            if (psk != null) {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("carplay-wifi", psk))
                snack("热点密码已复制（${psk.length} 位）——粘贴到密码框即可")
                log(
                    "WiFi: 热点密码已复制到剪贴板，选择 \"$ssid\" 粘贴密码；" +
                        "若提示\"无法访问互联网\"请选择保持连接",
                )
            } else {
                snack("暂无热点密码——蓝牙连上后自动获取")
                log("WiFi: 尚未拿到热点密码（需蓝牙会话 0x5703）——打开 WLAN 设置手动选择 \"$ssid\"")
            }
            try {
                startActivity(android.content.Intent(android.provider.Settings.ACTION_WIFI_SETTINGS))
            } catch (_: Exception) {
                log("WiFi: 无法打开WLAN设置，请手动前往 设置 > WLAN")
            }
        }

        // 目标车机选择：配对了多台车机时指定唯一目标（只向它发请求/只接受它连入）
        btnPickTarget.setOnClickListener { showTargetPicker() }

        // ---- 折叠区：高级与兼容设置 ----
        advancedConnBody = findViewById(R.id.advancedConnBody)
        advancedConnChevron = findViewById(R.id.advancedConnChevron)
        advancedConnSubline = findViewById(R.id.advancedConnSubline)
        bindExpandable(R.id.advancedConnHeader, advancedConnBody, advancedConnChevron)

        // ---- 折叠卡：日志 ----
        bindExpandable(R.id.logHeader, logBody, logChevron)
        findViewById<View>(R.id.keepAliveCheck).setOnClickListener { showKeepAliveGuide() }

        // ---- 日志开关（默认开；关闭后不采集、不渲染；选择持久化） ----
        var logEnabled = preferences.getBoolean("logEnabled", true)
        SessionController.logEnabled = logEnabled
        switchLog.isChecked = logEnabled
        switchLog.setOnCheckedChangeListener { _, checked ->
            logEnabled = checked
            SessionController.logEnabled = checked
            preferences.edit().putBoolean("logEnabled", checked).apply()
            renderConsole(SessionController.logText())
        }
        bindLogFilters()
        renderConsole(SessionController.logText())
        findViewById<ImageView>(R.id.btnClearLog).setOnClickListener {
            // 清除缓冲后必须显式重渲染：日志回调是增量追加语义，clearLog 的通知
            // 无法清 UI（见 SessionController.clearLog 注释，「点了没反应」根因）
            SessionController.clearLog()
            renderConsole(SessionController.logText())
        }
        findViewById<ImageView>(R.id.btnCopyLog).setOnClickListener {
            val text = SessionController.logText()
            if (text.isEmpty()) {
                snack("日志为空")
                return@setOnClickListener
            }
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("CarPToCar+ICCOA 日志", text))
            snack("已复制 ${text.length} 字符日志")
        }
        findViewById<View>(R.id.btnCopyIssueSummary).setOnClickListener { copyIssueSummary() }
        findViewById<ImageView>(R.id.btnExportLog).setOnClickListener { exportLogFile() }
        // 长按导全部历史：默认只导本次启动，历史日志量大时按需手动取
        findViewById<ImageView>(R.id.btnExportLog).setOnLongClickListener {
            exportLogFile(LogExporter.Scope.ALL)
            true
        }

        // 通知使用权（媒体会话枚举，音乐卡数据源）：Shizuku 移除后没有静默授予通道，
        // 需要用户自己在系统设置里打开；这里不再阻塞启动。

        // ---- 显示设置（原「显示」面板迁移至首页，2026-10-24） ----
        bindDisplayCard()

        VirtualLauncher.logSink = { line -> log(line) }

        log("ICCOA: 打开应用后自动启动接收端，可点首页画面来源状态行重试")

        // 版本号走运行时读取：BuildConfig.VERSION_NAME 会被 Kotlin 折叠成字面量，
        // 改版本号后增量编译不重编本类时，日志会印上一版的号（1.2.13 踩过一次）。
        log("${getString(R.string.app_name)} ${AppVersion.nameWithTag(this)}（画面=ICCOA Car+ 视频）就绪")
        log("真车：与车机蓝牙配对 + 手机加入车机热点 -> 点「启动连接」（自动主动连入车机）")
        log("测试台：两台手机蓝牙配对 -> 备用机跑 xcertplay 无线模式 -> 点「启动连接」等待回连")
        // 接收端（WinPlay 等）按真实蓝牙地址过滤设备：解析不到就等于注定 SETUP 403，
        // 必须在启动时就明确告知，而不是让用户对着「接收端拒绝了会话」无从上手。
        val identityMac = DeviceIdentity.bluetoothMac(this)
        if (identityMac == DeviceIdentity.PLACEHOLDER_MAC) {
            log(
                "MAC: 未取到本机真实蓝牙地址（厂商脱敏/Shizuku 已移除）——" +
                    "请在上方「本机蓝牙地址」填入后保存；否则 WinPlay 类接收端会回 SETUP 403",
            )
        } else {
            val from = if (DeviceIdentity.hasOverride(this)) "手填" else "自动解析"
            log("MAC: 本机蓝牙地址=$identityMac（$from）")
        }
        // 启动即按上面的解析结果刷新门禁（副标题/按钮置灰/输入框提示行）
        refreshMacGate()
    }

    private fun bindExpandable(
        headerId: Int,
        body: View,
        chevron: ImageView,
    ) {
        findViewById<View>(headerId).setOnClickListener {
            val opening = body.visibility != View.VISIBLE
            body.visibility = if (opening) View.VISIBLE else View.GONE
            chevron
                .animate()
                .rotation(if (opening) 180f else 0f)
                .setDuration(250L)
                .start()
        }
    }

    // ==================== 英雄卡状态机 ====================

    private fun updateCarPlusStatus() {
        val text = if (heroVideoSender?.let(SessionController::isCarPlusPictureReady) == true) {
            "CarPlay 正在接收 ICCOA Car+ 画面"
        } else {
            CarPlusReceiver.status
        }
        if (carPlusStatus.text != text) carPlusStatus.text = text
    }

    private fun tickHero() {
        val sender = SessionController.videoSender?.takeIf {
            it.isRunning && SessionController.currentClient?.isRetired != true
        }
        if (sender !== heroVideoSender) {
            heroVideoSender = sender
            connectedAt = SystemClock.elapsedRealtime()
            lastFpsSampleAt = 0L
            actualSentFps = null
        }
        val s =
            when {
                sender != null -> HeroState.CONNECTED
                SessionController.isSessionRunning() || SessionController.sessionStarting.get() ->
                    HeroState.CONNECTING
                else -> HeroState.IDLE
            }
        val offline = SessionController.isCarOffline
        if (s != heroState || offline != heroCarOffline) {
            heroState = s
            heroCarOffline = offline
            applyHeroState(s)
        }
        // 连接中：副标题跟随阶段提示（英雄卡内即可见，不必划到底部看日志）
        if (s == HeroState.CONNECTING) {
            heroSubline.text = connectingMessage()
        } else if (s == HeroState.IDLE) {
            heroSubline.text = idleConnectionHint(effectiveBluetoothMac() == null)
        }
        refreshCarHotspot()
        updateConnectedSubline()
        updateHeroStats()
        updateHeroMediaChannels(sender)
        refreshResolutionInputs()
        refreshCarPlusFrameRate()
        updateDisplayResolutionNote()
        updateReadyRow()
        updateDeviceSubtitle()
        updateCarPlusStatus()
    }

    private fun connectingMessage(): String =
        SessionController.reconnectWaitSeconds()?.let {
            if (it > 0) "车机正在释放上次连接，约 ${it} 秒后自动重连"
            else "正在重新连接车机…"
        } ?: SessionController.carPlusConnectionMessage()
        ?: DEFAULT_CONNECTING_HINT

    private fun idleConnectionHint(macMissing: Boolean): String =
        when {
            macMissing -> HINT_MAC_MISSING
            SessionController.isCarOffline -> "等待车机下次连接"
            else -> SessionController.lastConnectionFailure?.let { "上次连接 · $it" } ?: HINT_IDLE_READY
        }

    private fun effectiveStreamSize(): Pair<Int, Int>? {
        val w = SessionController.activeStreamWidth
        val h = SessionController.activeStreamHeight
        return if (w > 0 && h > 0) w to h else null
    }

    /**
     * 英雄卡统计行（仅连接时显示）：运行时分辨率 + 实测帧率。
     * 实际帧率 = 1s 轮询对已发帧计数取差值；新会话计数归零时先对齐基线。
     *
     * 分辨率报**像素**，不再是「100%」这种只有自己看得懂的比例：车机原值在前，
     * 码流与它不一致时用 `→` 接实际值。
     */
    private fun updateHeroStats() {
        if (heroState != HeroState.CONNECTED) return
        val sent = heroVideoSender?.framesSentTotal ?: return
        val now = SystemClock.elapsedRealtime()
        val elapsed = now - lastFpsSampleAt
        actualSentFps = if (lastFpsSampleAt > 0 && elapsed > 0 && sent >= lastSentFrames) {
            ((sent - lastSentFrames) * 1_000.0 / elapsed).roundToInt()
        } else null
        lastSentFrames = sent
        lastFpsSampleAt = now
        val carW = SessionController.activeCarWidth
        val carH = SessionController.activeCarHeight
        val stream = effectiveStreamSize()
        val resolution =
            when {
                carW <= 0 -> "—"
                !SessionController.activeCarResolutionFromCar -> {
                    // 车机没回 /info：这个值其实是手机横屏兜底，必须标明
                    "${carW}×$carH（手机兜底）"
                }
                stream == null || (stream.first == carW && stream.second == carH) -> "$carW×$carH"
                else -> "$carW×$carH → ${stream.first}×${stream.second}"
            }
        heroStats.text = "分辨率 $resolution · 实际发送 ${actualSentFps ?: "—"} fps"
    }

    private fun updateHeroMediaChannels(sender: VideoSender?) {
        if (sender == null || SessionController.videoSender !== sender) {
            heroMediaChannels.visibility = View.GONE
            return
        }
        val video = when (sender.outputMode) {
            VideoSender.OutputMode.H264_PASSTHROUGH -> "H.264 直通"
            VideoSender.OutputMode.SURFACE -> "Surface 重编码"
            VideoSender.OutputMode.SURFACE_FALLBACK -> "Surface 重编码（已回退）"
            null -> "正在准备视频输出"
        }
        val audio = SessionController.audioSender?.takeIf { it.isRunning }
        val audioText = when (audio?.acceptedByReceiver) {
            true -> {
                val codec = when (audio.codec) {
                    AudioCodec.PCM -> "PCM"
                    AudioCodec.AAC_LC -> "AAC-LC"
                }
                val rate = if (audio.sampleRate % 1_000 == 0) {
                    (audio.sampleRate / 1_000).toString()
                } else {
                    (audio.sampleRate / 1_000.0).toString()
                }
                val suffix = when {
                    audio.isCallAudio -> "（通话）"
                    audio.isMediaFallback -> "（已回退）"
                    else -> ""
                }
                "$codec $rate kHz$suffix"
            }
            false -> "车机未确认激活"
            null -> if (audio == null) "通道未建立" else "等待车机激活"
        }
        val details = "视频 · $video\n音频 · $audioText"
        if (heroMediaChannels.text != details) heroMediaChannels.text = details
        heroMediaChannels.visibility = View.VISIBLE
    }

    /** 显示本轮车机分辨率与下次投屏设置。 */
    private fun updateDisplayResolutionNote() {
        val target = displayResolutionNote
        val running = SessionController.videoSender?.isRunning == true
        val nextPeer = TargetDevicePrefs.get(this)
        val nextSize = DisplayPrefs.carPlusStreamSize(this, nextPeer?.address)
        val text = buildList {
            if (running) {
                val current = effectiveStreamSize().takeIf {
                    heroVideoSender?.let(SessionController::isCarPlusPictureReady) == true
                }
                add("当前投屏 · " + (current?.let { "${it.first}×${it.second}" } ?: "等待 Car+ 画面"))
                val w = SessionController.activeCarWidth
                val h = SessionController.activeCarHeight
                if (w > 0 && h > 0 && SessionController.activeCarResolutionFromCar) {
                    add("车机分辨率 · $w×$h")
                } else {
                    add("车机未提供分辨率")
                }
            }
            val next = when {
                nextPeer == null -> "按连接到的车机应用设置"
                nextSize == null -> "${nextPeer.name} · 自动读取"
                else -> "${nextPeer.name} · ${nextSize.width}×${nextSize.height}"
            }
            add("下次连接 · $next")
        }.joinToString("\n")
        if (target.text != text) target.text = text
    }

    private fun applyHeroState(s: HeroState) {
        signalWaves.setState(
            when (s) {
                HeroState.IDLE -> SignalWavesView.State.IDLE
                HeroState.CONNECTING -> SignalWavesView.State.CONNECTING
                HeroState.CONNECTED -> SignalWavesView.State.CONNECTED
            },
        )
        val (plateColor, carColor) =
            when (s) {
                HeroState.IDLE -> getColor(R.color.m3_surface_high) to getColor(R.color.m3_on_var)
                HeroState.CONNECTING ->
                    getColor(R.color.m3_pri_container) to getColor(R.color.m3_on_pri_container)
                HeroState.CONNECTED -> getColor(R.color.m3_primary) to getColor(R.color.m3_on_primary)
            }
        hubPlate.background.mutate().setTint(plateColor)
        hubCar.setColorFilter(carColor)
        hubCaption.setTextColor(
            if (s == HeroState.IDLE) getColor(R.color.m3_mono_dim) else getColor(R.color.m3_primary),
        )

        hubBlink?.cancel()
        hubBlink = null
        hubCar.alpha = 1f
        if (s == HeroState.CONNECTING) {
            hubBlink =
                ObjectAnimator.ofFloat(hubCar, View.ALPHA, 1f, 0.45f).apply {
                    duration = 1_200L
                    repeatCount = ObjectAnimator.INFINITE
                    repeatMode = ObjectAnimator.REVERSE
                    start()
                }
        }

        heroStateText.text =
            when (s) {
                HeroState.IDLE -> if (SessionController.isCarOffline) "车机已离线" else "未连接"
                HeroState.CONNECTING -> "正在连接"
                HeroState.CONNECTED -> "已连接"
            }
        heroProgress.visibility = if (s == HeroState.CONNECTING) View.VISIBLE else View.GONE
        heroStats.visibility = if (s == HeroState.CONNECTED) View.VISIBLE else View.GONE
        updateCarPlusReconnectButton(s == HeroState.CONNECTED && supportsSystemCarPlus)

        when (s) {
            HeroState.IDLE -> {
                // 门禁：本机蓝牙地址未填写 → 副标题直接说明卡在哪，按钮同时置灰
                val macMissing = effectiveBluetoothMac() == null
                heroSubline.text = idleConnectionHint(macMissing)
                styleCta(
                    R.drawable.bg_cta_fill,
                    R.color.m3_on_primary,
                    R.drawable.ic_play,
                    "启动连接",
                    blocked = macMissing,
                )
            }
            HeroState.CONNECTING -> {
                heroSubline.text = connectingMessage()
                styleCta(R.drawable.bg_btn_tonal, R.color.m3_on_sec_container, R.drawable.ic_close, "取消连接")
            }
            HeroState.CONNECTED -> {
                styleCta(R.drawable.bg_cta_outline_err, R.color.m3_error, R.drawable.ic_power, "断开连接")
            }
        }

        ctaIcon.rotation = 0f
    }

    /**
     * 英雄卡主操作按钮统一样式（三个状态仅风格/图标/文案不同）。
     *
     * @param blocked 置灰但不禁用——门禁未通过时仍可点击，点上会立刻说明原因
     *   （禁用态点下去毫无反馈，用户只会以为界面卡死）。
     */
    private fun styleCta(
        bgRes: Int,
        colorRes: Int,
        iconRes: Int,
        label: String,
        blocked: Boolean = false,
    ) {
        ctaButton.setBackgroundResource(bgRes)
        ctaLabel.setTextColor(getColor(colorRes))
        ctaIcon.imageTintList =
            android.content.res.ColorStateList
                .valueOf(getColor(colorRes))
        ctaIcon.setImageResource(iconRes)
        ctaLabel.text = label
        ctaButton.isEnabled = true
        ctaButton.alpha = if (blocked) CTA_BLOCKED_ALPHA else 1f
    }

    private fun updateConnectedSubline() {
        if (heroState != HeroState.CONNECTED) return
        val now = SystemClock.elapsedRealtime()
        val elapsed = now - connectedAt
        val mm = elapsed / 60_000
        val ss = elapsed % 60_000 / 1_000
        val ssid =
            ssidInput.text
                .toString()
                .trim()
                .ifEmpty { "车机热点" }
        val details = String.format(Locale.US, "%s · %02d:%02d", ssid, mm, ss)
        val sender = heroVideoSender ?: return
        val flowing = SessionController.isCarPlusPictureReady(sender)
        val title = if (flowing) "正在投屏" else "车机已连接"
        if (heroStateText.text != title) heroStateText.text = title
        heroSubline.text = if (flowing) details else "${SessionController.carPlusVideoMessage(sender)}\n$details"
        heroStats.visibility = if (flowing) View.VISIBLE else View.GONE
    }

    private fun updateCarPlusReconnectButton(visible: Boolean) {
        val visibility = if (visible) View.VISIBLE else View.GONE
        if (reconnectCarPlusButton.visibility != visibility) reconnectCarPlusButton.visibility = visibility
        val params = ctaButton.layoutParams as LinearLayout.LayoutParams
        val topMargin = ((if (visible) 8 else 18) * resources.displayMetrics.density).toInt()
        if (params.topMargin != topMargin) {
            params.topMargin = topMargin
            ctaButton.layoutParams = params
        }
    }

    private fun bindDisplayCard() {
        bindCarPlusStreamSize()
        if (BuildConfig.CAR_PLUS_RUNTIME) bindCarPlusFrameRate()
        if (BuildConfig.CAR_PLUS_WFD_PROFILE_CONTROL) {
            findViewById<View>(R.id.carPlusAudiBaselinePanel).visibility = View.VISIBLE
            findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switchCarPlusAudiBaseline).apply {
                isChecked = DisplayPrefs.carPlusAudiBaseline(this@MainActivity)
                setOnCheckedChangeListener { _, enabled ->
                    DisplayPrefs.setCarPlusAudiBaseline(this@MainActivity, enabled)
                }
            }
        }
    }

    private fun carPlusFrameRatePeer(): TargetDevicePrefs.Target? =
        TargetDevicePrefs.get(this) ?: SessionController.activeCarPlusPeer.takeIf { SessionController.isStreaming() }

    private fun bindCarPlusFrameRate() {
        findViewById<View>(R.id.carPlusFrameRatePanel).visibility = View.VISIBLE
        val options = findViewById<RadioGroup>(R.id.carPlusFrameRateOptions)
        for (index in 0 until options.childCount) {
            options.getChildAt(index).setOnClickListener { option ->
                val peer = carPlusFrameRatePeer() ?: return@setOnClickListener
                DisplayPrefs.setCarPlusHighFps(this, peer.address, option.id == R.id.carPlusFrameRate60)
                refreshCarPlusFrameRate()
                snack(getString(R.string.carplus_frame_rate_saved, peer.name))
            }
        }
        refreshCarPlusFrameRate()
    }

    private fun refreshCarPlusFrameRate() {
        if (!BuildConfig.CAR_PLUS_RUNTIME) return
        val peer = carPlusFrameRatePeer()
        val maxFps = DisplayPrefs.carPlusCarMaxFps(this, peer?.address)
        val canUse60 = CarPlusVideo.supportsHighFps(maxFps)
        val saved60 = DisplayPrefs.carPlusHighFps(this, peer?.address)
        val options = findViewById<RadioGroup>(R.id.carPlusFrameRateOptions)
        val checked = if (canUse60 && saved60) R.id.carPlusFrameRate60 else R.id.carPlusFrameRate30
        if (options.checkedRadioButtonId != checked) options.check(checked)
        findViewById<View>(R.id.carPlusFrameRate30).isEnabled = peer != null
        findViewById<View>(R.id.carPlusFrameRate60).apply {
            isEnabled = canUse60
            alpha = if (canUse60) 1f else 0.45f
        }
        val capability = when {
            peer == null -> getString(R.string.carplus_frame_rate_select_device)
            canUse60 -> getString(R.string.carplus_frame_rate_next_connection, peer.name)
            maxFps > 0 -> getString(R.string.carplus_frame_rate_limited, peer.name, maxFps)
            else -> getString(R.string.carplus_frame_rate_unknown, peer.name)
        }
        val current = if (heroVideoSender != null) {
            getString(R.string.carplus_frame_rate_current, SessionController.activeTargetFps, actualSentFps?.toString() ?: "—")
        } else null
        val note = findViewById<TextView>(R.id.carPlusFrameRateNote)
        val text = if (current == null) capability else "$capability\n$current"
        if (note.text != text) note.text = text
    }

    private fun bindCarPlusStreamSize() {
        findViewById<View>(R.id.displaySizeControls).visibility = View.VISIBLE
        findViewById<View>(R.id.displayHistoryPanel).visibility = View.VISIBLE
        val width = findViewById<EditText>(R.id.displayWidthInput)
        val height = findViewById<EditText>(R.id.displayHeightInput)
        val note = findViewById<TextView>(R.id.displaySizeNote)
        findViewById<View>(R.id.displaySizeTarget).visibility = View.VISIBLE
        refreshResolutionInputs(force = true)
        note.text = "留空并保存可恢复自动读取 · 下次连接生效"
        findViewById<View>(R.id.btnSaveDisplaySize).setOnClickListener {
            val peer = resolutionInputPeer ?: return@setOnClickListener
            val w = width.text.toString().trim()
            val h = height.text.toString().trim()
            val size = CarPlusVideo.manualSize(w.toIntOrNull() ?: 0, h.toIntOrNull() ?: 0)
            if (size == null && (w.isNotEmpty() || h.isNotEmpty())) {
                snack("请输入偶数像素：宽 320–4096，高 180–2304")
            } else {
                DisplayPrefs.setCarPlusStreamSize(this, peer.address, size)
                updateDisplayResolutionNote()
                snack(
                    if (size == null) "${peer.name} 已设为自动读取"
                    else "${peer.name} 已保存 ${size.width}×${size.height}",
                )
            }
        }
    }

    private fun refreshResolutionInputs(force: Boolean = false) {
        val peer = resolutionHistoryPeer ?: TargetDevicePrefs.get(this)
            ?: SessionController.activeCarPlusPeer.takeIf { SessionController.isStreaming() }
        if (!force && resolutionInputPeer == peer) return
        resolutionInputPeer = peer
        fillCarPlusStreamSize(DisplayPrefs.carPlusStreamSize(this, peer?.address))
        findViewById<EditText>(R.id.displayWidthInput).isEnabled = peer != null
        findViewById<EditText>(R.id.displayHeightInput).isEnabled = peer != null
        findViewById<View>(R.id.btnSaveDisplaySize).isEnabled = peer != null
        findViewById<TextView>(R.id.displaySizeTarget).text =
            peer?.let { "设置对象 · ${it.name}" } ?: "选择蓝牙设备后可手动设置"
    }

    private fun fillCarPlusStreamSize(size: CarPlusVideo.Size?) {
        findViewById<EditText>(R.id.displayWidthInput).setText(size?.width?.toString().orEmpty())
        findViewById<EditText>(R.id.displayHeightInput).setText(size?.height?.toString().orEmpty())
    }

    private fun renderDisplayHistory() {
        val entries = CarPlusResolutionHistory.entries(this)
        val list = findViewById<LinearLayout>(R.id.displayHistoryList)
        val empty = findViewById<TextView>(R.id.displayHistoryEmpty)
        list.visibility = if (entries.isEmpty()) View.GONE else View.VISIBLE
        empty.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        list.removeAllViews()
        if (entries.isEmpty()) {
            val last = DisplayPrefs.lastReportedCanvas(this)
            empty.text = if (last == null) getString(R.string.display_history_empty)
            else getString(R.string.display_history_legacy, last.width, last.height)
        }
        entries.forEachIndexed { index, entry ->
            val row = layoutInflater.inflate(R.layout.item_display_history, list, false)
            row.findViewById<View>(R.id.displayHistoryDivider).visibility = if (index == 0) View.GONE else View.VISIBLE
            row.findViewById<TextView>(R.id.displayHistoryDevice).text =
                getString(R.string.display_history_device, entry.name)
            row.findViewById<TextView>(R.id.displayHistoryResolution).text =
                getString(R.string.display_history_resolution, entry.width, entry.height)
            val size = entry.inputSize
            row.findViewById<View>(R.id.displayHistoryFill).apply {
                contentDescription = getString(R.string.display_history_fill_device, entry.name)
                isEnabled = size != null
                alpha = if (size == null) 0.45f else 1f
                if (size != null) setOnClickListener {
                    DisplayPrefs.setCarPlusStreamSize(this@MainActivity, entry.address, size)
                    resolutionHistoryPeer = TargetDevicePrefs.Target(entry.address, entry.name)
                    refreshResolutionInputs(force = true)
                    updateDisplayResolutionNote()
                    snack("${entry.name} 已保存 ${size.width}×${size.height}")
                }
            }
            list.addView(row)
        }
    }

    /**
     * 连接准备计数：已填写热点 SSID、系统已有蓝牙配对设备。
     */
    private fun updateReadyRow() {
        val target = TargetDevicePrefs.get(this)
        val btGranted =
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        val bonded = try {
            if (btGranted && adapter?.isEnabled == true) adapter.bondedDevices else null
        } catch (_: SecurityException) {
            null
        }
        val paired = bonded?.any { target == null || TargetDevicePrefs.matches(target, it.address) } == true
        val bluetoothStatus = when {
            !btGranted -> "蓝牙未授权"
            adapter == null -> "蓝牙不可用"
            bonded == null -> "蓝牙未开启"
            paired -> "蓝牙已配对"
            else -> "蓝牙未配对"
        }
        val wifiStatus = when {
            carHotspotConnected -> "车机热点已连接"
            ssidInput.text.isNotBlank() -> "车机热点未连接"
            else -> "等待识别车机热点"
        }
        if (readyBluetooth.text != bluetoothStatus) readyBluetooth.text = bluetoothStatus
        if (readyWifi.text != wifiStatus) readyWifi.text = wifiStatus
        readyBluetooth.setTextColor(getColor(if (paired) R.color.m3_ok else R.color.m3_on_var))
        readyWifi.setTextColor(getColor(if (carHotspotConnected) R.color.m3_ok else R.color.m3_on_var))
        btnPickTarget.visibility = if (bonded != null) View.VISIBLE else View.GONE
        btBadge.visibility = btnPickTarget.visibility
        if (bonded != null) {
            btBadge.background.mutate().setTint(getColor(if (paired) R.color.m3_ok_dot else R.color.m3_amber))
            btBadge.text = when {
                paired -> "已配对"
                target != null -> "需重选"
                else -> "未配对"
            }
        }
        val description = when {
            bonded == null -> bluetoothStatus
            target != null && paired -> "目标「${target.name}」· 只向该设备发请求"
            target != null -> "目标「${target.name}」已不在配对列表，请重新选择设备"
            bonded.isEmpty() -> "尚未配对，点「选择设备」扫描并配对车机"
            bonded.size > 1 -> "已配对 ${bonded.size} 台 · 未指定目标将逐个尝试"
            else -> "已与 1 台设备配对 · 连接自动回连"
        }
        if (btDesc.text != description) btDesc.text = description
    }

    // ---- 目标车机选择弹窗（扫描/配对状态）----

    /** 当前打开的弹窗（扫描回调要往里加行；关闭时统一收尾）。 */
    private var targetPicker: androidx.appcompat.app.AlertDialog? = null

    private var targetPickerReceiver: BroadcastReceiver? = null

    /** 弹窗内已展示的扫描结果（按地址去重；已配对的单独进上组，不进这里）。 */
    private val scannedTargets = LinkedHashMap<String, TargetPickerEntry>()

    /** 扫描期间点过「配对」的设备地址（配对完成后自动选中为目标）。 */
    private var pendingBondAddress: String? = null

    private data class TargetPickerEntry(
        val address: String,
        val name: String,
        val rssi: Int,
        val bonded: Boolean,
    )

    /** 扫描权限被拒后置位：授权回调里继续（弹窗可能已关，则忽略）。 */
    private var pendingTargetScan = false

    private val targetScanPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            log("BT-SCAN: BLUETOOTH_SCAN 授权: $granted")
            if (granted && pendingTargetScan) {
                pendingTargetScan = false
                targetPicker?.let { startTargetScan(it) }
            } else if (!granted) {
                pendingTargetScan = false
                targetPicker?.let { _ ->
                    updatePickerStatus("未授予「附近的设备」权限——无法扫描；可到系统设置授权后重开本弹窗", warn = true)
                    pickerToast("未授予「附近的设备」权限——无法扫描附近设备")
                }
            }
        }

    /**
     * 目标车机选择弹窗（2026-10-18 改版）：
     *  组 1「已配对」——直接选用（车机无线 CarPlay 的前置是系统级蓝牙配对）；
     *  组 2「附近的设备」——实时扫描（经典蓝牙 discovery），点击未配对设备即发起
     *  系统配对，配对成功后自动设为目标。
     *
     * 首行「自动（不指定）」保留原有语义：出站逐个尝试、入站来者不拒；
     * 选定后出站只连目标、入站拒绝非目标（防误连家里另一台车机）。
     * 选择立即生效（链路动态读取目标，无需重连）。
     */
    private fun showTargetPicker() {
        val adapter =
            try {
                (getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager)
                    .adapter
            } catch (_: Exception) {
                null
            }
        if (adapter == null || !adapter.isEnabled) {
            snack("蓝牙未开启——请先开启蓝牙")
            return
        }
        val view = layoutInflater.inflate(R.layout.dialog_target_picker, null)
        val dialog =
            androidx.appcompat.app.AlertDialog
                .Builder(this)
                .setView(view)
                .create()
        targetPicker = dialog
        scannedTargets.clear()
        pendingBondAddress = null
        view.findViewById<TextView>(R.id.btnCancelPicker).setOnClickListener { dialog.dismiss() }
        view.findViewById<TextView>(R.id.btnRescan).setOnClickListener { startTargetScan(dialog) }
        renderBondedGroup(view)
        dialog.setOnDismissListener {
            stopTargetScan()
            targetPicker = null
            pendingBondAddress = null
            updateReadyRow()
        }
        dialog.setOnShowListener {
            // 面板自带圆角：窗口底色必须透明，否则四角露出方角
            dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
            val width = (resources.displayMetrics.widthPixels * PanelWindow.WIDTH_FRACTION).toInt()
            dialog.window?.setLayout(width, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        dialog.show()
        startTargetScan(dialog)
    }

    /** 渲染「已配对」组：自动行 + 每个已配对设备。 */
    private fun renderBondedGroup(view: View) {
        val list = view.findViewById<LinearLayout>(R.id.bondedList)
        val empty = view.findViewById<TextView>(R.id.bondedEmpty)
        list.removeAllViews()
        val current = TargetDevicePrefs.get(this)
        // 「自动（不指定）」是模式而非设备：单独放在两组之上，不混进「已配对」组
        view.findViewById<LinearLayout>(R.id.autoRowContainer).apply {
            removeAllViews()
            addView(
                inflateTargetRow(
                    name = "自动（不指定目标）",
                    address = "逐个尝试 · 接受任一设备连入",
                    action = if (current == null) "当前" else "选择",
                    highlighted = current == null,
                    iconTint = R.color.m3_outline,
                ) {
                    TargetDevicePrefs.set(this@MainActivity, null)
                    resolutionHistoryPeer = null
                    refreshResolutionInputs(force = true)
                    refreshCarHotspot()
                    log("TARGET: 已取消目标指定——出站逐个尝试已配对设备，入站接受任一设备")
                    // 先关弹窗再提示（snackbar 在 Activity 窗口内，会被弹窗盖住）
                    targetPicker?.dismiss()
                    snack("已切换为自动（不指定目标）")
                },
            )
        }
        val bonded = bondedDevices()
        empty.visibility = if (bonded.isEmpty()) View.VISIBLE else View.GONE
        bonded.forEach { device ->
            val address = device.address
            val name = deviceDisplayName(device)
            val isCurrent = current != null && TargetDevicePrefs.matches(current, address)
            list.addView(
                inflateTargetRow(
                    name = name,
                    address = address,
                    action = if (isCurrent) "当前" else "选择",
                    highlighted = isCurrent,
                    iconTint = R.color.m3_primary,
                ) {
                    setTarget(device, name, closePicker = true)
                },
            )
        }
    }

    /** 单个设备行（两组共用；highlighted 时名称用主色）。 */
    private fun inflateTargetRow(
        name: String,
        address: String,
        action: String,
        highlighted: Boolean,
        iconTint: Int,
        onClick: () -> Unit,
    ): View {
        val row = layoutInflater.inflate(R.layout.item_target_device, null)
        row.background =
            ContextCompat.getDrawable(
                this@MainActivity,
                if (highlighted) R.drawable.bg_bt_device_row_current else R.drawable.bg_bt_device_row,
            )
        row.findViewById<TextView>(R.id.devName).apply {
            text = name
            setTextColor(getColor(if (highlighted) R.color.m3_primary else R.color.m3_on_surface))
        }
        row.findViewById<TextView>(R.id.devAddr).text = address
        row.findViewById<TextView>(R.id.devAction).apply {
            // 「当前」= 实心绿胶囊 + 对勾 + 深色字；「选择/配对」= 描边胶囊 + 青色字。
            // 只靠色相区分不够（青 #7EDCE6 vs 绿 #7BD8A5 小字号下难辨），改用形态差异。
            if (highlighted) {
                text = "✓ $action"
                setTextColor(getColor(R.color.m3_surface))
                background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_pill_current)
            } else {
                text = action
                setTextColor(getColor(R.color.m3_primary))
                background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_tbtn)
            }
            // 实心/描边切换后 padding 由 drawable 决定，不需要重设
            setPadding(paddingLeft, paddingTop, paddingRight, paddingBottom)
        }
        row.findViewById<ImageView>(R.id.devIcon).setColorFilter(getColor(iconTint))
        row.setOnClickListener { onClick() }
        return row
    }

    /**
     * 开始扫描附近设备：注册 ACTION_FOUND / ACTION_BOND_STATE_CHANGED 后 startDiscovery。
     * 发现结果按地址去重、排除已配对设备（它们在上一组）；点「配对」调 createBond()。
     */
    private fun startTargetScan(dialog: androidx.appcompat.app.AlertDialog) {
        val adapter =
            try {
                (getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager)
                    .adapter
            } catch (_: Exception) {
                null
            } ?: return
        val granted =
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) ==
                PackageManager.PERMISSION_GRANTED
        if (!granted) {
            pendingTargetScan = true
            updatePickerStatus("需要「附近的设备」权限才能扫描…", warn = true)
            targetScanPermissionLauncher.launch(Manifest.permission.BLUETOOTH_SCAN)
            return
        }
        val scannedList = dialog.findViewById<LinearLayout>(R.id.scannedList) ?: return
        // 已配对的地址集合：扫描结果里剔掉（避免同一设备在两处出现）
        val bondedAddresses =
            bondedDevices()
                .map { it.address }
                .toSet()
        scannedTargets.clear()
        scannedList.removeAllViews()
        updatePickerStatus("正在扫描附近的蓝牙设备…")
        stopTargetScan()
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    context: Context,
                    intent: Intent,
                ) {
                    when (intent.action) {
                        BluetoothDevice.ACTION_FOUND -> {
                            @Suppress("DEPRECATION")
                            val device =
                                intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                                    ?: return
                            val rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, 0).toInt()
                            onTargetFound(dialog, device, rssi, bondedAddresses)
                        }

                        BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                            val device =
                                intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                                    ?: return
                            val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
                            onBondStateChanged(dialog, device, state)
                        }
                    }
                }
            }
        targetPickerReceiver = receiver
        val filter =
            IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_FOUND)
                addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            }
        // 系统（蓝牙栈）发出的广播：必须用 EXPORTED 才能在部分 ROM 上收到
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        val started =
            try {
                adapter.startDiscovery()
            } catch (_: SecurityException) {
                false
            }
        if (!started) {
            updatePickerStatus("扫描启动失败——请确认蓝牙已开启后点「重新扫描」", warn = true)
        } else {
            log("BT-SCAN: 开始扫描附近蓝牙设备")
        }
    }

    /** 扫描到一台设备：已配对→忽略；新设备→加一行（点「配对」发起系统配对）。 */
    private fun onTargetFound(
        dialog: androidx.appcompat.app.AlertDialog,
        device: BluetoothDevice,
        rssi: Int,
        bondedAddresses: Set<String>,
    ) {
        val address =
            try {
                device.address
            } catch (_: SecurityException) {
                return
            }
        if (address in bondedAddresses || scannedTargets.containsKey(address)) return
        val name = deviceDisplayName(device)
        scannedTargets[address] = TargetPickerEntry(address, name, rssi, bonded = false)
        val list = dialog.findViewById<LinearLayout>(R.id.scannedList) ?: return
        // 扫描仍在继续：状态行保留并计数，用户知道还可以继续等其它设备。
        // 刻意不走 updatePickerStatus——它会把列表滚到状态行，发现新设备时抢滚动位置。
        dialog.findViewById<TextView>(R.id.scanStatus)?.apply {
            text = "继续扫描中…已发现 ${scannedTargets.size} 台未配对设备"
            setTextColor(getColor(R.color.m3_mono_dim))
            visibility = View.VISIBLE
        }
        list.addView(
            inflateTargetRow(
                name = name,
                address = "$address · ${rssi}dBm",
                action = "配对",
                highlighted = false,
                iconTint = R.color.m3_amber,
            ) {
                beginBond(device)
            },
        )
    }

    /** 点击未配对设备：先停扫描（扫描期间配对易失败），再 createBond()。 */
    private fun beginBond(device: BluetoothDevice) {
        val address =
            try {
                device.address
            } catch (_: SecurityException) {
                return
            }
        stopDiscoveryOnly()
        pendingBondAddress = address
        val name = deviceDisplayName(device)
        val ok =
            try {
                device.createBond()
            } catch (error: SecurityException) {
                log("BT-PAIR: 配对「$name」失败 - 缺少权限：${error.message}")
                false
            }
        if (ok) {
            log("BT-PAIR: 已发起与「$name」($address) 的配对——请在系统弹窗确认")
            pickerNotice("正在配对「$name」：请在系统弹窗确认")
        } else {
            pendingBondAddress = null
            log("BT-PAIR: 配对「$name」($address) 发起失败（设备可能已配对或蓝牙忙）")
            pickerNotice("无法发起配对——请在系统蓝牙设置中手动配对")
        }
    }

    /**
     * 弹窗打开期间的提示。
     *
     * 弹窗是**独立窗口**（更高层级 + 全屏遮罩），Activity 里的 snackbar 必然被它盖住
     * ——用户只能看到底部一条被压暗的色块（2026-10-18 反馈）。系统 Toast 属更高窗口层，
     * 能浮在弹窗之上；同时更新弹窗内的状态行（滚动到可见位置）作为长期可见的说明。
     */
    private fun pickerNotice(message: String) {
        pickerToast(message)
        updatePickerStatus(message, warn = true)
    }

    /**
     * 弹窗打开期间的系统 Toast。系统 Toast 属更高窗口层，能浮在弹窗之上；
     * Activity 内的 snackbar 会被弹窗（独立窗口 + 遮罩）完全盖住。
     */
    private fun pickerToast(message: String) {
        android.widget.Toast
            .makeText(this, message, android.widget.Toast.LENGTH_SHORT)
            .show()
    }

    /** 更新弹窗内的状态行并滚动到可见位置（`scanStatus` 在「附近的设备」标题下）。 */
    private fun updatePickerStatus(
        message: String,
        warn: Boolean = false,
    ) {
        val dialog = targetPicker ?: return
        val status = dialog.findViewById<TextView>(R.id.scanStatus) ?: return
        status.text = message
        status.setTextColor(getColor(if (warn) R.color.m3_amber else R.color.m3_mono_dim))
        status.visibility = View.VISIBLE
        // 状态行在视口外才滚动（用户在浏览上面的已配对列表时不该被抢走位置）。
        status.post {
            val scroll = dialog.findViewById<android.widget.ScrollView>(R.id.pickerScroll) ?: return@post
            val content = scroll.getChildAt(0) ?: return@post
            val top = offsetWithin(status, content)
            val bottom = top + status.height
            val visibleTop = scroll.scrollY
            val visibleBottom = visibleTop + scroll.height
            if (top < visibleTop || bottom > visibleBottom) {
                val y = top - (12 * resources.displayMetrics.density).toInt()
                scroll.smoothScrollTo(0, y.coerceAtLeast(0))
            }
        }
    }

    /** view 相对 ancestor 的内容偏移（沿父链累加 top；ancestor 必须是其祖先）。 */
    private fun offsetWithin(
        view: View,
        ancestor: View,
    ): Int {
        var current: View? = view
        var y = 0
        while (current != null && current !== ancestor) {
            y += current.top
            current = current.parent as? View
        }
        return y
    }

    /** 配对状态变化：成功后设为目标并关弹窗；失败给出提示。 */
    private fun onBondStateChanged(
        dialog: androidx.appcompat.app.AlertDialog,
        device: BluetoothDevice,
        state: Int,
    ) {
        val address =
            try {
                device.address
            } catch (_: SecurityException) {
                return
            }
        if (address != pendingBondAddress) return
        when (state) {
            BluetoothDevice.BOND_BONDED -> {
                pendingBondAddress = null
                val name = deviceDisplayName(device)
                log("BT-PAIR: ✓ 已与「$name」($address) 完成配对——自动设为目标")
                // 先关弹窗再提示：snackbar 在 Activity 窗口内，弹窗未关时会被盖住
                dialog.dismiss()
                setTarget(device, name)
                // 「只配对、不连接」：系统/车机在配对完成后常会自动建立 HFP/A2DP
                // 连接，而奥迪实测这种「已连接」状态会让车机拒绝 CarPlay。立即与
                // 1.5s 后各断一次（车机侧重连通常在这两个时间点之间发生）。
                val app = applicationContext
                Bg.run("bt-pair-only") {
                    repeat(2) { attempt ->
                        val dropped = BtProfiles.disconnect(app, device)
                        if (dropped.isNotEmpty()) {
                            log(
                                "BT-PAIR: 已断开配对后自动建立的 ${dropped.joinToString("/")} 连接" +
                                    "（保持仅配对，避免车机拒绝 CarPlay）",
                            )
                        }
                        if (attempt == 0) {
                            try {
                                Thread.sleep(1_500)
                            } catch (_: InterruptedException) {
                                return@run
                            }
                        }
                    }
                }
            }

            BluetoothDevice.BOND_NONE -> {
                pendingBondAddress = null
                log("BT-PAIR: 与 $address 配对未完成（用户取消或失败）")
                // 弹窗仍开着：用 Toast（浮在弹窗之上）+ 弹窗内状态行，不用被遮挡的 snackbar
                pickerNotice("配对未完成——可再点「配对」重试，或到系统蓝牙设置手动配对")
            }
            // BOND_BONDING：继续等（系统确认框未应答）
        }
    }

    /**
     * 设为出站目标；正在进行的连接在下一轮尝试/下一次连入时切换。
     *
     * @param closePicker true = 先关闭设备选择弹窗再提示（snackbar 属 Activity 窗口，
     *   弹窗开着时必被盖住——见 pickerNotice 注释）。
     */
    private fun setTarget(
        device: BluetoothDevice,
        name: String,
        closePicker: Boolean = false,
    ) {
        if (closePicker) targetPicker?.dismiss()
        val previous = TargetDevicePrefs.get(this)
        TargetDevicePrefs.set(this, TargetDevicePrefs.Target(device.address, name))
        refreshCarHotspot()
        log(
            "TARGET: 目标车机已设为「$name」(${device.address})" +
                "——出站只连该设备、入站拒绝其它设备" +
                if (previous != null && previous.address != device.address) {
                    "（原目标「${previous.name}」）"
                } else {
                    ""
                },
        )
        resolutionHistoryPeer = null
        refreshResolutionInputs(force = true)
        updateDisplayResolutionNote()
        snack("目标已设为「$name」")
        updateReadyRow()
    }

    /** 停掉扫描并注销监听（弹窗关闭/重扫时调用）。 */
    private fun stopTargetScan() {
        stopDiscoveryOnly()
        targetPickerReceiver?.let {
            runCatching { unregisterReceiver(it) }
            targetPickerReceiver = null
        }
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun stopDiscoveryOnly() {
        // 调用点均已先确认 BLUETOOTH_SCAN 已授予（扫描流程入口），且此处整体捕获异常
        try {
            (getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager)
                .adapter
                ?.cancelDiscovery()
        } catch (_: Exception) {
        }
    }

    private fun bondedDevices(): List<BluetoothDevice> =
        try {
            (getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager)
                .adapter
                ?.bondedDevices
                ?.toList()
                .orEmpty()
        } catch (_: SecurityException) {
            emptyList()
        }

    /** 设备展示名（无名称时退回地址）；读名需要 BLUETOOTH_CONNECT，异常时用地址。 */
    private fun deviceDisplayName(device: BluetoothDevice): String =
        try {
            device.name?.takeIf { it.isNotBlank() } ?: device.address
        } catch (_: SecurityException) {
            try {
                device.address
            } catch (_: SecurityException) {
                "未知设备"
            }
        }

    /** 设备营销名（ro.product.marketname），取不到时退回 Build.MODEL。 */
    private fun deviceName(): String = DeviceIdentity.marketingName() ?: Build.MODEL

    /** 副标题只显示设备名（原来的 Shizuku 状态已随依赖移除）。 */
    private fun updateDeviceSubtitle() {
        val text = deviceName()
        if (deviceSubtitle.text != text) deviceSubtitle.text = text
    }

    // ==================== 通用 UI 工具 ====================

    private fun log(message: String) {
        // 写入进程级缓冲（logcat + 文件 + 挂屏渲染均由 SessionController 统一处理）
        SessionController.appendLog(message)
    }

    private fun copyIssueSummary() {
        val target = SessionController.activeCarPlusPeer ?: TargetDevicePrefs.get(this)
        val sender = SessionController.videoSender?.takeIf {
            it.isRunning && SessionController.currentClient?.isRetired != true
        }
        val stage = when {
            SessionController.disconnecting -> "正在断开连接"
            sender != null -> if (SessionController.isCarPlusPictureReady(sender)) {
                "正在投屏"
            } else {
                "车机已连接 · ${SessionController.carPlusVideoMessage(sender)}"
            }
            SessionController.isSessionRunning() || SessionController.sessionStarting.get() -> connectingMessage()
            SessionController.isCarOffline -> "车机已离线"
            else -> "未连接"
        }
        val summary = ConnectionIssueSummary.format(
            appName = getString(R.string.app_name),
            version = AppVersion.nameWithTag(this),
            target = target?.name ?: "自动选择",
            currentStage = stage,
            logs = SessionController.logText(),
            incident = ConnectionIncidentTimeline.latest(this),
        )
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("连接故障摘要", summary))
        snack("已复制故障摘要")
    }

    /** 导出日志到下载目录并显示文件位置。 */
    private fun exportLogFile(scope: LogExporter.Scope = LogExporter.Scope.CURRENT_SESSION) {
        snack(if (scope == LogExporter.Scope.ALL) "正在导出全部日志…" else "正在导出本次启动的日志…")
        val fallback = SessionController.logText()
        Bg.run("export-log") {
            val result = LogExporter.export(applicationContext, fallback, scope)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (result == null) {
                    snack("导出失败——请检查存储空间后重试", 4_000L)
                    return@runOnUiThread
                }
                log("日志已导出（${scope.label}）: ${result.displayPath}（${result.bytes} 字节）")
                val scopeNote =
                    if (scope == LogExporter.Scope.CURRENT_SESSION) {
                        "仅含本次 App 启动之后的日志。需要更早的历史，请长按导出按钮。"
                    } else {
                        "包含历史日志（文件上限 2MB，超出时只保留最近一段）。"
                    }
                DarkMessageDialog.show(
                    this,
                    title = "日志已导出",
                    message =
                        "文件位置：\n${result.displayPath}\n\n" +
                            "大小：${formatBytes(result.bytes)}\n$scopeNote\n\n" +
                            "可在「文件管理 → 下载 → CarPToCar+ICCOA」中找到，也可直接分享给开发者排查问题。",
                    actions =
                        listOf(
                            DarkMessageDialog.Action("打开文件", DarkMessageDialog.Emphasis.PRIMARY) {
                                openExportedFile(result.uri, result.displayPath)
                            },
                            DarkMessageDialog.Action("打开文件夹") {
                                openExportFolder(result.displayPath)
                            },
                            DarkMessageDialog.Action("分享文件") {
                                shareExportedFile(result.uri)
                            },
                            DarkMessageDialog.Action("关闭", onClick = {}),
                        ),
                )
            }
        }
    }

    /** 人类可读大小：<1KB 显示字节（整数除法会显示成误导性的「0 KB」）。 */
    private fun formatBytes(bytes: Long): String =
        when {
            bytes < 1024 -> "$bytes 字节"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            else -> String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
        }

    /**
     * 打开系统文件管理器并定位到导出目录（DocumentsUI 的 document URI 可直达子目录）。
     *
     * 为什么优先**显式组件**：荣耀 ROM 的隐式 ACTION_VIEW + directory 会被 resolver
     * 乱匹配（2026-10-18 实测弹出了「网易云音乐」默认项）；同一条 intent 用
     * `am start` 却正确落到 DocumentsUI。显式指定 FilesActivity 可跳过 resolver，
     * 失败再逐级回退（别的 ROM 组件名可能不同 → 隐式 → 外部存储根 → 文字指引）。
     */
    private fun openExportFolder(displayPath: String) {
        val folder = displayPath.substringBeforeLast('/')
        val relative = folder.removePrefix("/storage/emulated/0/")
        val dirUri =
            Uri.parse(
                "content://com.android.externalstorage.documents/document/" +
                    "primary%3A" + Uri.encode(relative).replace("/", "%2F"),
            )
        val explicit =
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(dirUri, "vnd.android.document/directory")
                component =
                    android.content.ComponentName(
                        "com.android.documentsui",
                        "com.android.documentsui.files.FilesActivity",
                    )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        val implicit =
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(dirUri, "vnd.android.document/directory")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        val storageRoot =
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(
                    Uri.parse("content://com.android.externalstorage.documents/root/primary"),
                    "vnd.android.document/directory",
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        for (intent in listOf(explicit, implicit, storageRoot)) {
            if (runCatching { startActivity(intent) }.isSuccess) {
                if (intent !== explicit) snack("请按路径打开：$folder", 5_000L)
                return
            }
        }
        snack("请到「文件管理 → 下载 → CarPToCar+ICCOA」查看", 5_000L)
    }

    /** 用系统默认应用打开（多数 ROM 会落到文件管理器 / 文本查看器预览）。 */
    private fun openExportedFile(
        uri: Uri,
        displayPath: String,
    ) {
        val view =
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "text/plain")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        if (runCatching { startActivity(view) }.isSuccess) return
        // 无文本查看器时退到「文件管理器」：DocumentsUI 的内部存储根（用户按路径进 下载/CarPToCarL）
        val storageRoot =
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(
                    Uri.parse("content://com.android.externalstorage.documents/root/primary"),
                    "vnd.android.document/root",
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        if (runCatching { startActivity(storageRoot) }.isSuccess) {
            snack("请按路径打开：$displayPath", 5_000L)
            return
        }
        snack("没有可打开文件的应用——请到文件管理器中按上述路径查找", 5_000L)
    }

    /** 分享日志文件（微信/QQ/邮件等），内容 URI 带临时读权限。 */
    private fun shareExportedFile(uri: Uri) {
        val send =
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        runCatching { startActivity(Intent.createChooser(send, "分享日志文件")) }
            .onFailure { snack("没有可分享的应用") }
    }

    private fun updateLogBottomButton() {
        findViewById<ImageView>(R.id.btnLogBottom)?.visibility =
            if (consoleAtBottom()) View.INVISIBLE else View.VISIBLE
    }

    /** 日志内容最大滚动偏移。 */
    private fun consoleMaxScroll(): Int {
        val layout = logText.layout ?: return 0
        return (
            layout.getLineBottom(layout.lineCount - 1) +
                logText.paddingBottom - logText.height
        ).coerceAtLeast(0)
    }

    /** 是否已在底部（40px 容差：吸收行高舍入，避免永远差几像素判不到底）。 */
    private fun consoleAtBottom(): Boolean = consoleMaxScroll() - logText.scrollY <= 40

    private fun scrollConsoleToBottom() {
        // +16px 过冲补齐舍入差（控制台深色底，过冲不可见；View 不钳制但视觉无感）
        logText.scrollTo(0, consoleMaxScroll() + 16)
        logText.post { updateLogBottomButton() }
    }

    private fun bindLogFilters() {
        val all = findViewById<TextView>(R.id.logFilterAll)
        val issues = findViewById<TextView>(R.id.logFilterIssues)
        fun updateSelection() {
            for (button in listOf(all, issues)) {
                val selected = (button === issues) == logIssuesOnly
                button.isSelected = selected
                button.setBackgroundResource(
                    if (selected) R.drawable.bg_btn_compact else R.drawable.bg_btn_compact_outline,
                )
                button.setTextColor(getColor(if (selected) R.color.m3_on_sec_container else R.color.m3_on_var))
            }
        }
        all.setOnClickListener {
            logIssuesOnly = false
            updateSelection()
            renderConsole(SessionController.logText(), follow = true)
        }
        issues.setOnClickListener {
            logIssuesOnly = true
            updateSelection()
            renderConsole(SessionController.logText(), follow = true)
        }
        updateSelection()
    }

    private fun formatConsole(text: String): CharSequence =
        SpannableStringBuilder().apply {
            for (line in text.lineSequence()) {
                if (line.isEmpty()) continue
                val level = LogDisplayLevel.of(line)
                if (logIssuesOnly && !level.isIssue) continue
                val start = length
                append(line)
                val color = when (level) {
                    LogDisplayLevel.NORMAL -> null
                    LogDisplayLevel.PROTOCOL -> R.color.m3_primary
                    LogDisplayLevel.WARNING -> R.color.m3_amber
                    LogDisplayLevel.ERROR -> R.color.m3_error
                }
                if (color != null) {
                    setSpan(ForegroundColorSpan(getColor(color)), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                append('\n')
            }
        }

    private fun renderConsole(text: String, follow: Boolean = consoleAtBottom()) {
        logText.hint = if (logIssuesOnly) "暂无异常日志" else "暂无日志"
        logText.text =
            if (SessionController.logEnabled) formatConsole(text) else "日志采集已关闭（打开「监听日志」开关恢复）"
        logText.post {
            if (follow) scrollConsoleToBottom() else updateLogBottomButton()
        }
    }

    /**
     * 增量追加一行日志（SessionController 逐行直送；此前每行全量 toString + setText
     * 整段文本，高频日志下 UI/GC 双重放大）。超限时从进程缓冲全量对账一次完成裁剪。
     */
    private fun appendConsole(line: String) {
        if (!SessionController.logEnabled) return
        val formatted = formatConsole(line)
        if (formatted.isEmpty()) return
        val follow = consoleAtBottom()
        if (logText.text.length > 44_000) {
            renderConsole(SessionController.logText())
            return
        }
        logText.append(formatted)
        logText.post {
            if (follow) scrollConsoleToBottom() else updateLogBottomButton()
        }
    }

    private var snackRunnable: Runnable? = null

    /** 自绘 M3 Snackbar（底部反色胶囊，替代 Toast）。 */
    private fun snack(
        message: String,
        durationMs: Long = 2_400L,
    ) {
        snackbar.text = message
        snackbar.visibility = View.VISIBLE
        snackbar.translationY = snackbar.height + 60f
        snackbar
            .animate()
            .translationY(0f)
            .setDuration(260L)
            .start()
        snackRunnable?.let { mainHandler.removeCallbacks(it) }
        snackRunnable =
            Runnable {
                snackbar
                    .animate()
                    .translationY(snackbar.height + 60f)
                    .setDuration(260L)
                    .withEndAction { snackbar.visibility = View.GONE }
                    .start()
            }.also { mainHandler.postDelayed(it, durationMs) }
    }

    /**
     * 复用界面时，离车后通过外部入口打开 App，可发起新一轮连接。
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (isFinishing || isDestroyed || !rearmAfterDeparture(intent)) return
        autoConnectRequested = true
        // 等界面恢复到前台再检查连接条件。
        mainHandler.postDelayed(autoConnectCheck, 150L)
    }

    override fun onResume() {
        super.onResume()
        CarPlusResolutionHistory.preferences(this).registerOnSharedPreferenceChangeListener(displayHistoryListener)
        renderDisplayHistory()
        // 离车时保留用户正在看的界面。
        AppUiPresence.markResumed()
        lastFpsSampleAt = 0L
        actualSentFps = null
        mainHandler.post(heroTick)
        // 从系统设置/别的界面回来：门禁提示与按钮状态重新对一次（地址可能已补填）
        refreshMacGate()
        updateKeepAliveStatus()
        // 旋转/重建后尚未接受：用原截止时刻续弹（不重新等 10 秒）
        if (needsDisclaimerAtLaunch(
                DisclaimerPrefs.acceptedVersion(this),
                DisclaimerPrefs.CURRENT_VERSION,
            ) &&
            activeDisclaimer?.isShowing != true
        ) {
            showDisclaimer(requireAcceptance = true)
        }
        startCarPlusOnLaunch()
        // 在本轮界面状态刷新后处理冷启动和待续接的连接请求。
        mainHandler.removeCallbacks(autoConnectCheck)
        mainHandler.post(autoConnectCheck)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong(KEY_DISCLAIMER_DEADLINE, disclaimerDeadlineAt)
        outState.putBoolean(KEY_LOG_ISSUES_ONLY, logIssuesOnly)
        resolutionHistoryPeer?.let {
            outState.putString(KEY_RESOLUTION_PEER_ADDRESS, it.address)
            outState.putString(KEY_RESOLUTION_PEER_NAME, it.name)
        }
    }

    /**
     * 旋转（已声明 configChanges，Activity 不重建）：视图与所有内存状态原样保留。
     * 需要处理两件事：
     * 1. **内容区切成双栏**——横屏屏高只有 ~300dp，英雄卡单独就占满整屏，
     *    其余内容全被挤到屏幕外（用户实测「要滑动才能看到」）；
     * 2. **重算弹窗窗口**——它们按旧方向的屏宽算过（92%），不重算会只占中间一半。
     */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (isFinishing || isDestroyed) return
        applyContentLayout(landscape = newConfig.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE)
        PanelWindow.resizeShowing(this)
        // 目标车机弹窗不走 DarkMessageDialog，单独重算
        targetPicker?.window?.let {
            val width = (resources.displayMetrics.widthPixels * PanelWindow.WIDTH_FRACTION).toInt()
            it.setLayout(width, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    /**
     * 内容区排布：竖屏单列（英雄卡与详情同在一个 ScrollView）⇄
     * 横屏双栏（英雄卡**固定在左侧**，只有右侧详情列滚动）。
     *
     * 横屏为什么要把英雄卡移出 ScrollView：它和详情同属一个滚动容器时，
     * 滑右栏会把英雄卡一起带走（用户实测反馈）。移入 [contentArea] 后它是固定层，
     * 滚动只作用于 mainScroll；右侧内容通过 ScrollView 的左内边距让位。
     *
     * onCreate 也要调用一次——旋转不重建，但**以横屏启动**不会触发
     * onConfigurationChanged。
     */
    private fun applyContentLayout(landscape: Boolean) {
        val contentArea = findViewById<android.widget.FrameLayout>(R.id.contentArea)
        val scroll = findViewById<android.widget.ScrollView>(R.id.mainScroll)
        val row = findViewById<android.widget.LinearLayout>(R.id.contentRow)
        val hero = findViewById<android.widget.LinearLayout>(R.id.heroCard)
        val detail = findViewById<android.widget.LinearLayout>(R.id.detailColumn)
        val density = resources.displayMetrics.density
        val gap = (12 * density).toInt()
        // 横屏屏高只有 ~300dp：波形区 176→70dp、内边距 24/20→12dp，
        // 让「状态 + 副标题 + 启动连接」全部留在首屏。
        // 预算：顶部栏 ~60 + 波形 70 + 状态 40 + 副标题 20 + 18 间距 + 按钮 52 + 内边距 24 ≈ 284dp
        val waveHeight = ((if (landscape) 70 else 176) * density).toInt()
        for (id in intArrayOf(R.id.heroWaveFrame, R.id.signalWaves)) {
            findViewById<View>(id).layoutParams =
                findViewById<View>(id).layoutParams.apply { height = waveHeight }
        }
        val padTop = ((if (landscape) 12 else 24) * density).toInt()
        val padBottom = ((if (landscape) 12 else 20) * density).toInt()
        hero.setPadding(hero.paddingStart, padTop, hero.paddingEnd, padBottom)
        // 横屏波形框只有 70dp：圆盘同步缩到 44dp，让外圈轨道与弧线仍有空间
        // （圆盘 74dp 会把波形框撑满、弧线被完全挤出可视区，真机截图暴露）
        val plateSize = ((if (landscape) 44 else 74) * density).toInt()
        val carSize = ((if (landscape) 24 else 38) * density).toInt()
        findViewById<View>(R.id.hubPlate).layoutParams =
            findViewById<View>(R.id.hubPlate).layoutParams.apply {
                width = plateSize
                height = plateSize
            }
        findViewById<View>(R.id.hubCar).layoutParams =
            findViewById<View>(R.id.hubCar).layoutParams.apply {
                width = carSize
                height = carSize
            }
        // 横屏波形框只有 70dp：装不下 CARPLAY 标签，标签让位
        // （圆盘是视觉主体，品牌字在顶部标题里已有）
        findViewById<View>(R.id.hubCaption).visibility =
            if (landscape) View.GONE else View.VISIBLE

        row.orientation = android.widget.LinearLayout.VERTICAL
        if (landscape) {
            if (hero.parent === row) {
                row.removeView(hero)
                contentArea.addView(hero)
            }
            // 宽度用**屏幕宽减页面内边距**推算，不读 contentArea.width：
            // 旋转瞬间后者还是旧方向的测量值（竖屏 1168 → 英雄卡只有 537px，
            // 副标题被挤成三行，真机截图暴露）。
            val pagePadding =
                (16 * resources.displayMetrics.density * 2).toInt()
            val available =
                (resources.displayMetrics.widthPixels - pagePadding).coerceAtLeast(1)
            val heroWidth = (available * 0.46f).toInt().coerceAtLeast(1)
            // 高度必须 WRAP_CONTENT：FrameLayout 会把 MATCH_PARENT/WRAP 视作铺满，
            // 铺满会把按钮顶到卡片底缘、副标题挤成三行（真机截图暴露）
            hero.layoutParams =
                android.widget.FrameLayout
                    .LayoutParams(
                        heroWidth,
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { gravity = android.view.Gravity.START or android.view.Gravity.TOP }
            // 右侧内容让出英雄卡宽度（含间距）：滚动条仍在屏幕右缘
            scroll.setPadding(heroWidth + gap, scroll.paddingTop, scroll.paddingEnd, scroll.paddingBottom)
            (detail.layoutParams as android.widget.LinearLayout.LayoutParams).apply {
                width = android.view.ViewGroup.LayoutParams.MATCH_PARENT
                weight = 0f
            }
        } else {
            if (hero.parent === contentArea) {
                contentArea.removeView(hero)
                row.addView(
                    hero,
                    0,
                    android.widget.LinearLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                    ),
                )
            }
            (hero.layoutParams as android.widget.LinearLayout.LayoutParams).apply {
                width = android.view.ViewGroup.LayoutParams.MATCH_PARENT
                weight = 0f
                topMargin = gap
                marginEnd = 0
            }
            scroll.setPadding(0, scroll.paddingTop, scroll.paddingEnd, scroll.paddingBottom)
            (detail.layoutParams as android.widget.LinearLayout.LayoutParams).apply {
                width = android.view.ViewGroup.LayoutParams.MATCH_PARENT
                weight = 0f
            }
        }
    }

    override fun onPause() {
        super.onPause()
        CarPlusResolutionHistory.preferences(this).unregisterOnSharedPreferenceChangeListener(displayHistoryListener)
        AppUiPresence.markPaused()
        mainHandler.removeCallbacks(heroTick)
        mainHandler.removeCallbacks(autoConnectCheck)
    }

    override fun onStop() {
        super.onStop()
        CarIdleGuard.onUiStopped(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        // 强制声明不得随 Activity 销毁残留窗口（旋转时 Activity 重建，onResume 会续弹）
        runCatching { activeDisclaimer?.dismiss() }
        activeDisclaimer = null
        // 蓝牙扫描/配对监听随界面销毁收尾：Activity 退出后不再需要（也避免广播泄漏）
        runCatching { stopTargetScan() }
        targetPicker = null
        // 只解绑"挂屏"，不动会话——连接由前台服务保活、对象归 SessionController 持有；
        // 显式断开请用「断开连接」按钮（任意实例都能断到真管线）。
        if (SessionController.logListener === logListener) SessionController.logListener = null
        if (SessionController.uiHintSink === uiHintSinkRef) SessionController.uiHintSink = null
        SessionController.dialogSink = null
        if (SessionController.ssidSink === ssidSink) SessionController.ssidSink = null
    }
}
