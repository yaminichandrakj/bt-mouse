# BT Mouse & PC Companion — Implementation Plan

**Plan version:** 2.3
**Repository:** `BT Mouse`
**Status:** Active implementation
**Updated:** 2026-10-23

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
| Critical | Network security | Authenticate every UDP command, not only power/app/process commands; remove shell-based arbitrary launch; keep WAN auth before dispatch and require TLS for Internet use. | Implemented; Python syntax check passes; security regression tests pending |
| High | RFCOMM/WAN buttons | Handle button-only packets and release held mouse/pen/keyboard input on disconnect or prolonged idle. | Implemented in `server.py`; device verification pending |
| High | Wi-Fi protocol | Align command names and add an acknowledged, PIN-authenticated connect flow with timeout/cancel behavior. | Implemented; device verification pending |
| High | RFCOMM response path | Await WinRT `store_async()` on its owning event loop; report and handle failed writes. | Implemented with a bounded response queue; Windows device verification pending |
| High | Wi-Fi camera stream | Deliver frames/status to the UDP peer or explicitly disable the feature on UDP; do not claim success without a working stream. | UDP streaming now returns an explicit unsupported response; snapshots are size-bounded |
| High | Numpad | Map every keypad HID usage in the PC companion, including operators and decimal. | Implemented in `server.py`; device verification pending |
| Medium | Media state | Normalize mute field parsing and isolate state by selected transport; handle non-current WinRT sessions and failed operations. | Core normalization/routing implemented; Windows media-session verification pending |
| Medium | Connection state | Only show connected after handshake; clear connection on timeout, socket failure, mode change, and disconnect. | Implemented for Wi-Fi/WAN; Bluetooth device verification pending |
| Medium | Writing pad | Use selected color; describe mouse-based mapping accurately; clamp and validate coordinates; do not claim pressure support the PC cannot consume. | Color and capability labels implemented; pressure remains phone-preview-only |
| Medium | Navigation | Remove redundant Settings bottom tab and provide a clear return path from Settings. | Implemented; Android build verification pending |
| Medium | Dynamic apps | Query installed Start Menu entries, make launch identifiers safe, support search/pinning, and handle missing apps. | Safe server launch and app query implemented; Android flow verification pending |
| Low | Quality | Add isolated protocol/security regression tests and run available compile/build checks. | Codex-owned tests and GitHub Actions verification in progress |

## Architecture and protocol contract

- One normalized JSON command vocabulary is shared across UDP, Bluetooth RFCOMM, and WebSocket. Keep aliases temporarily where older clients send them, but have current Android code emit canonical names.
- UDP discovery returns only server address, port, and display name. The user enters the pairing PIN on the phone. `connect` includes the PIN; the server returns `connect_ack`. The app sends the PIN with every subsequent UDP command so authorization does not depend on an IP-only allowlist. The server does not retain an IP-only authenticated session; disconnect releases held mouse buttons.
- Reject unauthenticated UDP commands before any input, query, recording, camera, or system action. Keep discovery and a rate-limited failed-auth response as the only pre-auth operations. Bluetooth RFCOMM relies on OS-level pairing and the RFCOMM session; WebSocket relies on the authenticated WebSocket session.
- Avoid `shell=True`. Launch only validated registered app identifiers, approved system utilities, or canonical Start Menu shortcut paths. Never treat arbitrary client text as a command line.
- `connect_ack`, error, media, stats, apps, process, camera, audio, and kill responses use the same response types on all supported transports. UDP responses go to the requesting endpoint; RFCOMM responses are newline-delimited JSON; WebSocket responses remain JSON messages.
- UDP camera streaming returns an explicit unsupported/error response because ordinary large UDP video datagrams are unreliable. Snapshots are encoded with a bounded UDP payload; use Bluetooth RFCOMM or authenticated WebSocket for live video. Do not broadcast camera frames to unrelated clients.
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

**Release identity:** `v1.3.0` already exists as a source tag, so it must not be moved or overwritten. The next release is `v1.3.1` (`versionCode = 8`). Preserve the established Android application id and signing identity so existing installations can upgrade. CI currently reads the repository's historical release keystore; migrating it to protected Actions secrets and removing the checked-in key is a separate security migration because it must preserve the exact signing key.

**Acceptance:** deterministic regression tests pass; GitHub Actions Android and Windows build jobs pass; release `v1.3.1` contains both the APK and EXE; Android compile/lint is checked by Actions; device-specific reliability is reported as pending until tested on real Android/Windows hardware.

## Current implementation sequence

1. Phase 1 security gates and safe launch behavior are implemented; keep them unchanged unless a review finds a concrete defect.
2. Handshake and transport parity are implemented; run protocol regression tests and device checks before claiming hardware reliability.
3. Media, app, writing surface, and mode-aware UI fixes are implemented; verify behavior on Android and Windows.
4. Codex adds and runs deterministic Python regression tests and adds them to the GitHub Actions workflow.
5. Bump the app/server version to `1.3.1`, preserve signing identity, push source changes, and let Actions build both release binaries.
6. After both builds pass, push tag `v1.3.1`, let Actions publish the GitHub Release, and verify both assets.

## Verification checklist

- [x] All network command types are gated by UDP PIN authentication or the established RFCOMM/WebSocket transport.
- [x] UDP handshake validates PIN and returns a source-addressed ACK; Android waits for it and times out disconnected.
- [x] Safe app launch validates Start Menu paths and known system identifiers; no client-controlled shell command is used.
- [x] RFCOMM writes are serialized on the WinRT event loop with bounded queueing and surfaced write errors.
- [x] Button-only packets are handled; held mouse, pen, and keyboard inputs are released on disconnect and after 30 seconds without input.
- [x] Wi-Fi and RFCOMM command names align; keypad map includes keypad usages.
- [x] UDP camera streaming is explicitly unsupported; UDP snapshots have a size bound; camera/audio status is based on manager outcomes.
- [x] Mute parsing accepts both field names; Bluetooth mode reads RFCOMM state instead of Wi-Fi state.
- [x] Writing pad color selection works; UI labels describe mouse emulation rather than a digitizer.
- [x] WAN setup omits the PIN from QR/discovery, avoids a fabricated default token, and requires `wss://` for non-local hosts.
- [x] Password persistence/injection for workstation unlock was removed.
- [x] Python syntax compilation passes for `server.py` and `app_gui.py`.
- [ ] Codex-owned Python protocol/security regression suite passes locally and in Actions.
- [ ] Android release build and Windows executable build pass in Actions.
- [ ] GitHub Release `v1.3.1` is published with APK and EXE assets.
- [ ] Hardware verification is completed on Android/Windows devices (not available in this environment).
- [x] Existing user edits are preserved; prior `v1.3.0` tag remains untouched.
