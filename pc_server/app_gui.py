"""
BT Mouse & Keyboard - PC Companion Application
Modern Windows 11 Fluent-Styled Desktop GUI with DWM Dark Titlebar,
Sidebar Navigation, System Tray integration, instant QR Code pairing,
and zero heavy third-party UI dependencies.
"""

try:
    import tkinter as tk
    from tkinter import ttk, messagebox
    HAS_TK = True
except ImportError:
    HAS_TK = False

    class _DummyTkModule:
        Tk = object
        Toplevel = object
        Frame = object
        Label = object
        Button = object
        Entry = object
        Text = object
        Canvas = object
        Listbox = object
        Checkbutton = object
        LabelFrame = object
        Menu = object
        BooleanVar = object
        StringVar = object
        END = "end"

    tk = _DummyTkModule()
    ttk = None
    messagebox = None

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
from datetime import datetime
import shutil
import webbrowser

# Prevent crash when running as a windowed GUI application without a console attached
if sys.stdout is None:
    sys.stdout = open(os.devnull, "w")
if sys.stderr is None:
    sys.stderr = open(os.devnull, "w")


def get_asset_path(filename):
    if hasattr(sys, "_MEIPASS"):
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

from server import (
    Server,
    ensure_interactive_desktop,
    IS_WINDOWS,
    load_or_create_pairing_pin,
    save_pairing_pin,
    load_quick_apps,
    save_quick_apps,
    load_media_preview_region,
    save_media_preview_region,
)

APP_VERSION = "1.3.16"
GITHUB_REPOSITORY = "yaminichandrakj/bt-mouse"
MAX_COMPANION_UPDATE_BYTES = 150 * 1024 * 1024
COMPANION_UPDATE_ERROR_LOG = Path(tempfile.gettempdir()) / "btmouse-update-error.log"


def set_windows_dpi_awareness():
    if not IS_WINDOWS:
        return
    try:
        import ctypes
        # Per-Monitor V2 DPI awareness context (-4)
        ctypes.windll.user32.SetProcessDpiAwarenessContext(ctypes.c_void_p(-4))
    except Exception:
        try:
            import ctypes
            # Per-Monitor DPI aware fallback
            ctypes.windll.shcore.SetProcessDpiAwareness(2)
        except Exception:
            try:
                import ctypes
                # System DPI aware fallback
                ctypes.windll.user32.SetProcessDPIAware()
            except Exception:
                pass


def get_windows_dpi_scale():
    if not IS_WINDOWS:
        return 1.0, 96
    try:
        import ctypes
        if hasattr(ctypes.windll.user32, "GetDpiForSystem"):
            dpi = ctypes.windll.user32.GetDpiForSystem()
            if dpi and dpi > 0:
                return dpi / 96.0, dpi
        hdc = ctypes.windll.user32.GetDC(0)
        if hdc:
            LOGPIXELSX = 88
            dpi = ctypes.windll.gdi32.GetDeviceCaps(hdc, LOGPIXELSX)
            ctypes.windll.user32.ReleaseDC(0, hdc)
            if dpi and dpi > 0:
                return dpi / 96.0, dpi
    except Exception:
        pass
    return 1.0, 96


def apply_windows_dwm_styling(widget):
    """Apply native Windows 11 DWM immersive dark titlebar, rounded corners, and caption colors."""
    if not IS_WINDOWS:
        return
    try:
        import ctypes
        hwnd = ctypes.windll.user32.GetParent(widget.winfo_id()) or widget.winfo_id()
        dwmapi = ctypes.windll.dwmapi

        # DWMWA_USE_IMMERSIVE_DARK_MODE = 20 (fallback 19 on older Win10 builds)
        dark_mode = ctypes.c_int(1)
        if dwmapi.DwmSetWindowAttribute(hwnd, 20, ctypes.byref(dark_mode), ctypes.sizeof(dark_mode)) != 0:
            dwmapi.DwmSetWindowAttribute(hwnd, 19, ctypes.byref(dark_mode), ctypes.sizeof(dark_mode))

        # DWMWA_WINDOW_CORNER_PREFERENCE = 33, DWMWCP_ROUND = 2
        corner_pref = ctypes.c_int(2)
        dwmapi.DwmSetWindowAttribute(hwnd, 33, ctypes.byref(corner_pref), ctypes.sizeof(corner_pref))

        # DWMWA_CAPTION_COLOR = 35 (COLORREF 0x00BBGGRR matching #111622)
        caption_color = ctypes.c_int(0x00221611)
        dwmapi.DwmSetWindowAttribute(hwnd, 35, ctypes.byref(caption_color), ctypes.sizeof(caption_color))

        # DWMWA_TEXT_COLOR = 36 (COLORREF 0x00BBGGRR matching #F8FAFC)
        text_color = ctypes.c_int(0x00FCF8F8)
        dwmapi.DwmSetWindowAttribute(hwnd, 36, ctypes.byref(text_color), ctypes.sizeof(text_color))
    except Exception:
        pass


# Windows 11 Fluent Dark Palette
BG_DARK = "#0B0F17"
SIDEBAR_BG = "#111622"
SIDEBAR_HOVER = "#1A2234"
SIDEBAR_ACTIVE = "#1E293B"
CARD_BG = "#151C28"
CARD_SURFACE = "#1C2536"
CARD_BORDER = "#263248"
ACCENT_CYAN = "#38BDF8"
ACCENT_CYAN_HOVER = "#0EA5E9"
ACCENT_INDIGO = "#6366F1"
ACCENT_INDIGO_HOVER = "#4F46E5"
ACCENT_GREEN = "#10B981"
ACCENT_RED = "#F43F5E"
ACCENT_AMBER = "#F59E0B"
TEXT_PRIMARY = "#F8FAFC"
TEXT_SECONDARY = "#CBD5E1"
TEXT_MUTED = "#94A3B8"
BUTTON_BG = "#1E293B"
BUTTON_HOVER = "#2B3A55"

UI_FONT = "Segoe UI"


class _NotebookCompatibilityShim:
    """Provides .select(tab_frame) compatibility while using a modern Fluent sidebar stack."""

    def __init__(self, app):
        self._app = app
        self._tabs = []

    def add(self, frame, text=""):
        self._tabs.append((frame, text))

    def select(self, target_frame):
        self._app._select_page(target_frame)


class CompanionApp(tk.Tk):
    def __init__(self):
        super().__init__()
        ensure_interactive_desktop()

        self.title("BT Mouse & Keyboard — Windows Companion")

        # Calibrate window geometry for high-DPI displays without double-scaling fonts
        self.dpi_scale, self.system_dpi = get_windows_dpi_scale()
        effective_scale = min(max(self.dpi_scale, 1.0), 1.65)

        base_w = int(980 * effective_scale)
        base_h = int(760 * effective_scale)
        min_w = int(860 * effective_scale)
        min_h = int(660 * effective_scale)

        # Center window on primary monitor
        screen_w = self.winfo_screenwidth()
        screen_h = self.winfo_screenheight()
        pos_x = max(0, (screen_w - base_w) // 2)
        pos_y = max(0, (screen_h - base_h) // 2)

        self.geometry(f"{base_w}x{base_h}+{pos_x}+{pos_y}")
        self.minsize(min_w, min_h)
        self.configure(bg=BG_DARK)

        # Apply Windows 11 native DWM dark titlebar & rounded window corners
        self.update_idletasks()
        apply_windows_dwm_styling(self)

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

        # Build Fluent Desktop Shell
        self._build_header()
        self._build_notebook()
        self._build_statusbar()

        executable_path = Path(sys.executable).resolve()
        self.title(f"BT Mouse Companion v{APP_VERSION} — {executable_path}")
        self.after(500, self._show_update_recovery_notice)
        self.after(3000, self._refresh_recordings_loop)
        self.phone_cam_photo = None
        self.phone_cam_pending_update = False
        if hasattr(self.server, "phone_camera_receiver") and self.server.phone_camera_receiver:
            self.server.phone_camera_receiver.register_callback(self._on_phone_cam_frame_received)
        self.after(2000, self._check_phone_cam_active)

        # System Tray setup
        self.tray_icon = None
        self._setup_system_tray()

        # Start server automatically
        self.start_server()

        # Handle window close: minimize to tray instead of quitting
        self.protocol("WM_DELETE_WINDOW", self.hide_to_tray)
        self._write_update_ready_marker()
        self._heal_startup_path_if_registered()
        if "--minimized" in sys.argv:
            self.after(700, self.hide_to_tray if self.tray_icon else self.iconify)

    @staticmethod
    def _bind_hover(widget, normal_bg, hover_bg, normal_fg=None, hover_fg=None):
        """Attach smooth mouse-hover color transitions to a Tkinter widget."""
        def _on_enter(_event):
            try:
                if str(widget.cget("state")) != "disabled":
                    widget.configure(bg=hover_bg)
                    if hover_fg:
                        widget.configure(fg=hover_fg)
            except Exception:
                pass

        def _on_leave(_event):
            try:
                if str(widget.cget("state")) != "disabled":
                    widget.configure(bg=normal_bg)
                    if normal_fg:
                        widget.configure(fg=normal_fg)
            except Exception:
                pass

        widget.bind("<Enter>", _on_enter, add="+")
        widget.bind("<Leave>", _on_leave, add="+")

    def _make_button(self, parent, text, command, bg=BUTTON_BG, fg=TEXT_PRIMARY,
                     hover_bg=BUTTON_HOVER, font_size=9, bold=True, padx=12, pady=6):
        weight = "bold" if bold else "normal"
        btn = tk.Button(
            parent,
            text=text,
            font=(UI_FONT, font_size, weight),
            bg=bg,
            fg=fg,
            activebackground=hover_bg,
            activeforeground=TEXT_PRIMARY,
            disabledforeground=TEXT_MUTED,
            relief="flat",
            bd=0,
            highlightthickness=1,
            highlightbackground=CARD_BORDER,
            highlightcolor=ACCENT_CYAN,
            padx=padx,
            pady=pady,
            command=command,
            cursor="hand2",
        )
        self._bind_hover(btn, bg, hover_bg, fg, TEXT_PRIMARY if bg in (ACCENT_INDIGO, ACCENT_CYAN) else fg)
        return btn

    def _make_card(self, parent, padx=18, pady=16, bg=CARD_BG):
        return tk.Frame(
            parent,
            bg=bg,
            padx=padx,
            pady=pady,
            relief="flat",
            bd=0,
            highlightbackground=CARD_BORDER,
            highlightthickness=1,
        )

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

    def _heal_startup_path_if_registered(self):
        """If startup is enabled in the registry, ensure the registered command matches current binary and args."""
        if os.name != "nt":
            return
        reg_val = self._startup_registry_value()
        if not reg_val:
            return
        expected = self._startup_command()
        if reg_val.strip() != expected.strip():
            try:
                import winreg
                run_key = r"Software\Microsoft\Windows\CurrentVersion\Run"
                with winreg.CreateKeyEx(winreg.HKEY_CURRENT_USER, run_key, 0, winreg.KEY_SET_VALUE) as key:
                    winreg.SetValueEx(key, "BT Mouse Companion", 0, winreg.REG_SZ, expected)
                self.log(f"Self-healed Windows startup path to: {expected}")
            except (OSError, ImportError) as exc:
                self.log(f"Warning: could not update Windows startup path: {exc}")

    def _init_server(self):
        """Initializes a clean Server instance with bound callbacks"""
        self.server = Server(port=self.current_port, ws_port=self.ws_port, auth_token=self.auth_token)
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

        # Combobox & Spinbox dark theme
        self.option_add("*TCombobox*Listbox.background", CARD_SURFACE)
        self.option_add("*TCombobox*Listbox.foreground", TEXT_PRIMARY)
        self.option_add("*TCombobox*Listbox.selectBackground", ACCENT_INDIGO)
        self.option_add("*TCombobox*Listbox.selectForeground", TEXT_PRIMARY)
        self.option_add("*TCombobox*Listbox.font", (UI_FONT, 9))

        style.configure(
            "TCombobox",
            fieldbackground=BUTTON_BG,
            background=CARD_SURFACE,
            foreground=TEXT_PRIMARY,
            arrowcolor=ACCENT_CYAN,
            bordercolor=CARD_BORDER,
            lightcolor=CARD_BORDER,
            darkcolor=CARD_BORDER,
            padding=5,
        )
        style.map(
            "TCombobox",
            fieldbackground=[("readonly", BUTTON_BG)],
            selectbackground=[("readonly", BUTTON_BG)],
            selectforeground=[("readonly", TEXT_PRIMARY)],
            foreground=[("readonly", TEXT_PRIMARY)],
        )

        style.configure(
            "TSpinbox",
            fieldbackground=BUTTON_BG,
            background=CARD_SURFACE,
            foreground=TEXT_PRIMARY,
            arrowcolor=ACCENT_CYAN,
            bordercolor=CARD_BORDER,
            padding=4,
        )

        style.configure(
            "Vertical.TScrollbar",
            background=CARD_SURFACE,
            troughcolor=BG_DARK,
            bordercolor=BG_DARK,
            arrowcolor=TEXT_MUTED,
            relief="flat",
        )
        style.map("Vertical.TScrollbar", background=[("active", CARD_BORDER)])

        # Treeview styling for recordings
        style.configure(
            "Recordings.Treeview",
            background=CARD_BG,
            foreground=TEXT_PRIMARY,
            fieldbackground=CARD_BG,
            rowheight=32,
            font=(UI_FONT, 9),
            borderwidth=0,
        )
        style.configure(
            "Recordings.Treeview.Heading",
            background=SIDEBAR_BG,
            foreground=ACCENT_CYAN,
            font=(UI_FONT, 9, "bold"),
            relief="flat",
            padding=[10, 8],
        )
        style.map(
            "Recordings.Treeview",
            background=[("selected", ACCENT_INDIGO)],
            foreground=[("selected", TEXT_PRIMARY)],
        )
        style.map(
            "Recordings.Treeview.Heading",
            background=[("active", CARD_SURFACE)],
            foreground=[("active", ACCENT_CYAN)],
        )

    def _setup_system_tray(self):
        if not HAS_TRAY:
            return

        if os.path.exists(self.icon_png_path):
            tray_img = Image.open(self.icon_png_path)
        else:
            tray_img = Image.new("RGBA", (64, 64), color=(14, 17, 24))

        menu = (
            item("BT Mouse Companion", lambda: None, enabled=False),
            item(lambda text: f"Status: {'Running' if self.is_server_running else 'Stopped'}", lambda: None, enabled=False),
            item("Show Window", self.show_from_tray, default=True),
            item("Open Recordings Folder", self.open_recordings_folder),
            item("Restart Server", self.restart_server),
            item("Start Server", self.start_server),
            item("Stop Server", self.stop_server),
            item("Copy Wi-Fi IP", self.copy_ip),
            item("Bluetooth Settings", self.open_windows_bt_settings),
            item("Exit Application", self.quit_application),
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
        header_frame = tk.Frame(
            self,
            bg=SIDEBAR_BG,
            padx=20,
            pady=12,
            highlightbackground=CARD_BORDER,
            highlightthickness=1,
        )
        header_frame.pack(fill="x", side="top")

        # Left Brand & Version Info
        brand_box = tk.Frame(header_frame, bg=SIDEBAR_BG)
        brand_box.pack(side="left", fill="y")

        title_row = tk.Frame(brand_box, bg=SIDEBAR_BG)
        title_row.pack(anchor="w")

        lbl_badge = tk.Label(
            title_row,
            text="🖱️",
            font=(UI_FONT, 15),
            bg=SIDEBAR_BG,
            fg=ACCENT_CYAN,
        )
        lbl_badge.pack(side="left", padx=(0, 8))

        lbl_title = tk.Label(
            title_row,
            text="BT Mouse & Keyboard",
            font=(UI_FONT, 14, "bold"),
            bg=SIDEBAR_BG,
            fg=TEXT_PRIMARY,
        )
        lbl_title.pack(side="left")

        lbl_ver = tk.Label(
            title_row,
            text=f"v{APP_VERSION}",
            font=(UI_FONT, 8, "bold"),
            bg=CARD_SURFACE,
            fg=ACCENT_CYAN,
            padx=7,
            pady=2,
        )
        lbl_ver.pack(side="left", padx=(10, 0))

        lbl_sub = tk.Label(
            brand_box,
            text="Windows Desktop Companion · Low-Latency Wi-Fi, Bluetooth & Remote WAN Server",
            font=(UI_FONT, 8),
            bg=SIDEBAR_BG,
            fg=TEXT_MUTED,
        )
        lbl_sub.pack(anchor="w", pady=(2, 0))

        # Right Status Pill & Action Controls
        right_box = tk.Frame(header_frame, bg=SIDEBAR_BG)
        right_box.pack(side="right", fill="y")

        self.lbl_status_pill = tk.Label(
            right_box,
            text="● SERVER READY",
            font=(UI_FONT, 9, "bold"),
            bg=CARD_SURFACE,
            fg=ACCENT_GREEN,
            padx=12,
            pady=6,
        )
        self.lbl_status_pill.pack(side="left", padx=(0, 8))

        self.btn_toggle = self._make_button(
            right_box, "Stop Server", self.toggle_server, bg=BUTTON_BG, fg=ACCENT_RED, padx=11, pady=5
        )
        self.btn_toggle.pack(side="left", padx=(0, 6))

        self.btn_restart = self._make_button(
            right_box, "Restart", self.restart_server, bg=BUTTON_BG, fg=ACCENT_CYAN, padx=11, pady=5
        )
        self.btn_restart.pack(side="left", padx=(0, 6))

        self.btn_update = self._make_button(
            right_box, "Check for Updates", self.check_for_companion_updates, bg=ACCENT_INDIGO,
            fg=TEXT_PRIMARY, hover_bg=ACCENT_INDIGO_HOVER, padx=12, pady=5
        )
        self.btn_update.pack(side="left")

    def _build_notebook(self):
        """Builds a modern Windows 11 Fluent left navigation rail + main page container."""
        self.notebook = _NotebookCompatibilityShim(self)

        body = tk.Frame(self, bg=BG_DARK)
        body.pack(fill="both", expand=True)

        # Left Navigation Sidebar
        self.sidebar = tk.Frame(
            body,
            bg=SIDEBAR_BG,
            width=225,
            padx=10,
            pady=12,
            highlightbackground=CARD_BORDER,
            highlightthickness=1,
        )
        self.sidebar.pack(side="left", fill="y")
        self.sidebar.pack_propagate(False)

        tk.Label(
            self.sidebar,
            text="NAVIGATION",
            font=(UI_FONT, 8, "bold"),
            bg=SIDEBAR_BG,
            fg=TEXT_MUTED,
            padx=8,
        ).pack(anchor="w", pady=(0, 6))

        self.nav_Container = tk.Frame(self.sidebar, bg=SIDEBAR_BG)
        self.nav_Container.pack(fill="x")

        # Main Page Stack Area
        self.page_host = tk.Frame(body, bg=BG_DARK, padx=16, pady=14)
        self.page_host.pack(side="left", fill="both", expand=True)

        self._nav_items = {}
        self._active_page = None

        def _create_page(icon_text, title):
            page = tk.Frame(self.page_host, bg=BG_DARK)
            self.notebook.add(page, text=f"{icon_text} {title}")

            nav_row = tk.Frame(self.nav_Container, bg=SIDEBAR_BG, cursor="hand2")
            nav_row.pack(fill="x", pady=2)

            indicator = tk.Frame(nav_row, bg=SIDEBAR_BG, width=4, height=34)
            indicator.pack(side="left", fill="y")

            btn_lbl = tk.Label(
                nav_row,
                text=f"  {icon_text}   {title}",
                font=(UI_FONT, 9, "bold"),
                bg=SIDEBAR_BG,
                fg=TEXT_SECONDARY,
                anchor="w",
                padx=8,
                pady=8,
                cursor="hand2",
            )
            btn_lbl.pack(side="left", fill="x", expand=True)

            def _on_click(_e=None, target=page):
                self._select_page(target)

            def _on_enter(_e=None, target=page):
                if self._active_page is not target:
                    nav_row.configure(bg=SIDEBAR_HOVER)
                    btn_lbl.configure(bg=SIDEBAR_HOVER, fg=TEXT_PRIMARY)

            def _on_leave(_e=None, target=page):
                if self._active_page is not target:
                    nav_row.configure(bg=SIDEBAR_BG)
                    btn_lbl.configure(bg=SIDEBAR_BG, fg=TEXT_SECONDARY)

            for w in (nav_row, indicator, btn_lbl):
                w.bind("<Button-1>", _on_click)
                w.bind("<Enter>", _on_enter)
                w.bind("<Leave>", _on_leave)

            self._nav_items[page] = (nav_row, indicator, btn_lbl)
            return page

        self.tab_wifi = _create_page("📱", "Wi-Fi & QR Pairing")
        self.tab_bt = _create_page("📶", "Bluetooth Mode")
        self.tab_wan = _create_page("🌐", "Remote WAN")
        self.tab_desktop = _create_page("🚀", "Desktop & Controls")
        self.tab_recordings = _create_page("🎬", "Recordings Library")
        self.tab_phone_cam = _create_page("📷", "Phone as Webcam")
        self.tab_tablet = _create_page("✍️", "Tablet Writing Pad")
        self.tab_logs = _create_page("📊", "Live Monitor")

        # Sidebar Footer Quick-Status Card
        sidebar_footer = tk.Frame(
            self.sidebar,
            bg=CARD_BG,
            padx=12,
            pady=10,
            highlightbackground=CARD_BORDER,
            highlightthickness=1,
        )
        sidebar_footer.pack(side="bottom", fill="x", pady=(8, 0))

        tk.Label(
            sidebar_footer,
            text="QUICK PAIRING INFO",
            font=(UI_FONT, 7, "bold"),
            bg=CARD_BG,
            fg=TEXT_MUTED,
        ).pack(anchor="w")

        self.lbl_sidebar_ip = tk.Label(
            sidebar_footer,
            text=f"IP: {self.current_ip}:{self.current_port}",
            font=("Consolas", 9, "bold"),
            bg=CARD_BG,
            fg=ACCENT_CYAN,
        )
        self.lbl_sidebar_ip.pack(anchor="w", pady=(3, 1))

        self.lbl_sidebar_pin = tk.Label(
            sidebar_footer,
            text=f"PIN: {self.auth_token}",
            font=("Consolas", 10, "bold"),
            bg=CARD_BG,
            fg=ACCENT_GREEN,
        )
        self.lbl_sidebar_pin.pack(anchor="w")

        # Populate all 8 pages
        self._build_wifi_tab()
        self._build_bt_tab()
        self._build_wan_tab()
        self._build_desktop_tab()
        self._build_recordings_tab()
        self._build_phone_cam_tab()
        self._build_tablet_tab()
        self._build_logs_tab()

        # Show initial page
        self._select_page(self.tab_wifi)

    def _select_page(self, target_page):
        if self._active_page is target_page:
            return
        if self._active_page is not None:
            self._active_page.pack_forget()
        self._active_page = target_page
        target_page.pack(fill="both", expand=True)

        for page, (nav_row, indicator, btn_lbl) in self._nav_items.items():
            if page is target_page:
                nav_row.configure(bg=SIDEBAR_ACTIVE)
                indicator.configure(bg=ACCENT_CYAN)
                btn_lbl.configure(bg=SIDEBAR_ACTIVE, fg=TEXT_PRIMARY)
            else:
                nav_row.configure(bg=SIDEBAR_BG)
                indicator.configure(bg=SIDEBAR_BG)
                btn_lbl.configure(bg=SIDEBAR_BG, fg=TEXT_SECONDARY)

    def _build_wifi_tab(self):
        container = tk.Frame(self.tab_wifi, bg=BG_DARK)
        container.pack(fill="both", expand=True)

        # Left Card: Instant QR Scanner
        qr_card = self._make_card(container, padx=22, pady=20)
        qr_card.pack(side="left", fill="both", expand=True, padx=(0, 8))

        tk.Label(
            qr_card,
            text="Instant Wi-Fi Pairing",
            font=(UI_FONT, 14, "bold"),
            bg=CARD_BG,
            fg=TEXT_PRIMARY,
        ).pack(anchor="center", pady=(0, 4))

        tk.Label(
            qr_card,
            text="Scan with the BT Mouse Android app — IP, port, and PIN auto-fill automatically.",
            font=(UI_FONT, 9),
            bg=CARD_BG,
            fg=TEXT_MUTED,
            wraplength=320,
            justify="center",
        ).pack(anchor="center", pady=(0, 14))

        qr_frame = tk.Frame(qr_card, bg="#FFFFFF", padx=10, pady=10, relief="flat", bd=0)
        qr_frame.pack(anchor="center", pady=6)

        self.qr_label = tk.Label(qr_frame, bg="#FFFFFF")
        self.qr_label.pack(anchor="center")

        tk.Label(
            qr_card,
            text="Phone App  ➔  Connect  ➔  Wi-Fi  ➔  Scan QR Code",
            font=(UI_FONT, 9, "bold"),
            bg=CARD_BG,
            fg=ACCENT_CYAN,
        ).pack(anchor="center", pady=(14, 4))

        tk.Label(
            qr_card,
            text="Ensure your phone and PC are connected to the same Wi-Fi network or hotspot.",
            font=(UI_FONT, 8),
            bg=CARD_BG,
            fg=TEXT_MUTED,
            wraplength=300,
            justify="center",
        ).pack(anchor="center")

        # Right Card: Connection Details & Pairing PIN
        info_card = self._make_card(container, padx=22, pady=20)
        info_card.pack(side="right", fill="both", expand=True, padx=(8, 0))

        tk.Label(
            info_card,
            text="Server Network & Security",
            font=(UI_FONT, 14, "bold"),
            bg=CARD_BG,
            fg=TEXT_PRIMARY,
        ).pack(anchor="w", pady=(0, 12))

        tk.Label(
            info_card,
            text="Network Adapter / Interface:",
            font=(UI_FONT, 9, "bold"),
            bg=CARD_BG,
            fg=TEXT_MUTED,
        ).pack(anchor="w", pady=(2, 4))

        adapter_names = [f"{name}: {ip}" for name, ip in self.adapters]
        self.combo_adapter = ttk.Combobox(info_card, values=adapter_names, state="readonly", font=(UI_FONT, 9))
        if adapter_names:
            self.combo_adapter.current(0)
        self.combo_adapter.pack(fill="x", pady=(0, 14))
        self.combo_adapter.bind("<<ComboboxSelected>>", self._on_adapter_selected)

        metrics_grid = tk.Frame(info_card, bg=CARD_BG)
        metrics_grid.pack(fill="x", pady=(0, 14))

        ip_box = tk.Frame(
            metrics_grid, bg=CARD_SURFACE, padx=14, pady=10, highlightbackground=CARD_BORDER, highlightthickness=1
        )
        ip_box.pack(side="left", fill="both", expand=True, padx=(0, 6))

        tk.Label(ip_box, text="LOCAL IP ADDRESS", font=(UI_FONT, 8, "bold"), bg=CARD_SURFACE, fg=TEXT_MUTED).pack(
            anchor="w"
        )
        self.lbl_ip_val = tk.Label(
            ip_box, text=self.current_ip, font=("Consolas", 14, "bold"), bg=CARD_SURFACE, fg=ACCENT_CYAN
        )
        self.lbl_ip_val.pack(anchor="w", pady=(2, 0))

        port_box = tk.Frame(
            metrics_grid, bg=CARD_SURFACE, padx=14, pady=10, highlightbackground=CARD_BORDER, highlightthickness=1
        )
        port_box.pack(side="right", fill="both", padx=(6, 0))

        tk.Label(port_box, text="UDP PORT", font=(UI_FONT, 8, "bold"), bg=CARD_SURFACE, fg=TEXT_MUTED).pack(anchor="w")
        self.lbl_port_val = tk.Label(
            port_box, text=str(self.current_port), font=("Consolas", 14, "bold"), bg=CARD_SURFACE, fg=TEXT_PRIMARY
        )
        self.lbl_port_val.pack(anchor="w", pady=(2, 0))

        pin_box = tk.Frame(
            info_card, bg=CARD_SURFACE, padx=14, pady=12, highlightbackground=CARD_BORDER, highlightthickness=1
        )
        pin_box.pack(fill="x", pady=(0, 16))

        tk.Label(
            pin_box,
            text="6-DIGIT PAIRING PIN (INCLUDED IN QR CODE)",
            font=(UI_FONT, 8, "bold"),
            bg=CARD_SURFACE,
            fg=TEXT_MUTED,
        ).pack(anchor="w")

        self.lbl_wifi_pin = tk.Label(
            pin_box,
            text=self.auth_token,
            font=("Consolas", 20, "bold"),
            bg=CARD_SURFACE,
            fg=ACCENT_GREEN,
        )
        self.lbl_wifi_pin.pack(anchor="w", pady=(4, 0))

        btn_copy = self._make_button(
            info_card, "📋  Copy IP Address", self.copy_ip, bg=BUTTON_BG, fg=TEXT_PRIMARY, pady=8
        )
        btn_copy.pack(fill="x", pady=(0, 8))

        btn_copy_pin = self._make_button(
            info_card, "🔑  Copy Pairing PIN", self.copy_pairing_pin, bg=BUTTON_BG, fg=TEXT_PRIMARY, pady=8
        )
        btn_copy_pin.pack(fill="x", pady=(0, 8))

        btn_refresh = self._make_button(
            info_card,
            "🔄  Refresh QR Code",
            self.generate_qr,
            bg=ACCENT_INDIGO,
            fg=TEXT_PRIMARY,
            hover_bg=ACCENT_INDIGO_HOVER,
            pady=8,
        )
        btn_refresh.pack(fill="x")

        self.generate_qr()

    def _build_bt_tab(self):
        card = self._make_card(self.tab_bt, padx=24, pady=22)
        card.pack(fill="both", expand=True)

        tk.Label(
            card,
            text="Bluetooth RFCOMM & HID Hardware Mode",
            font=(UI_FONT, 14, "bold"),
            bg=CARD_BG,
            fg=TEXT_PRIMARY,
        ).pack(anchor="w", pady=(0, 6))

        desc = (
            "Your Android phone supports two Bluetooth modes simultaneously:\n"
            "• Bluetooth RFCOMM (Companion Link): Full media remote, system stats, live audio, and webcam streaming.\n"
            "• Bluetooth HID (Hardware Emulation): Zero-driver hardware mouse & keyboard that works even on BIOS or lock screens."
        )
        tk.Label(
            card,
            text=desc,
            font=(UI_FONT, 9),
            justify="left",
            bg=CARD_BG,
            fg=TEXT_SECONDARY,
            wraplength=660,
        ).pack(anchor="w", pady=(0, 18))

        btn_win_bt = self._make_button(
            card,
            "⚙️  Open Windows Bluetooth Settings",
            self.open_windows_bt_settings,
            bg=ACCENT_INDIGO,
            fg=TEXT_PRIMARY,
            hover_bg=ACCENT_INDIGO_HOVER,
            font_size=10,
            padx=16,
            pady=9,
        )
        btn_win_bt.pack(anchor="w", pady=(0, 18))

        trouble_box = tk.Frame(
            card, bg=CARD_SURFACE, padx=18, pady=16, highlightbackground=CARD_BORDER, highlightthickness=1
        )
        trouble_box.pack(fill="x", pady=6)

        tk.Label(
            trouble_box,
            text="Quick Pairing & Troubleshooting Checklist",
            font=(UI_FONT, 10, "bold"),
            bg=CARD_SURFACE,
            fg=ACCENT_CYAN,
        ).pack(anchor="w", pady=(0, 8))

        steps = (
            "1. Open Windows Bluetooth Settings and pair your Android phone with this PC.\n"
            "2. In the BT Mouse Android app, open the Connect tab and select the Bluetooth section.\n"
            "3. Tap 'Refresh' to list your paired PC, then tap 'Connect' next to your PC's name.\n"
            "4. If switching from an older pairing, remove the device on both Windows and Android, then pair fresh."
        )
        tk.Label(
            trouble_box,
            text=steps,
            font=(UI_FONT, 9),
            justify="left",
            bg=CARD_SURFACE,
            fg=TEXT_PRIMARY,
        ).pack(anchor="w")

    def _build_tablet_tab(self):
        card = self._make_card(self.tab_tablet, padx=24, pady=22)
        card.pack(fill="both", expand=True)

        tk.Label(
            card,
            text="Tablet Writing & Graphics Drawing Slate",
            font=(UI_FONT, 14, "bold"),
            bg=CARD_BG,
            fg=TEXT_PRIMARY,
        ).pack(anchor="w", pady=(0, 6))

        desc = (
            "Turn any Android phone or tablet into a low-latency graphics drawing slate:\n\n"
            "• 1:1 Absolute Screen Mapping: Maps your mobile surface proportionally to your primary monitor.\n"
            "• S-Pen & Active Stylus Support: Translates stylus pressure and hover actions smoothly.\n"
            "• Works with OneNote, MS Paint, Photoshop, Krita, Blender, and whiteboard apps."
        )
        tk.Label(
            card,
            text=desc,
            font=(UI_FONT, 9),
            justify="left",
            bg=CARD_BG,
            fg=TEXT_SECONDARY,
            wraplength=660,
        ).pack(anchor="w", pady=(0, 16))

        metrics_frame = tk.Frame(
            card, bg=CARD_SURFACE, padx=16, pady=12, highlightbackground=CARD_BORDER, highlightthickness=1
        )
        metrics_frame.pack(anchor="w", fill="x", pady=(0, 16))

        sw, sh = 1920, 1080
        if IS_WINDOWS:
            try:
                import ctypes
                sw = ctypes.windll.user32.GetSystemMetrics(0)
                sh = ctypes.windll.user32.GetSystemMetrics(1)
            except Exception:
                pass

        tk.Label(
            metrics_frame,
            text="Detected Primary Monitor Resolution:",
            font=(UI_FONT, 9, "bold"),
            bg=CARD_SURFACE,
            fg=TEXT_MUTED,
        ).pack(side="left")

        tk.Label(
            metrics_frame,
            text=f"{sw} × {sh} px",
            font=("Consolas", 12, "bold"),
            bg=CARD_SURFACE,
            fg=ACCENT_CYAN,
            padx=10,
        ).pack(side="left")

        btn_open_paint = self._make_button(
            card,
            "🎨  Open MS Paint for Drawing Test",
            self.open_paint,
            bg=ACCENT_INDIGO,
            fg=TEXT_PRIMARY,
            hover_bg=ACCENT_INDIGO_HOVER,
            font_size=10,
            padx=16,
            pady=9,
        )
        btn_open_paint.pack(anchor="w")

    def _build_wan_tab(self):
        container = tk.Frame(self.tab_wan, bg=BG_DARK)
        container.pack(fill="both", expand=True)

        qr_card = self._make_card(container, padx=22, pady=20)
        qr_card.pack(side="left", fill="both", expand=True, padx=(0, 8))

        tk.Label(
            qr_card,
            text="Remote WAN / WebSocket Pairing",
            font=(UI_FONT, 14, "bold"),
            bg=CARD_BG,
            fg=TEXT_PRIMARY,
        ).pack(anchor="center", pady=(0, 4))

        tk.Label(
            qr_card,
            text="Scan to auto-configure the WebSocket URL and 6-digit security PIN on your phone.",
            font=(UI_FONT, 9),
            bg=CARD_BG,
            fg=TEXT_MUTED,
            wraplength=310,
            justify="center",
        ).pack(anchor="center", pady=(0, 14))

        wan_qr_frame = tk.Frame(qr_card, bg="#FFFFFF", padx=10, pady=10, relief="flat", bd=0)
        wan_qr_frame.pack(anchor="center", pady=6)

        self.wan_qr_label = tk.Label(wan_qr_frame, bg="#FFFFFF")
        self.wan_qr_label.pack(anchor="center")

        tk.Label(
            qr_card,
            text="Phone App  ➔  Connect  ➔  Remote WAN  ➔  Scan QR",
            font=(UI_FONT, 9, "bold"),
            bg=CARD_BG,
            fg=ACCENT_CYAN,
        ).pack(anchor="center", pady=(14, 0))

        info_card = self._make_card(container, padx=22, pady=20)
        info_card.pack(side="right", fill="both", expand=True, padx=(8, 0))

        tk.Label(
            info_card,
            text="WebSocket & PIN Configuration",
            font=(UI_FONT, 14, "bold"),
            bg=CARD_BG,
            fg=TEXT_PRIMARY,
        ).pack(anchor="w", pady=(0, 12))

        ws_box = tk.Frame(
            info_card, bg=CARD_SURFACE, padx=14, pady=10, highlightbackground=CARD_BORDER, highlightthickness=1
        )
        ws_box.pack(fill="x", pady=(0, 12))

        tk.Label(ws_box, text="DIRECT WEBSOCKET ENDPOINT", font=(UI_FONT, 8, "bold"), bg=CARD_SURFACE, fg=TEXT_MUTED).pack(
            anchor="w"
        )
        self.lbl_wan_url = tk.Label(
            ws_box,
            text=f"ws://{self.current_ip}:{self.ws_port}",
            font=("Consolas", 12, "bold"),
            bg=CARD_SURFACE,
            fg=ACCENT_GREEN,
        )
        self.lbl_wan_url.pack(anchor="w", pady=(3, 0))

        tk.Label(
            info_card,
            text="Security Access Key (6-Digit Pairing PIN):",
            font=(UI_FONT, 9, "bold"),
            bg=CARD_BG,
            fg=TEXT_MUTED,
        ).pack(anchor="w", pady=(2, 4))

        token_row = tk.Frame(info_card, bg=CARD_BG)
        token_row.pack(fill="x", pady=(0, 14))

        self.ent_token = tk.Entry(
            token_row,
            font=("Consolas", 12, "bold"),
            bg=BUTTON_BG,
            fg=TEXT_PRIMARY,
            insertbackground=TEXT_PRIMARY,
            relief="flat",
            highlightbackground=CARD_BORDER,
            highlightcolor=ACCENT_CYAN,
            highlightthickness=1,
        )
        self.ent_token.insert(0, self.auth_token)
        self.ent_token.pack(side="left", fill="x", expand=True, padx=(0, 8), ipady=6)

        btn_set_token = self._make_button(
            token_row,
            "Update PIN",
            self._update_auth_token,
            bg=ACCENT_INDIGO,
            fg=TEXT_PRIMARY,
            hover_bg=ACCENT_INDIGO_HOVER,
            padx=12,
            pady=6,
        )
        btn_set_token.pack(side="right")

        btn_copy_url = self._make_button(
            info_card, "📋  Copy WebSocket URL", self.copy_wan_url, bg=BUTTON_BG, fg=TEXT_PRIMARY, pady=8
        )
        btn_copy_url.pack(fill="x", pady=(0, 8))

        btn_refresh_wan = self._make_button(
            info_card, "🔄  Refresh WAN QR Code", self.generate_wan_qr, bg=BUTTON_BG, fg=ACCENT_CYAN, pady=8
        )
        btn_refresh_wan.pack(fill="x", pady=(0, 12))

        tk.Label(
            info_card,
            text="Security Note: Local LAN connections use ws://. For access over the public Internet, route through a TLS-terminated tunnel (wss://) such as Cloudflare Tunnel or Tailscale Funnel.",
            font=(UI_FONT, 8),
            bg=CARD_BG,
            fg=TEXT_MUTED,
            wraplength=330,
            justify="left",
        ).pack(anchor="w")

        self.generate_wan_qr()

    def _build_desktop_tab(self):
        canvas = tk.Canvas(self.tab_desktop, bg=BG_DARK, highlightthickness=0, borderwidth=0)
        v_scroll = ttk.Scrollbar(self.tab_desktop, orient="vertical", command=canvas.yview)
        card = self._make_card(canvas, padx=20, pady=18)
        card_window = canvas.create_window((0, 0), window=card, anchor="nw")

        def _on_card_configure(_event):
            canvas.configure(scrollregion=canvas.bbox("all"))

        def _on_canvas_configure(event):
            canvas.itemconfig(card_window, width=event.width)

        card.bind("<Configure>", _on_card_configure)
        canvas.bind("<Configure>", _on_canvas_configure)
        canvas.configure(yscrollcommand=v_scroll.set)

        def _on_mousewheel(event):
            if canvas.winfo_height() < card.winfo_height():
                canvas.yview_scroll(int(-1 * (event.delta / 120)), "units")

        canvas.bind("<Enter>", lambda _e: canvas.bind_all("<MouseWheel>", _on_mousewheel))
        canvas.bind("<Leave>", lambda _e: canvas.unbind_all("<MouseWheel>"))

        canvas.pack(side="left", fill="both", expand=True)
        v_scroll.pack(side="right", fill="y")

        top_row = tk.Frame(card, bg=CARD_BG)
        top_row.pack(fill="x", pady=(0, 10))

        title_col = tk.Frame(top_row, bg=CARD_BG)
        title_col.pack(side="left")

        tk.Label(
            title_col,
            text="Desktop Controls, Quick Apps & Diagnostics",
            font=(UI_FONT, 13, "bold"),
            bg=CARD_BG,
            fg=TEXT_PRIMARY,
        ).pack(anchor="w")

        tk.Label(
            title_col,
            text="Configure Windows startup, curated Quick Apps, media preview area, and local hardware checks.",
            font=(UI_FONT, 9),
            bg=CARD_BG,
            fg=TEXT_MUTED,
        ).pack(anchor="w")

        btn_lock = self._make_button(
            top_row,
            "🔒  Lock Workstation",
            lambda: self.server._handle_system_power("lock") if self.server else None,
            bg=BUTTON_BG,
            fg=ACCENT_RED,
            padx=12,
            pady=6,
        )
        btn_lock.pack(side="right")

        startup_frame = tk.Frame(card, bg=CARD_SURFACE, padx=12, pady=8, highlightbackground=CARD_BORDER, highlightthickness=1)
        startup_frame.pack(fill="x", pady=(0, 12))
        self.startup_var = tk.BooleanVar(value=self._startup_registry_value() is not None)
        tk.Checkbutton(
            startup_frame,
            text="Launch BT Mouse Companion automatically when signing in to Windows (minimized to system tray)",
            variable=self.startup_var,
            command=lambda: self._set_startup_enabled(self.startup_var.get()),
            state="normal" if os.name == "nt" else "disabled",
            font=(UI_FONT, 9, "bold"),
            bg=CARD_SURFACE,
            fg=TEXT_PRIMARY,
            activebackground=CARD_SURFACE,
            activeforeground=TEXT_PRIMARY,
            selectcolor=BUTTON_BG,
            anchor="w",
        ).pack(anchor="w", fill="x")

        # Quick Apps Section
        quick_box = tk.LabelFrame(
            card,
            text="  Quick Apps on this PC (Synced to Phone)  ",
            font=(UI_FONT, 9, "bold"),
            bg=CARD_BG,
            fg=ACCENT_CYAN,
            padx=12,
            pady=10,
            bd=1,
            relief="solid",
        )
        quick_box.pack(fill="x", pady=(0, 12))

        add_row = tk.Frame(quick_box, bg=CARD_BG)
        add_row.pack(fill="x", pady=(0, 8))
        self.combo_available_apps = ttk.Combobox(add_row, state="readonly", font=(UI_FONT, 9))
        self.combo_available_apps.pack(side="left", fill="x", expand=True, padx=(0, 8))
        self._make_button(
            add_row, "＋ Add App", self.add_quick_app, bg=ACCENT_INDIGO, fg=TEXT_PRIMARY, hover_bg=ACCENT_INDIGO_HOVER, padx=10, pady=4
        ).pack(side="right")

        self.list_quick_apps = tk.Listbox(
            quick_box,
            height=4,
            font=(UI_FONT, 9),
            bg=CARD_SURFACE,
            fg=TEXT_PRIMARY,
            selectbackground=ACCENT_INDIGO,
            borderwidth=0,
            highlightthickness=1,
            highlightbackground=CARD_BORDER,
            activestyle="none",
        )
        self.list_quick_apps.pack(fill="x", pady=(0, 8))
        self.list_quick_apps.bind("<<ListboxSelect>>", self.on_quick_app_selected)

        edit_row = tk.Frame(quick_box, bg=CARD_BG)
        edit_row.pack(fill="x")
        self.ent_quick_app_name = tk.Entry(
            edit_row,
            font=(UI_FONT, 9),
            bg=BUTTON_BG,
            fg=TEXT_PRIMARY,
            insertbackground=TEXT_PRIMARY,
            relief="flat",
            highlightbackground=CARD_BORDER,
            highlightthickness=1,
        )
        self.ent_quick_app_name.pack(side="left", fill="x", expand=True, padx=(0, 8), ipady=4)
        self._make_button(edit_row, "Save Name", self.rename_quick_app, fg=ACCENT_CYAN, padx=9, pady=4).pack(
            side="left", padx=(0, 6)
        )
        self._make_button(edit_row, "Remove", self.remove_quick_app, fg=ACCENT_RED, padx=9, pady=4).pack(
            side="left", padx=(0, 6)
        )
        self._make_button(edit_row, "🔄 Refresh Apps", self.refresh_quick_apps, fg=TEXT_PRIMARY, padx=9, pady=4).pack(
            side="left"
        )

        self.lbl_quick_apps_status = tk.Label(
            quick_box,
            text="Quick apps are saved on this PC and shared with the phone app.",
            font=(UI_FONT, 8),
            bg=CARD_BG,
            fg=TEXT_MUTED,
            anchor="w",
        )
        self.lbl_quick_apps_status.pack(fill="x", pady=(6, 0))
        self.after_idle(self.refresh_quick_apps)

        # Recording Policy Section
        policy_box = tk.LabelFrame(
            card,
            text="  Capture & Storage Retention Policy  ",
            font=(UI_FONT, 9, "bold"),
            bg=CARD_BG,
            fg=ACCENT_CYAN,
            padx=12,
            pady=10,
            bd=1,
            relief="solid",
        )
        policy_box.pack(fill="x", pady=(0, 12))

        self.auto_record_video_var = tk.BooleanVar(value=self.auto_record_video)
        tk.Checkbutton(
            policy_box,
            text="Automatically record every live PC webcam session (1280×720 MP4)",
            variable=self.auto_record_video_var,
            command=self._save_recording_preferences,
            font=(UI_FONT, 9),
            bg=CARD_BG,
            fg=TEXT_PRIMARY,
            activebackground=CARD_BG,
            activeforeground=TEXT_PRIMARY,
            selectcolor=BUTTON_BG,
            anchor="w",
        ).pack(anchor="w", fill="x")

        self.auto_record_audio_var = tk.BooleanVar(value=self.auto_record_audio)
        tk.Checkbutton(
            policy_box,
            text="Also record the PC microphone during each live webcam session",
            variable=self.auto_record_audio_var,
            command=self._save_recording_preferences,
            font=(UI_FONT, 9),
            bg=CARD_BG,
            fg=TEXT_PRIMARY,
            activebackground=CARD_BG,
            activeforeground=TEXT_PRIMARY,
            selectcolor=BUTTON_BG,
            anchor="w",
        ).pack(anchor="w", fill="x", pady=(2, 6))

        retention_row = tk.Frame(policy_box, bg=CARD_BG)
        retention_row.pack(anchor="w", fill="x", pady=2)
        tk.Label(retention_row, text="Auto-delete older than (days, 0=keep):", font=(UI_FONT, 8), bg=CARD_BG, fg=TEXT_MUTED).pack(
            side="left"
        )
        self.recording_retention_var = tk.StringVar(value=str(self.recording_retention_days))
        ttk.Spinbox(retention_row, from_=0, to=3650, width=6, textvariable=self.recording_retention_var).pack(
            side="left", padx=(6, 14)
        )
        tk.Label(retention_row, text="Max library MB (0=unlimited):", font=(UI_FONT, 8), bg=CARD_BG, fg=TEXT_MUTED).pack(
            side="left"
        )
        self.recording_max_storage_var = tk.StringVar(value=str(self.recording_max_storage_mb))
        ttk.Spinbox(retention_row, from_=0, to=500000, increment=512, width=8, textvariable=self.recording_max_storage_var).pack(
            side="left", padx=(6, 14)
        )
        tk.Label(retention_row, text="Min free disk (MB):", font=(UI_FONT, 8), bg=CARD_BG, fg=TEXT_MUTED).pack(side="left")
        self.recording_min_free_var = tk.StringVar(value=str(self.recording_min_free_mb))
        ttk.Spinbox(retention_row, from_=0, to=100000, increment=256, width=7, textvariable=self.recording_min_free_var).pack(
            side="left", padx=6
        )
        self._make_button(
            retention_row, "Save Limits", self._save_recording_preferences, fg=ACCENT_CYAN, font_size=8, padx=8, pady=3
        ).pack(side="left", padx=(6, 0))

        # Media Preview Region & Hardware Diagnostics
        diag_box = tk.LabelFrame(
            card,
            text="  Media Player Preview Area & Hardware Self-Test  ",
            font=(UI_FONT, 9, "bold"),
            bg=CARD_BG,
            fg=ACCENT_CYAN,
            padx=12,
            pady=10,
            bd=1,
            relief="solid",
        )
        diag_box.pack(fill="x", pady=(0, 12))

        preview_row = tk.Frame(diag_box, bg=CARD_BG)
        preview_row.pack(anchor="w", fill="x", pady=(0, 8))
        self._make_button(
            preview_row, "📐 Select Player Preview Area", self.select_media_preview_region, fg=ACCENT_CYAN, padx=10, pady=5
        ).pack(side="left", padx=(0, 10))
        self.lbl_media_preview_region = tk.Label(
            preview_row, text=self._media_preview_region_description(), font=(UI_FONT, 9, "bold"), bg=CARD_BG, fg=TEXT_PRIMARY, anchor="w"
        )
        self.lbl_media_preview_region.pack(side="left", fill="x", expand=True)

        media_frame = tk.Frame(diag_box, bg=CARD_BG)
        media_frame.pack(anchor="w", fill="x", pady=(4, 4))
        self._make_button(media_frame, "📸 Test Webcam Snapshot", self.test_camera_snapshot, fg=ACCENT_CYAN, padx=10, pady=5).pack(
            side="left", padx=(0, 8)
        )
        self._make_button(media_frame, "📷 Snapshots Folder", self.open_snapshots_folder, fg=TEXT_PRIMARY, padx=10, pady=5).pack(
            side="left", padx=(0, 8)
        )
        self._make_button(media_frame, "🎙️ Test Mic (3s Record)", self.test_mic_record, fg=ACCENT_GREEN, padx=10, pady=5).pack(
            side="left"
        )

        self.lbl_cam_preview = tk.Label(diag_box, bg=CARD_BG)
        self.lbl_cam_preview.pack(anchor="w", pady=4)

        self.lbl_media_status = tk.Label(
            diag_box, text="Webcam & Microphone ready for remote commands.", font=(UI_FONT, 8), bg=CARD_BG, fg=TEXT_MUTED
        )
        self.lbl_media_status.pack(anchor="w")

        # Recordings Quick Summary Box
        recordings = tk.LabelFrame(
            card,
            text="  Recent Recordings on this PC  ",
            font=(UI_FONT, 9, "bold"),
            bg=CARD_BG,
            fg=ACCENT_CYAN,
            padx=12,
            pady=10,
            bd=1,
            relief="solid",
        )
        recordings.pack(fill="x")

        rec_header = tk.Frame(recordings, bg=CARD_BG)
        rec_header.pack(fill="x", pady=(0, 8))

        self.lbl_desktop_recordings_summary = tk.Label(
            rec_header, text="Loading recordings...", font=(UI_FONT, 9, "bold"), bg=CARD_BG, fg=TEXT_PRIMARY, anchor="w"
        )
        self.lbl_desktop_recordings_summary.pack(side="left", fill="x", expand=True)

        self._make_button(
            rec_header,
            "🎬 Open Full Library ➔",
            self.switch_to_recordings_tab,
            bg=ACCENT_INDIGO,
            fg=TEXT_PRIMARY,
            hover_bg=ACCENT_INDIGO_HOVER,
            padx=10,
            pady=4,
        ).pack(side="right")

        rec_list_frame = tk.Frame(recordings, bg=CARD_BG)
        rec_list_frame.pack(fill="x", pady=(0, 8))

        self.list_recordings = tk.Listbox(
            rec_list_frame,
            height=4,
            font=(UI_FONT, 9),
            bg=CARD_SURFACE,
            fg=TEXT_PRIMARY,
            selectbackground=ACCENT_INDIGO,
            borderwidth=0,
            highlightthickness=1,
            highlightbackground=CARD_BORDER,
            activestyle="none",
        )
        rec_scroll = ttk.Scrollbar(rec_list_frame, orient="vertical", command=self.list_recordings.yview)
        self.list_recordings.config(yscrollcommand=rec_scroll.set)
        self.list_recordings.pack(side="left", fill="x", expand=True)
        rec_scroll.pack(side="right", fill="y")
        self.list_recordings.bind("<Double-Button-1>", lambda _event: self.open_selected_recording())

        recording_actions = tk.Frame(recordings, bg=CARD_BG)
        recording_actions.pack(fill="x")
        self._make_button(recording_actions, "▶️ Play", self.open_selected_recording, fg=ACCENT_GREEN, padx=10, pady=4).pack(
            side="left", padx=(0, 6)
        )
        self._make_button(recording_actions, "🔍 Reveal in Explorer", self.show_selected_in_explorer, padx=10, pady=4).pack(
            side="left", padx=(0, 6)
        )
        self._make_button(recording_actions, "📁 Recordings Folder", self.open_recordings_folder, fg=ACCENT_CYAN, padx=10, pady=4).pack(
            side="left", padx=(0, 6)
        )
        self._make_button(recording_actions, "🗑️ Delete", self.delete_selected_recording, fg=ACCENT_RED, padx=10, pady=4).pack(
            side="left"
        )

        self.lbl_recordings_path = tk.Label(recordings, text="", font=(UI_FONT, 8), bg=CARD_BG, fg=TEXT_MUTED, anchor="w")
        self.lbl_recordings_path.pack(fill="x", pady=(6, 0))
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
        canvas.create_text(
            width // 2, 50, text="Drag around the video player area · Esc to cancel", fill="white", font=(UI_FONT, 18, "bold")
        )
        canvas.create_text(
            width // 2,
            82,
            text="Only the selected rectangle is sent to your phone while preview is enabled.",
            fill="#cbd5e1",
            font=(UI_FONT, 11),
        )
        selection = {"start": None, "rect": None}

        def begin(event):
            selection["start"] = (event.x, event.y)
            if selection["rect"]:
                canvas.delete(selection["rect"])
            selection["rect"] = canvas.create_rectangle(
                event.x, event.y, event.x, event.y, outline="#22d3ee", width=3, fill="", stipple="gray25"
            )

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
                json.dumps(
                    {
                        "auto_record_video": enabled,
                        "auto_record_audio": record_audio,
                        "recording_retention_days": retention_days,
                        "recording_max_storage_mb": max_storage_mb,
                        "recording_min_free_mb": min_free_mb,
                    },
                    indent=2,
                ),
                encoding="utf-8",
            )
        except OSError as exc:
            messagebox.showerror("Recording settings", f"Could not save the recording preference.\n\n{exc}")

    def _recordings_dir(self):
        if self.server and self.server.audio_manager:
            return Path(self.server.audio_manager.recordings_dir)
        user_data = os.environ.get("LOCALAPPDATA") or str(Path.home())
        return Path(user_data) / "BTMouse" / "Recordings"

    def _snapshots_dir(self):
        if self.server and hasattr(self.server, "camera_manager") and getattr(self.server.camera_manager, "snapshots_dir", None):
            return Path(self.server.camera_manager.snapshots_dir)
        user_data = os.environ.get("LOCALAPPDATA") or str(Path.home())
        return Path(user_data) / "BTMouse" / "Snapshots"

    def refresh_recordings(self):
        folder = self._recordings_dir()
        folder.mkdir(parents=True, exist_ok=True)
        allowed_extensions = {".wav", ".avi", ".mp4", ".m4a", ".mp3", ".webm", ".mkv"}
        recordings = []
        total_bytes = 0

        for path in sorted((p for p in folder.iterdir() if p.is_file()), key=lambda p: p.stat().st_mtime, reverse=True):
            ext = path.suffix.lower()
            if ext not in allowed_extensions:
                continue
            if self.server and self.server.camera_manager.streaming and self.server.camera_manager.last_recording_path == str(path):
                continue
            if self.server and self.server.audio_manager.recording and self.server.audio_manager.current_filename == str(path):
                continue
            try:
                stat = path.stat()
                total_bytes += stat.st_size
                is_audio = ext in {".wav", ".m4a", ".mp3"}
                type_name = "Audio" if is_audio else "Video"
                type_icon = "🎙️" if is_audio else "📹"
                dt = datetime.fromtimestamp(stat.st_mtime)
                date_str = dt.strftime("%Y-%m-%d  %I:%M %p")
                if stat.st_size < 1024 * 1024:
                    size_str = f"{stat.st_size / 1024:.1f} KB"
                else:
                    size_str = f"{stat.st_size / (1024 * 1024):.1f} MB"

                recordings.append(
                    {
                        "path": path,
                        "name": path.name,
                        "size": stat.st_size,
                        "size_str": size_str,
                        "mtime": stat.st_mtime,
                        "date_str": date_str,
                        "is_audio": is_audio,
                        "is_snapshot": False,
                        "type_name": type_name,
                        "type_icon": type_icon,
                        "ext": ext,
                    }
                )
            except OSError:
                continue

        snap_folder = self._snapshots_dir()
        snap_folder.mkdir(parents=True, exist_ok=True)
        try:
            for path in snap_folder.iterdir():
                if not path.is_file():
                    continue
                ext = path.suffix.lower()
                if ext not in {".jpg", ".jpeg", ".png"}:
                    continue
                try:
                    stat = path.stat()
                    total_bytes += stat.st_size
                    dt = datetime.fromtimestamp(stat.st_mtime)
                    date_str = dt.strftime("%Y-%m-%d  %I:%M %p")
                    if stat.st_size < 1024 * 1024:
                        size_str = f"{stat.st_size / 1024:.1f} KB"
                    else:
                        size_str = f"{stat.st_size / (1024 * 1024):.1f} MB"

                    recordings.append(
                        {
                            "path": path,
                            "name": path.name,
                            "size": stat.st_size,
                            "size_str": size_str,
                            "mtime": stat.st_mtime,
                            "date_str": date_str,
                            "is_audio": False,
                            "is_snapshot": True,
                            "type_name": "Snapshot",
                            "type_icon": "📷",
                            "ext": ext,
                        }
                    )
                except OSError:
                    continue
        except Exception:
            pass

        recordings.sort(key=lambda item: item["mtime"], reverse=True)

        fingerprint = tuple((r["path"], r["size"], r["mtime"]) for r in recordings)
        changed = getattr(self, "_recordings_fingerprint", None) != fingerprint
        self._recordings_fingerprint = fingerprint
        self._all_recordings = recordings
        self._total_recordings_bytes = total_bytes

        if hasattr(self, "list_recordings"):
            self.list_recordings.delete(0, "end")
            self._recording_paths = []
            for rec in recordings:
                self._recording_paths.append(rec["path"])
                self.list_recordings.insert("end", f"{rec['type_icon']}  {rec['name']}  ·  {rec['size_str']}")

            if hasattr(self, "lbl_recordings_path"):
                self.lbl_recordings_path.config(text=str(folder))
            if hasattr(self, "lbl_desktop_recordings_summary"):
                total_mb = total_bytes / (1024 * 1024)
                count = len(recordings)
                self.lbl_desktop_recordings_summary.config(
                    text=f"Total: {count} item{'s' if count != 1 else ''}  ·  {total_mb:.1f} MB stored"
                )

        if hasattr(self, "tree_recordings"):
            if changed or not getattr(self, "_tree_rendered_once", False):
                self._tree_rendered_once = True
                self._filter_and_render_recordings()

    def _refresh_recordings_loop(self):
        if not self.winfo_exists():
            return
        self.refresh_recordings()
        self.after(4000, self._refresh_recordings_loop)

    def _filter_and_render_recordings(self):
        if not hasattr(self, "tree_recordings"):
            return

        search_query = self.ent_recording_search.get().strip().lower() if hasattr(self, "ent_recording_search") else ""
        filter_type = self.recording_filter_type.get() if hasattr(self, "recording_filter_type") else "all"
        sort_mode = self.combo_recording_sort.get() if hasattr(self, "combo_recording_sort") else "Newest First"

        items = list(getattr(self, "_all_recordings", []))

        if filter_type == "audio":
            items = [x for x in items if x.get("is_audio")]
        elif filter_type == "video":
            items = [x for x in items if not x.get("is_audio") and not x.get("is_snapshot")]
        elif filter_type == "snapshot":
            items = [x for x in items if x.get("is_snapshot")]

        if search_query:
            items = [x for x in items if search_query in x["name"].lower() or search_query in x["date_str"].lower()]

        if sort_mode == "Newest First":
            items.sort(key=lambda x: x["mtime"], reverse=True)
        elif sort_mode == "Oldest First":
            items.sort(key=lambda x: x["mtime"])
        elif sort_mode == "Name (A-Z)":
            items.sort(key=lambda x: x["name"].lower())
        elif sort_mode == "Size (Largest)":
            items.sort(key=lambda x: x["size"], reverse=True)
        elif sort_mode == "Size (Smallest)":
            items.sort(key=lambda x: x["size"])

        prev_selection_path = None
        current_sel = self.tree_recordings.selection()
        if current_sel and current_sel[0] in getattr(self, "_tree_item_map", {}):
            prev_selection_path = self._tree_item_map[current_sel[0]]["path"]

        self.tree_recordings.delete(*self.tree_recordings.get_children())
        self._tree_item_map = {}

        to_select = None
        for idx, rec in enumerate(items):
            iid = f"rec_{hash(str(rec['path']))}"
            type_display = f"{rec['type_icon']} {rec['type_name']} ({rec['ext'][1:].upper()})"
            tag = "even" if idx % 2 == 0 else "odd"
            self.tree_recordings.insert(
                "",
                "end",
                iid=iid,
                text=rec["name"],
                values=(f"{rec['type_icon']}  {rec['name']}", type_display, rec["date_str"], rec["size_str"]),
                tags=(tag,),
            )
            self._tree_item_map[iid] = rec
            if prev_selection_path and rec["path"] == prev_selection_path:
                to_select = iid

        self.tree_recordings.tag_configure("even", background=CARD_BG)
        self.tree_recordings.tag_configure("odd", background=CARD_SURFACE)

        if to_select:
            self.tree_recordings.selection_set(to_select)
        elif items:
            first_iid = self.tree_recordings.get_children()[0]
            self.tree_recordings.selection_set(first_iid)
            self._on_tree_recording_selected()
        else:
            self._on_tree_recording_selected()

        folder = self._recordings_dir()
        total_mb = getattr(self, "_total_recordings_bytes", 0) / (1024 * 1024)
        count_all = len(getattr(self, "_all_recordings", []))
        showing_str = f"Showing {len(items)} of {count_all}" if len(items) != count_all else f"{count_all} items"
        if hasattr(self, "lbl_recordings_stats"):
            self.lbl_recordings_stats.config(text=f"📊 {showing_str}  ·  {total_mb:.1f} MB total library")

        if hasattr(self, "lbl_disk_free"):
            try:
                free_bytes = shutil.disk_usage(folder).free
                free_gb = free_bytes / (1024 * 1024 * 1024)
                self.lbl_disk_free.config(text=f"💾 Free Disk: {free_gb:.1f} GB")
            except Exception:
                self.lbl_disk_free.config(text="")

        if hasattr(self, "lbl_mgr_folder_path"):
            self.lbl_mgr_folder_path.config(text=str(folder))

        count_audio = sum(1 for x in getattr(self, "_all_recordings", []) if x.get("is_audio"))
        count_video = sum(1 for x in getattr(self, "_all_recordings", []) if not x.get("is_audio") and not x.get("is_snapshot"))
        count_snaps = sum(1 for x in getattr(self, "_all_recordings", []) if x.get("is_snapshot"))
        if hasattr(self, "btn_filter_all"):
            self.btn_filter_all.config(text=f"All ({count_all})")
        if hasattr(self, "btn_filter_audio"):
            self.btn_filter_audio.config(text=f"🎙️ Audio ({count_audio})")
        if hasattr(self, "btn_filter_video"):
            self.btn_filter_video.config(text=f"📹 Video ({count_video})")
        if hasattr(self, "btn_filter_snapshot"):
            self.btn_filter_snapshot.config(text=f"📷 Snapshots ({count_snaps})")

    def _update_filter_button_styles(self):
        ft = self.recording_filter_type.get() if hasattr(self, "recording_filter_type") else "all"
        if hasattr(self, "btn_filter_all"):
            self.btn_filter_all.config(bg=ACCENT_INDIGO if ft == "all" else BUTTON_BG)
        if hasattr(self, "btn_filter_audio"):
            self.btn_filter_audio.config(bg=ACCENT_INDIGO if ft == "audio" else BUTTON_BG)
        if hasattr(self, "btn_filter_video"):
            self.btn_filter_video.config(bg=ACCENT_INDIGO if ft == "video" else BUTTON_BG)
        if hasattr(self, "btn_filter_snapshot"):
            self.btn_filter_snapshot.config(bg=ACCENT_INDIGO if ft == "snapshot" else BUTTON_BG)

    def _on_tree_recording_selected(self, _event=None):
        if not hasattr(self, "tree_recordings") or not hasattr(self, "lbl_selected_recording_info"):
            return
        sel = self.tree_recordings.selection()
        if not sel or sel[0] not in getattr(self, "_tree_item_map", {}):
            self.lbl_selected_recording_info.config(text="Select a recording to play or inspect.", fg=TEXT_MUTED)
            return
        rec = self._tree_item_map[sel[0]]
        self.lbl_selected_recording_info.config(
            text=f"Selected: {rec['name']}  ·  {rec['size_str']}  ·  {rec['date_str']}", fg=TEXT_PRIMARY
        )

    def _on_tree_context_menu(self, event):
        row_id = self.tree_recordings.identify_row(event.y)
        if row_id:
            self.tree_recordings.selection_set(row_id)
            self._on_tree_recording_selected()
        if hasattr(self, "rec_context_menu"):
            self.rec_context_menu.tk_popup(event.x_root, event.y_root)

    def _get_selected_recording_path(self):
        if hasattr(self, "tree_recordings") and hasattr(self, "_tree_item_map"):
            tree_sel = self.tree_recordings.selection()
            if tree_sel and tree_sel[0] in self._tree_item_map:
                return self._tree_item_map[tree_sel[0]]["path"]

        if hasattr(self, "list_recordings") and hasattr(self, "_recording_paths"):
            list_sel = self.list_recordings.curselection()
            if list_sel and list_sel[0] < len(self._recording_paths):
                return self._recording_paths[list_sel[0]]

        return None

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
        except Exception:
            try:
                subprocess.Popen(["explorer.exe", str(folder.resolve())])
            except Exception as exc:
                messagebox.showerror("Recordings", f"Could not open recordings folder in File Explorer.\n\n{exc}")

    def open_snapshots_folder(self):
        folder = self._snapshots_dir()
        folder.mkdir(parents=True, exist_ok=True)
        try:
            if os.name == "nt":
                os.startfile(str(folder))
            elif sys.platform == "darwin":
                subprocess.Popen(["open", str(folder)])
            else:
                subprocess.Popen(["xdg-open", str(folder)])
        except Exception:
            try:
                subprocess.Popen(["explorer.exe", str(folder.resolve())])
            except Exception as exc:
                messagebox.showerror("Snapshots", f"Could not open snapshots folder in File Explorer.\n\n{exc}")

    def show_selected_in_explorer(self, target_path=None):
        path = target_path or self._get_selected_recording_path()
        if not path or not Path(path).is_file():
            self.open_recordings_folder()
            return
        path = Path(path)
        try:
            if os.name == "nt":
                subprocess.Popen(["explorer.exe", f"/select,{str(path.resolve())}"])
            elif sys.platform == "darwin":
                subprocess.Popen(["open", "-R", str(path.resolve())])
            else:
                subprocess.Popen(["xdg-open", str(path.parent.resolve())])
        except Exception as exc:
            messagebox.showerror("Recordings", f"Could not reveal {path.name} in File Explorer.\n\n{exc}")

    def open_selected_recording(self, target_path=None):
        path = target_path or self._get_selected_recording_path()
        if not path or not Path(path).is_file():
            messagebox.showinfo("Recordings", "Please select a recording to open.")
            return
        path = Path(path)
        try:
            if os.name == "nt":
                os.startfile(str(path))
            elif sys.platform == "darwin":
                subprocess.Popen(["open", str(path)])
            else:
                subprocess.Popen(["xdg-open", str(path)])
        except Exception as exc:
            messagebox.showerror("Recordings", f"Could not open {path.name}.\n\n{exc}")

    def delete_selected_recording(self, target_path=None):
        path = target_path or self._get_selected_recording_path()
        if not path or not Path(path).is_file():
            messagebox.showinfo("Delete Recording", "Please select a recording to delete.")
            return
        path = Path(path)
        size_mb = path.stat().st_size / (1024 * 1024)
        confirm = messagebox.askyesno(
            "Delete Recording",
            f"Are you sure you want to permanently delete this recording?\n\n{path.name} ({size_mb:.1f} MB)\n\nThis cannot be undone.",
        )
        if confirm:
            try:
                path.unlink()
                self._tree_rendered_once = False
                self.refresh_recordings()
            except OSError as exc:
                messagebox.showerror("Delete Recording", f"Could not delete recording.\n\n{exc}")

    def copy_selected_recording_path(self, target_path=None):
        path = target_path or self._get_selected_recording_path()
        if not path:
            return
        self.clipboard_clear()
        self.clipboard_append(str(Path(path).resolve()))

    def switch_to_recordings_tab(self):
        if hasattr(self, "notebook") and hasattr(self, "tab_recordings"):
            self.notebook.select(self.tab_recordings)

    def _build_recordings_tab(self):
        card = self._make_card(self.tab_recordings, padx=20, pady=18)
        card.pack(fill="both", expand=True)

        header = tk.Frame(card, bg=CARD_BG)
        header.pack(fill="x", pady=(0, 12))

        header_text = tk.Frame(header, bg=CARD_BG)
        header_text.pack(side="left", fill="y")

        tk.Label(
            header_text, text="Session & Hardware Recordings", font=(UI_FONT, 14, "bold"), bg=CARD_BG, fg=TEXT_PRIMARY
        ).pack(anchor="w")

        tk.Label(
            header_text,
            text="Browse, preview, filter, and manage audio, webcam videos, and snapshots saved on this PC.",
            font=(UI_FONT, 9),
            bg=CARD_BG,
            fg=TEXT_MUTED,
        ).pack(anchor="w")

        header_buttons = tk.Frame(header, bg=CARD_BG)
        header_buttons.pack(side="right", fill="y")

        self._make_button(header_buttons, "📁 Recordings Folder", self.open_recordings_folder, fg=ACCENT_CYAN, padx=12, pady=5).pack(
            side="left", padx=(0, 6)
        )
        self._make_button(header_buttons, "📷 Snapshots Folder", self.open_snapshots_folder, fg=ACCENT_GREEN, padx=12, pady=5).pack(
            side="left", padx=(0, 6)
        )
        self._make_button(header_buttons, "🔄 Refresh", self.refresh_recordings, fg=TEXT_PRIMARY, padx=10, pady=5).pack(
            side="left"
        )

        stats_bar = tk.Frame(card, bg=CARD_SURFACE, padx=12, pady=8, highlightbackground=CARD_BORDER, highlightthickness=1)
        stats_bar.pack(fill="x", pady=(0, 10))

        stats_left = tk.Frame(stats_bar, bg=CARD_SURFACE)
        stats_left.pack(side="left")

        self.lbl_recordings_stats = tk.Label(
            stats_left, text="📊 Loading recordings...", font=(UI_FONT, 9, "bold"), bg=CARD_SURFACE, fg=TEXT_PRIMARY
        )
        self.lbl_recordings_stats.pack(side="left", padx=(0, 12))

        self.lbl_disk_free = tk.Label(stats_left, text="", font=(UI_FONT, 9, "bold"), bg=CARD_SURFACE, fg=ACCENT_GREEN)
        self.lbl_disk_free.pack(side="left")

        self.lbl_mgr_folder_path = tk.Label(
            stats_bar, text="", font=("Consolas", 8), bg=CARD_SURFACE, fg=TEXT_MUTED, anchor="e"
        )
        self.lbl_mgr_folder_path.pack(side="right")

        filter_bar = tk.Frame(card, bg=CARD_BG)
        filter_bar.pack(fill="x", pady=(0, 10))

        tk.Label(filter_bar, text="🔍 Search:", font=(UI_FONT, 9, "bold"), bg=CARD_BG, fg=TEXT_MUTED).pack(
            side="left", padx=(0, 6)
        )

        self.ent_recording_search = tk.Entry(
            filter_bar,
            font=(UI_FONT, 9),
            bg=BUTTON_BG,
            fg=TEXT_PRIMARY,
            insertbackground=TEXT_PRIMARY,
            relief="flat",
            highlightbackground=CARD_BORDER,
            highlightthickness=1,
            width=20,
        )
        self.ent_recording_search.pack(side="left", ipady=4, padx=(0, 12))
        self.ent_recording_search.bind("<KeyRelease>", lambda _e: self._filter_and_render_recordings())

        filter_btn_frame = tk.Frame(filter_bar, bg=CARD_BG)
        filter_btn_frame.pack(side="left", padx=(0, 12))

        self.recording_filter_type = tk.StringVar(value="all")

        def _select_filter(ft):
            self.recording_filter_type.set(ft)
            self._update_filter_button_styles()
            self._filter_and_render_recordings()

        self.btn_filter_all = tk.Button(
            filter_btn_frame,
            text="All",
            font=(UI_FONT, 8, "bold"),
            bg=ACCENT_INDIGO,
            fg=TEXT_PRIMARY,
            relief="flat",
            padx=10,
            pady=4,
            cursor="hand2",
            command=lambda: _select_filter("all"),
        )
        self.btn_filter_all.pack(side="left", padx=(0, 4))

        self.btn_filter_audio = tk.Button(
            filter_btn_frame,
            text="🎙️ Audio",
            font=(UI_FONT, 8, "bold"),
            bg=BUTTON_BG,
            fg=TEXT_PRIMARY,
            relief="flat",
            padx=10,
            pady=4,
            cursor="hand2",
            command=lambda: _select_filter("audio"),
        )
        self.btn_filter_audio.pack(side="left", padx=4)

        self.btn_filter_video = tk.Button(
            filter_btn_frame,
            text="📹 Video",
            font=(UI_FONT, 8, "bold"),
            bg=BUTTON_BG,
            fg=TEXT_PRIMARY,
            relief="flat",
            padx=10,
            pady=4,
            cursor="hand2",
            command=lambda: _select_filter("video"),
        )
        self.btn_filter_video.pack(side="left", padx=4)

        self.btn_filter_snapshot = tk.Button(
            filter_btn_frame,
            text="📷 Snapshots",
            font=(UI_FONT, 8, "bold"),
            bg=BUTTON_BG,
            fg=TEXT_PRIMARY,
            relief="flat",
            padx=10,
            pady=4,
            cursor="hand2",
            command=lambda: _select_filter("snapshot"),
        )
        self.btn_filter_snapshot.pack(side="left", padx=4)

        sort_frame = tk.Frame(filter_bar, bg=CARD_BG)
        sort_frame.pack(side="right")

        tk.Label(sort_frame, text="Sort:", font=(UI_FONT, 9, "bold"), bg=CARD_BG, fg=TEXT_MUTED).pack(
            side="left", padx=(0, 6)
        )

        self.combo_recording_sort = ttk.Combobox(
            sort_frame,
            state="readonly",
            width=14,
            font=(UI_FONT, 9),
            values=["Newest First", "Oldest First", "Name (A-Z)", "Size (Largest)", "Size (Smallest)"],
        )
        self.combo_recording_sort.set("Newest First")
        self.combo_recording_sort.pack(side="left")
        self.combo_recording_sort.bind("<<ComboboxSelected>>", lambda _e: self._filter_and_render_recordings())

        tree_frame = tk.Frame(card, bg=CARD_BG, highlightbackground=CARD_BORDER, highlightthickness=1)
        tree_frame.pack(fill="both", expand=True, pady=(0, 10))

        columns = ("name", "type", "date", "size")
        self.tree_recordings = ttk.Treeview(
            tree_frame, columns=columns, show="headings", style="Recordings.Treeview", selectmode="browse"
        )

        self.tree_recordings.heading("name", text="File Name", anchor="w")
        self.tree_recordings.heading("type", text="Type", anchor="center")
        self.tree_recordings.heading("date", text="Recorded On", anchor="center")
        self.tree_recordings.heading("size", text="Size", anchor="e")

        self.tree_recordings.column("name", width=290, minwidth=200, anchor="w")
        self.tree_recordings.column("type", width=120, minwidth=80, anchor="center")
        self.tree_recordings.column("date", width=170, minwidth=140, anchor="center")
        self.tree_recordings.column("size", width=90, minwidth=70, anchor="e")

        tree_scroll = ttk.Scrollbar(tree_frame, orient="vertical", command=self.tree_recordings.yview)
        self.tree_recordings.configure(yscrollcommand=tree_scroll.set)
        self.tree_recordings.pack(side="left", fill="both", expand=True)
        tree_scroll.pack(side="right", fill="y")

        self.tree_recordings.bind("<Double-Button-1>", lambda _e: self.open_selected_recording())
        self.tree_recordings.bind("<<TreeviewSelect>>", self._on_tree_recording_selected)
        self.tree_recordings.bind("<Return>", lambda _e: self.open_selected_recording())
        self.tree_recordings.bind("<Delete>", lambda _e: self.delete_selected_recording())
        self.tree_recordings.bind("<Button-3>", self._on_tree_context_menu)
        self.tree_recordings.bind("<Button-2>", self._on_tree_context_menu)

        self.rec_context_menu = tk.Menu(
            self, tearoff=0, bg=CARD_BG, fg=TEXT_PRIMARY, activebackground=ACCENT_INDIGO, activeforeground=TEXT_PRIMARY
        )
        self.rec_context_menu.add_command(label="▶️  Play / Open", command=self.open_selected_recording)
        self.rec_context_menu.add_command(label="🔍  Show in File Explorer", command=self.show_selected_in_explorer)
        self.rec_context_menu.add_command(label="📁  Open Recordings Folder", command=self.open_recordings_folder)
        self.rec_context_menu.add_separator()
        self.rec_context_menu.add_command(label="📋  Copy File Path", command=self.copy_selected_recording_path)
        self.rec_context_menu.add_command(label="🗑️  Delete Recording", command=self.delete_selected_recording)

        action_bar = tk.Frame(card, bg=CARD_BG)
        action_bar.pack(fill="x")

        self.lbl_selected_recording_info = tk.Label(
            action_bar, text="Select a recording to play or inspect.", font=(UI_FONT, 9), bg=CARD_BG, fg=TEXT_MUTED, anchor="w"
        )
        self.lbl_selected_recording_info.pack(side="left", fill="x", expand=True)

        action_buttons = tk.Frame(action_bar, bg=CARD_BG)
        action_buttons.pack(side="right")

        self._make_button(action_buttons, "▶️ Play / Open", self.open_selected_recording, fg=ACCENT_GREEN, padx=11, pady=5).pack(
            side="left", padx=(0, 6)
        )
        self._make_button(action_buttons, "🔍 Show in Explorer", self.show_selected_in_explorer, padx=11, pady=5).pack(
            side="left", padx=4
        )
        self._make_button(action_buttons, "📁 Open Folder", self.open_recordings_folder, fg=ACCENT_CYAN, padx=11, pady=5).pack(
            side="left", padx=4
        )
        self._make_button(action_buttons, "🗑️ Delete", self.delete_selected_recording, fg=ACCENT_RED, padx=10, pady=5).pack(
            side="left", padx=(4, 0)
        )

    def _build_phone_cam_tab(self):
        container = tk.Frame(self.tab_phone_cam, bg=BG_DARK)
        container.pack(fill="both", expand=True)

        preview_card = self._make_card(container, padx=18, pady=16)
        preview_card.pack(side="left", fill="both", expand=True, padx=(0, 8))

        preview_header = tk.Frame(preview_card, bg=CARD_BG)
        preview_header.pack(fill="x", pady=(0, 10))

        tk.Label(
            preview_header, text="📹 Live Phone Camera Viewfinder", font=(UI_FONT, 13, "bold"), bg=CARD_BG, fg=TEXT_PRIMARY
        ).pack(side="left")

        self.lbl_phone_cam_status = tk.Label(
            preview_header, text="○ STANDBY", font=(UI_FONT, 9, "bold"), bg=CARD_SURFACE, fg=TEXT_MUTED, padx=10, pady=3
        )
        self.lbl_phone_cam_status.pack(side="right")

        viewfinder_container = tk.Frame(
            preview_card, bg="#050811", highlightbackground=CARD_BORDER, highlightthickness=1
        )
        viewfinder_container.pack(fill="both", expand=True, pady=4)

        self.lbl_phone_cam_preview = tk.Label(
            viewfinder_container,
            text="📷 Phone Camera Standby\n\nOpen the BT Mouse mobile app\nGo to Tools  ➔  Camera & Mic  ➔  Start Phone Camera\nto broadcast live video to this PC.",
            font=(UI_FONT, 10),
            bg="#050811",
            fg=TEXT_MUTED,
            justify="center",
        )
        self.lbl_phone_cam_preview.pack(fill="both", expand=True, padx=8, pady=8)

        telemetry_bar = tk.Frame(preview_card, bg=CARD_BG)
        telemetry_bar.pack(fill="x", pady=(8, 0))

        self.lbl_phone_cam_telemetry = tk.Label(
            telemetry_bar, text="Waiting for stream from phone...", font=(UI_FONT, 9), bg=CARD_BG, fg=TEXT_MUTED, anchor="w"
        )
        self.lbl_phone_cam_telemetry.pack(side="left", fill="x", expand=True)

        self.lbl_phone_cam_counter = tk.Label(
            telemetry_bar, text="0 frames", font=("Consolas", 9, "bold"), bg=CARD_BG, fg=ACCENT_CYAN, anchor="e"
        )
        self.lbl_phone_cam_counter.pack(side="right")

        controls_card = self._make_card(container, padx=18, pady=16)
        controls_card.configure(width=360)
        controls_card.pack(side="right", fill="both", padx=(8, 0))
        controls_card.pack_propagate(False)

        tk.Label(
            controls_card, text="⚙️ Virtual Webcam & OBS", font=(UI_FONT, 13, "bold"), bg=CARD_BG, fg=TEXT_PRIMARY
        ).pack(anchor="w", pady=(0, 4))

        tk.Label(
            controls_card,
            text="Use your phone's camera inside OBS Studio, Zoom, Microsoft Teams, Discord, and Google Meet.",
            font=(UI_FONT, 9),
            bg=CARD_BG,
            fg=TEXT_MUTED,
            wraplength=320,
            justify="left",
        ).pack(anchor="w", pady=(0, 12))

        mjpeg_group = tk.Frame(
            controls_card, bg=CARD_SURFACE, padx=12, pady=12, highlightbackground=CARD_BORDER, highlightthickness=1
        )
        mjpeg_group.pack(fill="x", pady=(0, 12))

        tk.Label(
            mjpeg_group, text="LOCALHOST MJPEG STREAM URL", font=(UI_FONT, 8, "bold"), bg=CARD_SURFACE, fg=ACCENT_CYAN
        ).pack(anchor="w", pady=(0, 6))

        port = getattr(getattr(self.server, "phone_camera_http", None), "port", 8766)
        mjpeg_url = f"http://127.0.0.1:{port}/phone_cam/mjpeg"

        self.lbl_phone_mjpeg_url = tk.Label(
            mjpeg_group,
            text=mjpeg_url,
            font=("Consolas", 9, "bold"),
            bg=BG_DARK,
            fg=TEXT_PRIMARY,
            padx=8,
            pady=6,
            anchor="w",
        )
        self.lbl_phone_mjpeg_url.pack(fill="x", pady=(0, 8))

        btn_row = tk.Frame(mjpeg_group, bg=CARD_SURFACE)
        btn_row.pack(fill="x")

        self._make_button(btn_row, "📋 Copy URL", self._copy_phone_mjpeg_url, padx=10, pady=4).pack(
            side="left", padx=(0, 6)
        )
        self._make_button(
            btn_row,
            "🌐 Open in Browser",
            lambda: webbrowser.open(self.lbl_phone_mjpeg_url.cget("text")),
            fg=ACCENT_CYAN,
            padx=10,
            pady=4,
        ).pack(side="left")

        vcam_group = tk.Frame(
            controls_card, bg=CARD_SURFACE, padx=12, pady=10, highlightbackground=CARD_BORDER, highlightthickness=1
        )
        vcam_group.pack(fill="x", pady=(0, 12))

        tk.Label(
            vcam_group, text="DIRECT VIRTUAL CAMERA DRIVER", font=(UI_FONT, 8, "bold"), bg=CARD_SURFACE, fg=TEXT_MUTED
        ).pack(anchor="w")

        vcam_status = getattr(getattr(self.server, "phone_camera_receiver", None), "virtual_cam_status", "MJPEG_STREAM")
        vcam_err = getattr(getattr(self.server, "phone_camera_receiver", None), "virtual_cam_error", "MJPEG stream ready.")

        self.lbl_vcam_status = tk.Label(
            vcam_group,
            text=f"Backend: {vcam_status}",
            font=(UI_FONT, 9, "bold"),
            bg=CARD_SURFACE,
            fg=ACCENT_GREEN if vcam_status.startswith("ACTIVE") else ACCENT_CYAN,
        )
        self.lbl_vcam_status.pack(anchor="w", pady=(2, 2))

        tk.Label(
            vcam_group, text=vcam_err, font=(UI_FONT, 8), bg=CARD_SURFACE, fg=TEXT_MUTED, justify="left", wraplength=310
        ).pack(anchor="w")

        setup_group = tk.Frame(
            controls_card, bg=CARD_SURFACE, padx=12, pady=10, highlightbackground=CARD_BORDER, highlightthickness=1
        )
        setup_group.pack(fill="x", pady=(0, 12))

        tk.Label(
            setup_group, text="3-STEP SETUP FOR ZOOM / TEAMS / MEET", font=(UI_FONT, 8, "bold"), bg=CARD_SURFACE, fg=TEXT_PRIMARY
        ).pack(anchor="w", pady=(0, 4))

        steps_text = (
            "1. Open OBS Studio and add a 'Media Source'.\n"
            "2. Uncheck 'Local File' and paste the MJPEG URL above.\n"
            "3. Click 'Start Virtual Camera' in OBS and select 'OBS Virtual Camera' in Zoom/Teams/Meet."
        )
        tk.Label(
            setup_group, text=steps_text, font=(UI_FONT, 8), bg=CARD_SURFACE, fg=TEXT_MUTED, justify="left", wraplength=310
        ).pack(anchor="w")

        snapshot_group = tk.Frame(controls_card, bg=CARD_BG)
        snapshot_group.pack(fill="x")

        self._make_button(snapshot_group, "📸 Save Snapshot", self._save_phone_cam_snapshot, fg=ACCENT_GREEN, padx=10, pady=6).pack(
            side="left", padx=(0, 6)
        )
        self._make_button(snapshot_group, "📁 Open Snapshots", self.open_snapshots_folder, padx=10, pady=6).pack(
            side="left"
        )

    def _copy_phone_mjpeg_url(self):
        try:
            url = self.lbl_phone_mjpeg_url.cget("text")
            self.clipboard_clear()
            self.clipboard_append(url)
            self.lbl_phone_cam_telemetry.config(text="✓ Copied MJPEG stream URL to clipboard!")
        except Exception:
            pass

    def _save_phone_cam_snapshot(self):
        receiver = getattr(self.server, "phone_camera_receiver", None)
        frame = receiver.latest_jpeg if receiver else None
        if not frame:
            self.lbl_phone_cam_telemetry.config(text="No live frame to save as snapshot.")
            return
        try:
            snapshots_dir = Path(os.environ.get("LOCALAPPDATA", str(Path.home() / ".config"))) / "BTMouse" / "Snapshots"
            snapshots_dir.mkdir(parents=True, exist_ok=True)
            timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
            filename = f"phone_snapshot_{timestamp}.jpg"
            target = snapshots_dir / filename
            target.write_bytes(frame)
            self.lbl_phone_cam_telemetry.config(text=f"✓ Saved snapshot: {filename}")
        except Exception as e:
            self.lbl_phone_cam_telemetry.config(text=f"Could not save snapshot: {e}")

    def _on_phone_cam_frame_received(self, raw_jpeg, w, h, lens, fps, bitrate_kbps):
        if not getattr(self, "phone_cam_pending_update", False):
            self.phone_cam_pending_update = True
            self.after(0, lambda: self._update_phone_cam_gui(raw_jpeg, w, h, lens, fps, bitrate_kbps))

    def _update_phone_cam_gui(self, raw_jpeg, w, h, lens, fps, bitrate_kbps):
        self.phone_cam_pending_update = False
        try:
            import io
            img = Image.open(io.BytesIO(raw_jpeg))
            max_w, max_h = 520, 292
            img.thumbnail((max_w, max_h), Image.Resampling.LANCZOS)
            photo = ImageTk.PhotoImage(img)
            self.phone_cam_photo = photo
            if hasattr(self, "lbl_phone_cam_preview"):
                self.lbl_phone_cam_preview.config(image=photo, text="")
            if hasattr(self, "lbl_phone_cam_status"):
                self.lbl_phone_cam_status.config(text=f"● LIVE · {lens.upper()} CAMERA", fg=ACCENT_GREEN)
            if hasattr(self, "lbl_phone_cam_telemetry"):
                self.lbl_phone_cam_telemetry.config(text=f"{w}×{h} · {fps} FPS · {bitrate_kbps} kbps")
            if hasattr(self, "lbl_phone_cam_counter"):
                count = getattr(self.server.phone_camera_receiver, "frame_count", 0)
                self.lbl_phone_cam_counter.config(text=f"{count:,} frames")
        except Exception:
            pass

    def _check_phone_cam_active(self):
        receiver = getattr(self.server, "phone_camera_receiver", None)
        if receiver:
            now = time.monotonic()
            if receiver.latest_timestamp and now - receiver.latest_timestamp > 4.0:
                receiver.is_active = False
                if hasattr(self, "lbl_phone_cam_status"):
                    self.lbl_phone_cam_status.config(text="○ STANDBY", fg=TEXT_MUTED)
                if hasattr(self, "lbl_phone_cam_telemetry"):
                    self.lbl_phone_cam_telemetry.config(text="Stream paused / standby")
        self.after(2000, self._check_phone_cam_active)

    def _build_logs_tab(self):
        container = tk.Frame(self.tab_logs, bg=BG_DARK)
        container.pack(fill="both", expand=True)

        stats_card = self._make_card(container, padx=16, pady=12)
        stats_card.pack(fill="x", pady=(0, 10))

        self.lbl_client = tk.Label(
            stats_card, text="Client: Disconnected", font=(UI_FONT, 10, "bold"), bg=CARD_BG, fg=TEXT_MUTED
        )
        self.lbl_client.pack(side="left", padx=4)

        self._make_button(
            stats_card,
            "🧹 Clear Log",
            lambda: self.txt_log.delete("1.0", "end"),
            font_size=8,
            padx=10,
            pady=4,
        ).pack(side="right", padx=(8, 0))

        self.lbl_packets = tk.Label(
            stats_card, text="Packets Received: 0", font=("Consolas", 10, "bold"), bg=CARD_BG, fg=ACCENT_CYAN
        )
        self.lbl_packets.pack(side="right", padx=8)

        log_frame = tk.Frame(container, bg=CARD_BG, relief="flat", highlightbackground=CARD_BORDER, highlightthickness=1)
        log_frame.pack(fill="both", expand=True)

        self.txt_log = tk.Text(
            log_frame,
            bg=CARD_BG,
            fg=TEXT_PRIMARY,
            font=("Consolas", 9),
            borderwidth=0,
            highlightthickness=0,
            padx=12,
            pady=10,
        )
        self.txt_log.pack(side="left", fill="both", expand=True)

        scrollbar = ttk.Scrollbar(log_frame, command=self.txt_log.yview)
        scrollbar.pack(side="right", fill="y")
        self.txt_log.config(yscrollcommand=scrollbar.set)

        self.log("Companion initialized. Ready for connections.")

    def _build_statusbar(self):
        bar = tk.Frame(
            self,
            bg=SIDEBAR_BG,
            height=28,
            padx=16,
            pady=5,
            highlightbackground=CARD_BORDER,
            highlightthickness=1,
        )
        bar.pack(fill="x", side="bottom")

        lbl_foot = tk.Label(
            bar,
            text=f"BT Mouse Companion v{APP_VERSION}  ·  Minimizes to system tray on close  ·  Native Windows Hardware Input",
            font=(UI_FONT, 8),
            bg=SIDEBAR_BG,
            fg=TEXT_MUTED,
        )
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
            request = urllib.request.Request(
                api_url,
                headers={
                    "Accept": "application/vnd.github+json",
                    "User-Agent": "BT-Mouse-Companion-Updater",
                },
            )
            with urllib.request.urlopen(request, timeout=20) as response:
                release = json.loads(response.read(2 * 1024 * 1024).decode("utf-8"))

            tag_name = release.get("tag_name", "")
            if release.get("draft") or release.get("prerelease"):
                raise ValueError("GitHub did not return a stable release.")
            if self._version_tuple(tag_name) <= self._version_tuple(APP_VERSION):
                self.after(
                    0, lambda: messagebox.showinfo("Companion updates", f"You are using the latest version (v{APP_VERSION}).")
                )
                self.after(0, lambda: self.btn_update.config(state="normal", text="Check for Updates"))
                return

            asset = next((item for item in release.get("assets", []) if item.get("name") == "BT-Mouse-Companion.exe"), None)
            if not asset or not asset.get("browser_download_url"):
                raise ValueError("The latest release does not include the Windows companion executable.")
            if asset.get("size", 0) <= 0 or asset["size"] > MAX_COMPANION_UPDATE_BYTES:
                raise ValueError("The Windows update has an unexpected file size.")

            def ask_to_update():
                update = messagebox.askyesno(
                    "Companion update available",
                    f"Version {tag_name} is available. Download and install it now?\n\n"
                    "The companion will close briefly and reopen after updating.",
                )
                if update:
                    self.btn_update.config(state="disabled", text="Downloading…")
                    threading.Thread(target=self._download_companion_update_worker, args=(tag_name, asset), daemon=True).start()
                else:
                    self.btn_update.config(state="normal", text="Check for Updates")

            self.after(0, ask_to_update)
        except Exception as exc:
            self.after(
                0, lambda error=str(exc): messagebox.showerror("Companion update failed", f"Could not check for updates.\n\n{error}")
            )
            self.after(0, lambda: self.btn_update.config(state="normal", text="Check for Updates"))

    def _download_companion_update_worker(self, tag_name, asset):
        staged_path = None
        try:
            download_url = asset["browser_download_url"]
            if not download_url.startswith("https://github.com/"):
                raise ValueError("The update URL is not a trusted GitHub release link.")
            request = urllib.request.Request(
                download_url,
                headers={
                    "User-Agent": "BT-Mouse-Companion-Updater",
                    "Accept": "application/octet-stream",
                },
            )
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
                "  $quotedMarker = '\"' + $readyMarker + '\"'\n"
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
                    [
                        "powershell.exe",
                        "-NoProfile",
                        "-NonInteractive",
                        "-ExecutionPolicy",
                        "Bypass",
                        "-File",
                        str(script_path),
                    ],
                    creationflags=flags,
                    stdin=subprocess.DEVNULL,
                    stdout=output,
                    stderr=subprocess.STDOUT,
                )
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
            self.after(
                0,
                lambda error=str(exc): messagebox.showerror(
                    "Companion update failed", f"Could not download or install the update.\n\n{error}"
                ),
            )
            self.after(0, lambda: self.btn_update.config(state="normal", text="Check for Updates"))

    def _exit_for_companion_update(self):
        self.stop_server()
        if self.tray_icon:
            try:
                self.tray_icon.stop()
            except Exception:
                pass
        self.destroy()
        sys.exit(0)

    def _on_adapter_selected(self, event):
        idx = self.combo_adapter.current()
        if 0 <= idx < len(self.adapters):
            name, ip = self.adapters[idx]
            self.current_ip = ip
            self.lbl_ip_val.config(text=ip)
            if hasattr(self, "lbl_sidebar_ip"):
                self.lbl_sidebar_ip.config(text=f"IP: {ip}:{self.current_port}")
            self.generate_qr()
            self.generate_wan_qr()

    def generate_qr(self):
        if not HAS_QR:
            self.qr_label.config(text="QR library not found.\nInstall: pip install qrcode pillow", fg=ACCENT_RED, bg="#FFFFFF")
            return

        hostname = socket.gethostname()
        payload = json.dumps({
            "type": "btmouse",
            "ip": self.current_ip,
            "port": self.current_port,
            "name": hostname,
            "pin": self.auth_token,
            "token": self.auth_token
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
                    self.lbl_quick_apps_status.config(
                        text="No quick apps saved yet. Select an installed app above to add one.", fg=TEXT_MUTED
                    )
                else:
                    self.lbl_quick_apps_status.config(
                        text=f"{len(quick_apps)} quick app(s). Select one to rename or remove it.", fg=TEXT_MUTED
                    )

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
        apps = [dict(item, is_quick=os.path.realpath(item["path"]).lower() in quick_paths) for item in self.available_apps]
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
            self.after(0, lambda: self.btn_toggle.config(text="Stop Server", fg=ACCENT_RED))
        else:
            self.after(0, lambda: self.lbl_status_pill.config(text="○ SERVER STOPPED", fg=ACCENT_RED))
            self.after(0, lambda: self.btn_toggle.config(text="Start Server", fg=ACCENT_GREEN))

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
            if hasattr(self, "lbl_sidebar_pin"):
                self.lbl_sidebar_pin.config(text=f"PIN: {new_token}")
            if self.server:
                self.server.auth_token = new_token
                if hasattr(self.server, "ws_server") and self.server.ws_server:
                    self.server.ws_server.auth_token = new_token
            self.generate_qr()
            self.generate_wan_qr()
            messagebox.showinfo("Pairing PIN Updated", "The new six-digit PIN is active for Wi-Fi and remote pairing.")
        else:
            messagebox.showerror("Invalid PIN", "Use exactly six digits for the pairing PIN.")

    def copy_wan_url(self):
        url = f"ws://{self.current_ip}:{self.ws_port}"
        self.clipboard_clear()
        self.clipboard_append(url)
        messagebox.showinfo(
            "Copied", f"Local WebSocket URL '{url}' copied. For Internet access, use a TLS-protected wss:// endpoint."
        )

    def generate_wan_qr(self):
        if not HAS_QR:
            if hasattr(self, "wan_qr_label"):
                self.wan_qr_label.config(text="QR library not found.", fg=ACCENT_RED, bg="#FFFFFF")
            return
        hostname = socket.gethostname()
        payload = json.dumps({
            "type": "btmouse_wan",
            "url": f"ws://{self.current_ip}:{self.ws_port}",
            "ip": self.current_ip,
            "port": self.ws_port,
            "name": hostname,
            "pin": self.auth_token,
            "token": self.auth_token
        })
        try:
            qr = qrcode.QRCode(version=1, box_size=5, border=2)
            qr.add_data(payload)
            qr.make(fit=True)
            img = qr.make_image(fill_color="black", back_color="white")
            img = img.resize((200, 200), Image.Resampling.NEAREST)
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
                last_path = getattr(self.server.camera_manager, "last_snapshot_path", None)
                snap_name = os.path.basename(last_path) if last_path else "snapshot.jpg"

                def _update_ui():
                    self.cam_photo = photo
                    self.lbl_cam_preview.config(image=self.cam_photo)
                    self.lbl_media_status.config(text=f"✓ Camera snapshot captured & saved: {snap_name}", fg=ACCENT_GREEN)
                    self.refresh_recordings()

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
                self.after(
                    0,
                    lambda: self.lbl_media_status.config(
                        text=f"✓ Recorded {os.path.basename(final_file)} ({sz} bytes)", fg=ACCENT_GREEN
                    ),
                )
                self.after(0, self.refresh_recordings)
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
