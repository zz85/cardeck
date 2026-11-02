# Feasibility study: CarLife/Carlink → CarPlay gateway

> Study date: 2026-10-08. Goal: add a CarLife/ICCOA Carlink **receiver** to the existing app (CarPlay sender),
> so the phone's car-connect app / Amap can project their screen into our app,
> which then forwards it via CarPlay to the Audi head unit — upgrading from a "self-drawn launcher" to a "real projected-content gateway".

## Summary of conclusions

| Dimension | Assessment |
|---|---|
| Architectural precedent | ✅ Carlinkit ICCOA boxes/Tbox are exactly a "connectivity-protocol receiver → CarPlay channel" bridge, mature retail products |
| CarLife protocol openness | ✅ Baidu officially open-sourced a complete head-unit side in 2018 (apollo-DuerOS, Apache 2.0, surviving forks) |
| Open-source reference implementation | ✅ CarLifeWirelessBox (MIT, active in 2026, verified compatible with Xiaomi CarWith's CarLife connection) |
| Video forwarding cost | ✅ CarLife is already H.264 Annex B; → CarPlay TS/RTP only needs a remux, zero transcoding possible |
| **Full Amap projection** | ❌ **The CarLife channel doesn't support Amap** (only Baidu apps are adapted); Amap's deep integration uses the Carlink channel |
| ICCOA Carlink openness | ❌ Alliance membership, zero open source, includes authentication/encryption; only reverse engineering is possible (lots of work, compliance risk) |
| Single-device loopback (127.0.0.1) | ⚠️ No obstacles on the data plane; the risk is whether the sender app will enter listening state without USB/head unit — needs testing |

## 1. The CarLife protocol: almost entirely public

In 2018 Baidu officially open-sourced a complete head-unit implementation on GitHub at ApolloAuto/apollo-DuerOS
(the original repo was deleted; 81 forks survive, e.g. milesking/apollo-DuerOS; Apache 2.0, legally reusable).

- **Transport**: 7 TCP channels — the phone listens on 7240(CMD)/8240(VIDEO)/9240(MEDIA)/
  9241(TTS)/9242(VR)/9340(TOUCH)/9440(CTRL); WiFi mode = the receiver actively connects to
  "phone IP:port" over plain direct TCP (no ADB needed; only USB mode uses ADB forward/AOA)
- **Messages**: Protobuf + custom header; message IDs known from testing: version negotiation 98305/65538,
  authentication 98380/65611, heartbeat 131074, touch 425985, hard keys 425992; 8-stage handshake
- **Media**: video = raw H.264/AVC Annex B stream encoded by the sender (around 720p/30fps);
  audio PCM/AAC; control via protobuf
- **Touch return path**: separate touch channel, coordinates scaled to the projection resolution — maps neatly onto our CarPlay HID touch points

## 2. Key gap: which channel Amap uses

- **CarLife+ does not support Amap projection by default** (only Baidu apps are adapted). Workarounds: CarLife+ 8.0
  "Project phone apps" full-screen mirroring (includes Amap), or "Super projection" USB unlock — the mirroring experience is limited
- **Amap's full deep projection is on the CarWith/ICCOA Carlink channel** (lane-level/cruise/full phone UI);
  that protocol: spec downloadable only by alliance members, zero open-source implementations, includes authentication/encryption; the community can only reverse
  CarWith/Jovi phone apps or the head-unit APKs circulating on the Enshan forum (right.com.cn)
- **Xiaomi CarWith keeps a "CarLife connection" entry** (built-in com.baidu.carlife component) —
  i.e. implementing just a CarLife receiver already covers CarWith's compatibility mode; same for Honor car-connect (CarLife-compatible)
- Pitfall in newer versions: CarLife app ≥7.2 is incompatible with third-party head units (black screen); reliable senders =
  CarWith's CarLife connection, old CarLife app (≤6.7.2), vendor car-connect compatibility modes (needs testing)

## 3. Single-device loopback feasibility (the core innovation and risk of this approach)

- CarLife WiFi mode data plane = direct TCP to the phone IP → 127.0.0.1 should work directly in theory
  (Android's sandbox allows localhost communication between apps, no adb forward needed — self-forwarding isn't possible without root anyway)
- **Nobody has publicly verified same-device loopback**. All the risk lies in the sender's state machine:
  the sender app (car-connect/CarLife/Amap's embedded module) may require "USB plugged in detected" or
  "connected to head unit hotspot" before starting its listening service/accepting connections
- Test method (Go/No-Go experiment): install an old CarLife app on the phone → open the connection screen →
  use the local browser/another app to connect to 127.0.0.1:7240 and see whether a service answers; or first use
  carlife_pc_tool to verify the phone sender on a PC, then move back to the same device
- **Fallback architecture** (if loopback doesn't work): a spare phone running the full app (CarLife receiver +
  CarPlay sender) kept in the car = a home-built Tbox (CarLifeWirelessBox has validated this topology;
  we just need to add the CarPlay sender) — which is exactly the form of the commercial products

## 4. Integration with the existing project (if implemented)

```
┌──────────────────── same Honor phone ───────────────────┐
│ Honor car-connect/CarLife app (sender)                  │
│        ↕ 127.0.0.1:7240… (CarLife WiFi mode)            │
│ carlife/ module: CarLife receiver (new)                 │
│   - handshake/auth/heartbeat (protobuf)                 │
│   - receive H.264 Annex B → remux (zero transcoding)    │
│   - touch 425985 ← CarPlay HID touch coordinate mapping │
│ bridge/: feed the remuxed TS stream to CarPlay sender   │
│        ↕ Bluetooth RFCOMM + WiFi + AirPlay main session │
└──────────────────────→ Audi head unit screen ───────────┘
```

Suggested order: first do the "loopback Go/No-Go experiment" (1-2 days) → CarLife receiver
(reuse the apollo-DuerOS C++ library or the CarLifeWirelessBox Kotlin implementation, 2-3 weeks) →
remux bridge (1 week) → hook up to the Phase 2 CarPlay sender.

## 5. Comparison with alternative routes (important)

| Route | Amap experience | Effort | Risk | Latency |
|---|---|---|---|---|
| A. Embed Amap navigation SDK in self-drawn UI | Professional navigation, controllable UI but not "full Amap" | Small (individuals can apply for a key) | Low | Single hop, lowest |
| B. CarLife gateway (this study) | Baidu apps + Amap via mirroring mode | Medium | Medium (sender compatibility) | Two hops ~200-400ms |
| C. Carlink gateway (reverse engineered) | Full Amap deep integration (best) | Large | High (closed source + encryption + fragmentation) | Two hops |
| D. Buy a Tbox | Full (box has B+C built in) | Zero development | Low | Box-level |

**Recommendation**: do A immediately as the baseline (it doesn't conflict with B/C and is already part of the Phase 2 self-drawn UI);
start B as an enhancement after Phase 2 is done (run the loopback experiment first); evaluate C only if there's still strong demand after B is validated.

## 6. Reference implementation index

| Project | Notes |
|---|---|
| milesking/apollo-DuerOS (fork) | Baidu's official open-source head-unit side, Apache 2.0, C++ library + Android app |
| pitate7777777/CarLifeWirelessBox | MIT, Kotlin, HU+MD dual role, verified compatible with CarWith wireless CarLife |
| MeshHun/carlife_pc_tool | PC head-unit simulator, decompiled protocol constants, active 2026-09 |
| aa112901/CarProjection | Phone as a CarLife head-unit screen, Android 10+, no root |
| eytb2.github.io CarLife training notes | Official training material: connection modes/channels/protobuf structures |
| Enshan forum (right.com.cn) ICCOA Carlink update collection | Source of circulating head-unit APKs (research reference only, do not distribute) |

## 7. Compliance notes

- apollo-DuerOS (Apache 2.0) and CarLifeWirelessBox (MIT) can be legally reused; keep the copyright headers
- Reverse engineering CarWith/Carlink APKs and APKs circulating on the Enshan forum: personal research only, no redistribution
- "CarLife" is a Baidu trademark and commercial use requires authorization; avoid it when naming personal projects
