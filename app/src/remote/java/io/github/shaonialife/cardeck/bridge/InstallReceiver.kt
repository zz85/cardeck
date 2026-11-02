package io.github.shaonialife.cardeck.bridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import io.github.shaonialife.cardeck.util.DebugFileLog

/**
 * PackageInstaller 结果接收：STATUS_PENDING_USER_ACTION 时把系统确认 Intent
 * 拉到手机主屏（此时会话已停、虚拟屏已释放，弹窗不会再落到车机侧）。
 */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Integer.MIN_VALUE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        val sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        // 只认本次提交的会话：遗留会话的迟到广播会把确认 Intent 串成旧包
        if (ReinstallFlow.currentInstallSessionId != -1 && sessionId != ReinstallFlow.currentInstallSessionId) {
            DebugFileLog.append(
                context,
                "BRIDGE: ignoring broadcast for other session (session=$sessionId current=${ReinstallFlow.currentInstallSessionId})",
            )
            return
        }
        DebugFileLog.append(context, "BRIDGE: install status status=$status msg=$message")
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm =
                    intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                DebugFileLog.append(context, "BRIDGE: user confirmation required → ${confirm.component}")
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ReinstallFlow.pendingConfirmIntent = confirm
                // 只走前台转发单一路径：Receiver 直启 + 前台转发会双启动同一确认
                // Intent，荣耀安装器被打进怪状态（2026-10-24 实拍「旧版成功页」）。
                // 悬浮窗权限（后台启动豁免）已由用户开启，前台转发必然可达。
                BridgeSetupActivity.startForConfirm(context)
            }

            PackageInstaller.STATUS_SUCCESS -> {
                DebugFileLog.append(context, "BRIDGE: install succeeded")
                // 安装完成后拉起设置页弹「继续调试」询问，闭环不断在人手上
                BridgeSetupActivity.startForResume(context)
            }

            else ->
                DebugFileLog.append(
                    context,
                    "BRIDGE: install not completed status=$status msg=$message",
                )
        }
    }
}
