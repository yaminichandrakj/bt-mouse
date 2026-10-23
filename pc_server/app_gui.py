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
import os
import sys
import time
import secrets

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

from server import Server, ensure_interactive_desktop, IS_WINDOWS

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
        self.auth_token = f"{secrets.randbelow(1_000_000):06d}"
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

        # System Tray setup
        self.tray_icon = None
        self._setup_system_tray()

        # Start server automatically
        self.start_server()

        # Handle window close: minimize to tray instead of quitting
        self.protocol("WM_DELETE_WINDOW", self.hide_to_tray)

    def _init_server(self):
        """Initializes a clean Server instance with bound callbacks"""
        self.server = Server(port=self.current_port, ws_port=self.ws_port, auth_token=self.auth_token)
        self.adapters = self.server.get_all_ips()
        self.current_ip = self.server.get_local_ip()

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

        btn_copy = tk.Button(info_card, text="📋 Copy IP Address", font=("Segoe UI", 9, "bold"),
                             bg=BUTTON_BG, fg=TEXT_PRIMARY, activebackground=CARD_BORDER,
                             relief="flat", padx=10, pady=8, command=self.copy_ip, cursor="hand2")
        btn_copy.pack(fill="x", pady=(16, 6))

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

        # Action buttons grid
        btn_grid = tk.Frame(card, bg=CARD_BG)
        btn_grid.pack(anchor="w", fill="x", pady=4)

        apps_to_test = [
            ("🌐 Chrome", "chrome"),
            ("📝 OneNote", "onenote"),
            ("🎵 Spotify", "spotify"),
            ("💻 Terminal", "terminal"),
            ("📊 Task Manager", "taskmgr"),
            ("🧮 Calculator", "calc")
        ]
        for idx, (label, app_id) in enumerate(apps_to_test):
            b = tk.Button(btn_grid, text=label, font=("Segoe UI", 9), bg=BUTTON_BG, fg=TEXT_PRIMARY,
                          relief="flat", padx=10, pady=6, cursor="hand2",
                          command=lambda a=app_id: self.server._launch_application(a) if self.server else None)
            b.grid(row=idx // 3, column=idx % 3, padx=4, pady=4, sticky="ew")

        # Power Controls
        pwr_frame = tk.Frame(card, bg=CARD_BG)
        pwr_frame.pack(anchor="w", fill="x", pady=10)

        lbl_pwr = tk.Label(pwr_frame, text="Power Actions:", font=("Segoe UI", 9, "bold"), bg=CARD_BG, fg=TEXT_MUTED)
        lbl_pwr.pack(side="left", padx=(0, 8))

        btn_lock = tk.Button(pwr_frame, text="🔒 Lock Workstation", font=("Segoe UI", 9, "bold"),
                             bg=BUTTON_BG, fg=ACCENT_RED, relief="flat", padx=10, pady=4, cursor="hand2",
                             command=lambda: self.server._handle_system_power("lock") if self.server else None)
        btn_lock.pack(side="left", padx=4)

        # Hardware & Multimedia tests
        media_frame = tk.Frame(card, bg=CARD_BG)
        media_frame.pack(anchor="w", fill="x", pady=12)

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

        lbl_foot = tk.Label(bar, text="BT Mouse Companion v1.1 | Minimizes to system tray | Native Windows Hardware Input",
                            font=("Segoe UI", 8), bg=CARD_BG, fg=TEXT_MUTED)
        lbl_foot.pack(side="left")

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
            self.auth_token = new_token
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
            time.sleep(3.0)
            final_file = self.server.audio_manager.stop_recording()
            if final_file and os.path.exists(final_file):
                sz = os.path.getsize(final_file)
                self.after(0, lambda: self.lbl_media_status.config(
                    text=f"✓ Recorded {os.path.basename(final_file)} ({sz} bytes)", fg=ACCENT_GREEN))
            else:
                self.after(0, lambda: self.lbl_media_status.config(text="✕ Mic recording failed.", fg=ACCENT_RED))
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
    app = CompanionApp()
    app.mainloop()
