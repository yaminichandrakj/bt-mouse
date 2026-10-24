"""
BT Mouse & Keyboard - PC Companion Application
Modern Desktop GUI with System Tray minimization, background icon,
instant QR Code generation, and tablet writing pad support.
"""

import tkinter as tk
from tkinter import ttk, messagebox
import threading
import socket
import json
import base64
import os
import sys
import time
import re
import hashlib
import tempfile
import subprocess
import urllib.request
import urllib.error
from pathlib import Path

# Prevent crash when running as a windowed GUI application without a console attached
if sys.stdout is None:
    sys.stdout = open(os.devnull, "w")
if sys.stderr is None:
    sys.stderr = open(os.devnull, "w")

def get_asset_path(filename):
    if hasattr(sys, '_MEIPASS'):
        p = os.path.join(sys._MEIPASS, filename)
        if os.path.exists(p):
            return p
    local_p = os.path.join(os.path.dirname(__file__), filename)
    if os.path.exists(local_p):
        return local_p
    return filename

try:
    import qrcode
    from PIL import Image, ImageTk
    HAS_QR = True
except ImportError:
    HAS_QR = False

try:
    import pystray
    from pystray import MenuItem as item
    HAS_TRAY = True
except ImportError:
    HAS_TRAY = False

from server import (Server, ensure_interactive_desktop, IS_WINDOWS, load_or_create_pairing_pin,
                    save_pairing_pin, load_quick_apps, save_quick_apps,
                    load_media_preview_region, save_media_preview_region)

APP_VERSION = "1.3.9"
GITHUB_REPOSITORY = "yaminichandrakj/bt-mouse"
MAX_COMPANION_UPDATE_BYTES = 150 * 1024 * 1024
COMPANION_UPDATE_ERROR_LOG = Path(tempfile.gettempdir()) / "btmouse-update-error.log"


def set_windows_dpi_awareness():
    if not IS_WINDOWS:
        return
    try:
        import ctypes
        ctypes.windll.user32.SetProcessDpiAwarenessContext(ctypes.c_void_p(-4))
    except Exception:
        try:
            import ctypes
            ctypes.windll.shcore.SetProcessDpiAwareness(2)
        except Exception:
            pass

# Color Theme
BG_DARK = "#0E1118"
CARD_BG = "#181C26"
CARD_BORDER = "#283042"
ACCENT_CYAN = "#00D2FF"
ACCENT_INDIGO = "#6366F1"
ACCENT_GREEN = "#10B981"
ACCENT_RED = "#F43F5E"
TEXT_PRIMARY = "#F8FAFC"
TEXT_MUTED = "#94A3B8"
BUTTON_BG = "#222736"

class CompanionApp(tk.Tk):
    def __init__(self):
        super().__init__()
        ensure_interactive_desktop()

        self.title("BT Mouse & Keyboard - Companion")
        self.geometry("700x750")
        self.minsize(640, 680)
        self.configure(bg=BG_DARK)

        # Set window icon if available
        self.icon_path = get_asset_path("icon.ico")
        self.icon_png_path = get_asset_path("icon.png")
        if os.path.exists(self.icon_path):
            try:
                self.iconbitmap(self.icon_path)
            except Exception:
                pass

        # Server management
        self.server = None
        self.server_thread = None
        self.is_server_running = False

        self.current_port = 53530
        self.ws_port = 53531
        self.auth_token = load_or_create_pairing_pin()
        self._init_server()

        # Stats
        self.packet_count = 0
        self.last_client = "None"

        # Setup styling
        self._setup_styles()

        # Build UI
        self._build_header()
        self._build_notebook()
        self._build_statusbar()
        executable_path = Path(sys.executable).resolve()
        self.title(f"BT Mouse Companion v{APP_VERSION} — {executable_path}")
        self.after(500, self._show_update_recovery_notice)
        self.after(3000, self._refresh_recordings_loop)

        # System Tray setup
        self.tray_icon = None
        self._setup_system_tray()

        # Start server automatically
        self.start_server()

        # Handle window close: minimize to tray instead of quitting
        self.protocol("WM_DELETE_WINDOW", self.hide_to_tray)
        self._write_update_ready_marker()
        if "--minimized" in sys.argv:
            self.after(700, self.hide_to_tray if self.tray_icon else self.iconify)

    @staticmethod
    def _write_update_ready_marker():
        """Acknowledge that the replacement reached app initialization."""
        try:
            marker_arg = sys.argv.index("--btmouse-update-ready")
            marker_path = Path(sys.argv[marker_arg + 1])
            marker_path.write_text(APP_VERSION, encoding="ascii")
        except (ValueError, IndexError, OSError):
            pass

    @staticmethod
    def _startup_registry_value():
        """Return the current-user sign-in command, if one is registered."""
        if os.name != "nt":
            return None
        try:
            import winreg
            with winreg.OpenKey(
                winreg.HKEY_CURRENT_USER,
                r"Software\Microsoft\Windows\CurrentVersion\Run",
                0,
                winreg.KEY_QUERY_VALUE,
            ) as key:
                return winreg.QueryValueEx(key, "BT Mouse Companion")[0]
        except (OSError, ImportError):
            return None

    @staticmethod
    def _startup_command():
        if getattr(sys, "frozen", False):
            return subprocess.list2cmdline([str(Path(sys.executable).resolve()), "--minimized"])
        script = Path(__file__).resolve()
        pythonw = Path(sys.executable).with_name("pythonw.exe")
        runner = pythonw if pythonw.is_file() else Path(sys.executable)
        return subprocess.list2cmdline([str(runner), str(script), "--minimized"])

    def _set_startup_enabled(self, enabled):
        if os.name != "nt":
            return
        try:
            import winreg
            run_key = r"Software\Microsoft\Windows\CurrentVersion\Run"
            with winreg.CreateKeyEx(winreg.HKEY_CURRENT_USER, run_key, 0, winreg.KEY_SET_VALUE) as key:
                if enabled:
                    winreg.SetValueEx(key, "BT Mouse Companion", 0, winreg.REG_SZ, self._startup_command())
                else:
                    try:
                        winreg.DeleteValue(key, "BT Mouse Companion")
                    except FileNotFoundError:
                        pass
        except (OSError, ImportError) as exc:
            self.startup_var.set(not enabled)
            messagebox.showerror("Startup setting", f"Could not update the Windows sign-in setting.\n\n{exc}", parent=self)

    def _init_server(self):
        """Initializes a clean Server instance with bound callbacks"""
        self.server = Server(port=self.current_port, ws_port=self.ws_port, auth_token=self.auth_token)
        # Installed-app discovery walks the Start Menu and can be slow on some
        # machines. Keep it out of the updater's startup acknowledgement path.
        self.available_apps = []
        self.quick_apps = []
        self.adapters = self.server.get_all_ips()
        self.current_ip = self.server.get_local_ip()
        self.recording_settings_path = Path(self.server.audio_manager.recordings_dir).parent / "recording_settings.json"
        try:
            preferences = json.loads(self.recording_settings_path.read_text(encoding="utf-8"))
        except (OSError, ValueError, TypeError):
            preferences = {}
        def _preference_int(name, default, maximum):
            try:
                return min(maximum, max(0, int(preferences.get(name, default) or 0)))
            except (TypeError, ValueError):
                return default
        self.auto_record_video = bool(preferences.get("auto_record_video", True))
        self.auto_record_audio = bool(preferences.get("auto_record_audio", False))
        self.recording_retention_days = _preference_int("recording_retention_days", 0, 3650)
        self.recording_max_storage_mb = _preference_int("recording_max_storage_mb", 0, 500000)
        self.recording_min_free_mb = _preference_int("recording_min_free_mb", 512, 100000)
        self.server.recording_policy = {
            "retention_days": self.recording_retention_days,
            "max_storage_mb": self.recording_max_storage_mb,
            "min_free_mb": self.recording_min_free_mb,
        }
        self.server.camera_manager.recording_policy = dict(self.server.recording_policy)
        self.server.audio_manager.recording_policy = dict(self.server.recording_policy)
        self.server.live_audio_manager.recording_policy = dict(self.server.recording_policy)
        self.server.camera_manager.auto_record_enabled = self.auto_record_video
        self.server.auto_record_audio_with_video = self.auto_record_audio

        self.server.on_packet_callback = self._on_packet
        self.server.on_client_callback = self._on_client
        self.server.on_status_callback = self._on_status

    def _setup_styles(self):
        style = ttk.Style(self)
        style.theme_use("clam")

        style.configure("TNotebook", background=BG_DARK, borderwidth=0)
        style.configure("TNotebook.Tab", background=CARD_BG, foreground=TEXT_MUTED,
                        padding=[16, 8], font=("Segoe UI", 10, "bold"), borderwidth=0)
        style.map("TNotebook.Tab",
                  background=[("selected", ACCENT_INDIGO)],
                  foreground=[("selected", TEXT_PRIMARY)])

    def _setup_system_tray(self):
        if not HAS_TRAY:
            return

        # Load or generate tray icon image
        if os.path.exists(self.icon_png_path):
            tray_img = Image.open(self.icon_png_path)
        else:
            tray_img = Image.new("RGBA", (64, 64), color=(14, 17, 24))

        menu = (
            item("BT Mouse Companion", lambda: None, enabled=False),
            item(lambda text: f"Status: {'Running' if self.is_server_running else 'Stopped'}", lambda: None, enabled=False),
            item("Show Window", self.show_from_tray, default=True),
            item("Restart Server", self.restart_server),
            item("Start Server", self.start_server),
            item("Stop Server", self.stop_server),
            item("Copy Wi-Fi IP", self.copy_ip),
            item("Bluetooth Settings", self.open_windows_bt_settings),
            item("Exit Application", self.quit_application)
        )

        self.tray_icon = pystray.Icon("bt_mouse_companion", tray_img, "BT Mouse & Keyboard Companion", menu)
        threading.Thread(target=self.tray_icon.run, daemon=True).start()

    def hide_to_tray(self):
        """Hides the main window to the system tray"""
        self.withdraw()
        if self.tray_icon:
            try:
                self.tray_icon.notify("Running in background", "BT Mouse Companion is still active in your system tray.")
            except Exception:
                pass

    def show_from_tray(self, icon=None, item=None):
        """Restores the main window from system tray"""
        self.after(0, self._restore_window)

    def _restore_window(self):
        self.deiconify()
        self.lift()
        self.focus_force()

    def _build_header(self):
        header_frame = tk.Frame(self, bg=CARD_BG, height=80, padx=20, pady=14)
        header_frame.pack(fill="x", padx=16, pady=(16, 8))

        text_box = tk.Frame(header_frame, bg=CARD_BG)
        text_box.pack(side="left", fill="y")

        lbl_title = tk.Label(text_box, text="BT MOUSE & KEYBOARD", font=("Segoe UI", 15, "bold"),
                             bg=CARD_BG, fg=TEXT_PRIMARY)
        lbl_title.pack(anchor="w")

        lbl_sub = tk.Label(text_box, text="PC Companion Receiver Server", font=("Segoe UI", 9),
                           bg=CARD_BG, fg=TEXT_MUTED)
        lbl_sub.pack(anchor="w")

        right_box = tk.Frame(header_frame, bg=CARD_BG)
        right_box.pack(side="right", fill="y")

        self.lbl_status_pill = tk.Label(right_box, text="● SERVER READY", font=("Segoe UI", 10, "bold"),
                                        bg=CARD_BG, fg=ACCENT_GREEN, padx=8, pady=4)
        self.lbl_status_pill.pack(side="left", padx=8)

        self.btn_toggle = tk.Button(right_box, text="Stop", font=("Segoe UI", 9, "bold"),
                                    bg=BUTTON_BG, fg=ACCENT_RED, activebackground=CARD_BORDER,
                                    activeforeground=TEXT_PRIMARY, relief="flat", padx=10, pady=6,
                                    command=self.toggle_server, cursor="hand2")
        self.btn_toggle.pack(side="left", padx=(0, 6))

        self.btn_restart = tk.Button(right_box, text="Restart Server", font=("Segoe UI", 9, "bold"),
                                     bg=BUTTON_BG, fg=ACCENT_CYAN, activebackground=CARD_BORDER,
                                     activeforeground=TEXT_PRIMARY, relief="flat", padx=12, pady=6,
                                     command=self.restart_server, cursor="hand2")
        self.btn_restart.pack(side="left")

        self.btn_update = tk.Button(right_box, text="Check for updates", font=("Segoe UI", 9, "bold"),
                                    bg=BUTTON_BG, fg=ACCENT_CYAN, activebackground=CARD_BORDER,
                                    activeforeground=TEXT_PRIMARY, relief="flat", padx=10, pady=6,
                                    command=self.check_for_companion_updates, cursor="hand2")
        self.btn_update.pack(side="left", padx=(6, 0))

    def _build_notebook(self):
        self.notebook = ttk.Notebook(self)
        self.notebook.pack(fill="both", expand=True, padx=16, pady=8)

        self.tab_wifi = tk.Frame(self.notebook, bg=BG_DARK, padx=12, pady=12)
        self.notebook.add(self.tab_wifi, text="📱 Wi-Fi & QR Code")
        self._build_wifi_tab()

        self.tab_bt = tk.Frame(self.notebook, bg=BG_DARK, padx=12, pady=12)
        self.notebook.add(self.tab_bt, text="📶 Bluetooth Assistant")
        self._build_bt_tab()

        self.tab_wan = tk.Frame(self.notebook, bg=BG_DARK, padx=12, pady=12)
        self.notebook.add(self.tab_wan, text="🌐 Remote Access (WAN)")
        self._build_wan_tab()

        self.tab_desktop = tk.Frame(self.notebook, bg=BG_DARK, padx=12, pady=12)
        self.notebook.add(self.tab_desktop, text="🚀 Desktop & Hardware")
        self._build_desktop_tab()

        self.tab_tablet = tk.Frame(self.notebook, bg=BG_DARK, padx=12, pady=12)
        self.notebook.add(self.tab_tablet, text="✍️ Tablet Writing Pad")
        self._build_tablet_tab()

        self.tab_logs = tk.Frame(self.notebook, bg=BG_DARK, padx=12, pady=12)
        self.notebook.add(self.tab_logs, text="📊 Live Monitor")
        self._build_logs_tab()

    def _build_wifi_tab(self):
        container = tk.Frame(self.tab_wifi, bg=BG_DARK)
        container.pack(fill="both", expand=True)

        qr_card = tk.Frame(container, bg=CARD_BG, padx=16, pady=16, relief="flat",
                           highlightbackground=CARD_BORDER, highlightthickness=1)
        qr_card.pack(side="left", fill="both", expand=True, padx=(0, 8))

        lbl_qr_title = tk.Label(qr_card, text="Scan with BT Mouse App", font=("Segoe UI", 12, "bold"),
                                bg=CARD_BG, fg=TEXT_PRIMARY)
        lbl_qr_title.pack(anchor="center", pady=(0, 4))

        lbl_qr_desc = tk.Label(qr_card, text="Instant Wi-Fi Pairing (Zero typing needed)",
                               font=("Segoe UI", 9), bg=CARD_BG, fg=TEXT_MUTED)
        lbl_qr_desc.pack(anchor="center", pady=(0, 12))

        self.qr_label = tk.Label(qr_card, bg=CARD_BG)
        self.qr_label.pack(anchor="center", pady=6)

        lbl_hint = tk.Label(qr_card, text="Open Phone App -> Connect -> Scan QR",
                            font=("Segoe UI", 9, "bold"), bg=CARD_BG, fg=ACCENT_CYAN)
        lbl_hint.pack(anchor="center", pady=(8, 0))

        info_card = tk.Frame(container, bg=CARD_BG, padx=16, pady=16, relief="flat",
                             highlightbackground=CARD_BORDER, highlightthickness=1)
        info_card.pack(side="right", fill="both", expand=True, padx=(8, 0))

        lbl_info_title = tk.Label(info_card, text="Server Connection Info", font=("Segoe UI", 12, "bold"),
                                  bg=CARD_BG, fg=TEXT_PRIMARY)
        lbl_info_title.pack(anchor="w", pady=(0, 10))

        lbl_adapter = tk.Label(info_card, text="Network Adapter / IP:", font=("Segoe UI", 9, "bold"),
                               bg=CARD_BG, fg=TEXT_MUTED)
        lbl_adapter.pack(anchor="w", pady=(4, 2))

        adapter_names = [f"{name}: {ip}" for name, ip in self.adapters]
        self.combo_adapter = ttk.Combobox(info_card, values=adapter_names, state="readonly", font=("Segoe UI", 9))
        if adapter_names:
            self.combo_adapter.current(0)
        self.combo_adapter.pack(fill="x", pady=(0, 10))
        self.combo_adapter.bind("<<ComboboxSelected>>", self._on_adapter_selected)

        lbl_ip_head = tk.Label(info_card, text="Local IP Address:", font=("Segoe UI", 9, "bold"),
                               bg=CARD_BG, fg=TEXT_MUTED)
        lbl_ip_head.pack(anchor="w", pady=(4, 2))

        self.lbl_ip_val = tk.Label(info_card, text=self.current_ip, font=("Segoe UI", 14, "bold"),
                                   bg=CARD_BG, fg=ACCENT_CYAN)
        self.lbl_ip_val.pack(anchor="w")

        lbl_port_head = tk.Label(info_card, text="UDP Input Port:", font=("Segoe UI", 9, "bold"),
                                 bg=CARD_BG, fg=TEXT_MUTED)
        lbl_port_head.pack(anchor="w", pady=(8, 2))

        self.lbl_port_val = tk.Label(info_card, text=str(self.current_port), font=("Segoe UI", 14, "bold"),
                                     bg=CARD_BG, fg=TEXT_PRIMARY)
        self.lbl_port_val.pack(anchor="w")

        lbl_pin_head = tk.Label(info_card, text="Wi-Fi Pairing PIN (enter this on your phone):",
                                font=("Segoe UI", 9, "bold"), bg=CARD_BG, fg=TEXT_MUTED)
        lbl_pin_head.pack(anchor="w", pady=(12, 2))
        self.lbl_wifi_pin = tk.Label(info_card, text=self.auth_token, font=("Segoe UI", 18, "bold"),
                                     bg=BUTTON_BG, fg=ACCENT_GREEN, padx=10, pady=5)
        self.lbl_wifi_pin.pack(anchor="w", fill="x")

        btn_copy = tk.Button(info_card, text="📋 Copy IP Address", font=("Segoe UI", 9, "bold"),
                             bg=BUTTON_BG, fg=TEXT_PRIMARY, activebackground=CARD_BORDER,
                             relief="flat", padx=10, pady=8, command=self.copy_ip, cursor="hand2")
        btn_copy.pack(fill="x", pady=(10, 6))

        btn_copy_pin = tk.Button(info_card, text="📋 Copy Pairing PIN", font=("Segoe UI", 9, "bold"),
                                 bg=BUTTON_BG, fg=TEXT_PRIMARY, activebackground=CARD_BORDER,
                                 relief="flat", padx=10, pady=8, command=self.copy_pairing_pin, cursor="hand2")
        btn_copy_pin.pack(fill="x", pady=(0, 6))

        btn_refresh = tk.Button(info_card, text="🔄 Refresh QR Code", font=("Segoe UI", 9),
                                bg=CARD_BG, fg=ACCENT_CYAN, activebackground=CARD_BORDER,
                                relief="flat", padx=10, pady=6, command=self.generate_qr, cursor="hand2")
        btn_refresh.pack(fill="x")

        self.generate_qr()

    def _build_bt_tab(self):
        card = tk.Frame(self.tab_bt, bg=CARD_BG, padx=20, pady=20, relief="flat",
                        highlightbackground=CARD_BORDER, highlightthickness=1)
        card.pack(fill="both", expand=True)

        lbl_title = tk.Label(card, text="Bluetooth HID Mode (Hardware Emulation)",
                             font=("Segoe UI", 13, "bold"), bg=CARD_BG, fg=TEXT_PRIMARY)
        lbl_title.pack(anchor="w", pady=(0, 6))

        desc = (
            "In Bluetooth HID mode, your Android phone acts as a real hardware Bluetooth Mouse & Keyboard.\n"
            "• Zero companion software is needed on the PC!\n"
            "• Works directly with Windows, macOS, Linux, Android TV, iPad, and iPhone.\n"
            "• If Bluetooth connection is acting funky or fails to pair, use the tools below to reset."
        )
        lbl_desc = tk.Label(card, text=desc, font=("Segoe UI", 10), justify="left",
                            bg=CARD_BG, fg=TEXT_MUTED, wraplength=600)
        lbl_desc.pack(anchor="w", pady=(0, 16))

        btn_win_bt = tk.Button(card, text="⚙️ Open Windows Bluetooth Settings", font=("Segoe UI", 10, "bold"),
                               bg=ACCENT_INDIGO, fg=TEXT_PRIMARY, activebackground=CARD_BORDER,
                               relief="flat", padx=14, pady=10, command=self.open_windows_bt_settings, cursor="hand2")
        btn_win_bt.pack(anchor="w", pady=(0, 16))

        trouble_box = tk.LabelFrame(card, text=" Pairing Fix Checklist ", font=("Segoe UI", 10, "bold"),
                                    bg=CARD_BG, fg=ACCENT_CYAN, padx=12, pady=10)
        trouble_box.pack(fill="x", pady=8)

        steps = (
            "1. In Windows Bluetooth Settings, find your phone under 'Other devices' or 'Input' and click 'Remove device'.\n"
            "2. On your phone: Go to Bluetooth settings, unpair this PC.\n"
            "3. In the BT Mouse phone app: Go to 'Connect' tab -> select 'Bluetooth HID' -> tap 'Register HID Device'.\n"
            "4. Put your phone in Bluetooth discoverable mode.\n"
            "5. On your PC: Click 'Add Device' -> 'Bluetooth' -> select your phone.\n"
            "6. Accept the pairing prompt on BOTH phone and PC."
        )
        lbl_steps = tk.Label(trouble_box, text=steps, font=("Segoe UI", 9), justify="left",
                             bg=CARD_BG, fg=TEXT_PRIMARY)
        lbl_steps.pack(anchor="w")

    def _build_tablet_tab(self):
        card = tk.Frame(self.tab_tablet, bg=CARD_BG, padx=20, pady=20, relief="flat",
                        highlightbackground=CARD_BORDER, highlightthickness=1)
        card.pack(fill="both", expand=True)

        lbl_title = tk.Label(card, text="Tablet Writing & Drawing Pad",
                             font=("Segoe UI", 13, "bold"), bg=CARD_BG, fg=TEXT_PRIMARY)
        lbl_title.pack(anchor="w", pady=(0, 6))

        desc = (
            "Turn any Android tablet (or phone) into an interactive Graphics Drawing Pad & Writing Slate!\n\n"
            "Features:\n"
            "• Absolute 1:1 Screen Mapping: Tablet surface maps directly to your PC monitor.\n"
            "• S-Pen & Stylus Support: Detects stylus touches with pressure sensitivity.\n"
            "• Palm Rejection: Rest your wrist on the tablet while drawing without accidental clicks.\n"
            "• Works seamlessly with MS Paint, OneNote, Photoshop, Paint 3D, and whiteboard apps."
        )
        lbl_desc = tk.Label(card, text=desc, font=("Segoe UI", 10), justify="left",
                            bg=CARD_BG, fg=TEXT_MUTED, wraplength=600)
        lbl_desc.pack(anchor="w", pady=(0, 16))

        metrics_frame = tk.Frame(card, bg=CARD_BG)
        metrics_frame.pack(anchor="w", pady=8)

        sw, sh = 1920, 1080
        if IS_WINDOWS:
            try:
                import ctypes
                sw = ctypes.windll.user32.GetSystemMetrics(0)
                sh = ctypes.windll.user32.GetSystemMetrics(1)
            except Exception:
                pass

        lbl_res_title = tk.Label(metrics_frame, text="Detected PC Screen Resolution:",
                                 font=("Segoe UI", 9, "bold"), bg=CARD_BG, fg=TEXT_MUTED)
        lbl_res_title.pack(side="left")

        lbl_res_val = tk.Label(metrics_frame, text=f"{sw} x {sh} px",
                               font=("Segoe UI", 11, "bold"), bg=CARD_BG, fg=ACCENT_CYAN, padx=8)
        lbl_res_val.pack(side="left")

        btn_open_paint = tk.Button(card, text="🎨 Open MS Paint for Drawing Test", font=("Segoe UI", 10, "bold"),
                                   bg=BUTTON_BG, fg=TEXT_PRIMARY, activebackground=CARD_BORDER,
                                   relief="flat", padx=14, pady=8, command=self.open_paint, cursor="hand2")
        btn_open_paint.pack(anchor="w", pady=12)

    def _build_wan_tab(self):
        container = tk.Frame(self.tab_wan, bg=BG_DARK)
        container.pack(fill="both", expand=True)

        qr_card = tk.Frame(container, bg=CARD_BG, padx=16, pady=16, relief="flat",
                           highlightbackground=CARD_BORDER, highlightthickness=1)
        qr_card.pack(side="left", fill="both", expand=True, padx=(0, 8))

        lbl_qr_title = tk.Label(qr_card, text="Secure Remote Access Setup", font=("Segoe UI", 12, "bold"),
                                bg=CARD_BG, fg=TEXT_PRIMARY)
        lbl_qr_title.pack(anchor="center", pady=(0, 4))

        lbl_qr_desc = tk.Label(qr_card, text="Scan to fill the local server address; enter the PIN separately",
                               font=("Segoe UI", 9), bg=CARD_BG, fg=TEXT_MUTED)
        lbl_qr_desc.pack(anchor="center", pady=(0, 10))

        self.wan_qr_label = tk.Label(qr_card, bg=CARD_BG)
        self.wan_qr_label.pack(anchor="center", pady=6)

        lbl_hint = tk.Label(qr_card, text="Open Phone App -> Connect -> Remote WAN -> Scan QR",
                            font=("Segoe UI", 9, "bold"), bg=CARD_BG, fg=ACCENT_CYAN)
        lbl_hint.pack(anchor="center", pady=(8, 0))

        info_card = tk.Frame(container, bg=CARD_BG, padx=16, pady=16, relief="flat",
                             highlightbackground=CARD_BORDER, highlightthickness=1)
        info_card.pack(side="right", fill="both", expand=True, padx=(8, 0))

        lbl_info_title = tk.Label(info_card, text="WAN Server Configuration", font=("Segoe UI", 12, "bold"),
                                  bg=CARD_BG, fg=TEXT_PRIMARY)
        lbl_info_title.pack(anchor="w", pady=(0, 10))

        lbl_ws_port = tk.Label(info_card, text=f"WebSocket Port: {self.ws_port}", font=("Segoe UI", 11, "bold"),
                               bg=CARD_BG, fg=ACCENT_CYAN)
        lbl_ws_port.pack(anchor="w", pady=(4, 6))

        lbl_token_head = tk.Label(info_card, text="Security Access Key (PIN):", font=("Segoe UI", 9, "bold"),
                                  bg=CARD_BG, fg=TEXT_MUTED)
        lbl_token_head.pack(anchor="w", pady=(6, 2))

        token_row = tk.Frame(info_card, bg=CARD_BG)
        token_row.pack(fill="x", pady=(0, 8))

        self.ent_token = tk.Entry(token_row, font=("Segoe UI", 11, "bold"), bg=BUTTON_BG, fg=TEXT_PRIMARY,
                                  insertbackground=TEXT_PRIMARY, relief="flat")
        self.ent_token.insert(0, self.auth_token)
        self.ent_token.pack(side="left", fill="x", expand=True, padx=(0, 8), ipady=4)

        btn_set_token = tk.Button(token_row, text="Update Key", font=("Segoe UI", 9, "bold"),
                                  bg=CARD_BORDER, fg=TEXT_PRIMARY, relief="flat", padx=10,
                                  command=self._update_auth_token, cursor="hand2")
        btn_set_token.pack(side="right")

        lbl_url_head = tk.Label(info_card, text="Direct WebSocket Address:", font=("Segoe UI", 9, "bold"),
                                bg=CARD_BG, fg=TEXT_MUTED)
        lbl_url_head.pack(anchor="w", pady=(8, 2))

        self.lbl_wan_url = tk.Label(info_card, text=f"ws://{self.current_ip}:{self.ws_port}",
                                    font=("Consolas", 10, "bold"), bg=BUTTON_BG, fg=ACCENT_GREEN, padx=8, pady=6)
        self.lbl_wan_url.pack(anchor="w", fill="x")

        btn_copy_url = tk.Button(info_card, text="📋 Copy WebSocket URL", font=("Segoe UI", 9, "bold"),
                                 bg=BUTTON_BG, fg=TEXT_PRIMARY, activebackground=CARD_BORDER,
                                 relief="flat", padx=10, pady=8, command=self.copy_wan_url, cursor="hand2")
        btn_copy_url.pack(fill="x", pady=(14, 6))

        btn_refresh_wan = tk.Button(info_card, text="🔄 Refresh WAN QR Code", font=("Segoe UI", 9),
                                    bg=CARD_BG, fg=ACCENT_CYAN, activebackground=CARD_BORDER,
                                    relief="flat", padx=10, pady=6, command=self.generate_wan_qr, cursor="hand2")
        btn_refresh_wan.pack(fill="x")

        lbl_tips = tk.Label(info_card, text="Internet use requires a TLS endpoint (wss://), such as a securely configured tunnel or reverse proxy. This companion listens locally with ws://; never expose that port directly to the Internet.",
                            font=("Segoe UI", 8), bg=CARD_BG, fg=TEXT_MUTED, wraplength=280, justify="left")
        lbl_tips.pack(anchor="w", pady=(12, 0))

        self.generate_wan_qr()

    def _build_desktop_tab(self):
        card = tk.Frame(self.tab_desktop, bg=CARD_BG, padx=18, pady=16, relief="flat",
                        highlightbackground=CARD_BORDER, highlightthickness=1)
        card.pack(fill="both", expand=True)

        lbl_title = tk.Label(card, text="Desktop Controls, Camera & Mic Diagnostics",
                             font=("Segoe UI", 13, "bold"), bg=CARD_BG, fg=TEXT_PRIMARY)
        lbl_title.pack(anchor="w", pady=(0, 6))

        lbl_sub = tk.Label(card, text="Test remote control actions locally on this PC:",
                           font=("Segoe UI", 9), bg=CARD_BG, fg=TEXT_MUTED)
        lbl_sub.pack(anchor="w", pady=(0, 14))

        startup_frame = tk.Frame(card, bg=CARD_BG)
        startup_frame.pack(fill="x", pady=(0, 8))
        self.startup_var = tk.BooleanVar(value=self._startup_registry_value() is not None)
        tk.Checkbutton(
            startup_frame,
            text="Start with Windows (minimized to tray)",
            variable=self.startup_var,
            command=lambda: self._set_startup_enabled(self.startup_var.get()),
            state="normal" if os.name == "nt" else "disabled",
            font=("Segoe UI", 9), bg=CARD_BG, fg=TEXT_PRIMARY,
            activebackground=CARD_BG, activeforeground=TEXT_PRIMARY,
            selectcolor=BUTTON_BG, anchor="w"
        ).pack(anchor="w", fill="x")

        quick_box = tk.LabelFrame(card, text=" Quick Apps on this PC ", font=("Segoe UI", 10, "bold"),
                                  bg=CARD_BG, fg=ACCENT_CYAN, padx=10, pady=8)
        quick_box.pack(fill="x", pady=(4, 8))
        add_row = tk.Frame(quick_box, bg=CARD_BG)
        add_row.pack(fill="x", pady=(0, 6))
        self.combo_available_apps = ttk.Combobox(add_row, state="readonly", font=("Segoe UI", 10))
        self.combo_available_apps.pack(side="left", fill="x", expand=True, padx=(0, 6))
        tk.Button(add_row, text="Add selected app", font=("Segoe UI", 9, "bold"), bg=BUTTON_BG,
                  fg=ACCENT_GREEN, relief="flat", padx=8, pady=5, command=self.add_quick_app).pack(side="right")

        self.list_quick_apps = tk.Listbox(quick_box, height=5, font=("Segoe UI", 10), bg=BUTTON_BG,
                                          fg=TEXT_PRIMARY, selectbackground=ACCENT_INDIGO,
                                          borderwidth=0, highlightthickness=0, activestyle="none")
        self.list_quick_apps.pack(fill="x", pady=(0, 6))
        self.list_quick_apps.bind("<<ListboxSelect>>", self.on_quick_app_selected)
        edit_row = tk.Frame(quick_box, bg=CARD_BG)
        edit_row.pack(fill="x")
        self.ent_quick_app_name = tk.Entry(edit_row, font=("Segoe UI", 10), bg=BUTTON_BG, fg=TEXT_PRIMARY,
                                           insertbackground=TEXT_PRIMARY, relief="flat")
        self.ent_quick_app_name.pack(side="left", fill="x", expand=True, padx=(0, 6), ipady=5)
        tk.Button(edit_row, text="Save name", font=("Segoe UI", 9), bg=BUTTON_BG, fg=ACCENT_CYAN,
                  relief="flat", padx=8, pady=5, command=self.rename_quick_app).pack(side="left", padx=(0, 5))
        tk.Button(edit_row, text="Remove", font=("Segoe UI", 9), bg=BUTTON_BG, fg=ACCENT_RED,
                  relief="flat", padx=8, pady=5, command=self.remove_quick_app).pack(side="left")
        tk.Button(edit_row, text="Refresh installed apps", font=("Segoe UI", 9), bg=BUTTON_BG,
                  fg=TEXT_PRIMARY, relief="flat", padx=8, pady=5, command=self.refresh_quick_apps).pack(side="left", padx=(5, 0))
        self.lbl_quick_apps_status = tk.Label(quick_box, text="Quick apps are saved on this PC and shared with the phone app.",
                                              font=("Segoe UI", 9), bg=CARD_BG, fg=TEXT_MUTED, anchor="w")
        self.lbl_quick_apps_status.pack(fill="x", pady=(5, 0))
        self.after_idle(self.refresh_quick_apps)

        # Power Controls
        pwr_frame = tk.Frame(card, bg=CARD_BG)
        pwr_frame.pack(anchor="w", fill="x", pady=10)

        lbl_pwr = tk.Label(pwr_frame, text="Power Actions:", font=("Segoe UI", 9, "bold"), bg=CARD_BG, fg=TEXT_MUTED)
        lbl_pwr.pack(side="left", padx=(0, 8))

        btn_lock = tk.Button(pwr_frame, text="🔒 Lock Workstation", font=("Segoe UI", 9, "bold"),
                             bg=BUTTON_BG, fg=ACCENT_RED, relief="flat", padx=10, pady=4, cursor="hand2",
                             command=lambda: self.server._handle_system_power("lock") if self.server else None)
        btn_lock.pack(side="left", padx=4)

        self.auto_record_video_var = tk.BooleanVar(value=self.auto_record_video)
        tk.Checkbutton(
            card,
            text="Record every live webcam session on this PC (1280×720, high quality)",
            variable=self.auto_record_video_var,
            command=self._save_recording_preferences,
            font=("Segoe UI", 9), bg=CARD_BG, fg=TEXT_PRIMARY,
            activebackground=CARD_BG, activeforeground=TEXT_PRIMARY,
            selectcolor=BUTTON_BG, anchor="w"
        ).pack(anchor="w", fill="x", pady=(4, 0))
        self.auto_record_audio_var = tk.BooleanVar(value=self.auto_record_audio)
        tk.Checkbutton(
            card,
            text="Also record the PC microphone during each live webcam session",
            variable=self.auto_record_audio_var,
            command=self._save_recording_preferences,
            font=("Segoe UI", 9), bg=CARD_BG, fg=TEXT_PRIMARY,
            activebackground=CARD_BG, activeforeground=TEXT_PRIMARY,
            selectcolor=BUTTON_BG, anchor="w"
        ).pack(anchor="w", fill="x", pady=(2, 0))

        retention_row = tk.Frame(card, bg=CARD_BG)
        retention_row.pack(anchor="w", fill="x", pady=(8, 2))
        tk.Label(retention_row, text="Delete older than days (0 = keep):", font=("Segoe UI", 8),
                 bg=CARD_BG, fg=TEXT_MUTED).pack(side="left")
        self.recording_retention_var = tk.StringVar(value=str(self.recording_retention_days))
        ttk.Spinbox(retention_row, from_=0, to=3650, width=6, textvariable=self.recording_retention_var).pack(side="left", padx=(5, 12))
        tk.Label(retention_row, text="Maximum library MB (0 = unlimited):", font=("Segoe UI", 8),
                 bg=CARD_BG, fg=TEXT_MUTED).pack(side="left")
        self.recording_max_storage_var = tk.StringVar(value=str(self.recording_max_storage_mb))
        ttk.Spinbox(retention_row, from_=0, to=500000, increment=512, width=8,
                    textvariable=self.recording_max_storage_var).pack(side="left", padx=5)
        reserve_row = tk.Frame(card, bg=CARD_BG)
        reserve_row.pack(anchor="w", fill="x", pady=(2, 6))
        tk.Label(reserve_row, text="Keep at least this much disk space free (MB):", font=("Segoe UI", 8),
                 bg=CARD_BG, fg=TEXT_MUTED).pack(side="left")
        self.recording_min_free_var = tk.StringVar(value=str(self.recording_min_free_mb))
        ttk.Spinbox(reserve_row, from_=0, to=100000, increment=256, width=8,
                    textvariable=self.recording_min_free_var).pack(side="left", padx=5)
        tk.Button(reserve_row, text="Save recording limits", font=("Segoe UI", 8), bg=BUTTON_BG,
                  fg=ACCENT_CYAN, relief="flat", padx=8, pady=4,
                  command=self._save_recording_preferences).pack(side="left", padx=8)

        # Hardware & Multimedia tests
        media_frame = tk.Frame(card, bg=CARD_BG)
        media_frame.pack(anchor="w", fill="x", pady=12)

        preview_row = tk.Frame(card, bg=CARD_BG)
        preview_row.pack(anchor="w", fill="x", pady=(0, 8))
        tk.Button(preview_row, text="Select player preview area", font=("Segoe UI", 9, "bold"),
                  bg=BUTTON_BG, fg=ACCENT_CYAN, relief="flat", padx=10, pady=6,
                  command=self.select_media_preview_region).pack(side="left", padx=(0, 8))
        self.lbl_media_preview_region = tk.Label(
            preview_row, text=self._media_preview_region_description(), font=("Segoe UI", 9),
            bg=CARD_BG, fg=TEXT_MUTED, anchor="w"
        )
        self.lbl_media_preview_region.pack(side="left", fill="x", expand=True)
        tk.Label(card, text="Phone preview captures only this selected on-screen rectangle while its preview toggle is on. Page content inside the selection may appear; protected video can be black.",
                 font=("Segoe UI", 8), bg=CARD_BG, fg=TEXT_MUTED, wraplength=610, justify="left").pack(anchor="w")

        btn_cam = tk.Button(media_frame, text="📸 Test Webcam Snapshot", font=("Segoe UI", 9, "bold"),
                            bg=BUTTON_BG, fg=ACCENT_CYAN, relief="flat", padx=10, pady=6, cursor="hand2",
                            command=self.test_camera_snapshot)
        btn_cam.pack(side="left", padx=(0, 8))

        btn_mic = tk.Button(media_frame, text="🎙️ Test Mic (3-sec Record)", font=("Segoe UI", 9, "bold"),
                            bg=BUTTON_BG, fg=ACCENT_GREEN, relief="flat", padx=10, pady=6, cursor="hand2",
                            command=self.test_mic_record)
        btn_mic.pack(side="left")

        self.lbl_cam_preview = tk.Label(card, bg=CARD_BG)
        self.lbl_cam_preview.pack(anchor="w", pady=8)

        self.lbl_media_status = tk.Label(card, text="Webcam & Mic ready for remote commands.",
                                         font=("Segoe UI", 9), bg=CARD_BG, fg=TEXT_MUTED)
        self.lbl_media_status.pack(anchor="w", pady=4)

        recordings = tk.LabelFrame(card, text=" Recordings on this PC ", font=("Segoe UI", 10, "bold"),
                                   bg=CARD_BG, fg=ACCENT_CYAN, padx=10, pady=8)
        recordings.pack(fill="both", expand=True, pady=(8, 0))
        self.list_recordings = tk.Listbox(recordings, height=5, font=("Segoe UI", 9), bg=BUTTON_BG,
                                          fg=TEXT_PRIMARY, selectbackground=ACCENT_INDIGO,
                                          borderwidth=0, highlightthickness=0, activestyle="none")
        self.list_recordings.pack(fill="both", expand=True, pady=(0, 6))
        self.list_recordings.bind("<Double-Button-1>", lambda _event: self.open_selected_recording())
        recording_actions = tk.Frame(recordings, bg=CARD_BG)
        recording_actions.pack(fill="x")
        tk.Button(recording_actions, text="Open selected", font=("Segoe UI", 9), bg=BUTTON_BG,
                  fg=TEXT_PRIMARY, relief="flat", padx=8, pady=5,
                  command=self.open_selected_recording).pack(side="left", padx=(0, 5))
        tk.Button(recording_actions, text="Open folder", font=("Segoe UI", 9), bg=BUTTON_BG,
                  fg=ACCENT_CYAN, relief="flat", padx=8, pady=5,
                  command=self.open_recordings_folder).pack(side="left", padx=5)
        tk.Button(recording_actions, text="Refresh", font=("Segoe UI", 9), bg=BUTTON_BG,
                  fg=TEXT_PRIMARY, relief="flat", padx=8, pady=5,
                  command=self.refresh_recordings).pack(side="left", padx=5)
        self.lbl_recordings_path = tk.Label(recordings, text="", font=("Segoe UI", 8), bg=CARD_BG,
                                            fg=TEXT_MUTED, anchor="w")
        self.lbl_recordings_path.pack(fill="x", pady=(5, 0))
        self.refresh_recordings()

    def _media_preview_region_description(self):
        region = load_media_preview_region()
        if not region:
            return "No player area selected"
        return f"Selected area: {region['width']} × {region['height']} px"

    def select_media_preview_region(self):
        overlay = tk.Toplevel(self)
        overlay.overrideredirect(True)
        overlay.attributes("-topmost", True)
        overlay.attributes("-alpha", 0.78)
        width, height = self.winfo_screenwidth(), self.winfo_screenheight()
        overlay.geometry(f"{width}x{height}+0+0")
        canvas = tk.Canvas(overlay, bg="#111827", cursor="crosshair", highlightthickness=0)
        canvas.pack(fill="both", expand=True)
        canvas.create_text(width // 2, 50, text="Drag around the video player area · Esc to cancel",
                           fill="white", font=("Segoe UI", 18, "bold"))
        canvas.create_text(width // 2, 82, text="Only the selected rectangle is sent to your phone while preview is enabled.",
                           fill="#cbd5e1", font=("Segoe UI", 11))
        selection = {"start": None, "rect": None}

        def begin(event):
            selection["start"] = (event.x, event.y)
            if selection["rect"]:
                canvas.delete(selection["rect"])
            selection["rect"] = canvas.create_rectangle(event.x, event.y, event.x, event.y,
                                                          outline="#22d3ee", width=3, fill="", stipple="gray25")

        def drag(event):
            if selection["start"] and selection["rect"]:
                x0, y0 = selection["start"]
                canvas.coords(selection["rect"], x0, y0, event.x, event.y)

        def finish(event):
            if not selection["start"]:
                return
            x0, y0 = selection["start"]
            left, top = min(x0, event.x), min(y0, event.y)
            region = {"x": left, "y": top, "width": abs(event.x - x0), "height": abs(event.y - y0)}
            try:
                save_media_preview_region(region)
                self.lbl_media_preview_region.config(text=self._media_preview_region_description())
                overlay.destroy()
            except ValueError as exc:
                messagebox.showinfo("Select player area", str(exc), parent=overlay)

        canvas.bind("<ButtonPress-1>", begin)
        canvas.bind("<B1-Motion>", drag)
        canvas.bind("<ButtonRelease-1>", finish)
        overlay.bind("<Escape>", lambda _event: overlay.destroy())
        canvas.focus_set()

    def _save_recording_preferences(self):
        enabled = bool(self.auto_record_video_var.get())
        record_audio = bool(self.auto_record_audio_var.get())
        try:
            retention_days = min(3650, max(0, int(self.recording_retention_var.get())))
            max_storage_mb = min(500000, max(0, int(self.recording_max_storage_var.get())))
            min_free_mb = min(100000, max(0, int(self.recording_min_free_var.get())))
        except (AttributeError, ValueError):
            messagebox.showerror("Recording limits", "Use whole numbers for the recording limits.")
            return
        self.auto_record_video = enabled
        self.auto_record_audio = record_audio
        self.recording_retention_days = retention_days
        self.recording_max_storage_mb = max_storage_mb
        self.recording_min_free_mb = min_free_mb
        policy = {"retention_days": retention_days, "max_storage_mb": max_storage_mb, "min_free_mb": min_free_mb}
        if self.server:
            self.server.camera_manager.auto_record_enabled = enabled
            self.server.auto_record_audio_with_video = record_audio
            self.server.recording_policy = dict(policy)
            self.server.camera_manager.recording_policy = dict(policy)
            self.server.audio_manager.recording_policy = dict(policy)
            self.server.live_audio_manager.recording_policy = dict(policy)
        try:
            self.recording_settings_path.parent.mkdir(parents=True, exist_ok=True)
            self.recording_settings_path.write_text(
                json.dumps({
                    "auto_record_video": enabled,
                    "auto_record_audio": record_audio,
                    "recording_retention_days": retention_days,
                    "recording_max_storage_mb": max_storage_mb,
                    "recording_min_free_mb": min_free_mb,
                }, indent=2), encoding="utf-8"
            )
        except OSError as exc:
            messagebox.showerror("Recording settings", f"Could not save the recording preference.\n\n{exc}")

    def _recordings_dir(self):
        if self.server and self.server.audio_manager:
            return Path(self.server.audio_manager.recordings_dir)
        user_data = os.environ.get("LOCALAPPDATA") or str(Path.home())
        return Path(user_data) / "BTMouse" / "Recordings"

    def refresh_recordings(self):
        if not hasattr(self, "list_recordings"):
            return
        folder = self._recordings_dir()
        folder.mkdir(parents=True, exist_ok=True)
        self.list_recordings.delete(0, "end")
        self._recording_paths = []
        for path in sorted((p for p in folder.iterdir() if p.is_file()), key=lambda p: p.stat().st_mtime, reverse=True):
            if self.server and self.server.camera_manager.streaming and self.server.camera_manager.last_recording_path == str(path):
                continue
            if self.server and self.server.audio_manager.recording and self.server.audio_manager.current_filename == str(path):
                continue
            self._recording_paths.append(path)
            size_mb = path.stat().st_size / (1024 * 1024)
            self.list_recordings.insert("end", f"{path.name}  ·  {size_mb:.1f} MB")
        self.lbl_recordings_path.config(text=str(folder))

    def _refresh_recordings_loop(self):
        if not self.winfo_exists():
            return
        self.refresh_recordings()
        self.after(4000, self._refresh_recordings_loop)

    def open_recordings_folder(self):
        folder = self._recordings_dir()
        folder.mkdir(parents=True, exist_ok=True)
        try:
            if os.name == "nt":
                os.startfile(str(folder))
            elif sys.platform == "darwin":
                subprocess.Popen(["open", str(folder)])
            else:
                subprocess.Popen(["xdg-open", str(folder)])
        except Exception as exc:
            messagebox.showerror("Recordings", f"Could not open the recordings folder.\n\n{exc}")

    def open_selected_recording(self):
        selection = self.list_recordings.curselection() if hasattr(self, "list_recordings") else ()
        if not selection:
            return
        path = self._recording_paths[selection[0]]
        try:
            os.startfile(str(path)) if os.name == "nt" else subprocess.Popen(["xdg-open", str(path)])
        except Exception as exc:
            messagebox.showerror("Recordings", f"Could not open {path.name}.\n\n{exc}")

    def _build_logs_tab(self):
        container = tk.Frame(self.tab_logs, bg=BG_DARK)
        container.pack(fill="both", expand=True)

        stats_card = tk.Frame(container, bg=CARD_BG, padx=12, pady=10, relief="flat",
                              highlightbackground=CARD_BORDER, highlightthickness=1)
        stats_card.pack(fill="x", pady=(0, 8))

        self.lbl_client = tk.Label(stats_card, text="Client: Disconnected", font=("Segoe UI", 10, "bold"),
                                   bg=CARD_BG, fg=TEXT_MUTED)
        self.lbl_client.pack(side="left", padx=8)

        self.lbl_packets = tk.Label(stats_card, text="Packets Received: 0", font=("Segoe UI", 10, "bold"),
                                    bg=CARD_BG, fg=ACCENT_CYAN)
        self.lbl_packets.pack(side="right", padx=8)

        log_frame = tk.Frame(container, bg=CARD_BG, relief="flat",
                             highlightbackground=CARD_BORDER, highlightthickness=1)
        log_frame.pack(fill="both", expand=True)

        self.txt_log = tk.Text(log_frame, bg=CARD_BG, fg=TEXT_PRIMARY, font=("Consolas", 9),
                               borderwidth=0, highlightthickness=0, padx=8, pady=8)
        self.txt_log.pack(side="left", fill="both", expand=True)

        scrollbar = ttk.Scrollbar(log_frame, command=self.txt_log.yview)
        scrollbar.pack(side="right", fill="y")
        self.txt_log.config(yscrollcommand=scrollbar.set)

        self.log("Companion initialized. Ready for connections.")

    def _build_statusbar(self):
        bar = tk.Frame(self, bg=CARD_BG, height=28, padx=16, pady=4)
        bar.pack(fill="x", side="bottom")

        lbl_foot = tk.Label(bar, text=f"BT Mouse Companion v{APP_VERSION} | Minimizes to system tray | Native Windows Hardware Input",
                            font=("Segoe UI", 8), bg=CARD_BG, fg=TEXT_MUTED)
        lbl_foot.pack(side="left")

    @staticmethod
    def _version_tuple(version):
        match = re.fullmatch(r"v?(\d+)\.(\d+)\.(\d+)", version.strip())
        if not match:
            raise ValueError(f"Unrecognized release version: {version}")
        return tuple(int(part) for part in match.groups())

    def _show_update_recovery_notice(self):
        """Show a deferred update error after the old executable was restored."""
        try:
            if not COMPANION_UPDATE_ERROR_LOG.is_file():
                return
            message = COMPANION_UPDATE_ERROR_LOG.read_text(encoding="utf-8-sig", errors="replace").strip()
            COMPANION_UPDATE_ERROR_LOG.unlink(missing_ok=True)
            if message:
                messagebox.showwarning(
                    "Companion update rolled back",
                    f"The update could not start, so the previous version was restored.\n\n{message}",
                    parent=self,
                )
        except OSError as exc:
            self.log(f"Could not read update recovery details: {exc}")

    def check_for_companion_updates(self):
        if not getattr(sys, "frozen", False):
            messagebox.showinfo("Companion updates", "In-app updates are available when running the packaged Windows app.")
            return
        self.btn_update.config(state="disabled", text="Checking…")
        threading.Thread(target=self._check_companion_updates_worker, daemon=True).start()

    def _check_companion_updates_worker(self):
        try:
            api_url = f"https://api.github.com/repos/{GITHUB_REPOSITORY}/releases/latest"
            request = urllib.request.Request(api_url, headers={
                "Accept": "application/vnd.github+json",
                "User-Agent": "BT-Mouse-Companion-Updater",
            })
            with urllib.request.urlopen(request, timeout=20) as response:
                release = json.loads(response.read(2 * 1024 * 1024).decode("utf-8"))

            tag_name = release.get("tag_name", "")
            if release.get("draft") or release.get("prerelease"):
                raise ValueError("GitHub did not return a stable release.")
            if self._version_tuple(tag_name) <= self._version_tuple(APP_VERSION):
                self.after(0, lambda: messagebox.showinfo(
                    "Companion updates", f"You are using the latest version (v{APP_VERSION})."))
                self.after(0, lambda: self.btn_update.config(state="normal", text="Check for updates"))
                return

            asset = next((item for item in release.get("assets", [])
                          if item.get("name") == "BT-Mouse-Companion.exe"), None)
            if not asset or not asset.get("browser_download_url"):
                raise ValueError("The latest release does not include the Windows companion executable.")
            if asset.get("size", 0) <= 0 or asset["size"] > MAX_COMPANION_UPDATE_BYTES:
                raise ValueError("The Windows update has an unexpected file size.")

            def ask_to_update():
                update = messagebox.askyesno(
                    "Companion update available",
                    f"Version {tag_name} is available. Download and install it now?\n\n"
                    "The companion will close briefly and reopen after updating.")
                if update:
                    self.btn_update.config(state="disabled", text="Downloading…")
                    threading.Thread(target=self._download_companion_update_worker,
                                     args=(tag_name, asset), daemon=True).start()
                else:
                    self.btn_update.config(state="normal", text="Check for updates")
            self.after(0, ask_to_update)
        except Exception as exc:
            self.after(0, lambda error=str(exc): messagebox.showerror(
                "Companion update failed", f"Could not check for updates.\n\n{error}"))
            self.after(0, lambda: self.btn_update.config(state="normal", text="Check for updates"))

    def _download_companion_update_worker(self, tag_name, asset):
        staged_path = None
        try:
            download_url = asset["browser_download_url"]
            if not download_url.startswith("https://github.com/"):
                raise ValueError("The update URL is not a trusted GitHub release link.")
            request = urllib.request.Request(download_url, headers={
                "User-Agent": "BT-Mouse-Companion-Updater",
                "Accept": "application/octet-stream",
            })
            digest = hashlib.sha256()
            downloaded = 0
            temp_dir = Path(tempfile.gettempdir())
            staged_path = temp_dir / f"BT-Mouse-Companion-{tag_name}.exe"
            with urllib.request.urlopen(request, timeout=60) as response, staged_path.open("wb") as output:
                while True:
                    chunk = response.read(1024 * 1024)
                    if not chunk:
                        break
                    downloaded += len(chunk)
                    if downloaded > asset["size"] or downloaded > MAX_COMPANION_UPDATE_BYTES:
                        raise ValueError("The downloaded update is larger than expected.")
                    digest.update(chunk)
                    output.write(chunk)

            if downloaded != asset["size"]:
                raise ValueError("The downloaded update size did not match the release metadata.")
            with staged_path.open("rb") as downloaded_file:
                if downloaded_file.read(2) != b"MZ":
                    raise ValueError("The downloaded file is not a valid Windows executable.")
            expected_digest = asset.get("digest") or ""
            if expected_digest.startswith("sha256:") and digest.hexdigest().lower() != expected_digest[7:].lower():
                raise ValueError("The downloaded update failed its SHA-256 check.")

            executable = Path(sys.executable).resolve()
            # Probe the destination directory now, before closing the working app.
            probe_path = executable.parent / f".btmouse-update-check-{os.getpid()}"
            try:
                probe_path.write_text("", encoding="ascii")
                probe_path.unlink()
            except OSError as exc:
                raise OSError("The app folder is not writable. Move the companion to a folder you own, then retry.") from exc

            script_path = temp_dir / f"btmouse-update-{os.getpid()}.ps1"
            log_path = COMPANION_UPDATE_ERROR_LOG
            replacement_path = executable.parent / f".BT-Mouse-Companion-{os.getpid()}.new.exe"
            backup_path = executable.parent / f".BT-Mouse-Companion-{os.getpid()}.backup.exe"
            ready_marker = temp_dir / f"btmouse-update-ready-{os.getpid()}.txt"
            ps_quote = lambda value: "'" + str(value).replace("'", "''") + "'"
            script = (
                "$ErrorActionPreference = 'Stop'\n"
                f"$pidToWait = {os.getpid()}\n"
                f"$target = {ps_quote(executable)}\n"
                f"$staged = {ps_quote(staged_path)}\n"
                f"$replacement = {ps_quote(replacement_path)}\n"
                f"$backup = {ps_quote(backup_path)}\n"
                f"$readyMarker = {ps_quote(ready_marker)}\n"
                f"$log = {ps_quote(log_path)}\n"
                f"$expectedLength = {asset['size']}\n"
                f"$expectedVersion = {ps_quote(tag_name.lstrip('v'))}\n"
                "$deadline = (Get-Date).AddSeconds(120)\n"
                "try {\n"
                "  while (Get-Process -Id $pidToWait -ErrorAction SilentlyContinue) {\n"
                "    if ((Get-Date) -gt $deadline) { throw 'The companion did not close in time.' }\n"
                "    Start-Sleep -Seconds 1\n"
                "  }\n"
                "  $copied = $false\n"
                "  for ($attempt = 0; $attempt -lt 30; $attempt++) {\n"
                "    try {\n"
                "      Copy-Item -LiteralPath $staged -Destination $replacement -Force -ErrorAction Stop\n"
                "      if ((Get-Item -LiteralPath $replacement).Length -eq $expectedLength) { $copied = $true; break }\n"
                "    } catch {}\n"
                "    Start-Sleep -Seconds 1\n"
                "  }\n"
                "  if (-not $copied) { throw 'Could not stage the replacement beside the app after waiting for Windows to release the executable.' }\n"
                "  $backedUp = -not (Test-Path -LiteralPath $target)\n"
                "  $moveError = ''\n"
                "  for ($attempt = 0; $attempt -lt 60 -and -not $backedUp; $attempt++) {\n"
                "    try {\n"
                "      Move-Item -LiteralPath $target -Destination $backup -Force -ErrorAction Stop\n"
                "      $backedUp = $true\n"
                "    } catch { $moveError = $_.Exception.Message; Start-Sleep -Seconds 1 }\n"
                "  }\n"
                "  if (-not $backedUp) { throw ('Could not move the previous companion into its rollback copy after waiting for Windows to release it: ' + $moveError) }\n"
                "  Move-Item -LiteralPath $replacement -Destination $target -Force\n"
                "  Remove-Item -LiteralPath $readyMarker -Force -ErrorAction SilentlyContinue\n"
                "  $quotedMarker = '"' + $readyMarker + '"'\n"
                "  $started = Start-Process -FilePath $target -WorkingDirectory (Split-Path -Parent $target) -ArgumentList @('--btmouse-update-ready', $quotedMarker) -PassThru\n"
                "  $launchDeadline = (Get-Date).AddSeconds(30)\n"
                "  $ready = $false\n"
                "  while ((Get-Date) -lt $launchDeadline) {\n"
                "    if (Test-Path -LiteralPath $readyMarker) {\n"
                "      if ((Get-Content -LiteralPath $readyMarker -Raw).Trim() -eq $expectedVersion) { $ready = $true; break }\n"
                "      throw 'The replacement opened with an unexpected version.'\n"
                "    }\n"
                "    if (-not (Get-Process -Id $started.Id -ErrorAction SilentlyContinue)) { throw 'The updated companion exited during startup.' }\n"
                "    Start-Sleep -Seconds 1\n"
                "  }\n"
                "  if (-not $ready) { throw 'The updated companion did not acknowledge startup.' }\n"
                "  Remove-Item -LiteralPath $backup -Force -ErrorAction SilentlyContinue\n"
                "  Remove-Item -LiteralPath $staged -Force -ErrorAction SilentlyContinue\n"
                "  Remove-Item -LiteralPath $readyMarker -Force -ErrorAction SilentlyContinue\n"
                "  Remove-Item -LiteralPath $log -Force -ErrorAction SilentlyContinue\n"
                "} catch {\n"
                "  $failure = $_.Exception.Message\n"
                "  try {\n"
                "    if ($started -and (Get-Process -Id $started.Id -ErrorAction SilentlyContinue)) {\n"
                "      Stop-Process -Id $started.Id -Force -ErrorAction SilentlyContinue\n"
                "      Wait-Process -Id $started.Id -Timeout 10 -ErrorAction SilentlyContinue\n"
                "    }\n"
                "    if (Test-Path -LiteralPath $backup) {\n"
                "      Remove-Item -LiteralPath $target -Force -ErrorAction SilentlyContinue\n"
                "      Move-Item -LiteralPath $backup -Destination $target -Force -ErrorAction Stop\n"
                "    }\n"
                "    if (-not (Get-Process -Id $pidToWait -ErrorAction SilentlyContinue) -and (Test-Path -LiteralPath $target)) {\n"
                "      Start-Process -FilePath $target -WorkingDirectory (Split-Path -Parent $target) -ErrorAction Stop\n"
                "    }\n"
                "  } catch { $failure = $failure + ' Rollback/relaunch error: ' + $_.Exception.Message }\n"
                "  $failure | Out-File -LiteralPath $log -Encoding utf8\n"
                "}\n"
                "Remove-Item -LiteralPath $replacement -Force -ErrorAction SilentlyContinue\n"
                "Remove-Item -LiteralPath $staged -Force -ErrorAction SilentlyContinue\n"
                "Remove-Item -LiteralPath $readyMarker -Force -ErrorAction SilentlyContinue\n"
                "Remove-Item -LiteralPath $PSCommandPath -Force -ErrorAction SilentlyContinue\n"
            )
            script_path.write_text(script, encoding="utf-8")
            flags = getattr(subprocess, "CREATE_NO_WINDOW", 0) | getattr(subprocess, "DETACHED_PROCESS", 0)
            helper_output = temp_dir / "btmouse-update-helper.log"
            with helper_output.open("wb") as output:
                helper = subprocess.Popen(
                    ["powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
                     "-File", str(script_path)], creationflags=flags,
                    stdin=subprocess.DEVNULL, stdout=output, stderr=subprocess.STDOUT)
            time.sleep(0.5)
            if helper.poll() is not None:
                detail = helper_output.read_text(encoding="utf-8", errors="replace").strip()
                raise RuntimeError(detail or f"The update helper exited early (code {helper.returncode}).")
            self.after(0, self._exit_for_companion_update)
        except Exception as exc:
            if staged_path and staged_path.exists():
                try:
                    staged_path.unlink()
                except OSError:
                    pass
            self.after(0, lambda error=str(exc): messagebox.showerror(
                "Companion update failed", f"Could not download or install the update.\n\n{error}"))
            self.after(0, lambda: self.btn_update.config(state="normal", text="Check for updates"))

    def _exit_for_companion_update(self):
        self.stop_server()
        if self.tray_icon:
            try:
                self.tray_icon.stop()
            except Exception:
                pass
        self.destroy()
        # Make the helper's PID wait definitive; destroying Tk alone can leave
        # the process alive while a non-daemon audio/device worker unwinds.
        sys.exit(0)

    def _on_adapter_selected(self, event):
        idx = self.combo_adapter.current()
        if 0 <= idx < len(self.adapters):
            name, ip = self.adapters[idx]
            self.current_ip = ip
            self.lbl_ip_val.config(text=ip)
            self.generate_qr()

    def generate_qr(self):
        if not HAS_QR:
            self.qr_label.config(text="QR library not found.\nInstall: pip install qrcode pillow", fg=ACCENT_RED)
            return

        hostname = socket.gethostname()
        payload = json.dumps({
            "type": "btmouse",
            "ip": self.current_ip,
            "port": self.current_port,
            "name": hostname
        })

        try:
            qr = qrcode.QRCode(
                version=1,
                error_correction=qrcode.constants.ERROR_CORRECT_M,
                box_size=6,
                border=2,
            )
            qr.add_data(payload)
            qr.make(fit=True)

            img = qr.make_image(fill_color="black", back_color="white")
            img = img.resize((210, 210), Image.Resampling.NEAREST)

            self.qr_photo = ImageTk.PhotoImage(img)
            self.qr_label.config(image=self.qr_photo)
        except Exception as e:
            self.qr_label.config(text=f"Error generating QR: {e}", fg=ACCENT_RED)

    def copy_ip(self):
        self.clipboard_clear()
        self.clipboard_append(self.current_ip)
        messagebox.showinfo("Copied", f"IP address '{self.current_ip}' copied to clipboard!")

    def copy_pairing_pin(self):
        self.clipboard_clear()
        self.clipboard_append(self.auth_token)
        messagebox.showinfo("Copied", "The Wi-Fi pairing PIN was copied to the clipboard.")

    def refresh_quick_apps(self):
        if not self.server:
            return
        self._quick_apps_refresh_generation = getattr(self, "_quick_apps_refresh_generation", 0) + 1
        generation = self._quick_apps_refresh_generation
        self.lbl_quick_apps_status.config(text="Loading installed apps…", fg=TEXT_MUTED)

        def _load():
            apps = self.server._cached_query("apps", 10.0, self.server._get_installed_apps)
            quick_apps = load_quick_apps(apps)

            def _update_ui():
                if not self.winfo_exists() or generation != self._quick_apps_refresh_generation:
                    return
                self.available_apps = apps
                self.quick_apps = quick_apps
                quick_paths = {item["path"].lower() for item in quick_apps}
                self.combo_available_apps["values"] = [
                    item["name"] for item in apps if item["path"].lower() not in quick_paths
                ]
                self.list_quick_apps.delete(0, tk.END)
                for app in quick_apps:
                    self.list_quick_apps.insert(tk.END, f"{app['name']}  —  {os.path.basename(app['path'])}")
                if not quick_apps:
                    self.lbl_quick_apps_status.config(text="No quick apps saved yet. Select an installed app above to add one.", fg=TEXT_MUTED)
                else:
                    self.lbl_quick_apps_status.config(text=f"{len(quick_apps)} quick app(s). Select one to rename or remove it.", fg=TEXT_MUTED)

            self.after(0, _update_ui)

        def _report_error(exc):
            def _update_ui():
                if not self.winfo_exists() or generation != self._quick_apps_refresh_generation:
                    return
                self.lbl_quick_apps_status.config(text=f"Could not load installed apps: {exc}", fg=ACCENT_RED)
            self.after(0, _update_ui)

        def _run():
            try:
                _load()
            except Exception as exc:
                _report_error(exc)

        threading.Thread(target=_run, name="QuickAppsRefresh", daemon=True).start()

    def _publish_quick_apps_to_server(self):
        if not self.server or not self.available_apps:
            return
        quick_paths = {os.path.realpath(item["path"]).lower() for item in self.quick_apps}
        apps = [dict(item, is_quick=os.path.realpath(item["path"]).lower() in quick_paths)
                for item in self.available_apps]
        with self.server._query_cache_lock:
            self.server._query_cache["apps"] = (time.monotonic(), apps)

    def add_quick_app(self):
        name = self.combo_available_apps.get().strip()
        match = next((app for app in self.available_apps if app["name"] == name), None)
        if not match:
            self.lbl_quick_apps_status.config(text="Choose an installed app from the list first.", fg=ACCENT_RED)
            return
        self.quick_apps = save_quick_apps(self.quick_apps + [{"name": match["name"], "path": match["path"]}])
        self._publish_quick_apps_to_server()
        self.refresh_quick_apps()
        self.lbl_quick_apps_status.config(text=f"Added {match['name']}.", fg=ACCENT_GREEN)

    def on_quick_app_selected(self, _event=None):
        selected = self.list_quick_apps.curselection()
        if selected:
            app = self.quick_apps[selected[0]]
            self.ent_quick_app_name.delete(0, tk.END)
            self.ent_quick_app_name.insert(0, app["name"])

    def rename_quick_app(self):
        selected = self.list_quick_apps.curselection()
        new_name = self.ent_quick_app_name.get().strip()
        if not selected or not new_name:
            self.lbl_quick_apps_status.config(text="Select an app and enter its new display name.", fg=ACCENT_RED)
            return
        self.quick_apps[selected[0]]["name"] = new_name[:60]
        self.quick_apps = save_quick_apps(self.quick_apps)
        self._publish_quick_apps_to_server()
        self.refresh_quick_apps()
        self.lbl_quick_apps_status.config(text="Quick app name saved.", fg=ACCENT_GREEN)

    def remove_quick_app(self):
        selected = self.list_quick_apps.curselection()
        if not selected:
            self.lbl_quick_apps_status.config(text="Select a quick app to remove it.", fg=ACCENT_RED)
            return
        name = self.quick_apps[selected[0]]["name"]
        self.quick_apps = save_quick_apps([app for i, app in enumerate(self.quick_apps) if i != selected[0]])
        self._publish_quick_apps_to_server()
        self.refresh_quick_apps()
        self.lbl_quick_apps_status.config(text=f"Removed {name} from Quick Apps.", fg=ACCENT_GREEN)

    def open_windows_bt_settings(self):
        try:
            os.system("start ms-settings:bluetooth")
        except Exception as e:
            messagebox.showerror("Error", f"Could not open Bluetooth settings: {e}")

    def open_paint(self):
        try:
            os.system("start mspaint")
        except Exception as e:
            messagebox.showerror("Error", f"Could not launch Paint: {e}")

    def log(self, message):
        timestamp = time.strftime("%H:%M:%S")
        self.txt_log.insert("end", f"[{timestamp}] {message}\n")
        self.txt_log.see("end")

    def _on_packet(self, cmd_type, addr, data):
        self.packet_count += 1
        if self.packet_count % 15 == 0:
            self.lbl_packets.config(text=f"Packets Received: {self.packet_count}")
        if cmd_type in ("connect", "disconnect", "text", "pen"):
            self.after(0, lambda: self.log(f"{cmd_type.upper()} from {addr[0] if addr else ''}"))

    def _on_client(self, client_name, is_connected):
        self.last_client = client_name if is_connected else "Disconnected"
        color = ACCENT_GREEN if is_connected else TEXT_MUTED
        text = f"Client: {client_name}" if is_connected else "Client: Disconnected"
        self.after(0, lambda: self.lbl_client.config(text=text, fg=color))

    def _on_status(self, is_running):
        self.is_server_running = is_running
        if is_running:
            self.after(0, lambda: self.lbl_status_pill.config(text="● SERVER LISTENING", fg=ACCENT_GREEN))
            self.after(0, lambda: self.btn_toggle.config(text="Stop", fg=ACCENT_RED))
        else:
            self.after(0, lambda: self.lbl_status_pill.config(text="○ SERVER STOPPED", fg=ACCENT_RED))
            self.after(0, lambda: self.btn_toggle.config(text="Start", fg=ACCENT_GREEN))

    def start_server(self):
        if self.server and self.server.running:
            return
        if not self.server:
            self._init_server()

        self.server_thread = threading.Thread(target=self.server.start, daemon=True)
        self.server_thread.start()
        self.log(f"Server starting on UDP port {self.current_port}...")

    def stop_server(self):
        if self.server:
            self.server.stop()
            self.log("Server stopped.")
            self._on_status(False)

    def toggle_server(self):
        if self.is_server_running:
            self.stop_server()
        else:
            self.start_server()

    def _update_auth_token(self):
        new_token = self.ent_token.get().strip()
        if new_token.isdigit() and len(new_token) == 6:
            try:
                save_pairing_pin(new_token)
            except OSError as e:
                messagebox.showerror("PIN Not Saved", f"Could not save the pairing PIN: {e}")
                return
            self.auth_token = new_token
            if hasattr(self, "lbl_wifi_pin"):
                self.lbl_wifi_pin.config(text=new_token)
            if self.server:
                self.server.auth_token = new_token
                if hasattr(self.server, "ws_server") and self.server.ws_server:
                    self.server.ws_server.auth_token = new_token
            self.generate_wan_qr()
            messagebox.showinfo("Pairing PIN Updated", "The new six-digit PIN is active for Wi-Fi and remote pairing.")
        else:
            messagebox.showerror("Invalid PIN", "Use exactly six digits for the pairing PIN.")

    def copy_wan_url(self):
        url = f"ws://{self.current_ip}:{self.ws_port}"
        self.clipboard_clear()
        self.clipboard_append(url)
        messagebox.showinfo("Copied", f"Local WebSocket URL '{url}' copied. For Internet access, use a TLS-protected wss:// endpoint.")

    def generate_wan_qr(self):
        if not HAS_QR:
            if hasattr(self, "wan_qr_label"):
                self.wan_qr_label.config(text="QR library not found.", fg=ACCENT_RED)
            return
        hostname = socket.gethostname()
        payload = json.dumps({
            "type": "btmouse_wan",
            "url": f"ws://{self.current_ip}:{self.ws_port}",
            "ip": self.current_ip,
            "port": self.ws_port,
            "name": hostname
        })
        try:
            qr = qrcode.QRCode(version=1, box_size=3, border=2)
            qr.add_data(payload)
            qr.make(fit=True)
            img = qr.make_image(fill_color="black", back_color="white")
            img = img.resize((190, 190), Image.Resampling.NEAREST)
            self.wan_qr_photo = ImageTk.PhotoImage(img)
            self.wan_qr_label.config(image=self.wan_qr_photo)
            if hasattr(self, "lbl_wan_url"):
                self.lbl_wan_url.config(text=f"ws://{self.current_ip}:{self.ws_port}")
        except Exception as e:
            if hasattr(self, "wan_qr_label"):
                self.wan_qr_label.config(text=f"Error: {e}", fg=ACCENT_RED)

    def test_camera_snapshot(self):
        if not self.server:
            return
        self.lbl_media_status.config(text="Capturing camera frame...", fg=ACCENT_CYAN)
        def _snap():
            b64 = self.server.camera_manager.get_snapshot()
            if b64:
                import io
                raw = base64.b64decode(b64)
                img = Image.open(io.BytesIO(raw))
                img = img.resize((240, 180), Image.Resampling.LANCZOS)
                photo = ImageTk.PhotoImage(img)
                def _update_ui():
                    self.cam_photo = photo
                    self.lbl_cam_preview.config(image=self.cam_photo)
                    self.lbl_media_status.config(text="✓ Camera snapshot captured successfully!", fg=ACCENT_GREEN)
                self.after(0, _update_ui)
            else:
                self.after(0, lambda: self.lbl_media_status.config(text="✕ Could not access camera.", fg=ACCENT_RED))
        threading.Thread(target=_snap, daemon=True).start()

    def test_mic_record(self):
        if not self.server:
            return
        self.lbl_media_status.config(text="Recording microphone for 3 seconds...", fg=ACCENT_CYAN)
        def _rec():
            rec_file = self.server.audio_manager.start_recording()
            if not rec_file:
                reason = self.server.audio_manager.last_error or "No microphone input is available."
                self.after(0, lambda: self.lbl_media_status.config(text=f"✕ {reason}", fg=ACCENT_RED))
                return
            time.sleep(3.0)
            final_file = self.server.audio_manager.stop_recording()
            if final_file and os.path.exists(final_file):
                sz = os.path.getsize(final_file)
                self.after(0, lambda: self.lbl_media_status.config(
                    text=f"✓ Recorded {os.path.basename(final_file)} ({sz} bytes)", fg=ACCENT_GREEN))
            else:
                reason = self.server.audio_manager.last_error or "Microphone recording stopped without a file."
                self.after(0, lambda: self.lbl_media_status.config(text=f"✕ {reason}", fg=ACCENT_RED))
        threading.Thread(target=_rec, daemon=True).start()

    def restart_server(self):
        self.log("Restarting server...")
        self.lbl_status_pill.config(text="● RESTARTING...", fg=ACCENT_CYAN)
        def _do_restart():
            if self.server:
                self.server.stop()
                if self.server_thread and self.server_thread.is_alive():
                    self.server_thread.join(timeout=1.0)
            time.sleep(0.3)
            self._init_server()
            self.server_thread = threading.Thread(target=self.server.start, daemon=True)
            self.server_thread.start()
            self.after(0, lambda: self.log("Server successfully restarted."))
        threading.Thread(target=_do_restart, daemon=True).start()

    def quit_application(self):
        if self.tray_icon:
            try:
                self.tray_icon.stop()
            except Exception:
                pass
        if self.server:
            self.server.stop()
        self.destroy()
        sys.exit(0)

if __name__ == "__main__":
    set_windows_dpi_awareness()
    app = CompanionApp()
    app.mainloop()
