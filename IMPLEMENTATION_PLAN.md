# BT Mouse & PC Companion - Implementation Plan

**Status:** Active roadmap | **Updated:** 2026-10-24 | **Current version:** 1.3.10
**Owner:** Codex | **Release:** v1.3.10 release candidate · [feature notes](release-notes/v1.3.10.md)

## Product objective

Make the Android phone a dependable PC input and companion device over Bluetooth, local Wi-Fi, or authenticated remote access. Basic Bluetooth HID must remain usable without the PC companion. Companion-only functions must show their real connection and permission state. Recover safely, display fresh PC data, and make camera, audio, and recording sessions explicit.

## Current implementation snapshot

Source status below distinguishes CI/build verification from physical-device verification. v1.3.9 passed the GitHub Actions version check, 17 companion regression checks, Android release build, and Windows companion packaging. The GitHub Release contains both artifacts. Physical-device and updater checks remain open where listed.

| Area | Present in source | Remaining |
|---|---|---|
| Security and pairing | PIN-authenticated UDP commands, persistent PIN, validated app launch, authenticated WebSocket, transport-specific state | Recheck integration and preserve authorization gates |
| Quick Apps | PC companion can add, rename, remove, and share curated Start Menu shortcuts; phone can add/remove discovered PC shortcuts from its shared list | Build/device-check async refresh and phone management, including preloaded entries |
| Tasks and stats | Cached/default response then background refresh; measured CPU sample; up to 200 processes sorted by usage; end-task result returned and list refreshed | Compare live values with Task Manager on target PC |
| PC webcam | Snapshot gallery stored on phone; live preview survives navigation within app; live recording indicator and errors; new MP4 recordings; old MJPEG AVI converts when opened/downloaded | Verify codecs, webcam lifecycle, conversion, and playback on target PC/phone |
| Recordings | Authenticated listing and chunked download, integrity checks, Android download/open and online playback; desktop retention settings | Verify conversion/transfer/playback and retention on target devices |
| Live audio | PC mic and phone mic routes; PC output resampling/channel conversion; first-audio and actual recording status; live controls now inside Camera & Mic | Verify latency, device changes, interruption recovery, playback and recording integrity; device selector and echo handling missing |
| Browser media | SMTC and browser audio-session detection; selected-screen-region preview with phone toggle in source; stable player identification; corrected Playing/Paused mapping and transient-session state retention | Verify player recognition, transport limits, coordinate scaling, errors, and stop behavior |
| Startup/update | Windows sign-in option, Android installer handoff, desktop updater retry/rollback logic, version consistency in Actions | Verify packaged update/relaunch/rollback and startup; Android auto-reconnect remains incomplete |
| Desktop UI | Python companion packaged as a one-file EXE; DPI awareness and version/path title | Sharpen/validate current UI; native Windows app migration not done |
| Laptop unlock | Explicitly unsupported in server | Research supported Windows Hello/passkey integration; no password injection or secure-desktop bypass |

## Product and security constraints

- Keep the Android application ID and signing identity so installed copies can upgrade.
- Authenticate before PC actions. Never include the pairing PIN in discovery or shareable QR data. Use authenticated TLS (wss://) for Internet use; identify ws:// as trusted-LAN only.
- Validate app IDs and shortcut paths. Never execute a client-provided shell string.
- Stop capture when the user stops, disconnects, or leaves the relevant session. Never broadcast media to unrelated clients.
- Browser preview captures only the user-selected visible rectangle. It may include surrounding content inside that rectangle; protected video may be black. Do not capture the full desktop/browser silently.
- Webcam recording, browser-region preview, and future screen recording are different features. Current video recording is for the PC webcam; selected-region preview is not a screen recorder.
- Android camera/microphone use is user-started and permission-aware. Background/locked-screen operation is subject to Android foreground-service and permission rules.
- Do not bump versions or publish until the release gates pass.

## Phased implementation plan

Phases are ordered from integration and verification toward larger platform work. Effort ratings are relative estimates.

### Phase 0 - Integrate and verify the current source (low/medium effort; immediate)

1. ~~Review and isolate slow media, camera, snapshot, and live-audio work from the command paths that carry input and heartbeats.~~
2. ~~Disable Pen/touch navigation while preserving its source for later work.~~
3. ~~Correct media playback-state mapping and preserve state across transient media-session errors.~~
4. ~~Build Android and Windows companion at v1.3.9; both GitHub Actions builds passed.~~
5. ~~Run existing companion regression checks, Android build checks, and release-version consistency in GitHub Actions for v1.3.9; all passed.~~
6. ~~Keep process and stats polling responsive, show the full process list, return end-task results, and use a measured system CPU sample.~~
7. ~~Save captured snapshots to a phone gallery and keep snapshot capture from interrupting an active webcam stream.~~
8. ~~Move live two-way audio controls into Camera & Mic and convert phone PCM frames to the PC output device's sample rate and channels.~~
9. ~~Write phone-compatible MP4 webcam recordings and convert legacy AVI webcam recordings on playback/download.~~
10. Smoke-test Bluetooth HID without the companion and each companion transport independently.

**Acceptance:** v1.3.10 CI builds and software checks pass. Hardware reliability still requires target-device checks; no claim is made for those until performed.

### Phase 1 - Webcam video recording, recordings library, and browser video preview (medium effort; high priority)

**PC webcam recording**
- Preserve the opt-in/auto-record setting for live PC webcam sessions and the separate option to include PC microphone audio.
- Show recording state and errors. Ensure stop, disconnect, camera failure, and app shutdown finalize or safely discard partial files.
- Preserve source resolution in the recording even if live preview frames are downscaled. Keep high-quality snapshots independent of preview compression.
- ~~Implement configurable age/size retention and minimum free-space settings; add regression coverage proving expired inactive files are removed while protected recordings remain.~~
- Verify cleanup behavior and recording quality on the target PC, including low-disk-space handling during a real webcam session.

**Phone recordings experience**
- ~~Implement authenticated catalog listing, bounded chunk transfer, SHA-256 verification, download, local open, and online-playback support.~~ Verify listing, transfer, playback, and recording integrity using actual webcam recordings on the phone.
- Add transfer progress, cancellation, retry/error status, and cleanup after navigation/disconnect.
- Verify new MP4 and converted legacy AVI/WAV formats on supported Android versions; verify seek/cancel behavior and throughput on Bluetooth, Wi-Fi, and WAN.
- Add optional phone-local retention and distinguish PC recordings from local copies.

**Selected-area browser video preview**
- ~~Implement PC selected-region capture and the independent phone preview toggle, with bounded streaming and stop handling.~~ Verify privacy boundaries, coordinate scaling, and stop behavior on the target PC and phone.
- Keep frame size/rate bounded and preview work off control/input paths. Show no-selection/capture-error states.
- Test Windows DPI scaling and multiple monitors; add display selection/coordinate conversion if needed.
- Explain that selected-page surroundings can be visible and protected surfaces may be black. Never label album art as video.

**Acceptance:** webcam recordings play with original capture quality; phone transfers are intact and cancellable; preview shows the selected live region and stops promptly on every transport.

### Phase 2 - Quick Apps, tasks, telemetry, volume, and media state (low/medium effort; high priority)

**Quick Apps**
- Verify async discovery returns pinned/cached entries immediately and completes with the installed list without leaving a permanent loading state.
- ~~Implement phone-side add/remove of discovered PC Quick Apps, including preloaded entries.~~ Verify add/remove over every transport. Phone-side renaming remains optional; names can currently be edited in the PC companion.
- Refresh on entry and after edits without rescanning for every tap. Explain missing/removed shortcuts.

**Tasks and live stats**
- ~~Return cached/default process and stats data promptly while background refresh runs; keep storage capacity separate from disk activity, report per-adapter GPU metrics, and show NPU as unavailable without a supported counter.~~
- Verify loading/freshness states and compare disk activity and GPU readings with Task Manager on the target PC.

**Volume and media controls**
- Reproduce and fix the reported PC master-volume bug. Show acknowledged PC values or an explicit unavailable/error result; test drag behavior and mute on every transport.
- Validate native-player and browser Playing/Paused/Stopped/Idle transitions, session switching, and closed-session clearing.
- Keep artwork distinct from selected-area video. Verify seek/next/previous/toggle and presentation volume-button navigation without duplicate/stale commands.

**Acceptance:** installed-app edits sync safely; tasks/stats arrive promptly and refresh; PC volume changes reliably or reports its limitation; media state matches the PC.

### Phase 3 - Two-way live audio (medium/high effort)

- ~~Implement separate PC-microphone-to-phone and phone-microphone-to-PC sessions with user-started Android permission, foreground playback/recording, and stop/disconnect cleanup.~~
- Test persistent active state, mute/stop, cleanup, latency, buffering, jitter, sample rates, device changes, and interruption recovery on each transport.
- Measure latency, buffering, jitter, sample rates, device changes, and interruption recovery on each transport.
- Verify optional PC-side WAV recording finalizes cleanly and enters the library only after close.
- Add device selection and feedback/echo guidance; investigate echo cancellation where available.
- Follow Android foreground-service rules; do not promise capture after force-stop or whenever the phone is locked.

**Acceptance:** both directions start and stop predictably across BT/Wi-Fi/WAN; no stuck capture/playback remains; recordings are playable and complete.

### Phase 4 - Startup, reconnect, and automatic repair (high effort)

- Verify Windows sign-in startup uses the correct companion executable, starts minimized as configured, and does not start camera/audio.
- Add an Android connection supervisor for user-approved saved devices/endpoints with backoff, cancel/disable controls, stale-state cleanup, and sleep/wake recovery.
- Keep endpoints and credentials protected and separate. Do not silently pair with unknown PCs or bypass user approval.
- Add Android startup/boot behavior only where platform rules permit it; never auto-start recording or streaming.
- Prevent retry loops, duplicate sockets/commands, held buttons, excess battery use, and orphaned media sessions.

**Acceptance:** sleep, Wi-Fi interruption, or companion restart either reconnects to a user-approved endpoint or presents a clear recoverable state.

### Phase 5 - Concurrent transports and measured failover (very high effort)

- Permit BT, local Wi-Fi, and WAN to remain available concurrently where supported.
- Measure authenticated connection health and latency; choose a healthy command path rather than treating an open socket as proof of working service.
- Use one active command path at a time, with sequencing/deduplication so input is never delivered twice.
- Resynchronize connection/media state and transfer or stop live streams safely when changing paths.
- Show the active path and provide manual override. If seamless handoff is unsafe, show the interruption and reconnect clearly.

**Acceptance:** network changes do not duplicate input, leave held buttons, display stale status, or leak camera/audio streams.

### Phase 6 - Phone camera as a PC camera source (very high effort)

- Add an Android user-started camera session with preview, front/back switching, resolution/frame-rate controls, privacy indicator, permission handling, and stop.
- Use bounded adaptive streaming over authenticated transports.
- Prototype a Windows Media Foundation virtual-camera endpoint; investigate supported Windows builds, registration, signing, installation, and packaging.
- Keep phone-camera streaming separate from PC webcam capture and browser preview. Respect Android background and lock-screen restrictions.

**Acceptance:** supported PC apps can select the phone camera; switching/stopping releases it; unsupported configurations show a clear reason.

### Phase 7 - Phone-based laptop unlock feasibility (exploratory; very high/platform dependent)

- Research whether Windows Hello/passkey/FIDO2 or a signed Windows credential-provider extension can support a phone authenticator on target Windows versions.
- Require explicit phone biometric/device authentication and cryptographic challenge-response. Never store or transmit the Windows password.
- Keep unlock unavailable unless a supported, security-reviewed sign-in path exists. Do not inject keystrokes on the secure desktop or bypass Windows sign-in.

**Acceptance:** document a supported prototype path or close the feature as unsupported for the target platform; never ship password injection.

### Phase 8 - Desktop UI sharpness and native Windows companion (very high effort)

- First verify and fix text sharpness, DPI scaling, fonts, and layout on the user's display.
- The current one-file executable is a Python app packaged with PyInstaller, so it embeds the Python runtime. If a non-Python runtime is required, build a native Windows companion rather than hiding the embedded runtime.
- Inventory pairing, Wi-Fi/WAN setup, Quick Apps, startup, recordings, camera/audio diagnostics, logs, tray behavior, update/rollback, and settings before choosing a native stack.
- Preserve protocol/security, settings, recordings, startup, Android compatibility, and updater rollback. Keep the Python app until native parity passes.

**Acceptance:** current UI is sharp at target scaling. A native replacement has feature and upgrade parity and does not lose user settings or recordings.

### Phase 9 - Updaters, GitHub Actions, and release (medium/high effort; release blocker)

- On Android, verify download hash, package/version checks, signing continuity, unknown-source permission return, system installer handoff, cancellation, and successful upgrade.
- On Windows, verify packaged update staging, closing the running app, replacing the exact install path, relaunching that same path, version acknowledgement, rollback, and second launch.
- Compare the displayed running EXE path with the user's shortcut to diagnose the stale-version report.
- Keep Android versionName/versionCode, PC version, release tag, workflow checks, and asset names consistent.
- Require a versioned `release-notes/vX.Y.Z.md` feature list for every release and publish it as the GitHub Release description, alongside only the APK and EXE downloads.
- ~~Actions version check, 17 Python regression checks, signed Android APK build, Windows companion EXE packaging, gated release publication, and asset verification.~~ Release: [v1.3.9](https://github.com/yaminichandrakj/bt-mouse/releases/tag/v1.3.9).
- **Still open:** validate Android and Windows in-app updater installation, relaunch, rollback, and installed-version acknowledgement on target devices. Move signing material to protected CI secrets while preserving the existing Android key.

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
- [ ] Verify v1.3.10 CI artifacts, signing identity, version/tag consistency, release notes, and assets.
- [ ] Publish v1.3.10 after both CI builds pass; hardware checks unavailable in CI remain open.
