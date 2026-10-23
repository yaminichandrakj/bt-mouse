# PC Companion Server (For Wi-Fi Network Mode)

This companion server allows your Android phone to control this PC over your local Wi-Fi, hotspot, or USB tethering network with near-zero latency (~1ms UDP packets).

> **Note:** If you are using **Bluetooth HID Mode**, you **DO NOT NEED THIS SERVER AT ALL**. The phone acts as a real hardware Bluetooth mouse & keyboard directly recognized by Windows/Mac/Linux out of the box!

---

## Quick Start

### 1. Install Dependencies
Run the following in PowerShell or terminal:
```bash
pip install -r requirements.txt
```

### 2. Run the Server
```bash
python server.py
```

### 3. Connect from the Android App
1. Make sure your PC and phone are connected to the same Wi-Fi router (or phone hotspot).
2. Open the **BT Mouse** app on your phone.
3. Switch to the **Connect** tab -> select **Wi-Fi Network**.
4. Tap **"Auto-Discover PC Server on Wi-Fi"** (or type the IP address printed in your terminal).
5. Done! You now have instant, buttery-smooth mouse and keyboard control.
