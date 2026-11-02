package io.github.shaonialife.cardeck.airplay

import io.github.shaonialife.cardeck.bt.AdapterMode
import io.github.shaonialife.cardeck.iap2.Iap2LinkEngine
import io.github.shaonialife.cardeck.iap2.Iap2SenderSession
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Adapter-mode (smartBox) behaviour of [AirPlayClient]; everything else must stay unchanged. */
class AdapterModeAirPlayTest {
    @After
    fun tearDown() {
        AdapterMode.active = false
    }

    private fun client() =
        AirPlayClient(
            Iap2SenderSession.CarPlayEndpoint(
                ssid = "smartBox-136F",
                passphrase = "secret",
                channel = 0,
                securityType = 2,
                airPlayPort = 7000,
                deviceIdentifier = null,
                publicKey = null,
                sourceVersion = "320.17.6",
                ipAddresses = listOf("192.168.50.2"),
            ),
            log = {},
        )

    @Test
    fun controlChannelIapIsGatedUntilTheBoxSpeaks() {
        AdapterMode.active = true
        val client = client()
        assertFalse(client.allowsControlIapPost())
        client.onIapEventBytes(Iap2LinkEngine.MARKER)
        assertTrue(client.allowsControlIapPost())
    }

    @Test
    fun closingTheSessionClosesTheGateAgain() {
        AdapterMode.active = true
        val client = client()
        client.onIapEventBytes(Iap2LinkEngine.MARKER)
        client.close()
        assertFalse(client.allowsControlIapPost())
    }

    @Test
    fun otherReceiversAreNotGated() {
        AdapterMode.active = false
        assertTrue(client().allowsControlIapPost())
    }

    @Test
    fun disableBluetoothBodyNamesOurBluetoothMacUpperCase() {
        val body = BinaryPlist.encode(disableBluetoothCommand("aa:bb:cc:dd:ee:ff"))
        val root = BinaryPlist.parse(body) as Map<*, *>
        assertEquals("disableBluetooth", root["type"])
        assertEquals(mapOf("deviceID" to "AA:BB:CC:DD:EE:FF"), root["params"])
        assertEquals(setOf("type", "params"), root.keys)
    }

    @Test
    fun setupMacAddressDefaultsToTheLegacyValue() {
        val root = BinaryPlist.parse(client().buildSessionSetupBody("10:C1:97:1A:C7:44", 1234)) as Map<*, *>
        assertEquals("aa:bb:cc:dd:ee:ff", root["macAddress"])
        val adapter =
            BinaryPlist.parse(
                client().buildSessionSetupBody("10:C1:97:1A:C7:44", 1234, macAddress = "3A:1C:9E:0F:42:7B"),
            ) as Map<*, *>
        assertEquals("3A:1C:9E:0F:42:7B", adapter["macAddress"])
        assertEquals("10:C1:97:1A:C7:44", adapter["deviceID"])
    }

    @Test
    fun macAddressNormalisation() {
        assertEquals("3A:1C:9E:0F:42:7B", normalizeMacAddress("3a:1c:9e:0f:42:7b\n"))
        assertEquals("3A:1C:9E:0F:42:7B", normalizeMacAddress("link/ether 3a-1c-9e-0f-42-7b brd"))
        assertNull(normalizeMacAddress("02:00:00:00:00:00"))
        assertNull(normalizeMacAddress("00:00:00:00:00:00"))
        assertNull(normalizeMacAddress("cat: permission denied"))
        assertNull(normalizeMacAddress(null))
    }

    @Test
    fun hidSetInputModeIsSkippedOnlyInAdapterMode() {
        AdapterMode.active = true
        assertFalse(client().sendHidSetInputMode("1"))
    }
}
