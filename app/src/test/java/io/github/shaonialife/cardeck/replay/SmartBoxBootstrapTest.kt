package io.github.shaonialife.cardeck.replay

import io.github.shaonialife.cardeck.bt.AdapterMode
import io.github.shaonialife.cardeck.iap2.Iap2Csm
import io.github.shaonialife.cardeck.iap2.Iap2MessageIds
import io.github.shaonialife.cardeck.iap2.Iap2SenderSession
import io.github.shaonialife.cardeck.transport.Iap2LinkChannel
import io.github.shaonialife.cardeck.transport.InMemoryStreamPair
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Adapter-mode Bluetooth bootstrap against an interactive smartBox stand-in ([SmartBoxStream]):
 * link opening (marker → box SYN → byte-exact SYN|ACK → box ACK), the delay before 0x1D00, and the
 * Carlinkit control-session order of the reference sender.
 */
class SmartBoxBootstrapTest {
    @After
    fun tearDown() {
        AdapterMode.active = false
    }

    @Test
    fun channelTakesAdapterModeFromTheSharedSwitch() {
        AdapterMode.active = true
        val first = Iap2LinkChannel(InMemoryStreamPair().sideA, name = "adapter")
        AdapterMode.active = false
        val second = Iap2LinkChannel(InMemoryStreamPair().sideA, name = "normal")
        assertTrue(first.adapterMode)
        assertFalse(second.adapterMode)
        first.close()
        second.close()
    }

    @Test
    fun bootstrapFollowsTheReferenceOrderOverTheBoxLink() {
        val box = SmartBoxStream()
        val logs = CopyOnWriteArrayList<String>()
        val channel = Iap2LinkChannel(box, name = "smartbox", adapterMode = true) { logs += it }
        val credentials = CopyOnWriteArrayList<Iap2SenderSession.WifiConfiguration>()
        val delayMillis = 400L
        val endpoint =
            Iap2SenderSession(
                channel,
                log = { logs += it },
                bluetoothMac = "AA:BB:CC:DD:EE:FF",
                deviceName = "iPhone",
                onWifiCredentials = { credentials += it },
                adapterIdentificationDelayMillis = delayMillis,
            ).run(timeoutMillis = 1L) // adapter mode ignores the timeout
        logs.forEach { println("PHONE $it") }

        // ---- link layer ----
        val written = box.writes.flatMap { SmartBoxCapture.parse(it.second) }
        assertEquals("first reply is the detect marker", -1, written.first().control)
        assertTrue("never our own SYN", written.none { it.control == 0x80 })
        val synAck = box.writes.first { (_, bytes) -> SmartBoxCapture.parse(bytes).any { it.control == 0xC0 } }.second
        assertArrayEquals("SYN|ACK echoes the box SYN byte-for-byte", SmartBoxCapture.PHONE_SYN_ACK, synAck)
        val dataWrites = box.writes.map { (_, bytes) -> SmartBoxCapture.parse(bytes).count { it.control == 0x40 && it.payload != null } }
        assertTrue("one-packet window: at most one data frame per write", dataWrites.all { it <= 1 })
        assertTrue(
            "data frames go on the box's control session and fit its 128-byte packets",
            written
                .filter { it.control == 0x40 && it.payload != null }
                .all { it.session == SmartBoxCapture.BOX_CONTROL_SESSION && it.payload!!.size + 10 <= SmartBoxCapture.BOX_MAX_PACKET },
        )

        // ---- delay before StartIdentification ----
        val (firstAt, firstMessage) = box.phoneMessages.first()
        assertEquals(Iap2MessageIds.START_IDENTIFICATION, firstMessage.messageId)
        val ackAt = box.boxAckOfSynAckAt!!
        assertTrue("0x1D00 only after the delay (${firstAt - ackAt} ms)", firstAt - ackAt >= delayMillis - 20)

        // ---- control session order ----
        val ids = box.phoneMessages.map { it.second.messageId }
        assertEquals(
            listOf(0x1D00, 0x1D02, 0xAA00, 0xAA02, 0xAA05, 0x5702, 0x4E0A, 0x4E0B, 0x4E0D, 0x4E0E, 0x4300).map(::hex),
            ids.map(::hex),
        )
        val challenge = box.phoneMessages.single { it.second.messageId == 0xAA02 }.second
        assertEquals(
            "20-byte challenge",
            20,
            Iap2Csm
                .parseParams(challenge.body)
                .single()
                .value.size,
        )
        val transport =
            Iap2Csm.parseParams(
                box.phoneMessages
                    .single { it.second.messageId == 0x4E0E }
                    .second.body,
            )
        assertEquals("AA:BB:CC:DD:EE:FF", transport[0].value.decodeToString().trimEnd('\u0000'))
        val udid = transport[1].value.decodeToString().trimEnd('\u0000')
        assertTrue("UDID-shaped transport id: $udid", Regex("[0-9A-F]{24}").matches(udid))
        val available = box.phoneMessages.single { it.second.messageId == 0x4E0D }.second
        assertArrayEquals(byteArrayOf(1), Iap2Csm.parseParams(available.body).single().value)

        // ---- handoff ----
        assertEquals(1, credentials.size)
        assertEquals(SmartBoxCapture.SSID, credentials.single().ssid)
        assertEquals(SmartBoxCapture.PASSPHRASE, credentials.single().passphrase)
        assertEquals(36, credentials.single().channel)
        assertEquals(SmartBoxCapture.SSID, endpoint.ssid)
        assertEquals("no 0x4301 declared: endpoint left to mDNS/gateway", 0, endpoint.airPlayPort)
        assertTrue("link stays open after the bootstrap", channel.isAlive)
        assertTrue(logs.any { it.startsWith("ADAPTER: link rx SYN") })
        assertTrue(logs.any { it.startsWith("ADAPTER: link rx ACK of our SYN|ACK") })

        channel.close()
    }

    @Test
    fun secondWifiConfigurationDoesNotRepeatTheBurst() {
        val box = SmartBoxStream()
        val channel = Iap2LinkChannel(box, name = "smartbox", adapterMode = true)
        val session =
            Iap2SenderSession(
                channel,
                log = { },
                bluetoothMac = "AA:BB:CC:DD:EE:FF",
                adapterIdentificationDelayMillis = 0,
            )
        session.run()
        val pump = Thread({ session.pumpIncomingForever() }, "pump").apply { isDaemon = true }
        pump.start()
        // The box's second 0x5703 (sent after 0x4E0E) reaches the keep-alive pump
        Thread.sleep(500)
        val ids = box.phoneMessages.map { it.second.messageId }
        assertEquals(1, ids.count { it == 0x4E0A })
        assertEquals(1, ids.count { it == 0x4300 })
        assertEquals(1, ids.count { it == 0x5702 })
        channel.close()
        pump.join(2_000)
    }

    private fun hex(id: Int) = "0x%04x".format(id)
}
