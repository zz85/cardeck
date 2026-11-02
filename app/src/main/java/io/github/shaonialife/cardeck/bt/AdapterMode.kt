package io.github.shaonialife.cardeck.bt

/**
 * Wireless CarPlay *adapter* mode (Carlinkit / "smartBox" style boxes that plug into the
 * car's wired CarPlay port and accept wireless CarPlay from the phone).
 *
 * These boxes behave differently from the head units the rest of the app was tuned on
 * (Audi MIB3, BMW, DiPlay), per a working reference sender
 * (unapple-phone, field-tested against a smartBox-136F / Sony XAV-AX8100 identity):
 *  - The box is the RFCOMM **client**: it dials the phone's `...decacafe` service. It offers
 *    no `...decacaff` service of its own, so the outbound client must stay off (and must not
 *    disconnect the box's other profiles).
 *  - The box opens the iAP2 link: it sends the detect marker, then its SYN; the phone answers
 *    and echoes the box's first SYN parameters byte-for-byte, then waits ~1 s before 0x1D00.
 *  - Control session: 20-byte MFi challenge, 0x5702 right after 0xAA05, then after 0x5703:
 *    0x4E0A, 0x4E0B, 0x4E0D, 0x4E0E{BT MAC, UDID}, 0x4300.
 *  - AirPlay: the Wi-Fi iAP2 tunnel is receive-driven (posting first makes the box close the
 *    control connection) and the box waits for `disableBluetooth{deviceID = our BT MAC}`.
 *
 * [active] is decided once per connection by `SessionController.startConnection` (before the
 * Bluetooth listener starts) and read by every layer; everything else behaves exactly as
 * before when it is false.
 */
object AdapterMode {
    /** True while the current connection targets a wireless CarPlay adapter. */
    @Volatile
    var active: Boolean = false

    private val NAME_PATTERNS =
        listOf(
            Regex("smart\\s*box", RegexOption.IGNORE_CASE),
            Regex("car\\s*link", RegexOption.IGNORE_CASE),
            Regex("carlinkit", RegexOption.IGNORE_CASE),
            Regex("auto\\s*kit", RegexOption.IGNORE_CASE),
            Regex("cpc200", RegexOption.IGNORE_CASE),
        )

    /** Whether a Bluetooth device name looks like a Carlinkit-style wireless CarPlay adapter. */
    fun looksLikeAdapter(name: String?): Boolean = name != null && NAME_PATTERNS.any { it.containsMatchIn(name) }
}
