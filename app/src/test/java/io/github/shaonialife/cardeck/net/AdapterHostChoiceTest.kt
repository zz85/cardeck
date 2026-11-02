package io.github.shaonialife.cardeck.net

import io.github.shaonialife.cardeck.net.EndpointHosts.AdapterHostSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Adapter-mode AirPlay host fallback (smartBox: router-less DHCP network, SRV target `(none).local.`;
 * unapple-phone tests/fixtures/smartbox_local_20261026.json).
 */
class AdapterHostChoiceTest {
    @Test
    fun resolvedMdnsAddressWins() {
        val choice = EndpointHosts.chooseAdapterHost("192.168.1.101", "192.168.1.1", "192.168.1.2", "192.168.1.3")
        assertEquals(EndpointHosts.AdapterHost("192.168.1.101", AdapterHostSource.MDNS), choice)
    }

    @Test
    fun malformedSrvTargetFallsBackToDhcpServer() {
        for (bad in listOf(null, "", "(none)", "(none).local.", "(none).local", "0.0.0.0", "127.0.0.1", "not-an-ip")) {
            val choice = EndpointHosts.chooseAdapterHost(bad, "192.168.1.101", gateway = null)
            assertEquals("mdns=$bad", EndpointHosts.AdapterHost("192.168.1.101", AdapterHostSource.DHCP_SERVER), choice)
        }
    }

    @Test
    fun invitePeerThenGatewayWhenNoDhcpServer() {
        assertEquals(
            EndpointHosts.AdapterHost("192.168.1.101", AdapterHostSource.CTRL_INVITE),
            EndpointHosts.chooseAdapterHost(null, null, "192.168.1.101", "10.0.0.1"),
        )
        assertEquals(
            EndpointHosts.AdapterHost("10.0.0.1", AdapterHostSource.GATEWAY),
            EndpointHosts.chooseAdapterHost("(none)", "0.0.0.0", null, "10.0.0.1"),
        )
    }

    @Test
    fun nothingUsableYieldsNull() {
        assertNull(EndpointHosts.chooseAdapterHost(null, null, null, null))
        assertNull(EndpointHosts.chooseAdapterHost("(none).local.", "", "::", "::1"))
    }

    @Test
    fun ipv6LiteralsAreAccepted() {
        assertEquals(
            EndpointHosts.AdapterHost("fe80::1%wlan0", AdapterHostSource.MDNS),
            EndpointHosts.chooseAdapterHost(" fe80::1%wlan0 ", "192.168.1.101"),
        )
    }

    @Test
    fun strictAddressCheck() {
        assertTrue(EndpointHosts.isUsableAdapterAddress("192.168.1.101"))
        assertTrue(EndpointHosts.isUsableAdapterAddress("2001:db8::5"))
        // EndpointHosts.isIpv4 treats any dotted text as IPv4; the adapter check must not.
        assertTrue(EndpointHosts.isIpv4("(none).local."))
        assertFalse(EndpointHosts.isUsableAdapterAddress("(none).local."))
        assertFalse(EndpointHosts.isUsableAdapterAddress("192.168.1.256"))
        assertFalse(EndpointHosts.isUsableAdapterAddress("192.168.1"))
        assertFalse(EndpointHosts.isUsableAdapterAddress("10.174.189.1\u0000\u0000"))
    }

    @Test
    fun malformedSrvTargetDetection() {
        assertTrue(EndpointHosts.isMalformedSrvTarget("(none).local."))
        assertTrue(EndpointHosts.isMalformedSrvTarget("(none)"))
        assertTrue(EndpointHosts.isMalformedSrvTarget(null))
        assertFalse(EndpointHosts.isMalformedSrvTarget("Audi-MMI.local."))
    }
}
