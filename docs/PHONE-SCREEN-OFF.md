# Physical phone screen off (true screen-off) + virtual displays stay on independently

> 2026-10-23. Goal: phone screen black in your pocket, while the head unit keeps interacting at 60fps.
> Code: `shizuku/CarInputServiceImpl.setPhysicalScreenPower` +
> `SessionController.enterPhoneScreenOff`.

## 1. Why not "press the power button"

Pressing the power button / calling `PowerManager.goToSleep` enters **lock screen + Doze**:

- Keyguard blocks the virtual displays (the head unit shows the lock screen or black);
- CPU is throttled/suspended, starving the encoder and protocol threads.

scrcpy's `-S/--turn-screen-off` takes a different route: **logically the system stays "screen on and unlocked"**,
and only **the physical panel's power** is cut at the SurfaceFlinger layer. CarDeck copies this approach.

## 2. Three key points

### 2.1 Virtual displays must be in their own display group

Trusted virtual displays (app/appHi/map) additionally declare two @hide flags:

| Flag | Value | Effect | Extra permission |
|---|---|---|---|
| `OWN_DISPLAY_GROUP` | `1<<11 = 0x800` | The virtual display gets its own DisplayGroup and doesn't turn OFF when the main display sleeps/powers off | `ADD_TRUSTED_DISPLAY` (already required for trusted displays) |
| `ALWAYS_UNLOCKED` | `1<<12 = 0x1000` | No Keyguard drawn on this display when locked | `ADD_ALWAYS_UNLOCKED_DISPLAY` (**some ROMs don't grant it to shell**) |

`DisplayManagerService.createVirtualDisplayInternal` explicitly checks permissions for both; a missing permission throws
`SecurityException` directly. So `createDisplay` uses a **three-level fallback**: `own group+ALWAYS_UNLOCKED` →
`own group` → basic flags; any level that fails is logged and we continue, so the whole display never fails.

Note that `PUBLIC` implicitly adds `AUTO_MIRROR`, and `AUTO_MIRROR` clears `OWN_DISPLAY_GROUP` —
we also declare `OWN_CONTENT_ONLY` (which clears AUTO_MIRROR), so the own display group is kept.

**The dock display must also be in its own group** (tested 2026-10-23): the dock is an **untrusted** display that originally only got basic flags,
so it landed in the default display group — once the physical panel powers off, the whole group goes dark; on the head unit this shows as "app display fine, dock bar fully black,
recovers after unlocking". The fix is to give the dock `OWN_DISPLAY_GROUP` too (with fallback):
AOSP only clears this flag with `AUTO_MIRROR`, and `AUTO_MIRROR` is only set automatically along with `PUBLIC`;
the dock doesn't declare `PUBLIC`, so the flag is kept; `OWN_DISPLAY_GROUP` only requires `ADD_TRUSTED_DISPLAY`,
and doesn't change the dock's "untrusted" semantics (third-party tasks still can't go there).

### 2.2 Powering off the physical panel: `SurfaceControl.setDisplayPowerMode`

Call `setDisplayPowerMode(token, 0)` on the **physical main display token** (0=OFF, 2=NORMAL).
The framework still thinks the screen is on → no lock screen, no Doze; the touch IC also loses power with the panel, so pocket touches have no effect.

**Starting with Android 14 the way to get the token changed** (this is the easiest pitfall):

- SDK ≤ 33: `SurfaceControl.getInternalDisplayToken()`, with fallback
  `getPhysicalDisplayIds()` + `getPhysicalDisplayToken(id)`;
- SDK ≥ 34: the methods above **were removed from SurfaceControl**; the only way is
  `com.android.server.display.DisplayControl`. It lives in `services.jar`, invisible to the normal class loader, so
  you must create a class loader with `ClassLoaderFactory.createClassLoader(SYSTEMSERVERCLASSPATH, ...)`,
  then `Runtime.loadLibrary0(cls, "android_servers")` to register the JNI methods (same as scrcpy).

### 2.3 Keeping awake and the screen timeout

- `CarPlayService` already holds a `PARTIAL_WAKE_LOCK` + WiFi low-latency lock during the session, nothing new needed;
- `ScreenDimmer` already sets `screen_off_timeout` to the maximum during the session and restores it at the end,
  so the framework won't trigger screen-off on its own (otherwise the group state change would turn the panel back on).

## 3. Sequence

```
Streaming starts (virtual displays already created)
  → if "True screen-off when connected" is off: keep only the dim overlay, skip the steps below
  → if the phone is asleep: input keyevent KEYCODE_WAKEUP → wait 400ms → wm dismiss-keyguard (best effort)
  → setPhysicalScreenPower(false)        // panel power off
  → phoneScreenOff = true

Head unit side disconnects (VideoSender.onEnded)
  → restorePhoneScreen(sleep=true)       // keep the panel black, send KEYCODE_SLEEP directly
User disconnects manually / session retired (retireSession)
  → restorePhoneScreen(sleep=false)      // panel turned back on (the user is at the phone)
```

## 3.1 Settings toggle

**Settings → Display → True screen-off when connected (on/off, default off, per user request 2026-10-24)**, persisted in
`DisplayPrefs.screenOffOnConnect` (`settings` shared prefs, key `screen_off_on_connect`).

### 3.2 Touch detection is handled by the dim overlay (settled by user testing 2026-10-24)

After the panel powers off, **the touch IC still works** (tested on Honor Magic7: tapping the settings button on a black screen, the touch reaches
the UI and turns the screen on programmatically). So the automatic path doesn't use a bare power-off, but reuses the full manual fake screen-off flow of the dim overlay
(`ScreenDimmer.enterDimNow`): show dim overlay → cut panel power → **tap the overlay to wake**
(onUserInteraction → restore power + remove overlay). The overlay also acts as a **pocket-touch shield** — without it,
touches in the pocket would hit the foreground app (e.g. "Disconnect"). Pressing the power button still triggers real sleep (Keyguard/
surface detachment is unreliable; tested 2026-10-24, head unit froze); the right way is to tap the overlay to wake, not press the power button.
When the overlay is unavailable (no overlay permission) we fall back to a bare power-off — with no touch wake-up, only the power button works.

- When off: the panel is no longer powered off at session setup; only the existing 45s dim overlay + keep-awake remain;
- **Toggling takes effect immediately** (`SessionController.applyScreenOffPreference`): even with the panel black you can turn it off in the
  settings panel on the **head unit screen**, and the phone lights up right away instead of waiting for the next connection;
- Toggling without an active session does nothing.

## 4. Known limitations (none are bugs; they're the inherent cost of this mechanism)

1. **The power button needs two presses to light up**: the framework thinks the screen is on, so the first press is "screen off" and the second is "screen on".
   This is the same behaviour as scrcpy; there's no risk of actually bricking anything.
2. **Secure locks (PIN/pattern) can't be dismissed**: `wm dismiss-keyguard` doesn't work on secure locks. Virtual displays are unaffected by Keyguard thanks to
   `ALWAYS_UNLOCKED`; but opening apps/answering calls on the phone still requires unlocking manually.
3. **App-side fallback displays (EMUI etc.) are not guaranteed**: fallback displays have no own display group, so whether frames keep coming after screen-off depends on the ROM;
   the log will show `SCREEN: Physical screen powered off` but the head unit image may freeze — on such ROMs we recommend turning this feature off.
4. **Abnormal process exit**: UserService is a long-lived shell process; its power state doesn't vanish with the app process.
   There are three fallback layers: restore at session end, restore before the expired service kills itself, and restore once at the next app launch.
5. **Some ROMs don't grant shell `ADD_ALWAYS_UNLOCKED_DISPLAY`**: the log will show
   `ALWAYS_UNLOCKED failed — falling back to own group`; the feature still works, but the virtual displays may show the lock screen when locked.

## 5. Troubleshooting

The shell process log is at `/data/local/tmp/carinput_svc.log`:

```
createDisplay(CarPlayApp) trusted+own group+ALWAYS_UNLOCKED flags=0xc48
createDisplay(CarPlayApp) trusted+own group flags=0xc08
DisplayControl ready
setPhysicalScreenPower(false) ok=true
setPhysicalScreenPower(true) ok=true
```

App side (`files/main_debug.log`):

```
SCREEN: Physical screen powered off (true screen-off; system stays awake, virtual displays keep rendering, press power twice to recover)
SCREEN: Failed to power off physical screen (ROM didn't grant shell permission / can't get display token) — continuing with dim overlay
SCREEN: Head unit disconnected — phone put to sleep (panel stays black)
SCREEN: Physical screen powered back on ok=true
```

## 6. How to roll back

1. **User level**: Settings → Display → set "True screen-off when connected" to "Off" (takes effect immediately, keeps only the dim overlay);
2. To roll back the flags as well: remove
   `FLAG_OWN_DISPLAY_GROUP or FLAG_ALWAYS_UNLOCKED` (the first-level attempt) from `CarInputServiceImpl.createDisplay`; the fallback chain will then use the basic flags automatically.
