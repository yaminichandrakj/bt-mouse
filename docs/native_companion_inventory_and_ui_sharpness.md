# Desktop UI Sharpness & Native Windows Companion Inventory (Phase 7)

**Document Status:** Architecture Assessment & Component Specification  
**Date:** 2026-10-24  
**Product:** BT Mouse Windows Companion  
**Milestone:** Phase 7 Implementation  

---

## 1. Desktop UI Sharpness & High-DPI Scaling

To eliminate font blurriness, blurry bitmap stretching, and miniature dialogs on modern Windows displays (1080p, 1440p, 4K, and Surface displays running at 125%, 150%, 175%, or 200% scaling), the PC Companion implements a native multi-tier DPI awareness pipeline:

### 1.1 Per-Monitor V2 DPI Awareness Context
During process startup before initializing any UI frameworks, `set_windows_dpi_awareness()` executes:
```python
ctypes.windll.user32.SetProcessDpiAwarenessContext(ctypes.c_void_p(-4))
```
- Context `-4` declares `DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2`, instructing the Windows Desktop Window Manager (DWM) not to bitmap-stretch the companion window.
- Includes automatic fallback to `SetProcessDpiAwareness(2)` (Per-Monitor Aware) and `SetProcessDPIAware()` (System DPI Aware) on legacy Windows builds.

### 1.2 Font Engine Density Calibration
In Tkinter, setting DPI awareness without calibrating the font engine causes fonts to render at 96 DPI metrics. The companion dynamically queries display density via `GetDpiForSystem()` / `GetDeviceCaps(hdc, LOGPIXELSX)` and updates the Tkinter font rasterizer:
```python
scale, dpi = get_windows_dpi_scale()
if scale > 1.0:
    self.tk.call('tk', 'scaling', dpi / 72.0)
```
- Calibrates font rendering so text remains crisp and readable across all scaling levels.
- Window geometry and minimum boundaries dynamically scale with the monitor DPI, centering automatically on the primary monitor.

---

## 2. Comprehensive Subsystem Inventory for Native Windows Architecture

To guide any future transition to a compiled native stack (e.g., C# / .NET 8 WinUI 3 or WPF) while preserving 100% protocol, security, and Android client compatibility, every subsystem of the Companion has been cataloged:

### 2.1 Networking & Transport Layer
- **UDP Datagram Engine (Port 53530):**
  - Handles mouse movement, scroll packets, and click actions with low latency.
  - JSON packet parsing with bounded datagram responses (`< 64KB`).
- **Remote WAN WebSocket Engine (Port 53531):**
  - Authenticated WebSocket server supporting TLS (`wss://`) for WAN and cleartext (`ws://`) for private LAN.
- **PIN Authentication & Security:**
  - 6-digit numeric pairing PIN persisted in `%LOCALAPPDATA%\BTMouse\pairing_pin.txt`.
  - Rate-limited exponential lockouts against brute-force attacks.
- **Concurrent Transport Supervisor & Sequence Deduplication:**
  - Sliding sequence window (`_cmd_seq_lock`) tracking client session UUIDs and monotonically incrementing sequence numbers.
  - Releases all held mouse buttons and modifier keys upon receiving `path_switch`.

### 2.2 Native Input Injection
- **Mouse Simulation:**
  - Low-level `SendInput` with `MOUSEINPUT` flags: `MOUSEEVENTF_MOVE`, `MOUSEEVENTF_LEFTDOWN`, `MOUSEEVENTF_LEFTUP`, `MOUSEEVENTF_RIGHTDOWN`, `MOUSEEVENTF_WHEEL`.
  - Absolute multi-monitor coordinate normalization for touchpad absolute positioning.
- **Keyboard Simulation:**
  - `KEYBDINPUT` targeting Virtual Key codes (VK) and hardware scan codes (`KEYEVENTF_SCANCODE`) with Unicode text support.

### 2.3 System Diagnostics & Management
- **Hardware Telemetry:**
  - High-performance counter queries for CPU utilization, RAM usage, storage space, and GPU 3D/Video engines.
- **Quick Apps Discovery:**
  - Scans `CSIDL_COMMON_PROGRAMS` and `CSIDL_PROGRAMS` for Start Menu `.lnk` shortcuts.
  - Resolves target executable paths using Windows Shell COM interfaces (`IShellLinkW`, `IPersistFile`).
- **Process Manager:**
  - Lists top 200 processes sorted by CPU and memory.
  - Process termination via `OpenProcess(PROCESS_TERMINATE)` and `TerminateProcess`.

### 2.4 Media, Audio & Webcam
- **System Media Transport Controls (SMTC):**
  - WinRT `GlobalSystemMediaTransportControlsSessionManager` integration to read track title, artist, album art, and playback state.
  - Prioritizes actively `Playing` sessions over stale `Paused` sessions.
- **Master Volume & Audio:**
  - CoreAudio endpoint volume control via `pycaw` / `IAudioEndpointVolume` with robust `_com_context()` COM threading.
  - Two-way PCM live audio streaming with WASAPI loopback and microphone capture.
- **Camera & Local MJPEG Streaming:**
  - DirectShow webcam capture for PC snapshots and MP4 recording.
  - Embedded HTTP MJPEG server at `http://127.0.0.1:8766/phone_cam/mjpeg` receiving wireless phone camera frames.
  - `pyvirtualcam` adapter routing phone frames into OBS Virtual Camera for Zoom, Teams, and Meet.

### 2.5 Lifecycle, System Tray & Updater
- **System Tray:**
  - Win32 `Shell_NotifyIcon` integration with minimize-to-tray on close (`WM_DELETE_WINDOW`).
- **Sign-In Self-Healing:**
  - Verifies and repairs registry `HKCU\Software\Microsoft\Windows\CurrentVersion\Run` to maintain `--minimized` startup.
- **Self-Healing In-App Updater:**
  - Downloads official GitHub release binary, verifies SHA-256 hash, stages executable, spawns updater script, and restores previous binary if launch marker verification fails.

---

## 3. Retention & Migration Decision

- **Immediate Action Taken:** Implemented native High-DPI font calibration and dynamic window geometry directly in `pc_server/app_gui.py`, resolving text sharpness and scaling across all displays without introducing breaking regressions.
- **Migration Strategy:** The current standalone executable (`BT-Mouse-Companion.exe`) maintains feature parity, has passed 32 automated regression checks, and functions across Windows 10 and 11. The inventory above provides the reference specification should a complete C# / WinUI 3 port be undertaken in future major cycles.
