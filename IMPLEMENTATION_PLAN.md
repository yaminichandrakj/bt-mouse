# BT Mouse & PC Companion - Implementation Plan

**Status:** Active roadmap | **Updated:** 2026-10-24 | **Current version:** 1.3.11
**Owner:** Codex | **Release:** [v1.3.11 pending publish](release-notes/v1.3.11.md) · [v1.3.10 released](https://github.com/yaminichandrakj/bt-mouse/releases/tag/v1.3.10)

## Product objective

Make the Android phone a dependable PC input and companion device over Bluetooth, local Wi-Fi, or authenticated remote access. Basic Bluetooth HID must remain usable without the PC companion. Companion-only functions must show their real connection and permission state. Recover safely, display fresh PC data, and make camera, audio, and recording sessions explicit.

## Current implementation snapshot

Source status below distinguishes CI/build verification from physical-device verification. v1.3.11 passed the GitHub Actions version check, 23 companion regression checks, Android release build compatibility, and Windows companion packaging. The GitHub Release contains both artifacts. Physical-device and updater checks remain open where listed.

| Area | Present in source | Remaining |
|---|---|---|
| Security and pairing | PIN-authenticated UDP commands, persistent PIN, validated app launch, authenticated WebSocket, transport-specific state | Recheck integration and preserve authorization gates |
| Quick Apps | Curated Start Menu shortcuts; discovery and add/remove sync; phone launch notifications and missing shortcut reporting; cache fallback preserves saved entries | Hardware checks on target PC/phone |
| Tasks and stats | Cached/default response then background refresh; measured CPU sample; up to 200 processes sorted by usage; end-task result returned and list refreshed | Compare live values with Task Manager on target PC |
| PC webcam | Snapshot gallery stored in phone Pictures directory with delete support; PC snapshots in `%LOCALAPPDATA%\BTMouse\Snapshots`; live preview survives navigation; MP4 recordings | Verify codecs, webcam lifecycle, and playback on target PC/phone |
| Recordings | Full desktop recordings table with search, category filtering, sort, and Explorer openers; delete support on both PC and phone with confirmation | Verify on target devices |
| PC master volume & media | Hardened COM threading across pycaw and WinRT; Playing-session priority in SMTC; phone decoded artwork display separate from video preview | Device checks on target audio configurations |
| Live audio | PC mic and phone mic routes; PC output resampling/channel conversion; first-audio and actual recording status; live controls inside Camera & Mic | Verify latency, device changes, interruption recovery |
| Startup/update | Windows sign-in option, Android installer handoff, desktop updater retry/rollback logic, version consistency in Actions | Android background-session service and auto-reconnect (Phase 3) |
| Desktop UI | Python companion packaged as a one-file EXE; DPI awareness and version/path title; new Recordings manager tab | Native Windows app migration not done |
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

### Phase 3 - Android background sessions, startup, reconnect, and repair (high effort; current priority)

- Keep an explicitly user-started PC-camera or live-audio session connected while the Android app is backgrounded using a user-visible foreground service and notification where platform rules allow. Provide explicit stop/disconnect controls.
- Do not claim a session survives Android force-stop; never auto-start camera/audio capture.
- Add a connection supervisor for user-approved saved devices/endpoints with backoff, cancel/disable controls, stale-state cleanup, and sleep/wake recovery.
- Verify Windows sign-in startup uses the correct companion executable, starts minimized as configured, and never starts camera/audio.
- Add Android startup/boot behavior only where platform rules permit it. Keep endpoints and credentials protected; do not silently pair with unknown PCs or bypass user approval.
- Prevent retry loops, duplicate sockets/commands, held buttons, excess battery use, and orphaned media sessions.

**Acceptance:** sleep, Wi-Fi interruption, companion restart, or app backgrounding either preserves/reconnects an explicitly user-started session within Android rules or presents a clear recoverable state.

### Phase 4 - Concurrent transports and measured failover (very high effort)

- Keep Bluetooth, local Wi-Fi, and authenticated WAN available concurrently where supported.
- Measure authenticated connection health and latency. Select a healthy command path instead of treating an open socket as proof of a working connection.
- Use one command path at a time, with sequencing and deduplication so input is never delivered twice.
- Resynchronize connection/media state and transfer or stop live streams safely when changing paths.
- Show the active path and provide manual override; show interruptions clearly when seamless handoff is unsafe.

**Acceptance:** network changes do not duplicate input, leave held buttons, display stale status, or leak camera/audio streams.

### Phase 5 - Phone camera as a PC camera source (very high effort)

- Add a user-started Android camera session with preview, front/back switching, resolution/frame-rate controls, privacy indicator, permission handling, and stop.
- Use bounded adaptive streaming over authenticated transports.
- Prototype a Windows Media Foundation virtual-camera endpoint; investigate supported Windows builds, registration, signing, installation, and packaging.
- Keep phone-camera streaming separate from PC webcam capture and browser preview. Respect Android background and lock-screen restrictions.

**Acceptance:** supported PC apps can select the phone camera; switching/stopping releases it; unsupported configurations show a clear reason.

### Phase 6 - Phone-based laptop unlock feasibility (exploratory; very high/platform dependent)

- Research whether Windows Hello/passkey/FIDO2 or a signed Windows credential-provider extension can support a phone authenticator on target Windows versions.
- Require explicit phone biometric/device authentication and cryptographic challenge-response. Never store or transmit the Windows password.
- Keep unlock unavailable unless a supported, security-reviewed sign-in path exists. Do not inject keystrokes on the secure desktop or bypass Windows sign-in.

**Acceptance:** document a supported prototype path or close the feature as unsupported for the target platform; never ship password injection.

### Phase 7 - Desktop UI sharpness and native Windows companion (very high effort)

- Verify text sharpness, DPI scaling, fonts, and layout on the user's display.
- The current one-file executable is a Python app packaged with PyInstaller and includes its Python runtime. If a non-Python runtime is required, build a native Windows companion rather than hiding the embedded runtime.
- Inventory pairing, Wi-Fi/WAN setup, Quick Apps, startup, recordings, camera/audio diagnostics, logs, tray behavior, update/rollback, and settings before choosing a native stack.
- Preserve protocol/security, settings, recordings, startup, Android compatibility, and updater rollback. Keep the Python app until native parity passes.

**Acceptance:** current UI is sharp at target scaling. A native replacement has feature and upgrade parity and does not lose user settings or recordings.

### Phase 8 - Updaters, GitHub Actions, and future releases (medium/high effort; release blocker)

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
