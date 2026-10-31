# BT Mouse Ecosystem — Plan (v1.6 → v2.0)

**Status:** v1.7.0 built (see the status tables at the end) | **Date:** 2026-10-31 | **Builds on:** [`unified_connectivity_plan.md`](unified_connectivity_plan.md)

## Where things stand today (v1.5.0)

| Feature asked for | In the code today? |
|---|---|
| 1. Laptop mouse/keyboard controlling the phone | **No.** All input goes phone → PC. The phone has no way to receive or inject input. |
| 2. Laptop + tab + phone in one system | **Partly.** Each phone or tab connects to the PC on its own, but the PC tracks only one `connected_client`, and devices cannot reach each other. |
| 3. Shared clipboard and file transfer | **No.** Only recordings can be downloaded from PC to phone (`recording_download`). |
| 4. Android-only setup (no laptop) | **No.** The PC companion is the only server. The app is client-only. |
| 5. A/V streaming from any device to any device | **Fixed routes only:** phone camera → PC, PC webcam → phone, PC screen → phone, mic in both directions. One viewer each. |
| 6. Recordings available and playable everywhere | **Partly.** The phone can list and download PC recordings. The PC cannot see phone recordings, and a tab cannot see either. |

## Core idea: every device is a "node"

Today the PC is a server and the phone is a client. The ecosystem treats **every device (laptop, phone, tab) as an equal node** that:

1. has a **stable device ID and name** (enrolled once by QR, as already planned in `unified_connectivity_plan.md` §1);
2. **runs a small server** (the PC already does; the Android app gains one on its own port);
3. **advertises what it can do** (`capabilities`: `input.receive`, `input.send`, `clipboard`, `files`, `stream.camera`, `stream.screen`, `stream.mic`, `library`);
4. talks the **same JSON protocol** over Wi-Fi, WebSocket, or Bluetooth.

Every message gets `from` and `to` device IDs. When two devices can't reach each other directly (for example, phone on mobile data and tab at home), any node they can both reach **relays** the message. The laptop is the usual relay, but any phone can do it too, which is what makes the Android-only mode (item 4) work.

```
        ┌──────── Laptop (Windows companion) ────────┐
        │  input hooks · clipboard · files · streams │
        └───────▲──────────────────────────▲─────────┘
       Wi-Fi/WS │ BT                 Wi-Fi/WS │
        ┌───────┴──────┐  Wi-Fi LAN  ┌───────┴──────┐
        │    Phone     │◄───────────►│     Tab      │
        │ (node server)│             │ (node server)│
        └──────────────┘             └──────────────┘
```

Discovery: the existing UDP broadcast is extended so each node announces `{deviceId, name, kind, ports, capabilities}`. Android nodes also use **NSD/mDNS** (`_btmouse._tcp`), which works without a laptop.

---

## Item 1 — Laptop keyboard and mouse controlling the phone/tab

### How Android lets us do this (no root)
- **Pointer and touch:** an **AccessibilityService** (`BtMouseControlService`) draws an on-screen cursor overlay and turns clicks, drags and scrolls into touch gestures with `dispatchGesture()`. It also handles Back, Home, Recents, Notifications and Quick settings through `performGlobalAction()`.
- **Keyboard:** an optional **input method** ("BT Mouse Keyboard", an `InputMethodService`) receives keystrokes from the laptop and calls `commitText()` and `sendKeyEvent()`. This gives real typing in any app, including arrows, Backspace, Enter, Ctrl+C/V/A/Z, and Unicode. If the input method is not selected, a fallback uses the AccessibilityService to set text on the focused field (less reliable).
- You turn these on **once** in Android Settings. The app has a guided "Allow laptop control" card with deep links. Android requires this one-time step; there is no way around it.

### How switching works (your question: is it always on, or do you toggle it?)

It is **always available but never stealing input**. Input goes to exactly **one target at a time**. The laptop is the default target. You switch in any of these ways:

| Method | What happens |
|---|---|
| **Screen-edge crossing** (default ON, like Mouse Without Borders) | Push the laptop cursor off the chosen edge (you choose which device sits left, right or above in a layout grid). The cursor appears on the phone. Push it back off the phone's opposite edge to return. |
| **Hotkey** | `Ctrl+Alt+→` goes to the next device, `Ctrl+Alt+←` to the previous one, `Ctrl+Alt+1/2/3` to a specific device. **`Ctrl+Alt+Home` (panic key) always returns to the laptop**, even if the phone hangs or disconnects. |
| **Tray icon / companion window** | A "Controlling: Laptop ▾" dropdown in the tray menu and on the Connect page. |
| **From the phone** | A notification button reads "Laptop is controlling this phone · Release". Tapping it hands input back. |

While the phone is the target, the laptop **captures** keyboard and mouse with low-level Windows hooks (`WH_KEYBOARD_LL`/`WH_MOUSE_LL` via `pynput` with suppression), so your keystrokes don't also land in laptop apps. The phone shows a coloured border and cursor, and the laptop shows a small "→ Phone" pill, so you always know which device has input. If the link drops for more than 1.5 s, control **automatically snaps back** to the laptop.

### Device layout editor (laptop, drag and drop)
A **Layout** page in the companion works like Windows *Settings → Display → Arrange displays*:
- Each laptop monitor appears as a rectangle at its real size and position, read from Qt `QScreen` geometry. Monitors are fixed and shown for reference only.
- Each paired phone or tab appears as a rectangle at its **real aspect ratio**, which the device reports with its screen size and rotation. You **drag it so it touches any edge of any monitor**: left, right, top or bottom, at any position along that edge. It snaps to the edge when you drop it.
- Only the shared part of the edge crosses over. For example, a phone placed against the lower half of the right edge only takes the cursor from that lower half, and the cursor's height is mapped proportionally.
- Devices can also be docked to each other (tab to the right of the phone), so the cursor can keep going laptop → phone → tab.
- When a phone rotates, its rectangle rotates live and the crossing maps update.
- Other controls: a per-device "edge crossing on/off" toggle, drag a device back to the tray area to un-place it (hotkey-only), **Identify** (flashes the device's screen), and **Reset**.
- The layout is saved per device ID in `%LOCALAPPDATA%\BTMouse\layout.json`, so it survives restarts and reconnects.

Other settings: hotkeys, pointer speed on the phone, and "lock cursor to current device".

---

## Always on, always connected (every device)

**Today:**
- **Laptop:** the companion starts at Windows sign-in (the self-healing `Run` entry) and keeps listening. It doesn't actively search; it waits for the phone to find it.
- **Android:** the app only reconnects while it is open or while a stream is running (`CompanionSessionService` stops itself otherwise). Nothing restarts it after a reboot.

**Planned — "Stay connected" (Android, on by default, can be turned off):**
- `CompanionSessionService` becomes a **permanent foreground service** with a quiet, low-priority notification: "Connected to Laptop · Wi-Fi" plus Disconnect and Release-control buttons. This is the only way Android allows an app to stay alive; Android requires the notification to be shown.
- It **starts on boot** (`RECEIVE_BOOT_COMPLETED`) and **after app updates** (`MY_PACKAGE_REPLACED`).
- It **reconnects on its own**:
  - listens for network changes (`ConnectivityManager.NetworkCallback`), and when Wi-Fi comes up, discovers the laptop and connects within a few seconds;
  - when Wi-Fi drops, fails over to WAN or Bluetooth (the existing supervisor);
  - when nothing is reachable, backs off to a probe every 60 s so it doesn't drain the battery;
  - also reconnects when a paired Bluetooth device comes into range (`ACTION_ACL_CONNECTED`).
- The node server (needed for laptop → phone control) lives in this same service, so the phone is always ready to be controlled.
- **Battery:** idle is just a heartbeat every 15 s. Screen and camera streams run only on demand. A first-run card asks you to set the app's battery use to "Unrestricted" (`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`), because otherwise Xiaomi, Oppo, Vivo and Samsung kill background apps. The card includes brand-specific instructions for those phones.

**Laptop side — searches actively too:**
- Besides listening, the companion **announces itself** every 5 s on the LAN (UDP and mDNS) and **connects outward** to known Android nodes it discovers. Whichever device comes up first, they find each other.
- It keeps starting at sign-in, minimised to the tray. A **Windows service** variant (works before sign-in) is not planned, because input capture and the clipboard need a signed-in desktop.

## Item 2 — One ecosystem: any device controls any device

Using the node model above:

| Controller → Target | How |
|---|---|
| Phone/Tab → Laptop | Already works (trackpad, keyboard, all PC tools). |
| Laptop → Phone/Tab | Item 1. |
| Phone ↔ Tab | Node-to-node: the controller's trackpad and keyboard screens get a **target picker** ("Controlling: Laptop ▾ / Tab / Phone"). Events go to the target's AccessibilityService and input method, exactly as in item 1. |
| Laptop as hub | One keyboard and mouse switch across all three devices with edges and hotkeys. Devices are arranged on a layout grid in the companion. |

What else the target picker reaches:
- **Remote screen view of a phone or tab.** The target streams its screen (Android `MediaProjection`) to the controller, so you can see what you're tapping on. Android asks you to approve screen capture once per session; that prompt can't be skipped.
- **Remote actions on Android:** Back, Home, Recents, notifications, volume, screenshot, open an app (from its launcher list), lock screen (accessibility global action).
- **What Android can't do:** unlock a phone protected by PIN or fingerprint, control the lock screen, or interact with secure screens such as banking apps and password fields, which show black or block gestures by design. The app will say this up front.

Device management on every node: a list of paired devices, rename, revoke, and per-device permissions ("Tab may control Phone: yes/no"). This brings in the session registry keyed by `deviceId` from `unified_connectivity_plan.md` Phase 4.

---

## Item 3 — Synced clipboard and file transfer

### Clipboard
- **Laptop:** watch `QClipboard.dataChanged`. Push text, and images up to 5 MB, to all trusted nodes.
- **Android:** Android 10+ blocks background clipboard *reading* (writing is fine). So:
  - laptop → phone copies are **instant and automatic**;
  - phone → laptop copies go through a **"Send clipboard" Quick Settings tile**, a notification action, a share-sheet target ("Share → BT Mouse → Copy to all devices"), or automatically whenever the app is in the foreground. When the AccessibilityService is on, it also catches copy events in many apps.
- Settings: on/off per device, text only or text plus images, and a 20-item clipboard history page.
- Loop protection: each clip carries a hash plus its origin device, so it is never echoed back.

### File transfer
- **Send:** drag and drop onto the companion window or tray, right-click "Send to → Phone/Tab" in Explorer (`SendTo` shortcut), the Android share sheet ("Share → BT Mouse → choose device"), and a "Send files" button in the app.
- **Transport:** chunked transfer (256 KB chunks, SHA-256 per file, resume after a drop). Over LAN it uses a direct HTTP stream to the node server (fast, 20–80 MB/s on Wi-Fi 5/6). Over WAN or relay it uses WebSocket chunks. Bluetooth RFCOMM is only for small files, because it is slow.
- **Receive:** files land in `Downloads/BT Mouse/` on Android (via MediaStore, so they show in Files and Gallery) and `%USERPROFILE%\Downloads\BT Mouse\` on the laptop. A notification offers Open and Show in folder. A transfers page shows progress, speed, cancel and retry.
- Folders are sent as zip on the fly or as a file list.

---

## Item 4 — Android-only ecosystem (no laptop)

The Android app gets its own **node server** inside `CompanionSessionService` (it already runs a localhost camera HTTP server). It exposes a WebSocket and an HTTP port on the LAN, authenticated with the same enrollment keys and PIN.

- **Pairing phone ↔ tab:** one device shows a QR code (new "Show pairing QR" on Android), and the other scans it.
- **Without Wi-Fi:** phone hotspot plus tab on that hotspot works as normal LAN. **Wi-Fi Direct** is a later step if needed.
- **Features available Android ↔ Android:** control (item 1 mechanics), clipboard, files, camera, screen and mic streams, and the shared library. **Not available:** PC-only tools such as task manager, quick apps, and PC volume.
- **Bluetooth HID mode** (phone as a Bluetooth mouse for a tab) already works with no server, because Android tablets accept Bluetooth mice. The plan adds a "Use as mouse for another Android device" shortcut on the Connect screen for this.

---

## Item 5 — A/V streams: any device to any device, multiple viewers

### Stream model
Every node can **publish** sources, and any number of other nodes can **tune in**:

| Source | Laptop | Phone/Tab |
|---|---|---|
| Camera | webcam (exists) | front/back camera (exists, made multi-viewer) |
| Screen | full screen or region (exists as preview, becomes a real stream) | `MediaProjection` (new) |
| Microphone | exists | exists |
| System audio | WASAPI loopback (new) | `AudioPlaybackCapture` on Android 10+, only for apps that allow it (new) |

- A **"Live" page** on every device lists every stream on the network ("Phone · Back camera · 2 watching"), with Watch and Record buttons. The publisher sees who is watching and can kick any viewer or stop the stream.
- **Fan-out:** the publisher encodes each frame **once**. Its node server sends it to every subscriber, and the relay copies frames when needed. The current JPEG-frame plus PCM approach is kept first (it works today on all links). Then:
- **Upgrade path:** H.264 through `MediaCodec` on Android and through `PyAV`/ffmpeg on the laptop, sent in the same frame envelope. This uses about 5–10× less bandwidth than JPEG, so 720p30 becomes practical over Wi-Fi. Opus replaces PCM for audio.
- The existing failover and lease machinery (`_refresh_camera_stream_lease`, `LiveAudioManager.touch_owner`) is generalised from "one owner" to "a set of subscribers per stream".
- Any viewer can **record** a stream locally, and the file goes into the shared library (item 6).

---

## Item 6 — One library across all devices

- Every node indexes its own `Videos/`, `Audios/`, `Snapshots/` and the new `Received/` folder, and serves a **library manifest**: id, name, type, size, duration, created time, origin device, SHA-256, and a small thumbnail.
- The **Library page on every device** shows one merged list, with filters for type and device and a badge for where each item lives ("on Laptop", "on Phone", "here").
- **Play anywhere without copying:** files are streamed with HTTP Range requests from the owning node (or through the relay). Android uses **Media3/ExoPlayer**, which plays mp4, wav and m4a and supports seeking. The laptop uses **QtMultimedia** (`QMediaPlayer` with `QVideoWidget`). Snapshots open in the zoom viewer.
- **Keep offline / auto-sync:** each device has an optional setting, such as "Auto-copy new recordings to: Laptop" (default: laptop keeps a copy of everything, phones stream on demand to save storage). Duplicate detection uses SHA-256.
- Deleting a file asks "Delete here only / everywhere".
- **Playable everywhere** means every saved format must play on every device. Recordings standardise on **MP4 (H.264 + AAC)** for video, **M4A (AAC)** for audio and **JPEG** for snapshots. Existing `.wav` files are converted on the laptop with the bundled ffmpeg.

---

## Build order

Ordered so each release is useful on its own, with the riskiest Android pieces first while there is still time to test them.

| Release | Contents | Main files |
|---|---|---|
| **v1.6 — Laptop → phone control + always connected** | Android AccessibilityService (cursor overlay and gestures) and input method; PC input capture with hooks; **drag-and-drop layout editor**, edge crossing, hotkeys, panic key, tray toggle; auto-return on disconnect; **"Stay connected" service with start on boot, network-change reconnect and battery card; PC announce + outward connect**; setup guide card. | New `control/BtMouseControlService.kt`, `control/BtMouseImeService.kt`, `control/RemoteInputInjector.kt`; PC new `input_share.py`; `server.py` dispatch; `app_gui.py` Layout page, Connect page and tray; `CompanionSessionService.kt`, new `BootReceiver.kt`. |
| **v1.7 — Clipboard and files** | Clipboard sync (auto laptop → phone; tile, share and foreground for phone → laptop); file send and receive with resume; Android share target; Explorer SendTo; transfers page on both sides. | New `sync/ClipboardSync.kt`, `sync/FileTransfer.kt`, `ShareReceiverActivity`; PC new `clipboard_sync.py`, `file_transfer.py`. |
| **v1.8 — Nodes and identity** | Stable device ID, names, capability announce, mDNS; Android node server; session registry and per-device permissions; `from`/`to` routing and relay; Android "Show pairing QR"; Phone ↔ Tab control, clipboard and files without a laptop. | New `node/NodeServer.kt`, `node/NodeRegistry.kt`, `node/Discovery.kt`; PC `node_registry.py`; `ConnectionCoordinator.kt` target picker. |
| **v1.9 — Streams hub** | Publish/subscribe for camera, screen and mic on every node; Android screen capture; multi-viewer fan-out; Live page; record any stream. | `PhoneCameraStreamer.kt` → `streams/StreamPublisher.kt`; new `streams/ScreenCaptureSource.kt`; PC `streams.py`. |
| **v2.0 — Universal library** | Library manifest on every node; merged Library page; Range-request streaming; ExoPlayer and QtMultimedia players; auto-copy rules; format standardisation. | `RecordingTransferClient.kt` → `library/LibraryClient.kt`; PC `library.py`; Media3 dependency. |
| **Later** | H.264/Opus codecs, Wi-Fi Direct, encrypted envelope everywhere (`unified_connectivity_plan.md` Phases 2–3). | |

## Honest limits (Android rules, not app choices)
- Accessibility and keyboard permissions must be enabled by hand once per device.
- Screen capture needs a tap-to-allow each time a screen stream starts (Android 14+).
- Phone → laptop clipboard can't be fully automatic in the background (Android 10+ privacy).
- No control of the lock screen, PIN/biometric entry, or secure-flagged apps.
- System-audio capture only works for apps that allow capture.
- Nothing here has been tested on a physical device in CI. Each release needs a hands-on check on your laptop, phone and tab.

## Verification per release
- CI: `pc_server/test_server.py` gains tests for routing, clipboard loop protection, transfer resume and hash checks, library manifest merging, and stream fan-out. Android build must stay green. Unit tests cover gesture mapping (pointer delta → path), the key-code table and chunk reassembly.
- Manual checklist (shipped in each release note): switch by edge, hotkey, panic key and phone notification; copy on each device and paste on the others; send a 1 GB file and kill Wi-Fi halfway to check resume; two viewers on one phone camera stream; play a phone recording on the laptop and a laptop recording on the tab.

## Build status (2026-10-31)

Everything below shipped together in **v1.6.0** instead of five separate releases. It compiles in CI (APK and Windows EXE) and is covered by `pc_server/test_ecosystem.py`. None of it has been tried on physical devices yet.

| Item | Status | Differences from the plan |
|---|---|---|
| 1. Laptop → phone control | Built | Same as planned: Accessibility pointer and gestures, keyless input method, Windows LL hooks, edge crossing, hotkeys, panic key, tray, pill, 3 s auto-return. Drags are replayed when the button is released, not live. |
| Layout editor | Built | Drag-and-drop canvas on **Devices**; snaps to any monitor or device edge; strip for hotkey-only devices. |
| Always connected | Built | Persistent service, boot/update receiver, network callback, once-a-minute retry, battery prompt. The PC does not dial out to phones; phones reconnect to it. |
| 2. Any device controls any device | Built through the PC hub | Phone/tablet ↔ phone/tablet goes through the PC relay (`to` field). There is no direct Android-to-Android server yet. |
| 3. Clipboard and files | Built | Text clipboard only (no images). Files are 32 KB chunks over the existing links (UDP/RFCOMM/WS) with resume; there is no fast HTTP path on the LAN yet. |
| 4. Android-only (no laptop) | **Not built** | Needs an Android node server and phone-to-phone pairing. Bluetooth HID (phone as mouse for a tablet) still works without the PC. |
| 5. Streams | Built (JPEG) | Phone screen (MediaProjection) and phone camera publish to the PC hub; any device or the PC window watches; a watched screen can be controlled. PC screen sharing stays as before. No H.264/Opus and no audio streams through the hub yet. |
| 6. Library everywhere | Built (PC as hub) | The PC copies every phone/tablet recording into its Library; phones browse and play the PC's Library as before. No in-place streaming from a phone to another phone. |

### Next steps, in order
1. Test on real devices with the v1.6.0 release-note checklist; fix what breaks.
2. Android node server (LAN WebSocket plus NSD) for item 4 and faster phone ↔ tablet links.
3. HTTP file path on the LAN for large transfers.
4. H.264 (MediaCodec) for streams; system-audio streams.
5. Live drags (`continueStroke`) instead of replaying on release.

## Build status (v1.7.0)

| Item | Status in v1.7.0 |
|---|---|
| Live drags | Built: continued strokes follow the pointer, with automatic fallback to replay-on-release (`RemoteControlService.pumpLive`). |
| Picture clipboard | Built both ways. PC → phone is automatic. Phone → PC follows the same Android limits as text. Pictures travel as small file transfers flagged `clipboard`. |
| LAN fast path | Built: `TransferHttpServer` (PC, port 53536, per-transfer token) plus OkHttp pull and upload on Android, resuming from received chunks and falling back to chunks on any error. |
| 4. Android-only | Built as a **phone hub** (`AndroidHub`, UDP 53540, same protocol and QR format as the PC). The tablet's trackpad, keyboard and Screen preview drive and show the phone. Clipboard, files, control and screen watching work both ways. Not built: relaying between two tablets, the phone watching a tablet's screen, and discovery without the QR. |
| PC dials out to phones | Not built (phones still reconnect to the PC). |
| H.264/Opus, audio through the hub | Not built. Next big item. |

### Next steps
1. Physical-device pass with `docs/ecosystem_test_checklist.md` (v1.6 and v1.7 sections).
2. H.264 via MediaCodec for screen and camera streams, decoding with MediaCodec on Android and PyAV on the PC.
3. Audio streams through the PC hub (reuse the live-audio PCM path, then Opus).
4. Hub: relaying between tablets, and watching a tablet's screen from the phone.
