package io.github.shaonialife.cardeck.bridge

import android.content.Context
import android.content.Intent
import io.github.shaonialife.cardeck.SessionController
import org.json.JSONObject

/**
 * 调试命令白名单（**绝不放开任意 shell/反射**——远程通道被劫持时的爆炸半径
 * 限定在这几个动作）。全部走既有公开 API，遵守「UI 线程禁 shell」约定。
 *
 * 远程发起/断开连接时必须「悬浮窗提示 + 自动跳主界面」：用户此刻多半停在
 * 调试设置页（地址输入页），不跳走就看不到连接进度（2026-10-21 用户实测反馈）。
 */
object BridgeCommands {
    fun handle(
        context: Context?,
        name: String,
        args: JSONObject,
    ): JSONObject {
        val ctx =
            context?.applicationContext
                ?: return fail("App context not ready")
        return when (name) {
            "ping" -> JSONObject().put("ok", true).put("detail", "pong ${System.currentTimeMillis()}")

            "get_state" ->
                JSONObject()
                    .put("ok", true)
                    .put(
                        "detail",
                        SessionController.statusLine() +
                            " session=${if (SessionController.isSessionRunning()) "running" else "stopped"}",
                    )

            "start_connection" -> {
                // 与手机上点「启动连接」等价：前置（Shizuku/蓝牙）不满足时日志会说明
                SessionController.ensure(ctx)
                SessionController.startConnection(ctx)
                BridgeOverlay.show(ctx, "Developer started a connection - opening main UI, see hero card for progress")
                openMainUi(ctx)
                JSONObject().put("ok", true).put("detail", "Connection started (switched to main UI)")
            }

            "stop_connection" -> {
                SessionController.disconnect(ctx)
                BridgeOverlay.show(ctx, "Developer disconnected the head unit")
                JSONObject().put("ok", true).put("detail", "Disconnected")
            }

            else -> fail("Unknown command: $name")
        }
    }

    /** 把主界面带到前台（用户可能在调试设置页，看不到连接进度）。
     *  后台启动 Activity 的限制对本应用豁免：悬浮窗权限（SYSTEM_ALERT_WINDOW）
     *  持有者不受后台启动限制，而调试包必然申请该权限。 */
    private fun openMainUi(ctx: Context) {
        val intent =
            Intent()
                .setClassName(ctx.packageName, "io.github.shaonialife.cardeck.MainActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        runCatching { ctx.startActivity(intent) }
    }

    private fun fail(detail: String): JSONObject = JSONObject().put("ok", false).put("detail", detail)
}
