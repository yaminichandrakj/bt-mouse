# Unified Connectivity & Pairing — Scope & Plan

**Status:** Scoping (later build). Phase 1 approved to start.
**Date:** 2026-10-25

## Context

Today the app exposes **three separate connection tabs** (Bluetooth, Wi-Fi, Remote WAN)
and **two different QR codes** (LAN UDP on `53530`, WAN WebSocket on `53531`) that all
ultimately reach the same PC and the same command handler. The user wants the Lenovo
Smart Connect experience: **one QR scan** that enrolls a device into an ecosystem
(phone, tablet, laptop), after which the app picks Bluetooth / Wi-Fi LAN / WAN
automatically, and the only thing the user ever changes afterwards is a **PIN** that
authorizes a live session — so a stranger can't take over an unattended laptop.

Two concrete problems motivate this:

1. **Off-campus reachability.** The laptop sits behind the college's Fortinet NAT; it has
   no public address and Tailscale is blocked. A Cloudflare quick tunnel was tested from
   campus and works (QUIC to Cloudflare Delhi, full round trip confirmed), but its URL
   changes on every restart. We need an automatic way for the phone to learn the current URL.
2. **No device identity.** `client_session` is a per-process random UUID
   (`ConnectionCoordinator.kt:56`), so the PC can't distinguish controllers. When more than
   one input source is active they merge "as the same device." Shared multi-device control
   is impossible without a stable identity per device.

### Decisions locked with the user
- **Scope:** design the full ecosystem now (phone + tab + laptop as peers), even if built in phases.
- **Multi-control:** simultaneous / shared — several devices can drive one host at once.
- **Transport preference:** Auto (silent lowest-latency pick that still supports the current feature; full features force Wi-Fi/WAN over Bluetooth HID).
- **Pairing model:** QR **enrolls** (permanent identity + one-time enrollment secret → trusted key pair); **PIN authorizes** each session and is rotatable without re-scanning.

---

## Target architecture

### 1. Durable identity + enrollment (the single QR)
- PC generates a long-lived identity keypair on first run (Ed25519 for signing, X25519 for
  key agreement), stored next to `pairing.json` in `%LOCALAPPDATA%\BTMouse\`. Its public key
  is the PC's stable **fingerprint**.
- Each phone/tablet generates its own keypair on first run (private key in the Android Keystore).
- **One QR** encodes: PC fingerprint, display name, every reachable coordinate the PC knows
  (LAN IP + ports, Bluetooth MAC + RFCOMM UUID, and the WAN rendezvous = `{workerUrl, recordId, verifyKey}`),
  plus a **short-lived, single-use enrollment secret**.
- Scanning runs an authenticated key exchange seeded by the enrollment secret (SPAKE2, or an
  X25519 ECDH authenticated by an HMAC of the secret). Result: each side stores the other's
  public key as a **trusted pairing record**; the enrollment secret is consumed so a later
  re-scan by someone else can't self-enroll without a fresh secret.
- After enrollment the phone holds the PC's identity + all coordinates = "synced." No re-scan
  ever again, across any transport change.

### 2. PIN = per-session authorization (rotatable), not the transport key
- The transport key is derived per session from the pairing keypairs (ephemeral ECDH → session
  key). The **PIN is a separate human gate**: to *start controlling*, the device must prove the
  current PIN inside the handshake.
- Rotating the PIN on the PC **immediately drops active sessions** (fixes today's gap where a
  PIN change logs no one out) and forces re-entry. The device stays *enrolled* (keypair intact),
  so the user just types the new PIN — no QR re-scan. This is exactly "only ever change the PIN."

### 3. One encrypted envelope across all transports
- Define **session protocol v2**: a framed, encrypted envelope identical over UDP datagram /
  RFCOMM stream / WebSocket. Fields: `deviceId` (fingerprint), `sessionId`, `seq`, `nonce`,
  `ciphertext` (the existing JSON command, encrypted with ChaCha20-Poly1305 under the session key).
- This reuses the existing `type`/`seq`/`client_session`/`path_switch` machinery verbatim —
  it just moves inside the ciphertext and is keyed to the **durable `deviceId`** instead of a
  per-process UUID. Closes the plaintext-PIN hole (Wi-Fi UDP) and the no-auth hole (RFCOMM) at once.

### 4. One connection screen + auto transport (Android)
- Collapse the three tabs into a single **Connect** screen: one scanner, one status card,
  manual entry behind "Advanced." After the single scan, `ConnectionCoordinator`'s existing
  latency failover (`:86-127`) is extended to also own Bluetooth and to choose across *all*
  enrolled coordinates for the PC — try bonded BT in range, LAN via discovery, else WAN tunnel;
  pick by latency; fail over silently.

### 5. WAN rendezvous (Phase 1 — builds on the approved tunnel)
- PC runs a `cloudflared` quick tunnel and **publishes the current `trycloudflare.com` URL,
  signed, to a Cloudflare Worker + KV** under a random record id. The QR carries
  `{workerUrl, recordId, verifyKey}`. The phone fetches the record, verifies the signature,
  and connects `wss://…`. On tunnel restart the PC republishes; the phone re-resolves on its
  next attempt. Tiny KV read/write per connect — negligible against the free Workers allowance,
  and nothing of the traffic itself goes through the Worker.

### 6. Multi-device shared control (simultaneous)
- Replace the single `connected_client` string with a **session registry keyed by `deviceId`**.
  Every enrolled device that authorizes gets a live session. Input from several devices
  multiplexes into the one OS cursor (last-writer-wins), but is tagged by `deviceId` so dedup is
  per-device (no cross-device `seq` collisions), the PC UI can list each controller, and any one
  can be revoked/kicked individually. The existing camera/live-audio ownership lease
  (`_refresh_camera_stream_lease`, server.py:2989) extends from `client_session` to `deviceId`.
- Hub-and-spoke (each device → the PC host) is v1. True peer-to-peer (phone ↔ tab directly) is a
  noted future extension.

### 7. Security hardening (needed regardless)
- Encrypt stored secrets: Android `SharedPreferences` is currently plaintext — move keys to
  Keystore-backed storage (androidx.security-crypto / Tink).
- Add rate-limiting to the WebSocket auth handshake (today it has none and every WS client
  shares the key `"WebSocket"`).
- Remove the insecure-RFCOMM fallback (`BluetoothRfcommManager.kt:157-170`); require the encrypted envelope.
- PIN rotation must invalidate live sessions (see §2).
- Flag (separate concern): the hardcoded release keystore password `btmouse123` in `app/build.gradle.kts:39-41`.

---

## Libraries to add
- **PC:** `PyNaCl` (Ed25519, X25519, ChaCha20-Poly1305) — add to `requirements.txt` and to the
  PyInstaller `binaries`/`collect_all` in `BT-Mouse-Companion.spec`. Bundle `cloudflared.exe`
  via a `binaries=[('cloudflared.exe','.')]` entry, or fetch it at runtime with the existing
  GitHub-release updater pattern (`app_gui.py:2687`).
- **Android:** Tink (or lazysodium) for the matching primitives + `androidx.security-crypto`
  for Keystore-backed secret storage. (No crypto libs exist today.)

---

## Phasing

| Phase | Deliverable | Main files |
|---|---|---|
| **1 (approved, start now)** | cloudflared quick tunnel + Cloudflare Worker/KV **signed** address lookup; Android resolves WAN URL from the record before connecting. No protocol change yet (still PIN auth over `wss`). Gets off-campus working. | New: Worker script, PC tunnel-manager + publish loop, `app_gui.py` WAN page. Android: `RemoteWanManager.kt` resolve step, QR payload field. |
| **2** | Device keypairs + enrollment handshake + encrypted envelope **on WAN first** (already `wss`). Keep legacy auth for other transports during transition. | PC: new `identity`/`crypto` module, `RemoteWebSocketServer`. Android: new crypto module, `RemoteWanManager`, `SettingsRepository` (store PC identity). |
| **3** | Encrypted envelope on LAN UDP + RFCOMM; **unify Android UI to one Connect tab + one QR**; coordinator auto-selects across all enrolled coordinates. | `WifiClientManager`, `BluetoothRfcommManager`, `ConnectionScreen.kt`, `ConnectionCoordinator.kt`; PC `Server`/`BluetoothRfcommServer`/discovery. |
| **4** | Server session registry keyed by `deviceId`; simultaneous multi-device control; PIN-rotation invalidation; per-device revoke UI on PC. | PC `handle_packet` + registry, `app_gui.py` device list. Android `ConnectionCoordinator` (deviceId as session key). |
| **5** | Ecosystem polish: tablet enrollment, named devices, PC-side device management; optional peer-to-peer. | UI both sides. |

---

## Verification
- **Phase 1:** start the companion → confirm a `trycloudflare.com` URL is published and the KV
  record is signed; kill and restart the tunnel → confirm the record updates and the phone
  reconnects without rescanning; connect the Android app over mobile data off-campus.
- **Phase 2+:** enroll once via QR; confirm traffic is ciphertext on the wire (capture shows no
  plaintext PIN/JSON); rotate the PIN → confirm active session drops and reconnect needs the new
  PIN but no rescan; confirm a tampered/replayed packet is rejected.
- **Phase 4:** connect phone + tablet together → PC lists two distinct `deviceId`s; revoke one →
  only that one drops; confirm no input doubling.
- **Regression:** `pc_server/test_server.py` asserts the QR payload shape
  (`test_server.py:799-800`) — update when the QR format changes.
