package io.github.shaonialife.cardeck.replay

import io.github.shaonialife.cardeck.iap2.Iap2Csm
import io.github.shaonialife.cardeck.iap2.Iap2LinkEngine
import io.github.shaonialife.cardeck.transport.DuplexStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * smartBox-136F (Carlinkit-style wireless CarPlay adapter) Bluetooth iAP2 opening, from the
 * reference sender's field capture (unapple-phone, out/captures/smartbox-e2e-20261026T171210Z/iap2.log).
 * Identifiers in 0x1D01 (serial, Bluetooth address, plan id) are zeroed at the same lengths.
 */
object SmartBoxCapture {
    /** Box SYN as captured: seq 0x24, window 1, max 128-byte packets, 5131 ms x16, session 1/control/v2. */
    val BOX_SYN: ByteArray = hex("ff5a001780240000ec01010080140b1017100301000222")
    val BOX_SYN_PAYLOAD: ByteArray = BOX_SYN.copyOfRange(9, BOX_SYN.size - 1)
    const val BOX_SYN_SEQUENCE = 0x24
    const val BOX_CONTROL_SESSION = 1
    const val BOX_MAX_PACKET = 128

    /** Our captured SYN|ACK answer (seq 99, ack 0x24, the box's payload verbatim). */
    val PHONE_SYN_ACK: ByteArray = hex("ff5a0017c06324004901010080140b1017100301000222")

    /** The box's 273-byte 0x1D01 (declares it sends only 0x5703 and receives only 0x5702). */
    val IDENTIFICATION: ByteArray =
        hex(
            "404001111d0100120000736d617274426f782d3133364600000f00015841562d4158383130300000090002536f6e79000011" +
                "000330303030303030303030303000000b0004312e33312e3000000c000548575f5631303000000600065703000600075702" +
                "00050008000006000900000007000c656e000007000d6b6f000007000d656e00001e00110006000000010006000131000004" +
                "0002000a000302000000000100330014000600000a0000120001736d617274426f782d313336460000050002000012000673" +
                "6d617274426f782d3133364600002f0018000600000006001d0001576972656c657373436172506c61795472616e73706f72" +
                "74000004000200040004000d0022303030303030303000",
        )

    const val SSID = "smartBox-TEST"
    const val PASSPHRASE = "test-passphrase"

    /** 0x5703 in the box's layout: 1=SSID, 2=passphrase, 3=security (2), 4=channel (36). */
    fun wifiConfiguration(): ByteArray =
        Iap2Csm.encode(
            0x5703,
            Iap2Csm.string(1, SSID) + Iap2Csm.string(2, PASSPHRASE) + Iap2Csm.u8(3, 2) + Iap2Csm.u8(4, 36),
        )

    fun frame(
        control: Int,
        sequence: Int,
        ack: Int,
        session: Int,
        payload: ByteArray?,
    ): ByteArray {
        val length = payload?.size?.plus(10) ?: 9
        val out = ByteArray(length)
        out[0] = 0xFF.toByte()
        out[1] = 0x5A
        out[2] = (length ushr 8).toByte()
        out[3] = length.toByte()
        out[4] = control.toByte()
        out[5] = sequence.toByte()
        out[6] = ack.toByte()
        out[7] = session.toByte()
        out[8] = checksum(out, 0, 8)
        if (payload != null) {
            payload.copyInto(out, 9)
            out[length - 1] = checksum(payload, 0, payload.size)
        }
        return out
    }

    data class Frame(
        val control: Int,
        val sequence: Int,
        val ack: Int,
        val session: Int,
        val payload: ByteArray?,
    )

    /** Splits our output into markers (returned as control = -1) and link frames. */
    fun parse(bytes: ByteArray): List<Frame> {
        val out = ArrayList<Frame>()
        var offset = 0
        while (offset < bytes.size) {
            if (offset + 6 <= bytes.size && bytes.copyOfRange(offset, offset + 6).contentEquals(Iap2LinkEngine.MARKER)) {
                out += Frame(-1, 0, 0, 0, null)
                offset += 6
                continue
            }
            if (offset + 9 > bytes.size || bytes[offset] != 0xFF.toByte() || bytes[offset + 1] != 0x5A.toByte()) {
                offset++
                continue
            }
            val length = ((bytes[offset + 2].toInt() and 0xFF) shl 8) or (bytes[offset + 3].toInt() and 0xFF)
            if (offset + length > bytes.size) break
            val payload = if (length > 9) bytes.copyOfRange(offset + 9, offset + length - 1) else null
            out +=
                Frame(
                    bytes[offset + 4].toInt() and 0xFF,
                    bytes[offset + 5].toInt() and 0xFF,
                    bytes[offset + 6].toInt() and 0xFF,
                    bytes[offset + 7].toInt() and 0xFF,
                    payload,
                )
            offset += length
        }
        return out
    }

    /** CSM payload split into the box's 128-byte link packets (118 data bytes each). */
    fun chunks(csm: ByteArray): List<ByteArray> = csm.toList().chunked(BOX_MAX_PACKET - 10).map { it.toByteArray() }

    fun hex(text: String): ByteArray = ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun checksum(
        bytes: ByteArray,
        from: Int,
        until: Int,
    ): Byte {
        var sum = 0
        for (index in from until until) sum += bytes[index].toInt() and 0xFF
        return (-sum and 0xFF).toByte()
    }
}

/**
 * Interactive smartBox stand-in for the full Iap2LinkChannel + Iap2SenderSession stack: opens with
 * its detect marker, answers our marker with the captured SYN, ACKs everything, and plays the
 * Carlinkit control session (0x1D01 in 128-byte packets, certificate, response, 0x5703 twice).
 */
class SmartBoxStream : DuplexStream {
    private val inbound = LinkedBlockingQueue<ByteArray>()
    private var leftover = ByteArray(0)

    @Volatile
    private var closed = false
    private var boxSequence = SmartBoxCapture.BOX_SYN_SEQUENCE
    private var lastPhoneSequence = 0
    private var synSent = false
    private val csmParser = Iap2Csm.Parser()

    /** Everything we wrote, one entry per write() call, with its arrival time. */
    val writes = ArrayList<Pair<Long, ByteArray>>()

    /** Phone → box CSM messages in arrival order, with arrival time. */
    val phoneMessages = ArrayList<Pair<Long, Iap2Csm.Frame>>()

    @Volatile
    var boxAckOfSynAckAt: Long? = null

    init {
        inbound.add(Iap2LinkEngine.MARKER)
    }

    override fun read(buffer: ByteArray): Int {
        while (true) {
            if (closed) return -1
            if (leftover.isNotEmpty()) {
                val count = minOf(buffer.size, leftover.size)
                leftover.copyInto(buffer, 0, 0, count)
                leftover = leftover.copyOfRange(count, leftover.size)
                return count
            }
            leftover =
                try {
                    inbound.poll(100, TimeUnit.MILLISECONDS) ?: continue
                } catch (_: InterruptedException) {
                    return -1
                }
        }
    }

    @Synchronized
    override fun write(bytes: ByteArray) {
        val now = System.nanoTime() / 1_000_000L
        writes += now to bytes.copyOf()
        for (frame in SmartBoxCapture.parse(bytes)) {
            when {
                frame.control == -1 ->
                    if (!synSent) {
                        synSent = true
                        inbound.add(SmartBoxCapture.BOX_SYN)
                    }

                frame.control == 0xC0 -> {
                    lastPhoneSequence = frame.sequence
                    if (boxAckOfSynAckAt == null) boxAckOfSynAckAt = now
                    inbound.add(SmartBoxCapture.frame(0x40, boxSequence, frame.sequence, 0, null))
                }

                frame.control == 0x40 && frame.payload != null -> {
                    lastPhoneSequence = frame.sequence
                    inbound.add(SmartBoxCapture.frame(0x40, boxSequence, frame.sequence, 0, null))
                    csmParser.offer(frame.payload).forEach { message ->
                        phoneMessages += now to message
                        answer(message.messageId)
                    }
                }
            }
        }
    }

    private fun answer(messageId: Int) {
        when (messageId) {
            0x1D00 -> sendCsm(SmartBoxCapture.IDENTIFICATION)
            0xAA00 -> sendCsm(Iap2Csm.encode(0xAA01, Iap2Csm.param(0, ByteArray(300) { 0x30 })))
            0xAA02 -> sendCsm(Iap2Csm.encode(0xAA03, Iap2Csm.param(0, ByteArray(128) { 0x44 })))
            0x5702 -> sendCsm(SmartBoxCapture.wifiConfiguration())
            // The box may repeat its Wi-Fi configuration after the transport identifiers
            0x4E0E -> sendCsm(SmartBoxCapture.wifiConfiguration())
        }
    }

    private fun sendCsm(csm: ByteArray) {
        SmartBoxCapture.chunks(csm).forEach { chunk ->
            boxSequence = (boxSequence + 1) and 0xFF
            inbound.add(SmartBoxCapture.frame(0x40, boxSequence, lastPhoneSequence, SmartBoxCapture.BOX_CONTROL_SESSION, chunk))
        }
    }

    override fun close() {
        closed = true
    }
}
