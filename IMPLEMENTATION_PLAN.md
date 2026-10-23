# BT Mouse & PC Companion — Implementation Plan

**Plan version:** 2.7
**Repository:** `BT Mouse`
**Status:** Active implementation
**Updated:** 2026-10-24

## Product goal

Make an Android phone a dependable PC input and control device. The product has three distinct transport paths:

1. **Bluetooth HID:** direct mouse, keyboard, and consumer media input; no PC companion is required.
2. **Companion mode:** Bluetooth RFCOMM or local Wi-Fi for input plus PC-only features such as media state, app/process lists, PC stats, camera, and audio.
3. **Remote mode:** authenticated WebSocket control through a configured remote endpoint.

The UI and protocol must communicate these capability differences. A disconnected transport must not look connected, and a feature unavailable on the selected transport must not silently appear to work.

## Working-tree rules

- Preserve the user's pre-existing edits in `ConnectionScreen.kt`, `KeyboardScreen.kt`, and `pc_server/server.py`; integrate them without resetting or replacing unrelated work.
- Keep the existing application id and signing identity for upgrade compatibility. The checked-in keystore and fallback signing password remain a known repository-security risk; do not rotate the key as part of this implementation because that would break in-place updates. Move CI signing to protected secrets in a separate migration before publishing from a repository that should not expose the key.
- Do not claim that injected mouse events provide a pressure-sensitive digitizer or that remote keystrokes can unlock the Windows secure desktop. Keep lock-screen automation disabled or clearly unavailable until there is a supported, safe OS mechanism.
- Do not include the WAN PIN in discovery broadcasts or QR payloads intended to be shared. Use an explicit user-provided pairing secret and TLS (`wss://`) for remote Internet access.

## Current audit findings

| Priority | Area | Required outcome | Status |
|---|---|---|---|
| Critical | Network security | Authenticate every UDP command, not only power/app/process commands; remove shell-based arbitrary launch; keep WAN auth before dispatch and require TLS for Internet use. | Implemented; Python syntax check and security regression tests pass locally and in Actions |
| High | RFCOMM/WAN buttons | Handle button-only packets and release held mouse/pen/keyboard input on disconnect or prolonged idle. | Implemented in `server.py`; device verification pending |
| High | Pairing PIN | Keep the companion PIN stable across restarts and use the same saved PIN for Wi-Fi and WAN; provide a clear PIN rejection instead of a generic timeout. | Implemented in v1.3.2; persistence regression test passes |
| High | Wi-Fi protocol | Align command names and use an acknowledged, PIN-authenticated connect flow with timeout/cancel behavior; save the visible PIN before auto-discovery connects. | Implemented; device verification pending |
| High | First-run network setup | Explain that QR parsing is not a connection, document Windows Firewall UDP ports, and distinguish local WS URLs from public WSS endpoints. | Status copy and connection guidance updated; device/network verification pending |
| High | RFCOMM response path | Await WinRT `store_async()` on its owning event loop; report and handle failed writes. | Implemented with a bounded response queue; Windows device verification pending |
| High | Wi-Fi camera stream | Deliver bounded frames/status to the UDP peer; use higher quality on reliable transports. | Implemented with low-resolution, byte-bounded UDP frames and higher-resolution Bluetooth/WebSocket frames |
| High | Numpad | Map every keypad HID usage in the PC companion, including operators and decimal. | Implemented in `server.py`; device verification pending |
| Medium | Media state | Normalize mute field parsing and isolate state by selected transport; refresh state after actions and surface operation status. | Actions return current state; WinRT results are checked with Windows media-key fallback; hardware verification pending |
| Medium | Connection state | Only show connected after handshake; clear connection on timeout, socket failure, mode change, and disconnect. | Implemented for Wi-Fi/WAN; app permits local `ws://` while the WAN client rejects public non-TLS `ws://` endpoints |
| Medium | Writing pad | Use selected color; describe mouse-based mapping accurately; clamp and validate coordinates; do not claim pressure support the PC cannot consume. | Color and capability labels implemented; pressure remains phone-preview-only |
| Medium | Navigation | Remove redundant Settings bottom tab and provide a clear return path from Settings. | Implemented; Android release build passes in Actions |
| Medium | Dynamic apps | Query installed Start Menu entries, make launch identifiers safe, support editable quick apps and missing-app handling. | Companion can add, rename, and remove saved Start Menu shortcuts; Android shows the same set |
| Low | Quality | Keep companion UI sharp at Windows display scaling and make microphone diagnostics reflect actual device readiness. | Per-monitor DPI awareness and microphone startup/error reporting implemented |

## Architecture and protocol contract

- One normalized JSON command vocabulary is shared across UDP, Bluetooth RFCOMM, and WebSocket. Keep aliases temporarily where older clients send them, but have current Android code emit canonical names.
- UDP discovery returns only server address, port, and display name. The user enters the pairing PIN on the phone. `connect` includes the PIN; the server returns `connect_ack`. The app sends the PIN with every subsequent UDP command so authorization does not depend on an IP-only allowlist. The server does not retain an IP-only authenticated session; disconnect releases held mouse buttons.
- Reject unauthenticated UDP commands before any input, query, recording, camera, or system action. Keep discovery and a rate-limited failed-auth response as the only pre-auth operations. Bluetooth RFCOMM relies on OS-level pairing and the RFCOMM session; WebSocket relies on the authenticated WebSocket session.
- Avoid `shell=True`. Launch only validated registered app identifiers, approved system utilities, or canonical Start Menu shortcut paths. Never treat arbitrary client text as a command line.
- `connect_ack`, error, media, stats, apps, process, camera, audio, and kill responses use the same response types on all supported transports. UDP responses go to the requesting endpoint; RFCOMM responses are newline-delimited JSON; WebSocket responses remain JSON messages.
- UDP camera streaming sends small, low-resolution frames with a strict encoded-size limit to the requesting peer. Bluetooth RFCOMM and authenticated WebSocket can carry larger frames. Do not broadcast camera frames to unrelated clients.
- Direct Bluetooth HID has no PC-side response channel. PC-only controls should be visibly unavailable in HID-only mode, while basic HID mouse/keyboard/media input remains enabled.

## Work phases and ownership

### Phase 1 — Secure command handling (Codex; core security changes)

- Apply one authorization gate before dispatching UDP commands. Require a valid pairing PIN during handshake and on subsequent requests; do not authorize by source IP alone.
- Keep Bluetooth RFCOMM trusted only after the Bluetooth OS session has connected. Keep WebSocket commands behind successful WebSocket authentication.
- Remove command-string execution. Allowlist app IDs and validate discovered shortcut paths against known Start Menu roots. Reject unknown values and malformed payloads.
- Rate-limit failed pairing attempts; avoid logging PINs/passwords. Do not return secrets in discovery responses or error text.
- Protect destructive/system commands and camera/audio operations. Make unlock capability unavailable until it can be implemented safely; remove plaintext password persistence.

**Acceptance:** unauthenticated UDP packets of every command type cause no side effect; invalid PINs do not create sessions; allowed app IDs launch without a shell; malformed/unknown app IDs are rejected; WebSocket dispatch happens only after auth.

### Phase 2 — Transport parity and reliability (Codex; Android + PC protocol)

- Fix RFCOMM writer dispatch, button packet handling, command aliases, mute key compatibility, and UDP request/response routing.
- Implement Wi-Fi handshake with a single pending connect attempt, bounded retries/timeout, cancellation when host/mode changes, ACK validation, and state reset on disconnect.
- Attach the PIN to all Wi-Fi command packets. Do not mark connected before the valid `connect_ack`.
- Make camera and audio status reflect actual manager start results. For UDP video, bound frame size/rate and send frames to the requesting client; ensure stop/disconnect closes streams.
- Map all keypad usages and normalize protocol values for key/media events.
- Separate per-transport state in `ConnectionCoordinator`; never expose Wi-Fi state while Bluetooth is selected.

**Acceptance:** mouse buttons, movement, scrolling, keys, media, queries, and supported camera/audio controls have explicit end-to-end behavior per transport. Unsupported combinations return a visible status rather than silently succeeding.

### Phase 3 — Media and desktop features (Codex)

- Use the current WinRT media session when available; otherwise choose an active Playing/Paused session. Treat failed WinRT operations as failures and use the Windows media-key fallback only then.
- Return fresh volume/mute state even when no media session exists; keep Android parsing backward-compatible during migration.
- Scan Start Menu shortcut roots safely, return a stable app ID and display name, and launch only validated IDs/paths. Preserve search and pinning in the Android UI.
- Keep remote power actions explicit and confirmed. Do not advertise workstation unlock as reliable; remove or gate password-saving/injection until an approved secure implementation exists.

**Acceptance:** tests cover active/paused/no-session media, all six media keys, missing apps, invalid launch IDs, and process/system actions under authorization.

### Phase 4 — Writing surface and user experience (Codex)

- Make the selected ink color affect local strokes; keep local preview and remote PC output behavior clearly distinguished.
- Make pointer transitions between one and multiple fingers avoid cursor jumps; release held buttons on cancel/disconnect.
- Treat stylus pressure and absolute pen mapping as preview-only unless the PC side supports a real digitizer protocol. On Windows, label the fallback as absolute mouse emulation; on other desktop OSes, report unsupported absolute pen mapping until implemented.
- Show connection mode and transport capability. Hide/disable companion-only features in HID-only mode, show connecting/error state for handshakes, and remove Settings from the crowded bottom bar in favor of the gear launcher/back action.
- Use the selected external endpoint for WAN QR setup. Require `wss://` for Internet operation; local `ws://` is only for trusted LAN development/use and must be labeled.
- Keep the pairing PIN stable in a per-user PC companion config file. Use the saved Android Wi-Fi PIN as the default WAN credential because both transports authenticate against the same PC PIN.
- Explain that scanning only loads an endpoint; the connection handshake must succeed. For Wi-Fi, Windows Firewall must allow inbound UDP 53530 and UDP 53535, and the phone and PC must be able to reach each other on the LAN. The companion's WAN QR is a local endpoint; cellular users must enter their public TLS-terminated `wss://` URL.

**Acceptance:** controls do not silently no-op; Settings navigation works from every screen; WAN help/QR values match the actual configured endpoint; UI does not promise pressure-sensitive digitizing or secure-desktop unlock where unsupported.

### Phase 5 — Verification and release (Codex owns all implementation and integration)

| Owner | Assigned work | Boundary |
|---|---|---|
| Codex | Own Android/Python changes, regression tests, plan/docs, security and integration review, Actions workflow, release tagging, and post-release verification. | Single owner; no external agent handoff. |

**Verification sequence:**

1. Run Python syntax compilation and deterministic `unittest` protocol/security checks locally.
2. Run `git diff --check` and review all staged changes, including Android UI/network behavior and the companion server.
3. Push the completed source to `main`; GitHub Actions must build the Android release APK and Windows companion executable.
4. Publish a new version tag only after Actions builds pass. The tag workflow creates the GitHub Release and attaches both binaries.
5. Verify the release page, attached asset names, and successful Actions run. Document hardware checks that cannot be performed in this environment.

**Release identity:** `v1.3.4` (`versionCode = 11`) is published with media state acknowledgements, volume-button slide navigation, editable shared Quick Apps, Wi-Fi camera streaming, microphone diagnostics, live process refresh, and UI scaling fixes. GitHub Actions run `39981535439` passed the companion checks, Windows EXE build, Android APK build, and release publication. The release contains `BT-Mouse-v1.3.4.apk` and `BT-Mouse-Companion.exe`. Preserve the established Android application id and signing identity so existing installations can upgrade. CI currently reads the repository's historical release keystore; migrating it to protected Actions secrets and removing the checked-in key is a separate security migration because it must preserve the exact signing key.

`v1.3.5` (`versionCode = 12`) is published with Windows audio COM initialization and truthful volume errors, stable phone-side volume dragging, improved Playing/Paused/Stopped recognition, opt-in media artwork, and a dedicated Tools tab. GitHub Actions run `40006732713` passed the Android release build, companion regression checks, Windows EXE build, and release publication. The release contains `BT-Mouse-v1.3.5.apk` and `BT-Mouse-Companion.exe`.

**Acceptance:** deterministic regression tests pass; GitHub Actions Android and Windows build jobs pass; the current release contains both the APK and EXE; Android compile/lint is checked by Actions; device-specific reliability is reported as pending until tested on real Android/Windows hardware.

## Current implementation sequence

1. Phase 1 security gates and safe launch behavior are implemented; keep them unchanged unless a review finds a concrete defect.
2. Handshake and transport parity are implemented; run protocol regression tests and device checks before claiming hardware reliability.
3. v1.3.4 implements media state acknowledgements, presentation volume-key mode, editable Quick Apps shared between PC and phone, Wi-Fi camera streaming, live process refresh, truthful audio readiness, visible Wi-Fi PIN, secure lock messaging, and DPI awareness. Verify behavior on Android and Windows.
4. v1.3.5 implements Windows volume COM initialization/error reporting, stable phone volume dragging, improved media-session selection/status, optional media artwork, and a dedicated Utilities tab. Validate the changes on Android and Windows hardware.
5. Codex adds and runs deterministic Python regression tests and adds them to the GitHub Actions workflow.
6. Bump the app/server version for each release, preserve signing identity, push source changes, and let Actions build both release binaries.
7. After both builds pass, push the matching version tag, let Actions publish the GitHub Release, and verify both assets.

## Verification checklist

- [x] All network command types are gated by UDP PIN authentication or the established RFCOMM/WebSocket transport.
- [x] UDP handshake validates PIN and returns a source-addressed ACK; Android waits for it and times out disconnected.
- [x] Safe app launch validates Start Menu paths and known system identifiers; no client-controlled shell command is used.
- [x] RFCOMM writes are serialized on the WinRT event loop with bounded queueing and surfaced write errors.
- [x] Button-only packets are handled; held mouse, pen, and keyboard inputs are released on disconnect and after 30 seconds without input.
- [x] Wi-Fi and RFCOMM command names align; keypad map includes keypad usages.
- [x] UDP camera frames are low-resolution and size-bounded; audio recording status follows actual microphone readiness.
- [x] Mute parsing accepts both field names; Bluetooth mode reads RFCOMM state instead of Wi-Fi state.
- [x] Writing pad color selection works; UI labels describe mouse emulation rather than a digitizer.
- [x] WAN setup omits the PIN from QR/discovery, avoids a fabricated default token, and requires `wss://` for non-local hosts.
- [x] Password persistence/injection for workstation unlock was removed.
- [x] Python syntax compilation passes for `server.py` and `app_gui.py`.
- [x] Codex-owned Python protocol/security regression suite passes locally and in Actions (7 tests).
- [x] Android release build and Windows executable build pass in Actions.
- [x] GitHub Release `v1.3.2` is published with APK and EXE assets.
- [x] Packaged Windows companion checks GitHub Releases, validates and stages the EXE update, replaces itself after exit, and relaunches (`v1.3.3`).
- [x] GitHub Release `v1.3.3` is published with APK and Windows companion assets; obsolete root-level APK/EXE downloads were removed.
- [x] GitHub Release `v1.3.4` is built and published with Android APK and Windows companion assets; the release workflow completed successfully.
- [x] GitHub Release `v1.3.5` is built and published with Android APK and Windows companion assets; Actions run `40006732713` completed successfully.
- [x] Python source is bundled into a single-file executable by PyInstaller; that EXE embeds its Python runtime. The in-app updater updates this bundle. Native Windows UI/runtime migration remains a separate multi-phase project and must retain network/security and updater parity before replacing the current app.
- [ ] Hardware verification is completed on Android/Windows devices (not available in this environment).
- [x] Existing user edits are preserved; prior `v1.3.0` tag remains untouched.

## Next release plan — media reliability, Utilities, and long-running device features

**Implementation ownership:** Codex owns implementation and integration. Antigravity is not assigned work. Earlier request to use two agents was superseded by the user's instruction to do it all here.

### In progress: v1.3.5 media and navigation fixes

- Initialize COM on each Windows audio-control call; report explicit master-volume availability and error state instead of presenting a fake 50% level as a working control.
- Keep Android volume changes local while dragging and send one final value at release. Keep the chosen thumb position while the PC acknowledges the change, so background polling cannot fight the gesture.
- Select the currently Playing media session ahead of stale sessions and show Playing, Paused, Stopped, and Idle distinctly.
- Add an opt-in media artwork preview through Windows' media-session thumbnail. The OS session API does not expose a protected live video surface; this preview is album/artwork metadata only.
- Move Quick Apps, task/system stats, camera/microphone, and power controls to a dedicated Tools tab; keep keyboard and numpad in Keys.
- Do not begin a release until the Android and companion builds pass and the user-visible version is updated together.

**Acceptance:** volume feedback is acknowledged or reports unavailable; slider does not snap while dragging; media status is not guessed as Paused; optional artwork is small and absent from routine status polls; PC utilities are reachable from Tools.

### Phase A: transport supervisor and reconnection

1. Add a `TransportSupervisor` above the existing mode-specific managers. The three interfaces remain independently identifiable: Bluetooth HID for input-only, Bluetooth RFCOMM for companion features, Wi-Fi UDP for LAN features, and authenticated TLS WebSocket for remote access.
2. Add a capability/health model per candidate transport: authenticated, reachable, round-trip latency, last-good time, feature set, and failure reason. Probe without issuing user input commands.
3. Select one output route at a time using a stable priority and hysteresis (for example, prefer LAN Wi-Fi when healthy, then RFCOMM, then configured WAN; keep direct HID available as an input fallback). Do not broadcast mouse/key events on every live path because that duplicates actions.
4. Preserve credentials in Android's protected storage and PC per-user settings; do not put the WAN endpoint secret in QR/discovery. Reuse current authenticated handshakes and define expiry/rotation behavior before auto reconnect.
5. Add a reconnect state machine with bounded exponential backoff, cancellation on manual disconnect, re-probe after network changes, Bluetooth adapter changes, PC resume, and socket failures. Reset per-session feature state on route change.
6. On Windows, register an explicit user-controlled launch-at-logon option for the companion and attempt reconnect after resume. Show the chosen active path and recovery state in Connect.
7. On Android, request opt-in for an ongoing foreground connection service and notification before enabling background recovery. Handle boot and connectivity broadcasts only within current Android background-execution policy; offer a battery-optimization note rather than silently promising always-on behavior.

**Acceptance:** connection state reports the active route and capability; only one route injects an event; sleep, Wi-Fi changes, and transient disconnects recover without a duplicate action; manual disconnect disables retries; users can disable PC/phone background startup.

### Phase B: recordings library and responsive camera/audio

1. Add a bounded PC recording catalog under the existing per-user recordings folder with generated IDs, capture kind, timestamp, duration, media type, file size, and optional thumbnail. Enforce an explicit storage cap and retention/deletion controls.
2. Implement PC camera video recording with a selected camera, visible recording state, clean stop/finalization, and recovery of interrupted temporary files. Keep snapshots and microphone recordings in the same library.
3. Add authenticated list, metadata, delete, and chunked-download commands. Use a reliable transport for media transfer; do not put full recordings into UDP or JSON. WAN transfer must remain TLS protected.
4. Add a phone Library screen for preview, streaming/download progress, playback, deletion, and local cache/storage visibility. Stop or cancel transfers when the active connection changes.
5. Reduce live-feed latency by measuring capture/encode/send time, maintaining only the newest frame, adapting JPEG resolution and frame rate to observed RTT and transport, and stopping frames when the screen is hidden.
6. Keep recorded-video transfer distinct from live preview. Use backpressure and bounded queues so a slow phone cannot accumulate unbounded frames or memory.

**Acceptance:** snapshots, microphone audio, and camera video appear in the PC catalog; authorized phone can list and play/download them; deletion and storage caps work; live feed stays responsive on slower transports; no recording is exposed to an unauthenticated peer.

### Phase C: startup, install/update, and security boundaries

- Keep the companion's single-file Windows distribution and self-updater so updates do not require a manual GitHub download. Explain that a PyInstaller executable still embeds the Python runtime; it is not a separate Python install or source-script launch.
- Improve companion UI rendering with DPI-aware fonts/layout and vector/native controls. Do not trade legibility for a rasterized fixed-size interface.
- Before enabling background startup, implement signed/validated update staging, safe rollback, clear version display, and a user-visible disable/startup repair path.
- Do not implement a custom phone-to-Windows password or biometric unlock bridge. Keep the secure desktop protected. Investigate native Windows Hello/passkey/FIDO2 sign-in eligibility for this PC/account; Windows FIDO2 logon has device/account policy constraints, and a companion app cannot bypass them.

**Acceptance:** startup/update toggles are explicit and reversible; old stable installs keep upgrading in place; no password or biometric secret crosses the app protocol; unsupported Windows sign-in scenarios are reported clearly.

### Execution and release sequence

1. v1.3.5 media and Tools changes are complete and published; validate them on Android and Windows hardware.
2. Ship media/navigation reliability separately from the larger multipath and recording changes so failures can be isolated and rolled back.
3. Implement Phase A with transport-state/UI work first and exercise it under Wi-Fi loss, Bluetooth loss, PC sleep/resume, and manual disconnect before enabling startup by default.
4. Implement Phase B in authenticated catalog/listing/transfer slices, then add video capture and phone playback after bounded transfer behavior is established.
5. Implement Phase C only after signed update/rollback behavior and Android foreground-service consent are integrated.
6. For each release: update Android and PC version identity together, push source, require GitHub Actions Android/Windows builds to pass, then tag and publish both assets. Device-dependent results remain marked pending until verified on real devices.

### External constraints and design references

- Windows media sessions can expose playback state and thumbnail artwork but do not provide a general live capture of another app's protected video surface: [GlobalSystemMediaTransportControlsSession](https://learn.microsoft.com/en-us/uwp/api/windows.media.control.globalsystemmediatransportcontrolssession?view=winrt-26100), [media thumbnail property](https://learn.microsoft.com/nl-be/uwp/api/windows.media.control.globalsystemmediatransportcontrolssessionmediaproperties.thumbnail?view=winrt-22000).
- Windows passkeys support companion-device flows in the native sign-in experience; FIDO2 Windows logon depends on eligible Entra device/account policy: [Windows passkeys](https://learn.microsoft.com/windows/security/identity-protection/passkeys/), [FIDO2 security-key sign-in](https://learn.microsoft.com/en-us/azure/active-directory/authentication/howto-authentication-passwordless-security-key-windows).

## Plan update — v1.3.6 reliability fixes and media roadmap

**Plan version:** 2.7
**Updated:** 2026-10-24
**Ownership:** Codex owns implementation, integration, CI, and release. No external agent is assigned.

This section supersedes the earlier “Next release plan” where schedules conflict. The goal is to keep the currently responsive input path intact while making companion queries/media asynchronous, bounded, measurable, and recoverable. High quality stills and low-latency live preview are separate products with separate capture/transfer settings.

### Immediate slice — v1.3.6

- Keep WebSocket protocol ping/pong alive while synchronous PC commands run by moving command dispatch off the asyncio event loop. Use a configurable, tolerant ping timeout and a bounded message size; log disconnect reason and command duration so future tuning is based on evidence.
- Make APK installation a complete user-approved Android flow: download to a temporary file, verify HTTP result and byte count, atomically finalize, check Android's per-source install permission, open the relevant Settings page when it is missing, and then launch the package installer with a FileProvider content URI. Android still requires the user to confirm installation; silent self-install is not available to an ordinary app.
- Keep release version identity synchronized across Android, companion handshake, and Actions fallback tag.

**Acceptance:** a slow command does not starve WebSocket ping/pong; a disconnected client is reported and can reconnect; a complete APK reaches Android's installer after the source permission is granted; partial/failed downloads cannot be offered as installable.

### Phase 1 — latency and reliability baseline

1. Add request IDs and timestamps to stats, process/task, media, camera, and audio requests/responses across RFCOMM, UDP, and WebSocket. Record round-trip time and server-side capture/query/encode/send durations without logging PINs, tokens, media, or personal process arguments.
2. Gather a repeatable matrix for each transport: idle, normal use, poor signal, PC busy, and WAN high latency. Record p50/p95 response time, timeouts, reconnects, frame age, and dropped/coalesced work. Do not infer that the transport itself is the cause until measurements separate network delay from PC work and phone UI/rendering.
3. Move expensive work out of receive/UI loops. Cache process lists and system statistics on a bounded sampler; avoid scanning every process for every poll; coalesce repeated requests while one sample is in progress. Keep input packets independent from slow query/media work.
4. Replace repeated background polling with explicit refresh rates per screen/feature. Keep a lightweight transport heartbeat; query media only when its screen or controls need it and refresh system/process data less often than input state. Cancel stale requests on transport changes and ignore out-of-order responses using request IDs.
5. Add bounded per-client queues and backpressure. For live frames retain the newest pending frame instead of accumulating old ones. Keep query responses ordered and ensure one slow client cannot block another.

**Acceptance:** latency is visible per transport and feature; stale results do not overwrite new state; query load is bounded; mouse/keyboard input latency remains independent of media capture and process scans; measured improvement is documented before and after.

### Phase 2 — camera quality, snapshots, and recordings

1. Split snapshot capture from live-stream capture. A snapshot uses the camera's best available still resolution and JPEG quality, independent of preview bitrate, UDP limits, or live-stream resizing. Do not resize a snapshot to the preview frame size.
2. Transfer snapshots over an authenticated reliable path using bounded chunks, sequence number, total length, and integrity verification. UDP's datagram ceiling must not silently lower image quality: use chunked/reassembled datagrams with strict size/time limits and integrity checks where UDP is the only path, or return a clear “use reliable transport” result. Never base64 a whole recording into one JSON response.
3. Live preview may adapt resolution, frame rate, and compression using observed RTT, bandwidth, device heat, and queue age. The user can toggle preview; stop capture and transmission when it is hidden. Separate stream quality controls from still-image quality.
4. Create a local media catalog for PC snapshots, mic recordings, and camera videos. Use generated IDs, timestamps, duration, format, size, optional thumbnail, bounded storage, retention/deletion controls, and recovery/cleanup for interrupted temporary files.
5. Add laptop camera video recording and an authenticated library API for list, metadata, preview/download, and delete. Add an Android library with transfer progress, cancellation, local cache controls, and playback. WAN media remains TLS protected.

**Acceptance:** still images retain the selected/full camera still resolution and are not altered by preview settings; snapshots and recordings transfer correctly over supported links; slow links do not build unbounded queues; the phone can browse and play authorized saved media; storage limits and delete behavior are clear.

### Phase 3 — call-like audio and PC microphone input

1. First implement PC-to-phone live listening as an explicit call-like session with start/stop, source selection, visible active indicator, muted/ended state, low-latency encoding, packet sequence/timestamps, jitter buffer, bounded queue, and reconnect behavior. Distinguish microphone monitoring from Windows output capture.
2. PC output capture can use Windows WASAPI loopback in shared mode. Protected/DRM audio may be unavailable, and capture must not bypass the source application's policy. User selects whether to hear PC output, microphone, or an available supported source.
3. Implement phone-to-PC voice as a separate authenticated live-audio direction. Begin as playback through the companion app/selected output. Measure echo, feedback, audio focus, and latency before adding full-duplex mode.
4. If the phone mic must appear as a system-wide Windows microphone selectable in Zoom/Teams/etc., treat that as a later native-driver project. Windows virtual audio endpoints require a signed/installed driver package and lifecycle/security work; a normal Python companion cannot register a robust system microphone by itself. Evaluate Microsoft's SYSVAD sample and WDK signing/deployment requirements before committing to production scope.
5. Android background microphone capture requires explicit permission and a correctly declared foreground service/notification. Start capture while the app is visible and user initiated; expose a persistent stop control and honor Android's background-start/while-in-use restrictions.

**Acceptance:** live audio has bounded latency and clear capture/route state; start, stop, mute, reconnect, and permission denial are handled; no recording or microphone capture starts silently; protected audio and unsupported system-wide endpoint cases report a concrete limitation.

### Phase 4 — phone camera as a laptop camera

1. Build an explicit user-started camera session in Android using CameraX, with front/back switch, supported resolution selection, preview, status, and authenticated low-latency video transport. Keep snapshots on the separate high-quality still path from Phase 2.
2. Keep capture visibly active with Android's camera foreground-service requirements and persistent notification. Camera/mic access cannot be promised while the app is closed, before user consent, or started from boot/background on modern Android. A locked-screen session may work only when Android allows the already-started foreground service and vendor power policy permits it; test by device/OS and document the result.
3. On Windows, expose the incoming stream as a Media Foundation virtual camera where supported (Windows 11 build 22000+). Implement native source/format conversion, install/registration/unregistration, user-level lifecycle, and app compatibility checks. Treat “view phone camera in companion” as an interim feature if a system camera endpoint is not ready.
4. Handle privacy indicator, source selection, front/back switch, pause, disconnect, phone lock, thermal throttling, and session cleanup. Do not keep the camera running after an explicit stop.

**Acceptance:** Windows camera applications can select the phone feed as a camera on supported Windows builds; switching cameras takes effect in the active session; unsupported Windows/app/Android combinations are detected; no hidden capture occurs.

### Phase 5 — transport supervisor, startup, and repair

1. Extend the existing transport coordinator with independent authenticated health probes, capabilities, measured RTT/reliability, and last-good timestamps for Bluetooth RFCOMM, Wi-Fi, and remote WSS. Keep direct Bluetooth HID distinct because it is an input-only route.
2. Select one route for each command session using health ranking plus hysteresis. Do not send the same input event across multiple links. Define route ownership and outstanding-command cancellation before enabling automatic failover.
3. Add bounded exponential retry, network/Bluetooth change detection, PC sleep/resume recovery, stale-session cleanup, manual disconnect cancellation, and visible repair state. Require fresh authentication after expiry and do not weaken PIN/TLS checks for convenience.
4. Add opt-in Windows launch-at-login and Android connected-device foreground operation with notification. On Android, explain boot/background limits and request only appropriate service types. Camera and microphone recovery require their own user-started, visible capture sessions and are not implied by transport reconnection.
5. Make startup state and active path visible on both devices. Provide pause/disable controls, retry limits, and a last failure explanation. Never promise guaranteed reachability when the PC is asleep, powered off, offline, or blocked by firewall/router policy.

**Acceptance:** the phone chooses the fastest healthy authenticated path without duplicate actions; sleep/resume and transient network loss recover; user disconnect stops retries; startup/background behavior is opt-in and accurately described.

### Phase 6 — Android update handoff and release maintenance

- Keep updates in-app: validate release metadata and APK size/hash when available, download to a temporary file, finalize only when complete, check per-source install permission, and invoke Android's system installer. If Settings permission was needed, re-check on return and launch installation or show one clear action; Android's final install confirmation remains user controlled.
- Add release asset/signature/hash metadata and rollback-safe companion updates. Keep the PyInstaller EXE updater self-contained so users do not need to download from GitHub after every companion release. Do not remove the current updater until replacement/rollback is validated.
- Long-term native Windows UI/runtime replacement is a separate project; the current EXE embeds Python but does not require a separately installed Python runtime. Improve text rendering with DPI-aware/vector UI work independently of packaging language.

**Acceptance:** app downloads the correct signed release artifact, reports permission/verification errors plainly, opens the OS installer reliably, and existing installations upgrade in place; companion updates stage, verify, apply after process exit, relaunch, and can recover from a failed update.

### Phase 7 — staged validation and publishing

1. Validate protocol/security and bounded-resource behavior in GitHub Actions on every source push. Keep test changes focused on the affected work and never treat CI as physical-device verification.
2. Build Android APK and Windows companion from one version identity. Do not tag until both release jobs pass.
3. For each release tag, publish both artifacts, verify their names/checksums and release page, and record the Actions run.
4. Maintain a device matrix: Android OS/vendor and lock state; Windows version/build; Bluetooth adapter; LAN/WAN route; sleep/resume; normal and saturated load. Mark every real-device result as verified or pending.

## Research and platform constraints

- WebSocket protocol keepalive uses ping/pong and configurable intervals/timeouts. If application work blocks the event loop, it can delay control frames; moving synchronous work off the loop and measuring disconnects addresses this failure mode. [WebSocket keepalive guidance](https://websockets.readthedocs.io/en/13.0.1/topics/keepalive.html)
- Android requires the `REQUEST_INSTALL_PACKAGES` declaration and user-granted per-source permission for external APK installation; the package installer still presents a confirmation UI. [PackageManager install permission](https://developer.android.com/reference/android/content/pm/PackageManager?authuser=4), [install package intent](https://developer.android.com/reference/android/content/Intent?authuser=8)
- Android camera/microphone foreground services are constrained by while-in-use permissions and background-start rules. A service cannot provide an unconditional “app closed/locked/after reboot” camera or mic guarantee. [Foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types), [background-start restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)
- Android playback capture is permission/policy gated and source apps can disallow capture. [Android media projection and audio capture](https://developer.android.com/media/platform/av-capture)
- Windows shared-mode WASAPI loopback captures rendered output, with protected-content limitations. [WASAPI loopback recording](https://learn.microsoft.com/windows/win32/coreaudio/loopback-recording)
- A Windows system-visible camera can be implemented through Media Foundation virtual-camera APIs, with Windows build and registration constraints. [MFCreateVirtualCamera](https://learn.microsoft.com/en-us/windows/win32/api/mfvirtualcamera/nf-mfvirtualcamera-mfcreatevirtualcamera)
- A system-wide Windows virtual microphone is a driver-level feature, not just network audio playback. Microsoft's SYSVAD reference is a WDK audio driver sample that requires a driver signing/deployment strategy. [SYSVAD virtual audio driver sample](https://learn.microsoft.com/en-us/samples/microsoft/windows-driver-samples/sysvad-virtual-audio-device-driver-sample/)

### v1.3.6 release status

- **Published:** WebSocket event-loop isolation and tolerant keepalive; safer APK download finalization and Android install-source permission handoff.
- **GitHub Actions:** run `40008805865` passed the companion regression checks, Android release APK build, Windows companion build, and release publication.
- **Release assets:** `BT-Mouse-v1.3.6.apk` and `BT-Mouse-Companion.exe` are attached to [GitHub Release v1.3.6](https://github.com/yaminichandrakj/bt-mouse/releases/tag/v1.3.6).
- **Planned after v1.3.6:** latency measurement/query scheduling, full-quality snapshot transfer, recordings library/video, live two-way audio and virtual microphone feasibility, phone camera virtual camera, and multi-transport supervision/startup recovery.
- Do not claim the later features are implemented until their acceptance criteria and device checks pass. Real-device hardware validation remains pending.

## Execution update — companion updater and responsiveness (v1.3.7)

The user reported that the Windows companion closes after “installing” an update, does not reopen, and still shows the old version. Source review found two concrete defects: `APP_VERSION` in `app_gui.py` was still `1.3.4` although releases `v1.3.5` and `v1.3.6` had already shipped; and the updater detached a PowerShell script, immediately closed the app, then silently deleted the staged executable without checking that the replacement had launched. The helper only wrote failure details to a temp log. This made both the footer and update comparison stale, and left a failed handoff looking successful.

The v1.3.7 implementation slice fixes this by synchronizing the companion/Android/server version, having the helper stage beside the target (same volume), preserve a rollback copy, verify byte count, start the replacement, verify its startup marker reports the expected version, restore and relaunch the old executable on failure, and show deferred failure details when the restored app opens. The app exits explicitly after stopping its server so the updater can reliably replace the image. It also makes system CPU sampling nonblocking, gathers process fields through psutil's one-shot handle, caches repeated process/stats/app responses briefly, and removes the WAN heartbeat's redundant stats poll; the Utilities screen remains responsible for live stats polling while visible. Wi-Fi snapshots now use the camera still path at 1920-pixel maximum width and JPEG quality 92, split into MTU-safe UDP chunks with missing-chunk retries and SHA-256 validation. These settings remain independent from the adaptive low-resolution live stream.

**Phase order for the remaining work:**

1. **Stabilize and measure:** land/verify v1.3.7 update handoff and query responsiveness; add per-feature request/response timing and reconnect reason reporting.
2. **Improve dependable media transfer:** validate the new snapshot chunk/reassembly path under packet loss, then add the bounded recording catalog and phone library.
3. **Add interactive media sessions:** implement low-latency PC-to-phone listening and phone-to-PC audio playback; assess a system-wide Windows microphone driver separately.
4. **Add phone camera source:** visible Android camera session with front/back switching, then a Windows virtual camera endpoint on supported systems.
5. **Unify transports/background recovery:** transport health ranking and failover after duplicate-event and auth behavior is proven; opt-in startup/reconnect while respecting Android service restrictions.
6. **Release and device verification:** Android/Windows builds and regression checks first, release tag second, then physical device checks for audio/video, sleep, lock, and connectivity transitions.

**Updater research:** Windows replacement operations require the running file to be closed; an atomic same-volume rename/replace with a backup is safer than copying directly over the target. [Moving and replacing files](https://learn.microsoft.com/en-us/windows/win32/fileio/moving-and-replacing-files). PowerShell starts processes asynchronously by default, so the handoff must explicitly verify that the launched process remains alive before discarding rollback material. [Start-Process behavior](https://learn.microsoft.com/en-us/powershell/module/Microsoft.PowerShell.Management/Start-Process). PyInstaller one-file launch/shutdown has parent/child process behavior, reinforcing the need to ensure the original process exits and verify the replacement rather than assuming the UI close means the update succeeded. [PyInstaller one-file behavior](https://pyinstaller.org/_/downloads/en/stable/pdf/).

**v1.3.7 published:** GitHub Actions run `40011699089` passed the companion regression checks, Android release APK build, Windows companion build, and release publication. [Release v1.3.7](https://github.com/yaminichandrakj/bt-mouse/releases/tag/v1.3.7) contains `BT-Mouse-v1.3.7.apk` and `BT-Mouse-Companion.exe`. The update handoff and snapshot behavior compile and pass CI; in-place replacement, packet-loss recovery, and camera image quality remain pending on physical devices.
