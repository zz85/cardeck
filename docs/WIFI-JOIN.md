# Joining the head unit hotspot: silent command-line join + getting a handle without a specifier

> 2026-10-22. This doc records "why it's done in two steps", "why bindProcessToNetwork is still required",
> and several easy-to-hit framework/command pitfalls. Code: `SessionController.joinCarWifiSilently`
> and `net/WifiCarPlayConnector.bindCurrentWifi`.

## 1. Two join paths

| | Silent path (default) | App-specific network (fallback) |
|---|---|---|
| Trigger | Shizuku `cmd wifi connect-network` | `WifiNetworkSpecifier` + `requestNetwork` |
| System dialogs | **0** | One confirmation dialog the first time this AP is joined |
| Network ownership | System saved network (globally visible) | Visible to this process only (`Ephemeral`) |
| Needs Shizuku | Yes | No |
| Needs location permission | No | Required on Android 12/12L (13+ handled by NEARBY_WIFI_DEVICES) |
| Used for | Normal connections | When Shizuku is unavailable / the ROM blocks `connect-network` |

Both paths end with the same step: get the `Network` handle → `bindProcessToNetwork` → re-register ctrl mDNS.

## 2. Why we can't rely on `WifiNetworkSpecifier` alone

It ties "make the system connect to Wi-Fi" and "return a Network to the app" together: once the request carries a specifier,
`WifiNetworkFactory` goes through `isAccessPointApprovedForActiveRequest` before approving; when all approval sources
(CompanionDevice association, internal approved-AP list, shell pre-approval) are false, it **shows a system confirmation dialog**,
and there's no app-side API to turn it off.

Two common misconceptions:

- **The approval result is actually persisted**: approved APs are stored per package name in the wifi config store
  (`NetworkRequestStoreData`), so "the same hotspot" only prompts the first time.
- **Shell pre-approval is not possible**: `cmd wifi network-requests-set-user-approved <pkg> yes` can add
  the given package to `mApprovedApp` (after which specifier requests pass straight through without a dialog; but it's a single global value,
  so approving this app evicts other apps), but this command is **not in `WifiShellCommand.NON_PRIVILEGED_COMMANDS`**,
  and running it as uid 2000 throws `SecurityException: Uid 2000 does not have access to ...` (needs root).
  So Shizuku can't use it.

Conclusion: to achieve "still 0 dialogs after changing cars, reinstalling or clearing data", the only way is to bypass the specifier and let the system connect by itself.

## 3. Two practical pitfalls of the two-step approach

### 3.1 `connect-network` exit=0 doesn't mean it's connected

`WifiShellCommand.connect-network` just hands the config to `WifiService.connect(...)` and does `return 0`,
without even waiting for the `IActionListener`. So you **must** poll `cmd wifi status` until
`Wifi is connected to "<SSID>"` appears (disconnect old network → scan → associate → DHCP measured at 2~10s, longer on 5GHz).
The code uses a 25s window (`CAR_WIFI_JOIN_WAIT_MS`), which "Cancel connection" can interrupt immediately.

### 3.2 `removeCapability(NET_CAPABILITY_INTERNET)` isn't required

`NetworkRequest.Builder()` by default only has `NOT_RESTRICTED / TRUSTED / NOT_VPN`, and never had
`INTERNET`; requests use "subset matching", so declaring one fewer capability only widens the match and won't exclude networks without internet.
Writing it has no side effects; it's kept for semantic clarity. **What actually guarantees no dialog is "no specifier"**.

### 3.3 Verify the SSID with `cmd wifi status`, not `WifiInfo`

`WifiInfo.ssid` in `NetworkCapabilities.transportInfo` returns `<unknown ssid>` when precise location permission isn't granted (or location
services are off), and you also have to deal with `FLAG_INCLUDE_LOCATION_INFO` and API-version
branches; whereas "is the head unit hotspot the one we're currently connected to" is just a single shell query. We uniformly use
`shellWifiStatus()` (one `cmd wifi status` + regex), which needs no permissions and can also rule out, before
binding, the mis-bind of "the phone is still on the home router".

## 4. Why `bindProcessToNetwork` is still required

Intuitively "just passing the `Network` to NsdManager / `socketFactory` is enough"; this project can't do that, for three reasons:

1. **There's no per-network API for mDNS advertising**: `NsdManager.registerService(NsdServiceInfo, int,
   RegistrationListener)` has no `Network` parameter (an `AdvertisingRequest` overload only arrived in API 36;
   this project's compileSdk is 34). Registration follows the **process default network** — without binding the process, `_carplay-ctrl._tcp`
   goes out over cellular, the head unit never discovers the phone, and neither `GET /ctrl-int/1/connect` nor 0x4301 ever arrives.
   (On the discovery side newer APIs provide a `discoverServices(..., NetworkRequest, ...)` overload, but not on the advertising side.)
2. **Bare sockets throughout the project**: AirPlay control/events, video TCP, audio UDP, timing server,
   and the ctrl server are all `Socket()` / `DatagramSocket()`, relying on the process default network.
3. **It only affects this process**: `bindProcessToNetwork` applies to the calling process's sockets (same UID); it's not a system-wide
   setting. Navigation/music in the head unit's virtual displays are **separate processes** and keep using the default network (cellular), so they don't lose connectivity.
   The only real cost is that "CarDeck itself"'s internet requests (media artwork URLs) also go over the head unit Wi-Fi during the session —
   which is exactly the same as in the specifier era, not something introduced by this change.

## 5. `cmd wifi set-wifi-watchdog-enabled false` doesn't exist

AOSP `WifiShellCommand` only has:

```
set-wifi-watchdog <true|false>     # controls WifiLastResortWatchdog (self-recovery after repeated connection failures)
get-wifi-watchdog
```

Neither is **in** `NON_PRIVILEGED_COMMANDS`; shell (uid 2000) running them throws SecurityException,
only root can run them; and what it controls is "self-recover when it keeps failing to connect", not "disconnect when there's no internet". Turning off
"automatically switch away when there's no internet" depends on the OEM's smart network switching (Honor/Xiaomi/OPPO each have their own entry); there's no generic
shell switch. The project's current approach: **on failure, write the reason into the log and the hero card**, and let the user decide whether to switch to the manual path.

## 6. The cost of saving at system level (this is a design trade-off, not a bug)

- The hotspot becomes a "system saved network": it auto-connects next time it's in range — which is exactly why the second connection needs zero interaction;
  it disconnects automatically after leaving the car.
- The default network stays cellular: this Wi-Fi isn't `VALIDATED`, `Active default network` won't become it,
  and other apps keep using 4G/5G (system-level Wi-Fi ≠ default network).
- Some ROMs show "This WLAN can't access the internet" or even offer a "Switch back to mobile data" button; this is vendor
  UI and can't be turned off uniformly via shell.
- To undo: "Forget" the hotspot in the system WLAN settings, or
  look up the netId with `cmd wifi list-networks` and then `cmd wifi forget-network <id>`.

## 7. Log keywords for troubleshooting

```
WIFI: Silently joining head unit hotspot "..." (cmd wifi connect-network, 0 dialogs; currently ...)
WIFI: connect-network failed (exit=...)
WIFI: Switched to head unit hotspot "..."
WIFI: Phone already on head unit hotspot "..." — setting up network channel and binding directly
WiFi: Bound current WiFi network ... bindProcessToNetwork=true
WIFI: Connected to "..." but requestNetwork didn't call back — aborting this round (not falling back to the dialog-prone specifier path)
>>> Joining "..." as an app-specific network (only appears when the fallback is used)
```
