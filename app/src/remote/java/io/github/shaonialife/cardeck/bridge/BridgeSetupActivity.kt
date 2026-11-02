package io.github.shaonialife.cardeck.bridge

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import io.github.shaonialife.cardeck.ui.DarkMessageDialog
import io.github.shaonialife.cardeck.util.Bg
import java.io.File
import java.lang.ref.WeakReference

/**
 * 远程调试设置页（remote 变体的第二个桌面入口）：
 * 填服务器地址与配对码 → 连接；展示连接状态；引导悬浮窗/安装权限。
 *
 * 兼任调试弹窗宿主：APK 安装确认、重装后续接询问等**必须用正规弹窗**
 * （DarkMessageDialog）——悬浮窗易被忽略（用户实测反馈）。App 后台时由
 * [ReinstallFlow]/[BridgeApp] 带参拉起本页（EXTRA_PROMPT），onCreate/onNewIntent 弹。
 */
class BridgeSetupActivity : AppCompatActivity() {
    private val refresh = Handler(Looper.getMainLooper())
    private val ticker =
        object : Runnable {
            override fun run() {
                // statusLine()：退避重连期间带「Ns 后自动重试」实时倒计时
                statusText?.text = "Status: ${BridgeClient.statusLine()}"
                refresh.postDelayed(this, 1_000)
            }
        }
    private var statusText: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "CarDeck Remote Debug"

        val pad = (16 * resources.displayMetrics.density).toInt()
        val root =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(pad, pad, pad, pad)
            }
        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)

        fun label(text: String): TextView =
            TextView(this).apply {
                this.text = text
                setTextColor(0xFFE0E0E5.toInt())
                textSize = 13f
            }

        val urlInput =
            EditText(this).apply {
                hint = "wss://example.trycloudflare.com  or  ws://192.168.1.2:8787"
                setText(BridgePrefs.serverUrl(this@BridgeSetupActivity))
                imeOptions = EditorInfo.IME_ACTION_NEXT
                setSingleLine()
                // 主题默认字色是黑，深色窗口上不可见（用户实测），必须显式给亮色
                setTextColor(0xFFE0E0E5.toInt())
                setHintTextColor(0xFF8A9099.toInt())
            }
        val codeInput =
            EditText(this).apply {
                hint = "6-digit pairing code (shown in server console)"
                setText(BridgePrefs.pairCode(this@BridgeSetupActivity))
                setSingleLine()
                setTextColor(0xFFE0E0E5.toInt())
                setHintTextColor(0xFF8A9099.toInt())
            }
        val connect =
            Button(this).apply {
                text = "Connect"
                setOnClickListener {
                    BridgePrefs.save(
                        this@BridgeSetupActivity,
                        urlInput.text.toString(),
                        codeInput.text.toString(),
                    )
                    BridgePrefs.setAutoConnect(this@BridgeSetupActivity, true)
                    // 悬浮窗权限顺手引导（提示窗依赖）
                    if (!Settings.canDrawOverlays(this@BridgeSetupActivity)) {
                        runCatching {
                            startActivity(
                                Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    android.net.Uri.parse("package:$packageName"),
                                ),
                            )
                        }
                    }
                    Bg.run("bridge-start") { BridgeClient.start(this@BridgeSetupActivity) }
                }
            }
        val disconnect =
            Button(this).apply {
                text = "Disconnect"
                setOnClickListener {
                    BridgePrefs.setAutoConnect(this@BridgeSetupActivity, false)
                    BridgeClient.stop()
                }
            }
        val openMain =
            Button(this).apply {
                text = "Open main UI"
                setOnClickListener {
                    runCatching {
                        startActivity(
                            Intent().setClassName(
                                packageName,
                                "io.github.shaonialife.cardeck.MainActivity",
                            ),
                        )
                    }
                }
            }
        val installPerm =
            Button(this).apply {
                text = "Grant \"Install unknown apps\" permission"
                visibility =
                    if (packageManager.canRequestPackageInstalls()) View.GONE else View.VISIBLE
                setOnClickListener {
                    runCatching {
                        startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                android.net.Uri.parse("package:$packageName"),
                            ),
                        )
                    }
                }
            }
        statusText =
            TextView(this).apply {
                text = "Status: ${BridgeClient.statusLine()}"
                setTextColor(0xFF3DD6C4.toInt())
                textSize = 14f
            }

        val note =
            TextView(this).apply {
                text =
                    "Notes:\n" +
                    "· This app is independent of the regular build (same name without the \"Remote Debug\" suffix); " +
                    "grant Shizuku and notification access to each separately\n" +
                    "· Once connected, this phone continuously streams runtime logs (incl. hotspot name, Bluetooth address, etc.) " +
                    "to the debug server, for troubleshooting only\n" +
                    "· The debug server can only run whitelisted commands (connect/disconnect, query status)"
                setTextColor(0xFFE0E0E5.toInt())
                textSize = 13f
                setLineSpacing(0f, 1.3f)
            }

        listOf(
            label("Debug server address"),
            urlInput,
            label("Pairing code"),
            codeInput,
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.START
                addView(connect)
                addView(disconnect)
                addView(openMain)
            },
            installPerm,
            statusText!!,
            label(""),
            note,
        ).forEach { view -> root.addView(view) }

        handlePromptIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handlePromptIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        resumedRef = WeakReference(this)
        refresh.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        if (resumedRef?.get() === this) resumedRef = null
        refresh.removeCallbacks(ticker)
    }

    /** 带参拉起时弹对应的正规弹窗（App 后台时安装确认/重装续接走这条路）。 */
    private fun handlePromptIntent(intent: Intent?) {
        when (intent?.getStringExtra(EXTRA_PROMPT)) {
            "install" -> {
                val path = intent.getStringExtra("apkPath") ?: return
                val version = intent.getStringExtra("version") ?: "?"
                promptInstall(File(path), version, intent.getStringExtra("deltaNote"))
            }
            "resume" -> promptResume()
            "confirm" -> {
                // 安装确认的前台转发：系统确认框从后台 Receiver 直接拉起会被
                // 荣耀静默拦截（无异常无界面），由前台 Activity 启动必然生效
                val confirm = ReinstallFlow.pendingConfirmIntent
                if (confirm == null) {
                    android.widget.Toast
                        .makeText(this, "Install confirmation expired - please push again", android.widget.Toast.LENGTH_LONG)
                        .show()
                } else {
                    ReinstallFlow.pendingConfirmIntent = null
                    runCatching { startActivity(confirm) }
                        .onFailure {
                            android.widget.Toast
                                .makeText(this, "Failed to open install confirmation: ${it.message}", android.widget.Toast.LENGTH_LONG)
                                .show()
                        }
                }
            }
        }
    }

    /** 新版本安装确认（正规弹窗；悬浮窗形式已被用户反馈否决）。 */
    fun promptInstall(
        file: File,
        version: String,
        deltaNote: String? = null,
    ) {
        if (!file.exists()) return
        val sizeText = deltaNote ?: "Full ${file.length() / 1024 / 1024}MB"
        DarkMessageDialog.show(
            this,
            title = "Install new debug build",
            message =
                "v$version ($sizeText) downloaded and verified.\n" +
                    "Installing will disconnect the head unit; afterwards you'll be asked whether to resume debugging.",
            actions =
                listOf(
                    DarkMessageDialog.Action("Cancel") {},
                    DarkMessageDialog.Action(
                        "Install",
                        emphasis = DarkMessageDialog.Emphasis.PRIMARY,
                    ) {
                        ReinstallFlow.installNow(this, file)
                    },
                ),
        )
    }

    /** 重装后的续接询问。 */
    fun promptResume() {
        DarkMessageDialog.show(
            this,
            title = "Resume debug mode?",
            message = "Debug build updated. Continue to reconnect to the debug server automatically.",
            actions =
                listOf(
                    DarkMessageDialog.Action("Not now") {
                        BridgePrefs.setResumePending(this@BridgeSetupActivity, false)
                    },
                    DarkMessageDialog.Action(
                        "Continue",
                        emphasis = DarkMessageDialog.Emphasis.PRIMARY,
                    ) {
                        BridgePrefs.setResumePending(this@BridgeSetupActivity, false)
                        Bg.run("bridge-start") { BridgeClient.start(this@BridgeSetupActivity) }
                    },
                ),
        )
    }

    companion object {
        private const val EXTRA_PROMPT = "prompt"

        /** 当前已 resume 的实例（弱引用防泄漏）：调试弹窗需要有前台的 Activity 宿主。 */
        @Volatile
        private var resumedRef: WeakReference<BridgeSetupActivity>? = null

        fun resumed(): BridgeSetupActivity? = resumedRef?.get()

        /** 安装确认前台转发：把设置页带到前台，由它启动系统确认界面。 */
        fun startForConfirm(context: android.content.Context) {
            runCatching {
                context.startActivity(
                    Intent()
                        .setClassName(context.packageName, ReinstallFlow.SETUP_ACTIVITY)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        .putExtra(EXTRA_PROMPT, "confirm"),
                )
            }
        }

        /** 供 App 后台场景带参拉起本页并弹续接询问。 */
        fun startForResume(context: android.content.Context) {
            runCatching {
                context.startActivity(
                    Intent()
                        .setClassName(context.packageName, ReinstallFlow.SETUP_ACTIVITY)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        .putExtra(EXTRA_PROMPT, "resume"),
                )
            }
        }
    }
}
