package io.github.shaonialife.cardeck.iap2

import io.github.shaonialife.cardeck.replay.SmartBoxCapture
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Adapter (smartBox) responder mode of [Iap2LinkEngine], replaying the box's captured opening. */
class AdapterLinkEngineTest {
    private fun adapterEngine() = Iap2LinkEngine().apply { adapterResponder = true }

    private fun negotiated(): Iap2LinkEngine {
        val engine = adapterEngine()
        engine.startResponder(0)
        engine.feed(Iap2LinkEngine.MARKER, 1)
        engine.feed(SmartBoxCapture.BOX_SYN, 2)
        engine.feed(SmartBoxCapture.frame(0x40, SmartBoxCapture.BOX_SYN_SEQUENCE, 99, 0, null), 3)
        engine.takeOutput()
        return engine
    }

    @Test
    fun answersMarkerThenEchoesTheBoxSynByteForByteWithoutOwnSyn() {
        val engine = adapterEngine()
        engine.startResponder(0)
        assertArrayEquals("first reply is the detect marker only", Iap2LinkEngine.MARKER, engine.takeOutput())

        engine.feed(Iap2LinkEngine.MARKER, 10)
        engine.advanceTime(1_000)
        assertArrayEquals("marker repeats each second until the SYN", Iap2LinkEngine.MARKER, engine.takeOutput())

        engine.feed(SmartBoxCapture.BOX_SYN, 1_100)
        val reply = engine.takeOutput()
        assertArrayEquals("SYN|ACK identical to the reference's field capture", SmartBoxCapture.PHONE_SYN_ACK, reply)
        val frame = SmartBoxCapture.parse(reply).single()
        assertEquals(0xC0, frame.control)
        assertArrayEquals(SmartBoxCapture.BOX_SYN_PAYLOAD, frame.payload)
        assertEquals(Iap2LinkEngine.State.NEGOTIATING, engine.state)

        // No marker once the SYN is in; the SYN|ACK repeats unchanged until the box ACKs it
        engine.advanceTime(2_100)
        assertArrayEquals(SmartBoxCapture.PHONE_SYN_ACK, engine.takeOutput())

        engine.feed(SmartBoxCapture.frame(0x40, SmartBoxCapture.BOX_SYN_SEQUENCE, 99, 0, null), 2_200)
        assertEquals(Iap2LinkEngine.State.NORMAL, engine.state)
        engine.advanceTime(10_000)
        assertTrue("nothing after link NORMAL without data", engine.takeOutput().isEmpty())
    }

    @Test
    fun neverSendsAPureSyn() {
        val engine = adapterEngine()
        engine.startResponder(0)
        val out = ArrayList<SmartBoxCapture.Frame>()
        for (now in 0L..5_000L step 250) {
            engine.advanceTime(now)
            out += SmartBoxCapture.parse(engine.takeOutput())
        }
        engine.feed(SmartBoxCapture.BOX_SYN, 5_100)
        engine.feed(SmartBoxCapture.BOX_SYN, 5_200)
        out += SmartBoxCapture.parse(engine.takeOutput())
        assertTrue(out.none { it.control == 0x80 })
        val echoes = out.filter { it.control == 0xC0 }
        assertEquals("every pure SYN gets a SYN|ACK", 2, echoes.size)
        echoes.forEach { assertArrayEquals(SmartBoxCapture.BOX_SYN_PAYLOAD, it.payload) }
    }

    @Test
    fun sendsWithTheBoxParametersOnePacketWindowAndSessionOne() {
        val engine = negotiated()
        engine.sendControl(byteArrayOf(1), 10)
        engine.sendControl(byteArrayOf(2), 10)
        val first = SmartBoxCapture.parse(engine.takeOutput())
        assertEquals("window 1: only one data frame in flight", 1, first.size)
        assertEquals(100, first.single().sequence)
        assertEquals(SmartBoxCapture.BOX_CONTROL_SESSION, first.single().session)

        // Box retransmit timeout is 5131 ms (not our 4000 ms default)
        engine.advanceTime(10 + 5_000)
        assertTrue(engine.takeOutput().isEmpty())
        engine.advanceTime(10 + 5_131)
        assertEquals(100, SmartBoxCapture.parse(engine.takeOutput()).single().sequence)

        engine.feed(SmartBoxCapture.frame(0x40, SmartBoxCapture.BOX_SYN_SEQUENCE, 100, 0, null), 6_000)
        assertEquals(101, SmartBoxCapture.parse(engine.takeOutput()).single().sequence)
    }

    @Test
    fun boxRetriesSixteenTimesBeforeTheLinkDies() {
        val engine = negotiated()
        engine.sendControl(byteArrayOf(1), 0)
        engine.takeOutput()
        var now = 0L
        repeat(16) {
            now += 5_131
            engine.advanceTime(now)
        }
        assertEquals(Iap2LinkEngine.State.NORMAL, engine.state)
        engine.advanceTime(now + 5_131)
        assertEquals(Iap2LinkEngine.State.DEAD, engine.state)
    }

    @Test
    fun identificationSplitAcross128BytePacketsReassembles() {
        val engine = negotiated()
        var sequence = SmartBoxCapture.BOX_SYN_SEQUENCE
        val packets =
            SmartBoxCapture.chunks(SmartBoxCapture.IDENTIFICATION).map {
                sequence++
                SmartBoxCapture.frame(0x40, sequence, 99, SmartBoxCapture.BOX_CONTROL_SESSION, it)
            }
        assertEquals(3, packets.size)
        assertTrue(packets.all { it.size <= SmartBoxCapture.BOX_MAX_PACKET })
        // RFCOMM delivers them in 120 + 8 byte reads, as in the field log
        packets.forEach { packet ->
            engine.feed(packet.copyOf(minOf(120, packet.size)), 10)
            if (packet.size > 120) engine.feed(packet.copyOfRange(120, packet.size), 10)
        }
        val parser = Iap2Csm.Parser()
        val frames = ArrayList<Iap2Csm.Frame>()
        while (true) {
            val event = engine.pollEvent() ?: break
            if (event is Iap2LinkEngine.Event.Control) frames += parser.offer(event.bytes)
        }
        assertEquals(1, frames.size)
        assertEquals(Iap2MessageIds.IDENTIFICATION_INFORMATION, frames.single().messageId)
        assertEquals(273 - Iap2Csm.HEADER_BYTES, frames.single().body.size)
    }
}
