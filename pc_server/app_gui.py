"""
BT Mouse & Keyboard - Windows 11 Native Qt6 Desktop Companion Application
Built on PySide6 (Qt for Python, LGPL; Qt6 hardware-accelerated rendering) with Windows 11 DWM
Dark Titlebar, DPI-independent Fluent Sidebar Navigation, Fullscreen Video
Monitor for both Phone & PC Webcams, and separated Videos / Audios / Snapshots folders.
"""

import os
import sys
import time
import json
import re
import socket
import base64
import shutil
import hashlib
import tempfile
import threading
import subprocess
import webbrowser
import urllib.request
import urllib.error
from pathlib import Path
from datetime import datetime

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
    from PIL import Image
    HAS_QR = True
except ImportError:
    HAS_QR = False

try:
    from PySide6.QtCore import Qt, QTimer, Signal, Slot, QObject, QSize, QRect, QPoint
    from PySide6.QtGui import QIcon, QPixmap, QImage, QFont, QColor, QAction, QPainter, QPen, QBrush, QCursor
    from PySide6.QtWidgets import (
        QApplication,
        QMainWindow,
        QWidget,
        QVBoxLayout,
        QHBoxLayout,
        QGridLayout,
        QLabel,
        QPushButton,
        QFrame,
        QStackedWidget,
        QComboBox,
        QLineEdit,
        QCheckBox,
        QSpinBox,
        QListWidget,
        QListWidgetItem,
        QTableWidget,
        QTableWidgetItem,
        QHeaderView,
        QAbstractItemView,
        QPlainTextEdit,
        QScrollArea,
        QSystemTrayIcon,
        QMenu,
        QMessageBox,
        QDialog,
        QSizePolicy,
    )
    HAS_QT = True
except ImportError:
    HAS_QT = False
    QMainWindow = object
    QWidget = object
    QDialog = object
    QLabel = object

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
    get_btmouse_media_dirs,
)

APP_VERSION = "1.4.0"
GITHUB_REPOSITORY = "yaminichandrakj/bt-mouse"
MAX_COMPANION_UPDATE_BYTES = 180 * 1024 * 1024
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
            try:
                import ctypes
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


def apply_windows_dwm_dark_frame(hwnd):
    """Enable Windows 11 native DWM dark caption bar and rounded window corners."""
    if not IS_WINDOWS or not hwnd:
        return
    try:
        import ctypes
        dwmapi = ctypes.windll.dwmapi
        dark = ctypes.c_int(1)
        if dwmapi.DwmSetWindowAttribute(int(hwnd), 20, ctypes.byref(dark), ctypes.sizeof(dark)) != 0:
            dwmapi.DwmSetWindowAttribute(int(hwnd), 19, ctypes.byref(dark), ctypes.sizeof(dark))
        corner = ctypes.c_int(2)  # DWMWCP_ROUND
        dwmapi.DwmSetWindowAttribute(int(hwnd), 33, ctypes.byref(corner), ctypes.sizeof(corner))
        caption_col = ctypes.c_int(0x001E130C)  # #0C131E
        dwmapi.DwmSetWindowAttribute(int(hwnd), 35, ctypes.byref(caption_col), ctypes.sizeof(caption_col))
        text_col = ctypes.c_int(0x00FCF8F8)
        dwmapi.DwmSetWindowAttribute(int(hwnd), 36, ctypes.byref(text_col), ctypes.sizeof(text_col))
    except Exception:
        pass


FLUENT_QSS = """
QMainWindow, QDialog {
    background-color: #0B0F17;
    color: #F8FAFC;
    font-family: "Segoe UI Variable Display", "Segoe UI", sans-serif;
}
QWidget {
    color: #F8FAFC;
    font-family: "Segoe UI Variable Text", "Segoe UI", sans-serif;
    font-size: 13px;
}
QScrollArea {
    background-color: transparent;
    border: none;
}
QFrame#HeaderBar {
    background-color: #0F1623;
    border-bottom: 1px solid #1E293B;
}
QFrame#Sidebar {
    background-color: #0F1623;
    border-right: 1px solid #1E293B;
}
QFrame#SidebarFooter {
    background-color: #151F30;
    border: 1px solid #26354D;
    border-radius: 10px;
}
QFrame#Card {
    background-color: #131B29;
    border: 1px solid #222F44;
    border-radius: 12px;
}
QFrame#SubCard {
    background-color: #182234;
    border: 1px solid #283750;
    border-radius: 10px;
}
QFrame#StatusBar {
    background-color: #0F1623;
    border-top: 1px solid #1E293B;
}
QPushButton {
    background-color: #1E293B;
    color: #F8FAFC;
    border: 1px solid #2E3F59;
    border-radius: 8px;
    padding: 8px 14px;
    font-weight: 600;
}
QPushButton:hover {
    background-color: #283750;
    border-color: #38BDF8;
}
QPushButton:pressed {
    background-color: #151F30;
}
QPushButton:disabled {
    background-color: #141B26;
    color: #64748B;
    border-color: #1E293B;
}
QPushButton#PrimaryBtn {
    background-color: #6366F1;
    border: 1px solid #818CF8;
    color: #FFFFFF;
}
QPushButton#PrimaryBtn:hover {
    background-color: #4F46E5;
}
QPushButton#DangerBtn {
    background-color: #2A1621;
    border: 1px solid #F43F5E;
    color: #FDA4AF;
}
QPushButton#DangerBtn:hover {
    background-color: #F43F5E;
    color: #FFFFFF;
}
QPushButton#NavBtn {
    background-color: transparent;
    border: none;
    border-left: 3px solid transparent;
    border-radius: 8px;
    padding: 10px 14px;
    text-align: left;
    color: #CBD5E1;
    font-size: 13px;
    font-weight: 600;
}
QPushButton#NavBtn:hover {
    background-color: #172133;
    color: #F8FAFC;
}
QPushButton#NavBtn[active="true"] {
    background-color: #1D2B42;
    border-left: 3px solid #38BDF8;
    color: #38BDF8;
    font-weight: 700;
}
QLineEdit, QComboBox, QSpinBox {
    background-color: #182234;
    color: #F8FAFC;
    border: 1px solid #2C3D57;
    border-radius: 8px;
    padding: 7px 10px;
    selection-background-color: #6366F1;
}
QLineEdit:focus, QComboBox:focus, QSpinBox:focus {
    border-color: #38BDF8;
}
QComboBox::drop-down {
    border: none;
    width: 24px;
}
QComboBox QAbstractItemView {
    background-color: #151F30;
    color: #F8FAFC;
    border: 1px solid #2C3D57;
    selection-background-color: #6366F1;
    outline: none;
}
QListWidget, QPlainTextEdit, QTableWidget {
    background-color: #0E1420;
    color: #F8FAFC;
    border: 1px solid #243248;
    border-radius: 10px;
    padding: 4px;
    gridline-color: #1E293B;
    selection-background-color: #4F46E5;
}
QHeaderView::section {
    background-color: #151F30;
    color: #38BDF8;
    font-weight: 700;
    border: none;
    border-bottom: 1px solid #26354D;
    padding: 8px 10px;
}
QCheckBox {
    spacing: 8px;
    color: #E2E8F0;
    font-weight: 500;
}
QCheckBox::indicator {
    width: 18px;
    height: 18px;
    border-radius: 5px;
    border: 1px solid #3B4F6B;
    background-color: #182234;
}
QCheckBox::indicator:checked {
    background-color: #6366F1;
    border-color: #818CF8;
}
QScrollBar:vertical {
    background: #0B0F17;
    width: 10px;
    margin: 0px;
    border-radius: 5px;
}
QScrollBar::handle:vertical {
    background: #283750;
    min-height: 24px;
    border-radius: 5px;
}
QScrollBar::handle:vertical:hover {
    background: #38BDF8;
}
QScrollBar::add-line:vertical, QScrollBar::sub-line:vertical {
    height: 0px;
}
"""


# Segoe Fluent Icons (Windows 11) / Segoe MDL2 Assets (Windows 10) code points.
FLUENT_GLYPHS = {
    "add": 0xE710,
    "apps": 0xECAA,
    "bluetooth": 0xE702,
    "camera": 0xE722,
    "clear": 0xE894,
    "close": 0xE711,
    "copy": 0xE8C8,
    "crop": 0xE7A8,
    "delete": 0xE74D,
    "folder": 0xE8B7,
    "fullscreen": 0xE740,
    "globe": 0xE774,
    "key": 0xE8D7,
    "library": 0xE8F1,
    "lock": 0xE72E,
    "mic": 0xE720,
    "monitor": 0xE7F4,
    "open": 0xE8A7,
    "paint": 0xE790,
    "pen": 0xE70F,
    "photo": 0xE91B,
    "play": 0xE768,
    "pulse": 0xE9D9,
    "record": 0xE7C8,
    "refresh": 0xE72C,
    "rotate": 0xE7AD,
    "search": 0xE721,
    "settings": 0xE713,
    "stop": 0xE71A,
    "video": 0xE714,
    "wifi": 0xE701,
}
ICON_COLOR = "#CBD5E1"
ICON_COLOR_MUTED = "#94A3B8"
ICON_COLOR_ACCENT = "#38BDF8"
_icon_family = None
_icon_cache = {}


def fluent_icon(name, color=ICON_COLOR):
    """Render a Windows icon-font glyph as a QIcon; returns an empty icon if the font is missing."""
    global _icon_family
    key = (name, color)
    if key in _icon_cache:
        return _icon_cache[key]
    if _icon_family is None:
        from PySide6.QtGui import QFontDatabase

        families = set(QFontDatabase.families())
        _icon_family = next((f for f in ("Segoe Fluent Icons", "Segoe MDL2 Assets") if f in families), "")
    icon = QIcon()
    glyph = FLUENT_GLYPHS.get(name)
    if _icon_family and glyph:
        size = 64
        pix = QPixmap(size, size)
        pix.fill(Qt.GlobalColor.transparent)
        painter = QPainter(pix)
        painter.setRenderHint(QPainter.RenderHint.TextAntialiasing)
        font = QFont(_icon_family)
        font.setPixelSize(int(size * 0.78))
        painter.setFont(font)
        painter.setPen(QColor(color))
        painter.drawText(pix.rect(), Qt.AlignmentFlag.AlignCenter, chr(glyph))
        painter.end()
        icon = QIcon(pix)
    _icon_cache[key] = icon
    return icon


def make_button(text, icon=None):
    btn = QPushButton(text)
    if icon:
        btn.setIcon(fluent_icon(icon, "#E2E8F0"))
        btn.setIconSize(QSize(16, 16))
    return btn


if HAS_QT:
    class _UiBridge(QObject):
        log_signal = Signal(str)
        client_signal = Signal(str, bool)
        status_signal = Signal(bool)
        packet_signal = Signal(int)
        phone_cam_signal = Signal(bytes, int, int, str, float, float)
        pc_cam_signal = Signal(bytes)
        # Worker threads have no Qt event loop, so QTimer.singleShot from them never fires.
        # Emitting this signal queues the callable onto the GUI thread instead.
        invoke_signal = Signal(object)

        @Slot(object)
        def _invoke(self, fn):
            fn()

    class RegionSelectorOverlay(QWidget):
        """Translucent full-screen overlay for dragging out the media player preview rectangle."""

        def __init__(self, on_selected):
            super().__init__(None, Qt.WindowType.FramelessWindowHint | Qt.WindowType.WindowStaysOnTopHint | Qt.WindowType.Tool)
            self._on_selected = on_selected
            self._origin = None
            self._current = None
            self.setAttribute(Qt.WidgetAttribute.WA_TranslucentBackground)
            self.setAttribute(Qt.WidgetAttribute.WA_DeleteOnClose)
            self.setCursor(QCursor(Qt.CursorShape.CrossCursor))
            screen = QApplication.primaryScreen()
            self._dpr = screen.devicePixelRatio()
            self.setGeometry(screen.geometry())

        def paintEvent(self, event):
            painter = QPainter(self)
            painter.setRenderHint(QPainter.RenderHint.Antialiasing)
            painter.fillRect(self.rect(), QColor(11, 15, 23, 190))
            painter.setPen(QColor("#F8FAFC"))
            title_font = QFont("Segoe UI Variable Display", 18, QFont.Weight.DemiBold)
            painter.setFont(title_font)
            painter.drawText(QRect(0, 36, self.width(), 34), Qt.AlignmentFlag.AlignCenter, "Drag around the video player area  ·  Esc to cancel")
            painter.setPen(QColor("#94A3B8"))
            painter.setFont(QFont("Segoe UI Variable Text", 11))
            painter.drawText(
                QRect(0, 74, self.width(), 24),
                Qt.AlignmentFlag.AlignCenter,
                "Only the selected rectangle is sent to your phone while preview is enabled.",
            )
            if self._origin and self._current:
                sel = QRect(self._origin, self._current).normalized()
                painter.setCompositionMode(QPainter.CompositionMode.CompositionMode_Clear)
                painter.fillRect(sel, Qt.GlobalColor.transparent)
                painter.setCompositionMode(QPainter.CompositionMode.CompositionMode_SourceOver)
                painter.fillRect(sel, QColor(56, 189, 248, 28))
                painter.setPen(QPen(QColor("#38BDF8"), 2))
                painter.setBrush(Qt.BrushStyle.NoBrush)
                painter.drawRoundedRect(sel, 6, 6)
                w_px, h_px = int(sel.width() * self._dpr), int(sel.height() * self._dpr)
                painter.setPen(QColor("#F8FAFC"))
                painter.drawText(sel.x(), max(18, sel.y() - 8), f"{w_px} × {h_px}")

        def mousePressEvent(self, event):
            if event.button() == Qt.MouseButton.LeftButton:
                self._origin = event.position().toPoint()
                self._current = self._origin
                self.update()

        def mouseMoveEvent(self, event):
            if self._origin:
                self._current = event.position().toPoint()
                self.update()

        def mouseReleaseEvent(self, event):
            if not self._origin or event.button() != Qt.MouseButton.LeftButton:
                return
            sel = QRect(self._origin, event.position().toPoint()).normalized()
            # ImageGrab works in physical pixels (the process is per-monitor DPI aware).
            origin = self.geometry().topLeft()
            region = {
                "x": int((origin.x() + sel.x()) * self._dpr),
                "y": int((origin.y() + sel.y()) * self._dpr),
                "width": int(sel.width() * self._dpr),
                "height": int(sel.height() * self._dpr),
            }
            try:
                self._on_selected(region)
                self.close()
            except ValueError as exc:
                self._origin = self._current = None
                self.update()
                QMessageBox.information(self, "Select player area", str(exc))

        def keyPressEvent(self, event):
            if event.key() == Qt.Key.Key_Escape:
                self.close()

    class AspectRatioVideoWidget(QLabel):
        """Responsive video surface that scales frames smoothly to fill available space and supports double-click fullscreen."""
        double_clicked = Signal()

        def __init__(self, placeholder_text="", parent=None):
            super().__init__(parent)
            self._placeholder_text = placeholder_text
            self._pixmap = None
            self.setAlignment(Qt.AlignmentFlag.AlignCenter)
            self.setMinimumSize(320, 220)
            self.setSizePolicy(QSizePolicy.Policy.Expanding, QSizePolicy.Policy.Expanding)
            self.setStyleSheet(
                "background-color: #050811; border: 1px solid #243248; border-radius: 10px; color: #94A3B8; font-size: 13px; padding: 16px;"
            )
            self.setText(placeholder_text)
            self.setWordWrap(True)

        def set_frame_bytes(self, jpeg_bytes):
            if not jpeg_bytes:
                return
            img = QImage.fromData(jpeg_bytes)
            if not img.isNull():
                self._pixmap = QPixmap.fromImage(img)
                self.setText("")
                self._render_scaled()

        def clear_frame(self):
            self._pixmap = None
            self.setPixmap(QPixmap())
            self.setText(self._placeholder_text)

        def _render_scaled(self):
            if self._pixmap and not self._pixmap.isNull():
                avail = self.size() - QSize(12, 12)
                if avail.width() > 10 and avail.height() > 10:
                    scaled = self._pixmap.scaled(
                        avail,
                        Qt.AspectRatioMode.KeepAspectRatio,
                        Qt.TransformationMode.SmoothTransformation,
                    )
                    super().setPixmap(scaled)

        def resizeEvent(self, event):
            super().resizeEvent(event)
            if self._pixmap is not None:
                self._render_scaled()

        def mouseDoubleClickEvent(self, event):
            super().mouseDoubleClickEvent(event)
            self.double_clicked.emit()

    class FullscreenVideoWindow(QDialog):
        """Edge-to-edge Fullscreen/Maximizable Video Monitor for Phone or PC Camera streams."""

        def __init__(self, title, on_rotate=None, on_snapshot=None, on_toggle_record=None, parent=None):
            super().__init__(parent)
            self.setWindowTitle(title)
            self.resize(1280, 760)
            self.setModal(False)
            apply_windows_dwm_dark_frame(self.winId())

            layout = QVBoxLayout(self)
            layout.setContentsMargins(12, 12, 12, 12)
            layout.setSpacing(10)

            # Top Control Bar
            top_bar = QFrame()
            top_bar.setObjectName("SubCard")
            top_row = QHBoxLayout(top_bar)
            top_row.setContentsMargins(14, 8, 14, 8)

            self.lbl_title = QLabel(title)
            self.lbl_title.setStyleSheet("font-size: 15px; font-weight: 700; color: #F8FAFC;")
            top_row.addWidget(self.lbl_title)

            self.lbl_meta = QLabel("LIVE VIEW")
            self.lbl_meta.setStyleSheet("color: #38BDF8; font-weight: 700; margin-left: 12px;")
            top_row.addWidget(self.lbl_meta)
            top_row.addStretch()

            if on_rotate:
                btn_rot = make_button("Rotate 90°", "rotate")
                btn_rot.clicked.connect(on_rotate)
                top_row.addWidget(btn_rot)

            if on_snapshot:
                btn_snap = make_button("Snapshot", "camera")
                btn_snap.clicked.connect(on_snapshot)
                top_row.addWidget(btn_snap)

            if on_toggle_record:
                self.btn_rec = make_button("Record", "record")
                self.btn_rec.setObjectName("PrimaryBtn")
                self.btn_rec.clicked.connect(on_toggle_record)
                top_row.addWidget(self.btn_rec)

            btn_fs = make_button("Fullscreen", "fullscreen")
            btn_fs.clicked.connect(self._toggle_fullscreen_state)
            top_row.addWidget(btn_fs)

            btn_close = make_button("Close", "close")
            btn_close.setObjectName("DangerBtn")
            btn_close.clicked.connect(self.close)
            top_row.addWidget(btn_close)

            layout.addWidget(top_bar)

            self.video_surface = AspectRatioVideoWidget("Waiting for live video stream...", self)
            self.video_surface.double_clicked.connect(self._toggle_fullscreen_state)
            layout.addWidget(self.video_surface, 1)

        def _toggle_fullscreen_state(self):
            if self.isFullScreen():
                self.showMaximized()
            else:
                self.showFullScreen()

        def keyPressEvent(self, event):
            if event.key() == Qt.Key.Key_F11:
                self._toggle_fullscreen_state()
            else:
                super().keyPressEvent(event)


class CompanionApp(QMainWindow):
    def __init__(self):
        if not HAS_QT:
            return
        super().__init__()
        ensure_interactive_desktop()

        self.dpi_scale, self.system_dpi = get_windows_dpi_scale()
        self.current_port = 53530
        self.ws_port = 53531
        self.auth_token = load_or_create_pairing_pin()

        self.server = None
        self.server_thread = None
        self.is_server_running = False
        self.packet_count = 0
        self.last_client = "None"
        self.pc_webcam_preview_active = False

        self.bridge = _UiBridge()
        self.bridge.log_signal.connect(self._append_log)
        self.bridge.client_signal.connect(self._update_client_ui)
        self.bridge.status_signal.connect(self._update_status_ui)
        self.bridge.packet_signal.connect(self._update_packet_ui)
        self.bridge.phone_cam_signal.connect(self._update_phone_cam_ui)
        self.bridge.pc_cam_signal.connect(self._update_pc_cam_ui)
        self.bridge.invoke_signal.connect(self.bridge._invoke)

        self.phone_fullscreen_win = None
        self.pc_fullscreen_win = None

        self._init_server()

        executable_path = Path(sys.executable).resolve()
        self.setWindowTitle(f"BT Mouse Companion v{APP_VERSION} — {executable_path}")
        self.setMinimumSize(1040, 720)
        self.resize(1160, 780)

        self.icon_path = get_asset_path("icon.ico")
        self.icon_png_path = get_asset_path("icon.png")
        if os.path.exists(self.icon_png_path):
            self.setWindowIcon(QIcon(self.icon_png_path))
        elif os.path.exists(self.icon_path):
            self.setWindowIcon(QIcon(self.icon_path))

        self.setStyleSheet(FLUENT_QSS + self._checkmark_qss())
        apply_windows_dwm_dark_frame(self.winId())

        # Build Fluent Main Layout
        central = QWidget()
        self.setCentralWidget(central)
        root_layout = QVBoxLayout(central)
        root_layout.setContentsMargins(0, 0, 0, 0)
        root_layout.setSpacing(0)

        self._build_header(root_layout)
        self._build_body(root_layout)
        self._build_statusbar(root_layout)

        # System Tray
        self.tray_icon = None
        self._setup_system_tray()

        # Timers
        self.recordings_timer = QTimer(self)
        self.recordings_timer.timeout.connect(self.refresh_recordings)
        self.recordings_timer.start(4000)

        self.phone_cam_timer = QTimer(self)
        self.phone_cam_timer.timeout.connect(self._check_phone_cam_active)
        self.phone_cam_timer.start(2000)

        QTimer.singleShot(400, self._show_update_recovery_notice)
        QTimer.singleShot(250, self.refresh_quick_apps)

        self.start_server()
        self._write_update_ready_marker()
        self._heal_startup_path_if_registered()

        if "--minimized" in sys.argv:
            QTimer.singleShot(500, self.hide_to_tray)

    @staticmethod
    def _checkmark_qss():
        """QSS can only draw an indicator tick from an image file, so render one once at startup."""
        try:
            size = 36
            pix = QPixmap(size, size)
            pix.fill(Qt.GlobalColor.transparent)
            painter = QPainter(pix)
            painter.setRenderHint(QPainter.RenderHint.Antialiasing)
            pen = QPen(QColor("#FFFFFF"), 5)
            pen.setCapStyle(Qt.PenCapStyle.RoundCap)
            pen.setJoinStyle(Qt.PenJoinStyle.RoundJoin)
            painter.setPen(pen)
            painter.drawPolyline([QPoint(9, 19), QPoint(15, 25), QPoint(27, 11)])
            painter.end()
            path = Path(tempfile.gettempdir()) / "btmouse-checkmark.png"
            if not pix.save(str(path), "PNG"):
                return ""
            return 'QCheckBox::indicator:checked { image: url("%s"); }' % path.as_posix()
        except Exception:
            return ""

    # ------------------------------------------------------------------
    # Startup & Self-Healing Helpers (also tested directly in unit tests)
    # ------------------------------------------------------------------
    @staticmethod
    def _write_update_ready_marker():
        try:
            marker_arg = sys.argv.index("--btmouse-update-ready")
            marker_path = Path(sys.argv[marker_arg + 1])
            marker_path.write_text(APP_VERSION, encoding="ascii")
        except (ValueError, IndexError, OSError):
            pass

    @staticmethod
    def _startup_registry_value():
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
            if hasattr(self, "chk_startup"):
                self.chk_startup.blockSignals(True)
                self.chk_startup.setChecked(not enabled)
                self.chk_startup.blockSignals(False)
            QMessageBox.critical(self, "Startup setting", f"Could not update the Windows sign-in setting.\n\n{exc}")

    def _heal_startup_path_if_registered(self):
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

    # ------------------------------------------------------------------
    # Server Initialization & Media Folders
    # ------------------------------------------------------------------
    def _init_server(self):
        self.media_dirs = get_btmouse_media_dirs()
        self.server = Server(port=self.current_port, ws_port=self.ws_port, auth_token=self.auth_token)
        self.available_apps = []
        self.quick_apps = []
        self.adapters = self.server.get_all_ips()
        self.current_ip = self.server.get_local_ip()
        self.recording_settings_path = Path(self.media_dirs["base"]) / "recording_settings.json"
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
        self.auto_record_audio = bool(preferences.get("auto_record_audio", True))
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
        self.server.on_pc_cam_frame_callback = self._on_pc_cam_frame_received
        if hasattr(self.server, "phone_camera_receiver") and self.server.phone_camera_receiver:
            self.server.phone_camera_receiver.register_callback(self._on_phone_cam_frame_received)

    # ------------------------------------------------------------------
    # Fluent Shell Construction
    # ------------------------------------------------------------------
    def _build_header(self, root_layout):
        header = QFrame()
        header.setObjectName("HeaderBar")
        header.setFixedHeight(72)
        h_layout = QHBoxLayout(header)
        h_layout.setContentsMargins(22, 10, 22, 10)

        title_col = QVBoxLayout()
        title_col.setSpacing(2)

        title_row = QHBoxLayout()
        title_row.setSpacing(10)
        logo_path = get_asset_path("icon.png")
        if os.path.exists(logo_path):
            lbl_logo = QLabel()
            lbl_logo.setPixmap(
                QPixmap(logo_path).scaled(28, 28, Qt.AspectRatioMode.KeepAspectRatio, Qt.TransformationMode.SmoothTransformation)
            )
            title_row.addWidget(lbl_logo)
        lbl_title = QLabel("BT Mouse")
        lbl_title.setStyleSheet("font-size: 17px; font-weight: 600; color: #F8FAFC;")
        title_row.addWidget(lbl_title)

        lbl_ver = QLabel(f"v{APP_VERSION}")
        lbl_ver.setStyleSheet(
            "background-color: #1E293B; color: #38BDF8; border: 1px solid #334155; border-radius: 6px; padding: 2px 8px; font-size: 11px; font-weight: 700;"
        )
        title_row.addWidget(lbl_ver)
        title_row.addStretch()
        title_col.addLayout(title_row)

        lbl_sub = QLabel("Desktop companion  ·  Wi-Fi, Bluetooth and remote access")
        lbl_sub.setStyleSheet("color: #94A3B8; font-size: 12px;")
        title_col.addWidget(lbl_sub)
        h_layout.addLayout(title_col)

        h_layout.addStretch()

        self.lbl_status_pill = QLabel("●  Server running")
        self.lbl_status_pill.setStyleSheet(
            "background-color: #062E22; color: #10B981; border: 1px solid #059669; border-radius: 14px; padding: 6px 14px; font-weight: 700; font-size: 12px;"
        )
        h_layout.addWidget(self.lbl_status_pill, 0, Qt.AlignmentFlag.AlignVCenter)

        self.btn_toggle = make_button("Stop server", "stop")
        self.btn_toggle.setObjectName("DangerBtn")
        self.btn_toggle.clicked.connect(self.toggle_server)
        h_layout.addWidget(self.btn_toggle)

        self.btn_restart = make_button("Restart", "refresh")
        self.btn_restart.clicked.connect(self.restart_server)
        h_layout.addWidget(self.btn_restart)

        self.btn_update = make_button("Check for updates", "refresh")
        self.btn_update.setObjectName("PrimaryBtn")
        self.btn_update.clicked.connect(self.check_for_companion_updates)
        h_layout.addWidget(self.btn_update)

        root_layout.addWidget(header)

    def _build_body(self, root_layout):
        body = QWidget()
        body_layout = QHBoxLayout(body)
        body_layout.setContentsMargins(0, 0, 0, 0)
        body_layout.setSpacing(0)

        # Left Navigation Sidebar (Wide enough on all DPIs so nothing ever clips!)
        sidebar = QFrame()
        sidebar.setObjectName("Sidebar")
        sidebar.setMinimumWidth(250)
        sidebar.setMaximumWidth(270)
        sb_layout = QVBoxLayout(sidebar)
        sb_layout.setContentsMargins(12, 16, 12, 16)
        sb_layout.setSpacing(4)

        lbl_nav = QLabel("WORKSPACE")
        lbl_nav.setStyleSheet("color: #64748B; font-size: 11px; font-weight: 700; padding-left: 10px; margin-bottom: 4px;")
        sb_layout.addWidget(lbl_nav)

        self.stack = QStackedWidget()
        self.nav_buttons = []

        pages = [
            ("Wi-Fi && QR pairing", "wifi", self._create_wifi_page),
            ("Phone as webcam", "camera", self._create_phone_cam_page),
            ("Media library", "library", self._create_recordings_page),
            ("Webcam && quick apps", "apps", self._create_desktop_page),
            ("Bluetooth", "bluetooth", self._create_bt_page),
            ("Remote access", "globe", self._create_wan_page),
            ("Drawing slate", "pen", self._create_tablet_page),
            ("Activity", "pulse", self._create_logs_page),
        ]

        self.nav_icons = []
        for idx, (label, icon_name, builder) in enumerate(pages):
            btn = QPushButton(label)
            btn.setObjectName("NavBtn")
            btn.setIconSize(QSize(18, 18))
            btn.setCursor(QCursor(Qt.CursorShape.PointingHandCursor))
            self.nav_icons.append(icon_name)
            btn.clicked.connect(lambda _checked=False, i=idx: self._switch_page(i))
            sb_layout.addWidget(btn)
            self.nav_buttons.append(btn)

            page_widget = builder()
            self.stack.addWidget(page_widget)

        sb_layout.addStretch()

        # Quick Folders Bar in Sidebar
        folders_card = QFrame()
        folders_card.setObjectName("SidebarFooter")
        fc_layout = QVBoxLayout(folders_card)
        fc_layout.setContentsMargins(12, 10, 12, 10)
        fc_layout.setSpacing(6)

        lbl_qf = QLabel("QUICK MEDIA FOLDERS")
        lbl_qf.setStyleSheet("color: #94A3B8; font-size: 10px; font-weight: 700;")
        fc_layout.addWidget(lbl_qf)

        folder_btn_row = QHBoxLayout()
        folder_btn_row.setSpacing(4)
        btn_vid_f = QPushButton("Videos")
        btn_vid_f.setStyleSheet("padding: 5px 6px; font-size: 11px;")
        btn_vid_f.clicked.connect(self.open_videos_folder)
        folder_btn_row.addWidget(btn_vid_f)

        btn_aud_f = QPushButton("Audios")
        btn_aud_f.setStyleSheet("padding: 5px 6px; font-size: 11px;")
        btn_aud_f.clicked.connect(self.open_audios_folder)
        folder_btn_row.addWidget(btn_aud_f)

        btn_snap_f = QPushButton("Snaps")
        btn_snap_f.setStyleSheet("padding: 5px 6px; font-size: 11px;")
        btn_snap_f.clicked.connect(self.open_snapshots_folder)
        folder_btn_row.addWidget(btn_snap_f)
        fc_layout.addLayout(folder_btn_row)

        self.lbl_sidebar_ip = QLabel(f"IP: {self.current_ip}:{self.current_port}")
        self.lbl_sidebar_ip.setStyleSheet("font-family: Consolas, monospace; font-size: 12px; font-weight: 700; color: #38BDF8; margin-top: 4px;")
        fc_layout.addWidget(self.lbl_sidebar_ip)

        self.lbl_sidebar_pin = QLabel(f"PIN: {self.auth_token}")
        self.lbl_sidebar_pin.setStyleSheet("font-family: Consolas, monospace; font-size: 13px; font-weight: 700; color: #10B981;")
        fc_layout.addWidget(self.lbl_sidebar_pin)

        sb_layout.addWidget(folders_card)

        body_layout.addWidget(sidebar)
        body_layout.addWidget(self.stack, 1)
        root_layout.addWidget(body, 1)

        self._switch_page(0)

    def _switch_page(self, index):
        self.stack.setCurrentIndex(index)
        for i, btn in enumerate(self.nav_buttons):
            btn.setProperty("active", "true" if i == index else "false")
            btn.setIcon(fluent_icon(self.nav_icons[i], ICON_COLOR_ACCENT if i == index else ICON_COLOR_MUTED))
            btn.style().unpolish(btn)
            btn.style().polish(btn)

    def switch_to_recordings_tab(self):
        self._switch_page(2)

    def _build_statusbar(self, root_layout):
        bar = QFrame()
        bar.setObjectName("StatusBar")
        bar.setFixedHeight(32)
        b_layout = QHBoxLayout(bar)
        b_layout.setContentsMargins(20, 0, 20, 0)

        self.lbl_footer_left = QLabel(
            f"BT Mouse Companion v{APP_VERSION}  ·  Minimizes to system tray on close  ·  Folders: Videos / Audios / Snapshots"
        )
        self.lbl_footer_left.setStyleSheet("color: #94A3B8; font-size: 11px;")
        b_layout.addWidget(self.lbl_footer_left)
        b_layout.addStretch()

        self.lbl_footer_client = QLabel("Client: Disconnected")
        self.lbl_footer_client.setStyleSheet("color: #94A3B8; font-size: 11px; font-weight: 700;")
        b_layout.addWidget(self.lbl_footer_client)

        root_layout.addWidget(bar)

    # ------------------------------------------------------------------
    # Page 1: Wi-Fi & Instant QR Pairing
    # ------------------------------------------------------------------
    def _create_wifi_page(self):
        page = QWidget()
        layout = QHBoxLayout(page)
        layout.setContentsMargins(20, 20, 20, 20)
        layout.setSpacing(16)

        # Left Card: QR Code
        qr_card = QFrame()
        qr_card.setObjectName("Card")
        qr_layout = QVBoxLayout(qr_card)
        qr_layout.setContentsMargins(24, 24, 24, 24)
        qr_layout.setAlignment(Qt.AlignmentFlag.AlignCenter)

        lbl_t = QLabel("Pair with a QR code")
        lbl_t.setStyleSheet("font-size: 17px; font-weight: 600;")
        lbl_t.setAlignment(Qt.AlignmentFlag.AlignCenter)
        qr_layout.addWidget(lbl_t)

        lbl_d = QLabel("Scan with the BT Mouse Android app — IP, UDP port, and 6-digit PIN auto-fill automatically.")
        lbl_d.setStyleSheet("color: #94A3B8; font-size: 12px;")
        lbl_d.setWordWrap(True)
        lbl_d.setAlignment(Qt.AlignmentFlag.AlignCenter)
        qr_layout.addWidget(lbl_d)
        qr_layout.addSpacing(12)

        self.qr_label = QLabel()
        self.qr_label.setFixedSize(240, 240)
        self.qr_label.setAlignment(Qt.AlignmentFlag.AlignCenter)
        self.qr_label.setStyleSheet("background-color: #FFFFFF; border-radius: 12px; padding: 10px;")
        qr_layout.addWidget(self.qr_label, 0, Qt.AlignmentFlag.AlignCenter)
        qr_layout.addSpacing(12)

        lbl_hint = QLabel("In the phone app: Connect  ›  Scan QR code")
        lbl_hint.setStyleSheet("color: #38BDF8; font-weight: 700; font-size: 13px;")
        lbl_hint.setAlignment(Qt.AlignmentFlag.AlignCenter)
        qr_layout.addWidget(lbl_hint)

        layout.addWidget(qr_card, 1)

        # Right Card: Network Details
        info_card = QFrame()
        info_card.setObjectName("Card")
        info_layout = QVBoxLayout(info_card)
        info_layout.setContentsMargins(24, 24, 24, 24)
        info_layout.setSpacing(12)

        lbl_it = QLabel("Connection details")
        lbl_it.setStyleSheet("font-size: 17px; font-weight: 600;")
        info_layout.addWidget(lbl_it)

        lbl_ad = QLabel("Network Adapter / Interface:")
        lbl_ad.setStyleSheet("color: #94A3B8; font-weight: 600;")
        info_layout.addWidget(lbl_ad)

        self.combo_adapter = QComboBox()
        for name, ip in self.adapters:
            self.combo_adapter.addItem(f"{name}: {ip}", ip)
        self.combo_adapter.currentIndexChanged.connect(self._on_adapter_selected)
        info_layout.addWidget(self.combo_adapter)

        grid = QHBoxLayout()
        grid.setSpacing(10)

        ip_box = QFrame()
        ip_box.setObjectName("SubCard")
        ip_l = QVBoxLayout(ip_box)
        lbl_iph = QLabel("LOCAL IP ADDRESS")
        lbl_iph.setStyleSheet("color: #94A3B8; font-size: 10px; font-weight: 700;")
        ip_l.addWidget(lbl_iph)
        self.lbl_ip_val = QLabel(self.current_ip)
        self.lbl_ip_val.setStyleSheet("font-family: Consolas, monospace; font-size: 20px; font-weight: 700; color: #38BDF8;")
        ip_l.addWidget(self.lbl_ip_val)
        grid.addWidget(ip_box, 2)

        port_box = QFrame()
        port_box.setObjectName("SubCard")
        port_l = QVBoxLayout(port_box)
        lbl_ph = QLabel("UDP PORT")
        lbl_ph.setStyleSheet("color: #94A3B8; font-size: 10px; font-weight: 700;")
        port_l.addWidget(lbl_ph)
        self.lbl_port_val = QLabel(str(self.current_port))
        self.lbl_port_val.setStyleSheet("font-family: Consolas, monospace; font-size: 20px; font-weight: 700; color: #F8FAFC;")
        port_l.addWidget(self.lbl_port_val)
        grid.addWidget(port_box, 1)

        info_layout.addLayout(grid)

        pin_box = QFrame()
        pin_box.setObjectName("SubCard")
        pin_l = QVBoxLayout(pin_box)
        lbl_pin_h = QLabel("6-DIGIT PAIRING PIN (EMBEDDED IN QR CODE)")
        lbl_pin_h.setStyleSheet("color: #94A3B8; font-size: 10px; font-weight: 700;")
        pin_l.addWidget(lbl_pin_h)
        self.lbl_wifi_pin = QLabel(self.auth_token)
        self.lbl_wifi_pin.setStyleSheet("font-family: Consolas, monospace; font-size: 26px; font-weight: 700; color: #10B981;")
        pin_l.addWidget(self.lbl_wifi_pin)
        info_layout.addWidget(pin_box)

        info_layout.addStretch()

        btn_copy_ip = make_button("Copy IP address", "copy")
        btn_copy_ip.clicked.connect(self.copy_ip)
        info_layout.addWidget(btn_copy_ip)

        btn_copy_pin = make_button("Copy pairing PIN", "key")
        btn_copy_pin.clicked.connect(self.copy_pairing_pin)
        info_layout.addWidget(btn_copy_pin)

        btn_ref = make_button("Refresh QR code", "refresh")
        btn_ref.setObjectName("PrimaryBtn")
        btn_ref.clicked.connect(self.generate_qr)
        info_layout.addWidget(btn_ref)

        layout.addWidget(info_card, 1)
        self.generate_qr()
        return page

    # ------------------------------------------------------------------
    # Page 2: Phone as Webcam (Large Auto-Scaling Viewfinder + Fullscreen + Orientation + Recording)
    # ------------------------------------------------------------------
    def _create_phone_cam_page(self):
        page = QWidget()
        layout = QHBoxLayout(page)
        layout.setContentsMargins(20, 20, 20, 20)
        layout.setSpacing(16)

        # Left: Expansive Video Viewfinder Card
        preview_card = QFrame()
        preview_card.setObjectName("Card")
        p_layout = QVBoxLayout(preview_card)
        p_layout.setContentsMargins(18, 16, 18, 16)
        p_layout.setSpacing(10)

        header_row = QHBoxLayout()
        lbl_t = QLabel("Phone camera")
        lbl_t.setStyleSheet("font-size: 15px; font-weight: 600;")
        header_row.addWidget(lbl_t)

        self.lbl_phone_cam_orientation = QLabel("ORIENTATION: AUTO")
        self.lbl_phone_cam_orientation.setStyleSheet(
            "background-color: #1E293B; color: #38BDF8; border-radius: 6px; padding: 4px 10px; font-size: 11px; font-weight: 700;"
        )
        header_row.addWidget(self.lbl_phone_cam_orientation)
        header_row.addStretch()

        self.lbl_phone_cam_status = QLabel("○ STANDBY")
        self.lbl_phone_cam_status.setStyleSheet("color: #94A3B8; font-weight: 700;")
        header_row.addWidget(self.lbl_phone_cam_status)
        p_layout.addLayout(header_row)

        self.lbl_phone_cam_preview = AspectRatioVideoWidget(
            "Phone camera on standby\n\nIn the phone app, open Cameras  ›  Phone cam  ›  Start broadcast.\n"
            "Double-click the video to go fullscreen.",
            self,
        )
        self.lbl_phone_cam_preview.double_clicked.connect(self.open_phone_cam_fullscreen)
        p_layout.addWidget(self.lbl_phone_cam_preview, 1)

        # Viewfinder Toolbar (telemetry gets its own line so it never squeezes the buttons)
        self.lbl_phone_cam_telemetry = QLabel("Waiting for stream from phone...")
        self.lbl_phone_cam_telemetry.setStyleSheet("color: #94A3B8; font-size: 12px;")
        p_layout.addWidget(self.lbl_phone_cam_telemetry)
        vf_bar = QHBoxLayout()
        vf_bar.addStretch()

        btn_rot = make_button("Rotate 90°", "rotate")
        btn_rot.clicked.connect(self.rotate_phone_cam_90)
        vf_bar.addWidget(btn_rot)

        self.btn_phone_cam_rec = make_button("Record", "record")
        self.btn_phone_cam_rec.clicked.connect(self.toggle_phone_cam_recording)
        vf_bar.addWidget(self.btn_phone_cam_rec)

        btn_snap = make_button("Snapshot", "camera")
        btn_snap.clicked.connect(self._save_phone_cam_snapshot)
        vf_bar.addWidget(btn_snap)

        btn_fs = make_button("Fullscreen", "fullscreen")
        btn_fs.setObjectName("PrimaryBtn")
        btn_fs.clicked.connect(self.open_phone_cam_fullscreen)
        vf_bar.addWidget(btn_fs)

        p_layout.addLayout(vf_bar)
        layout.addWidget(preview_card, 3)

        # Right: OBS / Virtual Camera & Folder Controls
        ctrl_card = QFrame()
        ctrl_card.setObjectName("Card")
        ctrl_card.setMinimumWidth(330)
        ctrl_card.setMaximumWidth(370)
        c_layout = QVBoxLayout(ctrl_card)
        c_layout.setContentsMargins(18, 16, 18, 16)
        c_layout.setSpacing(12)

        lbl_ct = QLabel("Virtual camera & OBS")
        lbl_ct.setStyleSheet("font-size: 15px; font-weight: 600;")
        c_layout.addWidget(lbl_ct)

        lbl_cd = QLabel("Route your phone camera into OBS Studio, Zoom, Teams, Discord, or Google Meet.")
        lbl_cd.setWordWrap(True)
        lbl_cd.setStyleSheet("color: #94A3B8; font-size: 12px;")
        c_layout.addWidget(lbl_cd)

        mjpeg_box = QFrame()
        mjpeg_box.setObjectName("SubCard")
        m_l = QVBoxLayout(mjpeg_box)
        lbl_mh = QLabel("LOCALHOST MJPEG URL")
        lbl_mh.setStyleSheet("color: #38BDF8; font-size: 10px; font-weight: 700;")
        m_l.addWidget(lbl_mh)

        port = getattr(getattr(self.server, "phone_camera_http", None), "port", 8766)
        mjpeg_url = f"http://127.0.0.1:{port}/phone_cam/mjpeg"
        self.lbl_phone_mjpeg_url = QLabel(mjpeg_url)
        self.lbl_phone_mjpeg_url.setStyleSheet("font-family: Consolas, monospace; font-size: 11px; font-weight: 700;")
        m_l.addWidget(self.lbl_phone_mjpeg_url)

        m_btns = QHBoxLayout()
        btn_cp = make_button("Copy URL", "copy")
        btn_cp.clicked.connect(self._copy_phone_mjpeg_url)
        m_btns.addWidget(btn_cp)

        btn_br = make_button("Open in browser", "open")
        btn_br.clicked.connect(lambda: webbrowser.open(self.lbl_phone_mjpeg_url.text()))
        m_btns.addWidget(btn_br)
        m_l.addLayout(m_btns)
        c_layout.addWidget(mjpeg_box)

        vcam_box = QFrame()
        vcam_box.setObjectName("SubCard")
        v_l = QVBoxLayout(vcam_box)
        vcam_status = getattr(getattr(self.server, "phone_camera_receiver", None), "virtual_cam_status", "MJPEG_STREAM")
        vcam_err = getattr(getattr(self.server, "phone_camera_receiver", None), "virtual_cam_error", "MJPEG stream ready.")
        self.lbl_vcam_status = QLabel(f"Driver Backend: {vcam_status}")
        self.lbl_vcam_status.setStyleSheet("font-weight: 700; color: #10B981;")
        v_l.addWidget(self.lbl_vcam_status)
        lbl_ve = QLabel(vcam_err)
        lbl_ve.setWordWrap(True)
        lbl_ve.setStyleSheet("color: #94A3B8; font-size: 11px;")
        v_l.addWidget(lbl_ve)
        c_layout.addWidget(vcam_box)

        c_layout.addStretch()

        btn_open_vids = make_button("Open Videos folder", "folder")
        btn_open_vids.clicked.connect(self.open_videos_folder)
        c_layout.addWidget(btn_open_vids)

        btn_open_snaps = make_button("Open Snapshots folder", "folder")
        btn_open_snaps.clicked.connect(self.open_snapshots_folder)
        c_layout.addWidget(btn_open_snaps)

        layout.addWidget(ctrl_card, 1)
        return page

    # ------------------------------------------------------------------
    # Page 3: Media Library (Videos / Audios / Snapshots in Separate Folders)
    # ------------------------------------------------------------------
    def _create_recordings_page(self):
        page = QWidget()
        layout = QVBoxLayout(page)
        layout.setContentsMargins(20, 20, 20, 20)
        layout.setSpacing(12)

        card = QFrame()
        card.setObjectName("Card")
        c_layout = QVBoxLayout(card)
        c_layout.setContentsMargins(20, 18, 20, 18)
        c_layout.setSpacing(12)

        # Header
        top_row = QHBoxLayout()
        t_col = QVBoxLayout()
        lbl_t = QLabel("Media library")
        lbl_t.setStyleSheet("font-size: 17px; font-weight: 600;")
        t_col.addWidget(lbl_t)
        lbl_s = QLabel("Automatically organized into dedicated Videos, Audios, and Snapshots folders on this PC.")
        lbl_s.setStyleSheet("color: #94A3B8; font-size: 12px;")
        lbl_s.setWordWrap(True)
        t_col.addWidget(lbl_s)
        top_row.addLayout(t_col, 1)

        btn_vf = make_button("Videos", "folder")
        btn_vf.clicked.connect(self.open_videos_folder)
        top_row.addWidget(btn_vf)

        btn_af = make_button("Audios", "folder")
        btn_af.clicked.connect(self.open_audios_folder)
        top_row.addWidget(btn_af)

        btn_sf = make_button("Snapshots", "folder")
        btn_sf.clicked.connect(self.open_snapshots_folder)
        top_row.addWidget(btn_sf)

        btn_ref = make_button("Refresh", "refresh")
        btn_ref.setObjectName("PrimaryBtn")
        btn_ref.clicked.connect(self.refresh_recordings)
        top_row.addWidget(btn_ref)
        c_layout.addLayout(top_row)

        # Stats Bar
        stats_bar = QFrame()
        stats_bar.setObjectName("SubCard")
        s_row = QHBoxLayout(stats_bar)
        s_row.setContentsMargins(14, 8, 14, 8)
        self.lbl_recordings_stats = QLabel("Loading library…")
        self.lbl_recordings_stats.setStyleSheet("font-weight: 700;")
        s_row.addWidget(self.lbl_recordings_stats)

        self.lbl_disk_free = QLabel("")
        self.lbl_disk_free.setStyleSheet("color: #10B981; font-weight: 700; margin-left: 14px;")
        s_row.addWidget(self.lbl_disk_free)
        s_row.addStretch()

        self.lbl_mgr_folder_path = QLabel(str(self.media_dirs["base"]))
        self.lbl_mgr_folder_path.setStyleSheet("font-family: Consolas, monospace; font-size: 11px; color: #94A3B8;")
        s_row.addWidget(self.lbl_mgr_folder_path)
        c_layout.addWidget(stats_bar)

        # Filter & Search Bar
        filter_row = QHBoxLayout()
        self.ent_recording_search = QLineEdit()
        self.ent_recording_search.setPlaceholderText("Search by name, folder or date")
        self.ent_recording_search.textChanged.connect(self._filter_and_render_recordings)
        filter_row.addWidget(self.ent_recording_search, 1)

        self.recording_filter_mode = "all"
        self.btn_filter_all = QPushButton("All")
        self.btn_filter_video = make_button("Videos", "video")
        self.btn_filter_audio = make_button("Audios", "mic")
        self.btn_filter_snapshot = make_button("Snapshots", "photo")

        for mode, b in (
            ("all", self.btn_filter_all),
            ("video", self.btn_filter_video),
            ("audio", self.btn_filter_audio),
            ("snapshot", self.btn_filter_snapshot),
        ):
            b.clicked.connect(lambda _c=False, m=mode: self._set_recording_filter(m))
            filter_row.addWidget(b)

        self.combo_recording_sort = QComboBox()
        self.combo_recording_sort.addItems(["Newest First", "Oldest First", "Name (A-Z)", "Size (Largest)", "Size (Smallest)"])
        self.combo_recording_sort.currentIndexChanged.connect(self._filter_and_render_recordings)
        filter_row.addWidget(self.combo_recording_sort)
        c_layout.addLayout(filter_row)

        # Table
        self.tree_recordings = QTableWidget(0, 5)
        self.tree_recordings.setHorizontalHeaderLabels(["File Name", "Folder", "Type", "Created", "Size"])
        header = self.tree_recordings.horizontalHeader()
        header.setSectionResizeMode(0, QHeaderView.ResizeMode.Stretch)
        header.setSectionResizeMode(1, QHeaderView.ResizeMode.ResizeToContents)
        header.setSectionResizeMode(2, QHeaderView.ResizeMode.ResizeToContents)
        header.setSectionResizeMode(3, QHeaderView.ResizeMode.ResizeToContents)
        header.setSectionResizeMode(4, QHeaderView.ResizeMode.ResizeToContents)
        self.tree_recordings.setSelectionBehavior(QAbstractItemView.SelectionBehavior.SelectRows)
        self.tree_recordings.setSelectionMode(QAbstractItemView.SelectionMode.SingleSelection)
        self.tree_recordings.setEditTriggers(QAbstractItemView.EditTrigger.NoEditTriggers)
        self.tree_recordings.verticalHeader().setVisible(False)
        self.tree_recordings.itemSelectionChanged.connect(self._on_tree_recording_selected)
        self.tree_recordings.itemDoubleClicked.connect(lambda _item: self.open_selected_recording())
        c_layout.addWidget(self.tree_recordings, 1)

        # Bottom Action Row
        act_row = QHBoxLayout()
        self.lbl_selected_recording_info = QLabel("Select an item to play, inspect in Explorer, or delete.")
        self.lbl_selected_recording_info.setStyleSheet("color: #94A3B8;")
        act_row.addWidget(self.lbl_selected_recording_info, 1)

        btn_play = make_button("Open", "play")
        btn_play.clicked.connect(self.open_selected_recording)
        act_row.addWidget(btn_play)

        btn_rev = make_button("Show in Explorer", "folder")
        btn_rev.clicked.connect(self.show_selected_in_explorer)
        act_row.addWidget(btn_rev)

        btn_cp_path = make_button("Copy path", "copy")
        btn_cp_path.clicked.connect(self.copy_selected_recording_path)
        act_row.addWidget(btn_cp_path)

        btn_del = make_button("Delete", "delete")
        btn_del.setObjectName("DangerBtn")
        btn_del.clicked.connect(self.delete_selected_recording)
        act_row.addWidget(btn_del)

        c_layout.addLayout(act_row)
        layout.addWidget(card)
        self._set_recording_filter("all")
        return page

    # ------------------------------------------------------------------
    # Page 4: Desktop Controls, PC Webcam Live Preview & Quick Apps
    # ------------------------------------------------------------------
    def _create_desktop_page(self):
        scroll = QScrollArea()
        scroll.setWidgetResizable(True)
        scroll.setHorizontalScrollBarPolicy(Qt.ScrollBarPolicy.ScrollBarAlwaysOff)
        content = QWidget()
        layout = QVBoxLayout(content)
        layout.setContentsMargins(20, 20, 20, 20)
        layout.setSpacing(16)

        # Card 1: PC Webcam Live Preview & Fullscreen + Microphone Diagnostics
        cam_card = QFrame()
        cam_card.setObjectName("Card")
        cam_l = QVBoxLayout(cam_card)
        cam_l.setContentsMargins(20, 16, 20, 16)
        cam_l.setSpacing(10)

        lbl_ct = QLabel("PC webcam")
        lbl_ct.setStyleSheet("font-size: 15px; font-weight: 600;")
        lbl_ct.setWordWrap(True)
        cam_l.addWidget(lbl_ct)

        cam_hdr = QHBoxLayout()

        self.btn_pc_cam_live = make_button("Start preview", "video")
        self.btn_pc_cam_live.clicked.connect(self.toggle_local_pc_webcam_preview)
        cam_hdr.addWidget(self.btn_pc_cam_live)

        btn_pc_snap = make_button("Snapshot", "camera")
        btn_pc_snap.clicked.connect(self.test_camera_snapshot)
        cam_hdr.addWidget(btn_pc_snap)

        btn_pc_mic = make_button("Test microphone", "mic")
        btn_pc_mic.clicked.connect(self.test_mic_record)
        cam_hdr.addWidget(btn_pc_mic)

        btn_pc_fs = make_button("Fullscreen", "fullscreen")
        btn_pc_fs.setObjectName("PrimaryBtn")
        btn_pc_fs.clicked.connect(self.open_pc_cam_fullscreen)
        cam_hdr.addWidget(btn_pc_fs)
        cam_hdr.addStretch()
        cam_l.addLayout(cam_hdr)

        self.lbl_cam_preview = AspectRatioVideoWidget(
            "PC webcam on standby. Start a preview here or a live stream from your phone.\n"
            "Double-click the video to go fullscreen.",
            self,
        )
        self.lbl_cam_preview.setMinimumHeight(240)
        self.lbl_cam_preview.double_clicked.connect(self.open_pc_cam_fullscreen)
        cam_l.addWidget(self.lbl_cam_preview)

        self.lbl_media_status = QLabel("PC Webcam & Microphone ready. Live recordings automatically combine video + audio into Videos/.")
        self.lbl_media_status.setStyleSheet("color: #38BDF8; font-size: 12px;")
        self.lbl_media_status.setWordWrap(True)
        cam_l.addWidget(self.lbl_media_status)

        layout.addWidget(cam_card)

        # Card 2: Recording Policy & Windows Sign-In Startup
        pol_card = QFrame()
        pol_card.setObjectName("Card")
        pol_l = QVBoxLayout(pol_card)
        pol_l.setContentsMargins(20, 16, 20, 16)
        pol_l.setSpacing(10)

        pol_hdr = QHBoxLayout()
        lbl_pt = QLabel("Startup & recording")
        lbl_pt.setStyleSheet("font-size: 15px; font-weight: 600;")
        pol_hdr.addWidget(lbl_pt)
        pol_hdr.addStretch()

        btn_lock = make_button("Lock PC", "lock")
        btn_lock.setObjectName("DangerBtn")
        btn_lock.clicked.connect(lambda: self.server._handle_system_power("lock") if self.server else None)
        pol_hdr.addWidget(btn_lock)
        pol_l.addLayout(pol_hdr)

        self.chk_startup = QCheckBox("Start BT Mouse Companion automatically when signing in to Windows (minimized to tray)")
        self.chk_startup.setChecked(self._startup_registry_value() is not None)
        self.chk_startup.toggled.connect(self._set_startup_enabled)
        pol_l.addWidget(self.chk_startup)

        self.chk_auto_video = QCheckBox("Record live PC webcam sessions into Videos/ (1280×720 MP4)")
        self.chk_auto_video.setChecked(self.auto_record_video)
        self.chk_auto_video.toggled.connect(self._save_recording_preferences)
        pol_l.addWidget(self.chk_auto_video)

        self.chk_auto_audio = QCheckBox("Include PC microphone audio and merge into a single combined MP4 video file")
        self.chk_auto_audio.setChecked(self.auto_record_audio)
        self.chk_auto_audio.toggled.connect(self._save_recording_preferences)
        pol_l.addWidget(self.chk_auto_audio)

        lim_grid = QGridLayout()
        lim_grid.setHorizontalSpacing(12)
        lim_grid.setVerticalSpacing(8)
        self.spin_retention = QSpinBox()
        self.spin_retention.setRange(0, 3650)
        self.spin_retention.setValue(self.recording_retention_days)
        self.spin_max_mb = QSpinBox()
        self.spin_max_mb.setRange(0, 500000)
        self.spin_max_mb.setSingleStep(512)
        self.spin_max_mb.setValue(self.recording_max_storage_mb)
        self.spin_min_free = QSpinBox()
        self.spin_min_free.setRange(0, 100000)
        self.spin_min_free.setSingleStep(256)
        self.spin_min_free.setValue(self.recording_min_free_mb)
        for col, (caption, spin) in enumerate(
            (
                ("Auto-delete after (days, 0 = keep)", self.spin_retention),
                ("Max library size (MB, 0 = unlimited)", self.spin_max_mb),
                ("Keep free disk space (MB)", self.spin_min_free),
            )
        ):
            lbl_cap = QLabel(caption)
            lbl_cap.setStyleSheet("color: #94A3B8; font-size: 12px;")
            lim_grid.addWidget(lbl_cap, 0, col)
            spin.setMinimumWidth(140)
            lim_grid.addWidget(spin, 1, col)

        btn_save_lim = QPushButton("Save Limits")
        btn_save_lim.setObjectName("PrimaryBtn")
        btn_save_lim.clicked.connect(self._save_recording_preferences)
        lim_grid.addWidget(btn_save_lim, 1, 3)
        lim_grid.setColumnStretch(4, 1)
        pol_l.addLayout(lim_grid)

        prev_row = QHBoxLayout()
        btn_sel_reg = make_button("Select player area", "crop")
        btn_sel_reg.clicked.connect(self.select_media_preview_region)
        prev_row.addWidget(btn_sel_reg)
        self.lbl_media_preview_region = QLabel(self._media_preview_region_description())
        self.lbl_media_preview_region.setStyleSheet("color: #94A3B8;")
        prev_row.addWidget(self.lbl_media_preview_region)
        prev_row.addStretch()
        pol_l.addLayout(prev_row)

        layout.addWidget(pol_card)

        # Card 3: Quick Apps Manager
        qa_card = QFrame()
        qa_card.setObjectName("Card")
        qa_l = QVBoxLayout(qa_card)
        qa_l.setContentsMargins(20, 16, 20, 16)
        qa_l.setSpacing(10)

        lbl_qat = QLabel("Quick apps")
        lbl_qat.setStyleSheet("font-size: 15px; font-weight: 600;")
        qa_l.addWidget(lbl_qat)

        add_row = QHBoxLayout()
        self.combo_available_apps = QComboBox()
        add_row.addWidget(self.combo_available_apps, 1)
        btn_add_qa = make_button("Add app", "add")
        btn_add_qa.setObjectName("PrimaryBtn")
        btn_add_qa.clicked.connect(self.add_quick_app)
        add_row.addWidget(btn_add_qa)
        qa_l.addLayout(add_row)

        self.list_quick_apps = QListWidget()
        self.list_quick_apps.setMaximumHeight(130)
        self.list_quick_apps.currentRowChanged.connect(self.on_quick_app_selected)
        qa_l.addWidget(self.list_quick_apps)

        edit_row = QHBoxLayout()
        self.ent_quick_app_name = QLineEdit()
        self.ent_quick_app_name.setPlaceholderText("Rename selected quick app...")
        edit_row.addWidget(self.ent_quick_app_name, 1)

        btn_ren = QPushButton("Rename")
        btn_ren.clicked.connect(self.rename_quick_app)
        edit_row.addWidget(btn_ren)

        btn_rem = QPushButton("Remove")
        btn_rem.setObjectName("DangerBtn")
        btn_rem.clicked.connect(self.remove_quick_app)
        edit_row.addWidget(btn_rem)

        btn_ref_qa = make_button("Refresh list", "refresh")
        btn_ref_qa.clicked.connect(self.refresh_quick_apps)
        edit_row.addWidget(btn_ref_qa)
        qa_l.addLayout(edit_row)

        self.lbl_quick_apps_status = QLabel("Quick apps are synced with your Android phone.")
        self.lbl_quick_apps_status.setStyleSheet("color: #94A3B8; font-size: 12px;")
        qa_l.addWidget(self.lbl_quick_apps_status)

        layout.addWidget(qa_card)
        layout.addStretch()
        scroll.setWidget(content)
        return scroll

    # ------------------------------------------------------------------
    # Page 5: Bluetooth Assistant
    # ------------------------------------------------------------------
    def _create_bt_page(self):
        page = QWidget()
        layout = QVBoxLayout(page)
        layout.setContentsMargins(20, 20, 20, 20)

        card = QFrame()
        card.setObjectName("Card")
        c_l = QVBoxLayout(card)
        c_l.setContentsMargins(24, 24, 24, 24)
        c_l.setSpacing(14)

        lbl_t = QLabel("Bluetooth")
        lbl_t.setStyleSheet("font-size: 17px; font-weight: 600;")
        c_l.addWidget(lbl_t)

        lbl_d = QLabel(
            "Your phone connects over Bluetooth in two complementary ways:\n"
            "• Bluetooth RFCOMM (Companion Link): Carries media controls, system stats, live audio, and camera frames.\n"
            "• Bluetooth HID (Direct Hardware Profile): Emulates a physical Bluetooth mouse & keyboard at the OS level."
        )
        lbl_d.setWordWrap(True)
        lbl_d.setStyleSheet("color: #CBD5E1; line-height: 1.4;")
        c_l.addWidget(lbl_d)

        btn_bt = make_button("Open Bluetooth settings", "settings")
        btn_bt.setObjectName("PrimaryBtn")
        btn_bt.setFixedWidth(280)
        btn_bt.clicked.connect(self.open_windows_bt_settings)
        c_l.addWidget(btn_bt)

        steps_box = QFrame()
        steps_box.setObjectName("SubCard")
        s_l = QVBoxLayout(steps_box)
        lbl_sh = QLabel("PAIRING & FAILOVER CHECKLIST")
        lbl_sh.setStyleSheet("color: #38BDF8; font-weight: 700; font-size: 12px;")
        s_l.addWidget(lbl_sh)
        lbl_st = QLabel(
            "1. Pair your Android phone and PC in Windows Bluetooth Settings.\n"
            "2. In the BT Mouse app, open Connect  ›  Bluetooth and tap Connect next to your PC.\n"
            "3. Enable 'Automatic Measured Failover' in Settings to seamlessly switch between Wi-Fi and Bluetooth without dropping video or audio streams."
        )
        lbl_st.setWordWrap(True)
        s_l.addWidget(lbl_st)
        c_l.addWidget(steps_box)
        c_l.addStretch()

        layout.addWidget(card)
        return page

    # ------------------------------------------------------------------
    # Page 6: Remote WAN
    # ------------------------------------------------------------------
    def _create_wan_page(self):
        page = QWidget()
        layout = QHBoxLayout(page)
        layout.setContentsMargins(20, 20, 20, 20)
        layout.setSpacing(16)

        qr_card = QFrame()
        qr_card.setObjectName("Card")
        qr_l = QVBoxLayout(qr_card)
        qr_l.setContentsMargins(24, 24, 24, 24)
        qr_l.setAlignment(Qt.AlignmentFlag.AlignCenter)

        lbl_t = QLabel("Remote pairing")
        lbl_t.setStyleSheet("font-size: 17px; font-weight: 600;")
        lbl_t.setAlignment(Qt.AlignmentFlag.AlignCenter)
        qr_l.addWidget(lbl_t)

        lbl_d = QLabel("Scan from the Remote WAN tab on your phone to auto-fill the WebSocket URL and 6-digit PIN.")
        lbl_d.setWordWrap(True)
        lbl_d.setAlignment(Qt.AlignmentFlag.AlignCenter)
        lbl_d.setStyleSheet("color: #94A3B8; font-size: 12px;")
        qr_l.addWidget(lbl_d)
        qr_l.addSpacing(12)

        self.wan_qr_label = QLabel()
        self.wan_qr_label.setFixedSize(220, 220)
        self.wan_qr_label.setAlignment(Qt.AlignmentFlag.AlignCenter)
        self.wan_qr_label.setStyleSheet("background-color: #FFFFFF; border-radius: 12px; padding: 10px;")
        qr_l.addWidget(self.wan_qr_label, 0, Qt.AlignmentFlag.AlignCenter)
        layout.addWidget(qr_card, 1)

        info_card = QFrame()
        info_card.setObjectName("Card")
        in_l = QVBoxLayout(info_card)
        in_l.setContentsMargins(24, 24, 24, 24)
        in_l.setSpacing(12)

        lbl_it = QLabel("WebSocket & PIN")
        lbl_it.setStyleSheet("font-size: 17px; font-weight: 600;")
        in_l.addWidget(lbl_it)

        url_box = QFrame()
        url_box.setObjectName("SubCard")
        u_l = QVBoxLayout(url_box)
        lbl_uh = QLabel("LOCAL WEBSOCKET ENDPOINT")
        lbl_uh.setStyleSheet("color: #94A3B8; font-size: 10px; font-weight: 700;")
        u_l.addWidget(lbl_uh)
        self.lbl_wan_url = QLabel(f"ws://{self.current_ip}:{self.ws_port}")
        self.lbl_wan_url.setStyleSheet("font-family: Consolas, monospace; font-size: 15px; font-weight: 600; color: #10B981;")
        u_l.addWidget(self.lbl_wan_url)
        in_l.addWidget(url_box)

        in_l.addWidget(QLabel("Update 6-Digit Security PIN:"))
        pin_row = QHBoxLayout()
        self.ent_token = QLineEdit(self.auth_token)
        self.ent_token.setMaxLength(6)
        pin_row.addWidget(self.ent_token, 1)
        btn_upd = QPushButton("Save PIN")
        btn_upd.setObjectName("PrimaryBtn")
        btn_upd.clicked.connect(self._update_auth_token)
        pin_row.addWidget(btn_upd)
        in_l.addLayout(pin_row)

        in_l.addStretch()

        btn_cp_url = make_button("Copy WebSocket URL", "copy")
        btn_cp_url.clicked.connect(self.copy_wan_url)
        in_l.addWidget(btn_cp_url)

        btn_ref_wan = make_button("Refresh QR code", "refresh")
        btn_ref_wan.clicked.connect(self.generate_wan_qr)
        in_l.addWidget(btn_ref_wan)

        layout.addWidget(info_card, 1)
        self.generate_wan_qr()
        return page

    # ------------------------------------------------------------------
    # Page 7: Tablet Writing Pad
    # ------------------------------------------------------------------
    def _create_tablet_page(self):
        page = QWidget()
        layout = QVBoxLayout(page)
        layout.setContentsMargins(20, 20, 20, 20)

        card = QFrame()
        card.setObjectName("Card")
        c_l = QVBoxLayout(card)
        c_l.setContentsMargins(24, 24, 24, 24)
        c_l.setSpacing(14)

        lbl_t = QLabel("Tablet drawing slate")
        lbl_t.setStyleSheet("font-size: 17px; font-weight: 600;")
        c_l.addWidget(lbl_t)

        lbl_d = QLabel(
            "Use the 'Drawing Pen' mode right inside the main Control Pad tab on your Android phone or tablet:\n"
            "• 1:1 Absolute Screen Mapping: Maps your mobile screen directly onto your primary monitor.\n"
            "• Stylus & Palm Rejection: Supports S-Pen / active styluses with optional finger touch rejection."
        )
        lbl_d.setWordWrap(True)
        c_l.addWidget(lbl_d)

        btn_paint = make_button("Open Paint to test", "paint")
        btn_paint.setObjectName("PrimaryBtn")
        btn_paint.setFixedWidth(260)
        btn_paint.clicked.connect(self.open_paint)
        c_l.addWidget(btn_paint)
        c_l.addStretch()

        layout.addWidget(card)
        return page

    # ------------------------------------------------------------------
    # Page 8: Live Monitor
    # ------------------------------------------------------------------
    def _create_logs_page(self):
        page = QWidget()
        layout = QVBoxLayout(page)
        layout.setContentsMargins(20, 20, 20, 20)
        layout.setSpacing(12)

        top_card = QFrame()
        top_card.setObjectName("Card")
        t_row = QHBoxLayout(top_card)
        t_row.setContentsMargins(18, 12, 18, 12)

        self.lbl_client = QLabel("Client: Disconnected")
        self.lbl_client.setStyleSheet("font-size: 14px; font-weight: 700; color: #94A3B8;")
        t_row.addWidget(self.lbl_client)
        t_row.addStretch()

        self.lbl_packets = QLabel("Packets Received: 0")
        self.lbl_packets.setStyleSheet("font-family: Consolas, monospace; font-size: 14px; font-weight: 700; color: #38BDF8;")
        t_row.addWidget(self.lbl_packets)

        btn_clr = make_button("Clear log", "clear")
        btn_clr.clicked.connect(lambda: self.txt_log.clear())
        t_row.addWidget(btn_clr)

        layout.addWidget(top_card)

        self.txt_log = QPlainTextEdit()
        self.txt_log.setReadOnly(True)
        self.txt_log.setStyleSheet("font-family: Consolas, monospace; font-size: 12px;")
        layout.addWidget(self.txt_log, 1)

        self.log("Companion initialized. Ready for connections.")
        return page

    # ------------------------------------------------------------------
    # System Tray & Window Lifecycle
    # ------------------------------------------------------------------
    def _setup_system_tray(self):
        if not QSystemTrayIcon.isSystemTrayAvailable():
            return
        self.tray_icon = QSystemTrayIcon(self)
        if os.path.exists(self.icon_png_path):
            self.tray_icon.setIcon(QIcon(self.icon_png_path))
        elif os.path.exists(self.icon_path):
            self.tray_icon.setIcon(QIcon(self.icon_path))

        menu = QMenu()
        menu.setStyleSheet(
            "QMenu { background-color: #131B29; color: #F8FAFC; border: 1px solid #283750; padding: 6px; } "
            "QMenu::item { padding: 6px 18px; border-radius: 4px; } "
            "QMenu::item:selected { background-color: #6366F1; }"
        )
        act_show = QAction("Show Companion Window", self)
        act_show.triggered.connect(self.show_from_tray)
        menu.addAction(act_show)

        act_vids = QAction("Open Videos Folder", self)
        act_vids.triggered.connect(self.open_videos_folder)
        menu.addAction(act_vids)

        act_auds = QAction("Open Audios Folder", self)
        act_auds.triggered.connect(self.open_audios_folder)
        menu.addAction(act_auds)

        act_snaps = QAction("Open Snapshots Folder", self)
        act_snaps.triggered.connect(self.open_snapshots_folder)
        menu.addAction(act_snaps)

        menu.addSeparator()
        act_restart = QAction("Restart Server", self)
        act_restart.triggered.connect(self.restart_server)
        menu.addAction(act_restart)

        act_quit = QAction("Exit Application", self)
        act_quit.triggered.connect(self.quit_application)
        menu.addAction(act_quit)

        self.tray_icon.setContextMenu(menu)
        self.tray_icon.setToolTip(f"BT Mouse Companion v{APP_VERSION}")
        self.tray_icon.activated.connect(
            lambda reason: self.show_from_tray() if reason == QSystemTrayIcon.ActivationReason.Trigger else None
        )
        self.tray_icon.show()

    def closeEvent(self, event):
        event.ignore()
        self.hide_to_tray()

    def hide_to_tray(self):
        self.hide()
        if self.tray_icon:
            self.tray_icon.showMessage(
                "BT Mouse Companion",
                "Still running in your system tray.",
                QSystemTrayIcon.MessageIcon.Information,
                2000,
            )

    def show_from_tray(self):
        self.showNormal()
        self.activateWindow()
        self.raise_()

    # ------------------------------------------------------------------
    # Camera Fullscreen, Rotation & Recording Actions
    # ------------------------------------------------------------------
    def open_phone_cam_fullscreen(self):
        if self.phone_fullscreen_win is None:
            self.phone_fullscreen_win = FullscreenVideoWindow(
                "Phone Camera Live Stream",
                on_rotate=self.rotate_phone_cam_90,
                on_snapshot=self._save_phone_cam_snapshot,
                on_toggle_record=self.toggle_phone_cam_recording,
                parent=self,
            )
        self.phone_fullscreen_win.showMaximized()
        self.phone_fullscreen_win.activateWindow()
        self.phone_fullscreen_win.raise_()

    def open_pc_cam_fullscreen(self):
        if self.pc_fullscreen_win is None:
            self.pc_fullscreen_win = FullscreenVideoWindow(
                "PC Webcam Live Monitor",
                on_snapshot=self.test_camera_snapshot,
                parent=self,
            )
        if not self.server.camera_manager.streaming:
            self.toggle_local_pc_webcam_preview()
        self.pc_fullscreen_win.showMaximized()
        self.pc_fullscreen_win.activateWindow()
        self.pc_fullscreen_win.raise_()

    def rotate_phone_cam_90(self):
        receiver = getattr(self.server, "phone_camera_receiver", None)
        if receiver:
            deg = receiver.rotate_90()
            self.lbl_phone_cam_telemetry.setText(f"Rotated phone camera view to {deg}°")

    def toggle_phone_cam_recording(self):
        receiver = getattr(self.server, "phone_camera_receiver", None)
        if not receiver:
            return
        if receiver.recording:
            saved = receiver.stop_recording()
            self.btn_phone_cam_rec.setText("Record")
            self.btn_phone_cam_rec.setIcon(fluent_icon("record", "#E2E8F0"))
            self.btn_phone_cam_rec.setObjectName("")
            self.btn_phone_cam_rec.style().unpolish(self.btn_phone_cam_rec)
            self.btn_phone_cam_rec.style().polish(self.btn_phone_cam_rec)
            if saved:
                self.lbl_phone_cam_telemetry.setText(f"Saved video to Videos/{os.path.basename(saved)}")
                self.refresh_recordings()
        else:
            path = receiver.start_recording()
            self.btn_phone_cam_rec.setText("Stop recording")
            self.btn_phone_cam_rec.setIcon(fluent_icon("stop", "#E2E8F0"))
            self.btn_phone_cam_rec.setObjectName("DangerBtn")
            self.btn_phone_cam_rec.style().unpolish(self.btn_phone_cam_rec)
            self.btn_phone_cam_rec.style().polish(self.btn_phone_cam_rec)
            self.lbl_phone_cam_telemetry.setText(f"Recording to Videos/{os.path.basename(path)}...")

    def toggle_local_pc_webcam_preview(self):
        if not self.server:
            return
        if self.server.camera_manager.streaming:
            self.server._stop_camera_stream_for_client()
            self.pc_webcam_preview_active = False
            self.btn_pc_cam_live.setText("Start preview")
            self.btn_pc_cam_live.setIcon(fluent_icon("video", "#E2E8F0"))
            self.lbl_cam_preview.clear_frame()
            self.lbl_media_status.setText("PC Webcam preview stopped.")
            self.refresh_recordings()
        else:
            self.pc_webcam_preview_active = True
            self.btn_pc_cam_live.setText("Stop preview")
            self.btn_pc_cam_live.setIcon(fluent_icon("stop", "#E2E8F0"))
            self.lbl_media_status.setText("Starting PC Webcam live preview...")
            threading.Thread(
                target=self.server._start_camera_stream_for_client,
                args=(("local_gui", 0), lambda _msg: None, True, "local_gui"),
                daemon=True,
            ).start()

    def _on_pc_cam_frame_received(self, b64_frame):
        try:
            raw = base64.b64decode(b64_frame)
            self.bridge.pc_cam_signal.emit(raw)
        except Exception:
            pass

    def _update_pc_cam_ui(self, raw_jpeg):
        self.lbl_cam_preview.set_frame_bytes(raw_jpeg)
        if self.pc_fullscreen_win and self.pc_fullscreen_win.isVisible():
            self.pc_fullscreen_win.video_surface.set_frame_bytes(raw_jpeg)

    def _on_phone_cam_frame_received(self, raw_jpeg, w, h, lens, fps, bitrate_kbps):
        self.bridge.phone_cam_signal.emit(raw_jpeg, int(w), int(h), str(lens), float(fps), float(bitrate_kbps))

    def _update_phone_cam_ui(self, raw_jpeg, w, h, lens, fps, bitrate_kbps):
        self.lbl_phone_cam_preview.set_frame_bytes(raw_jpeg)
        orient = "PORTRAIT 9:16 (Vertical)" if h > w else "LANDSCAPE 16:9 (Horizontal)"
        rot = getattr(getattr(self.server, "phone_camera_receiver", None), "rotation_degrees", 0)
        rot_suffix = f" · Rot {rot}°" if rot else ""
        self.lbl_phone_cam_orientation.setText(f"{orient}{rot_suffix}")
        self.lbl_phone_cam_status.setText(f"● LIVE · {lens.upper()} LENS")
        self.lbl_phone_cam_status.setStyleSheet("color: #10B981; font-weight: 700;")
        count = getattr(getattr(self.server, "phone_camera_receiver", None), "frame_count", 0)
        meta_text = f"{w}×{h}  ·  {fps:.1f} FPS  ·  {bitrate_kbps:.0f} kbps  ·  {count:,} frames"
        self.lbl_phone_cam_telemetry.setText(meta_text)

        if self.phone_fullscreen_win and self.phone_fullscreen_win.isVisible():
            self.phone_fullscreen_win.video_surface.set_frame_bytes(raw_jpeg)
            self.phone_fullscreen_win.lbl_meta.setText(f"● LIVE ({lens.upper()})  ·  {orient}  ·  {meta_text}")

    def _check_phone_cam_active(self):
        receiver = getattr(self.server, "phone_camera_receiver", None)
        if receiver and receiver.latest_timestamp and (time.monotonic() - receiver.latest_timestamp > 4.5):
            if receiver.is_active:
                receiver.is_active = False
                self.lbl_phone_cam_status.setText("○ STANDBY")
                self.lbl_phone_cam_status.setStyleSheet("color: #94A3B8; font-weight: 700;")
                self.lbl_phone_cam_telemetry.setText("Stream paused / standby")

    def _copy_phone_mjpeg_url(self):
        QApplication.clipboard().setText(self.lbl_phone_mjpeg_url.text())
        self.lbl_phone_cam_telemetry.setText("Copied the MJPEG stream URL.")

    def _save_phone_cam_snapshot(self):
        receiver = getattr(self.server, "phone_camera_receiver", None)
        frame = receiver.latest_jpeg if receiver else None
        if not frame:
            self.lbl_phone_cam_telemetry.setText("No live phone camera frame available to snapshot.")
            return
        try:
            snap_dir = Path(self.media_dirs["snapshots"])
            snap_dir.mkdir(parents=True, exist_ok=True)
            timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
            filename = f"phone_snapshot_{timestamp}.jpg"
            target = snap_dir / filename
            target.write_bytes(frame)
            self.lbl_phone_cam_telemetry.setText(f"Saved snapshot to Snapshots/{filename}")
            self.refresh_recordings()
        except Exception as e:
            self.lbl_phone_cam_telemetry.setText(f"Could not save snapshot: {e}")

    # ------------------------------------------------------------------
    # Media Folders & Library Management
    # ------------------------------------------------------------------
    def _open_folder_path(self, folder_path):
        folder = Path(folder_path)
        folder.mkdir(parents=True, exist_ok=True)
        try:
            if os.name == "nt":
                os.startfile(str(folder))
            elif sys.platform == "darwin":
                subprocess.Popen(["open", str(folder)])
            else:
                subprocess.Popen(["xdg-open", str(folder)])
        except Exception as exc:
            QMessageBox.critical(self, "Folder Error", f"Could not open folder:\n{folder}\n\n{exc}")

    def open_videos_folder(self):
        self._open_folder_path(self.media_dirs["videos"])

    def open_audios_folder(self):
        self._open_folder_path(self.media_dirs["audios"])

    def open_snapshots_folder(self):
        self._open_folder_path(self.media_dirs["snapshots"])

    def open_recordings_folder(self):
        self._open_folder_path(self.media_dirs["base"])

    def _set_recording_filter(self, mode):
        self.recording_filter_mode = mode
        for m, btn in (
            ("all", self.btn_filter_all),
            ("video", self.btn_filter_video),
            ("audio", self.btn_filter_audio),
            ("snapshot", self.btn_filter_snapshot),
        ):
            btn.setObjectName("PrimaryBtn" if m == mode else "")
            btn.style().unpolish(btn)
            btn.style().polish(btn)
        self._filter_and_render_recordings()

    def refresh_recordings(self):
        self.media_dirs = get_btmouse_media_dirs()
        records = []
        total_bytes = 0

        folder_specs = [
            (Path(self.media_dirs["videos"]), "Videos", "Video", "video", {".mp4", ".avi", ".mkv", ".webm"}),
            (Path(self.media_dirs["audios"]), "Audios", "Audio", "mic", {".wav", ".m4a", ".mp3"}),
            (Path(self.media_dirs["snapshots"]), "Snapshots", "Snapshot", "photo", {".jpg", ".jpeg", ".png"}),
        ]

        for folder, folder_name, type_name, icon, exts in folder_specs:
            if not folder.exists():
                continue
            for path in folder.iterdir():
                if not path.is_file() or path.suffix.lower() not in exts:
                    continue
                if path.name.startswith("_mux_audio_"):
                    continue
                if self.server and self.server._is_recording_active(path):
                    continue
                try:
                    stat = path.stat()
                    total_bytes += stat.st_size
                    dt = datetime.fromtimestamp(stat.st_mtime)
                    date_str = dt.strftime("%Y-%m-%d  %I:%M %p")
                    size_str = (
                        f"{stat.st_size / 1024:.1f} KB"
                        if stat.st_size < 1024 * 1024
                        else f"{stat.st_size / (1024 * 1024):.1f} MB"
                    )
                    records.append(
                        {
                            "path": path,
                            "name": path.name,
                            "folder": folder_name,
                            "size": stat.st_size,
                            "size_str": size_str,
                            "mtime": stat.st_mtime,
                            "date_str": date_str,
                            "is_video": folder_name == "Videos",
                            "is_audio": folder_name == "Audios",
                            "is_snapshot": folder_name == "Snapshots",
                            "type_name": type_name,
                            "type_icon": icon,
                            "ext": path.suffix.lower(),
                        }
                    )
                except OSError:
                    continue

        records.sort(key=lambda r: r["mtime"], reverse=True)
        self._all_recordings = records
        self._total_recordings_bytes = total_bytes
        self._filter_and_render_recordings()

    def _filter_and_render_recordings(self):
        if not hasattr(self, "tree_recordings"):
            return
        items = list(getattr(self, "_all_recordings", []))
        mode = getattr(self, "recording_filter_mode", "all")
        if mode == "video":
            items = [x for x in items if x["is_video"]]
        elif mode == "audio":
            items = [x for x in items if x["is_audio"]]
        elif mode == "snapshot":
            items = [x for x in items if x["is_snapshot"]]

        query = self.ent_recording_search.text().strip().lower()
        if query:
            items = [
                x
                for x in items
                if query in x["name"].lower() or query in x["folder"].lower() or query in x["date_str"].lower()
            ]

        sort_mode = self.combo_recording_sort.currentText()
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

        self._displayed_recordings = items
        self.tree_recordings.setRowCount(len(items))
        for row, rec in enumerate(items):
            self.tree_recordings.setItem(row, 0, QTableWidgetItem(fluent_icon(rec["type_icon"], ICON_COLOR_ACCENT), rec["name"]))
            self.tree_recordings.setItem(row, 1, QTableWidgetItem(rec["folder"]))
            self.tree_recordings.setItem(row, 2, QTableWidgetItem(f"{rec['type_name']} ({rec['ext'][1:].upper()})"))
            self.tree_recordings.setItem(row, 3, QTableWidgetItem(rec["date_str"]))
            self.tree_recordings.setItem(row, 4, QTableWidgetItem(rec["size_str"]))

        all_recs = getattr(self, "_all_recordings", [])
        c_all = len(all_recs)
        c_vid = sum(1 for x in all_recs if x["is_video"])
        c_aud = sum(1 for x in all_recs if x["is_audio"])
        c_snp = sum(1 for x in all_recs if x["is_snapshot"])
        self.btn_filter_all.setText(f"All ({c_all})")
        self.btn_filter_video.setText(f"Videos ({c_vid})")
        self.btn_filter_audio.setText(f"Audios ({c_aud})")
        self.btn_filter_snapshot.setText(f"Snapshots ({c_snp})")

        total_mb = getattr(self, "_total_recordings_bytes", 0) / (1024 * 1024)
        self.lbl_recordings_stats.setText(f"Showing {len(items)} of {c_all} items  ·  {total_mb:.1f} MB")
        try:
            free_gb = shutil.disk_usage(self.media_dirs["base"]).free / (1024 * 1024 * 1024)
            self.lbl_disk_free.setText(f"{free_gb:.1f} GB free")
        except Exception:
            pass

    def _get_selected_recording_path(self):
        row = self.tree_recordings.currentRow()
        items = getattr(self, "_displayed_recordings", [])
        if 0 <= row < len(items):
            return items[row]["path"]
        return None

    def _on_tree_recording_selected(self):
        path = self._get_selected_recording_path()
        if path and Path(path).is_file():
            self.lbl_selected_recording_info.setText(f"Selected: {path}")
        else:
            self.lbl_selected_recording_info.setText("Select an item to play, inspect in Explorer, or delete.")

    def open_selected_recording(self):
        path = self._get_selected_recording_path()
        if not path or not Path(path).is_file():
            QMessageBox.information(self, "Media Library", "Select a recording or snapshot first.")
            return
        self._open_folder_path(path)

    def show_selected_in_explorer(self):
        path = self._get_selected_recording_path()
        if not path or not Path(path).is_file():
            self.open_recordings_folder()
            return
        try:
            if os.name == "nt":
                subprocess.Popen(["explorer.exe", f"/select,{str(Path(path).resolve())}"])
            else:
                self._open_folder_path(Path(path).parent)
        except Exception:
            self.open_recordings_folder()

    def copy_selected_recording_path(self):
        path = self._get_selected_recording_path()
        if path:
            QApplication.clipboard().setText(str(Path(path).resolve()))

    def delete_selected_recording(self):
        path = self._get_selected_recording_path()
        if not path or not Path(path).is_file():
            QMessageBox.information(self, "Delete Item", "Select an item to delete first.")
            return
        p = Path(path)
        ans = QMessageBox.question(
            self,
            "Delete Permanently",
            f"Permanently delete {p.name}?",
            QMessageBox.StandardButton.Yes | QMessageBox.StandardButton.No,
        )
        if ans == QMessageBox.StandardButton.Yes:
            try:
                p.unlink()
                self.refresh_recordings()
            except OSError as exc:
                QMessageBox.critical(self, "Delete Error", f"Could not delete file:\n{exc}")

    # ------------------------------------------------------------------
    # QR Generation, Settings & Quick Apps
    # ------------------------------------------------------------------
    def _set_qr_pixmap(self, label, payload_dict, size_px=210):
        if not HAS_QR:
            label.setText("qrcode / pillow not installed")
            return
        try:
            import io
            qr = qrcode.QRCode(version=1, error_correction=qrcode.constants.ERROR_CORRECT_M, box_size=6, border=2)
            qr.add_data(json.dumps(payload_dict))
            qr.make(fit=True)
            img = qr.make_image(fill_color="black", back_color="white")
            buf = io.BytesIO()
            img.save(buf, format="PNG")
            qimg = QImage.fromData(buf.getvalue())
            pix = QPixmap.fromImage(qimg).scaled(
                size_px, size_px, Qt.AspectRatioMode.KeepAspectRatio, Qt.TransformationMode.FastTransformation
            )
            label.setPixmap(pix)
        except Exception as exc:
            label.setText(f"QR Error: {exc}")

    def generate_qr(self):
        hostname = socket.gethostname()
        payload = {
            "type": "btmouse",
            "ip": self.current_ip,
            "port": self.current_port,
            "name": hostname,
            "pin": self.auth_token,
            "token": self.auth_token,
        }
        if hasattr(self, "qr_label"):
            self._set_qr_pixmap(self.qr_label, payload, 220)

    def generate_wan_qr(self):
        hostname = socket.gethostname()
        payload = {
            "type": "btmouse_wan",
            "url": f"ws://{self.current_ip}:{self.ws_port}",
            "ip": self.current_ip,
            "port": self.ws_port,
            "name": hostname,
            "pin": self.auth_token,
            "token": self.auth_token,
        }
        if hasattr(self, "wan_qr_label"):
            self._set_qr_pixmap(self.wan_qr_label, payload, 200)
        if hasattr(self, "lbl_wan_url"):
            self.lbl_wan_url.setText(f"ws://{self.current_ip}:{self.ws_port}")

    def _on_adapter_selected(self, idx):
        if 0 <= idx < len(self.adapters):
            _name, ip = self.adapters[idx]
            self.current_ip = ip
            self.lbl_ip_val.setText(ip)
            self.lbl_sidebar_ip.setText(f"IP: {ip}:{self.current_port}")
            self.generate_qr()
            self.generate_wan_qr()

    def copy_ip(self):
        QApplication.clipboard().setText(self.current_ip)
        QMessageBox.information(self, "Copied", f"IP address '{self.current_ip}' copied to clipboard.")

    def copy_pairing_pin(self):
        QApplication.clipboard().setText(self.auth_token)
        QMessageBox.information(self, "Copied", "The 6-digit pairing PIN was copied to the clipboard.")

    def copy_wan_url(self):
        url = f"ws://{self.current_ip}:{self.ws_port}"
        QApplication.clipboard().setText(url)
        QMessageBox.information(self, "Copied", f"WebSocket URL '{url}' copied to clipboard.")

    def _update_auth_token(self):
        new_token = self.ent_token.text().strip()
        if new_token.isdigit() and len(new_token) == 6:
            save_pairing_pin(new_token)
            self.auth_token = new_token
            self.lbl_wifi_pin.setText(new_token)
            self.lbl_sidebar_pin.setText(f"PIN: {new_token}")
            if self.server:
                self.server.auth_token = new_token
                if hasattr(self.server, "ws_server") and self.server.ws_server:
                    self.server.ws_server.auth_token = new_token
            self.generate_qr()
            self.generate_wan_qr()
            QMessageBox.information(self, "PIN Updated", "The new 6-digit pairing PIN is now active.")
        else:
            QMessageBox.warning(self, "Invalid PIN", "Enter exactly 6 digits for the pairing PIN.")

    def _save_recording_preferences(self):
        enabled = self.chk_auto_video.isChecked()
        record_audio = self.chk_auto_audio.isChecked()
        retention_days = self.spin_retention.value()
        max_storage_mb = self.spin_max_mb.value()
        min_free_mb = self.spin_min_free.value()

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
        except OSError:
            pass

    def _media_preview_region_description(self):
        region = load_media_preview_region()
        if not region:
            return "No player area selected"
        return f"Selected area: {region['width']} × {region['height']} px at ({region['x']}, {region['y']})"

    def select_media_preview_region(self):
        def _on_selected(region):
            save_media_preview_region(region)
            self.lbl_media_preview_region.setText(self._media_preview_region_description())

        self._region_overlay = RegionSelectorOverlay(_on_selected)
        self._region_overlay.show()
        self._region_overlay.activateWindow()
        self._region_overlay.setFocus()

    def refresh_quick_apps(self):
        if not self.server:
            return

        def _worker():
            try:
                apps = self.server._cached_query("apps", 10.0, self.server._get_installed_apps)
                quick_apps = load_quick_apps(apps)
                self.available_apps = apps
                self.quick_apps = quick_apps
                self._run_on_ui(self._render_quick_apps_ui)
            except Exception:
                pass

        threading.Thread(target=_worker, daemon=True).start()

    def _render_quick_apps_ui(self):
        quick_paths = {item["path"].lower() for item in self.quick_apps}
        self.combo_available_apps.clear()
        for item in self.available_apps:
            if item["path"].lower() not in quick_paths:
                self.combo_available_apps.addItem(item["name"], item)
        self.list_quick_apps.clear()
        for app in self.quick_apps:
            self.list_quick_apps.addItem(f"{app['name']}  —  {os.path.basename(app['path'])}")
        self.lbl_quick_apps_status.setText(f"{len(self.quick_apps)} quick app(s) configured.")

    def _publish_quick_apps_to_server(self):
        if not self.server or not self.available_apps:
            return
        quick_paths = {os.path.realpath(item["path"]).lower() for item in self.quick_apps}
        apps = [dict(item, is_quick=os.path.realpath(item["path"]).lower() in quick_paths) for item in self.available_apps]
        with self.server._query_cache_lock:
            self.server._query_cache["apps"] = (time.monotonic(), apps)

    def add_quick_app(self):
        name = self.combo_available_apps.currentText().strip()
        match = next((app for app in self.available_apps if app["name"] == name), None)
        if not match:
            return
        self.quick_apps = save_quick_apps(self.quick_apps + [{"name": match["name"], "path": match["path"]}])
        self._publish_quick_apps_to_server()
        self._render_quick_apps_ui()

    def on_quick_app_selected(self, row):
        if 0 <= row < len(self.quick_apps):
            self.ent_quick_app_name.setText(self.quick_apps[row]["name"])

    def rename_quick_app(self):
        row = self.list_quick_apps.currentRow()
        new_name = self.ent_quick_app_name.text().strip()
        if 0 <= row < len(self.quick_apps) and new_name:
            self.quick_apps[row]["name"] = new_name[:60]
            self.quick_apps = save_quick_apps(self.quick_apps)
            self._publish_quick_apps_to_server()
            self._render_quick_apps_ui()

    def remove_quick_app(self):
        row = self.list_quick_apps.currentRow()
        if 0 <= row < len(self.quick_apps):
            self.quick_apps = save_quick_apps([a for i, a in enumerate(self.quick_apps) if i != row])
            self._publish_quick_apps_to_server()
            self._render_quick_apps_ui()

    def test_camera_snapshot(self):
        if not self.server:
            return
        self.lbl_media_status.setText("Capturing webcam snapshot to Snapshots/...")

        def _snap():
            b64 = self.server.camera_manager.get_snapshot()
            if b64:
                raw = base64.b64decode(b64)
                self.bridge.pc_cam_signal.emit(raw)
                last_path = getattr(self.server.camera_manager, "last_snapshot_path", None)
                snap_name = os.path.basename(last_path) if last_path else "snapshot.jpg"
                self._run_on_ui(
                    lambda: (
                        self.lbl_media_status.setText(f"Saved webcam snapshot to Snapshots/{snap_name}"),
                        self.refresh_recordings(),
                    ),
                )
            else:
                self._run_on_ui(lambda: self.lbl_media_status.setText("Could not access the PC webcam."))

        threading.Thread(target=_snap, daemon=True).start()

    def test_mic_record(self):
        if not self.server:
            return
        self.lbl_media_status.setText("Recording microphone for 3 seconds to Audios/...")

        def _rec():
            rec_file = self.server.audio_manager.start_recording()
            if not rec_file:
                reason = self.server.audio_manager.last_error or "Microphone unavailable."
                self._run_on_ui(lambda: self.lbl_media_status.setText(f"{reason}"))
                return
            time.sleep(3.0)
            final_file = self.server.audio_manager.stop_recording()
            if final_file and os.path.exists(final_file):
                self._run_on_ui(
                    lambda: (
                        self.lbl_media_status.setText(f"Saved audio recording to Audios/{os.path.basename(final_file)}"),
                        self.refresh_recordings(),
                    ),
                )

        threading.Thread(target=_rec, daemon=True).start()

    def open_windows_bt_settings(self):
        try:
            os.system("start ms-settings:bluetooth")
        except Exception:
            pass

    def open_paint(self):
        try:
            os.system("start mspaint")
        except Exception:
            pass

    # ------------------------------------------------------------------
    # Server Callbacks & Logging
    # ------------------------------------------------------------------
    def _run_on_ui(self, fn):
        self.bridge.invoke_signal.emit(fn)

    def log(self, message):
        if hasattr(self, "bridge"):
            self.bridge.log_signal.emit(str(message))

    def _append_log(self, message):
        timestamp = time.strftime("%H:%M:%S")
        if hasattr(self, "txt_log"):
            self.txt_log.appendPlainText(f"[{timestamp}] {message}")

    def _on_packet(self, cmd_type, addr, data):
        self.packet_count += 1
        if self.packet_count % 15 == 0:
            self.bridge.packet_signal.emit(self.packet_count)
        if cmd_type in ("connect", "disconnect", "text", "pen"):
            self.log(f"{cmd_type.upper()} from {addr[0] if addr else ''}")

    def _update_packet_ui(self, count):
        if hasattr(self, "lbl_packets"):
            self.lbl_packets.setText(f"Packets Received: {count:,}")

    def _on_client(self, client_name, is_connected):
        self.bridge.client_signal.emit(str(client_name or ""), bool(is_connected))

    def _update_client_ui(self, client_name, is_connected):
        text = f"Client: {client_name}" if is_connected else "Client: Disconnected"
        color = "#10B981" if is_connected else "#94A3B8"
        if hasattr(self, "lbl_client"):
            self.lbl_client.setText(text)
            self.lbl_client.setStyleSheet(f"font-size: 14px; font-weight: 700; color: {color};")
        if hasattr(self, "lbl_footer_client"):
            self.lbl_footer_client.setText(text)
            self.lbl_footer_client.setStyleSheet(f"font-size: 11px; font-weight: 700; color: {color};")

    def _on_status(self, is_running):
        self.is_server_running = is_running
        self.bridge.status_signal.emit(bool(is_running))

    def _update_status_ui(self, is_running):
        if is_running:
            self.lbl_status_pill.setText("●  Server running")
            self.lbl_status_pill.setStyleSheet(
                "background-color: #062E22; color: #10B981; border: 1px solid #059669; border-radius: 14px; padding: 6px 14px; font-weight: 700; font-size: 12px;"
            )
            self.btn_toggle.setText("Stop server")
            self.btn_toggle.setIcon(fluent_icon("stop", "#E2E8F0"))
        else:
            self.lbl_status_pill.setText("●  Server stopped")
            self.lbl_status_pill.setStyleSheet(
                "background-color: #2A1621; color: #F43F5E; border: 1px solid #F43F5E; border-radius: 14px; padding: 6px 14px; font-weight: 700; font-size: 12px;"
            )
            self.btn_toggle.setText("Start server")
            self.btn_toggle.setIcon(fluent_icon("play", "#E2E8F0"))

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

    def restart_server(self):
        self.log("Restarting server...")

        def _do_restart():
            if self.server:
                self.server.stop()
                if self.server_thread and self.server_thread.is_alive():
                    self.server_thread.join(timeout=1.0)
            time.sleep(0.3)
            self._init_server()
            self.server_thread = threading.Thread(target=self.server.start, daemon=True)
            self.server_thread.start()
            self.log("Server successfully restarted.")

        threading.Thread(target=_do_restart, daemon=True).start()

    # ------------------------------------------------------------------
    # Self-Healing In-App Updater
    # ------------------------------------------------------------------
    @staticmethod
    def _version_tuple(version):
        match = re.fullmatch(r"v?(\d+)\.(\d+)\.(\d+)", version.strip())
        if not match:
            raise ValueError(f"Unrecognized release version: {version}")
        return tuple(int(part) for part in match.groups())

    def _show_update_recovery_notice(self):
        try:
            if not COMPANION_UPDATE_ERROR_LOG.is_file():
                return
            message = COMPANION_UPDATE_ERROR_LOG.read_text(encoding="utf-8-sig", errors="replace").strip()
            COMPANION_UPDATE_ERROR_LOG.unlink(missing_ok=True)
            if message:
                QMessageBox.warning(
                    self,
                    "Companion update rolled back",
                    f"The update could not start, so the previous version was restored.\n\n{message}",
                )
        except OSError as exc:
            self.log(f"Could not read update recovery details: {exc}")

    def check_for_companion_updates(self):
        if not getattr(sys, "frozen", False):
            QMessageBox.information(self, "Companion Updates", "In-app updates are available when running the packaged Windows EXE.")
            return
        self.btn_update.setEnabled(False)
        self.btn_update.setText("Checking…")
        threading.Thread(target=self._check_companion_updates_worker, daemon=True).start()

    def _check_companion_updates_worker(self):
        try:
            api_url = f"https://api.github.com/repos/{GITHUB_REPOSITORY}/releases/latest"
            request = urllib.request.Request(
                api_url,
                headers={"Accept": "application/vnd.github+json", "User-Agent": "BT-Mouse-Companion-Updater"},
            )
            with urllib.request.urlopen(request, timeout=20) as response:
                release = json.loads(response.read(2 * 1024 * 1024).decode("utf-8"))

            tag_name = release.get("tag_name", "")
            if release.get("draft") or release.get("prerelease"):
                raise ValueError("GitHub did not return a stable release.")
            if self._version_tuple(tag_name) <= self._version_tuple(APP_VERSION):
                self._run_on_ui(
                    lambda: (
                        QMessageBox.information(self, "Companion Updates", f"You are using the latest version (v{APP_VERSION})."),
                        self.btn_update.setEnabled(True),
                        self.btn_update.setText("Check for updates"),
                    ),
                )
                return

            asset = next((item for item in release.get("assets", []) if item.get("name") == "BT-Mouse-Companion.exe"), None)
            if not asset or not asset.get("browser_download_url"):
                raise ValueError("The latest release does not include the Windows companion executable.")

            def _ask():
                ans = QMessageBox.question(
                    self,
                    "Update Available",
                    f"Version {tag_name} is available. Download and install it now?",
                    QMessageBox.StandardButton.Yes | QMessageBox.StandardButton.No,
                )
                if ans == QMessageBox.StandardButton.Yes:
                    self.btn_update.setText("Downloading…")
                    threading.Thread(target=self._download_companion_update_worker, args=(tag_name, asset), daemon=True).start()
                else:
                    self.btn_update.setEnabled(True)
                    self.btn_update.setText("Check for updates")

            self._run_on_ui(_ask)
        except Exception as exc:
            self._run_on_ui(
                lambda error=str(exc): (
                    QMessageBox.critical(self, "Update Failed", f"Could not check for updates.\n\n{error}"),
                    self.btn_update.setEnabled(True),
                    self.btn_update.setText("Check for updates"),
                ),
            )

    def _download_companion_update_worker(self, tag_name, asset):
        staged_path = None
        try:
            download_url = asset["browser_download_url"]
            if not download_url.startswith("https://github.com/"):
                raise ValueError("The update URL is not a trusted GitHub release link.")
            request = urllib.request.Request(
                download_url,
                headers={"User-Agent": "BT-Mouse-Companion-Updater", "Accept": "application/octet-stream"},
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

            executable = Path(sys.executable).resolve()
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
                "  Copy-Item -LiteralPath $staged -Destination $replacement -Force -ErrorAction Stop\n"
                "  Move-Item -LiteralPath $target -Destination $backup -Force -ErrorAction Stop\n"
                "  Move-Item -LiteralPath $replacement -Destination $target -Force\n"
                "  $quotedMarker = '\"' + $readyMarker + '\"'\n"
                "  $started = Start-Process -FilePath $target -WorkingDirectory (Split-Path -Parent $target) -ArgumentList @('--btmouse-update-ready', $quotedMarker) -PassThru\n"
                "  $launchDeadline = (Get-Date).AddSeconds(30)\n"
                "  $ready = $false\n"
                "  while ((Get-Date) -lt $launchDeadline) {\n"
                "    if (Test-Path -LiteralPath $readyMarker) {\n"
                "      if ((Get-Content -LiteralPath $readyMarker -Raw).Trim() -eq $expectedVersion) { $ready = $true; break }\n"
                "    }\n"
                "    Start-Sleep -Seconds 1\n"
                "  }\n"
                "  if (-not $ready) { throw 'The updated companion did not acknowledge startup.' }\n"
                "  Remove-Item -LiteralPath $backup -Force -ErrorAction SilentlyContinue\n"
                "} catch {\n"
                "  $failure = $_.Exception.Message\n"
                "  if (Test-Path -LiteralPath $backup) {\n"
                "    Remove-Item -LiteralPath $target -Force -ErrorAction SilentlyContinue\n"
                "    Move-Item -LiteralPath $backup -Destination $target -Force -ErrorAction SilentlyContinue\n"
                "    Start-Process -FilePath $target -WorkingDirectory (Split-Path -Parent $target)\n"
                "  }\n"
                "  $failure | Out-File -LiteralPath $log -Encoding utf8\n"
                "}\n"
            )
            script_path.write_text(script, encoding="utf-8")
            flags = getattr(subprocess, "CREATE_NO_WINDOW", 0) | getattr(subprocess, "DETACHED_PROCESS", 0)
            subprocess.Popen(
                ["powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", str(script_path)],
                creationflags=flags,
            )
            self._run_on_ui(lambda: QTimer.singleShot(200, self.quit_application))
        except Exception as exc:
            self._run_on_ui(
                lambda error=str(exc): (
                    QMessageBox.critical(self, "Update Failed", f"Could not install update.\n\n{error}"),
                    self.btn_update.setEnabled(True),
                    self.btn_update.setText("Check for updates"),
                ),
            )

    def quit_application(self):
        if self.tray_icon:
            try:
                self.tray_icon.hide()
            except Exception:
                pass
        if self.server:
            self.server.stop()
        QApplication.quit()
        sys.exit(0)


if __name__ == "__main__":
    set_windows_dpi_awareness()
    if HAS_QT:
        qt_app = QApplication(sys.argv)
        qt_app.setQuitOnLastWindowClosed(False)
        win = CompanionApp()
        if "--minimized" not in sys.argv:
            win.show()
        sys.exit(qt_app.exec())
    else:
        print("[!] PySide6 is required to launch the desktop GUI. Run: pip install -r requirements.txt")
