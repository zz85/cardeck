package io.github.shaonialife.cardeck.airplay

import io.github.shaonialife.cardeck.iap2.Iap2Csm
import io.github.shaonialife.cardeck.iap2.Iap2LinkEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wireless-adapter (smartBox) Wi-Fi iAP2 tunnel: receive-driven gating, marker answering and the
 * reference control session (unapple-phone `iap_tunnel.rs` / `bootstrap_rx.rs` `Path::Tunnel`).
 * A bare [Iap2LinkEngine] plays the box.
 */
class AdapterIapLinkTest {
    private class Harness {
        var clock = 10_000L
        val posted = ArrayList<ByteArray>()
        private val pending = ArrayList<ByteArray>()
        var readyCount = 0
        var carSyn = ByteArray(0)
        val car = Iap2LinkEngine()
        val link =
            AdapterIapLink(
                log = { },
                post = {
                    posted.add(it)
                    pending.add(it)
                },
                bluetoothId = { "AA:BB:CC:DD:EE:01" },
                transportId = { "00008150AAAAAAAAAAAAAAAA" },
                alive = { false },
                nowMillis = { clock },
                language = "en",
            ).also { it.onReady = { readyCount++ } }

        fun carToLink() {
            val out = car.takeOutput()
            if (out.isNotEmpty()) link.onCarBytes(out)
        }

        fun linkToCar() {
            while (pending.isNotEmpty()) {
                val batch = pending.toList()
                pending.clear()
                batch.forEach { car.feed(it, clock) }
            }
        }

        fun exchange() {
            carToLink()
            linkToCar()
            clock += 100
            car.advanceTime(clock)
            carToLink()
            linkToCar()
        }

        /** Box SYN → our SYN|ACK → box ACK → 0x1D00. */
        fun bringUp() {
            car.start(clock)
            exchange()
            link.onCarBytes(bareAck(sequence = 99, ack = 99))
            linkToCar()
        }

        fun carSends(
            messageId: Int,
            body: ByteArray = ByteArray(0),
        ): List<Int> {
            val before = csmIds().size
            assertTrue(car.sendControl(Iap2Csm.encode(messageId, body), clock))
            exchange()
            return csmIds().drop(before)
        }

        fun csmFrames(): List<Pair<Int, ByteArray>> =
            posted.flatMap { chunk ->
                linkPayloads(chunk).flatMap { payload ->
                    Iap2Csm.Parser().offer(payload).map { it.messageId to it.body }
                }
            }

        fun csmIds(): List<Int> = csmFrames().map { it.first }
    }

    @Test
    fun nothingIsPostedBeforeTheBoxSpeaks() {
        val h = Harness()
        repeat(20) {
            h.clock += 500
            h.link.tick()
        }
        assertTrue(h.posted.isEmpty())
        assertFalse(h.link.unblocked)
        h.link.onCarBytes(Iap2LinkEngine.MARKER)
        assertTrue(h.link.unblocked)
        assertEquals(listOf(Iap2LinkEngine.MARKER.toList()), h.posted.map { it.toList() })
    }

    @Test
    fun markersAreAnsweredAtMostEveryTwoSecondsThenOurSyn() {
        val h = Harness()
        h.link.onCarBytes(Iap2LinkEngine.MARKER)
        assertEquals(1, h.posted.size)
        h.clock += 1_000
        h.link.onCarBytes(Iap2LinkEngine.MARKER)
        assertEquals("a marker within 2 s gets no reply", 1, h.posted.size)
        h.clock += 1_500
        h.link.onCarBytes(Iap2LinkEngine.MARKER)
        h.clock += 2_000
        h.link.onCarBytes(Iap2LinkEngine.MARKER)
        assertEquals(3, h.posted.size)
        assertTrue(h.posted.all { it.contentEquals(Iap2LinkEngine.MARKER) })
        // After three replies a box that keeps detecting gets our SYN (no extra marker in front).
        h.clock += 2_000
        h.link.onCarBytes(Iap2LinkEngine.MARKER)
        val syn = h.posted.last()
        assertEquals(0xFF.toByte(), syn[0])
        assertEquals(0x5A.toByte(), syn[1])
        assertTrue("SYN bit", (syn[4].toInt() and 0x80) != 0)
        assertTrue("pure SYN", (syn[4].toInt() and 0x40) == 0)
    }

    @Test
    fun boxSynGetsSynAckThenIdentificationStarts() {
        val h = Harness()
        h.car.start(h.clock)
        h.carSyn = synPayload(h.car.takeOutput().also { h.link.onCarBytes(it) })
        h.exchange()
        val synAck = h.posted.first()
        assertEquals(0xC0, synAck[4].toInt() and 0xC0)
        assertEquals(Iap2LinkEngine.State.NORMAL, h.car.state)
        // The SYN|ACK echoes the box's SYN payload byte-for-byte.
        assertTrue(h.carSyn.isNotEmpty())
        assertEquals(h.carSyn.toList(), synAck.copyOfRange(9, synAck.size - 1).toList())
        h.clock += 600
        h.link.tick()
        assertFalse("0x1D00 waits for the box's ACK", h.csmIds().contains(AdapterIapLink.START_IDENTIFICATION))
        h.link.onCarBytes(bareAck(sequence = 99, ack = 99))
        assertEquals(listOf(AdapterIapLink.START_IDENTIFICATION), h.csmIds())
        // SYN|ACK and 0x1D00 are separate posts.
        assertTrue(h.posted.size >= 2)
    }

    @Test
    fun fullControlSessionMatchesTheReference() {
        val h = Harness()
        h.bringUp()
        assertEquals(listOf(0x1D02, 0xAA00), h.carSends(0x1D01, Iap2Csm.string(0, "smartBox-136F") + Iap2Csm.string(1, "XAV-AX8100")))
        // Authentication serial first: re-request the certificate once only.
        assertEquals(listOf(0xAA00), h.carSends(0xAA06, Iap2Csm.string(0, "serial")))
        assertEquals(emptyList<Int>(), h.carSends(0xAA06, Iap2Csm.string(0, "serial")))
        assertEquals(listOf(0xAA02), h.carSends(0xAA01, Iap2Csm.param(0, ByteArray(600))))
        val challenge = h.csmFrames().last { it.first == 0xAA02 }.second
        assertEquals(
            20,
            Iap2Csm
                .parseParams(challenge)
                .first()
                .value.size,
        )
        assertEquals(0, h.readyCount)
        assertEquals(listOf(0xAA05, 0x4E0A, 0x4E0B, 0x5702), h.carSends(0xAA03, Iap2Csm.param(0, ByteArray(64))))
        assertEquals(1, h.readyCount)
        assertEquals(AdapterIapLink.Stage.NETWORK, h.link.stage)

        assertEquals(listOf(0x4E0D, 0x4E0E, 0x4300), h.carSends(0x5703, Iap2Csm.string(0, "smartBox-136F")))
        val transport = Iap2Csm.parseParams(h.csmFrames().last { it.first == 0x4E0E }.second)
        assertEquals("AA:BB:CC:DD:EE:01", transport[0].value.decodeToString().trimEnd('\u0000'))
        assertEquals("00008150AAAAAAAAAAAAAAAA", transport[1].value.decodeToString().trimEnd('\u0000'))
        // A repeated 0x5703 does not repeat the identifiers or availability.
        assertEquals(emptyList<Int>(), h.carSends(0x5703, Iap2Csm.string(0, "smartBox-136F")))

        assertEquals(listOf(0x5001), h.carSends(0x5000))
        assertEquals(listOf(0x4155), h.carSends(0x4154))
        assertEquals(listOf(0x4158), h.carSends(0x4157))
        assertEquals(1, h.readyCount)
    }

    @Test
    fun boxRestartingTheLinkRestartsIdentification() {
        val h = Harness()
        h.bringUp()
        h.carSends(0x1D01, Iap2Csm.string(0, "box"))
        assertEquals(AdapterIapLink.Stage.CERTIFICATE, h.link.stage)
        // A fresh box engine reuses SYN seq 99; after the box's data that is still a restart.
        val restarted = Iap2LinkEngine()
        restarted.start(h.clock)
        h.link.onCarBytes(restarted.takeOutput())
        assertEquals(AdapterIapLink.Stage.IDLE, h.link.stage)
    }
}

/** Link payloads (FF5A frames with a payload) in one posted chunk; markers are skipped. */
private fun linkPayloads(chunk: ByteArray): List<ByteArray> {
    val out = ArrayList<ByteArray>()
    var off = 0
    while (off + 9 <= chunk.size) {
        if (chunk[off] != 0xFF.toByte() || chunk[off + 1] != 0x5A.toByte()) {
            off += 1
            continue
        }
        val frameLen = ((chunk[off + 2].toInt() and 0xFF) shl 8) or (chunk[off + 3].toInt() and 0xFF)
        if (frameLen < 9 || off + frameLen > chunk.size) break
        // SYN frames carry link parameters, not CSM.
        if (frameLen > 9 && (chunk[off + 4].toInt() and 0x80) == 0) out.add(chunk.copyOfRange(off + 9, off + frameLen - 1))
        off += frameLen
    }
    return out
}

/** A bare link ACK frame from the box. */
private fun bareAck(
    sequence: Int,
    ack: Int,
): ByteArray {
    val frame = byteArrayOf(0xFF.toByte(), 0x5A, 0x00, 0x09, 0x40, sequence.toByte(), ack.toByte(), 0x00, 0x00)
    var sum = 0
    for (index in 0 until 8) sum += frame[index].toInt() and 0xFF
    frame[8] = (-sum and 0xFF).toByte()
    return frame
}

/** Payload of the first SYN frame in a chunk (after the box's marker). */
private fun synPayload(chunk: ByteArray): ByteArray {
    var off = 0
    while (off + 9 <= chunk.size) {
        if (chunk[off] == 0xFF.toByte() && chunk[off + 1] == 0x5A.toByte()) {
            val frameLen = ((chunk[off + 2].toInt() and 0xFF) shl 8) or (chunk[off + 3].toInt() and 0xFF)
            return chunk.copyOfRange(off + 9, off + frameLen - 1)
        }
        off++
    }
    return ByteArray(0)
}
