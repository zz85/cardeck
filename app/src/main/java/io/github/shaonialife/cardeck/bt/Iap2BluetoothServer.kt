package io.github.shaonialife.cardeck.bt

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import io.github.shaonialife.cardeck.iap2.Iap2SenderSession
import io.github.shaonialife.cardeck.iap2.ReceiverProfile
import io.github.shaonialife.cardeck.transport.Iap2LinkChannel
import io.github.shaonialife.cardeck.transport.StreamDuplexStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * 手机侧（iPhone 角色）的 iAP2 蓝牙 RFCOMM 监听。
 *
 * 真实无线 CarPlay 里 iPhone 配对后监听这个 SDP 服务等待车机回连；
 * 测试台上 DiPlay/xcertplay 会扫描已配对的 PHONE_SMART 设备并主动连入。
 *
 * 连入后：链路协商（marker/同步/ACK）→ 完整发送端 bring-up（Phase 1），
 * 成功时回调 [onEndpoint] 交出热点 SSID/密码与 AirPlay 端点。
 */
class Iap2BluetoothServer(
    private val context: Context,
    private val log: (String) -> Unit,
    private val onEndpoint: (Iap2SenderSession.CarPlayEndpoint) -> Unit = {},
    /** 0x5703 一到即触发（绑 WiFi + 广播 _carplay-ctrl），真车 0x4301 的前置条件。 */
    private val onWifiCredentials: (Iap2SenderSession.WifiConfiguration) -> Unit = {},
    /** Phase 1 之后车机补发 0x4301（CarPlay 窗口晚开）时交给主控热更新端点。 */
    private val onEndpointUpdate: (Iap2SenderSession.CarPlayEndpoint) -> Unit = {},
    /** 发送端链路断开（保活泵退出）时通知主控（释放僵尸轮次，见 SessionController.onSenderLinkClosed）。 */
    private val onLinkClosed: () -> Unit = {},
    /** 本进程已知的车机热点 SSID（本轮无新凭据时沿用，避免每轮 8 秒拆链）。 */
    private val knownCarSsid: () -> String? = { null },
    /** 0x1D01 画像判定的链路代际（Phase 1 完成后回调，AirPlay 阶段分派用）。 */
    private val onGeneration: (ReceiverProfile.LinkGeneration) -> Unit = {},
    /** 目标车机（动态读取：拒绝非目标设备的连入；null=来者不拒）。 */
    private val targetProvider: () -> TargetDevicePrefs.Target? = { null },
) {
    companion object {
        /**
         * iPhone 的 iAP2 服务 UUID（Oligo Security 公布；与 xcertplay
         * CarPlayController.IAP2_IPHONE_UUID 一致）。配件端是 …decacaff。
         */
        val IPHONE_IAP2_UUID: UUID = UUID.fromString("00000000-deca-fade-deca-deafdecacafe")
        private const val SDP_NAME = "iAP2"

        /** Service name the reference sender / a real iPhone publish for the smartBox. */
        private const val ADAPTER_SDP_NAME = "Wireless iAP"
        private const val SESSION_TIMEOUT_MILLIS = 60_000L
        private val CHANNEL_REGEX = Regex("Channel:\\s*(-?\\d+)")
    }

    private var serverSocket: AtomicReference<BluetoothServerSocket?> = AtomicReference(null)

    /** SDP service name of the current listener. */
    @Volatile
    private var publishedName: String? = null

    /**
     * Set once an adapter session has handed over Wi-Fi credentials. The smartBox re-dials RFCOMM
     * afterwards and repeats detect markers; the reference sender never answered them (CarPlay ran
     * fine), so later connections are held open silently instead of starting a second bootstrap.
     * Cleared by [stop] and [resetAdapterHandoff].
     */
    @Volatile
    private var adapterHandedOff = false

    @Volatile
    private var activeChannel: Iap2LinkChannel? = null

    @Volatile
    private var running = false

    val isRunning: Boolean get() = running

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    fun start(): Boolean {
        val wantedName = if (AdapterMode.active) ADAPTER_SDP_NAME else SDP_NAME
        if (running) {
            if (wantedName == publishedName || activeChannel != null) return true
            // Adapter mode was decided after the record went out under the other name
            log("ADAPTER: re-publishing the phone iAP2 service as \"$wantedName\" (was \"$publishedName\")")
            running = false
            serverSocket.getAndSet(null)?.let { runCatching { it.close() } }
        }
        if (!hasPermission()) {
            log("BT: missing BLUETOOTH_CONNECT permission")
            return false
        }
        val adapter =
            try {
                context.getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter
            } catch (_: Exception) {
                null
            }
        if (adapter == null || !adapter.isEnabled) {
            log("BT: Bluetooth unavailable or off")
            return false
        }
        return try {
            val listener = adapter.listenUsingRfcommWithServiceRecord(wantedName, IPHONE_IAP2_UUID)
            serverSocket.set(listener)
            publishedName = wantedName
            running = true
            // toString() 形如「ServerSocket: Type: TYPE_RFCOMM Channel: N」：参考实现在
            // smartBox 上用的是信道 4，信道号对排查「车机不回连」有用。
            log("BT: listening on $wantedName ($listener), waiting for receiver to connect back...")
            if (AdapterMode.active) {
                // Android picks the RFCOMM channel. Forcing 4 (the reference's) would need an unbound
                // BluetoothServerSocket(type, auth, encrypt, ParcelUuid) or BluetoothSocket.bindListen(),
                // both blocked non-SDK APIs on Android 14; the box reads the channel from SDP anyway.
                val channel = CHANNEL_REGEX.find(listener.toString())?.groupValues?.get(1) ?: "?"
                log(
                    "ADAPTER: published \"$wantedName\" (...decacafe) on RFCOMM channel $channel " +
                        "(system-assigned; the reference used 4, forcing it is not possible without blocked hidden APIs)",
                )
            }
            Thread({ acceptLoop(listener) }, "iap2-bt-accept").apply {
                isDaemon = true
                start()
            }
            true
        } catch (error: IOException) {
            log("BT: listen failed - ${error.message}")
            false
        }
    }

    @SuppressLint("MissingPermission")
    private fun acceptLoop(listener: BluetoothServerSocket) {
        while (running && serverSocket.get() === listener) {
            val socket: BluetoothSocket =
                try {
                    listener.accept() ?: break
                } catch (error: IOException) {
                    if (running && serverSocket.get() === listener) log("BT: accept failed - ${error.message}")
                    break
                }
            val peerName =
                try {
                    socket.remoteDevice?.name
                } catch (_: SecurityException) {
                    null
                } ?: "unknown"
            // 指定了目标车机时拒绝其它设备的连入（用户配对了多台车机：非目标车机
            // 的控制器也会周期回连，放行会抢占/误建会话）。地址比对，大小写不敏感。
            val target = targetProvider()
            val peerAddress =
                try {
                    socket.remoteDevice?.address
                } catch (_: SecurityException) {
                    null
                }
            if (target != null && !TargetDevicePrefs.matches(target, peerAddress)) {
                log(
                    "BT: ignoring incoming non-target device name=$peerName addr=${peerAddress ?: "?"}" +
                        " (current target: \"${target.name}\")",
                )
                runCatching { socket.close() }
                continue
            }
            if (AdapterMode.active && adapterHandedOff) {
                log(
                    "ADAPTER: adapter re-dialed RFCOMM after the Wi-Fi handoff (name=$peerName) - holding it open without " +
                        "answering its markers (reference behaviour; no second bootstrap)",
                )
                Thread({ holdSilently(socket) }, "iap2-bt-hold").apply {
                    isDaemon = true
                    start()
                }
                continue
            }
            log("BT: receiver connected name=$peerName, starting iAP2 sender session")
            Thread({ runSession(socket) }, "iap2-bt-session").apply {
                isDaemon = true
                start()
            }
        }
    }

    private fun runSession(socket: BluetoothSocket) {
        val stream =
            StreamDuplexStream(socket.inputStream, socket.outputStream) {
                runCatching { socket.close() }
            }
        val channel = Iap2LinkChannel(stream, name = "iap2-bt") { line -> log(line) }
        activeChannel = channel
        BtSessionGate.inboundActive = true
        val credentialsHook: (Iap2SenderSession.WifiConfiguration) -> Unit =
            if (channel.adapterMode) {
                { wifi ->
                    adapterHandedOff = true
                    onWifiCredentials(wifi)
                }
            } else {
                onWifiCredentials
            }
        try {
            channel.runSenderSession(
                context,
                SESSION_TIMEOUT_MILLIS,
                log,
                onEndpoint,
                credentialsHook,
                onEndpointUpdate,
                onLinkClosed,
                knownCarSsid,
                onGeneration,
            )
        } catch (error: IOException) {
            log("SESSION failed: ${error.message}")
        } finally {
            activeChannel = null
            BtSessionGate.inboundActive = false
            channel.close()
            log("SESSION ended, waiting for receiver to reconnect (BT listener still running)")
        }
    }

    /** 主动结束当前 BT 控制会话（不影响监听）。用于 AirPlay 配对前复现控制通道退役状态。 */
    fun detachCurrentSession() {
        activeChannel?.let { channel ->
            activeChannel = null
            channel.close()
            log("BT: closed current control session (simulated retirement after tunnel handoff)")
        }
        closeHeldSockets()
    }

    /** Forget an earlier adapter Wi-Fi handoff so the next RFCOMM connection gets a full bootstrap. */
    fun resetAdapterHandoff() {
        adapterHandedOff = false
        closeHeldSockets()
    }

    private val heldSockets = java.util.Collections.synchronizedSet(HashSet<BluetoothSocket>())

    private fun holdSilently(socket: BluetoothSocket) {
        heldSockets += socket
        var bytes = 0L
        try {
            val input = socket.inputStream
            val buffer = ByteArray(1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                bytes += count
            }
        } catch (_: IOException) {
            // closed by either side
        } finally {
            heldSockets -= socket
            runCatching { socket.close() }
            log("ADAPTER: held post-handoff RFCOMM connection closed ($bytes bytes ignored)")
        }
    }

    private fun closeHeldSockets() {
        val sockets = synchronized(heldSockets) { heldSockets.toList() }
        sockets.forEach { runCatching { it.close() } }
    }

    fun stop() {
        running = false
        adapterHandedOff = false
        closeHeldSockets()
        serverSocket.getAndSet(null)?.let { runCatching { it.close() } }
        publishedName = null
        log("BT: listener stopped")
    }
}
