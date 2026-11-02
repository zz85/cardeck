package io.github.shaonialife.cardeck

import android.Manifest
import android.animation.ObjectAnimator
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.method.ScrollingMovementMethod
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import io.github.shaonialife.cardeck.bt.AdapterMode
import io.github.shaonialife.cardeck.bt.BtProfiles
import io.github.shaonialife.cardeck.bt.TargetDevicePrefs
import io.github.shaonialife.cardeck.input.TouchRouter
import io.github.shaonialife.cardeck.launcher.VirtualLauncher
import io.github.shaonialife.cardeck.launcher.settings.AppOrientationCompat
import io.github.shaonialife.cardeck.legal.DisclaimerDialog
import io.github.shaonialife.cardeck.legal.DisclaimerPrefs
import io.github.shaonialife.cardeck.legal.needsDisclaimerAtLaunch
import io.github.shaonialife.cardeck.media.NotificationAccess
import io.github.shaonialife.cardeck.service.CarPlayService
import io.github.shaonialife.cardeck.shizuku.CarInputClient
import io.github.shaonialife.cardeck.shizuku.Shell
import io.github.shaonialife.cardeck.shizuku.ShizukuGate
import io.github.shaonialife.cardeck.ui.DarkMessageDialog
import io.github.shaonialife.cardeck.ui.PanelWindow
import io.github.shaonialife.cardeck.ui.ScreenDimmer
import io.github.shaonialife.cardeck.ui.SignalWavesView
import io.github.shaonialife.cardeck.ui.WallpaperStore
import io.github.shaonialife.cardeck.util.Bg
import io.github.shaonialife.cardeck.util.LogExporter
import java.util.Locale

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
        const val DEFAULT_CONNECTING_HINT = "Bluetooth iAP2 handshake → join car hotspot → start CarPlay session"
        const val KEY_DISCLAIMER_DEADLINE = "disclaimer_deadline"
    }

    private lateinit var deviceSubtitle: TextView
    private lateinit var shizukuChip: TextView
    private lateinit var shizukuDesc: TextView
    private lateinit var shizukuBadge: TextView
    private lateinit var signalWaves: SignalWavesView
    private lateinit var hubPlate: FrameLayout
    private lateinit var hubCar: ImageView
    private lateinit var hubCaption: TextView
    private lateinit var heroStateText: TextView
    private lateinit var btnResidualCleanup: TextView
    private lateinit var heroSubline: TextView
    private lateinit var heroStats: TextView
    private lateinit var heroProgress: View
    private lateinit var ctaButton: LinearLayout
    private lateinit var ctaIcon: ImageView
    private lateinit var ctaLabel: TextView
    private lateinit var audioTag: TextView
    private lateinit var readyCount: TextView
    private lateinit var ssidInput: EditText
    private lateinit var btDesc: TextView
    private lateinit var btBadge: TextView
    private lateinit var btnPickTarget: TextView
    private lateinit var audioDesc: TextView
    private lateinit var btnAudio: TextView
    private lateinit var switchAlwaysOn: androidx.appcompat.widget.SwitchCompat
    private lateinit var advBody: View
    private lateinit var advChevron: ImageView
    private lateinit var shellStateText: TextView
    private lateinit var logBody: View
    private lateinit var logChevron: ImageView
    private lateinit var switchLog: androidx.appcompat.widget.SwitchCompat
    private lateinit var logText: TextView
    private lateinit var snackbar: TextView
    private lateinit var appVersionFooter: TextView

    // ---- 英雄卡状态机（1s 轮询 SessionController 对账）----
    private var heroState: HeroState? = null
    private var connectedAt = 0L
    private var hubBlink: ObjectAnimator? = null

    // 启动残留屏检查进行中（英雄卡显示「检查中」）；发现的残留暂缓清理时
    // 记在 pendingResidualDisplays，状态旁的「清理残留屏」按钮可再次发起
    private var startupCheckRunning = false
    private var pendingResidualDisplays: List<Pair<Int, String>>? = null

    // 英雄卡实时帧率：1s 轮询对 VideoSender 已发帧计数取差值
    private var lastSentFrames = 0L

    /** 免责声明倒计时截止时刻（elapsedRealtime；旋转重建后透传，不重新计时）。 */
    private var disclaimerDeadlineAt = 0L

    private var activeDisclaimer: androidx.appcompat.app.AlertDialog? = null

    /** Shizuku 授权结果监听（onDestroy 必须注销，否则跨 Activity 泄漏）。 */
    private var shizukuPermissionListener:
        rikka.shizuku.Shizuku.OnRequestPermissionResultListener? = null

    /** 用户在「请求授权」引导里点了主操作：授权成功后自动继续连接流程。 */
    private var pendingShizukuThenConnect = false

    private val mainHandler = Handler(Looper.getMainLooper())
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

    private val logListener: (String) -> Unit = { line ->
        runOnUiThread {
            if (!isFinishing && !isDestroyed) appendConsole(line)
        }
    }

    private val ssidSink: (String) -> Unit = { ssid ->
        runOnUiThread { ssidInput.setText(ssid) }
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            log("BLUETOOTH_CONNECT granted: $granted")
            if (granted && pendingStartAfterBtPermission.compareAndSet(true, false)) {
                // 授权期间 Shizuku 可能被停掉：这里同样走前置检查
                ensureShizukuReady { showAudioChoiceDialog() }
            }
        }

    /** 蓝牙权限授权后自动继续启动连接。 */
    private val pendingStartAfterBtPermission =
        java.util.concurrent.atomic
            .AtomicBoolean(false)

    /** 车机壁纸选择（Photo Picker；无需存储权限）。选中即持久化并通知合成器换图。 */
    private val wallpaperPicker =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri == null) {
                log("WALLPAPER: no image selected")
                return@registerForActivityResult
            }
            val ok =
                WallpaperStore
                    .setFromUri(this, uri)
            log(
                "WALLPAPER: ${if (ok) "wallpaper updated (live within ~1s if connected; else next connection)" else "save failed"}",
            )
            if (ok) snack("Wallpaper updated") else snack("Save failed")
        }

    private val audioPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            log("MODE: RECORD_AUDIO granted: $granted (needed for audio channel)")
        }

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode != RESULT_OK || data == null) {
                log("MODE: projection permission denied (car audio unavailable)")
                return@registerForActivityResult
            }
            // Android 14+ 顺序：先把结果交给服务，由服务升级 FGS 类型后调 getMediaProjection
            CarPlayService.pendingProjectionResult =
                result.resultCode to data
            CarPlayService
                .start(this)
            log("MODE: ✓ projection permission granted (must re-grant after phone reboot)")
            // 会话已在进行中授权（授权晚于连接）：等投影就绪后动态补启音频通道
            Bg.run("audio-late-start") {
                val deadline = SystemClock.uptimeMillis() + 5_000L
                while (SystemClock.uptimeMillis() < deadline) {
                    if (CarPlayService.mediaProjection != null) break
                    Thread.sleep(200)
                }
                if (CarPlayService.mediaProjection == null) {
                    log("MODE: projection not ready after 5s (service error?), audio channel not started this time")
                    return@run
                }
                SessionController.maybeStartAudioNow(applicationContext)
                // 「启动连接」时选了开启声音、投屏授权在此刻才拿到：接着启动连接
                if (pendingStartAfterProjection.compareAndSet(true, false)) {
                    startConnectionNow()
                }
            }
        }

    /** 投屏授权是「启动连接」的前置（选了开启声音且尚未授权）时置位。 */
    private val pendingStartAfterProjection =
        java.util.concurrent.atomic
            .AtomicBoolean(false)

    /**
     * 「启动连接」前的音频选择弹窗：
     * 音频与视频同时推流会显著增加负载（实测 48k 立体声 PCM ≈1.5Mbps + 视频 ≈4Mbps），
     * 测试台出现画面卡顿与断连；真车链路能力未知。因此默认不推音频，需用户显式开启。
     *
     * 三个选项竖排（横排会挤爆窄屏）：推荐项「仅视频」放第一位并用实心强调
     * ——与正文「以看视频为主时建议暂不开启」一致；取消在最下。
     */
    private fun showAudioChoiceDialog() {
        // 默认档 = 高质量媒体通路（type=102 + AAC-LC，真 iPhone 走这条）；
        // 「兼容档」= 强制只走 PCM 旧通路（type=100），AAC 被车机直接拒掉时的人工退路。
        // 按钮竖排每个约 36dp：横屏屏高只有 ~300dp，再多一个「取消」就会顶出屏幕，
        // 因此横屏省略「取消」（弹窗默认可返回键/点外部关闭）。
        val landscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val actions =
            buildList {
                add(
                    DarkMessageDialog.Action(
                        "Video only (smoother)",
                        DarkMessageDialog.Emphasis.PRIMARY,
                    ) { beginWithAudio(enabled = false) },
                )
                add(DarkMessageDialog.Action("Enable audio") { beginWithAudio(enabled = true) })
                if (!landscape) add(DarkMessageDialog.Action("Cancel") {})
            }
        DarkMessageDialog.show(
            activity = this,
            title = "Play audio on the car?",
            message =
                "When on, phone media/navigation audio plays on the car and the phone is muted (off = no sound in the car).\n" +
                    "Streaming audio and video together adds load; if you mainly watch video, you can leave it off.\n" +
                    "Audio needs the \"screen projection\" permission, which will be requested too.",
            actions = actions,
        )
    }

    /** 「取消连接」确认弹窗（防误触）：中止当前连接尝试并断开蓝牙/WiFi 链路。 */
    private fun promptCancelConnecting() {
        DarkMessageDialog.show(
            activity = this,
            title = "Cancel connection?",
            message = "This stops the current attempt: Bluetooth and Wi-Fi links will drop; tap \"Connect\" again to retry.",
            actions =
                listOf(
                    DarkMessageDialog.Action("Keep connecting") {},
                    DarkMessageDialog.Action("Stop connecting", DarkMessageDialog.Emphasis.DANGER) {
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
     * 启动连接的前置检查：蓝牙权限 → 蓝牙开关 → WLAN 位置权限（仅 Android 12/12L）。
     * 任一不满足都弹窗引导（蓝牙关闭时可一键跳系统蓝牙设置），通过后回调继续。
     */
    private fun ensureBluetoothReady(onReady: () -> Unit) {
        val granted =
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
        if (!granted) {
            pendingStartAfterBtPermission.set(true)
            permissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
            return
        }
        ensureWifiLocationReady {
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
                    title = "Bluetooth is off",
                    message = "Wireless CarPlay needs Bluetooth. Turn it on and make sure the phone is paired with the car.",
                    actions =
                        listOf(
                            DarkMessageDialog.Action("Cancel") {},
                            DarkMessageDialog.Action("Turn on Bluetooth", DarkMessageDialog.Emphasis.PRIMARY) {
                                runCatching {
                                    startActivity(
                                        android.content.Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS),
                                    )
                                }.onFailure { log("BT: can't open Bluetooth settings, please turn it on manually") }
                            },
                        ),
                )
                log("BT: Bluetooth is off - connection not started")
                return@ensureWifiLocationReady
            }
            onReady()
        }
    }

    /**
     * 应用专属网络（WifiNetworkSpecifier）在 Android 12/12L 需要位置权限与位置服务；
     * 13+ 由 NEARBY_WIFI_DEVICES 接管、不再要求定位。缺权限时系统会直接
     * `requestNetwork` 失败（表现为「未能加入车机热点」，很难自查），因此前置拦截。
     */
    private fun ensureWifiLocationReady(onReady: () -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            onReady()
            return
        }
        val granted =
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        if (!granted) {
            pendingStartAfterLocationPermission.set(true)
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
                title = "Location services required",
                message =
                    "On Android 12, joining the car hotspot (app-specific Wi-Fi) requires location services - the system uses it " +
                        "to find connectable Wi-Fi; your location is not recorded.\n\nTurn on location services and retry, or " +
                        "connect to the hotspot manually in Wi-Fi settings.",
                actions =
                    listOf(
                        DarkMessageDialog.Action("Cancel") {},
                        DarkMessageDialog.Action("Turn on", DarkMessageDialog.Emphasis.PRIMARY) {
                            runCatching {
                                startActivity(
                                    android.content.Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS),
                                )
                            }.onFailure { log("WIFI: can't open location settings, please turn it on manually") }
                        },
                    ),
            )
            log("WIFI: location services off - app-specific network unavailable on Android 12, connection not started")
            return
        }
        onReady()
    }

    /** 位置权限授权后自动继续启动连接（仅 Android 12/12L）。 */
    private val pendingStartAfterLocationPermission =
        java.util.concurrent.atomic
            .AtomicBoolean(false)

    private val locationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            log("ACCESS_FINE_LOCATION granted: $granted")
            if (granted && pendingStartAfterLocationPermission.compareAndSet(true, false)) {
                ensureShizukuReady { showAudioChoiceDialog() }
            }
        }

    /**
     * 「断开连接」确认弹窗（防误触）：断开会立即中断车机画面，
     * 且投屏授权随保活服务停止而失效（下次要车机侧声音需重新授权）。
     */
    private fun promptDisconnect() {
        DarkMessageDialog.show(
            activity = this,
            title = "Disconnect?",
            message =
                "This stops streaming and the keep-alive service; the car display will cut out immediately.\n\n" +
                    "Note: the projection permission ends with the service; re-grant it next time if you want car audio.",
            actions =
                listOf(
                    DarkMessageDialog.Action("Cancel") {},
                    DarkMessageDialog.Action("Disconnect", DarkMessageDialog.Emphasis.DANGER) {
                        SessionController.disconnect(this)
                    },
                ),
        )
    }

    /**
     * 「启动连接」的硬性前置：Shizuku 必须就绪。
     *
     * 隐形屏模式的虚拟屏创建、触摸注入、窗口/显示状态读取**全部经 Shizuku shell**；
     * 未就绪时连接会走到「蓝牙已连、车机黑屏 + 触摸全丢」的半残状态，比直接拦住更糟。
     * 未就绪时按三态给出对应的可操作引导（装 / 启动 / 授权）。
     */

    /**
     * 静默补授予通知使用权（幂等；后台线程）。
     *
     * **不能只在 onCreate 试一次**：那次如果 Shizuku 未就绪就会永久跳过，
     * 用户看到的是「音乐在播、卡片却显示媒体信息未连接」——未授权时
     * `getActiveSessions()` 仍返回会话（播放状态正常）但 `metadata` 恒为 null。
     * 因此在每个「Shizuku 确认就绪」的时机都补调一次；已授权时内部直接返回，
     * 不产生额外 shell 往返。
     */
    private fun ensureNotificationAccessAsync() {
        Bg.run("notification-access") {
            if (ShizukuGate.state(this) != ShizukuGate.State.READY) {
                log("MEDIA: Shizuku not ready - can't auto-grant notification access yet (will retry when ready)")
                return@run
            }
            NotificationAccess.ensureGranted(this, ::log)
        }
    }

    private fun ensureShizukuReady(onReady: () -> Unit) {
        val state = ShizukuGate.state(this)
        if (state == ShizukuGate.State.READY) {
            // Shizuku 就绪即可静默补授权（此前若因未就绪而跳过，这里补上）
            ensureNotificationAccessAsync()
            onReady()
            return
        }
        log("SHIZUKU: not ready ($state) - connection blocked")
        promptShizukuNotReady(state)
    }

    /**
     * Shizuku 未就绪引导弹窗：主操作随状态变化，附「重试检查」与「取消」。
     * 走 [DarkMessageDialog] 自绘面板——系统 AlertDialog 是浅色主题，与深色 UI 割裂。
     */
    private fun promptShizukuNotReady(state: ShizukuGate.State) {
        val guidance = ShizukuGate.guidanceOf(state)
        val actions = mutableListOf<DarkMessageDialog.Action>()
        actions += DarkMessageDialog.Action("Cancel") {}
        actions +=
            DarkMessageDialog.Action("Check again") {
                ensureShizukuReady { showAudioChoiceDialog() }
            }
        when (state) {
            ShizukuGate.State.NOT_RUNNING ->
                actions +=
                    DarkMessageDialog.Action("Open Shizuku", DarkMessageDialog.Emphasis.PRIMARY) {
                        val intent = packageManager.getLaunchIntentForPackage(ShizukuGate.PACKAGE_NAME)
                        if (intent == null) {
                            snack("Can't open the Shizuku app - please open it manually")
                        } else {
                            runCatching { startActivity(intent) }
                                .onFailure { snack("Can't open the Shizuku app - please open it manually") }
                        }
                    }

            ShizukuGate.State.UNAUTHORIZED ->
                actions +=
                    DarkMessageDialog.Action("Request access", DarkMessageDialog.Emphasis.PRIMARY) {
                        pendingShizukuThenConnect = true
                        ShizukuGate.requestPermission()
                        snack("Tap \"Allow\" in the Shizuku prompt - connecting continues automatically")
                    }

            // 未安装：没有可执行的主操作（不给编造的安装链接）
            ShizukuGate.State.NOT_INSTALLED, ShizukuGate.State.READY -> Unit
        }
        DarkMessageDialog.show(
            activity = this,
            title = guidance.title,
            message = guidance.message,
            actions = actions,
        )
    }

    private fun beginWithAudio(enabled: Boolean) {
        SessionController.audioEnabledForSession = enabled
        // 声音只剩一个模式：通路协商（AAC-LC 优先，不行自动退 PCM）在会话内自动完成，
        // 不再有「兼容档」这种人工分支。
        SessionController.audioPreferCompat = false
        if (!enabled) {
            log("MODE: video only for this connection (car audio off)")
            startConnectionNow()
            return
        }
        if (CarPlayService.mediaProjection != null) {
            log("MODE: audio enabled (projection permission ready)")
            startConnectionNow()
            return
        }
        // 开启声音需要投屏权限（播放捕获的授权来源）：先申请，授权完成后再启动连接
        log("MODE: audio needs projection permission - requesting it first (audio capture depends on it), will connect once granted")
        pendingStartAfterProjection.set(true)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
        projectionLauncher.launch(manager.createScreenCaptureIntent())
    }

    /** 「启动连接」主体（权限检查留在这里；会话启动交给 [SessionController]，幂等不双开）。 */
    private fun startConnectionNow() {
        runOnUiThread {
            // 兜底守卫：授权回调等延迟路径也可能走到这里，Shizuku 中途停掉时同样要拦住
            val gate = ShizukuGate.state(this)
            if (gate != ShizukuGate.State.READY) {
                log("SHIZUKU: not ready ($gate) - connection blocked")
                promptShizukuNotReady(gate)
                return@runOnUiThread
            }
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
            SessionController.startConnection(this)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        // 旋转/重建：沿用原倒计时截止时刻（onResume 据此续弹）
        disclaimerDeadlineAt = savedInstanceState?.getLong(KEY_DISCLAIMER_DEADLINE, 0L) ?: 0L

        appVersionFooter = findViewById(R.id.appVersionFooter)
        appVersionFooter.text = "CarDeck v${BuildConfig.VERSION_NAME}"
        deviceSubtitle = findViewById(R.id.deviceSubtitle)
        shizukuChip = findViewById(R.id.shizukuChip)
        shizukuDesc = findViewById(R.id.shizukuDesc)
        shizukuBadge = findViewById(R.id.shizukuBadge)
        signalWaves = findViewById(R.id.signalWaves)
        hubPlate = findViewById(R.id.hubPlate)
        hubCar = findViewById(R.id.hubCar)
        hubCaption = findViewById(R.id.hubCaption)
        heroStateText = findViewById(R.id.heroStateText)
        btnResidualCleanup = findViewById(R.id.btnResidualCleanup)
        btnResidualCleanup.setOnClickListener {
            pendingResidualDisplays?.let { promptResidualDisplays(it) }
        }
        heroSubline = findViewById(R.id.heroSubline)
        heroStats = findViewById(R.id.heroStats)
        heroProgress = findViewById(R.id.heroProgress)
        ctaButton = findViewById(R.id.ctaButton)
        ctaIcon = findViewById(R.id.ctaIcon)
        ctaLabel = findViewById(R.id.ctaLabel)
        audioTag = findViewById(R.id.audioTag)
        readyCount = findViewById(R.id.readyCount)
        ssidInput = findViewById(R.id.ssidInput)
        btDesc = findViewById(R.id.btDesc)
        btBadge = findViewById(R.id.btBadge)
        btnPickTarget = findViewById(R.id.btnPickTarget)
        audioDesc = findViewById(R.id.audioDesc)
        btnAudio = findViewById(R.id.btnAudio)
        switchAlwaysOn = findViewById(R.id.switchAlwaysOn)
        advBody = findViewById(R.id.advBody)
        advChevron = findViewById(R.id.advChevron)
        shellStateText = findViewById(R.id.shellStateText)
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
                    Shell.exec(
                        "settings put system screen_off_timeout $backup",
                    )
                }
            }
        }

        // Shizuku 授权结果：状态行与英雄卡副标题立即刷新；若用户是被「请求授权」引导
        // 过来的（pendingShizukuThenConnect），授权成功后直接接着走音频选择，不用再点一次。
        shizukuPermissionListener =
            rikka.shizuku.Shizuku
                .OnRequestPermissionResultListener { requestCode, grantResult ->
                    if (requestCode != ShizukuGate.PERMISSION_REQUEST_CODE) return@OnRequestPermissionResultListener
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        val granted = grantResult == PackageManager.PERMISSION_GRANTED
                        log("SHIZUKU: permission result granted=$granted")
                        updateReadyRow()
                        updateShizukuChip()
                        if (granted) {
                            // Shizuku 刚就绪：补一次通知使用权（改包名/重启后首次授权
                            // 的典型场景——onCreate 那次因未就绪已跳过）
                            ensureNotificationAccessAsync()
                        }
                        if (granted && pendingShizukuThenConnect) {
                            pendingShizukuThenConnect = false
                            ensureShizukuReady { showAudioChoiceDialog() }
                        } else if (!granted) {
                            snack("Shizuku permission not granted - can't connect yet")
                        }
                    }
                }.also {
                    runCatching { rikka.shizuku.Shizuku.addRequestPermissionResultListener(it) }
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
        if (savedInstanceState == null &&
            needsDisclaimerAtLaunch(
                DisclaimerPrefs.acceptedVersion(this),
                DisclaimerPrefs.CURRENT_VERSION,
            )
        ) {
            showDisclaimer(requireAcceptance = true)
        }
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
                        // 若已有会话在跑，先正常断开，避免留下无人管理的推流与虚拟屏。
                        runCatching { SessionController.disconnect(this) }
                        finishAndRemoveTask()
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

        // ---- 免责声明（只读重看）----
        findViewById<LinearLayout>(R.id.btnDisclaimer).setOnClickListener {
            showDisclaimer(requireAcceptance = false)
        }

        // ---- 远程调试入口：仅 remote 调试包存在该组件时显示（正式包 resolve 为 null，恒隐藏；
        // 主代码不引用任何 bridge 类，靠组件探测实现 flavor 差异，调试代码零混入正式产物）----
        val bridgeSetup =
            Intent().setClassName(packageName, "io.github.shaonialife.cardeck.bridge.BridgeSetupActivity")
        if (packageManager.resolveActivity(bridgeSetup, 0) != null) {
            findViewById<LinearLayout>(R.id.btnBridgeSetup).visibility = View.VISIBLE
            findViewById<LinearLayout>(R.id.btnBridgeSetup).setOnClickListener {
                startActivity(bridgeSetup)
            }
        }

        // ---- Shizuku 状态行：未就绪时点它直接看引导（与「启动连接」被拦下同一套文案）----
        findViewById<LinearLayout>(R.id.rowShizuku).setOnClickListener {
            val state = ShizukuGate.state(this)
            if (state == ShizukuGate.State.READY) {
                snack("Shizuku ready - virtual displays and touch injection available")
            } else {
                promptShizukuNotReady(state)
            }
        }

        // ---- 常亮+暗幕 ----
        ScreenDimmer.enabled =
            preferences.getBoolean("alwaysOn", true)
        switchAlwaysOn.isChecked = ScreenDimmer.enabled
        switchAlwaysOn.setOnCheckedChangeListener { _, checked ->
            ScreenDimmer.enabled = checked
            preferences.edit().putBoolean("alwaysOn", checked).apply()
            if (checked) {
                if (!android.provider.Settings.canDrawOverlays(this)) {
                    promptOverlayPermission("\"Stay awake + dim\" enabled.")
                } else {
                    ScreenDimmer
                        .attach(this)
                }
            } else {
                if (VirtualLauncher.appDisplayId >= 0) {
                    // 会话中：仅关闭常亮/暗幕（投屏探针等资源留待会话结束统一释放）
                    ScreenDimmer
                        .disableDim()
                } else {
                    ScreenDimmer
                        .detach()
                }
            }
            log("MODE: stay awake + dim ${if (checked) "on" else "off"}")
        }

        // ---- 英雄卡主操作 ----
        ctaButton.setOnClickListener {
            when (heroState) {
                HeroState.CONNECTED -> promptDisconnect()
                // 前置检查：Shizuku（硬性依赖）→ 蓝牙权限/开关 → 音频选择
                HeroState.IDLE -> ensureShizukuReady { ensureBluetoothReady { showAudioChoiceDialog() } }
                // 连接中可取消：卡在错误网络/等待回连时用户主动脱身（弹窗防误触）
                HeroState.CONNECTING -> promptCancelConnecting()
                null -> {}
            }
        }

        // ---- 连接准备 ----
        findViewById<TextView>(R.id.btnWifi).setOnClickListener {
            val ssid = ssidInput.text.toString().trim()
            // 自动加入不可用时的手动兜底：密码先进剪贴板，系统 WLAN 页粘贴即可
            val psk = SessionController.lastWifiPsk
            if (psk != null) {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("carplay-wifi", psk))
                snack("Hotspot password copied (${psk.length} chars) - just paste it")
                log(
                    "WiFi: hotspot password copied to clipboard; pick \"$ssid\" and paste it; " +
                        "if it says \"no internet access\", choose to stay connected",
                )
            } else {
                snack("No hotspot password yet - it's fetched once Bluetooth connects")
                log("WiFi: no hotspot password yet (needs BT session 0x5703) - opening Wi-Fi settings, pick \"$ssid\" manually")
            }
            try {
                startActivity(android.content.Intent(android.provider.Settings.ACTION_WIFI_SETTINGS))
            } catch (_: Exception) {
                log("WiFi: can't open Wi-Fi settings, go to Settings > Wi-Fi manually")
            }
        }

        // 目标车机选择：配对了多台车机时指定唯一目标（只向它发请求/只接受它连入）
        btnPickTarget.setOnClickListener { showTargetPicker() }

        // 车机侧声音授权（原「申请投屏权限」）：RECORD_AUDIO + MediaProjection 一起请求
        btnAudio.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
            projectionLauncher.launch(manager.createScreenCaptureIntent())
        }

        // ---- 车机显示 ----
        findViewById<LinearLayout>(R.id.rowDim).setOnClickListener {
            if (!ScreenDimmer
                    .isAttached()
            ) {
                snack("Dimmer not ready (enabled automatically once connected)")
                return@setOnClickListener
            }
            ScreenDimmer
                .enterDimNow()
            log("DIMMER: manual fake screen-off (tap screen to restore)")
        }

        findViewById<TextView>(R.id.btnWallpaperPick).setOnClickListener {
            wallpaperPicker.launch(
                androidx.activity.result.PickVisualMediaRequest(
                    androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly,
                ),
            )
        }
        findViewById<TextView>(R.id.btnWallpaperReset).setOnClickListener {
            val ok =
                WallpaperStore
                    .resetToDefault(this)
            log("WALLPAPER: ${if (ok) "restored built-in default (official CarPlay dark wallpaper)" else "restore failed"}")
            snack(if (ok) "Default wallpaper restored" else "Restore failed")
        }

        // ---- 折叠卡：高级 / 日志 ----
        bindExpandable(R.id.advHeader, advBody, advChevron)
        bindExpandable(R.id.logHeader, logBody, logChevron)

        // ---- Shell 全局状态 ----
        findViewById<View>(R.id.btnShellRefresh).setOnClickListener { refreshShellState() }
        findViewById<View>(R.id.btnShellReset).setOnClickListener { resetShellState() }
        refreshShellState(startupCheck = true)

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
        findViewById<ImageView>(R.id.btnClearLog).setOnClickListener {
            // 清除缓冲后必须显式重渲染：日志回调是增量追加语义，clearLog 的通知
            // 无法清 UI（见 SessionController.clearLog 注释，「点了没反应」根因）
            SessionController.clearLog()
            renderConsole(SessionController.logText())
        }
        findViewById<ImageView>(R.id.btnCopyLog).setOnClickListener {
            val text = SessionController.logText()
            if (text.isEmpty()) {
                snack("Log is empty")
                return@setOnClickListener
            }
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("CarDeck log", text))
            snack("Copied ${text.length} chars of log")
        }
        findViewById<ImageView>(R.id.btnExportLog).setOnClickListener { exportLogFile() }
        // 长按导全部历史：默认只导本次启动，历史日志量大时按需手动取
        findViewById<ImageView>(R.id.btnExportLog).setOnLongClickListener {
            exportLogFile(LogExporter.Scope.ALL)
            true
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
        }

        // 通知使用权（媒体会话枚举，音乐卡数据源）：Shizuku 就绪时可静默授予。
        // 幂等可重试——启动时不一定就绪（重启后 Shizuku 未运行、改包名后未授权），
        // 所以 Shizuku 授权成功、连接建立等时机都会再调一次。
        ensureNotificationAccessAsync()

        // ---- 车机设置（手机侧二级菜单：桌面/横屏/超采样/dock/默认地图） ----
        findViewById<LinearLayout>(R.id.btnCarSettings).setOnClickListener {
            startActivity(android.content.Intent(this, PhoneSettingsActivity::class.java))
        }

        // 启动即检查：常亮开 + 无悬浮窗权限 → 主动请求
        if (ScreenDimmer.enabled) {
            if (!android.provider.Settings.canDrawOverlays(this)) {
                promptOverlayPermission("\"Stay awake + dim\" is on.")
            } else {
                // attach 只挂触摸探针、不排自动暗幕定时器：自动暗幕/常亮由会话驱动
                // （ScreenDimmer.setSessionActive），否则未连接时也会 45s 后变黑
                // ——用户实测「会话断了还会启动暗幕」即此（2026-10-19）。
                ScreenDimmer
                    .attach(this)
            }
        }

        TouchRouter.logSink = { line -> log(line) }
        VirtualLauncher.logSink = { line -> log(line) }
        CarInputClient.logSink = { line -> log(line) }

        log("CarDeck Phase 0 ready")
        log("Real car: pair with the car via Bluetooth + join the car hotspot -> tap \"Connect\" (connects to the car automatically)")
        log("Test bench: pair two phones via Bluetooth -> spare runs xcertplay (wireless) -> tap \"Connect\", wait for callback")
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

    private fun tickHero() {
        val s =
            when {
                SessionController.videoSender?.isRunning == true -> HeroState.CONNECTED
                SessionController.isSessionRunning() || SessionController.sessionStarting.get() ->
                    HeroState.CONNECTING
                else -> HeroState.IDLE
            }
        if (s != heroState) {
            if (s == HeroState.CONNECTED) connectedAt = SystemClock.elapsedRealtime()
            heroState = s
            applyHeroState(s)
        }
        // 连接中：副标题跟随阶段提示（英雄卡内即可见，不必划到底部看日志）
        if (s == HeroState.CONNECTING) {
            heroSubline.text = SessionController.phaseHint ?: DEFAULT_CONNECTING_HINT
        }
        // 启动残留屏检查进行中：状态与副标题临时切换为检查提示
        if (s == HeroState.IDLE && startupCheckRunning) {
            heroStateText.text = "Checking"
            heroSubline.text = "Checking for leftover displays..."
            heroProgress.visibility = View.VISIBLE
        }
        // 空闲但 Shizuku 未就绪：副标题直接说明「点了也连不上」，避免用户反复点
        if (s == HeroState.IDLE && !startupCheckRunning) {
            val gate = ShizukuGate.state(this)
            if (gate != ShizukuGate.State.READY) {
                heroSubline.text =
                    "Shizuku not ready (${ShizukuGate.guidanceOf(gate).title}) - " +
                    "can't connect; tap \"Shizuku\" below for help"
            }
        }
        updateConnectedSubline()
        updateHeroStats()
        updateAudioTag()
        updateReadyRow()
        updateShizukuChip()
    }

    /**
     * 英雄卡统计行（仅连接时显示）：运行时分辨率 + 实际/目标帧率。
     * 实际帧率 = 1s 轮询对已发帧计数取差值；新会话计数归零时先对齐基线。
     */
    private fun updateHeroStats() {
        if (heroState != HeroState.CONNECTED) return
        val sent = SessionController.videoSender?.framesSentTotal ?: 0
        if (sent < lastSentFrames) lastSentFrames = sent
        val fps = sent - lastSentFrames
        lastSentFrames = sent
        heroStats.text =
            "Resolution ${SessionController.activeScalePercent}% · " +
            "$fps/${SessionController.activeTargetFps} fps"
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
            if (s == HeroState.IDLE) getColor(R.color.m3_mono_dim) else 0xFF5C8B96.toInt(),
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
                HeroState.IDLE -> "Not connected"
                HeroState.CONNECTING -> "Connecting"
                HeroState.CONNECTED -> "Connected"
            }
        heroProgress.visibility = if (s == HeroState.CONNECTING) View.VISIBLE else View.GONE
        heroStats.visibility = if (s == HeroState.CONNECTED) View.VISIBLE else View.GONE

        when (s) {
            HeroState.IDLE -> {
                val pending = pendingResidualDisplays
                heroSubline.text =
                    if (pending != null) {
                        "Last session ended abnormally; ${pending.size} leftover virtual display(s) to clean up"
                    } else {
                        "After pairing with the car via Bluetooth, tap below to connect wirelessly"
                    }
                styleCta(R.drawable.bg_cta_fill, R.color.m3_on_primary, R.drawable.ic_play, "Connect")
                audioTag.visibility = View.GONE
            }
            HeroState.CONNECTING -> {
                heroSubline.text = SessionController.phaseHint ?: DEFAULT_CONNECTING_HINT
                styleCta(R.drawable.bg_cta_fill, R.color.m3_on_primary, R.drawable.ic_close, "Cancel")
                audioTag.visibility = View.GONE
            }
            HeroState.CONNECTED -> {
                styleCta(R.drawable.bg_cta_outline_err, R.color.m3_error, R.drawable.ic_power, "Disconnect")
                audioTag.visibility = View.VISIBLE
            }
        }

        ctaIcon.rotation = 0f
    }

    /** 英雄卡主操作按钮统一样式（三个状态仅风格/图标/文案不同）。 */
    private fun styleCta(
        bgRes: Int,
        colorRes: Int,
        iconRes: Int,
        label: String,
    ) {
        ctaButton.setBackgroundResource(bgRes)
        ctaLabel.setTextColor(getColor(colorRes))
        ctaIcon.imageTintList =
            android.content.res.ColorStateList
                .valueOf(getColor(colorRes))
        ctaIcon.setImageResource(iconRes)
        ctaLabel.text = label
        ctaButton.isEnabled = true
    }

    private fun updateConnectedSubline() {
        if (heroState != HeroState.CONNECTED) return
        val elapsed = SystemClock.elapsedRealtime() - connectedAt
        val mm = elapsed / 60_000
        val ss = elapsed % 60_000 / 1_000
        val ssid =
            ssidInput.text
                .toString()
                .trim()
                .ifEmpty { "Car hotspot" }
        heroSubline.text = String.format(Locale.US, "Connected · %s · %02d:%02d · Streaming video", ssid, mm, ss)
    }

    private fun updateAudioTag() {
        if (heroState != HeroState.CONNECTED) return
        val on =
            SessionController.audioEnabledForSession &&
                SessionController.audioSender?.isRunning == true
        val icon =
            ContextCompat.getDrawable(
                this,
                if (on) R.drawable.ic_music_small else R.drawable.ic_volume_off_small,
            )!!
        DrawableCompat.setTint(icon.mutate(), getColor(if (on) R.color.m3_ok else R.color.m3_outline))
        audioTag.setCompoundDrawablesWithIntrinsicBounds(icon, null, null, null)
        audioTag.text = if (on) "Car audio · On" else "Car audio · Off"
    }

    /**
     * 连接准备计数：Shizuku / 热点 SSID / 蓝牙配对 / 投屏权限。
     *
     * Shizuku 是**硬性前置**（未就绪时连接会被拦下），所以计入总数并单独渲染状态行。
     */
    private fun updateReadyRow() {
        var ready = 0
        val shizukuState = ShizukuGate.state(this)
        val shizukuReady = shizukuState == ShizukuGate.State.READY
        if (shizukuReady) ready++
        updateShizukuRow(shizukuState)
        if (ssidInput.text
                .toString()
                .trim()
                .isNotEmpty()
        ) {
            ready++
        }
        val btGranted =
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
        var bondedCount = -1
        if (btGranted) {
            try {
                val adapter =
                    (getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager)
                        .adapter
                bondedCount = adapter?.bondedDevices?.size ?: -1
            } catch (_: SecurityException) {
                bondedCount = -1
            }
        }
        if (bondedCount > 0) ready++
        if (CarPlayService.mediaProjection != null) ready++

        btBadge.visibility = if (bondedCount >= 0) View.VISIBLE else View.GONE
        if (bondedCount >= 0) {
            val target = TargetDevicePrefs.get(this)
            if (bondedCount > 0) {
                btBadge.background.mutate().setTint(getColor(R.color.m3_ok_dot))
                btBadge.text = "Ready"
                btDesc.text =
                    if (target != null) {
                        "Target \"${target.name}\" · only this device is contacted"
                    } else if (bondedCount > 1) {
                        "$bondedCount paired · no target set, will try each"
                    } else {
                        "Paired with $bondedCount device(s) · reconnects automatically"
                    }
            } else {
                btBadge.background.mutate().setTint(getColor(R.color.m3_amber))
                btBadge.text = "Not paired"
                btDesc.text = "Not paired yet - tap \"Select\" to scan and pair the car"
            }
            // 目标设备已不在已配对列表（如系统里删了配对）时提示重选
            if (target != null && bondedCount > 0) {
                val stillBonded =
                    try {
                        (getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager)
                            .adapter
                            ?.bondedDevices
                            ?.any { TargetDevicePrefs.matches(target, it.address) } == true
                    } catch (_: SecurityException) {
                        true
                    }
                if (!stillBonded) {
                    btBadge.background.mutate().setTint(getColor(R.color.m3_amber))
                    btBadge.text = "Reselect"
                    btDesc.text = "Target \"${target.name}\" is no longer paired - please select a device again"
                }
            }
            // 弹窗内可扫描并直接配对未配对设备，因此不再只在「已有配对」时显示：
            // 一台都没配对时，这里正是用户的入口。
            btnPickTarget.visibility = View.VISIBLE
        } else {
            btnPickTarget.visibility = View.GONE
        }
        val projectionReady =
            CarPlayService.mediaProjection != null
        if (projectionReady) {
            btnAudio.text = "Granted"
            btnAudio.setTextColor(getColor(R.color.m3_ok))
            audioDesc.text = "Projection permission ready · enable when connecting"
        } else {
            btnAudio.text = "Grant"
            btnAudio.setTextColor(getColor(R.color.m3_primary))
            audioDesc.text = "Needs \"projection\" permission, optional · no car audio without it"
        }
        readyCount.text = "$ready/4"
    }

    /**
     * 「连接准备」里的 Shizuku 状态行：状态即事实源（每次对账时读一次）。
     * 未就绪时点整行直接给引导弹窗——与点「启动连接」被拦下时看到的是同一套文案。
     */
    private fun updateShizukuRow(state: ShizukuGate.State) {
        val ready = state == ShizukuGate.State.READY
        shizukuBadge.background.mutate().setTint(
            getColor(if (ready) R.color.m3_ok_dot else R.color.m3_amber),
        )
        shizukuBadge.setTextColor(getColor(if (ready) R.color.m3_ok else R.color.m3_on_sec_container))
        shizukuBadge.text = if (ready) "Ready" else "Not ready"
        shizukuDesc.text =
            when (state) {
                ShizukuGate.State.READY -> "Shell access ready · virtual displays & touch available"
                ShizukuGate.State.NOT_RUNNING -> "Service not running - tap to see how to start it"
                ShizukuGate.State.UNAUTHORIZED -> "App not authorized - tap to request access"
                ShizukuGate.State.NOT_INSTALLED -> "Shizuku not installed - tap for instructions"
            }
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
            log("BT-SCAN: BLUETOOTH_SCAN granted: $granted")
            if (granted && pendingTargetScan) {
                pendingTargetScan = false
                targetPicker?.let { startTargetScan(it) }
            } else if (!granted) {
                pendingTargetScan = false
                targetPicker?.let { _ ->
                    updatePickerStatus("\"Nearby devices\" denied - can't scan; grant it in Settings, then reopen", warn = true)
                    pickerToast("\"Nearby devices\" permission denied - can't scan for devices")
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
            snack("Bluetooth is off - please turn it on first")
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
                    name = "Auto (no target)",
                    address = "Try each · accept any device",
                    action = if (current == null) "Current" else "Select",
                    highlighted = current == null,
                    iconTint = R.color.m3_outline,
                ) {
                    TargetDevicePrefs.set(this@MainActivity, null)
                    log("TARGET: target cleared - outbound tries each paired device, inbound accepts any device")
                    // 先关弹窗再提示（snackbar 在 Activity 窗口内，会被弹窗盖住）
                    targetPicker?.dismiss()
                    snack("Switched to auto (no target)")
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
                    action = if (isCurrent) "Current" else "Select",
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
            updatePickerStatus("\"Nearby devices\" permission needed to scan...", warn = true)
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
        updatePickerStatus("Scanning for nearby Bluetooth devices...")
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
            updatePickerStatus("Scan failed to start - make sure Bluetooth is on, then tap \"Rescan\"", warn = true)
        } else {
            log("BT-SCAN: scanning for nearby Bluetooth devices")
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
            text = "Still scanning... found ${scannedTargets.size} unpaired device(s)"
            setTextColor(getColor(R.color.m3_mono_dim))
            visibility = View.VISIBLE
        }
        list.addView(
            inflateTargetRow(
                name = name,
                address = "$address · ${rssi}dBm",
                action = "Pair",
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
        if (AdapterMode.looksLikeAdapter(name)) {
            SessionController.listenForAdapterPairing(applicationContext)
        }
        val ok =
            try {
                device.createBond()
            } catch (error: SecurityException) {
                log("BT-PAIR: pairing \"$name\" failed - missing permission: ${error.message}")
                false
            }
        if (ok) {
            log("BT-PAIR: pairing requested with \"$name\" ($address) - confirm in the system dialog")
            pickerNotice("Pairing \"$name\": confirm in the system dialog")
        } else {
            pendingBondAddress = null
            log("BT-PAIR: couldn't start pairing \"$name\" ($address) (already paired or Bluetooth busy?)")
            pickerNotice("Can't start pairing - pair manually in system Bluetooth settings")
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
                log("BT-PAIR: ✓ paired with \"$name\" ($address) - set as target automatically")
                // 先关弹窗再提示：snackbar 在 Activity 窗口内，弹窗未关时会被盖住
                dialog.dismiss()
                setTarget(device, name)
                if (AdapterMode.looksLikeAdapter(name)) {
                    // Wireless CarPlay adapters connect to the phone themselves; dropping their
                    // profiles right after pairing would only get in the way.
                    log("BT-PAIR: \"$name\" looks like a wireless CarPlay adapter - tap \"Connect\" and wait for it to connect")
                    return
                }
                // 「只配对、不连接」：系统/车机在配对完成后常会自动建立 HFP/A2DP
                // 连接，而奥迪实测这种「已连接」状态会让车机拒绝 CarPlay。立即与
                // 1.5s 后各断一次（车机侧重连通常在这两个时间点之间发生）。
                val app = applicationContext
                Bg.run("bt-pair-only") {
                    repeat(2) { attempt ->
                        val dropped = BtProfiles.disconnect(app, device)
                        if (dropped.isNotEmpty()) {
                            log(
                                "BT-PAIR: dropped ${dropped.joinToString("/")} connection auto-created after pairing" +
                                    " (stay paired-only so the car doesn't reject CarPlay)",
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
                log("BT-PAIR: pairing with $address not completed (cancelled or failed)")
                // 弹窗仍开着：用 Toast（浮在弹窗之上）+ 弹窗内状态行，不用被遮挡的 snackbar
                pickerNotice("Pairing not completed - tap \"Pair\" to retry, or pair in system Bluetooth settings")
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
        log(
            "TARGET: target car set to \"$name\" (${device.address})" +
                " - outbound only to this device, inbound rejects others" +
                if (previous != null && previous.address != device.address) {
                    " (previous target \"${previous.name}\")"
                } else {
                    ""
                },
        )
        snack("Target set to \"$name\" - only this device is contacted")
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
                "Unknown device"
            }
        }

    /** 设备营销名（ro.product.marketname），取不到时退回 Build.MODEL。 */
    private fun deviceName(): String =
        try {
            val sp = Class.forName("android.os.SystemProperties")
            val get = sp.getMethod("get", String::class.java)
            (get.invoke(null, "ro.product.marketname") as? String)
                ?.takeIf { it.isNotBlank() } ?: Build.MODEL
        } catch (_: Exception) {
            Build.MODEL
        }

    private fun updateShizukuChip() {
        val ready =
            ShizukuGate
                .state(this) ==
                ShizukuGate.State.READY
        shizukuChip.text = if (ready) "Service online" else "Shizuku not ready"
        shizukuChip.compoundDrawables.getOrNull(0)?.let {
            DrawableCompat.setTint(
                it.mutate(),
                getColor(if (ready) R.color.m3_ok_dot else R.color.m3_amber),
            )
        }
        // 副标题与原型一致：设备名 · Shizuku 状态（点色随状态）
        deviceSubtitle.text = "${deviceName()} · ${if (ready) "Shizuku ready" else "Shizuku not ready"}"
        deviceSubtitle.compoundDrawables.getOrNull(0)?.let {
            DrawableCompat.setTint(
                it.mutate(),
                getColor(if (ready) R.color.m3_ok_dot else R.color.m3_amber),
            )
        }
    }

    // ==================== 通用 UI 工具 ====================

    private fun log(message: String) {
        // 写入进程级缓冲（logcat + 文件 + 挂屏渲染均由 SessionController 统一处理）
        SessionController.appendLog(message)
    }

    /** Shell 查询/执行（仅后台线程调用；两个诊断面板共用，勿再各写局部函数）。 */
    private fun shExec(
        cmd: String,
        timeoutMs: Long = 4_000L,
    ): Shell.Result =
        Shell
            .exec(cmd, timeoutMs)

    private fun sh(cmd: String): String = shExec(cmd).output

    /**
     * 导出日志到「下载/CarDeck/」并弹窗告知位置。
     *
     * 默认只导**本次进程启动之后**的日志（历史轮次对排查是噪音，动辄几百 KB）；
     * 长按导出按钮导全部历史（[LogExporter.Scope.ALL]）。
     * 为什么必须弹窗而不是 snack：snack 只在页面底部浮 2.4s，用户点了导出后
     * 往往正在看日志区、根本注意不到；且失败时也必须有明确反馈（写文件可能因
     * 存储满/媒体库异常失败）。
     */
    private fun exportLogFile(scope: LogExporter.Scope = LogExporter.Scope.CURRENT_SESSION) {
        snack(if (scope == LogExporter.Scope.ALL) "Exporting all logs..." else "Exporting logs since app start...")
        val fallback = SessionController.logText()
        Bg.run("export-log") {
            val result = LogExporter.export(applicationContext, fallback, scope)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (result == null) {
                    snack("Export failed - check storage space and retry", 4_000L)
                    return@runOnUiThread
                }
                log("Log exported (${scope.label}): ${result.displayPath} (${result.bytes} bytes)")
                val scopeNote =
                    if (scope == LogExporter.Scope.CURRENT_SESSION) {
                        "Only includes logs since this app launch. For older history, long-press the export button."
                    } else {
                        "Includes history (file capped at 2MB; beyond that only the most recent part is kept)."
                    }
                DarkMessageDialog.show(
                    this,
                    title = "Log exported",
                    message =
                        "File location:\n${result.displayPath}\n\n" +
                            "Size: ${formatBytes(result.bytes)}\n$scopeNote\n\n" +
                            "Find it in \"Files → Download → CarDeck\", or share it with the developer to troubleshoot.",
                    actions =
                        listOf(
                            DarkMessageDialog.Action("Open file", DarkMessageDialog.Emphasis.PRIMARY) {
                                openExportedFile(result.uri, result.displayPath)
                            },
                            DarkMessageDialog.Action("Open folder") {
                                openExportFolder(result.displayPath)
                            },
                            DarkMessageDialog.Action("Share file") {
                                shareExportedFile(result.uri)
                            },
                            DarkMessageDialog.Action("Close", onClick = {}),
                        ),
                )
            }
        }
    }

    /** 人类可读大小：<1KB 显示字节（整数除法会显示成误导性的「0 KB」）。 */
    private fun formatBytes(bytes: Long): String =
        when {
            bytes < 1024 -> "$bytes bytes"
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
                if (intent !== explicit) snack("Open this path: $folder", 5_000L)
                return
            }
        }
        snack("Look under \"Files → Download → CarDeck\"", 5_000L)
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
        // 无文本查看器时退到「文件管理器」：DocumentsUI 的内部存储根（用户按路径进 下载/CarDeck）
        val storageRoot =
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(
                    Uri.parse("content://com.android.externalstorage.documents/root/primary"),
                    "vnd.android.document/root",
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        if (runCatching { startActivity(storageRoot) }.isSuccess) {
            snack("Open this path: $displayPath", 5_000L)
            return
        }
        snack("No app can open the file - find it in a file manager at the path above", 5_000L)
    }

    /** 分享日志文件（微信/QQ/邮件等），内容 URI 带临时读权限。 */
    private fun shareExportedFile(uri: Uri) {
        val send =
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        runCatching { startActivity(Intent.createChooser(send, "Share log file")) }
            .onFailure { snack("No app available to share") }
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

    private fun renderConsole(text: String) {
        // 改文本前已在底部（或用户上翻阅读中）：决定本次是否自动跟底
        val follow = consoleAtBottom()
        logText.text =
            if (SessionController.logEnabled) text else "Logging is off (turn on \"Capture logs\" to resume)"
        logText.post {
            if (follow) scrollConsoleToBottom() else updateLogBottomButton()
        }
    }

    /**
     * 增量追加一行日志（SessionController 逐行直送；此前每行全量 toString + setText
     * 整段文本，高频日志下 UI/GC 双重放大）。超限时从进程缓冲全量对账一次完成裁剪。
     */
    private fun appendConsole(line: String) {
        val follow = consoleAtBottom()
        if (logText.text.length > 44_000) {
            renderConsole(SessionController.logText())
            return
        }
        logText.append(line)
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
     * 解析 dumpsys display 中的 CarPlay 虚拟屏：返回 (displayId, 名称)。
     * 注意 uniqueId 形如 virtual:com.android.shell,2000,CarPlayDock,36——尾数 36 是
     * 代次计数器而非显示屏 ID；真实 ID 在 "displayId=39, uniqueId='…'" 段里。
     */
    private fun parseCarPlayDisplays(dump: String): List<Pair<Int, String>> =
        Regex("displayId=(\\d+), uniqueId='[^']*CarPlay(Dock|AppHi|App|Map)")
            .findAll(dump)
            .map { it.groupValues[1].toInt() to "CarPlay${it.groupValues[2]}" }
            .distinctBy { it.first }
            .toList()

    /** 读取 shell 全局状态（后台线程 + UI 回填）。[startupCheck] 时顺带做残留虚拟屏检测。 */
    private fun refreshShellState(startupCheck: Boolean = false) {
        if (startupCheck) {
            startupCheckRunning = true
            tickHero()
        }
        shellStateText.text = "Reading..."
        Bg.run("shell-state") {
            if (ShizukuGate
                    .state(this) !=
                ShizukuGate.State.READY
            ) {
                runOnUiThread {
                    startupCheckRunning = false
                    if (heroState == HeroState.IDLE) applyHeroState(HeroState.IDLE)
                    shellStateText.text = "Shizuku not ready - reading shell state requires Shizuku"
                }
                return@run
            }

            val letterbox =
                Regex("Aspect ratio:\\s*([0-9.]+)")
                    .find(sh("cmd window get-letterbox-style"))
                    ?.groupValues
                    ?.get(1) ?: "?"
            val forceResizable = sh("settings get global force_resizable_activities").ifBlank { "null" }
            val overlay = sh("settings get global overlay_display_devices").ifBlank { "null" }
            val wmSize = sh("wm size").replace("\n", " / ").ifBlank { "?" }
            val wmDensity = sh("wm density").replace("\n", " / ").ifBlank { "?" }
            val carDisplays = parseCarPlayDisplays(sh("dumpsys display"))
            val displays = carDisplays.joinToString(", ") { "${it.second}(${it.first})" }
            val timeout = sh("settings get system screen_off_timeout").ifBlank { "?" }
            val text =
                buildString {
                    appendLine("letterbox aspect ratio: $letterbox  (1.05=session tuning active; 0.0=system default)")
                    appendLine("force_resizable: $forceResizable  (tablet-mode experiment; default null/0)")
                    appendLine("overlay_display_devices: $overlay  (overlay-display experiment leftover; should be null)")
                    appendLine("wm size: $wmSize")
                    appendLine("wm density: $wmDensity")
                    appendLine("CarPlay virtual displays: " + displays.trim().replace("\n", ", ").ifEmpty { "none" })
                    appendLine("screen_off_timeout: $timeout  (managed by the app's stay-awake feature)")
                }
            runOnUiThread { shellStateText.text = text.trim() }
            if (startupCheck) {
                var residual = carDisplays.filter { it.first !in currentSessionDisplayIds() }
                if (residual.isNotEmpty()) {
                    log(
                        "SHELL: found ${residual.size} leftover virtual display(s) at startup: " +
                            residual.joinToString(", ") { "${it.second}(${it.first})" },
                    )
                    // 残留屏 = 上次会话异常退出（进程被杀）：横屏覆盖的会话末复原
                    // 也没执行——一并清理，避免应用在手机侧残留横屏状态
                    runCatching {
                        val reset =
                            AppOrientationCompat
                                .resetAll(applicationContext)
                        if (reset > 0) log("ORIENTATION: restored $reset landscape override(s) from leftover session")
                    }
                    // 先自动清理一轮（含旧版本 UserService 进程回收——其持有的屏跨进程
                    // 无法 release，只有进程回收能释放；重装 APK 后 Shizuku 按版本复用的
                    // 旧进程正是「点清理释放 0/N」的根因，2026-10-16）。清不掉的再问用户。
                    val released = releaseDisplays(residual.map { it.first })
                    log("SHELL: startup leftover auto-cleanup (released $released/${residual.size})")
                    if (released > 0) {
                        residual =
                            parseCarPlayDisplays(sh("dumpsys display"))
                                .filter { it.first !in currentSessionDisplayIds() }
                    }
                }
                runOnUiThread {
                    startupCheckRunning = false
                    if (residual.isNotEmpty()) {
                        // 用户「暂不」时保留 pending，状态旁的按钮可再次发起清理
                        pendingResidualDisplays = residual
                        updateResidualChip()
                        if (heroState == HeroState.IDLE) applyHeroState(HeroState.IDLE)
                        promptResidualDisplays(residual)
                        // 自动展开日志卡：启动清理的两条记录直接可见（卡片默认折叠）
                        findViewById<View>(R.id.logBody).visibility = View.VISIBLE
                        findViewById<ImageView>(R.id.logChevron)
                            .animate()
                            .rotation(180f)
                            .setDuration(250L)
                            .start()
                    } else if (heroState == HeroState.IDLE) {
                        // 无残留：恢复「未连接」默认状态展示
                        applyHeroState(HeroState.IDLE)
                    }
                }
            }

            // 预绑定 UserService：预热触摸注入，并触发旧版本残留进程回收（绑定回调内含
            // reapStaleUserServices——重装 APK 后 Shizuku 不回收的旧进程，其持有的虚拟屏
            // 只有进程回收能释放）。**必须晚于上方残留扫描**：回收会让旧进程的虚拟屏立即
            // 消失，若先于扫描发生，残留清单可能变空 → resetAll（强制横屏复原）被跳过。
            CarInputClient.ensure(this)
        }
    }

    /** 当前会话持有的虚拟屏（不参与残留清理）。 */
    private fun currentSessionDisplayIds(): Set<Int> =
        buildSet {
            val launcher = VirtualLauncher
            listOf(
                launcher.dockDisplayId,
                launcher.appDisplayId,
                launcher.mapDisplayId,
                launcher.gridDisplayId,
            ).forEach { if (it >= 0) add(it) }
        }

    /** 启动检测到残留 CarPlay 虚拟屏：询问是否立即清理（上次会话异常退出遗留）。 */
    private fun promptResidualDisplays(residual: List<Pair<Int, String>>) {
        if (isFinishing || isDestroyed) return
        val names = residual.joinToString("\n") { "· ${it.second} (displayId=${it.first})" }
        DarkMessageDialog.show(
            activity = this,
            title = "Leftover virtual displays found",
            message =
                "Found ${residual.size} CarPlay virtual display(s) left over from the last session:\n$names\n\n" +
                    "Leftover displays keep using display resources and may affect the next connection. Clean up now?",
            actions =
                listOf(
                    DarkMessageDialog.Action("Not now") {},
                    DarkMessageDialog.Action("Clean up", DarkMessageDialog.Emphasis.PRIMARY) {
                        pendingResidualDisplays = null
                        updateResidualChip()
                        if (heroState == HeroState.IDLE) applyHeroState(HeroState.IDLE)
                        Bg.run("residual-cleanup") {
                            val released = releaseDisplays(residual.map { it.first })
                            log("SHELL: leftover virtual display cleanup done (released $released/${residual.size})")
                            runOnUiThread {
                                snack("Released $released/${residual.size} leftover displays")
                                refreshShellState()
                            }
                        }
                    },
                ),
            onCancel = {
                // 返回键/点外部关闭 = 暂不：保留英雄卡的「清理残留屏」入口
                updateResidualChip()
            },
        )
    }

    /** 英雄卡状态旁的「清理残留屏」按钮显隐（有待清理残留时出现）。 */
    private fun updateResidualChip() {
        btnResidualCleanup.visibility =
            if (pendingResidualDisplays != null) View.VISIBLE else View.GONE
    }

    /** 释放指定虚拟屏（CarInputClient 懒绑定 + 3 秒短等）。后台线程调用，返回成功数。 */
    private fun releaseDisplays(ids: List<Int>): Int {
        if (ids.isEmpty()) return 0
        var svc = CarInputClient.service
        if (svc == null) {
            CarInputClient
                .ensure(this)
            val deadline = SystemClock.uptimeMillis() + 3_000L
            while (svc == null && SystemClock.uptimeMillis() < deadline) {
                Thread.sleep(150)
                svc = CarInputClient.service
            }
        }
        val bound = svc ?: return 0
        ids.forEach { runCatching { bound.releaseDisplay(it) } }
        // 未直接释放的屏极可能由旧版本 UserService 进程持有（releaseDisplay 只查本进程
        // map，跨进程无法释放——重装 APK 后 Shizuku 不回收旧进程的已知问题）。
        // 回收旧进程让其随进程销毁释放，再以实际扫描结果复核（进程回收是异步的）。
        CarInputClient.reapStaleUserServices()
        val deadline = SystemClock.uptimeMillis() + 2_000L
        while (true) {
            val alive = parseCarPlayDisplays(sh("dumpsys display")).map { it.first }.toSet()
            val gone = ids.count { it !in alive }
            if (gone >= ids.size || SystemClock.uptimeMillis() >= deadline) return gone
            Thread.sleep(200)
        }
    }

    /** 一键把 shell 全局状态复位为系统默认值（含残留虚拟屏释放，保留当前会话屏）。 */
    private fun resetShellState() {
        shellStateText.text = "Resetting..."
        Bg.run("shell-reset") {
            if (ShizukuGate
                    .state(this) !=
                ShizukuGate.State.READY
            ) {
                runOnUiThread { shellStateText.text = "Shizuku not ready - shell reset requires Shizuku" }
                return@run
            }

            fun sh(cmd: String) =
                Shell
                    .exec(cmd, 5_000L)
            val steps =
                listOf(
                    "letterbox aspect ratio" to "cmd window reset-letterbox-style aspectRatio",
                    "overlay display" to "settings delete global overlay_display_devices",
                    "force_resizable" to "settings delete global force_resizable_activities",
                    "wm size" to "wm size reset",
                    "wm density" to "wm density reset",
                )
            val results =
                steps.map { (name, cmd) ->
                    val r = sh(cmd)
                    if (r.ok) "✓ $name" else "✗ $name (${r.output.take(40)})"
                }
            // 残留虚拟屏释放：保留当前会话的各屏，其余 CarPlay* 屏逐一释放
            val ids =
                parseCarPlayDisplays(sh("dumpsys display").output)
                    .map { it.first }
                    .filter { it !in currentSessionDisplayIds() }
                    .toList()
            val released = releaseDisplays(ids)
            val summary = results.joinToString("; ") + "; leftover displays released $released/${ids.size}"
            log("SHELL-RESET: $summary")
            runOnUiThread {
                shellStateText.text = "Reset results:\n" + results.joinToString("\n") +
                    "\nLeftover virtual displays: released $released/${ids.size} (current session displays kept)\n" +
                    "Tip: letterbox tuning is re-applied automatically on next connection"
                refreshShellState()
            }
        }
    }

    /** 悬浮窗权限请求：先弹说明弹窗，用户确认后再跳系统设置。 */
    private fun promptOverlayPermission(reason: String) {
        if (android.provider.Settings.canDrawOverlays(this)) return
        DarkMessageDialog.show(
            activity = this,
            title = "\"Display over other apps\" permission needed",
            message =
                "$reason\n\n" +
                    "Used to turn the screen off when idle (panel off but not locked, so car touch keeps working). " +
                    "It doesn't read or block other apps' content; tap the screen to restore.",
            actions =
                listOf(
                    DarkMessageDialog.Action("Not now") {
                        log("DIMMER: overlay permission not granted yet (dimmer unavailable, stay-awake still active)")
                    },
                    DarkMessageDialog.Action("Grant", DarkMessageDialog.Emphasis.PRIMARY) {
                        try {
                            startActivity(
                                android.content.Intent(
                                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    android.net.Uri.parse("package:$packageName"),
                                ),
                            )
                        } catch (_: Exception) {
                            log("DIMMER: can't open permission page; go to Settings > Apps > Special access > Display over other apps")
                        }
                    },
                ),
        )
    }

    override fun onResume() {
        super.onResume()
        mainHandler.post(heroTick)
        // 旋转/重建后尚未接受：用原截止时刻续弹（不重新等 10 秒）
        if (needsDisclaimerAtLaunch(
                DisclaimerPrefs.acceptedVersion(this),
                DisclaimerPrefs.CURRENT_VERSION,
            ) &&
            activeDisclaimer?.isShowing != true
        ) {
            showDisclaimer(requireAcceptance = true)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong(KEY_DISCLAIMER_DEADLINE, disclaimerDeadlineAt)
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
        mainHandler.removeCallbacks(heroTick)
    }

    override fun onDestroy() {
        super.onDestroy()
        // 强制声明不得随 Activity 销毁残留窗口（旋转时 Activity 重建，onResume 会续弹）
        runCatching { activeDisclaimer?.dismiss() }
        activeDisclaimer = null
        shizukuPermissionListener?.let {
            runCatching { rikka.shizuku.Shizuku.removeRequestPermissionResultListener(it) }
        }
        shizukuPermissionListener = null
        pendingShizukuThenConnect = false
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
