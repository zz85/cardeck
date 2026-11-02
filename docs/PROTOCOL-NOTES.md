# Wireless CarPlay Sender Protocol Notes

> Project goal: an Android phone plays the iPhone (CarPlay sender) and connects to a CarPlay head unit (receiver).
> These notes combine four local reference repositories (`reference/`: DiPlay, xcertplay, catplay,
> carplay-wifi-extractor, all gitignored) with public reverse-engineering material.
> **The test-bench receiver actually used is DiPlay** (a derivative of xcertplay, `reference/DiPlay/`);
> most close code reading was done in xcertplay (`reference/xcertplay/`); the two codebases share a common origin and identical protocol logic.
> Items marked ✅ have been confirmed line by line in reference code; items marked ❓ are still to be verified.

## 1. Roles and Authentication Model

| | Receiver (head unit role) | Sender (iPhone role, this project) |
|---|---|---|
| Played by | Audi head unit / DiPlay (test bench) | This app |
| Needs an Apple-signed identity | ✅ Yes (MFi certificate/chip, verified by the phone) | ❌ No |
| Verifies the peer's identity | ❌ No (one-way authentication, confirmed by Oligo at DEF CON 2025) | Optional (skipped entirely in the test-bench phase) |

**Key conclusion**: iAP2 authentication is one-way — the phone verifies the head unit's MFi credentials (0xAA00-range challenge/response),
and the head unit fully trusts the verification result the phone reports. The sender therefore **needs no** Apple signing material at all;
the certificate DiPlay extracted from Carlinkit firmware is useless to a sender (it is a receiver identity).

## 2. Layering (Bottom Up)

```
┌────────────────────────────────────────────────────────────────┐
│ AirPlay main session: RTSP-style control + H.264/H.265 + HID   │  Phase 2
│   Transport: Wi-Fi TCP (head unit AirPlay port, from 0x4301)   │
├────────────────────────────────────────────────────────────────┤
│ mDNS/Bonjour: head unit advertises _airplay._tcp; phone        │
│   advertises _carplay-ctrl._tcp (head unit connects back here) │
├────────────────────────────────────────────────────────────────┤
│ iAP2 control session (CSM): 0x4040 framing + TLV params        │  Phase 1
├────────────────────────────────────────────────────────────────┤
│ iAP2 link layer: FF55 SYN / FF5A data frames + checksums       │  Phase 1
│   Transport: BT RFCOMM (wireless) / USB bulk (wired, excluded) │
└────────────────────────────────────────────────────────────────┘
```

## 3. Link Layer (✅ corrected after close reading of xcertplay Iap2LinkEngine.kt, 2026-10-08)

Data frame header (big-endian, 9 bytes + payload + payload checksum):

```
FF 5A | u16 total length | u8 control | u8 seq | u8 ack | u8 session ID | u8 header checksum | payload | u8 payload checksum
```

**Phase 1 on-device corrections (differences from earlier notes)**:
- The handshake is not an "FF55 control frame": both sides exchange the marker `FF 55 02 00 EE 10`, then send a sync packet
  (FF5A frame, control=0x80, payload = parameter negotiation struct `>BBHHHBB` + 3× session descriptors)
- **In wireless mode the phone initiates sync**: the accessory (openWireless, initiateNegotiation=false)
  only sends the marker and waits for the phone to send the sync packet first; each side ACKs on receiving a sync packet, and a negotiating side enters NORMAL on receiving the ACK
- **Session IDs are 10 (control) / 11 (EA) / 12 (file transfer)**, not the 0/1/2 from the Oligo notes
  (that may be the Bluetooth transport variant; the 10/11/12 that interoperates with DiPlay is authoritative)
- Sequence numbers are u8 and wrap, initial value 99; data frames use control=0x40 (piggybacked ACK)
- Control bits: SYNC=0x80, ACK=0x40, EAK=0x20, RST=0x10
- Checksum = two's complement of the byte sum (sum including the checksum mod 256 == 0), one each for header and payload
- Wireless parameter reference (DiPlay/xcertplay WIRELESS_LINK_CONFIG): maxOutgoing=4,
  controlSessionVersion=2, ACKs retained (wired uses zero-ack mode)
- Large CSM frames are fragmented according to the peer's negotiated maxLength and reassembled by the receiver's CSM parser

**Project code**: `app/src/main/java/.../iap2/Iap2LinkEngine.kt` (full state machine, loopback-tested)

## 4. CSM Framing and TLV (✅ xcertplay `iap2/wire/Iap2CsmFramer.kt`)

```
Frame:  0x4040 | u16 total length (incl. 6-byte header) | u16 message ID | body   (big-endian, no checksum)
Param:  u16 param total length (incl. 4-byte header) | u16 param ID | value     (body = sequence of params)
String: NUL-terminated UTF-8; nested param = param value is a sequence of sub-params
```

**Project code**: `app/src/main/java/.../iap2/Iap2Csm.kt` (frame encode/decode + incremental parser + TLV builder implemented)

## 5. Wireless Control Sequence (✅ xcertplay `Iap2WirelessControlClient.kt` + extractor README)

After the Bluetooth RFCOMM link is established (the two devices must first be paired at the system level):

```
Accessory → Phone: FF55 SYN                        (who initiates the link handshake ❓ to verify in Phase 1)
Phone → Accessory: StartIdentification      (0x1D00)
Accessory → Phone: IdentificationInformation (0x1D01)   head unit identity + list of sent/received message capabilities
Phone → Accessory: IdentificationAccepted    (0x1D02)
Accessory → Phone: MFi authentication sequence (0xAA00 range)  ← phone side can skip verification ❓ details still to read
Phone → Accessory: RequestAccessoryWiFiConfiguration (0x5702)   ← **mandatory**: all four independent
                                                   implementations declare it; send it even if the head unit omits it
                                                   (see §5b / §9j)
Accessory → Phone: AccessoryWiFiConfiguration (0x5703)   plaintext TLV: SSID/password/channel/security type
   (security type: 0=None 1=WEP 2=WPA/WPA2 3=WPA3 transition 4=WPA3)
Phone joins the head unit hotspot → mDNS resolves _airplay._tcp
Phone → Accessory: CarPlayAvailability      (0x4300)
Accessory → Phone: CarPlayStartSession      (0x4301)   wireless params + head unit IP + AirPlay port
                                                    + deviceIdentifier + public key + sourceVersion
Phone → Accessory: WirelessCarPlayUpdate    (0x4E0D)   announces wireless CarPlay is available
Phone → Accessory: DeviceTransportIdentifier(0x4E0E)   Bluetooth/USB identifier; accessory resends 0x5703 on receipt
Accessory → Phone: StartHID                 (0x6800)   Phase 2: touch back-channel
Phone → Accessory: DeviceHIDReport          (0x6801)   reply
```

0x1D01 identity parameter layout (the sender needs to parse it; field id → meaning):
0=name, 1=modelIdentifier, 2=manufacturer, 3=serialNumber, 4=firmwareVersion,
5=hardwareVersion, 6=list of sent message IDs (u16[]), 7=list of received message IDs (u16[]),
8=power source type, 9=max current (mA), 10=EA protocol group, 12=language, 13=language list,
16=USB transport component (wired), 17=Bluetooth transport component (wireless), 18=HID component, 24=wireless CarPlay component (incl. SSID).

## 5b. 0x5702 Is "Mandatory but Often Undeclared" — External Evidence from Four Independent Implementations (2026-10-22)

Background: 0.1.13 went from "connects" to "won't connect" on the Audi MMI 3315. 8b64147 made Wi-Fi credential
dispatch "skip the old protocol if 0x5702 is not in the head unit's receivable list", and Audi's sparse lists (sends 2 /
receives 5) don't include it. As a result, not a single 0x5702 was sent the whole round, the head unit's AirPlay service never came up
(port 7000 ECONNREFUSED throughout), and each of 4 sessions tore itself down after 8 seconds.

**All four independent implementations list 0x5702 in the "head unit can receive" list**, showing it is a mandatory item required by the spec:

| Project | Role | Evidence |
|---|---|---|
| catplay | Head unit (rx gadget) | `catplay_carplay_rx_gadget/src/iap2_session.rs`: `(identity.wifi_ssid.is_some(), &[RequestAccessoryWiFiConfigurationInformation::PACKET_ID])` |
| xcertplay | Head unit | `Iap2IdentificationClient.kt:315`: `WIRELESS_PHONE_MESSAGES = intArrayOf(0x4e0d, 0x4e0e, 0x5702)` |
| amineross/showcase | Head unit (jailbroken iPhone) | `msgs_recv[]` in `source/carplay_bt.m` includes `0x5702 /* RequestAccessoryWiFiConfigInfo */` |
| wiomoc/iap2 | Head unit (Python) | `msgs_recv` in `iap2/__main__.py` includes `RequestAccessoryWiFiConfigurationInformation` |

**Inference**: an absent 0x5702 only shows that "the list is incomplete"; it does not prove "unsupported". Real head units often omit it
(the Audi MMI 3315 does), yet they handle undeclared messages normally — in the same day's log, `0x4e0d/0x4e0b/0x4e0e`
were not in the list, and the head unit ACKed each of them (seq 107→ack=107, 110→ack=110, 113→ack=113).

Current dispatch only trusts `ReceiverProfile.wifiGen`: only `NEW` (both lists total ≥10 items and still no 0x57xx,
BMW iD9 style) skips 0x5702; `LEGACY` and `UNKNOWN` always send it, with a 1.5s wait window
(Audi responses over eight measured rounds: 122/136/166/166/198/246/266/368ms).

### The Two Send Points for 0x5703 (Before / After the Transport Notification)

`send_accessory_wifi_config(phase)` in `amineross/showcase` distinguishes pre-/post-transport:
`mfi_auth_succeeded → wireless_carplay_connecting_seen → transport_notification_seen
→ wifi_config_sent_post_transport` (resent up to 5 times). Its comments record a counter-example:

> On iPhone8,1 / iOS 15.8.3, the peer requested the Wi-Fi configuration **before** 0x4E0E, so the old code sent
> 0x4301 first, and then the network phase never started.

So the order itself is not necessarily fatal (in our 10-19/10-20 Audi logs 0x5703 arrived before 0x4e0d/0x4e0e
and things still worked), but **the timing sensitivity is real**. Our keep-alive pump re-answers a resent 0x5702
(the `wifi` cache in `Iap2SenderSession`), consistent with showcase's resend semantics.

### The Two Endpoint Discovery Flows (Apple's Official Description)

`docs/research/mhi2-native-wireless-2026-10/11_CONNECTION_FLOW_VARIANTS.md` in `harman-f/mhi2_altscreen_carplay`
summarizes Apple's two designs:

```
A. Classic flow (WWDC 2017):
   pairing → BT iAP2 (identification + MFi + transport identifier) → credential exchange → WLAN association
   → Bonjour browse/resolve → Controller Connect → session → iAP2 handoff → ACTIVE

B. Simplified flow (WWDC 2023, iOS 14+):
   exchange IP endpoint + TCP port directly in iAP2 → establish session directly → security + transport handoff → ACTIVE
   (**no Bonjour needed**, supports WPA3-only)
```

The same document stresses Apple's explicit warning: **after WLAN association and before the CarPlay/iAP handoff reaches the expected state,
Bluetooth/iAP2 must not be torn down**. This is exactly the theoretical basis for "self-teardown every 8 seconds = never receiving the invitation" —
in real iPhone recordings the head unit only sends `StartSessionHost` about 21 seconds after joining the network.

Corresponding implementation: the Audi MMI 3315's TXT `features=0x4040280,0x61` (bit37 CARPLAY_CONTROL
and bit38 HK_PAIRING both set), `srcvers=450.14.2`; in practice it uses a mix of A (mDNS) + B (0x4301).

### A Counter-Example Related to iOS 18 (Doesn't Apply to Audi, but Worth Knowing)

`amineross/showcase` deliberately **does not declare** 0x4301; its comments explain why:

> iPhone 16 Pro / iOS 18 rejects identification if 0x4301 is in msgs_sent.
> And 0x4300 cannot be added to msgs_recv — the iPhone validates that 0x4300/0x4301 are a mandatory pair,
> and rejects if 0x4301 is missing. So the whole 0x43xx path is blocked on iOS 18+.

Audi is not subject to this: its send list explicitly contains `0x4301` (log `head unit send list(2)=0x4301 0xea03`).
**On the contrary, it is willing to declare 0x4301 yet omits 0x5702** — further evidence that the list is incomplete, not that capabilities are limited.
When changing related code, don't treat showcase's "iOS 18 restriction" as a general rule for all head units.

## 6. mDNS/Bonjour (✅ xcertplay `network/CarPlayBonjour.kt`)

The **head unit side** advertises `_airplay._tcp`, with TXT record:

```
deviceid=AA:BB:CC:DD:EE:FF   features=0x44540380,0x61   flags=0x4
model=...  srcvers=...  protovers=1.1
pi=<pairing ID>  pk=<ed25519 public key hex>
```

The **phone side** advertises `_carplay-ctrl._tcp` (TXT: `id=<Bluetooth MAC>`); after resolving it the head unit **connects back** to that port
as the control channel (i.e. the carrier that moves the iAP2 control session from Bluetooth onto Wi-Fi TCP).
The test bench (DiPlay, inherited from xcertplay) prefers link-local IPv6 and falls back to IPv4 — the measured ctrl probe
source address is `fe80::…%wlan0`.

## 7. Bluetooth RFCOMM (✅ xcertplay `CarPlayController.kt:1591,1963`)

- iPhone (sender) listens on the SDP service: UUID `00000000-deca-fade-deca-deafdecacafe`
- Accessory UUID: `00000000-deca-fade-deca-deafdecacaff`
- Test bench (DiPlay) connection strategy: scan **paired** `PHONE_SMART` devices → connect to the UUID above one by one
- The test bench therefore only needs: the two phones paired at system level → this app listens → DiPlay on the spare phone connects back

## 8. AirPlay Main Session (Phase 2, Index Only)

- Reference implementation: catplay `carplay/catplay_carplay/src/carplay_tx/` (sender, 1959 lines) +
  `screen/tx/` (video sending session) + `rtp/record/` (sender-side RTP) + `pairing/pairing_helper_tx.rs`
- Encryption: ed25519 PairSetup/PairVerify (HomeKit style) + RTP AES/ChaCha
  (receiver reference implementation in xcertplay/DiPlay `airplay/PairSetup.kt`/`PairVerify.kt`/`Srp6a.kt`)
- Video: H.264/H.265, AVCC/AnnexB encapsulation conversion (catplay `video/`)
- Clock: NTP-style timing port + RTCP (catplay `clock/`)
- ❓ No public spec for FairPlay/stream encryption details; rely on catplay source + ludwig-v's decrypted firmware for comparison

## 8b. Audio Channel (✅ Implemented: Phone → Head Unit Main Audio Stream)

**Negotiation**: `SETUP` on the already-encrypted RTSP session, body = `streams:[{type:100 (main audio),
streamConnectionID, audioFormat:<bitmap>, audioType:"media"}]`; the receiver replies
`{type:100, dataPort:<UDP>, controlPort:<UDP>, streamConnectionID}`.
- Audio format bitmap (catplay `msg/streams.rs` AudioFormat): PCM 48k stereo = `1<<15`,
  AAC-LC 48k stereo = `1<<23`; the current implementation uses PCM 48k stereo (protocol baseline)
- Stream keys follow the same rule as video: `HKDF(shared, "DataStream-Salt<audio connId>", "DataStream-Output-Encryption-Key")`
- The audio stream ID is independent (uses its own connId) and does not affect video

**Wire format** (RTP over UDP; confirmed by the receiver DiPlay's `AudioStream.kt` decryption path):
```
[RTP header 12B][ChaCha20-Poly1305 ciphertext + 16B tag][8B little-endian nonce]
Header: v=2 | M=0 PT=<stream type=100> | seq u16BE | timestamp u32BE (cumulative sample frames) | ssrc u32BE
AAD = header bytes 4..12 (BE representation of timestamp+ssrc, catplay "modern AAD")
nonce (12B) = 4 zero bytes + trailing 8-byte little-endian counter (incremented per packet)
```
- 512 sample frames per packet (48000Hz stereo 16-bit = 2048B payload, ~2084B per packet;
  the receiver's UDP receive buffer is 4096B, safe)
- Capture: `AudioPlaybackCapture` (Android 10+, requires MediaProjection permission + RECORD_AUDIO)
- Currently one-way (media/navigation down to the head unit); microphone uplink (calls) not implemented

**Project code**: `audio/AudioSender.kt` (capture + packetization + UDP),
`airplay/AirPlayClient.runAudioStream()` (negotiation), unit test `AudioSenderTest` (decryption round-trip)

## 9. Real Car (Audi MIB3) Phase 3 Risk List

1. catplay's compatibility table records: Audi/Porsche HARMAN MIB2 "cuts power to and reboots the dongle after the role switch" —
   but that is the wired USB direction; the wireless direction is unknown
2. The user's head unit is MIB3 (2020 model year and later), with no public compatibility record — completely unknown
3. First pairing on a real car: "system-level Bluetooth pairing" must exist first; Audi head units usually require plugging in USB before opening wireless
   pairing (❓ key unknown: whether it can be triggered via Bluetooth alone. The DiPlay test bench has no such restriction)
4. WifiNetworkSpecifier does not support passing the password programmatically (entered in a system dialog) — even after 0x5703 provides the password,
   the network cannot be joined silently and the user must confirm every time (❓ impact to be measured in Phase 3)

## 9b. Audi MIB3 Field Notes (2026-10-09, Audi_MMI_6318 / MHI3_AS_AU_P3801)

The head unit identifies as `AUDI 3260 / DELPHI / CL37_MIB3H_AU_AS_G4x_2200910PROD`.
Bluetooth SDP advertises the accessory-side iAP2 service `…decacaff` (connectable right after system-level Bluetooth pairing, no USB trigger needed).

**Passed on device (after byte-by-byte comparison and fixes)**:

| Step | Result | Key points |
|---|---|---|
| RFCOMM both directions | ✅ | The head unit also connects back on its own (about every 60s); both directions work |
| Link negotiation | ✅ | The head unit sends SYN declaring session table `{1:control, 2:EA}` → the phone must **ACK + echo the table verbatim**; afterwards CSM uses **session 1** (sending on session 10 is silently dropped while the link layer still ACKs — extremely misleading) |
| Identification | ✅ | 0x1D01 received |
| MFi | ✅ | **20-byte challenge** (32B is silent but doesn't break the link); 0xAA03 signature 132B |
| Wi-Fi credentials | ✅ | 0x5702 → 0x5703 ssid=Audi_MMI_6318_5GHz ch=149 psk in plaintext |
| ctrl invitation | ✅ | After discovering the phone's `_carplay-ctrl._tcp` on Wi-Fi the head unit sends `GET /ctrl-int/1/connect` (deadlock key: the phone must bind Wi-Fi + advertise ctrl with the **real Bluetooth MAC** **immediately when 0x5703 arrives**) |
| mDNS endpoint | ✅ | Head unit `_airplay._tcp` comes up at 10.174.189.1:7000, TXT: `features=0x4040280,0x61 srcvers=320.17.7 flags=0x4 model=AUDI 3260 deviceid=54:64:DE:5E:51:B0` (**no pk field**) |
| AirPlay /info | ✅ | RTSP 200/455 (online) |
| pair-setup | ❌ 403 | The head unit does not accept anonymous SRP pairing (iAP2 has already established MFi trust) |
| pair-verify fast path | ⏳ | After 403, switch to "already paired device" pair-verify; to be tested |

**2026-10-11 retest addendum (two newly confirmed pitfalls)**:

1. **Audi's behavior on a 32B MFi challenge is inconsistent** — on 10-09 it was "silent, link stays up"; on 10-11 the
   head unit **tore down the link outright** ~0.4s after the 32B challenge (the 0xAA01 certificate, 912B, had already been received fine,
   showing the identification phase was entirely correct). If every session starts by hitting 32B, the link keeps dropping.
   Fix: `Iap2SenderSession` remembers "rejected lengths" and later sessions in the same process skip that length.
2. **Client thread leaks confuse the head unit** (on 10-11 we found 3 `iap2-bt-client` threads running concurrently):
   `BluetoothSocket.connect()` is a blocking call, and the old code only registered the socket after success, so
   `stop()` couldn't close a socket that was mid-connect → thread lingered; after the user hit "Start connection" again,
   the old thread revived because of a shared `running=true` → multiple RFCOMM sessions connected to the head unit concurrently,
   which showed up as random link teardowns / scrambled timing. Fix: generation counter (incremented on start/stop, stale threads exit themselves) +
   register the socket before connect (so stop() can close it and interrupt the blocking call).

**Differences between Audi and the test bench (DiPlay)**:
1. 0x4301 **varies by head unit model/firmware and cannot be generalized as "Audi"**:
   - **Audi MMI 6318 (the one fully working on 2026-10-09/11)**: does not send 0x4301 — the identification frame declares it can receive
     `0x4e0c 0x4e09 0x4e0d 0x4e0e 0x5702 0xea00 0xea01` and can send `0x5703 0xea03`;
     the AirPlay endpoint can only be found via mDNS `_airplay._tcp` over Wi-Fi (10.174.189.1:7000)
   - **Audi MMI 9310 (AUDI 5810 / DELPHI, user test on 2026-10-18)**: the identification frame
     **explicitly declares it will send 0x4301** (`head unit send list(2)=0x4301 0xea03`), but it actually arrives only
     **2 min 35 s** after Phase 1 ends (just as the head unit's CarPlay window opens); by then our 3s wait window,
     4s ctrl invitation window and 120s pairing window have **all expired**. If it's discarded as a heartbeat, the head unit waits 15s
     for nobody to connect and then tears down RFCOMM → the user sees "connects, then disconnects automatically".
     Also: this car's hotspot gateway is `10.174.189.254` (not .1), and gateway fallback port 7000 still gives Connection
     refused — whether the port differs is undetermined (mDNS never discovered `_airplay._tcp` during that round);
     the `iap2 0x4301 raw body` line in the next log can confirm whether it carries IPv4/port.
   - Conclusion: **0x4301 must be handled as a lifecycle event** (parse it and trigger pairing even when it arrives late),
     and timeout logs must not claim "this head unit doesn't send it".
2. The head unit **sends SYN itself** (the test bench waits for the phone); session table {1,2} and it must be echoed
3. **Dual-compatible MFi challenge length (important pitfall, hit on 2026-10-10)**:
   - DiPlay (Carlinkit AuthV3 EC identity): **accepts only exactly 32B**; on 20B it **drops the link immediately**
     (measured drop within 70~350ms; symptom: "can't connect to DiPlay + link drops during the challenge"); answers in ~0.2s
   - Audi MIB3: silent on 32B (link stays up) or tears down the link (both observed on 10-11), answers 20B instantly
   - Conclusion: the order must fall back **32B(3s) → 20B(6s) → 16B(3s)**. Trying 20B first instead kills DiPlay;
     Audi's 32B teardown variability self-heals via the "rejected length" memory (see the 2026-10-11 addendum above)
4. `pair-setup` gets 403 (the test bench accepts 3939 SRP)
5. The AirPlay port opens in a window (briefly open after the ctrl invitation is accepted; Connection refused when not invited)
6. **Do not drop the BT control session** (the test-bench hack relies on DiPlay rebuilding its stack; Audi tears down
   the entire AirPlay window after the link drops)

**Device identity (used by 0x4300/0x4E0E/ctrl TXT id)**: the real Bluetooth MAC must be resolved dynamically
(on Android 11+ the app layer only gets the placeholder `02:00:00:00:00:00`).
Read it from the Shizuku shell side via: `content query --uri content://settings/secure/bluetooth_address`.

## 9c. Audi MIB3 Fully Working (2026-10-11, Phase 3-C Acceptance)

iAP2 → AirPlay pairing → video streaming → touch back-channel → virtual display injection, interactive end to end.
The last batch of protocol differences cracked that day on top of §9b:

| Step | Root cause | Fix |
|---|---|---|
| pair-setup/pair-verify 403 | Pairing requests missing Apple identification headers | `Content-Type: application/pairing+tlv8` + `X-Apple-HKP: 2` (plus `X-Apple-PD: 1` for pair-verify); add Method=None(0)+State(1) TLVs to M1 |
| Screen stream SETUP 400 | ① timingPort=0 ② features proposed only videoPlayback (receiver's intersection is empty) ③ missing resource declaration ④ identity self-reported with the head unit's version | TimingServer (32B RTCP NTP, pt=0xD2 request/0xD3 reply, echoes t3 + local monotonic clock); full CONTROLLER_FEATURES list (uiContext/viewAreas/… 14 items total); send modesChanged before SETUP (resourceID 1/2 entity=1); UA/sourceVersion = iPhone self-reported 566.25.21 + osName/osBuild and all other fields |
| Session torn down after ~10s | Missing both heartbeats | keepAlive UDP 4B ping → head unit keepAlivePort (10s); POST /feedback with empty body (1s). Two independent watchdogs |
| No touch reports (1) | Wrong head unit command path | Commands must be sent as POST /command on the **event channel socket** (Events key, separate CSeq, fire-and-forget); a 200 on the RTSP control connection is a false acknowledgement |
| No touch reports (2) | HID not armed | After SETUP, send hidSetInputMode {hidInputMode:0, uuid} for each /info hidDevices[].uuid; **Audi's uuid is the string "0"/"1"** (DiPlay uses integers) |
| No touch reports (3) | The event channel is bidirectional RTSP | Reply to every incoming request immediately with RTSP/1.0 200 OK (encrypted); without replies → the head unit queues 3 and then drops the channel ("can swipe once") |
| Touch coordinates | Audi HID descriptor | uuid=1 eso_touchScreen: 5 bytes `[tip 1bit][X u16LE][Y u16LE]`, X≤1412/Y≤654 = absolute coordinates at screen resolution; uuid=0 is Consumer media keys (not touch) |
| Virtual iAP2 link | iAPSendMessage semantics | Event channel iAPSendMessage.data = link-layer byte stream; marker=DETECT, the head unit (client) resends every 200ms and sends SYN after the server answers DETECT once; a server resending DETECT knocks the client back to Detecting |
| 0x4E0E kills the BT link | Head unit accepts but doesn't ACK | sendControl(awaitAck=false) fire-and-forget; same change for 0x4300 |
| mDNS picks the wrong target | Phone sharing its own hotspot | Filter _airplay._tcp results by "local IP /24 prefix"; on mDNS failure fall back to the gateway (gateway = head unit, port 7000) |

**Debug-workflow pitfalls**: `adb install -r` doesn't kill the old process (old dex keeps running, fix "doesn't work");
NsdManager unregisterService is asynchronous (reusing the listener reports already in use; create a new one for each registration);
ctrl mDNS registration must happen **after** bindProcessToNetwork (multicast follows the process-bound network).

## 9d. WinPlay (Windows Receiver) Field Notes (2026-10-19)

WinPlay is an open-source Windows wireless CarPlay receiver (`reference/WinPlay`, GPL-3.0, Node + TS,
receiver role). A user connected to it using an Honor phone as the sender (log `logs/cardeck/xiaomi-winplay.log`):
iAP2 → Wi-Fi → mDNS → pairing all worked, stuck at the session-level SETUP.

**Root cause: the session-level SETUP's `deviceID` must equal the Bluetooth MAC of the iPhone selected on the receiver.**

- On startup WinPlay sets a filter from `cfg.targetBluetooth` (the paired iPhone the user selected on Windows):
  `stack.deviceFilter = id => normalize(id) === normalize(cfg.targetBluetooth)`
  (`reference/WinPlay/src/engine/run.ts:64`).
- If `dict.deviceID` in the session-level SETUP doesn't match (or is missing), it replies **403** immediately
  (`src/engine/protocol/cpStack.ts:696-699`); `RECORD` is likewise 403 before the device is accepted.
- We were previously sending catplay's hard-coded fake value `ff:ee:dd:cc:bb:aa`
  (the old comment "the head unit doesn't validate it" only holds for Audi/DiPlay).
- The status line `RTSP/1.0 403 OK` is WinPlay's fingerprint: its `STATUS_TEXT` table has no 403 entry, so
  `buildResponse` falls back to `OK` (`src/engine/protocol/rtspMessage.ts`). On seeing "403 OK",
  suspect device filtering first rather than a generic RTSP implementation difference.
- After the failure the symptom is "connects, then disconnects automatically after about 45 seconds": the sender stops sending requests, and the receiver waits for video until it times out
  (`armVideoDeadline` 45s) and tears down the link. The sender should actively close when SETUP is not 200 instead of waiting silently.

Compared with Audi/DiPlay: neither validates the SETUP deviceID, which is why this field went unnoticed for so long.
This project now reuses the **real local Bluetooth MAC** resolved by `DeviceIdentity` (consistent with 0x4300/0x4E0E);
the placeholder `02:00:00:00:00:00` is only a fallback when Shizuku isn't ready, and the 403 log
explicitly says "select this address on the receiver".

**Other WinPlay characteristics (useful to this project)**:

- Fixed TCP port 17000; `_airplay._tcp` TXT has the same shape as an iPhone's
  (`srcvers=950.7.1 features=0x44540380,0x61 flags=0x4`).
- The session-level SETUP only opens a keepAlive port when `keepAliveLowPower` is true.
- The event channel / stream SETUP / audio negotiation paths are essentially the same as DiPlay's (`_setupScreen`/`_setupAudio`),
  and stream keys are derived the same way via `DataStream-Salt<id>`, so it can serve as a third cross-check of receiver behavior.

**A bplist encoding bug fixed along the way**: `BinaryPlist.encodeInt` used to pick the width by **unsigned** range,
but bplist integers are **signed** — 35449 (0x8A79) was written as 2 bytes and parsed on the Apple side as int16
-30087. Ephemeral ports (timingPort/eventPort/dataPort often fall in 32768..65535) all hit this;
WinPlay reads with an unsigned BigInt so it's unaffected; whether Audi is affected is unknown (video used to work, but we can't
rule out that it ignored the wrong timingPort). Width is now chosen by the signed `Byte/Short/Int` ranges,
with regression tests covering 0/127/128/255/256/32767/32768/35449/65535/65536.

## 9e. Audi MMI 3315 Session Torn Down at 10 Seconds: All Three Heartbeats Are Required (2026-10-19)

User log `logs/cardeck/CarDeck-log-20261019-081700.txt`: iAP2 → MFi → hotspot →
pairing → SETUP → RECORD → video streaming steadily at 30fps, **then the whole session is reset 10.17~10.23s after RECORD**;
the timing of two rounds matches to the millisecond (not a random network problem).

**Root cause 1: the UDP keepAlive interval equals the head unit's watchdog.** We sent one every 10s (catplay
`KeepAliveClient::PING_INTERVAL`), and the head unit's watchdog is also 10s. In the log ping #2 is
exactly 10.001s after #1, followed by teardown within 213~247ms — millisecond jitter in `Thread.sleep` makes the heartbeat always arrive 1ms
past the deadline. catplay's 10s only works for its own receiver; real cars need headroom.
Changed to **5s** (`AirPlayClient.KEEPALIVE_INTERVAL_MS`).

**Root cause 2: missing in-stream keepAlive frames on the screen stream.** catplay writes a keepAlive frame to the
screen data port **every second even while video is streaming continuously** (`ScreenTransmitSession::create_keep_alive`,
`DEFAULT_KEEP_ALIVE = 1s`) — it is not a backup for video traffic but an independent liveness
signal for the receiver's screen. We had never sent one. Frame format (`reference/catplay/.../screen/screen_frame.rs`):

```
128B plaintext header: u32LE body length | u8 opcode | 3B small params | 15 × 8B params
opcode 2 = KeepAlive (empty body)
opcode 5 = KeepAliveWithBody (when /info.keepAliveSendStatsAsBody=true;
           body = bplist{txUsageAvg, encoderCurrentFPS, sentFramesAvg,
                         queuedFramesAvg, lossAvg},
           last 4 bytes of params[14] = body length as f32 LE)
```

**keepAlive frames are plaintext** — catplay's `ScreenFrameCodec::encode_composite` only encrypts
`opcode == VideoFrame`, so keepAlive doesn't consume a ChaCha frame nonce. We implement it the same way,
sending it throttled to 1s inside `VideoSender`'s encoder output loop.

**The three heartbeats are independent**: UDP keepAlive (5s, keepAlivePort) + RTSP /feedback (1s,
control channel) + screen stream keepAlive frame (1s, video data port). Previously only the first two were implemented;
the "add both heartbeats" recorded in `docs` §9c was only sufficient on the MMI 6318.

**Audio capture all zeros (unresolved, unrelated to the teardown)**: `AUDIO: capture samples nonzero 0% peak=0` two rounds in a row.
Candidate causes by likelihood: no media was playing at the time (only one old entry before the session), audio was routed to the head unit's Bluetooth
A2DP (PlaybackCapture cannot capture Bluetooth output), or the target app disallows playback capture.

**Per-head-unit MFi challenge length differences (additional on-device findings)**: the MMI 3315 is **silent for 6.0 seconds** on 20B
(sent at 15.605 → judged unanswered at 21.604), while 32B returns a signature in 0.64 seconds (0xAA03 received at 22.247).
Those 6 seconds are a fixed cost on every connection unless the preference is remembered per car. Now implemented as
`MfiLengthMemory`: keyed by the 0x1D01 identity (name+model+manufacturer), it persists three outcomes —
"success / silent / rejected" — and a successful length is tried first on a hit; the retry window for a silent length is reduced to 1.5s.
Note this doesn't contradict §9b/§9c — **head units of different model years/firmware have opposite preferences**: the MMI 6318
accepts 20B and tears down the link on 32B, so the baseline order is still chosen by receiver type, and the memory only overrides it.

## 9f. Older Head Units (CarPlay 210.81 / Audi 4xxx Firmware) Won't Connect: Six Differences Aligned with catplay (2026-10-19)

Users reported "Audi software versions starting with 4 (e.g. 4851) won't connect". This generation of head unit corresponds to the
**pre-HomeKit receiver** in catplay's classification (README compatibility table: Audi/Porsche ~2020 HARMAN MIB2/MHI2, i.e.
CarPlay 210.81). After checking item by item against catplay upstream (our local copy stopped at 9-30; this time we pulled up to v0.17.0, 39 commits),
this project differed from it in six places, all fixed to follow catplay:

**① The bplist root object must be at byte 8 (catplay 9ad3a7e)**. CarPlay 210.81 doesn't read the trailer's
topObject and **takes the root directly at offset 8**; our encoder encodes child objects first and appends the root container last, so the root was at
the end by default. The symptom is that the head unit receives a valid bplist but can't parse a dictionary out of it (`SETUP plist decode failed` / 400).
Fix: `BinaryPlist.encode` now always ends with a `makeRootFirst` reorder and patches the offset table accordingly (same algorithm as catplay);
the unit test `StreamFrameCipherTest.encodedPlistRootObjectStartsAtByteEight` locks it in using a multi-byte offset table with >255 objects
(small plists use a single-byte table and can't catch the bug).

**② The initial SETUP request URI uses the relative path `/setup`** (same as catplay `initial_setup`).
The old implementation sent the absolute `rtsp://host:port/`. Old firmware is sensitive to absolute URIs.

**③ Unpaired sessions must carry `et=1` (NONE), and the screen stream uses AES-128-CTR with an all-zero key**.
catplay `ScreenFrameCodec::new` comment: "There was never support for unencrypted screen
sessions. ET = None maps to AES session where session_key before derivation is all
zeroes." I.e. `et=NONE` ≠ plaintext video; it is **AES-128-CTR + a 16-byte all-zero session key**,
with key/IV = `SHA512("AirPlayStreamKey"/"AirPlayStreamIV" + connId(decimal) + sessionKey)[0..16]`.
The event channel is also **plaintext** in unpaired sessions (catplay `EventsServer::unencrypted`).
With `et=NONE` there is no shared key to derive the DataStream output key for audio, so audio is skipped.

**④ Two encryption modes for screen data frames**. Paired (pair-verify) → ChaCha20-Poly1305;
unpaired → AES-128-CTR (no tag, length unchanged, the keystream is **continuous across frames** — creating a new instance per frame turns
everything from the second frame on into garbage). Only `opcode=VideoFrame` is encrypted; keepAlive/config are always plaintext.

**⑤ The MTU for encrypted control-channel frames is 1440** (catplay `AIRPLAY_CIPHER_MTU_MAX`):
max plaintext per frame = 1440 - 2B AAD - 16B tag = **1422**. The old implementation's 0x4000 would be treated by strict head units
as a protocol violation (catplay's decode side does `len > 1422` → `PayloadTooBig` outright).

**⑥ The "only IPv4 addresses count" check was too narrow**. Audi 4xxx `_airplay._tcp` / 0x4301 often only provide an
**IPv6 link-local** address (`fe80::…%wlan0`). The old code used `contains('.')` everywhere to decide "is there an address",
so these endpoints were judged incomplete → pointless wait for the ctrl invitation → fall back to "gateway:7000" (the 4xxx gateway isn't necessarily
`.1`, nor the port necessarily 7000) → never connects. Everything now goes through `net/EndpointHosts`:
IPv4 first → global IPv6 → link-local IPv6 (with scope added), and **the video/audio/event channels reuse the address
that pairing actually connected to** (`AirPlayClient.activeHost`) instead of each going back and picking IPv4.

**Related: use TXT features to identify the head unit's era**. `_airplay._tcp`'s `features=low32,high32`,
bit38 = `HK_PAIRING_AND_ENCRYPT`. Older head units don't declare this bit → go straight to `skipPairing` and a plaintext session,
instead of burning a 120s pairing window (which is exactly what "never connects" felt like). Missing TXT / parse failure is always
treated as "unknown" and keeps the old path — never a false downgrade. If the plaintext SETUP is still rejected (misjudgment), pairing is
done once more (catplay `handle_setup_result`'s crypto upgrade path, with the window reduced to 30s).

**Aligned along the way**: the TXT `srcvers` of `_carplay-ctrl._tcp` changed to the sender version (566.25.21;
previously mistakenly the head-unit-side 950.7.1), service name iPhone; also advertise `_device-info._tcp` (model=D84AP,
catplay `DeviceInfoBonjourEntry`); `GET /info` carries an empty bplist dictionary body + plist Content-Type.

## 9g. Audi 4851 Real Car "Connected but the Head Unit Doesn't React": VideoConfig Frame Header Missing Two Fields (2026-10-19)

A car owner's log `logs/cardeck/CarDeck-log-20261019-101353-all.txt` (Xiaomi 24129PN74C /
Android 16, app **0.1.4**; head unit `Audi_MMI_3315` / `AUDI 5163` / DELPHI,
`srcvers=450.14.2`, CarPlay `name=CarPlay`). The analysis has two parts:

**A. In this log the session really is reset at 10.2 seconds every time** (reproduced in all six rounds, same root cause as §9e):
event channel established → exactly +10.2s `EVENT: read ended - Connection reset`. That version lacked
screen-stream keepAlive frames and the UDP keepAlive was still 10s (equal to the watchdog). Both were fixed in
**0.1.6** (§9e), so nothing more to change this round — but the owner must upgrade to 0.1.6 to get a complete session.

**B. The direct cause of "app shows connected, head unit doesn't react": the VideoConfig frame header is missing fields.**
Before 0.1.6 the implementation only wrote `body_size` + `opcode` when sending VideoConfig, whereas catplay's
`ScreenFrame::config` writes two more items; strict decoders like Audi's show **a completely black screen** without them:

| Field | Header position | Value | Purpose |
|---|---|---|---|
| `small_param[1]` | offset 6 | `ScreenFlag::Encrypted (0x04)` | Declares that subsequent VideoFrames are encrypted; without it they are decoded as plaintext NALs → decode failure |
| `params[1]` | offset 16 | `(width, height)` as two **f32 LE** | The head unit uses this to set up the decoder canvas size |
| `params[0]` | offset 8..16 | all 0 | config carries no PTS (only VideoFrame writes it) |

**Why the test bench never exposed it**: DiPlay's `ScreenStream.onMessage` only reads `body` (opcode 0/1)
and takes the resolution from avcC; WinPlay's `screenStream.ts` likewise only looks at opcode and body — neither validates
these header fields. A real car (Audi DELPHI) builds its decoder from the header fields and goes black without them, while **session, touch and
heartbeats are all normal** and the log contains no error lines at all — the hardest kind of problem to track down.

**Relationship between A and B**: A keeps the session from surviving past 10 seconds (the user sees "flashes, then drops"); B makes the screen black
even while the session is alive (the user sees "shows connected but the head unit doesn't react"). They are independent and both must be fixed.

**Diagnostic log (added this time)**: `VIDEO: ✓ VideoConfig sent … flags=0x07 1412x654` is followed by
a line with the first 24 bytes of the header in hex — the next report can be checked directly for whether these fields are correct.

## 9h. BMW 87866 Stuck in iAP2 Link Negotiation: Duplicate SYN-ACK Sequence Number (2026-10-19)

Log `logs/cardeck/CarDeck-log-20261019-103436.txt` (OnePlus PKR110 / Android 16,
app 0.1.6, head unit `BMW 87866` / MAC `9C:F5:5F:D0:44:09`). **All 14 sessions stuck at the link layer**:
`iap2 link ready` 0 times, `0x1D00` sent 0 times, `0x1D01` received 0 times.

**Frame statistics (decisive evidence)**:

| Direction | Frame type | Count |
|---|---|---|
| BMW → phone | `0x80` SYN | **133** |
| BMW → phone | `0x40` ACK / data | **0** |
| Phone → BMW | `0x40` bare ACK | 350 |
| Phone → BMW | `0xC0` SYN\|ACK | 14 |

**Root cause 1: SYN-ACK reuses the SYN's sequence number.** Raw bytes:

```
Our SYN    : ff 5a 00 1d 80 63 ...      seq = 0x63 (99)
Our SYN|ACK: ff 5a 00 1a c0 63 a1 ...   seq = 0x63 (99)   ← collision
```

`sentSequence` is only incremented when `drainSendWindow()` sends data frames, never during negotiation. The peer validates the reply
against `SYN.seq + 1` (catplay `verify_neg_seq_ack`: `packet.seq == peer_seq + 1`),
so the same sequence number is judged a duplicate frame and dropped. catplay's `NegotiationTx::SynAck` increments via `take_own_seq()`.
Fix: `sendSynchronizationEcho` consumes a new sequence number; **byte-level resends of the same SYN remain idempotent**
(replay the previous frame without advancing the sequence — in DiPlay tunnel mode the same SYN is resent every 500ms).

**Root cause 2: only the first SYN got a SYN|ACK.** The old implementation did `if (firstAdoption) sendSynchronizationEcho(...)`,
and every subsequent SYN only got a bare ACK. But BMW **reopens negotiation with a new sequence number on every retry** (161→191→40…;
a single round in the log received up to 36 SYNs), so it never got the echo it wanted. catplay's `on_recv_neg_host`
sends SYN+ACK for every SYN. Fix: **reply with one SYN|ACK frame to every pure SYN (without the ACK bit)**.

**Pitfall: a bare ACK collides with SYN|ACK.** The bare ACK uses the current `sentSequence`, while SYN|ACK increments it,
so the next bare ACK reuses the previous SYN|ACK's sequence number. Hence the host path now **only replies with SYN|ACK** (it carries its own
ACK bit and ack field, so the bare ACK was redundant anyway; catplay's host path also only sends SYN+ACK).
Cost: the measured Audi frame order "bare ACK + SYN|ACK" becomes a single SYN|ACK frame — still protocol-compliant,
but **needs a retest on Audi to confirm** (the final ACK will be 100 instead of 99; a one-off sequence offset is normal).

**Pitfall: you can't reply SYN|ACK to every frame with the SYN bit set.** The first version didn't distinguish SYN from SYN|ACK,
and two engines feeding each other formed an infinite ping-pong (unit test `windowBacklogFlushesAfterAcknowledgement` hung
outright, spinning the CPU for 55 seconds; only `jstack` revealed it). The criterion must be `control and ACK == 0`.

**Secondary issues (not changed this round, suggested for follow-up)**:
- Outbound client and inbound session run in parallel: 16× "BMW is Bluetooth-connected (HFP/A2DP) — disconnected";
  the 3-second retry thread keeps tearing down HFP/A2DP, which from the car's side looks like Bluetooth being continuously disturbed;
- With no target specified it also tries Bluetooth earbuds (`HUAWEI FreeBuds 4` / `FreeClip 2`) in turn; candidates should be limited to
  devices that declare a CarPlay/iAP2 service;
- A bare ACK every 500ms (350 of them), while BMW's LSP declares `ackTimeout=3000ms`; sending at the peer's value is enough.

## 9i. 0.1.7 Regression on Audi MMI 3315: ctrl Advertisement Identity Fields Changed (2026-10-19)

Log `logs/cardeck/CarDeck-log-20261019-141711.txt` (Xiaomi 15 / Android 16, 0.1.7;
the same car and phone connected fine under 0.1.6). Symptom: **iAP2 fully working, yet the head unit never opens the AirPlay window**.

**What worked**: RFCOMM → `iap2 link ready` → 0x1D01 → MFi (32B, preferred from memory, 0.58s)
→ 0x5703 (`Audi_MMI_3315_5GHz` ch149) → joined the app-specific network. The head unit's iAP2 layer was alive
(it later sent 0xAE00).

**Where it stuck (all four indicators zero)**:

| Check | 0.1.7 (this run) | 0.1.6 (works on the same car) |
|---|---|---|
| 0x4301 arrives | **0 times** | **0.65s** after 0x4300 |
| Head unit `GET /ctrl-int/1/connect` | **0 times** | Yes |
| mDNS `_airplay._tcp` | **0 times** | `CarPlay -> 10.174.189.1:7000` |
| Gateway `:7000` | 54s of `ECONNREFUSED` throughout | Connectable immediately |

**Root cause: I changed the ctrl advertisement's TXT `srcvers` and instance name to match catplay.** 0.1.6 used
`name=sender` + `srcvers=950.7.1` (accepted by the head unit); 0.1.7 changed them to `name=iPhone` +
`srcvers=566.25.21` (catplay's sender SDK version), and the head unit **stopped even probing**.
These two items are the only phone identity the head unit can see on Wi-Fi; changing them is equivalent to swapping in a different device.
→ Reverted to the 0.1.6 values, with test `CarPlayCtrlAdvertiserTest` pinning them (don't
"copy from catplay" again in the future).

**Also removed the `_device-info._tcp` advertisement**: Android's `NsdServiceInfo` doesn't accept port=0,
and on device it reported `The port is missing` every time (4 times in the log). It was an additional advertisement with unverified benefit,
and failures only produce noise — removed rather than fixed, because there's no evidence this car needs it.

**Other issues fixed along the way**:

1. **0x4300/0x4E0E were using a placeholder MAC**: throughout this log,
   `device status greeting … mac=02:00:00:00:00:00`. `DeviceIdentity` has a 10-second quiet
   window after a resolution failure, and the BT session is often constructed in the same millisecond Shizuku becomes ready — after the first failure it returned the
   placeholder for the next 10 seconds (in the 0.1.6 log, rounds 2 and 3 had the real MAC `5C:40:71:A4:0A:90`). Audi checks
   0x4300's transport identifier against the RFCOMM peer, and the placeholder leads to a silent drop.
   → `BtSession` now resolves with `forceRetry` at session start and logs the result.
2. **The ctrl advertisement's TXT was never logged**: so this case couldn't be attributed directly (only by comparing versions).
   → Every registration now prints `name= / id= / srcvers=`, with an extra ⚠ for a placeholder MAC.
3. **No more announcing or mDNS lookups during the fallback phase**: after the 4s invitation window timed out, it just spun against the gateway for 54s.
   → `AirPlayClient` adds `retryTick` (every 10 retries ≈ 20s): force_announce ctrl again,
   and log "head unit invitation count + whether mDNS discovered it".

**Methodology lesson**: moving "the reference implementation's values" straight into production can cost an entire round of the real car being unusable.
Any field that affects **how the head unit identifies us** (TXT, service name, sourceVersion, deviceID)
must first be A/B-tested on a real car, and the change must be attributable at a glance from the logs.

## 9j. BMW iD8.5 (MGU-86) Tears Down the Link 5.4s After Taking 0x5702: Greeting Messages Sent Too Late (2026-10-23)

Log `F:\Downloads\CarDeck-log-20261021-202434.txt` (**the user provided two copies with identical SHA-256**;
0.1.12, Xiaomi 2106118C / Android 14). Head unit `BMW 31726` / `MGU-86-BMW` (iDrive 8.5).

**Symptom**: all 6 sessions failed, alternating between two errors —
`SESSION failed: no head unit hotspot credentials…` and `SESSION failed: iAP2 link closed while sending 0x4e0d`.

**What worked** (true in every round): RFCOMM **connected in by the head unit** (our outbound `BT-C` failed every 3s) →
link SYN/SYN|ACK negotiation → `0x1D01` identity (BMW Group/CinemoHIDComponent) → MFi
(`0xAA01` 611B certificate, `0xAA02` 32B challenge, `0xAA03` 68B signature, all round trips successful).

**Key structure: the head unit alternates between two capability profiles** (switching almost every time):

| Profile | Send list | EA protocol | 0x5702/0x5703 |
|---|---|---|---|
| A (14 items) | No 0x5703 | `de.bmw.a4a.bt` protocol=7 | receive=no / send=no |
| B (16 items) | Includes 0x5703, 0xaa06 | `com.bmw.carplay` protocol=8 | **receive=yes / send=yes** |

Only profile B has wireless CarPlay capability (it also has an extra identity record `BMW 31726 Wifi`). Under profile A
we skip the old protocol per the capability list and go 0x4300/0x4301 — but this car **neither declares nor sends 0x4301**, so
profile A is doomed to fail. The one that should succeed is profile B.

**Profile B's dead point (millisecond timeline, consistent across two rounds)**:

```
T+0.9s  tx 0xea00 startEASession protocol=8 com.bmw.carplay     ← head unit doesn't respond
T+2.9s  tx 0x5702 (empty body)                                   ← we request the Wi-Fi config
T+3.0s  rx link ACK (len=0)                                      ← after this the head unit sends not one more byte
T+8.3s  LINK closed: bt socket closed, read return: -1           ← peer tears down the link
T+8.3s  sending 0x4e0d failed → SESSION failed
```

The gap is consistently **5.39~5.45s after 0x5702**, and 0x5703 never arrives.

**Decisive evidence: under the wireless profile the device status greetings were never sent.** In the entire log, `tx=0x4e0*` only appears in
profile A rounds; whereas the real iPhone's order (§12 step 4) is **an immediate burst right after 0xAA05** of
`0x4E0C/0x4E0A/0x4E0B/0x4E09…`, and only then subscriptions/requests. Our code scheduled the greetings
`after the 0x5703 wait` (in `Iap2SenderSession`: Wi-Fi exchange → greetings → 0x4300),
so BMW was waiting for greetings while we waited for 0x5703; after 5 seconds the head unit decided the accessory wasn't ready → tore down the link.

**A second log (sent by the user on 2026-10-22, `CarDeck-log-20261022-135133.txt`) proves this is reproducible**:
`BMW 17713` / `MGU-86` (another iD8.5), Xiaomi 23127PN0CC / Android 16, app **0.1.17-debug**.
**5 sessions → 5× tx 0x5702 → 0× rx 0x5703 → 5× LINK closed**, each round being
`0x5702 → link ACK only → head unit tears down RFCOMM 5.37s later → sending 0x4e0d fails`.
This car **had a 16-item list every round (always declaring it sends 0x5703)**, with none of the A/B profile alternation from the first log —
showing the head unit was earnestly attempting wireless CarPlay all along; we simply didn't respond in the order it wanted.
(Incidentally: 0.1.17-debug is newer than the local HEAD's 0.1.13, so the build came from another branch/upstream, but the failure signature is identical.)

**Third piece of evidence: the head unit declares it sends `0xEA03` (EA session status), but never sent one from start to finish.**
`0x1D01`'s send list contains `0xea03` and its receive list contains `0xea00`, showing it supports EA sessions;
yet the `0xEA00` body we sent was:

```
param 0: id=0, u8  = protocolId          ← correct
param 1: id=1, u16 = protocolId          ← wrong: the spec wants the **EA protocol name string** here
             i.e. "com.bmw.carplay"
```

The old code sent param 1 as an integer protocol number (while the log printed `name=com.bmw.carplay`,
so the problem was completely invisible from the log). The head unit received a malformed EA request → didn't reply `0xEA03` → the CarPlay data channel
never opened → the link was torn down after the 8-second budget ran out. Audi's EA protocol likewise never replied `0xea03`, showing this bug
has always existed for every head unit; Audi just doesn't rely on it to make progress.

**Remedy (changes this round; Audi unaffected)**:

1. **`0xEA00` param 1 now sends the protocol name** (`Iap2Csm.string(1, name)`), then probes 800ms for
   `0xEA03` and logs it — whether it replied is visible at a glance;
2. Send the device status greetings **immediately after `0xAA05`** (the real iPhone order; previously after the 0x5703 wait);
3. Wait for `0x5703` in segments: not arrived after 1.5s → **force a resend of the greetings** (`force=true`);
4. Not arrived after another 1.3s → resend `0x5702` once;
5. Then keep waiting with the original window; on timeout still go 0x4300/0x4301.

Greetings are still sent "once per round" in total (`greetingsSent` is idempotent; only force resends); Audi returns
0x5703 within milliseconds, so neither rescue path is reached and behavior is unchanged.

**Still unresolved** (if the next real-car test still fails, investigate in this order):

1. Whether `0x5702` must carry parameters (we send an **empty body**; Audi accepts an empty body, BMW may not) —
   pinning down the parameter IDs needs a **real iPhone packet capture on the same car**;
2. The link has **no periodic keep-alive** in NORMAL state (`Iap2LinkEngine` only sends SYN during negotiation); whether 5s of complete
   RFCOMM silence within the wait window triggers BMW's idle teardown — if ① doesn't help, add a 1s bare-ACK keep-alive next round;
3. Our outbound `BT-C` connection to the head unit's iAP2 service fails every 3s (we rely entirely on the head unit connecting back); the SDP channel selection is worth re-checking.

## §9j 0.1.13 Audi MMI 3315 Won't Connect: Capability List Used as a Hard Gate + Self-Teardown Every Round (2026-10-22)

Log: `CarDeck-log-20261022-185112.txt` (0.1.13, Xiaomi 15 / Android 16 / Audi_MMI_3315_5GHz).

### Symptom Chain (Millisecond by Millisecond)

```
18:47:59.934  0x1D01: Audi_MMI_3315 / AUDI 5163 / DELPHI
              send list(2)=0x4301 0xea03   receive list(5)=0x4e0c 0x4e09 0x4300 0xea00 0xea01
18:48:00.634  ⚠ "head unit does not declare support for 0x5702/0x5703 — skipping old protocol"
              → [tx=0x5702] appears 0 times in the entire log
18:48:03.72   0x4301 arrives once (with SSID/PSK/fe80::900c:…/10.174.189.1:7000)
18:48:05→49:29  specifier join: no callback for 84 seconds → onUnavailable
18:49:37.885  second join succeeds (1.9s, bindProcessToNetwork=true)
18:49:37→51:07  45 connections to 7000 all ECONNREFUSED; _airplay._tcp not discovered; head unit invitations = 0
from 18:49:55, 5 rounds  each round 0x1D00→0x1D01→MFi→0x4300 → no 0x4301 for 8s
                 → "SESSION failed: no head unit hotspot credentials" → self-teardown
```

### Comparison with Older Versions (Same Car, Same Hotspot)

| Log | 0x5702 | Result |
|---|---|---|
| `CarDeck-log-20261019-101353-all.txt` | Sent (every round) | 0x5703 replied in 122~368ms; 30ms after joining, `TCP connected 10.174.189.1:7000`; SETUP/RECORD all working |
| `CarDeck-log-20261020-103031.txt` | Sent (every round) | Same as above, `0x5703` 166ms, joined in 1.9s, port available immediately |
| `CarDeck-log-20261022-185112.txt` (0.1.13) | **0 times** | Port 7000 **RST throughout**, all 4 rounds failed |

**Key inference**: this Audi must receive 0x5702 before it brings up the AirPlay service. Without it, the head unit only emits
a single isolated 0x4301, then goes silent for the whole round with nothing listening on the port.

### Two Root Causes

1. **Capability list used as a hard gate**: `legacyWifiExchange = declaresReceiveWifi != false && declaresSendWifi != false`.
   Audi provided a list without 0x5702 → skipped. Yet the `PROFILE` line in the same log computed
   `wifiGen=UNKNOWN` (with the reason "conservatively use old protocol") — **the two decisions weren't wired together**; the log said one thing and the code did another.
2. **Self-teardown every 8 seconds**: when `wifiFromExchange == null && no 0x4301`, it directly does `throw IOException`.
   But in the real iPhone recording (§13a) the head unit sends the ctrl invitation only **21 seconds** after joining — restarting every round
   repeatedly resets the head unit's wait timer, so the invitation never comes.

### Fix (0.1.14)

- Dispatch only trusts `ReceiverProfile.wifiGen`: only `NEW` skips 0x5702; `LEGACY`/`UNKNOWN` always send it;
  wait window 1.5s (4× headroom per Audi measurements).
- With no credentials but a known hotspot SSID, synthesize an `airPlayPort=0` endpoint and continue (`credentiallessEndpoint`);
  a late 0x4301 still goes through the `onLateEndpoint` hot update.
- The 0x4301 path now also writes `lastWifiPsk`/`lastCarWifiSsid` (previously only the 0x5703 path did, so the manual fallback
  button falsely reported "hotspot password not yet obtained").
- Fixed ctrl advertisement port (re-registration only swaps the mDNS registration and reuses the socket; the old implementation changed the port every time,
  measured 43857→33383→33125→38151→40697→38015, with gaps of up to 1.2s in between).
- Added `TcpLiveness` to long-lived connections (control/event/video, idle=10s/count=3/userTimeout=20s).
- Unit tests pin both directions: `sparseDeclarationsStillSendWifiRequest` (Audi 3315 style must send),
  `fullDeclarationsWithoutOldProtocolSkipWifiRequest` (BMW iD9 style does not send),
  `knownCarSsidAvoidsCredentiallessAbort` / `noCredentialAndNoKnownSsidStillFails`.

### Reading the Signs (Next Time a Similar Problem Appears)

- Log shows "skipping 0x5702" followed by **`ECONNREFUSED` (not a timeout / unreachable route)** = the head unit is on the network but
  nothing is listening on 7000 — first suspect that the head unit's CarPlay stack was never brought up, not a network/address problem.
- A `[tx=0x5702]` count of 0 was the decisive evidence this time; for any "wireless won't connect" investigation, count it first.
- The corresponding rhythm on the iPhone sysdiagnose side: join network → **21s** invitation → first-round MFi 403 (normal) →
  **wait another 18~20s** for the head unit to re-invite on its own → success. Any client strategy of "start over if there's no progress for a dozen seconds"
  conflicts with this rhythm.

## 10. Reference File Quick Lookup

> All paths in the table refer to **close-reading sources** under `reference/` (xcertplay and DiPlay share a common origin
> with identical receiver logic, so either works); **the actual test bench runs DiPlay** (`reference/DiPlay/`).

| Topic | Location |
|---|---|
| Link-layer byte format / checksum | `reference/carplay-wifi-extractor/example/iap2/link_layer.py` |
| RFCOMM transport / UUID | Same repo, `transport/bluetooth.py`; xcertplay/DiPlay `CarPlayController.kt` |
| CSM framing / TLV | `reference/xcertplay/.../iap2/wire/Iap2CsmFramer.kt`, `Iap2WireCodec.kt` |
| Wireless control sequence | `reference/xcertplay/.../transport/Iap2WirelessControlClient.kt` |
| 0x1D01 identity fields | `reference/xcertplay/.../transport/Iap2IdentificationClient.kt` |
| Message endpoint catalog | `reference/xcertplay/.../iap2/catalog/Iap2Endpoint.kt` (931 lines, all parameter layouts) |
| Bonjour details | `reference/xcertplay/.../network/CarPlayBonjour.kt` |
| Receiver AirPlay session | `reference/xcertplay/.../airplay/AirPlaySession.kt` + `PairVerify.kt` |
| Test bench (version actually run) | `reference/DiPlay/` (with real-car diagnostics/audio enhancements; same origin as xcertplay) |
| Full sender reference | `reference/catplay/carplay/catplay_carplay/src/carplay_tx/` etc. |
| Windows receiver (WinPlay) | `reference/WinPlay/` (TS receiver; device filtering see §9d) |
| MFi authentication (receiver-side) | `reference/xcertplay/.../mfi/`; Oligo blog (evidence of one-way auth) |
| Decrypted firmware (lookup only) | ludwig-v/wireless-carplay-dongle-reverse-engineering (not cloned) |

## 12. MIB3 Wi-Fi Tunnel (a Second iAP2 Link on Top of the AirPlay Session, 2026-10-19)

> **⚠ Second correction on 2026-10-23 (this block is authoritative)**: this section's conclusion "MIB3 requires the tunnel, otherwise the link is torn down after 10~12s"
> **holds**; the correction from earlier that day, "CLASSIC_MIB3 ignores the marker and never negotiates", **has been disproven**.
> Unified model (complete chain of evidence):
> ① **The link must be negotiated to NORMAL** — the head unit's marker means it is waiting for our session table, resent at 1Hz **indefinitely**
>   (the 0.1.17 owner log shows far more than the old "2~5"); with no response, the head unit tears down the entire
>   wireless stack ~10s after RECORD (measured 10.17s on 0.1.9, 10.18s on 0.1.17, regardless of all heartbeats being healthy).
>   "Audi entered CarPlay as normal when 0.1.9 ignored the marker" was a misreport — 0.1.9's own log shows the 10.17s
>   teardown. The real iPhone's `iAPChannel FeatureKey=0` means the result after link negotiation **completes** is zero tunnels,
>   not "doesn't take part in negotiation".
> ② **Switching the screen relies on re-asserting modesChanged resources after negotiation completes** — 082446 (0.1.14) had negotiation/MFi/subscriptions
>   all succeed and a healthy session for 35s+, yet the head unit didn't switch the picture: the second control session reset the head unit's resource arbitration,
>   invalidating the modesChanged sent early in the session. The timing validated by AADisplay r17 long-play testing: after auth+burst,
>   **first send at +1.5s, re-assert at +4/+12/+30s** (must not be declared while 0xAA00/0xAA05 is in progress — contending with iAP POSTs
>   for the control channel kills the Audi for ~12s). 0.1.12's "picture appears, drops after 3 seconds" = negotiation slamming into a freshly started HMI,
>   the same timing-misalignment root cause.
> Current implementation: marker-triggered upgrade negotiation unified across all generations; `VirtualIap2Link.onControlSessionComplete`
> → `SessionController.scheduleModesReclaim`. The "ignore marker" dispatch once written based on the disproven correction has been deleted.

> Source of facts: `reference/AADisplay` (branch AADisplay-Split-Lite-Carplay, **GPL-3.0, read-only reference**).
> They captured the real-device handshake by hooking mediaserverd on a jailbroken iPhone with Frida; this repo implements
> `airplay/VirtualIap2Link.kt` based on that (protocol facts copied verbatim, code written independently).

**"Why it's needed" (original conclusion, applies only to the EVENT_LINK form)**: after the AirPlay session is established, MIB3 also requires the phone to open a second iAP2
link on the **event channel** and rerun the control session — when only link ACKs (empty echoes) are returned, MIB3 tears down the whole link after about 10~12 seconds (reproduced at 10.2s in two rounds of
this repo's real-car logs on 2026-10-19; previously misdiagnosed as missing heartbeats — the three-heartbeat fix in §9e cannot
save this car). The original comment in AADisplay's source: "Empty ACK alone makes MIB3 tear AirPlay control
down in ~12s with no picture."

**Transport asymmetry** (key):
- Head unit → phone: link bytes travel via `POST /command` on the **event channel** (TCP 5001), bplist
  `{"type":"iAPSendMessage","params":{"data":<link frame>}}`;
- Phone → head unit: via `POST /command` on the **RTSP control channel** (7000) (the same iAPSendMessage
  envelope, ChaCha-framed) — MIB3 **doesn't read** what's written on the event channel (DiPlay is lenient, Audi is not).

**Sequence** (Frida, real device):
1. After the screen stream SETUP returns 200 (dataPort obtained), the phone sends a DETECT marker **once** on its own (via the control
   channel) — sent too early, it is flushed during the SETUP exchange; afterwards the head unit's markers are all probe resends and are ignored;
2. The head unit replies with SYN (event channel, pure SYN with LSP) → the phone replies SYN|ACK (control channel). The LSP must declare
   **its own** parameters: maxOutgoing=0x7F (echoing the peer's table verbatim = locking yourself into the peer's small window,
   burst messages back up), keep maxLength at the peer's value; the session table follows the peer's (later versions may inject
   file-transfer 0x0C, needed for dashboard cover art);
3. Once the link is NORMAL the phone sends 0x1D00 → head unit 0x1D01 → phone 0x1D02 + 0xAA00 →
   (head unit 0xAA06 auth serial → phone sends 0xAA00 again) → head unit 0xAA01 certificate → phone 0xAA02
   challenge → head unit 0xAA03 → phone 0xAA05 (accepted directly without verifying the signature). **There is no version number to look up the challenge length**
   (it's determined by the generation of the head unit's MFi authentication chip and isn't in any identity/version field): the tunneled
   real device captured by Frida used 20B, DiPlay CARLINKIT only accepts 32B, and the same MMI 3315 accepts 32B over Bluetooth — so the tunnel
   path uses `MfiLengthMemory` (memory key = identity string + " tunnel", independent of the Bluetooth link), with a cold-start
   baseline of 20B first, switching length and retrying after 6s/3s of silence, switching immediately on rejection (0xAA04), and still sending 0xAA05 if all are silent;
4. After 0xAA05 the phone sends a **burst** (before any head unit subscription): 0x4E0C wireless session identifier (random uppercase UUID),
   0xA100 (param 6 empty), 0x4E0A language "zh", 0x4E0B device time (u64 seconds + s16 timezone minutes + u8 DST),
   0x4E09 device name "iPhone", 0xFFFA StartLocationInformation, 0xFFF0 GPRMC status —
   the last two are byte-for-byte Frida constants (`VirtualIap2Link` companion);
5. Replies to head unit subscriptions: 0x5000→0x5001 NowPlaying empty state (**the data path for dashboard lyrics**, later to be wired to
   MediaSessionsState), 0x4157→0x4158 communications empty state, 0x4154→0x4155 no calls,
   0x4170→0x4171 empty list; 0xEA02 (requestAppLaunch) / 0xA101 (VehicleStatus) /
   **0xFFFB (PASCD location samples ~1Hz) get link ACK only — missing ACKs also make the head unit close the control channel after ~10s**.

**Accompanying engine changes** (`iap2/Iap2LinkEngine.kt`, all unit-tested):
- When the initiator receives SYN|ACK (not a pure SYN) it also adopts the session table and advances the in-order watermark (adopt only, no echo);
- The responder treats echoing SYN|ACK as negotiation complete (the real device sends 0x1D00 without waiting for the ACK);
- The echo uses ownSyncOf (maxOutgoing=0x7F), and the send window is computed from **our own declared value**.

**AADisplay reference index**: `carplay/protocol/WifiIap2Link.kt` (sequence + constants),
`CarPlayRtspSession.kt:642` (how POST /command is written on the control channel), `docs/AUDI_CARPLAY_CONNECT_ANALYSIS.md`.

## §13 Real iPhone sysdiagnose Comparison (2026-10-21/17, Two Cars, Three Captures)

Source: unified log (airplayd/mDNSResponder) from iOS 24A446 (18.x) sysdiagnose, verified with
`/usr/bin/log show --archive system_logs.logarchive --predicate ...` (the zsh builtin `log` shadows it, so the full path is required).

### 13a. Audi MMI 3315_5GHz — "First Connection Always Rejected, Re-Invitation Succeeds"

Timeline consistent across both captures (same car, 14:24/14:32):

1. BT provisioning joins the network automatically (airplayd logs a value for `CarPlay UUID`) → about **21s later** the head unit sends its first ctrl invitation
   (`HandleControlServerEvent command 'connect'`);
2. Connects to `[fe80::…%en0]:7000` (**IPv6 link-local**, 1022ms) → pair-verify M1-M4 **succeeds in 32ms**;
3. **The MFi authentication stage is rejected with 403** (`MFi failed: 200403 Forbidden → Stage 1 failed → StartupFailed`)
   — **a real iPhone is rejected on first connection too**; it's an inherent head unit state, not a client problem;
4. **The head unit resends the invitation on its own 18~20s later**; the second verify takes 16ms, MFi (challenge 64B / certificate 607B)
   passes, Stage 1/2 → authorized → streams open. Throughout, **Bluetooth is never dropped and the network never changes**;
5. In the successful session the **`iAPChannel` FeatureKey negotiation result = 0** (Wi-Fi iAP2 tunnel not enabled) —
   contradicting our conclusion that "MIB3 requires the tunnel"; do a real-car A/B before changing the tunnel strategy;
6. Misc: the SETUP reply may omit streamID (the iPhone fills in 0 automatically); RECORD round trip 17ms; stream-level heartbeat
   Standard 2017ms; session-level keepAlive 10s (LowPower).

Implemented (2026-10-22): `AirPlayClient.pairingForbidden` (flagged on a 403 at any stage) → the main controller no longer takes
the "drop BT, second phase" path and no longer downgrades to et=NONE; the pairing loop backs off 5s after a 403 (the head unit's readiness window is ~20s,
and high-frequency retries may keep resetting its state machine).

### 13b. FAW-Volkswagen T-Roc 2023 (VW WLAN) — "Manually Joining the Network Never Works"

- Manually joined 2.4G `VW WLAN` ×5 rounds: `CarPlay UUID: N/A`, **zero mDNS responses for `_airplay._tcp`
  throughout**, no invitation; the iPhone waits 67s, leaves the network, and loops;
- Once BT provisioning is ready it automatically joins `VW WLAN_5GHz` (with UUID) → **77ms later** the head unit invites →
  `fe80::…:7000` (1831ms) → verify 21ms → MFi succeeds → streams normally for 3 minutes (no 403);
- The head unit's SETUP reply includes the viewAreas/initialViewArea structure without declaring the feature (the iPhone only warns and tolerates it).

Conclusion: this model only activates the CarPlay stack for "the network join brought about by iAP2 provisioning" — having the head unit hotspot saved in the system
or having joined it manually blocks the connection (our "clean up saved network with the same SSID" dialog is a key step for this car),
and the SSID delivered by 0x5703 must be used strictly (dual-band SSIDs; the successful path is `_5GHz`).
