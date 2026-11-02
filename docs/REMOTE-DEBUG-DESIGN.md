# CarDeck remote debug bridge (remoteDebug variant + cardeck-bridge)

> Design finalized and first version landed 2026-10-20. Purpose: remote troubleshooting for fellow drivers — the developer's computer provides a debug service
> via an intranet tunnel; the driver installs the **remoteDebug variant** on their phone and connects with address + pairing code; the developer
> (or an AI agent) watches logs live, sends whitelisted commands, pushes overlay hints, and reinstalls new builds with one click.

## Overall topology

```
Head unit ←→ Phone [remoteDebug variant .rd] ←WSS/WS── tunnel ── developer computer
                                                     ├─ WS device side (:8787)
                                                     ├─ MCP agent side (stdio)
                                                     └─ GET /apk/<file> (reinstall delivery)
```

## Key decisions and rationale

1. **Build isolation uses a product flavor, not a new module/new repo**: `bridge` dimension, `standard`
   (daily/release, package name unchanged — Shizuku permission is granted per package name) and `remote` (`applicationIdSuffix
   = ".rd"`, installable alongside the daily build). All debug bridge code is in `app/src/remote/`, dependencies use
   `remoteDebugImplementation` — **not a single line goes into the standard artifact** (verified in the build).
   The manifest overlay (`tools:replace="android:name"`) replaces the Application class with
   `BridgeApp`, and declares `REQUEST_INSTALL_PACKAGES` and debug-only components.
2. **The app side couples to business code in only two places**: `DebugFileLog.tap` (log
   sequence number + listener hook added in main; always null in standard, zero overhead) and `BridgeCommands` (calls existing public
   APIs `SessionController.ensure/startConnection/disconnect/statusLine`).
   The main source set knows nothing about any bridge types.
3. **Hand-written WS client (~200-line RFC6455 subset)**: no okhttp — keeps the dependency out of the daily build,
   and message shapes are under our control (text frames/masking/ping-pong/server fragmentation). wss uses the system
   SSLSocketFactory (the tunnel service provides a valid certificate); for self-signed/bare-IP setups use ws://.
4. **Server is a separate repo `cardeck-bridge` (MIT)**: Node/TS single process with two faces — WS device side
   + MCP(stdio) agent side + static APK delivery. Ring log of 5000 lines/device, deduplicated by seq;
   one-time pairing-code handshake; command whitelist (never opens up arbitrary shell).
5. **Three safeguards for the reinstall loop**: sha256 check rejects wrong packages; before installing, **first `SessionController.
   disconnect` to release all virtual displays** (otherwise the system install confirmation dialog lands on the head unit's virtual display —
   a pitfall already recorded in AGENTS); a `resumePending` marker is written before install, and on first launch of the new version an overlay asks
   "Continue into debug mode?" and reconnects automatically.

## Phone-side components (app/src/remote/java/.../bridge/)

| File | Responsibility |
|---|---|
| `BridgeApp` | Entry point: install tap, auto-reconnect / resume prompt after reinstall |
| `BridgePrefs` | Address/pairing code/device ID/auto-connect/resume marker (separate "bridge" prefs) |
| `WsClient` | Minimal RFC6455 client (reader thread + serialized writes + fragment reassembly) |
| `BridgeClient` | Session loop (exponential-backoff reconnect), pairing, batched log backfill + 500ms live batches, message dispatch |
| `BridgeCommands` | Command whitelist: start/stop_connection, get_state, ping |
| `BridgeOverlay` | Draggable overlay hint window (TYPE_APPLICATION_OVERLAY, text + optional buttons) |
| `ReinstallFlow` | Download/verify/prompt/stop session/PackageInstaller |
| `BridgeSetupActivity` | Second launcher entry: address + pairing code + status + permission guidance |
| `InstallReceiver` | Install result: brings the system confirmation Intent up on the phone's main display |

## Server (cardeck-bridge, repo outside the project)

MCP tools: `bridge_status` / `logs_tail(device,lines)` / `logs_fetch(device,since)`
/ `cmd(device,name,args)` / `notify(device,text)` / `offer_apk(device,filePath,version)`.
Environment variables `PORT` / `PUBLIC_BASE` / `BRIDGE_PAIRING`; `.npmrc` pins the public registry.

## Known costs and conventions

- `.rd` is a new package name: Shizuku, notification access and projection permissions must all be **granted again once** in this variant
  (the settings page has explanatory text).
- The remote variant doesn't go into the main GitHub Release line (or is clearly marked experimental), to keep log-leak concerns from spreading.
- Logs contain hotspot names/Bluetooth addresses; this is disclosed to users both in the README and in the app.
- Expanding the command whitelist goes through single-point review in `BridgeCommands`; adding arbitrary shell/reflection commands is forbidden.

## Delta updates (landed 2026-10-24, measured 72%~97% traffic savings)

A test round with a fellow driver pushes several builds; a full 15MB over cellular each time is too expensive. Byte-level block diff (rsync-style):

- **Server** (`src/deltadiff.ts`): historical APKs in `public/` are automatically indexed as baselines by sha256;
  the device's hello reports the sha256 of its installed APK (`sourceDir`); on offer, if a baseline matches, a
  COPY/LITERAL patch is generated (FNV-1a 4KB blocks + greedy extension); if the saving is under 60% of full size, no patch is generated.
  Measured 15128KB → 440~4223KB.
- **Device** (`bridge/DeltaPatch.kt`): download the patch → assemble using this process's `sourceDir` as the baseline
  → strict sha256 check of the result → enter the install flow. Any failure unconditionally falls back to a full download (delta is only an optimization).
- **Protocol contract (hard agreement between both ends, don't change one side alone)**: 88B header = magic(8) + targetLen(8) +
  targetSha(32) + baseLen(8) + baseSha(32); **all u64 are big-endian** (the device-side Kotlin
  `readLong` only reads big-endian — the first version used LE + a 72<88 header buffer that truncated baseSha, so the first delta hop silently fell back
  to full; fixed after catching it on a real device).

## Three tiers of log channels (2026-10-24)

In-memory ring (live tail, routine high-frequency lines de-noised) → on-disk JSONL (`logs_fetch`, full, across process
generations, 20MB rotation) → device file log (`logs_file`, the complete source of truth including RAW dumps, gzip
returned as a single message and saved to `cardeck-bridge/logs/`). Use the latter two for investigating historical issues (the stream setup before an install).

## Triple safeguard for install confirmation and the overlay exemption (settled by testing 2026-10-24)

- The PENDING_USER_ACTION system confirmation Intent, when launched with `startActivity` directly from a background Receiver,
  is **silently blocked** on Honor (no exception, no UI, no failure callback) — the exemption condition is that the app holds
  the **overlay permission (SYSTEM_ALERT_WINDOW)**. The `.rd` build must have "Display over other apps" enabled
  on first use: install confirmation, disconnect overlay hints and stable-repro guidance all depend on it.
- Triple safeguard (`InstallReceiver` + `BridgeSetupActivity`): ① launch the confirmation Intent directly
  (works within the foreground grace period); ② launch the debug setup page and have it **forward from a foreground Activity** (always works);
  ③ overlay exemption as the last resort. Even so, push install still requires the user to be looking at the phone at the time.
- Delta info is surfaced throughout: receive log "Delta XKB", confirmation dialog "Delta XKB · Full YMB"
  (this text only appears when the delta actually succeeded; on silent fallback only the full size is shown).

## Build commands (after flavorization)

```bash
./gradlew assembleStandardDebug    # Daily build (equivalent to the old assembleDebug)
./gradlew assembleRemoteDebug      # Remote debug build (…/remote/debug/app-remote-debug.apk)
./gradlew testStandardDebugUnitTest
./gradlew :app:lintStandardDebug   # pre-commit hook already updated
```
