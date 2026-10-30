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
    from PySide6.QtCore import Qt, QTimer, Signal, Slot, QObject, QSize, QRect, QPoint, QPointF
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
        QProgressDialog,
    )
    HAS_QT = True
except ImportError:
    HAS_QT = False
    QMainWindow = object
    QWidget = object
    QDialog = object
    QLabel = object

from remote_tunnel import RemoteIdentity, TunnelPublisher
from update_script import build_update_script
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

APP_VERSION = "1.5.0"
GITHUB_REPOSITORY = "yaminichandrakj/bt-mouse"
MAX_COMPANION_UPDATE_BYTES = 180 * 1024 * 1024
COMPANION_UPDATE_ERROR_LOG = Path(tempfile.gettempdir()) / "btmouse-update-error.log"
COMPANION_UPDATE_STEP_LOG = Path(tempfile.gettempdir()) / "btmouse-update.log"


class _UpdateCancelled(Exception):
    pass


def companion_update_wait_pids():
    """PIDs the swap must outlive: this process, plus the one-file launcher that holds the EXE open."""
    pids = [os.getpid()]
    if getattr(sys, "frozen", False):
        try:
            import psutil
            parent = psutil.Process().parent()
            if parent and Path(parent.exe()).resolve() == Path(sys.executable).resolve():
                pids.append(parent.pid)
        except Exception:
            pass
    return pids


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
        caption_col = ctypes.c_int(0x000F0E0E)  # #0E0E0F
        dwmapi.DwmSetWindowAttribute(int(hwnd), 35, ctypes.byref(caption_col), ctypes.sizeof(caption_col))
        text_col = ctypes.c_int(0x00E8E8E8)
        dwmapi.DwmSetWindowAttribute(int(hwnd), 36, ctypes.byref(text_col), ctypes.sizeof(text_col))
    except Exception:
        pass


FLUENT_QSS = """
QMainWindow, QDialog {
    background-color: #000000;
    color: #E8E8E8;
}
QWidget {
    color: #E8E8E8;
    font-family: "Inter", "Segoe UI Variable Text", "Segoe UI", sans-serif;
    font-size: 14px;
}
QToolTip {
    background-color: #1B1B1C;
    color: #E8E8E8;
    border: none;
    padding: 6px 10px;
}
QScrollArea, QStackedWidget {
    background-color: transparent;
    border: none;
}
QWidget#qt_scrollarea_viewport, QWidget#PageRoot {
    background-color: #000000;
}
QFrame#Sidebar {
    background-color: #0E0E0F;
    border: none;
}
QFrame#SidebarFooter {
    background-color: #141415;
    border: none;
    border-radius: 14px;
}
QFrame#Card {
    background-color: #141415;
    border: none;
    border-radius: 18px;
}
QFrame#SubCard {
    background-color: #1B1B1C;
    border: none;
    border-radius: 14px;
}
QFrame#Banner {
    background-color: #141415;
    border: none;
    border-radius: 14px;
}
QLabel#PageTitle {
    font-size: 28px;
    font-weight: 600;
    color: #E8E8E8;
}
QLabel#PageSubtitle {
    font-size: 15px;
    color: #ABABAB;
}
QLabel#SectionLabel {
    font-size: 13px;
    color: #ABABAB;
}
QPushButton {
    background-color: #2E2E30;
    color: #E8E8E8;
    border: 1px solid transparent;
    border-radius: 17px;
    padding: 9px 18px;
    font-weight: 500;
}
QPushButton:hover {
    background-color: #38383B;
}
QPushButton:pressed {
    background-color: #434346;
}
QPushButton:disabled {
    background-color: #1B1B1C;
    color: #6B6B6B;
}
QPushButton#PrimaryBtn {
    background-color: #E8E8E8;
    color: #000000;
}
QPushButton#PrimaryBtn:hover {
    background-color: #FFFFFF;
}
QPushButton#PrimaryBtn:pressed {
    background-color: #CFCFCF;
}
QPushButton#DangerBtn {
    background-color: #8C1D18;
    color: #F9DEDC;
}
QPushButton#DangerBtn:hover {
    background-color: #A12620;
}
QPushButton#QuietBtn {
    background-color: transparent;
    color: #E8E8E8;
    padding: 6px 10px;
}
QPushButton#QuietBtn:hover {
    background-color: #1B1B1C;
}
QPushButton#SegOn {
    background-color: #333436;
    color: #FFFFFF;
}
QPushButton#ZoomBtn {
    background-color: transparent;
    color: #E8E8E8;
    border-radius: 15px;
    padding: 0px;
    font-size: 16px;
}
QPushButton#ZoomBtn:hover {
    background-color: #2E2E30;
}
QPushButton#NavBtn {
    background-color: transparent;
    border: none;
    border-radius: 10px;
    padding: 9px 12px;
    text-align: left;
    color: #ABABAB;
    font-size: 14px;
    font-weight: 400;
}
QPushButton#NavBtn:hover {
    background-color: #161617;
    color: #E8E8E8;
}
QPushButton#NavBtn[active="true"] {
    background-color: #222224;
    color: #E8E8E8;
    font-weight: 500;
}
QLineEdit, QComboBox, QSpinBox {
    background-color: #262628;
    color: #E8E8E8;
    border: 1px solid transparent;
    border-radius: 12px;
    padding: 8px 12px;
    selection-background-color: #434346;
}
QLineEdit:focus, QComboBox:focus, QSpinBox:focus {
    border-color: #5A5A5D;
}
QComboBox::drop-down {
    border: none;
    width: 24px;
}
QComboBox QAbstractItemView {
    background-color: #262628;
    color: #E8E8E8;
    border: none;
    selection-background-color: #2E2E30;
    outline: none;
}
QListWidget, QPlainTextEdit, QTableWidget {
    background-color: #0E0E0F;
    color: #E8E8E8;
    border: none;
    border-radius: 14px;
    padding: 6px;
    gridline-color: #1B1B1C;
    selection-background-color: #2E2E30;
    selection-color: #FFFFFF;
}
QHeaderView::section {
    background-color: #0E0E0F;
    color: #ABABAB;
    font-weight: 400;
    border: none;
    border-bottom: 1px solid #1F1F21;
    padding: 8px 10px;
}
QCheckBox {
    spacing: 10px;
    color: #E8E8E8;
}
QCheckBox::indicator {
    width: 20px;
    height: 20px;
    border-radius: 6px;
    border: 1px solid #4A4B4D;
    background-color: #1B1B1C;
}
QCheckBox::indicator:checked {
    background-color: #E8E8E8;
    border-color: #E8E8E8;
}
QScrollBar:vertical {
    background: transparent;
    width: 10px;
    margin: 2px;
}
QScrollBar::handle:vertical {
    background: #2E2E30;
    min-height: 24px;
    border-radius: 4px;
}
QScrollBar::handle:vertical:hover {
    background: #4A4B4D;
}
QScrollBar::add-line:vertical, QScrollBar::sub-line:vertical, QScrollBar::add-page:vertical, QScrollBar::sub-page:vertical {
    height: 0px;
    background: transparent;
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
    "link": 0xE71B,
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
ICON_COLOR = "#C8C8C8"
ICON_COLOR_MUTED = "#ABABAB"
ICON_COLOR_ACCENT = "#FFFFFF"
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


def make_button(text, icon=None, color="#E8E8E8"):
    btn = QPushButton(text)
    if icon:
        btn.setIcon(fluent_icon(icon, color))
        btn.setIconSize(QSize(16, 16))
    return btn


UI_FONT_FILES = ("inter_regular.ttf", "inter_medium.ttf", "inter_semibold.ttf")


def load_ui_fonts():
    """Register the bundled Inter faces so the companion matches the phone app."""
    from PySide6.QtGui import QFontDatabase

    loaded = 0
    for name in UI_FONT_FILES:
        path = get_asset_path(os.path.join("fonts", name))
        if os.path.exists(path) and QFontDatabase.addApplicationFont(path) >= 0:
            loaded += 1
    return loaded


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
            painter.setPen(QColor("#E8E8E8"))
            title_font = QFont("Segoe UI Variable Display", 18, QFont.Weight.DemiBold)
            painter.setFont(title_font)
            painter.drawText(QRect(0, 36, self.width(), 34), Qt.AlignmentFlag.AlignCenter, "Drag around the video player area  ·  Esc to cancel")
            painter.setPen(QColor("#ABABAB"))
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
                painter.setPen(QPen(QColor("#E8E8E8"), 2))
                painter.setBrush(Qt.BrushStyle.NoBrush)
                painter.drawRoundedRect(sel, 6, 6)
                w_px, h_px = int(sel.width() * self._dpr), int(sel.height() * self._dpr)
                painter.setPen(QColor("#E8E8E8"))
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
        """Video surface that keeps the aspect ratio and can be zoomed (wheel, + / −) and dragged.

        Double-click resets the zoom, or asks for full screen when not zoomed.
        """
        double_clicked = Signal()
        MAX_ZOOM = 5.0

        def __init__(self, placeholder_text="", parent=None, show_fullscreen_button=True):
            super().__init__(parent)
            self._placeholder_text = placeholder_text
            self._pixmap = None
            self._zoom = 1.0
            self._center = QPointF(0.5, 0.5)
            self._drag_from = None
            self.setAlignment(Qt.AlignmentFlag.AlignCenter)
            self.setMinimumSize(320, 200)
            self.setSizePolicy(QSizePolicy.Policy.Expanding, QSizePolicy.Policy.Expanding)
            self.setStyleSheet(
                "background-color: #0E0E0F; border: none; border-radius: 16px; color: #ABABAB; font-size: 14px; padding: 16px;"
            )
            self.setText(placeholder_text)
            self.setWordWrap(True)

            self._controls = QFrame(self)
            self._controls.setStyleSheet("QFrame { background-color: rgba(0, 0, 0, 200); border-radius: 18px; }")
            row = QHBoxLayout(self._controls)
            row.setContentsMargins(3, 3, 3, 3)
            row.setSpacing(2)
            self._btn_out = QPushButton("−")
            self._lbl_zoom = QLabel("100%")
            self._btn_in = QPushButton("+")
            self._lbl_zoom.setStyleSheet("background: transparent; color: #E8E8E8; font-size: 12px; padding: 0 4px;")
            self._lbl_zoom.setMinimumWidth(42)
            self._lbl_zoom.setAlignment(Qt.AlignmentFlag.AlignCenter)
            buttons = [self._btn_out, self._btn_in]
            row.addWidget(self._btn_out)
            row.addWidget(self._lbl_zoom)
            row.addWidget(self._btn_in)
            self._btn_full = None
            if show_fullscreen_button:
                self._btn_full = QPushButton()
                self._btn_full.setIcon(fluent_icon("fullscreen", "#E8E8E8"))
                self._btn_full.setToolTip("Full screen")
                self._btn_full.clicked.connect(self.double_clicked.emit)
                buttons.append(self._btn_full)
                row.addWidget(self._btn_full)
            for btn in buttons:
                btn.setObjectName("ZoomBtn")
                btn.setFixedSize(30, 30)
                btn.setCursor(QCursor(Qt.CursorShape.PointingHandCursor))
            self._btn_out.setToolTip("Zoom out")
            self._btn_in.setToolTip("Zoom in")
            self._btn_out.clicked.connect(lambda: self.set_zoom(self._zoom / 1.5))
            self._btn_in.clicked.connect(lambda: self.set_zoom(self._zoom * 1.5))
            self._controls.hide()

        @property
        def zoom(self):
            return self._zoom

        def set_zoom(self, zoom, anchor=None):
            """Zoom keeping the source point under anchor (0..1 of the widget) in place."""
            new_zoom = max(1.0, min(self.MAX_ZOOM, float(zoom)))
            if anchor is not None and self._zoom != new_zoom:
                ax, ay = anchor
                # Source point currently under the anchor, then recentre so it stays there.
                sx = self._center.x() + (ax - 0.5) / self._zoom
                sy = self._center.y() + (ay - 0.5) / self._zoom
                self._center = QPointF(sx - (ax - 0.5) / new_zoom, sy - (ay - 0.5) / new_zoom)
            self._zoom = new_zoom
            self._clamp_center()
            self._lbl_zoom.setText(f"{int(round(self._zoom * 100))}%")
            self.setCursor(QCursor(Qt.CursorShape.OpenHandCursor if self._zoom > 1 else Qt.CursorShape.ArrowCursor))
            self._render_scaled()

        def reset_zoom(self):
            self._center = QPointF(0.5, 0.5)
            self.set_zoom(1.0)

        def _clamp_center(self):
            half = 0.5 / self._zoom
            self._center = QPointF(min(1 - half, max(half, self._center.x())), min(1 - half, max(half, self._center.y())))

        def set_frame_bytes(self, jpeg_bytes):
            if not jpeg_bytes:
                return
            img = QImage.fromData(jpeg_bytes)
            if not img.isNull():
                self._pixmap = QPixmap.fromImage(img)
                self.setText("")
                self._controls.show()
                self._render_scaled()

        def clear_frame(self):
            self._pixmap = None
            self.setPixmap(QPixmap())
            self.setText(self._placeholder_text)
            self._controls.hide()

        def _render_scaled(self):
            if not self._pixmap or self._pixmap.isNull():
                return
            avail = self.size() - QSize(12, 12)
            if avail.width() <= 10 or avail.height() <= 10:
                return
            source = self._pixmap
            if self._zoom > 1.0:
                w = source.width() / self._zoom
                h = source.height() / self._zoom
                x = self._center.x() * source.width() - w / 2
                y = self._center.y() * source.height() - h / 2
                source = source.copy(QRect(int(x), int(y), max(1, int(w)), max(1, int(h))))
            scaled = source.scaled(avail, Qt.AspectRatioMode.KeepAspectRatio, Qt.TransformationMode.SmoothTransformation)
            super().setPixmap(scaled)

        def _place_controls(self):
            self._controls.adjustSize()
            self._controls.move(self.width() - self._controls.width() - 12, self.height() - self._controls.height() - 12)
            self._controls.raise_()

        def resizeEvent(self, event):
            super().resizeEvent(event)
            self._place_controls()
            if self._pixmap is not None:
                self._render_scaled()

        def wheelEvent(self, event):
            if self._pixmap is None:
                return super().wheelEvent(event)
            pos = event.position()
            step = 1.12 if event.angleDelta().y() > 0 else 1 / 1.12
            self.set_zoom(self._zoom * step, (pos.x() / max(1, self.width()), pos.y() / max(1, self.height())))
            event.accept()

        def mousePressEvent(self, event):
            if self._zoom > 1 and event.button() == Qt.MouseButton.LeftButton:
                self._drag_from = event.position()
                self.setCursor(QCursor(Qt.CursorShape.ClosedHandCursor))
            super().mousePressEvent(event)

        def mouseMoveEvent(self, event):
            if self._drag_from is not None and self._pixmap is not None:
                delta = event.position() - self._drag_from
                self._drag_from = event.position()
                shown = self.pixmap()
                span_w = max(1, shown.width() if shown and not shown.isNull() else self.width())
                span_h = max(1, shown.height() if shown and not shown.isNull() else self.height())
                self._center = QPointF(
                    self._center.x() - delta.x() / span_w / self._zoom,
                    self._center.y() - delta.y() / span_h / self._zoom,
                )
                self._clamp_center()
                self._render_scaled()
            super().mouseMoveEvent(event)

        def mouseReleaseEvent(self, event):
            if self._drag_from is not None:
                self._drag_from = None
                self.setCursor(QCursor(Qt.CursorShape.OpenHandCursor))
            super().mouseReleaseEvent(event)

        def mouseDoubleClickEvent(self, event):
            super().mouseDoubleClickEvent(event)
            if self._zoom > 1:
                self.reset_zoom()
            else:
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
            self.lbl_title.setStyleSheet("font-size: 15px; font-weight: 500; color: #E8E8E8;")
            top_row.addWidget(self.lbl_title)

            self.lbl_meta = QLabel("Live")
            self.lbl_meta.setStyleSheet("color: #ABABAB; margin-left: 12px;")
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

        self.remote_identity = RemoteIdentity()
        self.tunnel = None
        self.remote_public_url = None

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
        self.setMinimumSize(1000, 680)
        self.resize(1240, 800)

        self.icon_path = get_asset_path("icon.ico")
        self.icon_png_path = get_asset_path("icon.png")
        if os.path.exists(self.icon_png_path):
            self.setWindowIcon(QIcon(self.icon_png_path))
        elif os.path.exists(self.icon_path):
            self.setWindowIcon(QIcon(self.icon_path))

        load_ui_fonts()
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

        self.client_status_timer = QTimer(self)
        self.client_status_timer.timeout.connect(self._refresh_client_status)
        self.client_status_timer.start(1000)

        QTimer.singleShot(400, self._show_update_recovery_notice)
        QTimer.singleShot(250, self.refresh_quick_apps)

        self.start_server()
        QTimer.singleShot(1500, self._restore_tunnel)
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
            pen = QPen(QColor("#000000"), 5)
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
        """Server and update controls. They live on the Settings page; the window has no header bar."""
        self.lbl_status_pill = QLabel("●  Server running")
        self.btn_toggle = make_button("Stop server", "stop")
        self.btn_toggle.setObjectName("DangerBtn")
        self.btn_toggle.clicked.connect(self.toggle_server)
        self.btn_restart = make_button("Restart", "refresh")
        self.btn_restart.clicked.connect(self.restart_server)
        self.btn_update = make_button("Check for updates", "refresh", "#000000")
        self.btn_update.setObjectName("PrimaryBtn")
        self.btn_update.clicked.connect(self.check_for_companion_updates)
        self._update_status_ui(True)

    def _build_body(self, root_layout):
        body = QWidget()
        body_layout = QHBoxLayout(body)
        body_layout.setContentsMargins(0, 0, 0, 0)
        body_layout.setSpacing(0)

        sidebar = QFrame()
        sidebar.setObjectName("Sidebar")
        sidebar.setFixedWidth(256)
        sb_layout = QVBoxLayout(sidebar)
        sb_layout.setContentsMargins(12, 14, 12, 16)
        sb_layout.setSpacing(2)

        brand = QHBoxLayout()
        brand.setContentsMargins(10, 4, 10, 18)
        brand.setSpacing(10)
        logo_path = get_asset_path("icon.png")
        if os.path.exists(logo_path):
            lbl_logo = QLabel()
            lbl_logo.setPixmap(
                QPixmap(logo_path).scaled(28, 28, Qt.AspectRatioMode.KeepAspectRatio, Qt.TransformationMode.SmoothTransformation)
            )
            brand.addWidget(lbl_logo)
        lbl_title = QLabel("BT Mouse")
        lbl_title.setStyleSheet("font-size: 16px; font-weight: 600;")
        brand.addWidget(lbl_title)
        brand.addStretch()
        sb_layout.addLayout(brand)

        self.stack = QStackedWidget()
        self.nav_buttons = []
        self.nav_icons = []

        pages = [
            ("Connect", "link", self._create_pair_page),
            ("Phone camera", "camera", self._create_phone_cam_page),
            ("Webcam && apps", "video", self._create_desktop_page),
            ("Library", "library", self._create_recordings_page),
            ("Activity", "pulse", self._create_logs_page),
            ("Settings", "settings", self._create_settings_page),
        ]

        for idx, (label, icon_name, builder) in enumerate(pages):
            btn = QPushButton(label)
            btn.setObjectName("NavBtn")
            btn.setIconSize(QSize(18, 18))
            btn.setCursor(QCursor(Qt.CursorShape.PointingHandCursor))
            self.nav_icons.append(icon_name)
            btn.clicked.connect(lambda _checked=False, i=idx: self._switch_page(i))
            self.nav_buttons.append(btn)
            self.stack.addWidget(self._page_scroll(builder()))
            if label != "Settings":
                sb_layout.addWidget(btn)

        sb_layout.addStretch()

        device_card = QFrame()
        device_card.setObjectName("SidebarFooter")
        dc_layout = QVBoxLayout(device_card)
        dc_layout.setContentsMargins(14, 12, 14, 12)
        dc_layout.setSpacing(4)
        self.lbl_footer_client = QLabel("No phone connected")
        self.lbl_footer_client.setWordWrap(True)
        self.lbl_footer_client.setStyleSheet("font-weight: 500;")
        dc_layout.addWidget(self.lbl_footer_client)
        self.lbl_sidebar_ip = QLabel(f"{self.current_ip}  ·  PIN {self.auth_token}")
        self.lbl_sidebar_ip.setStyleSheet("color: #8E8E8E; font-size: 13px;")
        dc_layout.addWidget(self.lbl_sidebar_ip)
        self.lbl_sidebar_pin = self.lbl_sidebar_ip
        sb_layout.addWidget(device_card)

        bottom = QHBoxLayout()
        bottom.setContentsMargins(0, 10, 6, 0)
        bottom.addWidget(self.nav_buttons[-1])
        bottom.addStretch()
        lbl_ver = QLabel(f"v{APP_VERSION}")
        lbl_ver.setStyleSheet("color: #8E8E8E; font-size: 13px;")
        bottom.addWidget(lbl_ver)
        sb_layout.addLayout(bottom)

        content = QWidget()
        content_layout = QVBoxLayout(content)
        content_layout.setContentsMargins(0, 0, 0, 0)
        content_layout.setSpacing(0)

        # Shown while a phone is looking at this screen, with a one-click stop.
        self.screen_banner = QFrame()
        self.screen_banner.setObjectName("Banner")
        banner_row = QHBoxLayout(self.screen_banner)
        banner_row.setContentsMargins(16, 8, 8, 8)
        dot = QLabel("●")
        dot.setStyleSheet("color: #F28B82; font-size: 12px;")
        banner_row.addWidget(dot)
        self.lbl_screen_banner = QLabel("Your phone is viewing this screen")
        banner_row.addWidget(self.lbl_screen_banner, 1)
        btn_stop_share = QPushButton("Stop sharing")
        btn_stop_share.clicked.connect(self.stop_screen_sharing)
        banner_row.addWidget(btn_stop_share)
        banner_wrap = QHBoxLayout()
        banner_wrap.setContentsMargins(32, 16, 32, 0)
        banner_wrap.addWidget(self.screen_banner)
        content_layout.addLayout(banner_wrap)
        self.screen_banner.hide()

        content_layout.addWidget(self.stack, 1)

        body_layout.addWidget(sidebar)
        body_layout.addWidget(content, 1)
        root_layout.addWidget(body, 1)

        self._switch_page(0)

    @staticmethod
    def _page_scroll(page):
        """Pages scroll inside the window instead of squeezing their contents."""
        if isinstance(page, QScrollArea):
            return page
        scroll = QScrollArea()
        scroll.setWidgetResizable(True)
        scroll.setFrameShape(QFrame.Shape.NoFrame)
        scroll.setHorizontalScrollBarPolicy(Qt.ScrollBarPolicy.ScrollBarAlwaysOff)
        scroll.setWidget(page)
        return scroll

    @staticmethod
    def _page_header(layout, title, subtitle=None):
        lbl = QLabel(title)
        lbl.setObjectName("PageTitle")
        layout.addWidget(lbl)
        if subtitle:
            sub = QLabel(subtitle)
            sub.setObjectName("PageSubtitle")
            sub.setWordWrap(True)
            layout.addWidget(sub)
        layout.addSpacing(16)

    def _switch_page(self, index):
        self.stack.setCurrentIndex(index)
        for i, btn in enumerate(self.nav_buttons):
            btn.setProperty("active", "true" if i == index else "false")
            btn.setIcon(fluent_icon(self.nav_icons[i], ICON_COLOR_ACCENT if i == index else ICON_COLOR_MUTED))
            btn.style().unpolish(btn)
            btn.style().polish(btn)

    def switch_to_recordings_tab(self):
        self._switch_page(3)

    def stop_screen_sharing(self):
        if self.server:
            self.server.stop_screen_sharing()
        self.screen_banner.hide()
        self.log("Stopped sharing this screen with the phone.")

    def _refresh_screen_banner(self):
        viewing = bool(self.server and getattr(self.server, "screen_viewer_active", lambda: False)())
        if viewing != self.screen_banner.isVisible():
            self.screen_banner.setVisible(viewing)

    def _set_screen_preview_allowed(self, allowed):
        if self.server:
            self.server.set_screen_preview_allowed(allowed)
        if not allowed:
            self.screen_banner.hide()

    def _build_statusbar(self, root_layout):
        """Kept for callers that update the footer text; the footer itself is no longer shown."""
        self.lbl_footer_left = QLabel(f"BT Mouse Companion v{APP_VERSION}")

    # ------------------------------------------------------------------
    # Page 1: Pair a device (one QR for Wi-Fi, Bluetooth and internet)
    # ------------------------------------------------------------------
    def _section_label(self, text):
        lbl = QLabel(text)
        lbl.setObjectName("SectionLabel")
        return lbl

    def _page(self, title, subtitle=None, max_width=820):
        """A page column centred in the window, capped at max_width, with a title on top."""
        page = QWidget()
        page.setObjectName("PageRoot")
        outer = QHBoxLayout(page)
        outer.setContentsMargins(32, 28, 32, 28)
        column = QWidget()
        column.setMaximumWidth(max_width)
        layout = QVBoxLayout(column)
        layout.setContentsMargins(0, 0, 0, 0)
        layout.setSpacing(12)
        outer.addStretch(0)
        outer.addWidget(column, 1)
        outer.addStretch(0)
        self._page_header(layout, title, subtitle)
        return page, layout

    def _link_row(self, icon, title, subtitle_widget, trailing=None):
        """One connection type: icon tile, name with details, and an optional control on the right."""
        row = QFrame()
        row.setObjectName("SubCard")
        r = QHBoxLayout(row)
        r.setContentsMargins(16, 14, 16, 14)
        r.setSpacing(14)
        tile = QLabel()
        tile.setFixedSize(36, 36)
        tile.setAlignment(Qt.AlignmentFlag.AlignCenter)
        tile.setStyleSheet("background-color: #232325; border-radius: 10px;")
        tile.setPixmap(fluent_icon(icon, "#D4D4D4").pixmap(18, 18))
        r.addWidget(tile, 0, Qt.AlignmentFlag.AlignTop)
        col = QVBoxLayout()
        col.setSpacing(4)
        name = QLabel(title)
        name.setStyleSheet("font-weight: 500;")
        col.addWidget(name)
        col.addWidget(subtitle_widget)
        r.addLayout(col, 1)
        if trailing is not None:
            r.addWidget(trailing, 0, Qt.AlignmentFlag.AlignTop)
        return row, col

    def _create_pair_page(self):
        page, layout = self._page(
            "Connect your phone",
            "Open BT Mouse on your phone and scan this code. It pairs once, then reconnects on its own "
            "over whichever link is fastest.",
        )

        pair_card = QFrame()
        pair_card.setObjectName("Card")
        pc = QHBoxLayout(pair_card)
        pc.setContentsMargins(24, 24, 24, 24)
        pc.setSpacing(28)

        self.qr_label = QLabel()
        self.qr_label.setFixedSize(196, 196)
        self.qr_label.setAlignment(Qt.AlignmentFlag.AlignCenter)
        self.qr_label.setStyleSheet("background-color: #FFFFFF; border-radius: 14px; padding: 12px;")
        pc.addWidget(self.qr_label, 0, Qt.AlignmentFlag.AlignTop)

        pin_col = QVBoxLayout()
        pin_col.setSpacing(6)
        pin_col.addWidget(self._section_label("Pairing PIN"))
        self.lbl_wifi_pin = QLabel(self.auth_token)
        self.lbl_wifi_pin.setStyleSheet("font-size: 40px; font-weight: 600; letter-spacing: 3px;")
        pin_col.addWidget(self.lbl_wifi_pin)
        lbl_pin_d = QLabel("The code already includes the PIN. Type it only if you connect by address. "
                           "Change it any time; paired phones just ask for the new one.")
        lbl_pin_d.setWordWrap(True)
        lbl_pin_d.setStyleSheet("color: #8E8E8E; font-size: 13px;")
        pin_col.addWidget(lbl_pin_d)
        pin_col.addSpacing(8)
        pin_row = QHBoxLayout()
        pin_row.setSpacing(8)
        self.ent_token = QLineEdit(self.auth_token)
        self.ent_token.setMaxLength(6)
        self.ent_token.setMaximumWidth(140)
        pin_row.addWidget(self.ent_token)
        btn_upd = QPushButton("Save PIN")
        btn_upd.setObjectName("PrimaryBtn")
        btn_upd.clicked.connect(self._update_auth_token)
        pin_row.addWidget(btn_upd)
        btn_copy_pin = make_button("Copy", "copy")
        btn_copy_pin.clicked.connect(self.copy_pairing_pin)
        pin_row.addWidget(btn_copy_pin)
        btn_ref = make_button("New code", "refresh")
        btn_ref.setToolTip("Draw the QR code again")
        btn_ref.clicked.connect(self.generate_qr)
        pin_row.addWidget(btn_ref)
        pin_row.addStretch()
        pin_col.addLayout(pin_row)
        pin_col.addStretch()
        pc.addLayout(pin_col, 1)
        layout.addWidget(pair_card)

        layout.addSpacing(12)
        layout.addWidget(self._section_label("Ways to connect"))

        # Wi-Fi (same network).
        wifi_details = QWidget()
        wd = QVBoxLayout(wifi_details)
        wd.setContentsMargins(0, 0, 0, 0)
        wd.setSpacing(8)
        addr_row = QHBoxLayout()
        self.lbl_ip_val = QLabel(self.current_ip)
        self.lbl_ip_val.setStyleSheet("color: #ABABAB;")
        addr_row.addWidget(self.lbl_ip_val)
        self.lbl_port_val = QLabel(f"·  port {self.current_port}")
        self.lbl_port_val.setStyleSheet("color: #8E8E8E;")
        addr_row.addWidget(self.lbl_port_val)
        addr_row.addStretch()
        wd.addLayout(addr_row)
        self.lbl_wan_url = QLabel(f"ws://{self.current_ip}:{self.ws_port}")
        self.lbl_wan_url.setStyleSheet("color: #8E8E8E; font-size: 13px;")
        self.lbl_wan_url.hide()
        wd.addWidget(self.lbl_wan_url)
        adapter_row = QHBoxLayout()
        self.combo_adapter = QComboBox()
        for name, ip in self.adapters:
            self.combo_adapter.addItem(f"{name}: {ip}", ip)
        self.combo_adapter.currentIndexChanged.connect(self._on_adapter_selected)
        adapter_row.addWidget(self.combo_adapter, 1)
        btn_copy_ip = make_button("Copy address", "copy")
        btn_copy_ip.clicked.connect(self.copy_ip)
        adapter_row.addWidget(btn_copy_ip)
        wd.addLayout(adapter_row)
        wifi_row, _ = self._link_row("wifi", "Wi-Fi", wifi_details)
        layout.addWidget(wifi_row)

        # Bluetooth (nearby, works with no network at all).
        lbl_bt = QLabel(
            f"Pair this PC (“{socket.gethostname()}”) with your phone once in Windows Bluetooth settings. "
            "After that the app also connects over Bluetooth whenever the PC is in range."
        )
        lbl_bt.setWordWrap(True)
        lbl_bt.setStyleSheet("color: #8E8E8E; font-size: 13px;")
        btn_bt = make_button("Bluetooth settings", "bluetooth")
        btn_bt.clicked.connect(self.open_windows_bt_settings)
        bt_row, _ = self._link_row("bluetooth", "Bluetooth", lbl_bt, btn_bt)
        layout.addWidget(bt_row)

        # Internet (anywhere, through the Cloudflare quick tunnel).
        inet_details = QWidget()
        idl = QVBoxLayout(inet_details)
        idl.setContentsMargins(0, 0, 0, 0)
        idl.setSpacing(8)
        self.lbl_tunnel_status = QLabel("Off · turn on to reach this PC from anywhere")
        self.lbl_tunnel_status.setWordWrap(True)
        self.lbl_tunnel_status.setStyleSheet("color: #8E8E8E; font-size: 13px;")
        idl.addWidget(self.lbl_tunnel_status)
        worker_row = QHBoxLayout()
        self.ent_worker_url = QLineEdit(self.remote_identity.worker_url)
        self.ent_worker_url.setPlaceholderText("Cloudflare Worker URL, e.g. https://btmouse-rv.<you>.workers.dev")
        worker_row.addWidget(self.ent_worker_url, 1)
        btn_save_worker = QPushButton("Save")
        btn_save_worker.clicked.connect(self._save_worker_url)
        worker_row.addWidget(btn_save_worker)
        idl.addLayout(worker_row)
        self.lbl_public_url = QLabel("—")
        self.lbl_public_url.setWordWrap(True)
        self.lbl_public_url.setStyleSheet("color: #8E8E8E; font-size: 12px;")
        idl.addWidget(self.lbl_public_url)
        self.btn_tunnel = QPushButton("Turn on")
        self.btn_tunnel.setObjectName("PrimaryBtn")
        self.btn_tunnel.clicked.connect(self._toggle_tunnel)
        inet_row, _ = self._link_row("globe", "Internet access", inet_details, self.btn_tunnel)
        layout.addWidget(inet_row)
        layout.addStretch()

        self.generate_qr()
        return page

    # ------------------------------------------------------------------
    # Page 2: Phone as Webcam (Large Auto-Scaling Viewfinder + Fullscreen + Orientation + Recording)
    # ------------------------------------------------------------------
    # Phone camera: what the phone sends, zoomable, with full screen and recording
    # ------------------------------------------------------------------
    def _create_phone_cam_page(self):
        page, layout = self._page(
            "Phone camera",
            "Live from your phone. Scroll or use − / + to zoom, drag to move around, double-click to go full screen.",
            max_width=980,
        )

        status_row = QHBoxLayout()
        self.lbl_phone_cam_status = QLabel("○ Standby")
        self.lbl_phone_cam_status.setStyleSheet("color: #ABABAB; font-weight: 500;")
        status_row.addWidget(self.lbl_phone_cam_status)
        self.lbl_phone_cam_orientation = QLabel("Orientation: auto")
        self.lbl_phone_cam_orientation.setStyleSheet("color: #8E8E8E;")
        status_row.addWidget(self.lbl_phone_cam_orientation)
        status_row.addStretch()
        layout.addLayout(status_row)

        self.lbl_phone_cam_preview = AspectRatioVideoWidget(
            "Phone camera on standby\n\nIn the phone app, open Cameras  ›  Phone and start the camera.",
            self,
        )
        self.lbl_phone_cam_preview.setMinimumHeight(380)
        self.lbl_phone_cam_preview.double_clicked.connect(self.open_phone_cam_fullscreen)
        layout.addWidget(self.lbl_phone_cam_preview, 1)

        self.lbl_phone_cam_telemetry = QLabel("Waiting for the phone…")
        self.lbl_phone_cam_telemetry.setStyleSheet("color: #8E8E8E; font-size: 13px;")
        layout.addWidget(self.lbl_phone_cam_telemetry)

        bar = QHBoxLayout()
        bar.setSpacing(8)
        btn_rot = make_button("Rotate 90°", "rotate")
        btn_rot.clicked.connect(self.rotate_phone_cam_90)
        bar.addWidget(btn_rot)
        btn_snap = make_button("Snapshot", "camera")
        btn_snap.clicked.connect(self._save_phone_cam_snapshot)
        bar.addWidget(btn_snap)
        btn_fs = make_button("Full screen", "fullscreen")
        btn_fs.clicked.connect(self.open_phone_cam_fullscreen)
        bar.addWidget(btn_fs)
        bar.addStretch()
        self.btn_phone_cam_rec = make_button("Record", "record", "#000000")
        self.btn_phone_cam_rec.setObjectName("PrimaryBtn")
        self.btn_phone_cam_rec.clicked.connect(self.toggle_phone_cam_recording)
        bar.addWidget(self.btn_phone_cam_rec)
        layout.addLayout(bar)

        layout.addSpacing(12)
        layout.addWidget(self._section_label("Use it in other apps"))
        port = getattr(getattr(self.server, "phone_camera_http", None), "port", 8766)
        mjpeg_url = f"http://127.0.0.1:{port}/phone_cam/mjpeg"
        url_box = QWidget()
        ub = QVBoxLayout(url_box)
        ub.setContentsMargins(0, 0, 0, 0)
        ub.setSpacing(8)
        self.lbl_phone_mjpeg_url = QLabel(mjpeg_url)
        self.lbl_phone_mjpeg_url.setTextInteractionFlags(Qt.TextInteractionFlag.TextSelectableByMouse)
        self.lbl_phone_mjpeg_url.setStyleSheet("color: #ABABAB;")
        ub.addWidget(self.lbl_phone_mjpeg_url)
        vcam_status = getattr(getattr(self.server, "phone_camera_receiver", None), "virtual_cam_status", "MJPEG_STREAM")
        vcam_err = getattr(getattr(self.server, "phone_camera_receiver", None), "virtual_cam_error", "MJPEG stream ready.")
        self.lbl_vcam_status = QLabel(f"Add this address as a browser or media source in OBS, Zoom or Teams.  ·  {vcam_status}")
        self.lbl_vcam_status.setWordWrap(True)
        self.lbl_vcam_status.setToolTip(vcam_err)
        self.lbl_vcam_status.setStyleSheet("color: #8E8E8E; font-size: 13px;")
        ub.addWidget(self.lbl_vcam_status)
        btns = QHBoxLayout()
        btn_cp = make_button("Copy address", "copy")
        btn_cp.clicked.connect(self._copy_phone_mjpeg_url)
        btns.addWidget(btn_cp)
        btn_br = make_button("Open in browser", "open")
        btn_br.clicked.connect(lambda: webbrowser.open(self.lbl_phone_mjpeg_url.text()))
        btns.addWidget(btn_br)
        btn_open_vids = make_button("Videos folder", "folder")
        btn_open_vids.clicked.connect(self.open_videos_folder)
        btns.addWidget(btn_open_vids)
        btns.addStretch()
        ub.addLayout(btns)
        row, _ = self._link_row("video", "Phone camera stream", url_box)
        layout.addWidget(row)
        return page

    # ------------------------------------------------------------------
    # Page 3: Media Library (Videos / Audios / Snapshots in Separate Folders)
    # ------------------------------------------------------------------
    def _create_recordings_page(self):
        page, layout = self._page("Library", "Recordings and snapshots from your webcam and phone.", max_width=1100)

        card = QFrame()
        card.setObjectName("Card")
        c_layout = QVBoxLayout(card)
        c_layout.setContentsMargins(18, 16, 18, 16)
        c_layout.setSpacing(12)

        top_row = QHBoxLayout()
        top_row.setSpacing(8)
        for label, handler in (("Videos", self.open_videos_folder), ("Audios", self.open_audios_folder),
                               ("Snapshots", self.open_snapshots_folder)):
            b = make_button(label, "folder")
            b.clicked.connect(handler)
            top_row.addWidget(b)
        top_row.addStretch()
        btn_ref = make_button("Refresh", "refresh")
        btn_ref.clicked.connect(self.refresh_recordings)
        top_row.addWidget(btn_ref)
        c_layout.addLayout(top_row)

        # Stats Bar
        stats_bar = QFrame()
        stats_bar.setObjectName("SubCard")
        s_row = QHBoxLayout(stats_bar)
        s_row.setContentsMargins(14, 8, 14, 8)
        self.lbl_recordings_stats = QLabel("Loading library…")
        self.lbl_recordings_stats.setStyleSheet("font-weight: 500;")
        s_row.addWidget(self.lbl_recordings_stats)

        self.lbl_disk_free = QLabel("")
        self.lbl_disk_free.setStyleSheet("color: #ABABAB; margin-left: 14px;")
        s_row.addWidget(self.lbl_disk_free)
        s_row.addStretch()

        self.lbl_mgr_folder_path = QLabel(str(self.media_dirs["base"]))
        self.lbl_mgr_folder_path.setStyleSheet("font-size: 12px; color: #8E8E8E;")
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
        self.lbl_selected_recording_info.setStyleSheet("color: #8E8E8E;")
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
        layout.addWidget(card, 1)
        self.tree_recordings.setMinimumHeight(380)
        self._set_recording_filter("all")
        return page

    # ------------------------------------------------------------------
    # Page 4: Desktop Controls, PC Webcam Live Preview & Quick Apps
    # ------------------------------------------------------------------
    # Webcam & apps: the PC webcam the phone can watch, and the apps the phone can open
    # ------------------------------------------------------------------
    def _create_desktop_page(self):
        page, layout = self._page(
            "Webcam & apps",
            "Your PC webcam, which the phone can watch, and the apps your phone can open.",
            max_width=980,
        )

        bar = QHBoxLayout()
        bar.setSpacing(8)
        self.btn_pc_cam_live = make_button("Start preview", "video")
        self.btn_pc_cam_live.clicked.connect(self.toggle_local_pc_webcam_preview)
        bar.addWidget(self.btn_pc_cam_live)
        btn_pc_snap = make_button("Snapshot", "camera")
        btn_pc_snap.clicked.connect(self.test_camera_snapshot)
        bar.addWidget(btn_pc_snap)
        btn_pc_mic = make_button("Test microphone", "mic")
        btn_pc_mic.clicked.connect(self.test_mic_record)
        bar.addWidget(btn_pc_mic)
        btn_pc_fs = make_button("Full screen", "fullscreen")
        btn_pc_fs.clicked.connect(self.open_pc_cam_fullscreen)
        bar.addWidget(btn_pc_fs)
        bar.addStretch()

        self.lbl_cam_preview = AspectRatioVideoWidget(
            "Webcam is off. Start a preview here, or start it from your phone.",
            self,
        )
        self.lbl_cam_preview.setMinimumHeight(320)
        self.lbl_cam_preview.double_clicked.connect(self.open_pc_cam_fullscreen)
        layout.addWidget(self.lbl_cam_preview, 1)
        layout.addLayout(bar)

        self.lbl_media_status = QLabel("Recordings from the webcam combine video and microphone into one MP4 in Videos.")
        self.lbl_media_status.setStyleSheet("color: #8E8E8E; font-size: 13px;")
        self.lbl_media_status.setWordWrap(True)
        layout.addWidget(self.lbl_media_status)

        layout.addSpacing(12)
        qa_head = QHBoxLayout()
        lbl_qat = QLabel("Quick apps on your phone")
        lbl_qat.setStyleSheet("font-size: 18px; font-weight: 500;")
        qa_head.addWidget(lbl_qat)
        qa_head.addStretch()
        btn_ref_qa = make_button("Refresh", "refresh")
        btn_ref_qa.clicked.connect(self.refresh_quick_apps)
        qa_head.addWidget(btn_ref_qa)
        layout.addLayout(qa_head)

        qa_card = QFrame()
        qa_card.setObjectName("Card")
        qa_l = QVBoxLayout(qa_card)
        qa_l.setContentsMargins(18, 16, 18, 16)
        qa_l.setSpacing(10)

        add_row = QHBoxLayout()
        self.combo_available_apps = QComboBox()
        add_row.addWidget(self.combo_available_apps, 1)
        btn_add_qa = make_button("Add app", "add", "#000000")
        btn_add_qa.setObjectName("PrimaryBtn")
        btn_add_qa.clicked.connect(self.add_quick_app)
        add_row.addWidget(btn_add_qa)
        qa_l.addLayout(add_row)

        self.list_quick_apps = QListWidget()
        self.list_quick_apps.setMinimumHeight(150)
        self.list_quick_apps.currentRowChanged.connect(self.on_quick_app_selected)
        qa_l.addWidget(self.list_quick_apps)

        edit_row = QHBoxLayout()
        self.ent_quick_app_name = QLineEdit()
        self.ent_quick_app_name.setPlaceholderText("New name for the selected app")
        edit_row.addWidget(self.ent_quick_app_name, 1)
        btn_ren = QPushButton("Rename")
        btn_ren.clicked.connect(self.rename_quick_app)
        edit_row.addWidget(btn_ren)
        btn_rem = QPushButton("Remove")
        btn_rem.setObjectName("DangerBtn")
        btn_rem.clicked.connect(self.remove_quick_app)
        edit_row.addWidget(btn_rem)
        qa_l.addLayout(edit_row)

        self.lbl_quick_apps_status = QLabel("Quick apps stay in sync with your phone.")
        self.lbl_quick_apps_status.setStyleSheet("color: #8E8E8E; font-size: 13px;")
        qa_l.addWidget(self.lbl_quick_apps_status)
        layout.addWidget(qa_card)
        return page

    # ------------------------------------------------------------------
    # Settings: server, updates, screen sharing, startup and recording limits
    # ------------------------------------------------------------------
    def _create_settings_page(self):
        page, layout = self._page("Settings")

        server_card = QFrame()
        server_card.setObjectName("Card")
        sc = QHBoxLayout(server_card)
        sc.setContentsMargins(20, 16, 16, 16)
        col = QVBoxLayout()
        lbl = QLabel("Companion server")
        lbl.setStyleSheet("font-weight: 500;")
        col.addWidget(lbl)
        col.addWidget(self.lbl_status_pill)
        sc.addLayout(col, 1)
        sc.addWidget(self.btn_restart)
        sc.addWidget(self.btn_toggle)
        layout.addWidget(server_card)

        upd_card = QFrame()
        upd_card.setObjectName("Card")
        uc = QHBoxLayout(upd_card)
        uc.setContentsMargins(20, 16, 16, 16)
        ucol = QVBoxLayout()
        lbl_u = QLabel("Updates")
        lbl_u.setStyleSheet("font-weight: 500;")
        ucol.addWidget(lbl_u)
        lbl_v = QLabel(f"Version {APP_VERSION}")
        lbl_v.setStyleSheet("color: #8E8E8E; font-size: 13px;")
        ucol.addWidget(lbl_v)
        uc.addLayout(ucol, 1)
        uc.addWidget(self.btn_update)
        layout.addWidget(upd_card)

        layout.addSpacing(8)
        layout.addWidget(self._section_label("Privacy and startup"))
        pref_card = QFrame()
        pref_card.setObjectName("Card")
        pc = QVBoxLayout(pref_card)
        pc.setContentsMargins(20, 16, 20, 16)
        pc.setSpacing(12)
        self.chk_screen_preview = QCheckBox("Let my paired phone view this screen")
        self.chk_screen_preview.setChecked(bool(getattr(self.server, "screen_preview_allowed", True)))
        self.chk_screen_preview.toggled.connect(self._set_screen_preview_allowed)
        pc.addWidget(self.chk_screen_preview)
        lbl_sp = QLabel("A banner shows while it is watching, with a button to stop.")
        lbl_sp.setStyleSheet("color: #8E8E8E; font-size: 13px; margin-left: 30px;")
        pc.addWidget(lbl_sp)
        self.chk_startup = QCheckBox("Start with Windows, in the tray")
        self.chk_startup.setChecked(self._startup_registry_value() is not None)
        self.chk_startup.toggled.connect(self._set_startup_enabled)
        pc.addWidget(self.chk_startup)
        layout.addWidget(pref_card)

        layout.addSpacing(8)
        layout.addWidget(self._section_label("Recording"))
        pol_card = QFrame()
        pol_card.setObjectName("Card")
        pol_l = QVBoxLayout(pol_card)
        pol_l.setContentsMargins(20, 16, 20, 16)
        pol_l.setSpacing(12)
        self.chk_auto_video = QCheckBox("Record live webcam sessions to Videos (1280×720 MP4)")
        self.chk_auto_video.setChecked(self.auto_record_video)
        self.chk_auto_video.toggled.connect(self._save_recording_preferences)
        pol_l.addWidget(self.chk_auto_video)
        self.chk_auto_audio = QCheckBox("Include the PC microphone in the same file")
        self.chk_auto_audio.setChecked(self.auto_record_audio)
        self.chk_auto_audio.toggled.connect(self._save_recording_preferences)
        pol_l.addWidget(self.chk_auto_audio)

        lim_grid = QGridLayout()
        lim_grid.setHorizontalSpacing(12)
        lim_grid.setVerticalSpacing(6)
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
        for col_i, (caption, spin) in enumerate(
            (
                ("Delete after (days, 0 keeps)", self.spin_retention),
                ("Library limit (MB, 0 = none)", self.spin_max_mb),
                ("Keep free (MB)", self.spin_min_free),
            )
        ):
            lbl_cap = QLabel(caption)
            lbl_cap.setStyleSheet("color: #ABABAB; font-size: 13px;")
            lim_grid.addWidget(lbl_cap, 0, col_i)
            spin.setMinimumWidth(130)
            lim_grid.addWidget(spin, 1, col_i)
        btn_save_lim = QPushButton("Save limits")
        btn_save_lim.clicked.connect(self._save_recording_preferences)
        lim_grid.addWidget(btn_save_lim, 1, 3)
        lim_grid.setColumnStretch(4, 1)
        pol_l.addLayout(lim_grid)
        layout.addWidget(pol_card)

        layout.addSpacing(8)
        folders = QHBoxLayout()
        for label, handler in (("Videos", self.open_videos_folder), ("Audios", self.open_audios_folder),
                               ("Snapshots", self.open_snapshots_folder)):
            b = make_button(label, "folder")
            b.clicked.connect(handler)
            folders.addWidget(b)
        folders.addStretch()
        layout.addLayout(folders)
        layout.addStretch()
        return page

    # ------------------------------------------------------------------
    # Page 8: Live Monitor
    # ------------------------------------------------------------------
    # Activity: who is connected and what happened
    # ------------------------------------------------------------------
    def _create_logs_page(self):
        page, layout = self._page("Activity", "What your phone has done on this PC.", max_width=980)

        top = QHBoxLayout()
        self.lbl_client = QLabel("No phone connected")
        self.lbl_client.setStyleSheet("font-size: 15px; font-weight: 500; color: #ABABAB;")
        top.addWidget(self.lbl_client)
        top.addStretch()
        self.lbl_packets = QLabel("0 commands")
        self.lbl_packets.setStyleSheet("color: #8E8E8E;")
        top.addWidget(self.lbl_packets)
        btn_clr = make_button("Clear", "clear")
        btn_clr.clicked.connect(lambda: self.txt_log.clear())
        top.addWidget(btn_clr)
        layout.addLayout(top)

        self.txt_log = QPlainTextEdit()
        self.txt_log.setReadOnly(True)
        self.txt_log.setMinimumHeight(460)
        self.txt_log.setStyleSheet("font-size: 13px;")
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
            "QMenu { background-color: #1B1B1C; color: #E8E8E8; border: none; padding: 6px; } "
            "QMenu::item { padding: 6px 18px; border-radius: 4px; } "
            "QMenu::item:selected { background-color: #2E2E30; }"
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
            self.btn_phone_cam_rec.setIcon(fluent_icon("record", "#000000"))
            self.btn_phone_cam_rec.setObjectName("PrimaryBtn")
            self.btn_phone_cam_rec.style().unpolish(self.btn_phone_cam_rec)
            self.btn_phone_cam_rec.style().polish(self.btn_phone_cam_rec)
            if saved:
                self.lbl_phone_cam_telemetry.setText(f"Saved video to Videos/{os.path.basename(saved)}")
                self.refresh_recordings()
        else:
            path = receiver.start_recording()
            self.btn_phone_cam_rec.setText("Stop recording")
            self.btn_phone_cam_rec.setIcon(fluent_icon("stop", "#F9DEDC"))
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
            self.btn_pc_cam_live.setIcon(fluent_icon("video", "#E8E8E8"))
            self.lbl_cam_preview.clear_frame()
            self.lbl_media_status.setText("PC Webcam preview stopped.")
            self.refresh_recordings()
        else:
            self.pc_webcam_preview_active = True
            self.btn_pc_cam_live.setText("Stop preview")
            self.btn_pc_cam_live.setIcon(fluent_icon("stop", "#E8E8E8"))
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
        orient = "Portrait 9:16" if h > w else "Landscape 16:9"
        rot = getattr(getattr(self.server, "phone_camera_receiver", None), "rotation_degrees", 0)
        rot_suffix = f" · Rot {rot}°" if rot else ""
        self.lbl_phone_cam_orientation.setText(f"{orient}{rot_suffix}")
        self.lbl_phone_cam_status.setText(f"● Live · {lens} lens")
        self.lbl_phone_cam_status.setStyleSheet("color: #5BD68A; font-weight: 500;")
        count = getattr(getattr(self.server, "phone_camera_receiver", None), "frame_count", 0)
        meta_text = f"{w}×{h}  ·  {fps:.1f} FPS  ·  {bitrate_kbps:.0f} kbps  ·  {count:,} frames"
        self.lbl_phone_cam_telemetry.setText(meta_text)

        if self.phone_fullscreen_win and self.phone_fullscreen_win.isVisible():
            self.phone_fullscreen_win.video_surface.set_frame_bytes(raw_jpeg)
            self.phone_fullscreen_win.lbl_meta.setText(f"● Live · {lens} lens  ·  {orient}  ·  {meta_text}")

    def _check_phone_cam_active(self):
        receiver = getattr(self.server, "phone_camera_receiver", None)
        if receiver and receiver.latest_timestamp and (time.monotonic() - receiver.latest_timestamp > 4.5):
            if receiver.is_active:
                receiver.is_active = False
                self.lbl_phone_cam_status.setText("○ Standby")
                self.lbl_phone_cam_status.setStyleSheet("color: #ABABAB; font-weight: 500;")
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
            btn.setObjectName("SegOn" if m == mode else "QuietBtn")
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

    def pairing_payload(self):
        """One QR for every link: Wi-Fi (UDP + WebSocket), Bluetooth (matched by PC name) and internet."""
        payload = {
            "type": "btmouse_pc",
            "v": 2,
            "name": socket.gethostname(),
            "ip": self.current_ip,
            "port": self.current_port,
            "ws_port": self.ws_port,
            "url": f"ws://{self.current_ip}:{self.ws_port}",
            "pin": self.auth_token,
            "token": self.auth_token,
        }
        if self.remote_identity.worker_url and self.remote_identity.public_b64:
            payload["rv"] = {
                "worker": self.remote_identity.worker_url,
                "id": self.remote_identity.record_id,
                "pub": self.remote_identity.public_b64,
            }
        return payload

    def generate_qr(self):
        if hasattr(self, "qr_label"):
            self._set_qr_pixmap(self.qr_label, self.pairing_payload(), 172)
        if hasattr(self, "lbl_wan_url"):
            self.lbl_wan_url.setText(f"ws://{self.current_ip}:{self.ws_port}")

    def generate_wan_qr(self):
        # The internet details now live in the same QR as everything else.
        self.generate_qr()

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

    def _save_worker_url(self):
        url = self.ent_worker_url.text().strip().rstrip("/")
        if url and not url.startswith("https://"):
            QMessageBox.warning(self, "Invalid URL",
                                "The Worker URL should start with https:// "
                                "(for example https://btmouse-rv.<you>.workers.dev).")
            return
        self.remote_identity.set_worker_url(url)
        self.generate_wan_qr()
        if url:
            QMessageBox.information(self, "Saved",
                                   "Worker URL saved. Turn on internet access to publish "
                                   "your tunnel address, then scan the QR from your phone.")

    def _toggle_tunnel(self):
        if self.tunnel is not None:
            self.tunnel.stop()
            self.tunnel = None
            self._style_tunnel_button(False)
            self._set_tunnel_status("Off · turn on to reach this PC from anywhere")
            self.remote_identity.set_tunnel_enabled(False)
            return
        if not self.remote_identity.worker_url:
            QMessageBox.warning(self, "Worker URL needed",
                                "Save your Cloudflare Worker URL first.")
            return
        self._start_tunnel()

    def _restore_tunnel(self):
        """Bring internet access back after a restart if it was on, so the PC stays reachable."""
        if self.tunnel is None and self.remote_identity.tunnel_enabled and self.remote_identity.worker_url:
            self._start_tunnel()

    def _start_tunnel(self):
        self.remote_identity.set_tunnel_enabled(True)
        self.tunnel = TunnelPublisher(
            self.remote_identity,
            self.ws_port,
            socket.gethostname(),
            on_status=lambda m: self._run_on_ui(lambda: self._set_tunnel_status(m)),
            on_url=lambda u: self._run_on_ui(lambda: self._set_public_url(u)),
        )
        self._style_tunnel_button(True)
        self.tunnel.start()

    def _style_tunnel_button(self, on):
        self.btn_tunnel.setText("Turn off" if on else "Turn on")
        self.btn_tunnel.setObjectName("" if on else "PrimaryBtn")
        self.btn_tunnel.style().unpolish(self.btn_tunnel)
        self.btn_tunnel.style().polish(self.btn_tunnel)

    def _set_tunnel_status(self, message):
        if hasattr(self, "lbl_tunnel_status"):
            self.lbl_tunnel_status.setText(message)

    def _set_public_url(self, url):
        self.remote_public_url = url
        if hasattr(self, "lbl_public_url"):
            self.lbl_public_url.setText(url or "—")

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
            self.lbl_packets.setText(f"{count:,} commands")

    def _on_client(self, client_name, is_connected):
        self.bridge.client_signal.emit(str(client_name or ""), bool(is_connected))

    def _update_client_ui(self, client_name, is_connected):
        # Link events are only logged: with concurrent transports one link closing does not mean
        # the phone is gone. The status labels come from _refresh_client_status instead.
        self.log(f"{'Connected' if is_connected else 'Link closed'}: {client_name or 'phone'}")

    def _refresh_client_status(self):
        self._refresh_screen_banner()
        clients = self.server.active_clients() if self.server else []
        if clients:
            transports = ", ".join(sorted({label for label, _name in clients}))
            text = f"{clients[0][1]} connected  ·  {transports}"
            color = "#E8E8E8"
        else:
            text = "No phone connected"
            color = "#ABABAB"
        if getattr(self, "_last_client_status", None) == text:
            return
        self._last_client_status = text
        if hasattr(self, "lbl_client"):
            self.lbl_client.setText(text)
            self.lbl_client.setStyleSheet(f"font-size: 15px; font-weight: 500; color: {color};")
        if hasattr(self, "lbl_footer_client"):
            self.lbl_footer_client.setText(text)
            self.lbl_footer_client.setStyleSheet(f"font-weight: 500; color: {color};")

    def _on_status(self, is_running):
        self.is_server_running = is_running
        self.bridge.status_signal.emit(bool(is_running))

    def _update_status_ui(self, is_running):
        if is_running:
            self.lbl_status_pill.setText("●  Running")
            self.lbl_status_pill.setStyleSheet("color: #5BD68A; font-weight: 500;")
            self.btn_toggle.setText("Stop server")
            self.btn_toggle.setIcon(fluent_icon("stop", "#F9DEDC"))
        else:
            self.lbl_status_pill.setText("●  Stopped")
            self.lbl_status_pill.setStyleSheet("color: #F2B8B5; font-weight: 500;")
            self.btn_toggle.setText("Start server")
            self.btn_toggle.setIcon(fluent_icon("play", "#F9DEDC"))

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
                    f"The update could not finish, so the previous version was kept.\n\n{message}\n\n"
                    f"Step-by-step log: {COMPANION_UPDATE_STEP_LOG}",
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
                    self._start_update_download(tag_name, asset)
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

    def _start_update_download(self, tag_name, asset):
        self.btn_update.setText("Downloading…")
        self._update_cancel = threading.Event()
        dlg = QProgressDialog(f"Downloading {tag_name}…", "Cancel", 0, 100, self)
        dlg.setWindowTitle("Updating BT Mouse Companion")
        dlg.setMinimumDuration(0)
        dlg.setAutoClose(False)
        dlg.setAutoReset(False)
        dlg.setMinimumWidth(420)
        dlg.canceled.connect(self._update_cancel.set)
        dlg.setValue(0)
        dlg.show()
        self._update_dialog = dlg
        threading.Thread(target=self._download_companion_update_worker, args=(tag_name, asset), daemon=True).start()

    def _set_update_progress(self, tag_name, downloaded, total):
        dlg = getattr(self, "_update_dialog", None)
        if dlg is None:
            return
        dlg.setValue(int(downloaded * 100 / total) if total else 0)
        dlg.setLabelText(
            f"Downloading {tag_name}… {downloaded / 1048576:.1f} of {total / 1048576:.1f} MB"
        )

    def _close_update_dialog(self):
        dlg = getattr(self, "_update_dialog", None)
        self._update_dialog = None
        if dlg is not None:
            dlg.close()
        self.btn_update.setEnabled(True)
        self.btn_update.setText("Check for updates")

    def _show_update_installing(self, tag_name):
        dlg = getattr(self, "_update_dialog", None)
        if dlg is not None:
            dlg.setCancelButton(None)
            dlg.setRange(0, 0)  # busy indicator while the swap runs after we exit
            dlg.setLabelText(
                f"Installing {tag_name}.\n\nThe companion will close and reopen by itself "
                "in about 10–30 seconds. Please don't reopen it yourself."
            )
        QTimer.singleShot(2500, self.quit_application)

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
            total = int(asset["size"])
            downloaded = 0
            last_percent = -1
            temp_dir = Path(tempfile.gettempdir())
            staged_path = temp_dir / f"BT-Mouse-Companion-{tag_name}.exe"
            with urllib.request.urlopen(request, timeout=60) as response, staged_path.open("wb") as output:
                while True:
                    if self._update_cancel.is_set():
                        raise _UpdateCancelled()
                    chunk = response.read(256 * 1024)
                    if not chunk:
                        break
                    downloaded += len(chunk)
                    if downloaded > total or downloaded > MAX_COMPANION_UPDATE_BYTES:
                        raise ValueError("The downloaded update is larger than expected.")
                    output.write(chunk)
                    percent = downloaded * 100 // total if total else 0
                    if percent != last_percent:
                        last_percent = percent
                        self._run_on_ui(lambda d=downloaded: self._set_update_progress(tag_name, d, total))

            if downloaded != total:
                raise ValueError("The downloaded update size did not match the release metadata.")
            with staged_path.open("rb") as downloaded_file:
                if downloaded_file.read(2) != b"MZ":
                    raise ValueError("The downloaded file is not a valid Windows executable.")

            executable = Path(sys.executable).resolve()
            script_path = temp_dir / f"btmouse-update-{os.getpid()}.ps1"
            script = build_update_script(
                wait_pids=companion_update_wait_pids(),
                target=executable,
                staged=staged_path,
                replacement=executable.parent / f".BT-Mouse-Companion-{os.getpid()}.new.exe",
                backup=executable.parent / f".BT-Mouse-Companion-{os.getpid()}.backup.exe",
                ready_marker=temp_dir / f"btmouse-update-ready-{os.getpid()}.txt",
                error_log=COMPANION_UPDATE_ERROR_LOG,
                step_log=COMPANION_UPDATE_STEP_LOG,
                expected_version=tag_name.lstrip("v"),
            )
            # BOM so Windows PowerShell 5.1 reads non-ASCII paths (e.g. user names) correctly.
            script_path.write_text(script, encoding="utf-8-sig")
            flags = getattr(subprocess, "CREATE_NO_WINDOW", 0) | getattr(subprocess, "DETACHED_PROCESS", 0)
            subprocess.Popen(
                ["powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", str(script_path)],
                creationflags=flags,
            )
            self._run_on_ui(lambda: self._show_update_installing(tag_name))
        except _UpdateCancelled:
            if staged_path:
                staged_path.unlink(missing_ok=True)
            self._run_on_ui(self._close_update_dialog)
        except Exception as exc:
            self._run_on_ui(
                lambda error=str(exc): (
                    self._close_update_dialog(),
                    QMessageBox.critical(self, "Update Failed", f"Could not install update.\n\n{error}"),
                ),
            )

    def quit_application(self):
        if self.tray_icon:
            try:
                self.tray_icon.hide()
            except Exception:
                pass
        if self.tunnel:
            try:
                self.tunnel.stop()
            except Exception:
                pass
        if self.server:
            self.server.stop()
        QApplication.quit()
        sys.exit(0)


SINGLE_INSTANCE_NAME = "BTMouseCompanion"
_single_instance_mutex = None


def acquire_single_instance_lock():
    """Hold a per-session named mutex for the process lifetime; False if another companion owns it."""
    global _single_instance_mutex
    if not IS_WINDOWS:
        return True
    import ctypes

    kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
    kernel32.CreateMutexW.restype = ctypes.c_void_p
    _single_instance_mutex = kernel32.CreateMutexW(None, False, f"Local\\{SINGLE_INSTANCE_NAME}")
    ERROR_ALREADY_EXISTS = 183
    return not (_single_instance_mutex and ctypes.get_last_error() == ERROR_ALREADY_EXISTS)


def ask_running_instance_to_show():
    from PySide6.QtNetwork import QLocalSocket

    sock = QLocalSocket()
    sock.connectToServer(SINGLE_INSTANCE_NAME)
    if sock.waitForConnected(1000):
        sock.write(b"show")
        sock.waitForBytesWritten(1000)
        sock.disconnectFromServer()


def listen_for_show_requests(window):
    from PySide6.QtNetwork import QLocalServer

    server = QLocalServer(window)
    QLocalServer.removeServer(SINGLE_INSTANCE_NAME)

    def _on_connection():
        conn = server.nextPendingConnection()
        if conn:
            conn.readyRead.connect(lambda: (conn.readAll(), window.show_from_tray()))
            conn.disconnected.connect(conn.deleteLater)

    server.newConnection.connect(_on_connection)
    server.listen(SINGLE_INSTANCE_NAME)
    return server


if __name__ == "__main__":
    set_windows_dpi_awareness()
    if HAS_QT:
        qt_app = QApplication(sys.argv)
        if not acquire_single_instance_lock():
            # Another companion (and its server) is already running: bring it forward instead.
            if "--minimized" not in sys.argv:
                ask_running_instance_to_show()
            sys.exit(0)
        qt_app.setQuitOnLastWindowClosed(False)
        win = CompanionApp()
        instance_server = listen_for_show_requests(win)
        if "--minimized" not in sys.argv:
            win.show()
        sys.exit(qt_app.exec())
    else:
        print("[!] PySide6 is required to launch the desktop GUI. Run: pip install -r requirements.txt")
