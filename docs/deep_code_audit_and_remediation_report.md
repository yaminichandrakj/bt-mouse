# Deep Code Audit & Remediation Report
**Project:** BT Mouse & Companion Ecosystem (Android Client & PC Companion Server)  
**Audit Baseline:** Release `v1.3.10` through `v1.3.15` (Oct 24, 2026)  
**Status:** High-Priority Remediation Required  
**Author:** Deep Code Audit & Security Assessment

---

## 1. Executive Summary

A deep architectural and forensic code audit was performed following the rapid succession of releases `v1.3.11` through `v1.3.15` and subsequent real-world testing. 

While the test suite passed and CI pipelines built successfully on GitHub Actions, real-device testing revealed that **the application fails to connect across all primary transports (Bluetooth, Wi-Fi, and Remote WAN)**. Furthermore, a rigorous code review verified critical vulnerabilities and functional bottlenecks.

This report documents:
1. **The exact root causes** preventing Bluetooth, Wi-Fi, and Remote WAN connections.
2. **Security vulnerabilities** including exposed signing keys, unauthenticated network video streaming, and unencrypted backup policies.
3. **Major functional defects** across failover logic, Android service lifecycles, UDP buffer limitations, and Windows UIPI injection.
4. **Roadmap corrections** to align `IMPLEMENTATION_PLAN.md` with actual platform capabilities.
5. **Concrete, line-by-line remediation steps** to restore connectivity, harden security, and ensure long-term stability.

---

## 2. Root-Cause Analysis: Why Transports Fail to Connect

Real-device testing in `v1.3.15` identified that **Bluetooth, Wi-Fi, and Remote WAN do not connect**. The investigation traced this breakdown to four intersecting defects in the Android client and PC companion server:

### A. The "Permission Gate" Paralyzes Coordinator Initialization
- **Location:** [`MainActivity.kt:41-48`](file:///c:/Users/yamin/Documents/Projects/BT%20Mouse/app/src/main/java/com/btmouse/app/MainActivity.kt#L41-L48)
- **Defect:** In commit `bcea928`, `POST_NOTIFICATIONS` was added to `checkAndRequestPermissions()`. The callback checks:
  ```kotlin
  private val requestPermissionsLauncher = registerForActivityResult(
      ActivityResultContracts.RequestMultiplePermissions()
  ) { permissions ->
      val allGranted = permissions.entries.all { it.value }
      if (allGranted) {
          connectionCoordinator.initialize()
      }
  }
  ```
- **Failure Impact:**
  1. On Android 13+ (API 33+), if a user taps "Don't allow" for notifications, or if their device hardware does not support BLE peripheral advertising (`BLUETOOTH_ADVERTISE` denied), `allGranted` evaluates to `false`.
  2. As a consequence, `connectionCoordinator.initialize()` is **never invoked**.
  3. On every subsequent launch, `checkAndRequestPermissions()` re-requests the denied permission, Android immediately returns `false` without showing a dialog, and `initialize()` remains completely blocked.
  4. Without `initialize()`:
     - `bluetoothRfcommManager.refreshPairedDevices()` is never called; the Bluetooth screen displays **"No Paired Devices Found"**.
     - `bluetoothHidManager.initialize()` never runs; Bluetooth HID emulation is dead.
     - `ConnectionCoordinator.activeInstance` remains `null`.
     - Connection supervisors, transport transition watchers, and session monitors are never launched.
     - The app is completely paralyzed for **all** transports.

### B. PC Companion QR Codes Omit the Pairing PIN
- **Location:** [`app_gui.py:2142-2147`](file:///c:/Users/yamin/Documents/Projects/BT%20Mouse/pc_server/app_gui.py#L2142-L2147) & [`app_gui.py:2365-2371`](file:///c:/Users/yamin/Documents/Projects/BT%20Mouse/pc_server/app_gui.py#L2365-L2371)
- **Defect:** In `app_gui.py`, both `generate_qr()` (Wi-Fi) and `generate_wan_qr()` (Remote WAN) construct QR payloads without the pairing PIN:
  ```python
  payload = json.dumps({
      "type": "btmouse",
      "ip": self.current_ip,
      "port": self.current_port,
      "name": hostname
      # CRITICAL: "pin" / "token" is completely missing!
  })
  ```
- **Failure Impact:**
  1. When a user scans the QR code from the PC screen, the Android client parses `ip` and `port`, but finds an empty `token`.
  2. In [`WifiClientManager.kt:561`](file:///c:/Users/yamin/Documents/Projects/BT%20Mouse/app/src/main/java/com/btmouse/app/connection/WifiClientManager.kt#L561):
     ```kotlin
     if (pin.isBlank() || pin.length != 6 || pin.any { !it.isDigit() }) {
         _statusMessage.value = "The PC companion pairing PIN must be six digits"
         return
     }
     ```
  3. `connectManual` immediately aborts without sending a single UDP datagram. The phone UI displays "Details scanned; waiting for the PC connection handshake", but no handshake packet is ever transmitted.
  4. Remote WAN scanning similarly displays a Toast prompting for manual entry because the token is absent from the QR code.

### C. The 2048-Byte UDP Receive Buffer in `server.py`
- **Location:** [`server.py:4161`](file:///c:/Users/yamin/Documents/Projects/BT%20Mouse/pc_server/server.py#L4161)
- **Defect:** The PC server receives incoming UDP datagrams using a static buffer:
  ```python
  data, addr = self.sock.recvfrom(2048)
  ```
- **Failure Impact:**
  1. On Windows, when an incoming UDP datagram exceeds the buffer size passed to `recvfrom()`, Winsock does not truncate the buffer; it aborts with error `WSAEMSGSIZE` (WinError `10040`: *"A message sent on a datagram socket was larger than the internal message buffer"*).
  2. Python raises an `OSError`, which enters the generic exception handler and discards the packet.
  3. Phone camera frames (15 KB – 45 KB), long text strings, or compound JSON payloads immediately crash the packet handler and are dropped.

### D. Fragile `connect_ack` Network Equality Checks
- **Location:** [`WifiClientManager.kt:201-205`](file:///c:/Users/yamin/Documents/Projects/BT%20Mouse/app/src/main/java/com/btmouse/app/connection/WifiClientManager.kt#L201-L205)
- **Defect:** The Wi-Fi manager matches handshake replies using strict reference and port equality:
  ```kotlin
  "connect_ack" -> {
      val pending = pendingConnect
      if (pending != null && pending.address == sourceAddress && pending.port == sourcePort) {
          pending.acknowledgement.complete(json)
      }
  }
  ```
- **Failure Impact:**
  1. On multi-homed PCs (e.g. Wi-Fi + Ethernet, VPNs, or WSL virtual adapters), `pending.address` (resolved target) and `sourceAddress` (the interface chosen by the OS routing table to reply) frequently differ.
  2. Because `pending.address == sourceAddress` fails, the `connect_ack` is ignored.
  3. The phone client loops through all 3 connection attempts and times out with *"PC server did not respond. Check the address, PIN, and firewall."*

### E. Tab Navigation Severing Active Sockets
- **Location:** [`ConnectionCoordinator.kt:267-286`](file:///c:/Users/yamin/Documents/Projects/BT%20Mouse/app/src/main/java/com/btmouse/app/connection/ConnectionCoordinator.kt#L267-L286)
- **Defect:** In `ConnectionScreen.kt`, switching between tabs (Bluetooth, Wi-Fi, Remote WAN) invokes `coordinator.setMode(mode)`. `setMode()` forcibly disconnects the previous transport:
  ```kotlin
  fun setMode(mode: ConnectionMode) {
      ...
      when (previousMode) {
          ConnectionMode.BLUETOOTH -> { bluetoothRfcommManager.disconnect(); bluetoothHidManager.disconnect() }
          ConnectionMode.WIFI -> wifiClientManager.disconnect()
          ConnectionMode.REMOTE_WAN -> remoteWanManager.disconnect()
      }
      settings.setConnectionMode(mode)
  }
  ```
- **Failure Impact:**
  1. A user cannot keep a Wi-Fi connection active while browsing the Bluetooth tab or vice versa.
  2. Any active connection is destroyed simply by looking at another tab.
  3. Multi-transport failover is completely prevented because non-active tabs are actively disconnected.

---

## 3. Security Vulnerabilities & Exposure Audit

### A. Exposed Android Release Keystore in Public Repository
- **Severity:** High (Identity & Supply Chain)
- **Files:** [`app/release.keystore`](file:///c:/Users/yamin/Documents/Projects/BT%20Mouse/app/release.keystore), [`app/build.gradle.kts:26-32`](file:///c:/Users/yamin/Documents/Projects/BT%20Mouse/app/build.gradle.kts#L26-L32)
- **Finding:** The release keystore file is checked directly into the repository, and `build.gradle.kts` falls back to hardcoded password `btmouse123`.
- **Impact:** Anyone with access to the repository history possesses the private signing key used to sign APK releases. An attacker could publish malicious APK updates matching the app's cryptographic signature.
- **Remediation:**
  1. Remove `release.keystore` from the Git repository.
  2. Add `*.keystore` and `*.jks` to `.gitignore`.
  3. Generate a fresh release signing key and transition to GitHub Actions Secrets (`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`).
  4. For Google Play or existing user installations, establish an APK signing key rotation / migration plan.

### B. Unauthenticated Network Camera Server on `0.0.0.0`
- **Severity:** High (Privacy & Unauthorized Access)
- **Files:** [`pc_server/server.py:2072-2155`](file:///c:/Users/yamin/Documents/Projects/BT%20Mouse/pc_server/server.py#L2072-L2155)
- **Finding:** `PhoneCameraHttpServer` binds to `0.0.0.0` across ports `8766–8775` and sets permissive CORS (`Access-Control-Allow-Origin: *`) without any token or PIN verification:
  ```python
  self.server = ThreadingHTTPServer(("0.0.0.0", p), Handler)
  ```
- **Impact:** The code comment claimed "localhost", but binding to `0.0.0.0` exposes live phone video, snapshots, and telemetry to **every device on the same local network (LAN / Wi-Fi)**. Anyone on the network can browse `http://<PC_IP>:8766/phone_cam/mjpeg` and monitor the user's camera.
- **Remediation:**
  1. Bind the HTTP server strictly to `127.0.0.1` so that only local applications (OBS Studio, virtual webcams, local browsers) can consume the stream.
  2. If LAN streaming is desired in the future, require authentication via URL parameter or header (`?pin=XXXXXX`).

### C. Unencrypted Credential Storage with Android Auto-Backup Enabled
- **Severity:** Medium (Credential Leakage)
- **Files:** [`AndroidManifest.xml:51`](file:///c:/Users/yamin/Documents/Projects/BT%20Mouse/app/src/main/AndroidManifest.xml#L51), [`SettingsRepository.kt:45`](file:///c:/Users/yamin/Documents/Projects/BT%20Mouse/app/src/main/java/com/btmouse/app/data/SettingsRepository.kt#L45)
- **Finding:** The pairing PIN and Remote WAN access tokens are stored in plain `SharedPreferences`. In `AndroidManifest.xml`, `android:allowBackup="true"` is enabled without backup rules, and `android:usesCleartextTraffic="true"` is globally enabled.
- **Impact:** Unencrypted credentials could be synced to unmanaged Google cloud backup drives or extracted via ADB backup tools.
- **Remediation:**
  1. Set `android:allowBackup="false"` or define `res/xml/backup_rules.xml` explicitly excluding `SettingsRepository` preference files.
  2. Restrict cleartext traffic to local private subnets (`192.168.0.0/16`, `10.0.0.0/8`, `172.16.0.0/12`) via a network security config.

---

## 4. Functional Defects & Architectural Limitations

### A. Activity Lifecycle Tearing Down Foreground Sessions
- **Location:** [`MainActivity.kt:107-110`](file:///c:/Users/yamin/Documents/Projects/BT%20Mouse/app/src/main/MainActivity.kt#L107-L110), [`ConnectionCoordinator.kt:696-710`](file:///c:/Users/yamin/Documents/Projects/BT%20Mouse/app/src/main/java/com/btmouse/app/connection/ConnectionCoordinator.kt#L696-L710)
- **Finding:** When the user rotates the device or the OS pauses/destroys `MainActivity`, `MainActivity.onDestroy()` calls `connectionCoordinator.cleanup()`. `cleanup()` stops `CompanionSessionService` and disconnects all sockets.
- **Impact:** The foreground service is intended to preserve live camera/audio companion sessions while backgrounded, but because the service does not own the connections, destroying the activity destroys the session.
- **Remediation:** Tie connection ownership to an application-level scope (`BtMouseApplication`) or have `CompanionSessionService` retain the active `ConnectionCoordinator` when background sessions are enabled.

### B. Failover Logic Does Not Measure Latency or Filter Incompatible Transports
- **Location:** [`ConnectionCoordinator.kt:66-95`](file:///c:/Users/yamin/Documents/Projects/BT%20Mouse/app/src/main/java/com/btmouse/app/connection/ConnectionCoordinator.kt#L66-L95)
- **Finding:** `activeTransport` selects connections based on a static preference list (`WIFI` -> `BLUETOOTH_RFCOMM` -> `BLUETOOTH_HID` -> `REMOTE_WAN`). It does not dynamically compare ping RTT latency. Furthermore, it prioritizes `BLUETOOTH_HID` ahead of `REMOTE_WAN` even though Bluetooth HID cannot carry companion commands (media metadata, volume, process manager, camera frames), leading to silent no-ops.
- **Remediation:**
  1. Compare actual RTT (`measuredLatencyMs`) between healthy connected transports to choose the lowest-latency path.
  2. Ensure companion command routing falls back to a protocol-capable transport (`RFCOMM`, `WIFI`, or `WAN`), reserving `BLUETOOTH_HID` strictly for mouse/keyboard injection.

### C. Phone-as-Webcam UDP Packet Sizing & PyVirtualCam Leaks
- **Location:** [`WifiClientManager.kt:947-956`](file:///c:/Users/yamin/Documents/Projects/BT%20Mouse/app/src/main/java/com/btmouse/app/connection/WifiClientManager.kt#L947-L956), [`server.py:1956-1967`](file:///c:/Users/yamin/Documents/Projects/BT%20Mouse/pc_server/server.py#L1956-L1967)
- **Finding:**
  1. High-resolution frames (720p/1080p) encoded in Base64 exceed the 65,507-byte IPv4 UDP datagram limit, causing `sendQueue` socket failures.
  2. On the PC companion server, `PhoneCameraReceiver.start()` instantiates a new `pyvirtualcam.Camera` without closing the existing instance, which can lock the virtual camera device when the user toggles resolution or switches between front and rear lenses.
- **Remediation:**
  1. Enforce preview resolution ceilings (480p/360p) for UDP, or implement frame chunking.
  2. In `PhoneCameraReceiver.start()`, explicitly call `self._release_virtual_cam()` before `_init_virtual_cam()`.

### D. Windows UIPI Blocks Mouse/Keyboard Injection into Elevated Windows
- **Location:** [`server.py:3825-3950`](file:///c:/Users/yamin/Documents/Projects/BT%20Mouse/pc_server/server.py#L3825-L3950)
- **Finding:** The companion server calls `user32.SendInput` to inject hardware mouse and keyboard events. When an elevated application (such as Task Manager, Registry Editor, or an Admin terminal) is in focus, Windows User Interface Privilege Isolation (UIPI) silently blocks input injection from non-elevated processes. The return value of `SendInput` was not evaluated.
- **Remediation:** Evaluate the return count of `SendInput`. If injection returns 0 and `GetLastError()` indicates access denial, notify the user via the live log that the PC companion must be run as Administrator to control elevated windows.

---

## 5. Roadmap & Documentation Rectifications

The rapid release cycle resulted in premature completion claims in [`IMPLEMENTATION_PLAN.md`](file:///c:/Users/yamin/Documents/Projects/BT%20Mouse/IMPLEMENTATION_PLAN.md):

1. **Phase 7 (Native Windows Companion):**
   - **Correction:** The roadmap marked Phase 7 complete in `v1.3.15`. In reality, `v1.3.15` added High-DPI font calibration and scaling to the **Python/Tkinter companion** and created an inventory specification. The native C# WinUI 3 application is **not yet built**. Phase 7 must be marked *In Progress (Inventory & Tkinter DPI Fix Complete; Native WinUI 3 Implementation Pending)*.
2. **Phase 6 (Laptop Unlock Feasibility):**
   - **Correction:** The feasibility report cited kernel-driver signing requirements as a barrier to user-mode Credential Providers (which are COM DLLs). The security conclusion remains correct (storing passwords or injecting credentials into Winlogon violates Windows security models), but technical reasoning must accurately cite UIPI, Session 0 isolation, and credential provider architecture without unsupported kernel-driver claims.
3. **Phase 8 (Verification & Release Lifecycle):**
   - **Correction:** Phase 8 cannot be marked "Complete" while real-device connectivity testing is failing and signing-key rotation is pending.

---

## 6. Complete 31-File Audit Matrix

The following table provides the line-by-line audit verdict for all 31 files touched between `v1.3.10` and `v1.3.15`:

| File | Subsystem | Findings / Verdict | Required Action |
| :--- | :--- | :--- | :--- |
| [`app/src/main/java/com/btmouse/app/MainActivity.kt`](file:///c:/Users/yamin/Documents/Projects/BT%20Mouse/app/src/main/java/com/btmouse/app/MainActivity.kt) | App Lifecycle | **CRITICAL BUG (Line 44):** Denying notifications or BLE advertising prevents `coordinator.initialize()` from ever running. | Remove `allGranted` requirement; initialize coordinator unconditionally in `onCreate()`. |
| [`pc_server/app_gui.py`](file:///c:/Users/yamin/Documents/Projects/BT%20Mouse/pc_server/app_gui.py) | PC Companion GUI | **CRITICAL BUG (Lines 2142, 2365):** Wi-Fi and WAN QR codes omit the pairing PIN. | Include `"pin": self.auth_token` in QR JSON payloads. |
| [`pc_server/server.py`](file:///c:/Users/yamin/Documents/Projects/BT%20Mouse/pc_server/server.py) | Server Core | **CRITICAL BUG (Line 4161):** `recvfrom(2048)` causes `WSAEMSGSIZE` error on large packets. **SECURITY (Line 2147):** Camera HTTP server binds to `0.0.0.0`. | Change buffer to `65507`. Bind HTTP server to `127.0.0.1`. Release virtualcam on restart. |
| [`app/src/main/java/com/btmouse/app/connection/WifiClientManager.kt`](file:///c:/Users/yamin/Documents/Projects/BT%20Mouse/app/src/main/java/com/btmouse/app/connection/WifiClientManager.kt) | Wi-Fi Transport | **DEFECT (Line 202):** Strict address/port equality drops valid `connect_ack` from multi-homed PCs. | Compare `hostAddress` and correlation state. |
| [`app/src/main/java/com/btmouse/app/connection/ConnectionCoordinator.kt`](file:///c:/Users/yamin/Documents/Projects/BT%20Mouse/app/src/main/java/com/btmouse/app/connection/ConnectionCoordinator.kt) | Transport Coordinator | **DEFECT (Line 277):** `setMode()` forcibly disconnects sockets during tab navigation. **DEFECT (Line 73):** Static failover order ignores RTT and selects no-op HID. | Don't disconnect on tab browse. Route commands to protocol transports; select best latency path. |
| [`app/src/main/java/com/btmouse/app/connection/BluetoothRfcommManager.kt`](file:///c:/Users/yamin/Documents/Projects/BT%20Mouse/app/src/main/java/com/btmouse/app/connection/BluetoothRfcommManager.kt) | Bluetooth Transport | **PASS (Dependent):** Implementation is sound, but starved because `MainActivity` failed to call `refreshPairedDevices()`. | Will function once `MainActivity` initialization is fixed. |
| [`app/src/main/java/com/btmouse/app/connection/RemoteWanManager.kt`](file:///c:/Users/yamin/Documents/Projects/BT%20Mouse/app/src/main/java/com/btmouse/app/connection/RemoteWanManager.kt) | Remote WAN Transport | **PASS (Dependent):** WebSocket transport is sound; starved by QR code PIN omission. | Will connect once QR code embeds PIN. |
| [`app/src/main/java/com/btmouse/app/connection/PhoneCameraStreamer.kt`](file:///c:/Users/yamin/Documents/Projects/BT%20Mouse/app/src/main/java/com/btmouse/app/connection/PhoneCameraStreamer.kt) | Camera Capture | **PASS:** Camera2 buffer acquisition cleanly enclosed in `try ... finally { image.close() }`. | None. |
| [`app/src/main/java/com/btmouse/app/connection/SnapshotGallery.kt`](file:///c:/Users/yamin/Documents/Projects/BT%20Mouse/app/src/main/java/com/btmouse/app/connection/SnapshotGallery.kt) | Media Storage | **PASS:** Snapshot storage and deletion work correctly. | None. |
| [`app/src/main/java/com/btmouse/app/connection/RecordingTransferClient.kt`](file:///c:/Users/yamin/Documents/Projects/BT%20Mouse/app/src/main/java/com/btmouse/app/connection/RecordingTransferClient.kt) | Media Transfer | **PASS:** Chunked transfer and verification work properly. | None. |
| [`app/src/main/java/com/btmouse/app/connection/InputConnection.kt`](file:///c:/Users/yamin/Documents/Projects/BT%20Mouse/app/src/main/java/com/btmouse/app/connection/InputConnection.kt) | Interface | **PASS:** Clean interface contracts. | None. |
| [`app/src/main/java/com/btmouse/app/service/CompanionSessionService.kt`](file:///c:/Users/yamin/Documents/Projects/BT%20Mouse/app/src/main/java/com/btmouse/app/service/CompanionSessionService.kt) | Foreground Service | **DEFECT (Line 41):** Not decoupled from `MainActivity` destruction. | Retain session coordinator in service/application scope. |
| [`app/src/main/AndroidManifest.xml`](file:///c:/Users/yamin/Documents/Projects/BT%20Mouse/app/src/main/AndroidManifest.xml) | Manifest | **SECURITY (Line 51):** `allowBackup="true"` and cleartext traffic. | Set `allowBackup="false"`. Add network security config. |
| [`app/build.gradle.kts`](file:///c:/Users/yamin/Documents/Projects/BT%20Mouse/app/build.gradle.kts) | Build Config | **SECURITY (Line 26):** Public keystore in Git history. | Remove keystore from Git; migrate to CI secrets. |
| [`app/src/main/java/com/btmouse/app/data/SettingsRepository.kt`](file:///c:/Users/yamin/Documents/Projects/BT%20Mouse/app/src/main/java/com/btmouse/app/data/SettingsRepository.kt) | Storage | **DEFECT:** Plaintext PIN and WAN token storage. | Exclude from backup; consider EncryptedSharedPreferences. |
| [`app/src/main/java/com/btmouse/app/ui/MainScreen.kt`](file:///c:/Users/yamin/Documents/Projects/BT%20Mouse/app/src/main/java/com/btmouse/app/ui/MainScreen.kt) | UI Host | **PASS:** Clean Compose navigation and state collection. | None. |
| [`app/src/main/java/com/btmouse/app/ui/screens/ConnectionScreen.kt`](file:///c:/Users/yamin/Documents/Projects/BT%20Mouse/app/src/main/java/com/btmouse/app/ui/screens/ConnectionScreen.kt) | Connection UI | **DEFECT (Line 254):** Tab clicks trigger mode disconnects. | Allow tab viewing without severing active sockets. |
| [`app/src/main/java/com/btmouse/app/ui/screens/KeyboardScreen.kt`](file:///c:/Users/yamin/Documents/Projects/BT%20Mouse/app/src/main/java/com/btmouse/app/ui/screens/KeyboardScreen.kt) | Input UI | **PASS:** Compose state collection fixed; viewfinder runs on background thread. | None. |
| [`app/src/main/java/com/btmouse/app/ui/screens/MediaRemoteScreen.kt`](file:///c:/Users/yamin/Documents/Projects/BT%20Mouse/app/src/main/java/com/btmouse/app/ui/screens/MediaRemoteScreen.kt) | Media UI | **PASS:** SMTC media controls and artwork render cleanly. | None. |
| [`app/src/main/java/com/btmouse/app/ui/screens/SettingsScreen.kt`](file:///c:/Users/yamin/Documents/Projects/BT%20Mouse/app/src/main/java/com/btmouse/app/ui/screens/SettingsScreen.kt) | Settings UI | **PASS:** Settings toggles properly update repository. | None. |
| [`app/src/main/java/com/btmouse/app/ui/screens/UtilitiesScreen.kt`](file:///c:/Users/yamin/Documents/Projects/BT%20Mouse/app/src/main/java/com/btmouse/app/ui/screens/UtilitiesScreen.kt) | Utilities UI | **PASS:** Quick apps, process manager, and power actions integrated. | None. |
| [`app/src/main/res/drawable/ic_stat_companion.xml`](file:///c:/Users/yamin/Documents/Projects/BT%20Mouse/app/src/main/res/drawable/ic_stat_companion.xml) | Resource | **PASS:** Vector drawable renders properly in notifications. | None. |
| [`pc_server/test_server.py`](file:///c:/Users/yamin/Documents/Projects/BT%20Mouse/pc_server/test_server.py) | Tests | **PASS (32 tests):** Cross-platform test mocks pass cleanly. | Expand tests to assert QR PIN inclusion and buffer size. |
| [`docs/laptop_unlock_feasibility.md`](file:///c:/Users/yamin/Documents/Projects/BT%20Mouse/docs/laptop_unlock_feasibility.md) | Docs | **NEEDS CORRECTION:** Refine Win11 CDF wording and credential provider architecture notes. | Correct technical justification. |
| [`docs/native_companion_inventory_and_ui_sharpness.md`](file:///c:/Users/yamin/Documents/Projects/BT%20Mouse/docs/native_companion_inventory_and_ui_sharpness.md) | Docs | **PASS:** Thorough specification for C# WinUI 3 native companion. | Retain as blueprint for native app migration. |
| [`IMPLEMENTATION_PLAN.md`](file:///c:/Users/yamin/Documents/Projects/BT%20Mouse/IMPLEMENTATION_PLAN.md) | Project Plan | **NEEDS CORRECTION:** Reconcile Phase 6 & 7 completion claims. | Update to reflect pending Native WinUI 3 work. |
| [`release-notes/v1.3.11.md`](file:///c:/Users/yamin/Documents/Projects/BT%20Mouse/release-notes/v1.3.11.md) | Notes | **PASS:** Accurately reflects Phase 1 & 2 deliverables. | None. |
| [`release-notes/v1.3.12.md`](file:///c:/Users/yamin/Documents/Projects/BT%20Mouse/release-notes/v1.3.12.md) | Notes | **PASS:** Accurately reflects Phase 3 deliverables. | None. |
| [`release-notes/v1.3.13.md`](file:///c:/Users/yamin/Documents/Projects/BT%20Mouse/release-notes/v1.3.13.md) | Notes | **PASS:** Accurately reflects Phase 4 deliverables. | None. |
| [`release-notes/v1.3.14.md`](file:///c:/Users/yamin/Documents/Projects/BT%20Mouse/release-notes/v1.3.14.md) | Notes | **PASS:** Accurately reflects Phase 5 deliverables. | None. |
| [`release-notes/v1.3.15.md`](file:///c:/Users/yamin/Documents/Projects/BT%20Mouse/release-notes/v1.3.15.md) | Notes | **PASS:** Accurately reflects High-DPI and Tkinter density scaling. | Update roadmap references. |

---

## 7. Prioritized Remediation Roadmap

The following prioritized roadmap outlines the exact sequence to restore full functionality and harden security:

### Step 1: Immediate Connection Fixes (P0)
1. **Unblock Coordinator Initialization in `MainActivity.kt`:**
   - Always call `connectionCoordinator.initialize()` in `onCreate()`.
   - Never gate coordinator startup on optional notification or Bluetooth advertise permissions.
2. **Embed Pairing PIN in QR Codes (`app_gui.py`):**
   - Include `"pin": self.auth_token` and `"token": self.auth_token` in `generate_qr()` and `generate_wan_qr()`.
3. **Expand Server UDP Buffer (`server.py`):**
   - Replace `recvfrom(2048)` with `recvfrom(65507)` to handle all datagrams up to max IPv4 bounds.
4. **Resilient Handshake Resolution (`WifiClientManager.kt`):**
   - Compare `pending.address.hostAddress == sourceAddress.hostAddress` rather than object reference identity.
5. **Decouple UI Tabs from Active Sockets (`ConnectionScreen.kt` & `ConnectionCoordinator.kt`):**
   - Do not disconnect working sockets when the user simply clicks between tabs.

### Step 2: Critical Security Hardening (P0)
1. **Secure Phone Camera HTTP Server (`server.py`):**
   - Bind to `127.0.0.1` instead of `0.0.0.0`.
   - Require pairing PIN for any non-localhost access.
2. **Remove Exposed Signing Keystore:**
   - Remove `app/release.keystore` from Git tracking.
   - Configure Gradle to require environment variables for release signing.
3. **Disable Android Auto-Backup for Secrets:**
   - Set `android:allowBackup="false"` in `AndroidManifest.xml` to prevent plaintext credential exposure in cloud backups.

### Step 3: Architecture & Documentation Alignment (P1)
1. **Update `IMPLEMENTATION_PLAN.md`:**
   - Mark Phase 7 as *In Progress (Inventory & Tkinter DPI Complete; WinUI 3 Implementation Pending)*.
   - Accurately describe Phase 6 research citations.
2. **VirtualCam Lifecycle Cleanup (`server.py`):**
   - Ensure `self._release_virtual_cam()` is called before creating a new instance on resolution/lens changes.

---

*End of Deep Code Audit & Remediation Report.*
