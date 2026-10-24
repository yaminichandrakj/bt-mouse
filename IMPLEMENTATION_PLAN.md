# BT Mouse & PC Companion - Implementation Plan

**Status:** Active roadmap | **Updated:** 2026-10-24 | **Current version:** 1.3.16
**Owner:** Codex | **Release:** [v1.3.16 (Remediation)](release-notes/v1.3.16.md) · [v1.3.15 released](https://github.com/yaminichandrakj/bt-mouse/releases/tag/v1.3.15) · [v1.3.14 released](https://github.com/yaminichandrakj/bt-mouse/releases/tag/v1.3.14) · [v1.3.13 released](https://github.com/yaminichandrakj/bt-mouse/releases/tag/v1.3.13) · [v1.3.12 released](https://github.com/yaminichandrakj/bt-mouse/releases/tag/v1.3.12) · [v1.3.11 released](https://github.com/yaminichandrakj/bt-mouse/releases/tag/v1.3.11) · [v1.3.10 released](https://github.com/yaminichandrakj/bt-mouse/releases/tag/v1.3.10)

## Product objective

Make the Android phone a dependable PC input and companion device over Bluetooth, local Wi-Fi, or authenticated remote access. Basic Bluetooth HID must remain usable without the PC companion. Companion-only functions must show their real connection and permission state. Recover safely, display fresh PC data, and make camera, audio, and recording sessions explicit.

## Current implementation snapshot

Source status below distinguishes CI/build verification from physical-device verification. `v1.3.16` resolves the P0 connectivity, lifecycle, and security defects identified in the post-v1.3.15 deep code audit across Bluetooth, Wi-Fi, and Remote WAN.

| Area | Present in source | Remaining |
|---|---|---|
| Security and pairing | PIN-authenticated UDP commands, persistent PIN embedded in local Wi-Fi/WAN QR codes, validated app launch, authenticated WebSocket, `allowBackup="false"`, localhost-bound (`127.0.0.1`) phone camera HTTP server | Rotate Android release keystore into GitHub Actions secrets |
| Connectivity & coordinator lifecycle | Unconditional `ConnectionCoordinator.initialize()` in `BtMouseApplication` & `MainActivity`; resilient multi-homed `connect_ack`; non-destructive tab browsing; dynamic RTT failover; dedicated companion command routing; 65,507-byte UDP receive buffer; sliding-window sequence deduplication | Verify across target PC and phone hardware |
| Quick Apps | Curated Start Menu shortcuts; discovery and add/remove sync; phone launch notifications and missing shortcut reporting; cache fallback preserves saved entries | Hardware checks on target PC/phone |
| Tasks and stats | Cached/default response then background refresh; measured CPU sample; up to 200 processes sorted by usage; end-task result returned and list refreshed | Compare live values with Task Manager on target PC |
| PC webcam | Snapshot gallery stored in phone Pictures directory with delete support; PC snapshots in `%LOCALAPPDATA%\BTMouse\Snapshots`; live preview survives navigation; MP4 recordings | Verify codecs, webcam lifecycle, and playback on target PC/phone |
| Phone as PC webcam | Android Camera2 JPEG capture; rear/front lens flip; 360p/480p/720p; foreground service camera type; PC HTTP MJPEG server strictly on `127.0.0.1:8766`; clean `pyvirtualcam` release on restart; Bluetooth RFCOMM support | Test with OBS/Zoom/Teams on target PC |
| Recordings | Full desktop recordings table with search, category filtering, sort, and Explorer openers; delete support on both PC and phone with confirmation | Verify on target devices |
| PC master volume & media | Hardened COM threading across pycaw and WinRT; Playing-session priority in SMTC; phone decoded artwork display separate from video preview | Device checks on target audio configurations |
| Live audio | PC mic and phone mic routes; PC output resampling/channel conversion; first-audio and actual recording status; live controls inside Camera & Mic | Verify latency, device changes, interruption recovery |
| Startup/update | Windows sign-in self-healing, Android 14+ hardened foreground session service, application-scoped coordinator, connection supervisor with exponential backoff | Verify full in-app update cycle on physical devices |
| Desktop UI | Python companion packaged as a one-file EXE; calibrated High-DPI window geometry without double-scaling; Recordings manager tab; Phone as Webcam tab | Native C# WinUI 3 Windows app migration pending (Phase 7) |
| Laptop unlock | Explicitly unsupported in server (`Winlogon` secure desktop + UIPI + LSA isolation) | Closed as unsupported on consumer Windows platforms |

## Product and security constraints

- Keep the Android application ID and signing identity so installed copies can upgrade.
- Authenticate before PC actions. Use authenticated TLS (`wss://`) for Internet use; identify `ws://` as trusted-LAN only.
- Validate app IDs and shortcut paths. Never execute a client-provided shell string.
- Stop capture when the user stops or disconnects. Preserve an explicitly started session across Android navigation and backgrounding via `CompanionSessionService`. Never broadcast media to unrelated clients.
- Browser preview captures only the user-selected visible rectangle. It may include surrounding content inside that rectangle; protected video may be black. Do not capture the full desktop/browser silently.
- Webcam recording, browser-region preview, and future screen recording are different features. Current video recording is for the PC webcam; selected-region preview is not a screen recorder.
- Android camera/microphone use is user-started and permission-aware. Background/locked-screen operation is subject to Android foreground-service and permission rules.
- Do not bump versions or publish until the release gates pass.

## Phased implementation plan

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

### Phase 3 - Android background sessions, startup, reconnect, and repair (Completed in v1.3.12, Hardened in v1.3.16)
- **Android Foreground Service:** `CompanionSessionService` keeps user-started camera feeds and live audio active in background; guarded against Android 14+ foreground service `SecurityException`; application-scoped `ConnectionCoordinator` survives activity recreation.
- **Connection Supervisor:** Exponential backoff (2s, 4s, 8s, 16s, 30s) auto-reconnection for saved endpoints on unexpected network drops with live UI countdown, manual retry, and immediate cancellation.
- **Intentional vs Unintentional Disconnect:** Cleanly distinguishes user disconnect actions from network drops; tab navigation in `ConnectionScreen` no longer disconnects active sockets.
- **Windows Sign-In Self-Healing:** Companion automatically verifies and heals the registry `CurrentVersion\Run` path to point to the active binary with `--minimized`.

### Phase 4 - Concurrent transports and measured failover (Completed in v1.3.13, Remediated in v1.3.16)
- **Real-Time Latency & Health Measurement:** Continuous round-trip ping time calculation across Wi-Fi, Bluetooth RFCOMM, and WAN with jitter threshold monitoring; dynamic RTT comparison selects the lowest-latency healthy companion path.
- **Sliding-Window Command Deduplication:** Input commands are stamped with client session UUID and sequence number (`seq`); server-side bounded sliding window drops exact duplicates across concurrent transports without discarding out-of-order UDP packets.
- **Companion vs. HID Routing:** Dedicated `currentCompanionConnection` routes rich protocol commands to Wi-Fi, Bluetooth RFCOMM, or WAN even when Bluetooth HID is connected.

### Phase 5 - Phone camera as a PC camera source (Completed in v1.3.14, Hardened in v1.3.16)
- **Android Camera2 Wireless Streamer:** Hardware-accelerated JPEG encoding via `ImageReader` directly from Android `Camera2` API; dynamic front/rear camera flip; resolution profiles (360p, 480p, 720p HD) and frame rate selectors (15, 24, 30 fps); supported across Wi-Fi, WAN, and Bluetooth RFCOMM.
- **PC Companion Virtual Camera & Localhost MJPEG Server:** Embedded HTTP MJPEG server bound strictly to `127.0.0.1:8766/phone_cam/mjpeg`; clean `pyvirtualcam` release before re-initialization on lens/resolution switches; 65,507-byte UDP receive buffer prevents `WSAEMSGSIZE` packet drops on Windows.

### Phase 6 - Phone-based laptop unlock feasibility (Completed: Researched and Closed as Unsupported per Security Model)
- **Feasibility Evaluation Completed:** Documented platform constraints, Windows Secure Desktop (`Winlogon`) isolation, LSA authentication requirements, and UIPI enforcement in [`docs/laptop_unlock_feasibility.md`](docs/laptop_unlock_feasibility.md).
- **Candidate Paths Analyzed:**
  - *Custom Credential Provider (C++ In-Process COM DLL):* Rejected due to `LogonUI.exe` (`NT AUTHORITY\SYSTEM`) reliability risk, administrative registration requirements, LSA credential verification boundaries, and incompatibility with portable execution.
  - *Companion Device Framework (CDF):* Deprecated in Windows 10 2004 and removed for consumer unlock in Windows 11.
  - *FIDO2 / CTAP2 Security Keys:* Supported only on Microsoft Entra ID / hybrid domain-joined PCs; unsupported for consumer local or MSA accounts.
  - *Password Caching / Synthetic Input:* Categorically prohibited by product security principles.
- **Architectural Determination:** Workstation Locking (`Win+L`) is supported and active. Remote workstation unlock is permanently closed as unsupported by the platform without security compromise.

### Phase 7 - Desktop UI sharpness and native Windows companion (In Progress: Inventory & Tkinter DPI Fix Complete; Native WinUI 3 Implementation Pending)
- **High-DPI UI Sharpness (Completed):** Implemented Per-Monitor V2 DPI awareness (`SetProcessDpiAwarenessContext(-4)`) and calibrated window geometry in `pc_server/app_gui.py` without double-scaling fonts against fixed containers.
- **Comprehensive Subsystem Inventory (Completed):** Cataloged full native Windows architecture across networking, input injection, SMTC media, WASAPI audio, virtual camera, system diagnostics, and self-healing updaters in [`docs/native_companion_inventory_and_ui_sharpness.md`](docs/native_companion_inventory_and_ui_sharpness.md).
- **Native WinUI 3 Companion Migration (Pending):** Implementation of the C# / WinUI 3 native desktop application to replace the Python/Tkinter companion remains open.

### Phase 8 - Updaters, GitHub Actions, and future releases (In Progress: CI Pipeline Active; Physical Device & Keystore Rotation Verification Pending)
- Verify Android update hash, package/version checks, signing continuity, unknown-source permission return, system installer handoff, cancellation, and successful upgrade.
- Verify Windows update staging, closing the running app, replacing the correct install path, relaunching that same path, version acknowledgement, rollback, and second launch.
- Transition Android release signing material to GitHub Actions secrets (`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`).
