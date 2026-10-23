# PC Companion Application & Server

This companion app allows your Android phone or tablet to control this PC over your local Wi-Fi, hotspot, or USB tethering with near-zero latency (~1ms UDP packets), featuring interactive QR code pairing and graphics tablet / stylus writing pad support.

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
4. Tap **"Scan QR Code"** and scan the QR code displayed on the PC Companion App window!
   *(Or tap "Auto-Discover PC Server on Wi-Fi" / enter the IP manually)*
5. Done! You now have instant mouse, live typing, PC shortcut control, and graphics tablet writing pad support.

---

## Features
- **Instant QR Code Pairing**: No IP typing needed; point your phone at the PC screen.
- **Hardware-Accelerated Native Windows Input**: Uses direct `user32.mouse_event` for butter-smooth pointer movement and scrolling.
- **Tablet Writing Pad & Stylus Mode**: Turn any Android tablet or phone into a graphics slate with 1:1 screen mapping, pressure sensitivity, and palm rejection for MS Paint, OneNote, Whiteboard, and Photoshop.
- **Live Keystroke Streaming**: Characters appear on your PC in real-time as you type on your phone.
- **In-App Updates**: One-tap GitHub release update checker and automatic APK installer.
- **Bluetooth Assistant**: One-click shortcuts to reset and pair Windows Bluetooth if devices act up.
