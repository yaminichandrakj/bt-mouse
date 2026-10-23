# PC Companion Application & Server

This companion app lets your Android phone or tablet control a PC over local Wi-Fi, Bluetooth RFCOMM, or an authenticated WebSocket endpoint. Wi-Fi input uses low-latency UDP and local discovery. The server also provides supported media, app, process, stats, camera, and audio controls.

> **Note:** If you are using **Bluetooth HID Mode**, you **DO NOT NEED THIS SERVER AT ALL**. The phone acts as a real hardware Bluetooth mouse & keyboard directly recognized by Windows/Mac/Linux out of the box!

---

## Quick Start

### 1. Install Dependencies
Run the following in PowerShell or terminal:
```bash
pip install -r requirements.txt
```

### 2. Launch Companion App
Simply double-click `run_pc_app.bat` or run:
```bash
python app_gui.py
```
*(Or for headless / terminal-only mode, run `python server.py`)*

### 3. Connect from the Android App
1. Make sure your PC and phone/tablet are connected to the same Wi-Fi network (or phone hotspot).
2. Open the **BT Mouse** app on your phone.
3. Switch to the **Connect** tab -> select **Wi-Fi Network**.
4. Enter the six-digit pairing PIN shown by the companion, then scan its Wi-Fi QR code or tap **Auto-Discover** / enter the IP manually.
5. Wait for the PIN-validated connection acknowledgment before sending input.

If the QR details load but the app reports that the PC did not respond, allow inbound **UDP 53530** (input) and **UDP 53535** (discovery) through Windows Firewall. Both devices must be on the same reachable LAN; guest Wi-Fi and client isolation can block device-to-device traffic.

---

## Features
- **Instant QR Code Pairing**: No IP typing needed; point your phone at the PC screen.
- **Hardware-Accelerated Native Windows Input**: Uses direct `user32.mouse_event` for butter-smooth pointer movement and scrolling.
- **Writing Pad**: Phone ink preview with mouse input to the PC. Windows absolute positioning is mouse emulation; pressure-sensitive digitizer input and colored PC strokes are not supported.
- **Camera**: Wi-Fi snapshots are size-limited. Live camera streaming uses RFCOMM or WebSocket because UDP video datagrams are unreliable.
- **Pairing**: A six-digit PIN appears in the PC companion/terminal and is saved on that PC so it stays the same after restart. It is not placed in discovery or QR payloads. Re-enter the displayed PIN on the phone if upgrading from an older companion version.
- **Remote access**: Internet connections require a `wss://` TLS endpoint, such as a securely configured tunnel or reverse proxy. Never expose the built-in plain `ws://` listener directly to the Internet.
- **WAN endpoint**: The companion's QR contains its local address for LAN use. For cellular/Internet use, enter the public `wss://` URL of your TLS tunnel or reverse proxy in the phone app; the companion does not create a public endpoint or relay by itself.
- **Live Keystroke Streaming**: Characters appear on your PC in real-time as you type on your phone.
- **In-App Updates**: One-tap GitHub release update checker and automatic APK installer.
- **Bluetooth Assistant**: One-click shortcuts to reset and pair Windows Bluetooth if devices act up.
