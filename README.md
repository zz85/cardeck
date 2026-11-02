# CarDeck — Let an Android Phone Act as an iPhone and Connect Directly to the Head Unit

Turns an ordinary Android phone (non-rooted) into a **CarPlay sender (the iPhone role)**,
projecting the app's own UI onto a CarPlay-capable head unit.

> **Unofficial project**: this is a personal, experimental work. It is not affiliated with Apple Inc. or any car manufacturer,
> has not been authorized or certified, and **is not an MFi product**. CarPlay / AirPlay are trademarks of Apple Inc.
> and are mentioned here only to describe the compatibility target.
>
> **Driving safety**: configure and operate only while parked; a dropped connection while driving can interrupt navigation, so keep the factory navigation as a safety fallback.
>
> Full terms: see [DISCLAIMER.md](DISCLAIMER.md) (same source as the in-app disclaimer shown on first launch).

## Bottom Line First (Feasibility)

- **The opposite direction from DiPlay/xcertplay**: those are receivers (head unit role); this project is the sender.
  **DiPlay is a derivative of xcertplay and is also the test bench this project actually uses**; the source of both is kept
  in `reference/` for reference (protocol knowledge is shared at the iAP2/link/Bonjour layers; neither contains the other's role logic).
- **The sender does not need an Apple-signed identity**: iAP2 authentication is one-way (the phone verifies the head unit's MFi certificate,
  and the head unit trusts the phone's self-reported result; publicly confirmed by Oligo Security at DEF CON 2025).
- **The wired USB route has been ruled out** (an app cannot control the USB gadget, and the Honor bootloader cannot be unlocked),
  so wireless only: Bluetooth RFCOMM → head unit hotspot → mDNS → iAP2/AirPlay.
- For the detailed protocol basis, see [docs/PROTOCOL-NOTES.md](docs/PROTOCOL-NOTES.md).

## Download & Install

**Recommended: GitHub Release** (direct download, with SHA-256 and signing fingerprint):
[Releases](https://github.com/shaonialife/cardeck/releases/latest)

**Mirror for mainland China: Baidu Netdisk** (use when GitHub is slow to access from China)

> Shared via Netdisk: CarDeck
> Link: https://pan.baidu.com/s/1RQoDzFBermUobakZ0d0e3Q?pwd=xc5i
> Extraction code: xc5i

Before installing, make sure:

- **Android 14+** (the `App landscape` feature relies on platform ChangeIds introduced in SDK 34; unavailable on 13 and earlier)
- **Shizuku** is installed and **started** (activated via wireless debugging, no root) — this app's virtual displays, touch injection
  and window-state reads all go through it; you cannot connect until it is ready
  - Website (with step-by-step activation guide): https://shizuku.rikka.app/
  - GitHub (releases and changelog): https://github.com/RikkaApps/Shizuku
- On first launch a disclaimer is shown; you must scroll to the bottom and wait 10 seconds before you can accept it

> Both channels provide the same build artifact (signed with the official release key). Netdisk downloads cannot be verified automatically,
> so after downloading, use `apksigner verify --print-certs <apk>` to check that the certificate fingerprint matches the one published
> in the Release notes (SHA-256 `9457d04d2709094404a534ba06d7ecbc53cb1406224ec7c53c05a9c67ccc4970`);
> if it doesn't match, do not install.

## Roadmap & Go/No-Go

| Phase | Scope | Gate | Status |
|---|---|---|---|
| Phase 0 | Test bench + network-layer demo (this repo) | Two phones discover each other via mDNS | ✅ Accepted (DiPlay test bench) |
| Phase 1 | iAP2 sender session (link state machine + 0x1D00/0x5702/0x4300) | Receiver log shows "iPhone connected" | ✅ Accepted: runs end-to-end to 0x4301, obtains hotspot credentials and AirPlay endpoint |
| Phase 2 | AirPlay main session (PairVerify + H.264 encoding + HID back-channel) | Spare phone shows this phone's screen and is touchable | ✅ Accepted (2026-10-08 23:17): 30fps streaming + rendering, touch events returned end-to-end |
| Phase 3 | Real car (Audi MIB3) validation | Head unit lights up | ✅ **Fully working (2026-10-11)**: video stable on screen + head unit touch/swipe interactive (see Phase 3-C below) |
| Fallback | Buy a Tbox Android box and install the same self-drawn UI on it | — | Not needed (real-car route works) |

**Phase 3-C fully working (2026-10-11, accepted on a real Audi)**: iAP2 → AirPlay pairing → video streaming →
touch back-channel → virtual display injection, interactive end to end. What was cracked that day (details in `docs/PROTOCOL-NOTES.md` §9c):

1. **AirPlay pairing 403 solved**: pairing requests were missing Apple identification headers (`Content-Type: application/pairing+tlv8` +
   `X-Apple-HKP: 2`, plus `X-Apple-PD: 1` for pair-verify, matched verbatim against catplay PairingHelperTx).
   With those added, pair-setup/pair-verify all return 200 and the head unit stores the long-term public key.
2. **Screen stream 400 solved**: SETUP must advertise a **real UDP timing port** (TimingServer, RTCP NTP replies)
   and features must send the **full iPhone capability list** (the receiver computes "head unit supports ∩ phone proposes";
   proposing only videoPlayback yields an empty set → video pipeline not enabled); the modesChanged resource declaration goes first;
   UA/sourceVersion use the iPhone's self-reported value (566.25.21), not the head unit's.
3. **Session torn down after 10s solved**: both heartbeats are required — keepAlive UDP ping (head unit keepAlivePort, 10s period)
   + `POST /feedback` (1s period, catplay FEEDBACK_INTERVAL).
4. **Touch path (the final battle of the day, five fixes in a row)**:
   - Head unit commands must go via `POST /command` on the **event channel socket** (fire-and-forget, separate CSeq,
     Events key); sent on the RTSP control connection, the head unit never receives them (confirmed by DiPlay sendCommandLocked)
   - Only after `hidSetInputMode` (uuid echoed back as the original string) arms HID does the head unit start sending touches
   - Audi's `/info hidDevices[].uuid` is a **string** "0"/"1" (touch = uuid 1 eso_touchScreen,
     5-byte report `[tip 1bit][X u16LE][Y u16LE]`, coordinate max 1412×654 = screen resolution)
   - The event channel is bidirectional RTSP: **every incoming request must get an immediate 200**, otherwise the head unit's request pipe fills up and it declares the channel dead
     (symptom: "swipe once and it disconnects")
   - The event channel's `iAPSendMessage` carries a **virtual iAP2 link** initiated by the head unit (marker=DETECT, one question one answer;
     the head unit is the client and sends SYN — a server re-sending DETECT knocks it back to Detecting)
5. **Three stability fixes**: BT client thread generation counter (prevents zombie concurrency), ctrl mDNS registration follows network binding
   (force re-registration after network switch/binding, new listener every time), mDNS results filtered by the head unit's subnet
   (prevents the phone's hotspot sharing from mistaking a computer's AirPlay for the head unit).
6. **Debug-workflow pitfall (never forget this one)**: `adb install -r` does not kill the old process — the old dex keeps running;
   when "the fix doesn't work", force-stop and restart first; when verifying, check both the process generation (`ps -T` thread count)
   and the strings inside the APK (unpack the dex and grep).

**Phase 3 real-car breakthrough (2026-10-09, Audi_MMI_6318 / MHI3_AS_AU_P3801, see `docs/PROTOCOL-NOTES.md` §9b)**:
Compared with the test bench (DiPlay), the Audi MIB3 has six key differences; after fixing each one, the full iAP2 chain went through for the first time:
1. The head unit **sends SYN itself**, declaring the session table `{1:control, 2:EA}`; the phone must ACK + echo it verbatim,
   after which CSM uses **session 1** (sending on session 10 is silently dropped by the head unit while the link layer still ACKs — extremely misleading)
2. **The MFi challenge must be 20 bytes** (32B gets no response at all from the head unit; the DiPlay test bench needs 32B instead → implemented as a 32/20/16 fallback)
3. The head unit **does not send 0x4301** (confirmed by the identification frame's capability list: can receive 0x4e0c/0x4e09/0x4e0d/0x4e0e/0x5702/0xea00/0xea01,
   can send 0x5703/0xea03) → the AirPlay endpoint can only be discovered via **mDNS `_airplay._tcp`** over Wi-Fi
4. **ctrl invitation deadlock**: the head unit only opens the AirPlay window after discovering the phone's `_carplay-ctrl._tcp` on Wi-Fi and
   inviting via `GET /ctrl-int/1/connect` → the phone must bind the hotspot + advertise ctrl **immediately when 0x5703 arrives**
5. The ctrl TXT `id` must be the **real Bluetooth MAC** (read via Shizuku shell `settings secure bluetooth_address`;
   the app-level API only gives the placeholder `02:00:…`, and the placeholder value makes the head unit never invite → two-way deadlock)
6. pair-setup gets **403** (Audi does not accept anonymous SRP pairing) → switch to the "already paired device" pair-verify fast path;
   and **never** drop the BT control session (the test bench detach hack makes Audi tear down the entire session)

**Phase 1 on-device debugging log (2026-10-08, DiPlay on Redmi K40)**:
1. A shared read/write lock in `StreamDuplexStream` caused a deadlock (a blocked read holds the lock, writes wait forever) → separated reads and writes
2. 16-byte MFi challenge rejected (the Carlinkit EC identity uses AuthV3 and requires **exactly 32 bytes**) → fixed
3. The reference code (xcertplay) sends 0x6800 StartHID after subscribing; **the actual test bench DiPlay doesn't** (it goes straight to waiting for 0x5702 after subscribing) → changed to an optional 2-second wait

**Phase 2 Milestone A: AirPlay pairing working (2026-10-08 22:58)** — passed on the first try while Bluetooth stayed connected:
`/info` probe answered in 87ms → pair-setup (SRP fixed PIN 3939, M1/M3/M5) → pair-verify (M1/M3,
receiver ed25519 signature verified against mDNS `pk`) → control-channel ChaCha20 key derivation.
DiPlay-side logcat confirms: all 5 POSTs processed, immediately followed by `airplay control encryption enabled`,
then it waited patiently for 23 seconds (for the encrypted SETUP/event channel) before resetting.

**Debugging lesson (one worth remembering forever)**: "No RTSP response" was once blamed on receiver state gating/timing races;
the real culprit turned out to be a **client read-buffer bug** — the response headers and body arrived in the same read, yet `readExactly`
went back to the stream waiting for bytes already consumed. **Before concluding "the peer is ignoring me", use the peer's logs/packet captures to verify that the bytes arrived,
and that your own read path is correct.**

**Phase 2 end-to-end acceptance (2026-10-08 23:17)**: control plane (Bluetooth iAP2 → pair-setup/verify →
encrypted RTSP → SETUP → event channel → stream negotiation → RECORD) + data plane (MediaCodec H.264 →
128-byte header + ChaCha20 frame encapsulation → TCP streaming). Measured on the receiver: rx=shown=29.6fps, decode p50=6ms,
touch2frame 18ms, recoveries=0.

**Touch loop acceptance (2026-10-09 10:20)**: event-channel command parsing (RtspAccumulator + plist) →
hidSendReport two-slot report parsing (u16LE pixel coordinates) → UI hit testing. Confirmed on device: press-and-hold highlights,
release clears it, counter increments, coordinates refresh in real time; drag trails are complete. Pitfall: the report's second slot is
an idle `(0,0)↑` placeholder and must be filtered out, otherwise it overrides the real finger state.

**Audio channel (2026-10-10)**: SETUP type=100 negotiates a UDP audio stream → `AudioPlaybackCapture`
captures the phone's media/navigation audio → PCM 48k stereo packetized as RTP+ChaCha20 and streamed (512 frames per packet).
Keys follow the same rule as video (`DataStream-Salt<connId>`); requires MediaProjection + RECORD_AUDIO
permission (requested together when tapping "Request projection permission"). Unit-tested (3 decryption round-trip cases), pending on-device verification.
Remaining: microphone uplink (calls), polishing the head unit home-screen UI, real car (Phase 3).

## Test Bench Setup (Phase 0 Instructions)

1. **Spare Android phone** (Android 9+): download and install the APK from [DiPlay releases](https://github.com/shihabal3amri/DiPlay/releases)
   — it is our "virtual head unit" (receiver).
   (DiPlay is a derivative of xcertplay and more active; both sources are kept in `reference/` for reference.)
2. **Pair the two phones via system Bluetooth** (just pair them in Settings; this is a prerequisite for DiPlay to connect back).
3. **Install this app on the Honor phone**:
   ```bash
   ./gradlew assembleDebug
   adb install app/build/outputs/apk/debug/app-debug.apk
   ```
4. Follow the four steps in the UI:
   - **③ Start Bluetooth iAP2 listener**: listens on the iPhone UUID and waits for DiPlay to connect back.
     Once connected, the log hex-dumps incoming bytes — seeing `ff 55 02 00 …` means the link-layer SYN,
     the core verification point of Phase 0;
   - On the spare phone, select wireless mode in DiPlay (hotspot/LAN); once enabled it scans paired phones and connects back;
   - **① Join hotspot**: enter the SSID shown by DiPlay (the system dialog asks for the password);
   - **② Scan _airplay._tcp**: should resolve the spare phone's AirPlay endpoint + TXT (deviceid/features/pi/pk);
   - **④ Advertise _carplay-ctrl._tcp**: verify that DiPlay can discover and connect back to the phone's control channel.
5. DiPlay release builds embed an experimental accessory identity extracted from Carlinkit firmware (as stated in its README),
   which is enough to complete MFi authentication (our sender skips verification). If you need your own MFi identity (chip / local certificate /
   remote service — three options), see the xcertplay README.

## Project Structure

```
app/src/main/java/com/lizhihua/carplaysender/
├── MainActivity.kt                 # Phone-side console UI (connection/sound/dimmer/log/disclaimer entry)
├── SessionController.kt            # Session manager (process-wide singleton): link lifecycle, pairing, Wi-Fi, cancellation
├── SenderApp.kt                    # Application (uncaught-exception fallback, cache invalidation on package changes)
├── iap2/                           # iAP2 link layer + CSM + sender session
├── airplay/                        # AirPlay pairing/control channel/stream negotiation/stream encryption/Timing
├── audio/AudioSender.kt            # Audio channel (playback capture → RTP/ChaCha20 over UDP)
├── video/VideoSender.kt            # H.264 encoding and streaming
├── bt/                             # Bidirectional RFCOMM (Server waits for connect-back / Client actively connects to real car) + target device
├── launcher/                       # Invisible-display launcher (dock + app grid + map display; incl. letterbox hint)
│   └── settings/                   # Settings panel Fragments (shared by head unit two-pane and phone sub-menus)
├── render/DualDisplayComposer.kt   # EGL multi-display compositing → encoder Surface (incl. overlay card layer)
├── input/                          # Touch routing (dock/app display/map display direct injection) + consumer keys
├── capture/                        # Invisible-display frame source
├── shizuku/                        # Shizuku bridge (shell permission / touch injection / trusted virtual display AIDL)
├── net/                            # Wi-Fi joining / mDNS discovery and ctrl advertising
├── media/                          # Notification access → media sessions (music card data source)
├── service/CarPlayService.kt       # Foreground keep-alive service (connectedDevice/projection FGS types)
├── transport/                      # Transport-layer wrappers
├── ui/                             # Dimmer, waveform view, wallpaper, dark dialogs, panel window sizing
├── legal/                          # Disclaimer (content/acceptance state/dialog)
└── util/                           # Bg (background task pool) and other utilities
docs/PROTOCOL-NOTES.md              # Protocol knowledge base (per-layer formats/timing/reference index)
docs/CARLIFE-GATEWAY-FEASIBILITY.md # Feasibility study of an alternative route (CarLife gateway)
reference/                          # Reference repositories (gitignored, not committed or distributed)
```

## Code Quality (Automatically Enforced Before Commit)

Before a commit (`git commit`), the pre-commit hook (`.githooks/pre-commit`, enabled via `core.hooksPath`)
automatically runs two steps; if either fails, the commit is blocked:

1. **Code formatting**: Spotless + ktlint (official Kotlin code style, rules in the root `.editorconfig`).
   Staged `.kt`/`.kts` files are formatted and re-staged automatically, no manual action needed;
2. **Android Lint**: full `lintDebug` check; error-level issues fail the commit.

Manual commands:

```bash
./gradlew spotlessApply             # Format everything
./gradlew :app:lintDebug            # Lint check (report: app/build/reports/lint-results-debug.html)
./gradlew :app:updateLintBaseline   # Rebuild the lint baseline after fixing issues
```

Existing lint issues (273 of them, all deliberate trade-offs for the head unit UI: hard-coded Chinese text, px positioning, RTL, etc.)
are exempted in `app/lint-baseline.xml`; **new code that introduces the same kind of issue is still blocked**.
Baseline entries become invalid automatically once the issue is actually fixed; confirmed false positives can have the corresponding issue id disabled individually, or the baseline can be rebuilt after review.
After a fresh clone, run `git config core.hooksPath .githooks` once to enable the hooks.

## Build & Release

```bash
./gradlew :app:assembleDebug      # Everyday debug build (app/build/outputs/apk/debug/)
./gradlew :app:assembleRelease    # Release build (app/build/outputs/apk/release/)
./gradlew testDebugUnitTest       # Unit tests
```

**Version number**: `appVersionName` at the top of `app/build.gradle.kts` is the single source of truth (semantic versioning);
`versionCode` is derived from it automatically (`major*10000 + minor*100 + patch`), avoiding the case where the two get out of sync and
users silently fail to install an update. To change the version, change only this one line.

**CI auto-builds** (`.github/workflows/android-ci.yml`): every push builds `assembleRelease` with the official
keystore from the repo Secrets, and automatically increments the build number — passing
`-Pcardeck.buildNumber=<run_number>` yields `0.1.13.<N>` / `versionCode=base*10000+N`
(+1 per push, can be installed over the previous build directly; a patch bump automatically overtakes it). The artifact
`CarDeck-<version>-release.apk` is only available in the Actions Artifacts, **not published as a GitHub Release**.
Without `KEYSTORE_BASE64` (and `KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD`) configured,
the pipeline fails outright rather than cobbling together a "release" with the debug signature.

**Signing**: a local `assembleRelease` without `keystore.properties` falls back to the **debug signature**
(only for forks to test-install; CI builds with a build number fail outright). Before any official public distribution, configure a real signature:

1. Copy `keystore.properties.example` → `keystore.properties` (already gitignored);
2. `keytool -genkeypair -v -keystore cardeck-release.jks -alias cardeck -keyalg RSA -keysize 4096 -validity 10000`;
3. Fill in the passwords, run `assembleRelease` again, and use `apksigner verify --print-certs` to confirm the certificate CN is not `Android Debug`.

> ⚠️ Leaked private key = anyone can sign updates that install over yours; lost private key = installed users can never receive updates again
> (the only option is for everyone to uninstall and reinstall). Never commit or share the keystore and passwords.

## Scope & Boundaries

- **The product is an invisible-display launcher** (trusted virtual displays run phone apps + a persistent dock), not full-device mirroring;
  head unit touches are injected directly into the virtual displays via Shizuku (desktop/mirror modes were removed as of 2026-10-10).
- **License**: this project's source code is released under [MIT](LICENSE). The reference projects under `reference/`
  (xcertplay/DiPlay are GPL-3.0; **the catplay repository declares no license**, so all rights are reserved by default)
  **are excluded via `.gitignore` and not distributed with this repository**, so their license obligations do not apply to it; this project is
  an independent implementation that only references public protocol formats and real-device packet captures, without copying their code.
  Do not distribute keys or certificates extracted from firmware.
- Before validating on a real car, do a normal wireless pairing with a borrowed iPhone and the head unit as a baseline for comparison.

## Name & Trademarks

- The app is named **CarDeck** and **contains no** third-party trademarks. Early versions used the name "CarPlay 车机投屏" (CarPlay head unit projection);
  that name has been retired — using a trademark as a product name is trademark use and can easily be mistaken for an official product.
- "CarPlay" and "AirPlay" are trademarks of Apple Inc.; they appear here only to **describe the compatibility target**
  (descriptive reference) and do not imply affiliation, sponsorship or endorsement.
- This project **does not use** Apple's or any car maker's logos or certification marks (including MFi).
- The package name `io.github.shaonialife.cardeck` likewise contains no third-party trademark (it uses the standard reversed form
  of the GitHub username). Migrating from the old `com.lizhihua.carplaysender` is an **incompatible change**:
  the system treats it as a different app, so you must uninstall and reinstall; Shizuku authorization and local settings (including the AirPlay pairing identity)
  are reset, and the head unit must be paired again.

## Known Risks (full list in PROTOCOL-NOTES.md §9)

1. Audi MIB2 has a history of power-cycling in catplay's compatibility table; MIB3 is completely unknown.
2. A real car may require plugging in USB first before it allows wireless pairing (the test bench has no such restriction) — to be tested in Phase 3.
3. WifiNetworkSpecifier cannot pass the password programmatically; every join requires confirming a system dialog.
