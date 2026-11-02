# Disclaimer

> This file shares the same source as the disclaimer shown in the app on first launch. The in-app
> disclaimer is the main text; this file supplements it at the repository level and should be provided
> to users along with any download, distribution or redistribution.

## Unofficial status

This project (the "App") is an **unofficial, personal experimental project**. It is **not affiliated**
with Apple Inc., any car manufacturer or any of their affiliates, and has not received any form of
authorization, certification or endorsement.

The App is **not** an MFi (Made for iPhone) certified product, nor is it an official implementation
within Apple's CarPlay ecosystem.

## Trademarks

- **CarPlay** is a trademark of Apple Inc.
- **AirPlay** is a trademark of Apple Inc.
- The App's name, "CarDeck", does not contain any of the above trademarks. Wherever the words
  CarPlay / AirPlay appear, they are used **only to describe the App's compatibility target** (i.e. the
  App attempts to communicate, acting as a sender, with receivers that support CarPlay). This is a
  descriptive reference and does not imply any affiliation, sponsorship or approval.
- This project **does not use** any graphical marks (logos, icons, certification marks, etc.) of Apple
  or any car manufacturer.
- All other product names and trademarks mentioned are the property of their respective owners.

## Driving safety warning

**The App is not a safety-critical system and cannot replace the vehicle's factory navigation or
instrument cluster.**

- Complete connecting, pairing and all settings **while parked**;
- Any operation while driving must be done by a passenger; the driver must not hold or operate the
  phone;
- The wireless link may **drop at any time** due to signal interference, system sleep, hotspot
  switching, Bluetooth reconnection and other causes, resulting in a frozen picture, black screen,
  unresponsive touch or interrupted navigation;
- A disconnect while driving may cause you to miss a turn, lose your route or become distracted.
  Always keep the head unit's factory navigation as a safety backup.

## Tested scope

The App has only been verified with the following combinations:

| Role | Device |
|---|---|
| Sender | Honor Magic7 Pro (Android 17) |
| Receiver | Audi MIB3 head unit (real car) |
| Receiver | DiPlay test bench on a Redmi K40 (Android 13) |

**Other phones, other Android versions and other car models are unverified.** They may not work at
all, or may show display glitches, misaligned touch or repeated disconnects. The "Force landscape"
feature requires Android 14 or later; it is unavailable on Android 13 and earlier.

## System permissions

To provide its features, the App requests and uses the following capabilities:

- **Shizuku (shell permission)**: creating trusted virtual displays, injecting touch events, writing
  system compatibility settings (e.g. forcing apps into landscape), and reading window and display
  state. It will not run commands unrelated to the App's functions.
  Shizuku is an independent third-party open-source project ([website](https://shizuku.rikka.app/) /
  [GitHub](https://github.com/RikkaApps/Shizuku)); the scope of its granted permissions and its
  security boundaries are managed by you in Shizuku. The App only gets what the shell identity can do
  and does not escalate privileges further.
- **Notification access / media sessions**: used only to show the currently playing music on the
  head-unit home screen.
- **Bluetooth**: pairing with the receiver (pairing only; no Bluetooth audio connection is established).
- **Screen recording (projection) permission**: used only to capture audio when car audio is turned on.

Pairing info, target device names, wallpapers and settings are stored only on this device and are never
uploaded to any server; the App contains no analytics or advertising SDKs.

## Disclaimer of liability

The App is provided "as is", without warranty of any kind, express or implied, including but not
limited to the warranties of merchantability, fitness for a particular purpose and non-infringement.

To the maximum extent permitted by applicable law, the authors and contributors shall not be liable for
any direct or indirect loss arising from the use of or inability to use the App, including but not
limited to: dropped connections, interrupted navigation or calls, data loss, device malfunctions,
vehicle system malfunctions, and accidents and losses caused by operating the phone or being distracted
while driving.

Whether to use the App, how to configure and operate it, and in what situations to use it are entirely
the user's own judgment and at the user's own risk. If you do not agree with any of the above, stop
using and uninstall the App.

## Third-party code and licenses

- The `reference/` directory of this repository (if present) contains **local reference material**; it
  is excluded in `.gitignore` and is not distributed with this repository. The source code and license
  obligations of the reference projects in it (xcertplay / DiPlay are GPL-3.0, etc.) do not apply to
  this repository.
- This project is an independent implementation that only refers to publicly available protocol formats
  and published research.
- **Do not distribute keys, certificates or proprietary material extracted from any firmware or
  device.**

## License

The App's source code is released under the [MIT License](LICENSE).

---

*Last updated: 2026-10-18*
