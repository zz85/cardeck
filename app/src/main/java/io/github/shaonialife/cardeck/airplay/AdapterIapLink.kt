package io.github.shaonialife.cardeck.airplay

import io.github.shaonialife.cardeck.iap2.Iap2Csm
import io.github.shaonialife.cardeck.iap2.Iap2LinkEngine
import io.github.shaonialife.cardeck.iap2.Iap2MessageIds
import java.security.SecureRandom
import java.util.Locale

/**
 * The Wi-Fi iAP2 session of a wireless CarPlay *adapter* (Carlinkit / smartBox, Apple's
 * 320.17 receiver), used only while `AdapterMode.active`.
 *
 * Ported from the field-tested reference sender (unapple-phone `iap_tunnel.rs`, `iap2/link*.rs`,
 * `iap2/bootstrap_rx.rs` with `Path::Tunnel`):
 *  - box→phone link bytes arrive as event-channel `iAPSendMessage` ([onCarBytes]);
 *    phone→box bytes are posted as control-channel `iAPSendMessage` ([post]).
 *  - **Receive-driven**: nothing is posted before the box sends its first `iAPSendMessage`.
 *    The box leaves `iAPChannel` disabled and closes the control connection on an
 *    unsolicited one (reference NEXT_STEPS "Screen header verified...", run 015238Z).
 *  - The box is the detecting side: its markers are answered at most every 2 s, and after
 *    three answers without a SYN we send ours. Every pure SYN gets a SYN|ACK (cardeck §9h);
 *    a new SYN on an established link restarts the control session.
 *  - Control session: 0x1D00 → 0x1D01 → 0x1D02 + 0xAA00 → (0xAA06 → 0xAA00 once) → 0xAA01 →
 *    0xAA02 (20-byte challenge; the smartBox answers 20 B) → 0xAA03 → 0xAA05 + 0x4E0A/0x4E0B/0x5702
 *    (ready) → 0x5703 → 0x4E0D + 0x4E0E{BT MAC, UDID} + 0x4300; idle replies to 0x5000/0x4154/0x4157.
 */
class AdapterIapLink(
    private val log: (String) -> Unit,
    /** Phone→box link bytes (control-channel `iAPSendMessage` POST). */
    private val post: (ByteArray) -> Unit,
    /** Our Bluetooth MAC (0x4E0E / 0x4300), upper-case colon form. */
    private val bluetoothId: () -> String,
    /** UDID-shaped USB transport identifier for 0x4E0E (should match the Bluetooth session's). */
    private val transportId: () -> String,
    /** Pacer thread runs while this is true (false in unit tests: drive with [tick]). */
    private val alive: () -> Boolean,
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000L },
    private val language: String = Locale.getDefault().language.ifBlank { "en" },
) {
    /** Inner control session is up (authenticated, 0xAA05 burst sent). Fires once. */
    var onReady: (() -> Unit)? = null

    enum class Stage { IDLE, IDENTIFICATION, CERTIFICATE, CHALLENGE, NETWORK, COMPLETE }

    private val lock = Any()
    private var engine = newEngine()
    private var csmParser = Iap2Csm.Parser()

    /** Set by the box's first `iAPSendMessage`; until then all output is dropped. */
    @Volatile
    var unblocked: Boolean = false
        private set
    private var droppedBeforeUnblock = 0

    /** Number of control-channel posts made (diagnostics/tests). */
    @Volatile
    var postsSent: Int = 0
        private set

    // ---- link negotiation ----
    private var detecting = true
    private var lastMarkerReplyAt: Long? = null
    private var markersAnswered = 0
    private var peerSynSeq: Int? = null
    private var peerSentData = false
    private var csmStarted = false

    // ---- control session ----
    @Volatile
    var stage: Stage = Stage.IDLE
        private set
    private var certificateRerequested = false
    private var wifiConfigurations = 0
    private var availabilitySent = false
    private var readyNotified = false
    private var unhandledLogged = 0
    private var eaLogged = 0

    init {
        Thread(
            {
                while (alive()) {
                    try {
                        Thread.sleep(PACER_MS)
                    } catch (_: InterruptedException) {
                        return@Thread
                    }
                    tick()
                }
            },
            "adapter-iap-pacer",
        ).apply {
            isDaemon = true
            start()
        }
    }

    /**
     * [responder] = the engine's adapter responder mode: echo the box's SYN byte-for-byte, resend
     * the SYN|ACK until the box ACKs it, NORMAL only then (reference `Mode::AwaitPeer` +
     * `echo_peer_parameters`). false only once we initiate because the box kept detecting.
     */
    private fun newEngine(responder: Boolean = true): Iap2LinkEngine =
        Iap2LinkEngine().also { created ->
            created.adapterResponder = responder
            created.onAdapterEvent = { line -> log("ADAPTER-IAP: link $line") }
            created.onEaData = { bytes ->
                if (eaLogged++ < 10) log("ADAPTER-IAP: EA data ${bytes.size}B (ignored)")
            }
            // Silent: the box detects; we never repeat markers on a timer.
            created.enterNegotiating()
        }

    /** Box→phone bytes (event-channel `iAPSendMessage` data). */
    fun onCarBytes(bytes: ByteArray) {
        synchronized(lock) {
            val now = nowMillis()
            if (!unblocked) {
                unblocked = true
                log(
                    "ADAPTER-IAP: box sent its first iAPSendMessage (${bytes.size}B) - tunnel unblocked" +
                        if (droppedBeforeUnblock > 0) " ($droppedBeforeUnblock premature outputs were dropped)" else "",
                )
            }
            val frames = scanLinkFrames(bytes)
            if (frames.isNotEmpty()) detecting = false
            frames.lastOrNull { it.control and SYN != 0 && it.control and ACK == 0 }?.let { onPeerSynLocked(it.sequence) }
            if (frames.any { it.length > Iap2LinkEngine.HEADER_BYTES && it.control and SYN == 0 }) peerSentData = true
            if (frames.isEmpty() && isMarker(bytes)) onMarkerLocked(now)
            engine.feed(bytes, now)
            drainLocked()
        }
    }

    /** Timer step (pacer thread; tests call it directly). */
    fun tick() {
        synchronized(lock) {
            engine.advanceTime(nowMillis())
            drainLocked()
        }
    }

    /**
     * A SYN after the box's first data (even reusing the sequence number, as engines starting at 99
     * do) or on a dead link restarts the link and the control session. A retransmitted SYN before
     * any data is answered by the responder engine itself with the identical SYN|ACK.
     */
    private fun onPeerSynLocked(sequence: Int) {
        val previous = peerSynSeq
        val state = engine.state
        val restart =
            state == Iap2LinkEngine.State.DEAD ||
                (state == Iap2LinkEngine.State.NORMAL && previous != null && (previous != sequence || peerSentData))
        if (restart) {
            log("ADAPTER-IAP: box restarted the inner link (SYN seq=$sequence, was $previous) - identification starts over")
            resetLocked()
        }
        peerSynSeq = sequence
    }

    private fun resetLocked() {
        engine = newEngine()
        csmParser = Iap2Csm.Parser()
        peerSentData = false
        csmStarted = false
        stage = Stage.IDLE
        certificateRerequested = false
        wifiConfigurations = 0
        availabilitySent = false
    }

    private fun onMarkerLocked(now: Long) {
        if (!detecting || engine.state == Iap2LinkEngine.State.NORMAL) return
        val last = lastMarkerReplyAt
        if (last != null && now - last < MARKER_REPLY_GAP_MS) return
        lastMarkerReplyAt = now
        if (markersAnswered >= MARKER_REPLIES_BEFORE_SYN) {
            // A box that keeps detecting after our replies waits for us to synchronize; as the
            // initiator we adopt its SYN|ACK, so the responder mode does not apply.
            engine = newEngine(responder = false)
            engine.initiate(now)
            engine.stopNegotiationRetries()
            val out = engine.takeOutput()
            val syn = if (isMarker(out)) out.copyOfRange(Iap2LinkEngine.MARKER.size, out.size) else out
            log("ADAPTER-IAP: box keeps detecting after $markersAnswered marker replies - sending our SYN")
            emit(syn)
            return
        }
        markersAnswered++
        log("ADAPTER-IAP: answering box detect marker (#$markersAnswered)")
        emit(Iap2LinkEngine.MARKER)
    }

    private fun drainLocked() {
        while (true) {
            when (val event = engine.pollEvent()) {
                null -> break
                is Iap2LinkEngine.Event.Control -> csmParser.offer(event.bytes).forEach(::handleCsmLocked)
                is Iap2LinkEngine.Event.Dead ->
                    log("ADAPTER-IAP: inner link died - ${event.reason ?: "EOF"} (waiting for the box to restart it)")
            }
        }
        // SYN|ACK goes out on its own post, before any control-session data.
        flushLocked()
        // NORMAL = the box ACKed our SYN|ACK (reference: link writable → identification).
        if (engine.state == Iap2LinkEngine.State.NORMAL && !csmStarted) {
            csmStarted = true
            stage = Stage.IDENTIFICATION
            log("ADAPTER-IAP: inner iAP2 link ready - starting identification")
            sendCsmLocked(START_IDENTIFICATION)
            flushLocked()
        }
    }

    private fun flushLocked() {
        val out = engine.takeOutput()
        if (out.isNotEmpty()) emit(out)
    }

    private fun emit(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        if (!unblocked) {
            droppedBeforeUnblock++
            if (droppedBeforeUnblock <= 3) log("ADAPTER-IAP: dropped ${bytes.size}B before the box's first iAPSendMessage (receive-driven)")
            return
        }
        postsSent++
        post(bytes)
    }

    private fun sendCsmLocked(
        messageId: Int,
        body: ByteArray = ByteArray(0),
        awaitAck: Boolean = true,
    ) {
        val ok = engine.sendControl(Iap2Csm.encode(messageId, body), nowMillis(), awaitAck)
        log("ADAPTER-IAP: tx 0x${messageId.toString(16)} (${Iap2MessageIds.nameOf(messageId)}) ok=$ok")
    }

    private fun handleCsmLocked(frame: Iap2Csm.Frame) {
        val id = frame.messageId
        log("ADAPTER-IAP: rx 0x${id.toString(16)} (${Iap2MessageIds.nameOf(id)}) ${frame.body.size}B stage=$stage")
        when {
            id == IDENTIFICATION_INFORMATION && stage == Stage.IDENTIFICATION -> {
                val params = Iap2Csm.parseParams(frame.body).associateBy { it.id }
                log(
                    "ADAPTER-IAP: box identity name=${params[0]?.value?.decodeToString()?.trimEnd('\u0000')} " +
                        "model=${params[1]?.value?.decodeToString()?.trimEnd('\u0000')}",
                )
                sendCsmLocked(IDENTIFICATION_ACCEPTED)
                sendCsmLocked(REQUEST_CERTIFICATE)
                stage = Stage.CERTIFICATE
            }

            // Authentication serial before the certificate: ask for the certificate again, once.
            id == AUTH_SERIAL && stage == Stage.CERTIFICATE && !certificateRerequested -> {
                certificateRerequested = true
                sendCsmLocked(REQUEST_CERTIFICATE)
            }

            id == CERTIFICATE && stage == Stage.CERTIFICATE -> {
                val challenge = ByteArray(CHALLENGE_BYTES).also { SecureRandom().nextBytes(it) }
                sendCsmLocked(REQUEST_CHALLENGE, Iap2Csm.param(0, challenge))
                stage = Stage.CHALLENGE
            }

            id == CHALLENGE_RESPONSE && stage == Stage.CHALLENGE -> {
                sendCsmLocked(AUTHENTICATION_SUCCEEDED)
                // A real iPhone bursts its state right after authenticating; the box's Wi-Fi
                // identification lists 0x4E0A/0x4E0B/0x5702 among the messages it receives.
                sendCsmLocked(DEVICE_LANGUAGE_UPDATE, Iap2Csm.string(0, language))
                sendCsmLocked(DEVICE_TIME_UPDATE, Iap2Csm.param(0, i64(System.currentTimeMillis() / 1000L)))
                sendCsmLocked(REQUEST_WIFI_CONFIGURATION)
                stage = Stage.NETWORK
                if (!readyNotified) {
                    readyNotified = true
                    log("ADAPTER-IAP: ✓ inner iAP2 control session ready")
                    runCatching { onReady?.invoke() }
                }
            }

            id == AUTHENTICATION_FAILED_ID -> log("ADAPTER-IAP: box reported authentication failure")

            id == WIFI_CONFIGURATION && stage == Stage.NETWORK -> {
                wifiConfigurations++
                if (wifiConfigurations == 1) {
                    sendCsmLocked(WIRELESS_CARPLAY_UPDATE, Iap2Csm.u8(0, 1))
                    // Fire-and-forget like the Bluetooth path (Audi never ACKs 0x4E0E).
                    sendCsmLocked(
                        TRANSPORT_IDENTIFIERS,
                        Iap2Csm.string(0, bluetoothId()) + Iap2Csm.string(1, transportId()),
                        awaitAck = false,
                    )
                }
                if (!availabilitySent) {
                    availabilitySent = true
                    sendCsmLocked(
                        CARPLAY_AVAILABILITY,
                        Iap2Csm.group(1, Iap2Csm.u8(0, 1), Iap2Csm.string(1, bluetoothId())),
                    )
                }
            }

            id == CARPLAY_START_SESSION && stage == Stage.NETWORK -> {
                stage = Stage.COMPLETE
                log("ADAPTER-IAP: box sent 0x4301 over Wi-Fi - bootstrap complete (session keeps answering subscriptions)")
            }

            stage == Stage.NETWORK || stage == Stage.COMPLETE ->
                when (id) {
                    START_NOW_PLAYING_UPDATES -> sendCsmLocked(NOW_PLAYING_UPDATE)
                    START_CALL_STATE_UPDATES -> sendCsmLocked(CALL_STATE_UPDATE, Iap2Csm.param(2, byteArrayOf(0)))
                    START_COMMUNICATIONS_UPDATES -> sendCsmLocked(COMMUNICATIONS_UPDATE)
                    else -> logUnhandled(frame)
                }

            else -> logUnhandled(frame)
        }
    }

    private fun logUnhandled(frame: Iap2Csm.Frame) {
        if (unhandledLogged++ < 20) {
            log(
                "ADAPTER-IAP: unhandled 0x${frame.messageId.toString(16)} at stage=$stage hex=" +
                    frame.body.take(48).joinToString(" ") { "%02x".format(it) },
            )
        }
    }

    private class LinkFrame(
        val control: Int,
        val sequence: Int,
        val length: Int,
    )

    companion object {
        const val PACER_MS = 200L
        const val MARKER_REPLY_GAP_MS = 2_000L
        const val MARKER_REPLIES_BEFORE_SYN = 3
        const val CHALLENGE_BYTES = 20

        private const val SYN = 0x80
        private const val ACK = 0x40

        const val START_IDENTIFICATION = 0x1D00
        const val IDENTIFICATION_INFORMATION = 0x1D01
        const val IDENTIFICATION_ACCEPTED = 0x1D02
        const val REQUEST_CERTIFICATE = 0xAA00
        const val CERTIFICATE = 0xAA01
        const val REQUEST_CHALLENGE = 0xAA02
        const val CHALLENGE_RESPONSE = 0xAA03
        const val AUTHENTICATION_FAILED_ID = 0xAA04
        const val AUTHENTICATION_SUCCEEDED = 0xAA05
        const val AUTH_SERIAL = 0xAA06
        const val DEVICE_LANGUAGE_UPDATE = 0x4E0A
        const val DEVICE_TIME_UPDATE = 0x4E0B
        const val WIRELESS_CARPLAY_UPDATE = 0x4E0D
        const val TRANSPORT_IDENTIFIERS = 0x4E0E
        const val REQUEST_WIFI_CONFIGURATION = 0x5702
        const val WIFI_CONFIGURATION = 0x5703
        const val CARPLAY_AVAILABILITY = 0x4300
        const val CARPLAY_START_SESSION = 0x4301
        const val START_NOW_PLAYING_UPDATES = 0x5000
        const val NOW_PLAYING_UPDATE = 0x5001
        const val START_CALL_STATE_UPDATES = 0x4154
        const val CALL_STATE_UPDATE = 0x4155
        const val START_COMMUNICATIONS_UPDATES = 0x4157
        const val COMMUNICATIONS_UPDATE = 0x4158

        private fun isMarker(bytes: ByteArray): Boolean {
            val marker = Iap2LinkEngine.MARKER
            if (bytes.size < marker.size) return false
            for (index in marker.indices) if (bytes[index] != marker[index]) return false
            return true
        }

        private fun i64(value: Long): ByteArray = ByteArray(8) { index -> (value ushr ((7 - index) * 8)).toByte() }

        /** Link frame headers (FF 5A, 9-byte header with valid checksum) in one chunk. */
        private fun scanLinkFrames(bytes: ByteArray): List<LinkFrame> {
            val frames = ArrayList<LinkFrame>()
            var offset = 0
            while (offset + Iap2LinkEngine.HEADER_BYTES <= bytes.size) {
                if (bytes[offset] != 0xFF.toByte() || bytes[offset + 1] != 0x5A.toByte()) {
                    offset++
                    continue
                }
                var sum = 0
                for (index in 0 until Iap2LinkEngine.HEADER_BYTES) sum += bytes[offset + index].toInt() and 0xFF
                val length = ((bytes[offset + 2].toInt() and 0xFF) shl 8) or (bytes[offset + 3].toInt() and 0xFF)
                if (sum and 0xFF != 0 || length < Iap2LinkEngine.HEADER_BYTES) {
                    offset++
                    continue
                }
                frames += LinkFrame(bytes[offset + 4].toInt() and 0xFF, bytes[offset + 5].toInt() and 0xFF, length)
                offset += length
            }
            return frames
        }
    }
}
