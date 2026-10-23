# BT Mouse & Keyboard - Universal Remote for Android & PC

Transform any Android phone into a high-precision, low-latency **Wireless Mouse, Trackpad, Full PC Keyboard, Numpad, Media Remote, and Gyroscopic Air Mouse** for any PC (Windows, macOS, Linux, ChromeOS, and Smart TVs).

![Platform](https://img.shields.io/badge/Platform-Android_9.0+_(API_28+)-green.svg)
![Kotlin](https://img.shields.io/badge/Language-Kotlin_2.0-blue.svg)
![UI](https://img.shields.io/badge/UI-Jetpack_Compose_Material_3-purple.svg)
![Dual-Mode](https://img.shields.io/badge/Modes-Bluetooth_HID_|_Wi--Fi-orange.svg)

---

## 🚀 Key Highlights & Architecture

### 1. Dual-Mode Connection
* **Mode A: Native Bluetooth HID (Zero PC Software Needed!)**
  * Emulates a genuine, 100% standard Bluetooth Composite HID peripheral (Keyboard + Mouse + Consumer Controls) using Android's native `BluetoothHidDevice` API.
  * Pairs directly with Windows, Mac, or Linux through standard OS Bluetooth settings. **No companion app, server, or driver required on the PC!**
* **Mode B: Wi-Fi / Local Network Mode (Companion Fallback)**
  * For older PCs without Bluetooth or custom Android ROMs where Bluetooth HID is restricted.
  * Connects over local Wi-Fi, phone hotspot, or USB tethering with ~1ms ultra-low latency UDP packets.
  * Includes 1-tap **UDP Auto-Discovery beacon** (`BT_MOUSE_DISCOVER`).

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
2. The terminal will display your PC's local IP (e.g. `192.168.1.15`).
3. In the Android App:
   * Go to the **Connect** tab -> Select **Wi-Fi Network**.
   * Tap **"Auto-Discover PC Server on Wi-Fi"** (or type the IP manually and tap Connect).
4. Enjoy instant wireless remote control over your local network!

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
│   ├── server.py                       # Standalone Python UDP companion server
│   ├── requirements.txt
│   └── README.md
├── build.gradle.kts                    # Root build configuration
├── settings.gradle.kts                 # Root project settings
├── gradle.properties
└── README.md
```

---

## 🔒 Security & Privacy
* **Zero Cloud Dependence**: All communications remain 100% strictly local between your phone and your PC. No data ever leaves your local environment.
* **Standard Bluetooth HID**: In Bluetooth mode, communication is encrypted and authenticated through standard Bluetooth pairing protocols.
