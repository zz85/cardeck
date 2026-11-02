package io.github.shaonialife.cardeck.legal

/**
 * 免责声明正文（中文字面量集中在代码里，便于与版本号一起审阅改动）。
 *
 * 写作要点：
 * - **先讲范围与风险，再讲法律条款**——用户最需要知道的是「这东西测过什么、
 *   什么时候会出事」，把免责条款放最后更符合阅读习惯，也更容易真正被读到；
 * - 如实写清测试机型与已知限制（Android 14 以下没有「应用横屏」、行驶中断连的
 *   具体后果），空洞的免责声明没有意义；
 * - 明确写出依赖的系统权限（Shizuku shell、通知使用权），这是用户决定是否
 *   授权的前提；
 * - 明确非官方、无关联，避免让用户误认为与 Apple / 车厂有授权关系。
 *
 * 正文刻意**不用 Markdown 记号**（`**`、`#` 等）：这里直接进 TextView，
 * 那些符号会原样显示。分节标题由 UI 单独加粗。
 */
internal object DisclaimerContent {
    /** 分节：标题加粗显示，正文常规。 */
    data class Section(
        val title: String,
        val body: String,
    )

    val sections: List<Section> =
        listOf(
            Section(
                title = "What this is",
                body =
                    "CarDeck is an unofficial, experimental project: it lets a non-rooted Android phone act as a " +
                        "CarPlay sender (the iPhone role), projecting the head-unit home screen and third-party apps " +
                        "from the phone onto a supported receiver, and injecting the receiver's touch input back into " +
                        "virtual displays on the phone.\n\n" +
                        "This project is not affiliated with Apple Inc. or any car manufacturer, has not received any " +
                        "authorization or certification, and is not an MFi-certified product. \"CarPlay\" and " +
                        "\"AirPlay\" are trademarks of Apple Inc., used here only to describe the compatibility target; " +
                        "no affiliation or endorsement is implied.",
            ),
            Section(
                title = "In development; things may break at any time",
                body =
                    "This is a development/test build. Features, UI and protocol implementation may change at any " +
                        "time, and there may be undiscovered defects. No stability, compatibility or availability is " +
                        "guaranteed.\n\n" +
                        "The only combinations tested so far are:\n" +
                        "· Sender: Honor Magic7 Pro (Android 17)\n" +
                        "· Receiver: Audi MIB3 head unit (real car)\n" +
                        "· Receiver: DiPlay test bench on a Redmi K40 (Android 13)\n\n" +
                        "Other phones, other Android versions and other car models are unverified; they may not work at " +
                        "all, or may show display glitches, misaligned touch or repeated disconnects.\n\n" +
                        "The \"Force landscape\" feature requires Android 14 or later; on Android 13 and earlier it is " +
                        "unavailable (the settings panel will say it is not supported).",
            ),
            Section(
                title = "Driving safety (must read)",
                body =
                    "This app is not a safety-critical system and cannot replace the factory navigation or the " +
                        "vehicle's instrument cluster.\n\n" +
                        "· Complete connecting, pairing and all settings while parked;\n" +
                        "· Any operation while driving must be done by a passenger; the driver must not hold or operate " +
                        "the phone;\n" +
                        "· The wireless link may drop at any time due to signal interference, system sleep, hotspot " +
                        "switching, Bluetooth reconnection and other causes, resulting in a frozen picture, black " +
                        "screen, unresponsive touch or interrupted navigation;\n" +
                        "· A disconnect while driving may cause you to miss a turn, lose your route or become distracted. " +
                        "Always keep the head unit's factory navigation as a safety backup, and take full " +
                        "responsibility for how you actually drive.",
            ),
            Section(
                title = "System permissions and data",
                body =
                    "To work properly, this app requests and uses the following capabilities. Make sure you " +
                        "understand them before granting access:\n\n" +
                        "· Shizuku (shell permission): used to create trusted virtual displays, inject touch events, " +
                        "write system compatibility settings (e.g. forcing apps into landscape), and read window and " +
                        "display state. This app will not use it to run commands unrelated to this app's " +
                        "functions;\n" +
                        "· Notification access / media sessions: used only to show the currently playing music on the " +
                        "head-unit home screen (title, artist, cover art, playback progress);\n" +
                        "· Bluetooth: used to pair with the receiver (pairing only; no Bluetooth audio connection is " +
                        "established);\n" +
                        "· Screen recording (projection) permission: used only to capture audio when car audio is " +
                        "turned on.\n\n" +
                        "Pairing info, target device names, wallpapers and settings are stored only on this device and " +
                        "are never uploaded to any server; this app contains no analytics or advertising SDKs.",
            ),
            Section(
                title = "Disclaimer of liability",
                body =
                    "This app is provided \"as is\", without warranty of any kind, express or implied, including " +
                        "but not limited to the warranties of merchantability, fitness for a particular purpose and " +
                        "non-infringement.\n\n" +
                        "To the maximum extent permitted by applicable law, the authors and contributors shall not be " +
                        "liable for any direct or indirect loss arising from the use of or inability to use this app, " +
                        "including but not limited to: dropped connections, interrupted navigation or calls, data " +
                        "loss, device malfunctions, vehicle system malfunctions, and accidents and losses caused by " +
                        "operating the phone or being distracted while driving.\n\n" +
                        "Whether you use this app, how you configure and operate it, and in what situations you use " +
                        "it are entirely your own judgment and at your own risk. If you do not agree with any of the " +
                        "above, tap \"Decline\" and uninstall this app.",
            ),
            Section(
                title = "Open source and feedback",
                body =
                    "This is a personal experimental project, open-sourced under the MIT License. Issue reports are " +
                        "welcome, but no timeline for fixes is promised. Because it involves protocol implementation " +
                        "and differences between third-party ROMs, some issues may not be solvable.\n\n" +
                        "For the full terms (unofficial status, trademark ownership, safety warnings, tested scope), " +
                        "see DISCLAIMER.md in the repository root, which shares the same source as this page.",
            ),
        )
}
