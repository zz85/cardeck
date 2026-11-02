# Head unit HID input: steering-wheel media keys + iDrive knob (BMW iD8.5)

> 2026-10-23. Goal: skip/pause from the steering wheel (global media keys), iDrive knob rotation and 4-way nudge (virtual display focus navigation).

## 1. Pipeline (current state = the parts already fixed)

```
Head unit /info → hidDevices[] (uuid + hidDescriptor)
  → at session setup send hidSetInputMode(mode=0) for **every** uuid   ← already iterated over all devices
  → head unit starts sending hidSendReport (event channel) / 0x6802 AccessoryHIDReport (iAP2 tunnel)
  → route by **device descriptor** (not by uuid, not by head unit model):
       Consumer page 0x0C      → MediaKey (AudioManager global dispatch, no focus needed)
       Digitizer page 0x0D     → touch (TouchRouter coordinate injection)
       Generic Desktop 0x01    → knob/4-way (new this time, DPAD injected into virtual display)
```

**Don't change this to "guess the device by uuid/name"**: `uuid` is arbitrary per head unit (Audi sends `"0"/"1"`, DiPlay sends integers);
the descriptor is the only stable signal (`ConsumerKeys.parseDescriptor` / `HidNavigation.isNavigationDevice`).

## 2. Media keys (existing)

`ConsumerKeys` parses the descriptor into a Usage array and decodes according to **the two HID semantics, Array/Variable**,
separately (Array: byte = index; Variable: 1 bit per usage), then maps to `KEYCODE_MEDIA_*`,
dispatched globally via `SessionController.dispatchMediaKey` → `AudioManager.dispatchMediaKeyEvent`
(works wherever focus is; you can skip tracks even while the head unit shows navigation).

## 3. Knob/4-way (new this time)

`input/HidNavigation` decides it's a navigation device when the descriptor has Generic Desktop page 0x01 and contains
**Wheel(0x38) or Hat(0x39)**, and it isn't a Digitizer page (touchscreens excluded).

Report parsing (falls back to 2~4 bytes when the descriptor is missing):

| Position | Meaning |
|---|---|
| Last byte | **Signed** knob steps (positive = clockwise → DPAD_DOWN, negative → DPAD_UP, at most 5 steps per frame) |
| Preceding bytes | Button bitmap, triggered on **rising edge**: bit0=confirm, 1=up, 2=down, 3=left, 4=right, 5=back |
| Hat device (0x39, 1 byte) | Low 4 bits = 8-way hat (0 up / 2 right / 4 down / 6 left, anything else = released) |

Injection goes through `ICarInput.keyEvent(displayId, keyCode)` (UserService's `injectInputEvent`,
`KeyEvent.setDisplayId` targets the virtual display directly, millisecond-level); on failure falls back to `input -d <id> keyevent <code>`.
Target display = `VirtualLauncher.activeAppDisplayId()` (launcher grid / currently open app display).

**Why DPAD can't be dispatched globally**: DPAD needs the focus system; global dispatch lands on the phone's main display; only injecting into
the head unit virtual display makes the head unit launcher/apps respond.

## 4. Focus highlight (knob visibility)

- `res/drawable/focus_ring.xml`: draws a 3dp cyan stroke only in `state_focused`;
- Grid cells (`GridActivity.PageHolder.bind`): `isFocusable = true` +
  `foreground = focus_ring` (foreground doesn't override the background set in code);
- Key events automatically take the window out of touch mode (`focusableInTouchMode=false` is enough to get focus).

**Known limitation**: the dock is a **separate display**, and DPAD doesn't move focus across displays — the knob can only move within
the app display. Supporting "push the knob to the left edge to jump to the dock" would require the Launcher to intercept edge DPAD and switch display/focus;
not implemented (do it after confirming head unit behaviour on a real car).

## 5. Key calibration (required on a real car)

Different iD8.5 firmwares may have different report layouts/bit orders (`S18A-23-xx` vs `24-xx`), and may also use
the event channel instead of 0x6802. To calibrate:

1. Settings → Display → "**HID raw log**" → on;
2. Press in turn: steering wheel next / previous / play-pause / knob rotate left & right / knob press / 4-way;
3. Look at `files/main_debug.log`:

```
HID-RAW: uuid=0 bytes=05 desc=27B
EVENT: Head unit media key Next (uuid=0 usage=0xb5 → keyCode=87)
HID-RAW: uuid=1 bytes=00 02 desc=34B
EVENT: Knob/nav key Down+Down (uuid=1)
```

4. If "Next" prints `usage=0xb5` but nothing happens → the problem is in dispatch, not mapping;
   if the printed bytes don't match expectations → adjust the `ConsumerKeys.keyUsage` mapping table or
   `HidNavigation`'s bit definitions according to the actual bytes; if there's not a single `HID-RAW` line → the head unit isn't sending (most likely
   `hidSetInputMode` wasn't armed for that uuid, or this head unit puts the keys on iAP2 `0x6802`).

## 6. Quick troubleshooting

| Symptom | Check first |
|---|---|
| No log at all | Does `/info` have hidDevices? Was `hidSetInputMode` sent for every uuid? |
| HID-RAW present but no reaction | Was the descriptor recognized as Consumer/navigation (byte count in `desc=` in the log + parse result)? |
| Knob logs appear but focus doesn't move | Does the virtual display have focusable views (grid cell `isFocusable`)? Is the injected displayId correct? |
| Steering-wheel track skip doesn't work | Is `dispatchMediaKey` called? Does a system media session currently exist? |
