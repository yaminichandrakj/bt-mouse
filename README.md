# BT Mouse & Keyboard - Universal Remote for Android & PC

Transform any Android phone into a high-precision, low-latency **Wireless Mouse, Trackpad, Full PC Keyboard, Numpad, Media Remote, and Gyroscopic Air Mouse** for any PC (Windows, macOS, Linux, ChromeOS, and Smart TVs).

![Platform](https://img.shields.io/badge/Platform-Android_9.0+_(API_28+)-green.svg)
![Kotlin](https://img.shields.io/badge/Language-Kotlin_2.0-blue.svg)
![UI](https://img.shields.io/badge/UI-Jetpack_Compose_Material_3-purple.svg)
![Dual-Mode](https://img.shields.io/badge/Modes-Bluetooth_HID_|_Wi--Fi-orange.svg)

---

## 🚀 Key Highlights & Architecture

### 1. Three Connection Paths
* **Mode A: Native Bluetooth HID (Zero PC Software Needed!)**
  * Emulates a genuine, 100% standard Bluetooth Composite HID peripheral (Keyboard + Mouse + Consumer Controls) using Android's native `BluetoothHidDevice` API.
  * Pairs directly with Windows, Mac, or Linux through standard OS Bluetooth settings. **No companion app, server, or driver required on the PC!**
* **Mode B: Bluetooth RFCOMM or Wi-Fi Companion Mode**
  * Uses the PC companion for authenticated input, media state, installed apps, process controls, PC stats, and supported camera/audio features.
  * Wi-Fi uses UDP for low-latency input and automatic local discovery; enter the pairing PIN shown by the companion.
  * RFCOMM uses an OS-paired Bluetooth serial session. Companion-only features are available only while connected to that server.
* **Mode C: Remote WAN**
  * Connects to an authenticated WebSocket endpoint. Internet connections must use `wss://` through a TLS-enabled tunnel or reverse proxy.
  * The companion's built-in listener is plain `ws://` for local use only. Do not expose it directly to the Internet.

---

## ✨ Full Feature Set

### 🖱️ 1. High-Precision Trackpad
* **Multi-Touch Gestures**:
  * **1-Finger Drag**: Relative cursor tracking with smooth acceleration and adjustable sensitivity ($0.4\times$ to $3.0\times$).
  * **1-Finger Tap**: Left Click.
  * **2-Finger Tap**: Right Click (Context Menu).
  * **3-Finger Tap**: Middle Click (Open link in new tab, close tab).
  * **2-Finger Drag**: Fluid vertical and horizontal scrolling with inertia.
  * **Double-Tap & Hold**: Drag selection / text highlight / window moving.
* **Dedicated Tactile Buttons**: Physical-style Left, Middle, and Right mouse buttons with haptic vibration feedback.
* **Virtual Scroll Strip**: Tactile vertical wheel bar on the right edge with clock-tick haptics.

### ⌨️ 2. Full PC Keyboard & Shortcuts
* **Live Text Streaming**: Type using your phone's native keyboard, autocorrect, or voice dictation and send complete strings directly to your PC.
* **Complete Function Key Row**: `Esc`, `F1` through `F12`, and `Delete`.
* **PC Navigation Cluster**: `Tab`, `Backspace`, `Enter`, `Space`, `Home`, `End`, `Page Up`, `Page Down`, and arrow keys (`←`, `↑`, `↓`, `→`).
* **Latching Modifiers**: Toggle `Ctrl`, `Alt`, `Shift`, and `Win` (Windows key / Command) individually or in combination.
* **1-Tap PC Shortcuts**:
  * `Ctrl+C` (Copy), `Ctrl+V` (Paste), `Ctrl+Z` (Undo), `Ctrl+A` (Select All)
  * `Alt+Tab` (Task Switcher)
  * `Win+D` (Show / Hide Desktop)
  * `Ctrl+Shift+Esc` (Windows Task Manager)

### 🔢 3. Dedicated 10-Key Numpad
* Full numeric keypad layout with `NumLock`, `/`, `*`, `-`, `+`, `Enter`, digits `0-9`, and decimal point `.`.
* Ideal for Excel, accounting software, calculator apps, and rapid data entry.

### 🎬 4. Media Remote & Presentation Clicker
* **Media Player Controls**:
  * Large Play / Pause button
  * Next Track (`Scan Next`) & Previous Track (`Scan Previous`)
  * Volume Up, Volume Down, and Instant Mute
* **Presentation Slide Controller (PowerPoint / Keynote / Google Slides / PDF)**:
  * Start Presentation (`F5`) & Exit Presentation (`Esc`)
  * Large Next Slide (`Page Down`) & Previous Slide (`Page Up`) buttons
  * Black Screen (`B`) & White Screen (`W`) blanking hotkeys

### 🎮 5. Gyroscopic Air Mouse
* Wave your phone in the air to move the mouse pointer on your PC screen!
* Utilizes phone's hardware Gyroscope (`Sensor.TYPE_GYROSCOPE`) with noise-canceling deadband filters to eliminate hand tremors.
* Instant on-screen toggle button.

### 🖊️ 6. Writing Pad
* Shows ink preview on the phone and sends mouse input to the PC.
* Windows absolute positioning is mouse emulation. Pressure-sensitive digitizer input is not supported; use a real digitizer for pressure-aware drawing.
* Ink color changes the phone preview. The PC receives pointer actions, not colored stroke data.

### 🖥️ 7. Companion Controls
* Search and launch discovered Start Menu apps, inspect running processes and PC stats, and use supported media, power, camera, and microphone controls.
* Camera snapshots are size-limited over Wi-Fi. Live camera streaming is available over RFCOMM/WebSocket, not UDP Wi-Fi.
* Workstation unlock automation is unavailable.

---

## 🛠️ How to Build & Install the App

You have **3 convenient ways** to build the `.apk`:

### Option A: Automatic Build via GitHub Actions (Zero Local Setup!)
1. Push this repository to your GitHub account:
   ```bash
   git init
   git add .
   git commit -m "Initial commit of BT Mouse app"
   git remote add origin https://github.com/<your-username>/bt-mouse.git
   git push -u origin main
   ```
2. In your GitHub repository, click the **Actions** tab.
3. The **"Build Android APK"** workflow will automatically run and compile the app using Ubuntu and JDK 17.
4. Once completed, click the workflow run and download the compiled **`BT-Mouse-Debug-APK`** artifact directly onto your phone!

### Option B: Open in Android Studio
1. Open **Android Studio** (Hedgehog, Iguana, Koala, or newer).
2. Select **File -> Open...** and select this directory (`BT Mouse`).
3. Allow Gradle to sync.
4. Plug in your Android phone with **USB Debugging** enabled, or select an emulator.
5. Click the green **Run (▶)** button (or press `Shift + F10`).

### Option C: Build via Command Line
If JDK 17+ and the Android SDK are installed in your environment:
```bash
# Windows
.\gradlew.bat assembleDebug

# macOS / Linux
chmod +x gradlew
./gradlew assembleDebug
```
The output `.apk` file will be generated at:
`app/build/outputs/apk/debug/app-debug.apk`

---

## 📲 How to Connect to Your PC

### Mode 1: Bluetooth HID Connection (Recommended)
1. Install and open the **BT Mouse** app on your Android phone.
2. Grant Bluetooth permissions when prompted.
3. On your PC:
   * Open **Windows Settings -> Bluetooth & devices -> Add device -> Bluetooth** (or System Preferences on macOS).
4. Put your phone in discoverable mode or tap **"Open Bluetooth Settings"** in the app's **Connect** tab.
5. Pair with your phone from the PC.
6. The PC will instantly detect your phone as a **Bluetooth Mouse and Keyboard peripheral**.
7. Start moving your finger on the trackpad or typing on the keyboard!

### Mode 2: Wi-Fi Network Mode (With PC Server)
1. Open PowerShell / Terminal in the `pc_server` folder on your PC:
   ```bash
   cd pc_server
   pip install -r requirements.txt
   python server.py
   ```
2. The terminal displays the local IP and six-digit pairing PIN. The PC GUI shows the same PIN.
3. In the Android App:
   * Go to the **Connect** tab -> Select **Wi-Fi** and enter the pairing PIN.
   * Tap **Auto-Discover** or type the IP manually, then connect. The app waits for the server's PIN-validated acknowledgment.
4. Enjoy instant wireless remote control over your local network!

### Mode 3: Remote WAN
The companion's WebSocket listener (`53531`) is plain `ws://`. Never port-forward it on your router; reach it through one of these instead.

**Option A: Tailscale (private, simplest).** Install Tailscale on the PC and the phone and sign in to the same account. In the app's **Remote WAN** tab enter `ws://<pc-name>.<tailnet>.ts.net:53531` (or `ws://100.x.y.z:53531`) and the pairing PIN. The app allows `ws://` here because the tailnet is already WireGuard-encrypted.

**Option B: Cloudflare Tunnel (public `wss://` URL).**
1. Install `cloudflared` on the PC (`winget install Cloudflare.cloudflared`).
2. Quick test: run `cloudflared tunnel --url http://localhost:53531`. It prints an `https://<random>.trycloudflare.com` address.
3. In the app enter `wss://<random>.trycloudflare.com` (no port) and the pairing PIN.
4. Quick-tunnel addresses change on every run. For a fixed address, create a named tunnel on your own domain (`cloudflared tunnel login`, `cloudflared tunnel create btmouse`, route a hostname such as `mouse.example.com` to `http://localhost:53531`, then `cloudflared service install`).

The six-digit PIN is still required on every connection, so use a PIN nobody else knows.

---

## 📁 Repository Structure

```
BT Mouse/
├── .github/
│   └── workflows/
│       └── build-apk.yml               # GitHub Actions CI for automated APK compilation
├── app/
│   ├── build.gradle.kts                # Compose, Material3, Coroutines configuration
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml         # Bluetooth HID, Wi-Fi, Haptics, Sensor permissions
│       ├── java/com/btmouse/app/
│       │   ├── MainActivity.kt         # Edge-to-edge Activity with permission management
│       │   ├── BtMouseApplication.kt   # Application class
│       │   ├── data/
│       │   │   ├── HidConstants.kt     # HID composite descriptors & USB scancodes
│       │   │   └── SettingsRepository.kt# Preferences (speed, inverted scroll, haptics)
│       │   ├── connection/
│       │   │   ├── InputConnection.kt  # Unified input interface
│       │   │   ├── BluetoothHidManager.kt # Native Android BluetoothHidDevice manager
│       │   │   ├── WifiClientManager.kt   # UDP client & auto-discovery beacon
│       │   │   └── ConnectionCoordinator.kt# Mode switcher & state coordinator
│       │   ├── sensors/
│       │   │   └── AirMouseManager.kt  # Gyroscope motion tracker with deadband filter
│       │   └── ui/
│       │       ├── MainScreen.kt       # Bottom navigation container
│       │       ├── theme/              # Sleek dark theme tokens & styling
│       │       ├── components/
│       │       │   ├── TouchpadSurface.kt   # Gesture recognition (pan, tap, scroll, drag)
│       │       │   ├── VirtualScrollWheel.kt# Tactile vertical scroll wheel
│       │       │   ├── MouseButtonsBar.kt   # Left, Middle, Right click buttons
│       │       │   └── KeyButton.kt         # Tactile PC key buttons
│       │       └── screens/
│       │           ├── TouchpadScreen.kt    # Main touchpad view
│       │           ├── KeyboardScreen.kt    # PC keyboard & shortcuts
│       │           ├── NumpadScreen.kt      # 10-key numeric keypad
│       │           ├── MediaRemoteScreen.kt # Media controls & slide clicker
│       │           ├── ConnectionScreen.kt  # Pairing & server discovery
│       │           └── SettingsScreen.kt    # Fine-tuning & system info
│       └── res/                        # Vector icons, colors, themes, adaptive launcher icons
├── pc_server/
│   ├── server.py                       # UDP, Bluetooth RFCOMM, and WebSocket companion server
│   ├── requirements.txt
│   └── README.md
├── build.gradle.kts                    # Root build configuration
├── settings.gradle.kts                 # Root project settings
├── gradle.properties
└── README.md
```

---

## 🔒 Security & Privacy
* **Pairing PIN**: Wi-Fi commands require the PIN; the companion does not include it in discovery responses or QR codes. Bluetooth RFCOMM relies on OS Bluetooth pairing; WebSocket requires an authenticated handshake.
* **Remote connections**: Internet access requires a `wss://` endpoint. The companion's built-in WebSocket listener is unencrypted and intended for local use behind a properly configured TLS proxy for remote access.
* **Bluetooth HID**: Direct HID uses the operating system's Bluetooth pairing and link security.
* **Privacy**: Local HID and companion modes do not require a cloud service. Remote WAN mode sends control traffic over the Internet to the endpoint you configure.
* **Unlock**: The app does not store or inject workstation passwords and cannot automate secure-desktop unlock.
