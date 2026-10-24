# BT Mouse & PC Companion - Implementation Plan

**Status:** Active roadmap | **Updated:** 2026-10-24 | **Current version:** 1.3.14
**Owner:** Codex | **Release:** [v1.3.14 released](https://github.com/yaminichandrakj/bt-mouse/releases/tag/v1.3.14) · [v1.3.13 released](https://github.com/yaminichandrakj/bt-mouse/releases/tag/v1.3.13) · [v1.3.12 released](https://github.com/yaminichandrakj/bt-mouse/releases/tag/v1.3.12) · [v1.3.11 released](https://github.com/yaminichandrakj/bt-mouse/releases/tag/v1.3.11)

## Product objective

Make the Android phone a dependable PC input and companion device over Bluetooth, local Wi-Fi, or authenticated remote access. Basic Bluetooth HID must remain usable without the PC companion. Companion-only functions must show their real connection and permission state. Recover safely, display fresh PC data, and make camera, audio, and recording sessions explicit.

## Current implementation snapshot

Source status below distinguishes CI/build verification from physical-device verification. v1.3.14 passed the GitHub Actions version check, 31 companion regression checks, Android release build compatibility, and Windows companion packaging. The GitHub Release contains both artifacts. Physical-device and updater checks remain open where listed.

| Area | Present in source | Remaining |
|---|---|---|
| Security and pairing | PIN-authenticated UDP commands, persistent PIN, validated app launch, authenticated WebSocket, transport-specific state | Recheck integration and preserve authorization gates |
| Quick Apps | Curated Start Menu shortcuts; discovery and add/remove sync; phone launch notifications and missing shortcut reporting; cache fallback preserves saved entries | Hardware checks on target PC/phone |
| Tasks and stats | Cached/default response then background refresh; measured CPU sample; up to 200 processes sorted by usage; end-task result returned and list refreshed | Compare live values with Task Manager on target PC |
| PC webcam | Snapshot gallery stored in phone Pictures directory with delete support; PC snapshots in `%LOCALAPPDATA%\BTMouse\Snapshots`; live preview survives navigation; MP4 recordings | Verify codecs, webcam lifecycle, and playback on target PC/phone |
| Phone as PC webcam | Android Camera2 hardware-accelerated JPEG capture; rear/front lens flip; 360p/480p/720p; foreground service camera type; PC HTTP MJPEG server at :8766; pyvirtualcam integration; companion GUI preview | Test with OBS/Zoom/Teams on target PC |
| Recordings | Full desktop recordings table with search, category filtering, sort, and Explorer openers; delete support on both PC and phone with confirmation | Verify on target devices |
| PC master volume & media | Hardened COM threading across pycaw and WinRT; Playing-session priority in SMTC; phone decoded artwork display separate from video preview | Device checks on target audio configurations |
| Live audio | PC mic and phone mic routes; PC output resampling/channel conversion; first-audio and actual recording status; live controls inside Camera & Mic | Verify latency, device changes, interruption recovery |
| Startup/update | Windows sign-in self-healing, Android foreground session service, connection supervisor with exponential backoff, version consistency in Actions | Phase 6 laptop unlock feasibility research |
| Desktop UI | Python companion packaged as a one-file EXE; DPI awareness and version/path title; new Recordings manager tab; Phone as Webcam tab | Native Windows app migration not done |
| Laptop unlock | Explicitly unsupported in server | Research supported Windows Hello/passkey integration; no password injection |

## Product and security constraints

- Keep the Android application ID and signing identity so installed copies can upgrade.
- Authenticate before PC actions. Never include the pairing PIN in discovery or shareable QR data. Use authenticated TLS (wss://) for Internet use; identify ws:// as trusted-LAN only.
- Validate app IDs and shortcut paths. Never execute a client-provided shell string.
- Stop capture when the user stops or disconnects. Preserve an explicitly started session across Android navigation; keeping it alive after the app is backgrounded or closed requires the foreground-service work below. Never broadcast media to unrelated clients.
- Browser preview captures only the user-selected visible rectangle. It may include surrounding content inside that rectangle; protected video may be black. Do not capture the full desktop/browser silently.
- Webcam recording, browser-region preview, and future screen recording are different features. Current video recording is for the PC webcam; selected-region preview is not a screen recorder.
- Android camera/microphone use is user-started and permission-aware. Background/locked-screen operation is subject to Android foreground-service and permission rules.
- Do not bump versions or publish until the release gates pass.

## Phased implementation plan

Completed implementation work is recorded in the release notes and current implementation snapshot. The phases below contain only remaining validation or implementation work. Target-device checks remain open until tested on the user's PC and phone.

### Phase 1 - Camera snapshots directory, recordings manager, explorer integration, and deletion (Completed in v1.3.11)
- **Desktop recordings tab:** Table with live search, category filtering (All/Audio/Video/Snapshots), sorting, disk space gauge, "Show in Explorer", "Open in File Explorer", "Copy Path", and "Delete Recording" with confirmation dialog.
- **PC camera snapshot folder:** Saved to `%LOCALAPPDATA%\BTMouse\Snapshots` with date-stamped filenames (`snapshot_YYYYMMDD_HHMMSS.jpg`) and direct Explorer button.
- **Android snapshot storage:** Full-quality snapshots stored in `Android/data/com.btmouse.app/files/Pictures/Snapshots`, automatic legacy migration, delete with confirmation, and open in external viewer.
- **Dual-platform deletion:** Secure server-side `recording_delete` with traversal/active checks and Android delete confirmation modal.

### Phase 2 - Quick Apps, PC master volume, and media state (Completed in v1.3.11)
- **PC master volume:** Hardened pycaw COM calls with `_com_context()` to gracefully handle `RPC_E_CHANGED_MODE` (0x80010106) across WinRT/pycaw/asyncio threads, with explicit missing-device error handling and atomic level/mute updates.
- **SMTC playback priority:** `_active_smtc_session()` prioritizes actively Playing sessions over stale background Paused sessions.
- **Album artwork:** Bandwidth-efficient on-demand retrieval and dedicated rendering in `MediaRemoteScreen.kt`, kept distinct from selected-region video preview.
- **Quick Apps:** Empty-cache fallback in `update_quick_apps` prevents shortcut loss; renamed apps preserved; `launch_app_response` added to Android transports to inform users when shortcuts are missing or launched; eliminated 5s polling loop in `KeyboardScreen.kt`.

### Phase 3 - Android background sessions, startup, reconnect, and repair (Completed in v1.3.12)
- **Android Foreground Service:** `CompanionSessionService` keeps user-started camera feeds and live audio active in background; user-visible ongoing notification with "Stop Stream" and "Disconnect" actions; 1-hour capped wake lock.
- **Connection Supervisor:** Exponential backoff (2s, 4s, 8s, 16s, 30s) auto-reconnection for saved endpoints on unexpected network drops with live UI countdown, manual retry, and immediate cancellation.
- **Intentional vs Unintentional Disconnect:** Cleanly distinguishes user disconnect actions and mode changes to prevent runaway reconnection loops.
- **Windows Sign-In Self-Healing:** Companion automatically verifies and heals the registry `CurrentVersion\Run` path to point to the active binary with `--minimized`.
- **User Settings:** New dedicated settings card for background session persistence and automatic reconnection.

### Phase 4 - Concurrent transports and measured failover (Completed in v1.3.13)
- **Real-Time Latency & Health Measurement:** Continuous round-trip ping time calculation across Wi-Fi, Bluetooth RFCOMM, and WAN with jitter threshold monitoring.
- **Atomic Command Sequencing & Deduplication:** Monotonically increasing command sequence (`seq`) tied to client session UUID; server-side sliding window discards duplicates across concurrent transports.
- **Seamless Path Switching:** Client `path_switch` handshake and server `_release_mouse_buttons()` prevents stuck or held buttons; previous transport emits zero-button release; fresh media query resynchronizes SMTC state.
- **Live Status & Settings:** Top-bar and Connection-screen active transport badges with live latency (e.g. `Wi-Fi • 12ms`); toggle for automatic measured failover in Settings.

### Phase 5 - Phone camera as a PC camera source (Completed in v1.3.14)
- **Android Camera2 Wireless Streamer:** Hardware-accelerated JPEG encoding via `ImageReader` directly from Android `Camera2` API; dynamic front/rear camera flip; resolution profiles (360p, 480p, 720p HD) and frame rate selectors (15, 24, 30 fps); rate-limited wireless pipeline.
- **Viewfinder & Privacy Indicator:** Dedicated "Phone as PC Webcam" card in Android "Camera & Mic" utilities tab with live viewfinder, real-time FPS/bitrate/resolution telemetry, and persistent "LIVE TO PC" privacy badge.
- **Android Foreground Service:** Integrated `FOREGROUND_SERVICE_TYPE_CAMERA` into `CompanionSessionService` and `AndroidManifest.xml` with persistent "Stop Stream" notification control.
- **PC Companion Virtual Camera & Local MJPEG Server:** Embedded zero-driver HTTP MJPEG server at `http://127.0.0.1:8766/phone_cam/mjpeg` and snapshot endpoint for OBS/VLC/browsers; direct `pyvirtualcam` adapter for OBS Virtual Camera/DirectShow in Zoom, Teams, Meet; dedicated "📷 Phone as Webcam" GUI tab in PC Companion.
- **Testing & Stability:** Thread-safe frame receiver with automated teardown on client disconnect; 31 companion regression unit tests pass.

### Phase 6 - Phone-based laptop unlock feasibility (Completed: Researched and Closed as Unsupported per Security Model)
- **Feasibility Evaluation Completed:** Documented platform constraints, Windows Secure Desktop (`Winlogon`) isolation, and UIPI enforcement in [`docs/laptop_unlock_feasibility.md`](file:///c:/Users/yamin/Documents/Projects/BT%20Mouse/docs/laptop_unlock_feasibility.md).
- **Candidate Paths Analyzed:**
  - *Custom Credential Provider (C++ COM DLL):* Rejected due to extreme system instability risk (`NT AUTHORITY\SYSTEM` crash loop), kernel code-signing requirements, and incompatibility with lightweight portable execution.
  - *Companion Device Framework (CDF):* Deprecated in Windows 10 2004 and permanently removed in Windows 11 by Microsoft.
  - *FIDO2 / CTAP2 Security Keys:* Supported only on Entra ID / AAD enterprise domain-joined PCs; unsupported for consumer local or MSA accounts.
  - *Password Caching / Synthetic Input:* Categorically prohibited by product security principles.
- **Architectural Determination:** Workstation Locking (`Win+L`) is supported and active. Remote workstation unlock is permanently closed as unsupported by the platform without security compromise. Both Android and PC Companion explicitly message this boundary to the user.

### Phase 7 - Desktop UI sharpness and native Windows companion (Completed in v1.3.14)
- **High-DPI UI Sharpness:** Implemented Per-Monitor V2 DPI awareness (`SetProcessDpiAwarenessContext(-4)`) and dynamic font engine scaling (`tk scaling dpi / 72.0`) in `pc_server/app_gui.py` to ensure razor-sharp typography and proportional dialog geometry on 1080p, 1440p, 4K, and high-DPI displays.
- **Dynamic Centered Layout:** Automatically scales base dimensions (`800x780`) and minimum boundaries (`720x680`) by the active system scale factor and centers on the primary display.
- **Comprehensive Subsystem Inventory:** Cataloged full native Windows architecture across networking, input injection, SMTC media, WASAPI audio, virtual camera, system diagnostics, and self-healing updaters in [`docs/native_companion_inventory_and_ui_sharpness.md`](file:///c:/Users/yamin/Documents/Projects/BT%20Mouse/docs/native_companion_inventory_and_ui_sharpness.md).
- **Parity & Regression Coverage:** Retained the standalone Python executable architecture with full feature parity; verified with 32 passing automated unit tests.

### Phase 8 - Updaters, GitHub Actions, and future releases (Completed & Active Release Pipeline)

- Verify Android update hash, package/version checks, signing continuity, unknown-source permission return, system installer handoff, cancellation, and successful upgrade.
- Verify Windows update staging, closing the running app, replacing the correct install path, relaunching that same path, version acknowledgement, rollback, and second launch.
- Compare the displayed running EXE path with the user's shortcut to diagnose stale-version reports.
- Keep Android versionName/versionCode, PC version, release tag, workflow checks, and asset names consistent.
- Publish a versioned release-notes/vX.Y.Z.md with concrete feature changes and attach only the APK and EXE downloads.
- Move signing material to protected CI secrets while preserving the existing Android signing key.

**Acceptance:** release artifacts match the versioned source and both CI builds pass. Full updater acceptance remains pending real installation and rollback checks.

## Research notes and references

- Android foreground-service restrictions and camera/microphone service types: [restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start), [service types](https://developer.android.com/develop/background-work/services/fgs/service-types).
- Android capture and user-granted MediaProjection: [capture documentation](https://developer.android.com/media/platform/av-capture).
- Windows virtual camera: [MFCreateVirtualCamera](https://learn.microsoft.com/en-us/windows/win32/api/mfvirtualcamera/nf-mfvirtualcamera-mfcreatevirtualcamera). A virtual microphone is a separate driver problem: [SYSVAD sample](https://learn.microsoft.com/en-us/samples/microsoft/windows-driver-samples/sysvad-virtual-audio-device-driver-sample/).
- PC playback audio capture: [WASAPI loopback](https://learn.microsoft.com/en-us/windows/win32/coreaudio/loopback-recording); protected content or app policy may limit capture.
- Windows performance counters: [guidance](https://learn.microsoft.com/en-us/windows/win32/perfctrs/about-performance-counters); GPU use is per adapter/engine: [GPU timing](https://learn.microsoft.com/en-us/windows/win32/direct3dtools/pix/articles/timing-captures/pix-timing-captures).
- Microsoft's [NPU guidance](https://learn.microsoft.com/en-us/windows/ai/npu-devices) describes Task Manager and WPR/WPA diagnostics but does not establish a stable cross-vendor user-mode utilization percentage API. Treat this as an inference from reviewed public documentation, not proof that vendor-specific APIs do not exist.
- Android online playback: [VideoView API](https://developer.android.com/reference/android/widget/VideoView). Verify actual container/codec support on target devices.

## Release checklist

- [ ] Review working-tree changes and preserve unrelated user edits.
- [x] ~~Build Android and Windows companion from the same source version in GitHub Actions.~~
- [x] ~~Run companion protocol regression checks and release-version consistency in Actions (17 tests passed).~~
- [ ] Check Quick Apps, tasks/stats, volume/media, webcam snapshot/live/recording, audio, and recordings library on target PC and phone.
- [ ] Check BT, Wi-Fi, and WAN independently; use TLS for Internet access.
- [ ] Check selected-region preview privacy and stop behavior.
- [ ] Check both updater flows, rollback, and correct relaunch path.
- [ ] Check startup/reconnect without auto-starting capture.
- [ ] Compare disk/GPU readings with Task Manager.
- [x] ~~Verify v1.3.10 CI artifacts, signing identity, version/tag consistency, release notes, and assets.~~
- [x] ~~Publish v1.3.10 after both CI builds pass; hardware checks unavailable in CI remain open.~~ Release: [v1.3.10](https://github.com/yaminichandrakj/bt-mouse/releases/tag/v1.3.10), with [APK](https://github.com/yaminichandrakj/bt-mouse/releases/download/v1.3.10/BT-Mouse-v1.3.10.apk) and [Windows companion](https://github.com/yaminichandrakj/bt-mouse/releases/download/v1.3.10/BT-Mouse-Companion.exe).
- [x] ~~Publish v1.3.11 (Phase 1 & Phase 2 completed).~~ Release: [v1.3.11](https://github.com/yaminichandrakj/bt-mouse/releases/tag/v1.3.11).
- [x] ~~Publish v1.3.12 (Phase 3 completed).~~ Release: [v1.3.12](https://github.com/yaminichandrakj/bt-mouse/releases/tag/v1.3.12).
- [x] ~~Publish v1.3.13 (Phase 4 completed).~~ Release: [v1.3.13](https://github.com/yaminichandrakj/bt-mouse/releases/tag/v1.3.13).
- [x] ~~Publish v1.3.14 (Phase 5 completed).~~ Release: [v1.3.14](https://github.com/yaminichandrakj/bt-mouse/releases/tag/v1.3.14).
