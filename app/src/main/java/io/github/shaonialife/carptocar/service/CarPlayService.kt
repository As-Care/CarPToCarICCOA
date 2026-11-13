package io.github.shaonialife.carptocar.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import io.github.shaonialife.carptocar.MainActivity
import io.github.shaonialife.carptocar.R
import io.github.shaonialife.carptocar.SessionController
import io.github.shaonialife.carptocar.bt.CarIdleGuard

/**
 * 前台保活服务：连接建立期间以 connectedDevice 类型常驻，
 * 防止 app 退到后台后被系统/MagicOS 省电策略杀死导致断连。
 * 连接逻辑仍运行在 MainActivity 派生的线程里——本服务负责保住进程。
 *
 * ## 通知栏是这个服务的第二个职责：唯一「看得见、关得掉」的界面（2026-10-30）
 *
 * 靠近车机自动启动把界面拉成**后台运行**时（系统限制后台弹界面 / 屏幕锁着），
 * 用户面前没有任何窗口，最近任务里也找不到——连接在跑，人却看不见，更没法停。
 * 所以常驻通知不能只是一行「运行中」：它必须能回答两个问题——
 *
 * 1. **在做什么**（[SessionController.notificationSummary]，每 3 秒刷新一次阶段文案）；
 * 2. **怎么关**（通知上的「断开」按钮，走 [NotificationActionReceiver]，不依赖界面存活）。
 *
 * 通道用 IMPORTANCE_DEFAULT + 静音：LOW 在 MagicOS 上会被折叠进「静默通知」，
 * 等于没通知；DEFAULT 则常驻通知栏、有图标，但不响不震。
 */
class CarPlayService : Service() {
    private var wakeLock: android.os.PowerManager.WakeLock? = null
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null

    /** 状态文案刷新：通知栏要跟着阶段走，否则「已连接」和「等车机」长得一样。 */
    private val uiHandler = Handler(Looper.getMainLooper())
    private val stateTick =
        object : Runnable {
            override fun run() {
                refreshNotification()
                uiHandler.postDelayed(this, STATE_TICK_MS)
            }
        }

    /**
     * 冻结监视：每秒一次心跳，靠 `elapsedRealtime()` 的跳变判定进程是否被系统冻结过。
     *
     * 放在前台服务里而不是会话线程里，因为主线程被冻结时 Handler 同样停摆——
     * 解冻后第一次 tick 就会看到「本该 1 秒前跑的心跳，现在才到」，差值就是冻结时长。
     */
    private val freezeWatch = FreezeWatch()

    private val freezeTick =
        object : Runnable {
            override fun run() {
                val frozen = freezeWatch.tick(android.os.SystemClock.elapsedRealtime())
                if (frozen > 0) {
                    // 先把「刚冻过」记到全局：短冻结（<10s）不会走重建，但一样能把会话拖死，
                    // 拆链归因要靠这一笔才不冤枉音频档（见 ProcFreeze 注释）。
                    ProcFreeze.noteDetected()
                    val seconds = frozen / 1_000
                    val stalled = freezeWatch.stallMillis(frozen) / 1_000
                    SessionController.appendLog(
                        "PROC: 进程被系统冻结 ${seconds} 秒（心跳中断 ${stalled}s）——" +
                            "冻结期间不发帧、不回 keepAlive，车机多半已按超时撤掉会话",
                    )
                    // 短暂迟到（< 10s）只记录不惊动：会话通常没受伤，重建反而多一次断链
                    if (frozen >= FreezeWatch.RECOVER_MIN_FROZEN_MS) {
                        SessionController.onProcessResumed(frozen)
                    }
                }
                uiHandler.postDelayed(this, FreezeWatch.DEFAULT_TICK_MS)
            }
        }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * 息屏保活：CPU 唤醒锁维持编码/协议线程与 TCP 存活探测的响应；
     * WiFi 低延迟锁阻止无线进入激进省电（否则接收端 20s 内判死拆链）。
     */
    private fun acquireLocks() {
        if (wakeLock?.isHeld == true && wifiLock?.isHeld == true) return
        val power = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        wakeLock =
            power.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "CarPlaySender:session").apply {
                setReferenceCounted(false)
                acquire()
            }
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
        wifiLock =
            wifi
                .createWifiLock(
                    android.net.wifi.WifiManager.WIFI_MODE_FULL_LOW_LATENCY,
                    "CarPlaySender:wifi",
                ).apply {
                    setReferenceCounted(false)
                    acquire()
                }
    }

    private fun releaseLocks() {
        runCatching { wakeLock?.release() }
        wakeLock = null
        runCatching { wifiLock?.release() }
        wifiLock = null
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        androidx.core.app.ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
        acquireLocks()
        // 通知栏文案跟着会话阶段走：服务一活就开始刷新，销毁时停。
        uiHandler.removeCallbacks(stateTick)
        uiHandler.post(stateTick)
        // 冻结监视：重置起算点后再开始心跳（服务重启时不能拿上一轮的 lastTick 算差值）
        uiHandler.removeCallbacks(freezeTick)
        freezeWatch.reset()
        uiHandler.postDelayed(freezeTick, FreezeWatch.DEFAULT_TICK_MS)
        return START_STICKY
    }

    /**
     * 用户从最近任务划掉了 App（2026-10-30 实机反馈）。
     *
     * 划卡片只销毁 Activity，**不会**停前台服务——于是出现「窗口关掉了、进程还在、
     * 通知栏那条还挂着」的观感：用户已经明确表示不要它了，系统却按服务的意愿继续保活。
     *
     * 这里把它当作用户的终态指令：走和「通知栏断开」「车机远离 30 秒」一样的收摊路径
     * （结束会话 → 停服务 → 清通知 → 退进程），并置抑制避免车机还连着时立刻又被拉起。
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        CarIdleGuard.shutdown(
            this,
            reason = "用户从最近任务划掉了 App",
            killProcess = true,
            suppressAutoStart = true,
        )
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        uiHandler.removeCallbacks(stateTick)
        uiHandler.removeCallbacks(freezeTick)
        freezeWatch.reset()
        releaseLocks()
        super.onDestroy()
    }

    companion object {
        /**
         * 通道 id 与 1.2.2 之前**不同**：旧通道 `carplay_connection` 建成了 IMPORTANCE_LOW，
         * 在 MagicOS 上会被折叠进「静默通知」——用户面前等于没有通知。
         * 通道的重要级别创建后程序改不了（只有用户能改），所以换 id 重建。
         */
        private const val CHANNEL_ID = "carplay_session_v2"
        private const val LEGACY_CHANNEL_ID = "carplay_connection"

        /** 常驻通知 id：收摊时由 [io.github.shaonialife.carptocar.bt.CarIdleGuard] 兜底撤掉。 */
        const val NOTIFICATION_ID = 1

        /** 通知栏文案刷新间隔：够跟上阶段变化，又不至于频繁唤醒。 */
        private const val STATE_TICK_MS = 3_000L

        /** 首帧文案（服务刚起来、[SessionController.notificationSummary] 还没跑第一轮时用）。 */
        private const val DEFAULT_TEXT = "正在准备连接车机…（点「断开」可随时停止）"

        /** 通知上的「断开」——直接断开并停服务，不依赖界面是否存活。 */
        const val ACTION_DISCONNECT = "io.github.shaonialife.carptocar.action.NOTIFICATION_DISCONNECT"
        const val ACTION_RECONNECT_CAR_PLUS = "io.github.shaonialife.carptocar.action.NOTIFICATION_RECONNECT_CAR_PLUS"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, CarPlayService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CarPlayService::class.java))
        }
    }

    /** 文案随会话阶段刷新（通知栏是后台连接时唯一的可见界面）。 */
    private fun refreshNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        runCatching {
            manager.notify(NOTIFICATION_ID, buildNotification(SessionController.notificationSummary()))
        }
    }

    private fun buildNotification(text: String = DEFAULT_TEXT): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "车机连接",
                    // DEFAULT + 静音：LOW 会被 MagicOS 折叠进「静默通知」，等于看不见；
                    // DEFAULT 常驻通知栏有图标，但通道里不给声音、不给震动。
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = "连接车机期间常驻，可在此直接断开"
                    setShowBadge(false)
                    setSound(null, null)
                    enableVibration(false)
                },
            )
            // 旧通道留着只会在设置里多一条「静默」的重复项，删掉
            runCatching { manager.deleteNotificationChannel(LEGACY_CHANNEL_ID) }
        }
        // 点击通知 → 打开 App 主界面（此前缺 setContentIntent，点击无反应）
        val contentIntent =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        // 「断开」用广播而不是 Activity：后台自动连接时界面可能根本不存在，
        // 通知是用户唯一能点的地方，必须能独立于界面把会话停掉。
        val disconnectIntent =
            PendingIntent.getBroadcast(
                this,
                1,
                Intent(this, NotificationActionReceiver::class.java).setAction(ACTION_DISCONNECT),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        return NotificationCompat
            .Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("${getString(R.string.app_name)} · 车机连接")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(contentIntent)
            .apply {
                if (SessionController.canReconnectCarPlusFromNotification(this@CarPlayService)) {
                    val reconnectIntent = PendingIntent.getBroadcast(
                        this@CarPlayService,
                        2,
                        Intent(this@CarPlayService, NotificationActionReceiver::class.java)
                            .setAction(ACTION_RECONNECT_CAR_PLUS),
                        PendingIntent.FLAG_IMMUTABLE,
                    )
                    addAction(0, "重新连接 Car+", reconnectIntent)
                }
            }
            .addAction(0, "断开", disconnectIntent)
            .addAction(0, "打开界面", contentIntent)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
    }
}
