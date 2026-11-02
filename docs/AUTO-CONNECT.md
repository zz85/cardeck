# Auto-connect on entering the car (wake-up via head unit Bluetooth ACL broadcast)

> 2026-10-23. Goal: phone in your pocket, open the car door → head unit Bluetooth connects → CarDeck is launched automatically and starts
> a CarPlay session. Code: `bt/CarBluetoothReceiver` + `CarPlayService.ACTION_AUTO_START_SESSION`.

## 1. Broadcast: why a statically registered manifest receiver can launch a killed process

`BluetoothDevice.ACTION_ACL_CONNECTED` / `ACTION_ACL_DISCONNECTED` are on the
[implicit broadcast exceptions list](https://developer.android.com/develop/background-work/background-tasks/broadcasts/broadcast-exceptions)
(Android 8+ restricts implicit broadcasts for manifest-registered receivers; these two are exceptions). So as long as the device broadcasts,
the system will deliver:

```
<receiver android:name=".bt.CarBluetoothReceiver" android:exported="true">
    <intent-filter>
        <action android:name="android.bluetooth.device.action.ACL_CONNECTED" />
        <action android:name="android.bluetooth.device.action.ACL_DISCONNECTED" />
    </intent-filter>
</receiver>
```

into a **cold-started process**, without needing any foreground Activity.

## 2. Foreground service: the real exemption does not come from Bluetooth

**The Android 12+ exemption list for starting an FGS from the background has no "Bluetooth broadcast" entry** (official list:
[Restrictions on starting a foreground service from the background](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)).
Calling `startForegroundService` directly from the ACL broadcast gets blocked with `ForegroundServiceStartNotAllowedException`
— **unless** the app satisfies one of the items on that list. This project satisfies:

> **Your app holds the SYSTEM_ALERT_WINDOW permission.**

The dim overlay (`ScreenDimmer`) and touch probe already require "Display over other apps", so this exemption holds naturally
(targetSdk 34 only requires the permission to be granted; Android 15+ additionally requires a **visible** overlay window at that moment — this project
targets SDK 34, so that extra clause does not apply).

Consequences and countermeasures:

- If the user **has not granted** the overlay permission, auto-start fails → `CarBluetoothReceiver` catches the exception and logs it
  (opening the app manually and connecting once works, since there is a visible Activity then);
- The FGS type must be `connectedDevice` (don't use the 2-arg `startForeground`, which implicitly includes
  `mediaProjection` — Android 14 validates AppOps PROJECT_MEDIA for the latter and crashes outright).
  This project's `CarPlayService.onStartCommand` already uses `FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE` unconditionally.

## 3. Target filtering (avoid false triggers from earbuds/watches)

`CarBluetoothReceiver.isCarDevice`:

1. The user has picked a **target head unit** under "Bluetooth pairing" (`TargetDevicePrefs`) → only that MAC is accepted;
2. Not picked → fall back to Bluetooth name keywords (BMW / MINI / AUDI / PORSCHE / MERCEDES / BENZ / VOLVO /
   CARPLAY).

The ACL broadcast only provides a `BluetoothDevice` (both `address` and `name` require `BLUETOOTH_CONNECT`;
without the permission we just give up — there's no way to tell whether it's a head unit).

## 3.1 Toggle

**Settings → Display → "Auto-connect in car" (on/off, default on)**, persisted in
`AutoConnectPrefs.enabled` (`settings` prefs, key `auto_connect_on_bt`).
The receiver reads it on every ACL broadcast (the process may have just cold-started, so there's no cache). When disabled, ACL broadcasts
still arrive, but the receiver returns immediately and starts no service.

## 4. Sequence

```
Head unit Bluetooth ACL connected
  → CarBluetoothReceiver (static in manifest, process can be cold-started)
  → startForegroundService(ACTION_AUTO_START_SESSION)
  → CarPlayService.onStartCommand: startForeground(connectedDevice) within 5s + acquire locks
  → SessionController.ensure(applicationContext)   // on cold start, build the session objects first!
  → silently obtain MediaProjection (see §4.1) → audioEnabledForSession = whether it succeeded
  → SessionController.startConnection(applicationContext)  // same path as the home screen "Start connection"

Head unit Bluetooth ACL disconnected
  → ACTION_AUTO_STOP_SESSION (startService: while the service runs the process is already in foreground-service state, so it's allowed)
  → SessionController.disconnect() (retire session, release virtual displays, restore screen/timeout, stop service)
```

## 4.1 Silently obtaining MediaProjection (the "sound" of auto-connect)

AudioPlaybackCapture requires a MediaProjection instance, and the token is **single-use** (invalidated when capture stops);
during auto-connect nobody is there to tap "Allow projection". The two system gates and how we get past them (`audio/SilentProjectionActivity`):

1. **Consent dialog** → Shizuku runs `appops set <pkg> PROJECT_MEDIA allow`: SystemUI's
   `MediaProjectionPermissionActivity` sees that app-op and returns `RESULT_OK` directly, without showing a dialog
   (same approach as Tasker's official docs).
2. **Background Activity launch (BAL)** → Shizuku runs
   `am start -n <pkg>/<activity> --activity-no-animation`: shell holds
   `START_ACTIVITIES_FROM_BACKGROUND`, so app-side BAL restrictions don't apply (which is why the Activity must be
   `exported=true` — shell does not have `START_ANY_ACTIVITY`); on failure we fall back to in-app
   `startActivity` (requires `SYSTEM_ALERT_WINDOW`; if missing, grant it via `appops` first).

The page itself: 1×1, not touchable/focusable, no animation, `noHistory` + `excludeFromRecents` +
`taskAffinity=""`; `setShowWhenLocked/setTurnScreenOn` ensure the callback completes even with the phone locked in a pocket.

There is one **iron rule on Android 14** in the sequence: after obtaining consent you must **first upgrade the FGS type to include
`mediaProjection`** before calling `getMediaProjection()`; the implementation reuses
`pendingProjectionResult → upgradeToProjection() → takeProjection()` directly.
Also, when `CarPlayService.onStartCommand` calls `startForeground` again, if
`mediaProjection != null` it must keep including that type; otherwise the type is effectively removed and the system stops the projection.

**Side effect**: `appops set PROJECT_MEDIA allow` is **persistent** (appops.xml), i.e. this app can
project silently from then on; clear it manually when not needed: `adb shell appops set <pkg> PROJECT_MEDIA default`.

## 5. Known limitations

1. **Sound depends on Shizuku being online**: silently obtaining the projection token goes through `appops` + `am start`; when Shizuku is unavailable,
   auto-connect still pushes video (audio is deliberately skipped, no dialog). If `RECORD_AUDIO` was never granted, we first try
   `appops set RECORD_AUDIO allow` to grant it.
2. **Shizuku must already be running**: virtual displays/touch injection rely entirely on shell permissions; after a phone reboot, if Shizuku isn't started,
   auto-connect stalls at "Bluetooth connected, head unit screen black". The log will show `SHIZUKU: service not running`.
3. **The first connection may fail once**: right after the head unit powers on, the iAP2 service window may not be ready; the existing BT client retries automatically;
   if it keeps failing, look at the `BT-C: connection failed…` lines.
4. **Cleanup on leaving the car**: `ACL_DISCONNECTED` → `disconnect()`. If streaming is in progress at that moment, `onEnded` and
   `disconnect` both run in turn (the `phoneScreenOff` flag ensures cleanup happens only once).
5. Auto-start shows no UI at all; to find out what happened, check the notification ("CarDeck running") or the log.

## 6. Troubleshooting

App log (`files/main_debug.log`):

```
AUTO: Head unit Bluetooth connected BMW 87866 (AA:BB:CC:DD:EE:FF) — auto-starting session
AUTO: Ignoring non-car Bluetooth FreeBuds Pro (…)
AUTO: Auto-start blocked by system (ForegroundServiceStartNotAllowedException: …) — please grant "Display over other apps"
SVC: Received "auto-start in car" — starting session
AUTO: Head unit Bluetooth disconnected … — ending session
```

Command-line verification:

```bash
# Is the receiver registered (manifest receivers show up in the package dump)
adb shell dumpsys package io.github.shaonialife.cardeck | grep -i -A3 CarBluetoothReceiver
# Power-cycle the head unit's Bluetooth once and check the log for the two AUTO: / SVC: lines
```

> **Don't** simulate it with `am broadcast -a …ACL_CONNECTED -n <pkg>/.bt.CarBluetoothReceiver`:
> `EXTRA_DEVICE` is a Parcelable that `am` can't pass, so the receiver gets no device and returns immediately, which looks like "it didn't work".
