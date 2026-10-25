# BT Mouse & PC Companion - Implementation Plan

**Status:** Active roadmap | **Updated:** 2026-10-25 | **Current version:** 1.4.3
**Owner:** Codex | **Release:** [v1.4.3 (Off-network publish fix)](release-notes/v1.4.3.md) · [v1.4.2 (Off-network remote access over a Cloudflare quick tunnel)](release-notes/v1.4.2.md) · [v1.4.1 (Native icons, single instance, truthful connection status & camera aspect fixes)](release-notes/v1.4.1.md) · [v1.4.0 released](https://github.com/yaminichandrakj/bt-mouse/releases/tag/v1.4.0) · [v1.3.16](release-notes/v1.3.16.md) · [v1.3.15 released](https://github.com/yaminichandrakj/bt-mouse/releases/tag/v1.3.15) · [v1.3.14 released](https://github.com/yaminichandrakj/bt-mouse/releases/tag/v1.3.14) · [v1.3.13 released](https://github.com/yaminichandrakj/bt-mouse/releases/tag/v1.3.13) · [v1.3.12 released](https://github.com/yaminichandrakj/bt-mouse/releases/tag/v1.3.12) · [v1.3.11 released](https://github.com/yaminichandrakj/bt-mouse/releases/tag/v1.3.11) · [v1.3.10 released](https://github.com/yaminichandrakj/bt-mouse/releases/tag/v1.3.10)

## Product objective

Make the Android phone a dependable PC input and companion device over Bluetooth, local Wi-Fi, or authenticated remote access. Basic Bluetooth HID must remain usable without the PC companion. Companion-only functions must show their real connection and permission state. Recover safely, display fresh PC data, and make camera, audio, and recording sessions explicit.

## Current implementation snapshot

Source status below distinguishes CI/build verification from physical-device verification. `v1.4.0` delivers the native Windows 11 `PySide6` Fluent desktop companion architecture, seamless video/audio stream continuity across transport failover, single-file muxed `.mp4` video+audio recording, dedicated `Videos/`, `Audios/`, and `Snapshots/` folders on both PC and phone, reliable multi-session Phone-as-Webcam streaming with explicit orientation controls, and fullscreen video viewers on both devices.

| Area | Present in source | Remaining |
|---|---|---|
| Security and pairing | PIN-authenticated UDP commands, persistent PIN embedded in local Wi-Fi/WAN QR codes, validated app launch, authenticated WebSocket, `allowBackup="false"`, localhost-bound (`127.0.0.1`) phone camera HTTP server | Rotate Android release keystore into GitHub Actions secrets |
| Connectivity & coordinator lifecycle | Unconditional `ConnectionCoordinator.initialize()` in `BtMouseApplication` & `MainActivity`; resilient multi-homed `connect_ack`; non-destructive tab browsing; dynamic RTT failover; reactive `StateFlow` stream multiplexing; dynamic server stream responder migration on failover | Verify across target PC and phone hardware |
| Quick Apps | Curated Start Menu shortcuts; discovery and add/remove sync; phone launch notifications and missing shortcut reporting; cache fallback preserves saved entries | Hardware checks on target PC/phone |
| Tasks and stats | Cached/default response then background refresh; measured CPU sample; up to 200 processes sorted by usage; end-task result returned and list refreshed | Compare live values with Task Manager on target PC |
| PC webcam | Dedicated `Videos/`, `Audios/`, `Snapshots/` folders on both PC and phone; automatic FFmpeg muxing of webcam video + mic audio into a single `.mp4` in `Videos/`; fullscreen pinch-to-zoom viewer on phone and fullscreen window on PC | Verify codecs, webcam lifecycle, and playback on target PC/phone |
| Phone as PC webcam | Generation-safe Camera2 session reopen; adaptive JPEG compression (`<= 35 KB`); explicit Landscape 16:9 / Portrait 9:16 + Rotate 90° alignment controls; large responsive PC viewfinder + `⛶ Fullscreen` window; PC MP4 recording to `Videos/` | Test with OBS/Zoom/Teams on target PC |
| Recordings | Full desktop `QTableWidget` media library with search, category filtering (`Videos`, `Audios`, `Snapshots`), sorting, and Explorer openers; Android Library tab with category filter chips and delete confirmation | Verify on target devices |
| PC master volume & media | Hardened COM threading across pycaw and WinRT; Playing-session priority in SMTC; phone decoded artwork display separate from video preview | Device checks on target audio configurations |
| Live audio | PC mic and phone mic routes; seamless responder migration across transport failover; standalone audio saved to `Audios/` | Verify latency, device changes, interruption recovery |
| Startup/update | Windows sign-in self-healing, Android 14+ hardened foreground session service, application-scoped coordinator, connection supervisor with exponential backoff | Verify full in-app update cycle on physical devices |
| Desktop UI | Hardware-accelerated `PySide6` Windows 11 Fluent desktop companion (`pc_server/app_gui.py`) with Windows 11 DWM dark frame, DPI-independent flexible layouts that never clip text, and fullscreen video modals | Optional C# WinUI 3 port remains documented in `docs/` |
| Laptop unlock | Explicitly unsupported in server (`Winlogon` secure desktop + UIPI + LSA isolation) | Closed as unsupported on consumer Windows platforms |

## Product and security constraints

- Keep the Android application ID and signing identity so installed copies can upgrade.
- Authenticate before PC actions. Use authenticated TLS (`wss://`) for Internet use; identify `ws://` as trusted-LAN only.
- Validate app IDs and shortcut paths. Never execute a client-provided shell string.
- Stop capture when the user stops or disconnects. Preserve an explicitly started session across Android navigation, transport failover, and backgrounding via `CompanionSessionService`. Never broadcast media to unrelated clients.
- Browser preview captures only the user-selected visible rectangle. It may include surrounding content inside that rectangle; protected video may be black. Do not capture the full desktop/browser silently.
- Webcam recording, browser-region preview, and future screen recording are different features. Current video recording is for the PC webcam and Phone camera; selected-region preview is not a screen recorder.
- Android camera/microphone use is user-started and permission-aware. Background/locked-screen operation is subject to Android foreground-service and permission rules.
- Do not bump versions or publish until the release gates pass.

## Phased implementation plan

### Phase 1 - Dedicated media directories (`Videos`, `Audios`, `Snapshots`), recordings manager, muxed MP4 recording, and deletion (Completed in v1.3.11, Upgraded in v1.4.0)
- **Three Dedicated Media Folders on Both PC and Phone:**
  - PC: `%LOCALAPPDATA%\BTMouse\Videos`, `%LOCALAPPDATA%\BTMouse\Audios`, and `%LOCALAPPDATA%\BTMouse\Snapshots` with automatic migration from legacy `Recordings/`.
  - Android: `Android/data/com.btmouse.app/files/Videos`, `Audios`, and `Snapshots` with automatic migration and folder filter chips (`All`, `Videos`, `Audios`, `Snapshots`).
- **Combined Video + Audio Recording (`.mp4` Muxing):** Live webcam recording with microphone audio automatically muxes the `.mp4` video and `.wav` audio track via `imageio-ffmpeg` / `ffmpeg` into a single combined `.mp4` file in `Videos/`.
- **Desktop Media Library:** Searchable, sortable `PySide6` `QTableWidget` with category filter dropdown, disk usage gauge, and 1-click Explorer openers for `Videos/`, `Audios/`, and `Snapshots/`.

### Phase 2 - Quick Apps, PC master volume, and media state (Completed in v1.3.11)
- **PC master volume:** Hardened pycaw COM calls with `_com_context()` to gracefully handle `RPC_E_CHANGED_MODE` (0x80010106) across WinRT/pycaw/asyncio threads, with explicit missing-device error handling and atomic level/mute updates.
- **SMTC playback priority:** `_active_smtc_session()` prioritizes actively Playing sessions over stale background Paused sessions.
- **Album artwork:** Bandwidth-efficient on-demand retrieval and dedicated rendering in `MediaRemoteScreen.kt`, kept distinct from selected-region video preview.
- **Quick Apps:** Empty-cache fallback in `update_quick_apps` prevents shortcut loss; renamed apps preserved; `launch_app_response` added to Android transports to inform users when shortcuts are missing or launched.

### Phase 3 - Android background sessions, startup, reconnect, and repair (Completed in v1.3.12, Hardened in v1.3.16)
- **Android Foreground Service:** `CompanionSessionService` keeps user-started camera feeds and live audio active in background; guarded against Android 14+ foreground service `SecurityException`; application-scoped `ConnectionCoordinator` survives activity recreation.
- **Connection Supervisor:** Exponential backoff (2s, 4s, 8s, 16s, 30s) auto-reconnection for saved endpoints on unexpected network drops with live UI countdown, manual retry, and immediate cancellation.
- **Intentional vs Unintentional Disconnect:** Cleanly distinguishes user disconnect actions from network drops; tab navigation in `ConnectionScreen` no longer disconnects active sockets.
- **Windows Sign-In Self-Healing:** Companion automatically verifies and heals the registry `CurrentVersion\Run` path to point to the active binary with `--minimized`.

### Phase 4 - Concurrent transports, measured failover, and seamless stream migration (Completed in v1.3.13, Upgraded in v1.4.0)
- **Real-Time Latency & Health Measurement:** Continuous round-trip ping time calculation across Wi-Fi, Bluetooth RFCOMM, and WAN with jitter threshold monitoring; dynamic RTT comparison selects the lowest-latency healthy companion path.
- **Seamless Stream Continuity Across Failover:** Active camera and live audio streams dynamically migrate their server-side responder callbacks (`_refresh_camera_stream_lease` and `LiveAudioManager.touch_owner`) and Android `StateFlow` collectors when switching transports so video and audio streams never stop during failover.
- **Sliding-Window Command Deduplication:** Input commands are stamped with client session UUID and sequence number (`seq`); server-side bounded sliding window drops exact duplicates across concurrent transports without discarding out-of-order UDP packets.

### Phase 5 - Phone camera as a PC camera source, orientation controls & fullscreen viewers (Completed in v1.3.14, Upgraded in v1.4.0)
- **Android Camera2 Wireless Streamer:** Generation-safe Camera2 reopen lifecycle, adaptive JPEG compression (`<= 35 KB`) so 360p/480p/720p frames fit within UDP datagrams, explicit **Landscape 16:9** vs **Portrait 9:16** orientation modes, and **Rotate 90°** alignment controls.
- **Large Viewfinders & Fullscreen Viewers on Both Devices:** Fullscreen pinch-to-zoom modal (`FullscreenVideoModal`) on Android and hardware-accelerated `AspectRatioVideoWidget` + `FullscreenVideoWindow` (`F11` / double-click) on the Windows PC companion for both Phone Camera and PC Webcam streams.

### Phase 6 - Phone-based laptop unlock feasibility (Completed: Researched and Closed as Unsupported per Security Model)
- **Feasibility Evaluation Completed:** Documented platform constraints, Windows Secure Desktop (`Winlogon`) isolation, LSA authentication requirements, and UIPI enforcement in [`docs/laptop_unlock_feasibility.md`](docs/laptop_unlock_feasibility.md).
- **Architectural Determination:** Workstation Locking (`Win+L`) is supported and active. Remote workstation unlock is permanently closed as unsupported by the platform without security compromise.

### Phase 7 - Native Windows 11 Desktop Companion UI & Android UX Overhaul (Completed in v1.4.0)
- **Native Windows 11 `PySide6` Desktop Companion (`pc_server/app_gui.py`):** Replaced legacy Tkinter with a hardware-accelerated `PySide6` Fluent Windows 11 UI featuring DWM dark titlebars, DPI-independent flexible layouts that never clip sidebar or card text, a dedicated PC Webcam Preview tab, and fullscreen video windows.
- **Intuitive Android 5-Tab Navigation & Quick Command Bar:** Promoted **Cameras** to a top-level bottom navigation tab and added a persistent **Quick Command Bar** on the **Control Pad** for 1-tap switching between Trackpad and Pen Slate plus inline Type, Play/Pause, Mute, and Lock PC controls.

### Phase 8 - Updaters, GitHub Actions, and future releases (In Progress: CI Pipeline Active; Physical Device & Keystore Rotation Verification Pending)
- Verify Android update hash, package/version checks, signing continuity, unknown-source permission return, system installer handoff, cancellation, and successful upgrade.
- Verify Windows update staging, closing the running app, replacing the correct install path, relaunching that same path, version acknowledgement, rollback, and second launch.
- Transition Android release signing material to GitHub Actions secrets (`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`).
