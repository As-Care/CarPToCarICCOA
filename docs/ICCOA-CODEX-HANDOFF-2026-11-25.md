# CarPToCar+ICCOA 接手说明

更新于 2026 年 11 月 25 日，中国标准时间。

**接手时先保留当前工作区。奥迪建链改动已写入源码，476 项单测通过，下一步是用当前源码打包后上车验证。** 这批改动尚未提交，文末附有完整补丁，换账号后如果打开同一份本地工作区，可以直接继续。

## 项目和使用目标

- 项目名为 CarPToCar+ICCOA，本机仓库在 `/Users/ashang/code/githubFile/CarPToCarO`，远端为 `As-Care/CarPToCarICCOA`。Android 包名是 `io.github.shaonialife.carptocar.carplus`。
- 一加手机（PMS110，ColorOS 17）上的系统 Car+ 提供画面和声音。App 在手机本机接收 ICCOA，再通过无线 CarPlay 转发给奥迪 A4L 的 MIB3 MMI，并回传触控。实测车机报告型号 AUDI 4930。MMI 已保存这台手机的 CarPlay 记录。
- 用户接受上车后解锁一次。Thanox 会拉起 App，随后应尽可能自动建立 CarPlay，并请求原生 Car+ 在后台重连。冷启动全程锁屏自启无需实现。如果 MMI 仍需要手动触发，用户接受在车机上点一次 CarPlay，希望省掉在手机上手动连接「奥迪蓝牙」的步骤。
- 本轮开发范围是手机到奥迪的 CarPlay 建链。后续请按用户指示保持 UI、其他车型、视频模式及已配对数据。用户要求保留 Surface 视频兜底，并由 Gemini 打包、本人上车试。对手机做 ADB 排查时保持手机运行，重启须由用户明确授权。

## 当前工作区

- `main` 基线为 `afaf4d3e66ee8acb6c30f0c7cfa401e1111a69cf`，属于 2.0.7。README 的版本描述更旧，版本以 `app/build.gradle.kts`、安装包和 App 进程启动日志为准。当前源码仍标为 2.0.7，后续核对实车是否用到新包时，应同时记录打包来源和日志行为。
- `MainActivity.maybeAutoConnect()` 走现有入口。`SessionController.startConnection()` 先启动蓝牙入站监听，再启动出站客户端。所选奥迪在自动连接时立即拨 iAP2，保留对 MMI 回连的监听，移除了原来前置的 12 秒等待。
- 所选奥迪首次直连失败后，尝试释放目标设备当前连接的 HFP 通话和媒体音频，再立即拨一次。当前没有普通蓝牙配置文件在线时也重拨一次。MMI 入站抢先建立或用户取消时，已有门控会让出后续的出站尝试。其他车型和手动发起的重试策略沿用原分支，车机热点、AirPlay、Car+ 和视频逻辑沿用现有流程。
- 手动断开后的重连冷却为 **5 秒**，实现位于 `BtSessionGate.REACTIVATE_COOLDOWN_MILLIS`。它是 App 开始再拨的时刻。旧测试曾观察到车机还在保留旧会话，表现为能拿到热点凭据，AirPlay 服务却迟迟不出现。11 月 16 日那次成功的手动重连隔了约 18 秒，5 秒还需要单独上车验证。
- 这一批未提交的生产改动在 `app/src/main/java/io/github/shaonialife/carptocar/SessionController.kt` 和 `app/src/main/java/io/github/shaonialife/carptocar/bt/Iap2BluetoothClient.kt`。回归测试在 `app/src/test/java/io/github/shaonialife/carptocar/bt/Iap2BluetoothRetryTest.kt`。全量 JVM 单测为 **476 项通过，0 失败，0 跳过**。本轮未安装验证包，实车效果待验证。

## 已核实的现象和历史记录

- MMI「连接的设备」页之前显示手机同时占用「电话 1」「音频播放器」，而「Audi 智能手机接口」未连接。用户观察到 CarPlay 成功时，智能手机接口显示已连接，普通电话和媒体槽位往往空着。普通蓝牙已连接只说明对应配置文件在线。日志中也见过普通配置文件在线后仍能成功的轮次，单凭截图不足以断定阻塞原因。App 主动拨的是 CarPlay 的 iAP2 服务。
- 2.0.0（`9e4f9ed`）、2.0.1（`40cf017`）、2.0.2（`18bf71d`）的蓝牙拨号及配置文件处理源码相同。首次直连失败时就释放普通蓝牙并重拨。2.0.3（`c0fbfa0`）起，首拨失败后留约 6 秒回连窗口，第二次直连失败才释放配置文件。2.0.5（`04fa5f8`）和 2.0.6（`04be9cd`）沿用这个策略。2.0.7（`afaf4d3`）对奥迪自动连接增加了前置 12 秒等待，并跳过释放配置文件的兜底。用户感觉 2.0.5 到 2.0.7 车机连接都不好用，因此决定以 **2.0.2 的建链时序**为对照，移植有限改动。

| 手机日志时间 | 版本 | 观察到的连接过程 |
| --- | --- | --- |
| 11 月 11 日 18:43 | 1.2.54 | App 主动连上 iAP2，先直连 AirPlay，MMI 邀请约 5 秒后才到。18:44:07 发出第一帧。 |
| 11 月 13 日 19:05 | 2.0.0 | App 启动后先尝试主动拨号，释放通话和媒体蓝牙，后来接到 MMI 回连，19:06:34 出首帧。 |
| 11 月 16 日 18:34 | 2.0.2 | 一轮走 MMI 回连后出画面。手动断开约 18 秒后进入下一轮，App 主动拨 iAP2，约 0.12 秒建立 RFCOMM，18:35:14 出首帧。 |
| 11 月 17 日 09:23 | 2.0.2 | App 启动即主动尝试，多轮后 09:24:38 拨上 iAP2，09:24:55 出首帧。 |
| 11 月 17 日 18:38、18 日 09:26 | 2.0.5 | 17 日晚由 MMI 回连成功。18 日早最初通话和媒体均在线，直连失败并请求释放后，下一轮于 09:27:04 主动连上 iAP2。09:27:08 App 连接 AirPlay，09:27:12 才收到 MMI 邀请，09:27:15 请求原生 Car+ 后台重连，09:27:21 出首帧。 |
| 11 月 20 日 09:25 | 2.0.6 | 启动即主动拨号，释放配置文件后接到 MMI 回连，09:26:24 出首帧。 |
| 11 月 25 日 09:17、11:59 | 2.0.7 旧包 | 前置等 12 秒，通话和媒体连接在线，自动拨 iAP2 多次失败。09:18:33 后续一轮拨上，09:18:51 出帧。进入 AirPlay 建链阶段到首帧约 14 秒，和 11 月 18 日成功轮次接近。 |

旧日志能确认发起方向和先后顺序，无法还原有没有人在 MMI 屏幕上点过 CarPlay。已经有证据表明 App 可以在收到 MMI 的 HTTP 邀请前主动连接已发现的 AirPlay 服务，目前仍待验证在 MMI 尚未打开服务窗口时能否完全自动启动。把 iAP2 蓝牙建立、Wi-Fi/AirPlay、Car+ 后台连接和首帧分别记录。

## 视频直通与短卡顿

- **保留 Surface 兜底。** 11 月 25 日成功出画面的日志仍有回退。车机目标画布为 **1412×654**，系统 Car+ 源 H.264 实际为 **1412×656、High（`profile_idc=100`）**，日志明确显示档位不兼容并切回 Surface。标记 `ICCOA H.264 直通 · 等待画面` 只是尝试进入直通。该奥迪仍缺持续以直通模式出图的实车证据。
- 11 月 18 日诊断时，临时内部配置值已进入编码器参数链路。默认对照和试验都在创建 MediaCodec 时因设备媒体服务异常提前失败，未拿到 Baseline 和精确 1412×654 的结果。临时诊断 APK、测试配置和已确认的测试辅助文件已清理，生产 App 和配对数据保留，手机未重启。本机仍有 `.tools/iccoa-passthrough-20261118/findings.md` 和 `cleanup.md` 可供核对。
- 用户曾遇到一次约 1–2 秒的卡顿，尚未确定是音频还是视频。若再复现，先记准确时间、画面与声音各自的表现，再对照该时段日志。近期优先完成车机建链验收。

## 打包和上车验收

1. **从这份含本地改动的工作区打包。** 仅用 SDK stubs 可以编译和跑单测。真机包须使用完整 ICCOA runtime 与现有 relay assets，构建任务为 `:app:packageCarPlusRuntime`。环境与产物位置见 [Mac 构建说明](carplus-mac-validation.md)。本机 JDK、Gradle、Android SDK 和 ICCOA 组件在 `.tools/`，运行时在 `.tools/references/miauto/runtime/`。该目录下的本地依赖需在新机器上另行准备。
2. **先测日常上车。** 在停车状态下启动 MMI，解锁手机让 Thanox 拉起 App。先观察是否自动出画面，同时记下唤起时间和首帧时间。
3. **再测车机一键兜底。** 如果没有自动出画面，只在 MMI 的「Audi 智能手机接口」里点一次已保存的 CarPlay 设备。记录从点击到出画面的时间，以及手机是否还需要手动连普通蓝牙。
4. **最后单测手动断开后的 5 秒。** 在 App 中断开后点击重新连接，确认到第 5 秒发起，并区分是否卡在 iAP2、已入网但没 AirPlay，或 Car+ 已请求重连但无首帧。

复现后在 App 底部「运行日志」导出。长按导出按钮可以保存 App 当前保留的历史，路径为手机的 `/sdcard/Download/CarPToCar+ICCOA/`。历史导出表头是导出时的版本，多版本文件要以各进程的 `CarPToCar+ICCOA … 就绪` 行识别当轮版本。按顺序定位 `AUTOCONNECT`、`BT-PROFILE`、`RFCOMM 建立` 或 `接收端已连入`、`_airplay._tcp`、`车机 ctrl 邀请`、`RECORD`、`已请求原生 Car+ 后台重连`、`首帧已到达`。现场日志可能含设备标识，向外发出前先核对要分享的内容。

在当前 Mac 上，从仓库根目录运行以下命令复跑单测。

```sh
JAVA_HOME="$PWD/.tools/temurin17/Contents/Home" \
  GRADLE_USER_HOME="$PWD/.tools/gradle-user-home" \
  .tools/gradle-8.10.2/bin/gradle --offline \
  -Dorg.gradle.java.home="$PWD/.tools/temurin17/Contents/Home" \
  -PcarPlusSdkStubs="$PWD/.tools/references/miauto/icar-sdk-stubs.jar" \
  :app:testDebugUnitTest
```

## 附录：未提交的源码补丁

同一台电脑只需打开原工作区并运行 `git status --short` 检查三个源码及测试文件。若新账号使用的是基线 `afaf4d3` 的干净检出，可从本页末尾的 `diff` 代码块提取补丁，先用 `git apply --check` 校验，再用 `git apply` 恢复。若工作区已有更新的修改，先比较差异并保留它们。

<details>
<summary>展开完整补丁</summary>


```diff
diff --git a/app/src/main/java/io/github/shaonialife/carptocar/SessionController.kt b/app/src/main/java/io/github/shaonialife/carptocar/SessionController.kt
index fa70d5e..bae35f4 100644
--- a/app/src/main/java/io/github/shaonialife/carptocar/SessionController.kt
+++ b/app/src/main/java/io/github/shaonialife/carptocar/SessionController.kt
@@ -933,8 +933,8 @@ object SessionController {
     /** 恢复前把蓝牙组件拉起来（已运行则空操作）。真车/测试台两条路径都拉，判断交给组件自身。 */
     private fun restartBluetoothForRecovery() {
         if (bluetoothClient?.isRunning == true && bluetoothServer?.isRunning == true) return
-        val listening = runCatching { bluetoothServer?.start() }.getOrNull() == true
-        runCatching { bluetoothClient?.start(automaticConnection, inboundListening = listening && lastGoodEndpoint == null) }
+        runCatching { bluetoothServer?.start() }
+        runCatching { bluetoothClient?.start(automaticConnection) }
         appendLog("PROC: 蓝牙链路已重新拉起（等车机重新下发 0x4301）")
     }
 
@@ -1517,8 +1517,8 @@ object SessionController {
                 ctrlAdvertiser?.resetInviteCount()
                 // 首次广播要读取真实蓝牙地址，留在后台。
                 Bg.run("ctrl-publish") { if (sessionWanted) ctrlAdvertiser?.start() }
-                val listening = bluetoothServer?.start() == true
-                bluetoothClient?.start(automatic, inboundListening = listening)
+                bluetoothServer?.start()
+                bluetoothClient?.start(automatic)
             }
         }
         if (delay > 0) {
diff --git a/app/src/main/java/io/github/shaonialife/carptocar/bt/Iap2BluetoothClient.kt b/app/src/main/java/io/github/shaonialife/carptocar/bt/Iap2BluetoothClient.kt
index b3aa156..40cf3fa 100644
--- a/app/src/main/java/io/github/shaonialife/carptocar/bt/Iap2BluetoothClient.kt
+++ b/app/src/main/java/io/github/shaonialife/carptocar/bt/Iap2BluetoothClient.kt
@@ -87,7 +87,6 @@ class Iap2BluetoothClient(
 
         /** 从首次直连开始给车机留出回连窗口。AUDI 4930 曾在约 4 秒时主动连入。 */
         private const val CAR_RECONNECT_WINDOW_MILLIS = 6_000L
-        private const val AUDI_AUTOMATIC_RECONNECT_WAIT_MILLIS = 12_000L
 
         /** 目标连不上多少轮后放开限制（16s/轮 → 约 32s 后回退，避免一个死目标堵死整轮）。 */
         private const val TARGET_FALLBACK_ROUNDS = 2
@@ -298,7 +297,7 @@ class Iap2BluetoothClient(
             PackageManager.PERMISSION_GRANTED
 
     @Synchronized
-    fun start(automatic: Boolean = false, inboundListening: Boolean = false): Boolean {
+    fun start(automatic: Boolean = false): Boolean {
         if (running) return true
         if (!hasPermission()) {
             log("BT-C: 缺少 BLUETOOTH_CONNECT 权限")
@@ -332,7 +331,7 @@ class Iap2BluetoothClient(
                     "（未指定目标：依次尝试全部候选设备，可在「连接准备」中指定）"
                 },
         )
-        Thread({ connectLoop(myGeneration, automaticAudiTarget, inboundListening) }, "iap2-bt-client").apply {
+        Thread({ connectLoop(myGeneration, automaticAudiTarget) }, "iap2-bt-client").apply {
             isDaemon = true
             thread = this
             start()
@@ -344,7 +343,6 @@ class Iap2BluetoothClient(
     private fun connectLoop(
         myGeneration: Long,
         automaticAudiTarget: TargetDevicePrefs.Target?,
-        inboundListening: Boolean,
     ) {
         val adapter =
             try {
@@ -352,12 +350,6 @@ class Iap2BluetoothClient(
             } catch (_: Exception) {
                 null
             } ?: return
-        if (automaticAudiTarget != null && inboundListening) {
-            log("BT-C: 奥迪自动连接 · 保留通话和媒体蓝牙，先等车机回连 ${AUDI_AUTOMATIC_RECONNECT_WAIT_MILLIS / 1000} 秒")
-            if (waitForCarInitiated(myGeneration, AUDI_AUTOMATIC_RECONNECT_WAIT_MILLIS, BACKOFF_POLL_MILLIS)) {
-                log("BT-C: 等待奥迪回连超时 · 保留普通蓝牙，开始主动拨 iAP2")
-            }
-        }
         var lastFailureLogAt = 0L
         var targetFailRounds = 0
         /** 连续「SDP 层失败」的轮数（见 [PASSIVE_FALLBACK_ROUNDS]）。 */
@@ -365,16 +357,7 @@ class Iap2BluetoothClient(
         /** 连续「没有有效会话」的轮数，用于 [RETRY_BACKOFF_MILLIS] 退避（连上即断也算）。 */
         var failStreak = 0
         connectLoop@ while (running && generation.get() == myGeneration) {
-            // 用户刚手动断开 → 先等车机「忘掉」上一轮，别急着拨。
-            //
-            // 2026-11-04 奥迪 4910（MIB3 4580）实录：16:22:32.519 手动断开，
-            // 16:22:33.696（1.2 秒后）重连。车机照常给了 iAP2 身份、0x5703 热点凭据，
-            // 但 **48 秒里一次 `_airplay._tcp` 都没广播、0x4301 也没回**——因为固件里
-            // `mediaDisconnectTimeout = 15000ms`，设备没被判「已移除」之前它只走
-            // `keepIAP2Session = 1` 分支：重开旧会话，不启动 projection。
-            // 结果是我们拿网关兜底、8 次重连全超时（fe80::1086:…:7000 after 2000ms）。
-            //
-            // 等够 [BtSessionGate.REACTIVATE_COOLDOWN_MILLIS] 再拨，这一次才是「新建会话」。
+            // 手动断开后的拨号冷却与界面的重连倒计时使用同一截止时间。
             val cooldownLeft = BtSessionGate.manualStopCooldownLeft(targetProvider()?.address)
             if (cooldownLeft > 0) {
                 sleepQuietly(cooldownLeft)
@@ -407,8 +390,9 @@ class Iap2BluetoothClient(
                 sleepQuietly(2_000)
                 continue
             }
-            val preserveAudiProfiles = recoveryPeers == null && automaticAudiTarget != null &&
-                TargetDevicePrefs.matches(automaticAudiTarget, targetProvider()?.address)
+            val selectedAutomaticAudi = automaticAudiTarget?.takeIf {
+                recoveryPeers == null && TargetDevicePrefs.matches(it, targetProvider()?.address)
+            }
             val candidates =
                 if (recoveryPeers == null) {
                     candidateDevices(adapter)
@@ -462,9 +446,11 @@ class Iap2BluetoothClient(
                     }
                     continue
                 }
-                // 奥迪自动连接和未确认的个人音频设备只拨一次，保留通话和媒体连接。
-                val allowProfileFallback = !preserveAudiProfiles &&
-                    (gate == Iap2Service.HAS || !looksLikePersonalAudio(device))
+                // 未确认的个人音频设备只拨一次，保留通话和媒体连接。
+                val allowProfileFallback = gate == Iap2Service.HAS || !looksLikePersonalAudio(device)
+                // 所选奥迪恢复 2.0.2 的时序：首次拨号失败就释放普通蓝牙，再拨一次。
+                val retryBeforeDisconnect = selectedAutomaticAudi == null ||
+                    !TargetDevicePrefs.matches(selectedAutomaticAudi, device.address)
                 val canAttempt = {
                     running && generation.get() == myGeneration &&
                         !BtSessionGate.inboundActive &&
@@ -474,6 +460,7 @@ class Iap2BluetoothClient(
                 val reconnectDeadline = SystemClock.uptimeMillis() + CAR_RECONNECT_WINDOW_MILLIS
                 val socket = connectWithProfileFallback(
                     allowProfileFallback = allowProfileFallback,
+                    retryBeforeDisconnect = retryBeforeDisconnect,
                     canAttempt = canAttempt,
                     connect = { tryConnect(device, myGeneration) },
                     waitForCar = {
@@ -486,7 +473,9 @@ class Iap2BluetoothClient(
                             log("BT-C: 仍未收到「$name」回连——保留蓝牙再试一次")
                         }
                     },
-                    disconnectProfiles = { disconnectProfilesForRetry(device, name, canAttempt) },
+                    disconnectProfiles = {
+                        disconnectProfilesForRetry(device, name, retryBeforeDisconnect, canAttempt)
+                    },
                 )
                 if (!canAttempt()) {
                     // 取消或车机抢先连入时，让出出站通道，不把这一轮算作失败。
@@ -534,7 +523,7 @@ class Iap2BluetoothClient(
                 // 目标连续连不上 → 放开目标限制（见 BtSessionGate.targetSoftFallback）。
                 // 只在「用户指定了目标」时计数，且只降级一次。
                 // 奥迪自动连接和控制链路补连始终保留所选目标，避免尝试其他已配对设备。
-                if (recoveryPeers == null && !preserveAudiProfiles &&
+                if (recoveryPeers == null && selectedAutomaticAudi == null &&
                     targetProvider() != null &&
                     !BtSessionGate.targetSoftFallback
                 ) {
@@ -599,6 +588,7 @@ class Iap2BluetoothClient(
     private fun disconnectProfilesForRetry(
         device: BluetoothDevice,
         name: String,
+        retryBeforeDisconnect: Boolean,
         canAttempt: () -> Boolean,
     ): Boolean {
         // 部分车机需要先释放通话和媒体蓝牙才会开放 iAP2，保留这条兼容路径。
@@ -608,8 +598,9 @@ class Iap2BluetoothClient(
         if (canAttempt() && now - lastProfileLogAt > PROFILE_LOG_THROTTLE_MILLIS) {
             lastProfileLogAt = now
             val left = BtProfiles.stillConnected(context, device.address)
+            val failedAttempts = if (retryBeforeDisconnect) "两次" else "首次"
             log(
-                "BT-C: 「$name」两次直连失败——已请求断开 ${dropped.joinToString("/")} 再试" +
+                "BT-C: 「$name」${failedAttempts}直连失败——已请求断开 ${dropped.joinToString("/")} 再试" +
                     if (left.isEmpty()) "" else "（仍有 ${left.joinToString("/")} 在线）",
             )
         }
@@ -867,9 +858,10 @@ internal fun awaitIap2DialWindow(
     return false
 }
 
-/** 只有保留蓝牙的两次直连都失败，才允许断开通话和媒体蓝牙后再拨。 */
+/** 首次直连失败后，可先保留普通蓝牙再拨一次，再释放配置文件重试。 */
 internal fun <Connection> connectWithProfileFallback(
     allowProfileFallback: Boolean,
+    retryBeforeDisconnect: Boolean = true,
     canAttempt: () -> Boolean,
     connect: () -> Connection?,
     waitForCar: () -> Unit,
@@ -878,10 +870,15 @@ internal fun <Connection> connectWithProfileFallback(
     if (!canAttempt()) return null
     connect()?.let { return it }
     if (!allowProfileFallback || !canAttempt()) return null
-    waitForCar()
+    if (retryBeforeDisconnect) {
+        waitForCar()
+        if (!canAttempt()) return null
+        connect()?.let { return it }
+    }
     if (!canAttempt()) return null
-    connect()?.let { return it }
-    if (!canAttempt() || !disconnectProfiles() || !canAttempt()) return null
+    val disconnected = disconnectProfiles()
+    // 奥迪的首次失败即进入重试，即使没有已连接的配置文件需要断开。
+    if (!canAttempt() || (retryBeforeDisconnect && !disconnected)) return null
     return connect()
 }
 
@@ -953,15 +950,15 @@ object BtSessionGate {
      * 热点和凭据 → 之后 48 秒里 mDNS 一直是「未发现」，只能拿网关兜底，8 次重连全超时。
      * 而前一轮（车机主动连入、它自己启动的 projection）10 秒就连上了。
      *
-     * 所以：手动断开后要给车机留足 [REACTIVATE_COOLDOWN_MILLIS] 再发起。
+     * App 等待 [REACTIVATE_COOLDOWN_MILLIS] 后发起重连。车机释放旧会话所需的时间由固件决定。
      */
     private data class ManualStop(val address: String?, val at: Long)
 
     @Volatile
     private var manualStop: ManualStop? = null
 
-    /** Audi's 15-second removal window plus a guard interval. */
-    private const val REACTIVATE_COOLDOWN_MILLIS = 18_000L
+    /** Delay before initiating a new connection after manual disconnection. */
+    private const val REACTIVATE_COOLDOWN_MILLIS = 5_000L
 
     fun noteManualStop(address: String?) {
         manualStop = ManualStop(address, android.os.SystemClock.uptimeMillis())
diff --git a/app/src/test/java/io/github/shaonialife/carptocar/bt/Iap2BluetoothRetryTest.kt b/app/src/test/java/io/github/shaonialife/carptocar/bt/Iap2BluetoothRetryTest.kt
index 6cf1c6b..9fbd28f 100644
--- a/app/src/test/java/io/github/shaonialife/carptocar/bt/Iap2BluetoothRetryTest.kt
+++ b/app/src/test/java/io/github/shaonialife/carptocar/bt/Iap2BluetoothRetryTest.kt
@@ -8,25 +8,25 @@ import org.junit.Test
 
 class Iap2BluetoothRetryTest {
     @Test
-    fun initialListenWindowAllowsDialingAtTheDeadline() {
+    fun listenWindowAllowsDialingAtTheDeadline() {
         var now = 1_000L
-        val deadline = now + 12_000L
+        val deadline = now + 6_000L
 
         assertTrue(awaitIap2DialWindow(deadline, 250L, { now }, { now += it }) { true })
         assertEquals(deadline, now)
     }
 
     @Test
-    fun inboundCarStopsTheInitialWaitBeforeAnyDial() {
+    fun inboundCarStopsTheWaitBeforeTheNextDial() {
         var now = 0L
 
-        assertFalse(awaitIap2DialWindow(12_000L, 250L, { now }, { now += it }) { now < 9_000L })
-        assertEquals(9_000L, now)
+        assertFalse(awaitIap2DialWindow(6_000L, 250L, { now }, { now += it }) { now < 4_000L })
+        assertEquals(4_000L, now)
     }
 
     @Test
     fun cancelledConnectionCannotDialEvenAfterTheDeadline() {
-        assertFalse(awaitIap2DialWindow(12_000L, 250L, { 12_000L }, { error("Cancelled wait") }) { false })
+        assertFalse(awaitIap2DialWindow(6_000L, 250L, { 6_000L }, { error("Cancelled wait") }) { false })
     }
 
     @Test
@@ -78,13 +78,26 @@ class Iap2BluetoothRetryTest {
     }
 
     @Test
-    fun profilePreservingModeOnlyDialsOnceAfterTheInitialWait() {
+    fun audiReleasesProfilesAfterTheFirstFailureAndRetriesImmediately() {
         val car = ConnectionAttempts(null, "fallback")
-        var now = 0L
 
-        if (awaitIap2DialWindow(12_000L, 250L, { now }, { now += it }) { true }) {
-            assertNull(car.connect(allowProfileFallback = false))
-        }
+        assertEquals("fallback", car.connect(retryBeforeDisconnect = false))
+        assertEquals(listOf("connect", "disconnect profiles", "connect"), car.events)
+    }
+
+    @Test
+    fun audiStillRetriesWhenNoProfilesAreConnected() {
+        val car = ConnectionAttempts(null, "fallback")
+
+        assertEquals("fallback", car.connect(retryBeforeDisconnect = false, profilesConnected = false))
+        assertEquals(listOf("connect", "disconnect profiles", "connect"), car.events)
+    }
+
+    @Test
+    fun audiSkipsProfileTeardownWhenInboundWinsDuringTheFirstDial() {
+        val car = ConnectionAttempts(null)
+
+        assertNull(car.connect(retryBeforeDisconnect = false, carReturnsAfterFirstDial = true))
         assertEquals(listOf("connect"), car.events)
     }
 
@@ -94,6 +107,9 @@ class Iap2BluetoothRetryTest {
 
         fun connect(
             allowProfileFallback: Boolean = true,
+            retryBeforeDisconnect: Boolean = true,
+            profilesConnected: Boolean = true,
+            carReturnsAfterFirstDial: Boolean = false,
             carReturnsDuringWait: Boolean = false,
             cancelAfterAttempts: Int = Int.MAX_VALUE,
         ): String? {
@@ -101,10 +117,12 @@ class Iap2BluetoothRetryTest {
             var inboundConnected = false
             return connectWithProfileFallback(
                 allowProfileFallback = allowProfileFallback,
+                retryBeforeDisconnect = retryBeforeDisconnect,
                 canAttempt = { !inboundConnected && attempts < cancelAfterAttempts },
                 connect = {
                     events += "connect"
                     attempts++
+                    if (attempts == 1 && carReturnsAfterFirstDial) inboundConnected = true
                     results.next()
                 },
                 waitForCar = {
@@ -113,7 +131,7 @@ class Iap2BluetoothRetryTest {
                 },
                 disconnectProfiles = {
                     events += "disconnect profiles"
-                    true
+                    profilesConnected
                 },
             )
         }
```

</details>
