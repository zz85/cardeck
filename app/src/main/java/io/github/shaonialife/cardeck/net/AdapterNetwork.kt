package io.github.shaonialife.cardeck.net

import android.net.ConnectivityManager
import android.net.Network

/**
 * Link facts of the bound car network used by the wireless-adapter host fallback
 * ([EndpointHosts.chooseAdapterHost]). The smartBox network has no default route, so the
 * DHCP server address is the only reliable pointer to the box besides mDNS.
 */
internal object AdapterNetwork {
    fun dhcpServer(
        connectivityManager: ConnectivityManager,
        network: Network?,
    ): String? {
        network ?: return null
        return try {
            connectivityManager.getLinkProperties(network)?.dhcpServerAddress?.hostAddress
        } catch (_: Exception) {
            null
        }
    }

    fun defaultGateway(
        connectivityManager: ConnectivityManager,
        network: Network?,
    ): String? {
        network ?: return null
        return try {
            connectivityManager
                .getLinkProperties(network)
                ?.routes
                ?.firstOrNull { it.isDefaultRoute }
                ?.gateway
                ?.hostAddress
        } catch (_: Exception) {
            null
        }
    }

    /** DHCP server → gateway for [network] (no mDNS/invite input here). */
    fun fallbackHost(
        connectivityManager: ConnectivityManager,
        network: Network?,
        invitePeer: String? = null,
    ): EndpointHosts.AdapterHost? =
        EndpointHosts.chooseAdapterHost(
            mdnsAddress = null,
            dhcpServer = dhcpServer(connectivityManager, network),
            invitePeer = invitePeer,
            gateway = defaultGateway(connectivityManager, network),
        )
}
