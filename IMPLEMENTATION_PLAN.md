# BT Mouse & PC Companion — Implementation Plan

**Plan version:** 2.6
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

**Acceptance:** deterministic regression tests pass; GitHub Actions Android and Windows build jobs pass; the current release contains both the APK and EXE; Android compile/lint is checked by Actions; device-specific reliability is reported as pending until tested on real Android/Windows hardware.

## Current implementation sequence

1. Phase 1 security gates and safe launch behavior are implemented; keep them unchanged unless a review finds a concrete defect.
2. Handshake and transport parity are implemented; run protocol regression tests and device checks before claiming hardware reliability.
3. v1.3.4 implements media state acknowledgements, presentation volume-key mode, editable Quick Apps shared between PC and phone, Wi-Fi camera streaming, live process refresh, truthful audio readiness, visible Wi-Fi PIN, secure lock messaging, and DPI awareness. Verify behavior on Android and Windows.
4. Codex adds and runs deterministic Python regression tests and adds them to the GitHub Actions workflow.
5. Bump the app/server version for each release, preserve signing identity, push source changes, and let Actions build both release binaries.
6. After both builds pass, push the matching version tag, let Actions publish the GitHub Release, and verify both assets.

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

1. Finish the in-progress v1.3.5 media and Tools changes; compile Android and companion artifacts and review the full diff.
2. Ship media/navigation reliability separately from the larger multipath and recording changes so failures can be isolated and rolled back.
3. Implement Phase A with transport-state/UI work first and exercise it under Wi-Fi loss, Bluetooth loss, PC sleep/resume, and manual disconnect before enabling startup by default.
4. Implement Phase B in authenticated catalog/listing/transfer slices, then add video capture and phone playback after bounded transfer behavior is established.
5. Implement Phase C only after signed update/rollback behavior and Android foreground-service consent are integrated.
6. For each release: update Android and PC version identity together, push source, require GitHub Actions Android/Windows builds to pass, then tag and publish both assets. Device-dependent results remain marked pending until verified on real devices.

### External constraints and design references

- Windows media sessions can expose playback state and thumbnail artwork but do not provide a general live capture of another app's protected video surface: [GlobalSystemMediaTransportControlsSession](https://learn.microsoft.com/en-us/uwp/api/windows.media.control.globalsystemmediatransportcontrolssession?view=winrt-26100), [media thumbnail property](https://learn.microsoft.com/nl-be/uwp/api/windows.media.control.globalsystemmediatransportcontrolssessionmediaproperties.thumbnail?view=winrt-22000).
- Windows passkeys support companion-device flows in the native sign-in experience; FIDO2 Windows logon depends on eligible Entra device/account policy: [Windows passkeys](https://learn.microsoft.com/windows/security/identity-protection/passkeys/), [FIDO2 security-key sign-in](https://learn.microsoft.com/en-us/azure/active-directory/authentication/howto-authentication-passwordless-security-key-windows).
