"""Devices page for the desktop companion: Lenovo Smart Connect-inspired Cross Control
workspace, spatial monitor & mobile layout, interactive Share Hub, and live mirroring.
"""

import os
import subprocess
import sys
import threading
import time
from pathlib import Path

from PySide6.QtCore import QPoint, QPointF, QRect, QRectF, QSize, Qt, QTimer, Signal
from PySide6.QtGui import (
    QBrush,
    QColor,
    QCursor,
    QFont,
    QIcon,
    QImage,
    QKeySequence,
    QLinearGradient,
    QPainter,
    QPainterPath,
    QPen,
    QPixmap,
)
from PySide6.QtWidgets import (
    QCheckBox,
    QDialog,
    QFileDialog,
    QFrame,
    QHBoxLayout,
    QLabel,
    QListWidget,
    QListWidgetItem,
    QProgressBar,
    QPushButton,
    QScrollArea,
    QSizePolicy,
    QSlider,
    QVBoxLayout,
    QWidget,
)

import video
from ecosystem import Rect

# Lenovo Smart Connect Modern Theme Palette
MONITOR_FRAME = QColor("#1E222B")
MONITOR_SCREEN = QColor("#111319")
MONITOR_EDGE = QColor("#333A48")
MONITOR_STAND = QColor("#2A303C")
DEVICE_CHASSIS = QColor("#181B22")
DEVICE_SCREEN = QColor("#0A0C11")
DEVICE_BEZEL = QColor("#242934")
DEVICE_ONLINE = QColor("#10B981")
DEVICE_ACTIVE = QColor("#00D2FF")
SNAP_GUIDE = QColor("#00D2FF")
TEXT = QColor("#F1F5F9")
TEXT_MUTED = QColor("#8E9AA8")
ACCENT_BLUE = QColor("#3B82F6")


class LayoutCanvas(QWidget):
    """Spatial workspace: Monitors drawn with realistic stands and bezels;
    connected phones and tablets can be dragged around monitor edges with magnetic snap."""

    changed = Signal()
    TRAY_HEIGHT = 90

    def __init__(self, ecosystem, parent=None):
        super().__init__(parent)
        self.eco = ecosystem
        self.setMinimumHeight(420)
        self.setMouseTracking(True)
        self._drag = None
        self._scale = 1.0
        self._origin = QPointF(0, 0)
        self._hover_id = None

    def _devices(self):
        return {d["device_id"]: d for d in self.eco.registry.all_devices()}

    def _bounds(self):
        rects = list(self.eco.layout.monitors) + list(self.eco.layout.placements.values())
        if not rects:
            return Rect(0, 0, 1920, 1080)
        x0 = min(r.x for r in rects)
        y0 = min(r.y for r in rects)
        x1 = max(r.right for r in rects)
        y1 = max(r.bottom for r in rects)
        pad = 0.16 * max(x1 - x0, y1 - y0)
        return Rect(x0 - pad, y0 - pad, x1 - x0 + 2 * pad, y1 - y0 + 2 * pad)

    def _update_transform(self):
        b = self._bounds()
        area_h = max(80, self.height() - self.TRAY_HEIGHT - 12)
        self._scale = min(self.width() / b.w, area_h / b.h)
        ox = (self.width() - b.w * self._scale) / 2 - b.x * self._scale
        oy = (area_h - b.h * self._scale) / 2 - b.y * self._scale
        self._origin = QPointF(ox, oy)

    def _to_screen(self, r):
        return QRectF(self._origin.x() + r.x * self._scale, self._origin.y() + r.y * self._scale,
                      r.w * self._scale, r.h * self._scale)

    def _to_layout(self, p):
        return ((p.x() - self._origin.x()) / self._scale, (p.y() - self._origin.y()) / self._scale)

    def _tray_rect(self):
        return QRectF(12, self.height() - self.TRAY_HEIGHT, self.width() - 24, self.TRAY_HEIGHT - 10)

    def _tray_slots(self, devices):
        unplaced = [d for k, d in devices.items() if k not in self.eco.layout.placements]
        slots = {}
        tray = self._tray_rect()
        x = tray.x() + 18
        for dev in unplaced:
            w, h = self.eco.layout.device_size(dev)
            s = (tray.height() - 34) / max(w, h)
            rect = QRectF(x + 50, tray.y() + 8, w * s, h * s)
            slots[dev["device_id"]] = rect
            x += rect.width() + 120
        return slots

    def paintEvent(self, _event):
        self._update_transform()
        p = QPainter(self)
        p.setRenderHint(QPainter.RenderHint.Antialiasing)

        font = QFont(self.font())
        font.setPointSizeF(max(8.5, font.pointSizeF() - 0.5))
        p.setFont(font)

        devices = self._devices()
        target = self.eco.share.target

        # 1. Draw Monitors
        for i, m in enumerate(self.eco.layout.monitors):
            r = self._to_screen(m)
            self._paint_monitor(p, r, i, target is None)

        # 2. Draw Snap Guides while dragging
        if self._drag:
            self._paint_snap_guides(p, self._drag["rect"])

        # 3. Draw Placed Devices
        for device_id, rect in self.eco.layout.placements.items():
            dev = devices.get(device_id)
            if dev is None:
                continue
            r = self._to_screen(self._drag["rect"]) if self._drag and self._drag["id"] == device_id else self._to_screen(rect)
            self._paint_device(p, r, dev, device_id == target)
            if self._drag is None and self.eco.layout.edge_crossing:
                self._paint_edge_indicator(p, r, rect)

        # 4. Draw Bottom Dock for Unplaced Devices
        tray = self._tray_rect()
        p.setPen(QPen(QColor("#242A36"), 1.5, Qt.PenStyle.DashLine))
        p.setBrush(QBrush(QColor("#101217")))
        p.drawRoundedRect(tray, 12, 12)

        slots = self._tray_slots(devices)
        if not slots:
            p.setPen(TEXT_MUTED)
            p.drawText(tray, Qt.AlignmentFlag.AlignCenter, "⚓ Devices Dock · Connected devices placed here switch via hotkey (Ctrl+Alt+→)")
        else:
            p.setPen(TEXT_MUTED)
            p.drawText(tray.adjusted(14, 0, -14, -6), Qt.AlignmentFlag.AlignRight | Qt.AlignmentFlag.AlignBottom,
                       "Drag device up onto any monitor edge to enable Cross Control cursor crossing.")

        for device_id, r in slots.items():
            if self._drag and self._drag["id"] == device_id:
                r = self._to_screen(self._drag["rect"])
            self._paint_device(p, r, devices[device_id], device_id == target)

        p.end()

    def _paint_monitor(self, p, r, index, active):
        # Stand Neck & Base
        stand_w = max(18.0, r.width() * 0.18)
        stand_h = max(10.0, r.height() * 0.10)
        neck_rect = QRectF(r.center().x() - stand_w * 0.25, r.bottom(), stand_w * 0.5, stand_h)
        base_rect = QRectF(r.center().x() - stand_w * 0.6, r.bottom() + stand_h * 0.6, stand_w * 1.2, stand_h * 0.4)

        p.setPen(Qt.PenStyle.NoPen)
        p.setBrush(QBrush(MONITOR_STAND))
        p.drawRoundedRect(neck_rect, 2, 2)
        p.drawRoundedRect(base_rect, 3, 3)

        # Monitor Outer Frame
        outer_pen = QPen(DEVICE_ACTIVE if active else MONITOR_EDGE, 2.0 if active else 1.5)
        p.setPen(outer_pen)
        p.setBrush(QBrush(MONITOR_FRAME))
        p.drawRoundedRect(r, 8, 8)

        # Inner Screen Glass
        screen_margin = 5.0
        screen_r = r.adjusted(screen_margin, screen_margin, -screen_margin, -screen_margin)
        grad = QLinearGradient(screen_r.topLeft(), screen_r.bottomLeft())
        grad.setColorAt(0.0, QColor("#1A1E27"))
        grad.setColorAt(1.0, MONITOR_SCREEN)
        p.setBrush(QBrush(grad))
        p.setPen(Qt.PenStyle.NoPen)
        p.drawRoundedRect(screen_r, 4, 4)

        # Screen Label
        p.setPen(TEXT if active else TEXT_MUTED)
        active_tag = " [Mouse & Keyboard Here]" if active else ""
        p.drawText(screen_r, Qt.AlignmentFlag.AlignCenter, f"🖥️ Display {index + 1}{active_tag}\n{int(r.width() / self._scale)}×{int(r.height() / self._scale)}")

    def _paint_device(self, p, r, dev, active):
        online = self.eco.registry.is_online(dev["device_id"])
        is_tablet = dev.get("kind") == "tablet"

        # Active / Online chassis border
        glow_pen = QPen(DEVICE_ACTIVE if active else (DEVICE_ONLINE if online else MONITOR_EDGE), 2.5 if active else 1.5)
        p.setPen(glow_pen)
        p.setBrush(QBrush(DEVICE_CHASSIS))
        corner_rad = 12 if not is_tablet else 8
        p.drawRoundedRect(r, corner_rad, corner_rad)

        # Screen Bezel & Glass
        bezel_m = 3.5
        scr_r = r.adjusted(bezel_m, bezel_m, -bezel_m, -bezel_m)
        p.setPen(Qt.PenStyle.NoPen)
        p.setBrush(QBrush(DEVICE_SCREEN))
        p.drawRoundedRect(scr_r, corner_rad - 2, corner_rad - 2)

        # Camera Notch / Hole Punch
        notch_w = min(18.0, r.width() * 0.25)
        p.setBrush(QBrush(QColor("#000000")))
        p.drawEllipse(QPointF(r.center().x(), scr_r.top() + 4), 2.5, 2.5)

        # Device Icon & Name
        icon = "📟" if is_tablet else "📱"
        label = dev.get("name", "Device")
        if not dev.get("caps", {}).get("control"):
            status_text = "control disabled"
        elif active:
            status_text = "● Active"
        elif online:
            status_text = "● Online"
        else:
            status_text = "offline"

        box = QRectF(r.center().x() - 75, r.bottom() + 4, 150, 36)
        p.setPen(TEXT if active else (TEXT if online else TEXT_MUTED))
        p.drawText(box, Qt.AlignmentFlag.AlignHCenter | Qt.AlignmentFlag.AlignTop | Qt.TextFlag.TextWordWrap,
                   f"{icon} {label}\n{status_text}")

    def _paint_snap_guides(self, p, drag_rect):
        """Draw subtle cyan snap guide lines when dragging near a monitor edge."""
        for m in self.eco.layout.monitors:
            # Check edge proximity
            near_left = abs(drag_rect.right - m.x) < 40
            near_right = abs(drag_rect.x - m.right) < 40
            near_top = abs(drag_rect.bottom - m.y) < 40
            near_bottom = abs(drag_rect.y - m.bottom) < 40

            if near_left or near_right or near_top or near_bottom:
                p.setPen(QPen(SNAP_GUIDE, 1.5, Qt.PenStyle.DashLine))
                r = self._to_screen(m)
                if near_right:
                    p.drawLine(QPointF(r.right(), r.top() - 20), QPointF(r.right(), r.bottom() + 20))
                elif near_left:
                    p.drawLine(QPointF(r.left(), r.top() - 20), QPointF(r.left(), r.bottom() + 20))
                elif near_top:
                    p.drawLine(QPointF(r.left() - 20, r.top()), QPointF(r.right() + 20, r.top()))
                elif near_bottom:
                    p.drawLine(QPointF(r.left() - 20, r.bottom()), QPointF(r.right() + 20, r.bottom()))

    def _paint_edge_indicator(self, p, screen_r, layout_r):
        """Draw subtle transition indicators where cursor crosses edge."""
        for m in self.eco.layout.monitors:
            if abs(layout_r.x - m.right) < 2:  # Right of monitor
                cy = screen_r.center().y()
                p.setPen(QPen(DEVICE_ACTIVE, 2))
                p.drawLine(QPointF(screen_r.left() - 6, cy), QPointF(screen_r.left(), cy))
            elif abs(layout_r.right - m.x) < 2:  # Left of monitor
                cy = screen_r.center().y()
                p.setPen(QPen(DEVICE_ACTIVE, 2))
                p.drawLine(QPointF(screen_r.right(), cy), QPointF(screen_r.right() + 6, cy))

    # --- Mouse Interaction ---
    def mousePressEvent(self, event):
        pos = event.position()
        devices = self._devices()
        for device_id, rect in list(self.eco.layout.placements.items()):
            if device_id in devices and self._to_screen(rect).contains(pos):
                lx, ly = self._to_layout(pos)
                self._drag = {"id": device_id, "rect": Rect(rect.x, rect.y, rect.w, rect.h),
                              "dx": lx - rect.x, "dy": ly - rect.y}
                return
        for device_id, r in self._tray_slots(devices).items():
            if r.contains(pos):
                w, h = self.eco.layout.device_size(devices[device_id])
                lx, ly = self._to_layout(pos)
                self._drag = {"id": device_id, "rect": Rect(lx - w / 2, ly - h / 2, w, h), "dx": w / 2, "dy": h / 2}
                return

    def mouseMoveEvent(self, event):
        if not self._drag:
            return
        lx, ly = self._to_layout(event.position())
        r = self._drag["rect"]
        self._drag["rect"] = Rect(lx - self._drag["dx"], ly - self._drag["dy"], r.w, r.h)
        self.update()

    def mouseReleaseEvent(self, event):
        if not self._drag:
            return
        device_id = self._drag["id"]
        layout = self.eco.layout
        if self._tray_rect().contains(event.position()):
            layout.placements.pop(device_id, None)
        else:
            snapped = layout.snap(device_id, self._drag["rect"])
            touching = snapped is not self._drag["rect"]
            if touching:
                layout.placements[device_id] = snapped
            elif device_id not in layout.placements:
                layout.placements[device_id] = layout.default_placement(self._devices()[device_id])
        self._drag = None
        layout.save()
        self.update()
        self.changed.emit()


class TargetPill(QWidget):
    """Sleek floating on-top notification pill while the keyboard and mouse are on another device."""

    def __init__(self):
        super().__init__(None, Qt.WindowType.FramelessWindowHint | Qt.WindowType.WindowStaysOnTopHint |
                         Qt.WindowType.Tool | Qt.WindowType.WindowTransparentForInput)
        self.setAttribute(Qt.WidgetAttribute.WA_TranslucentBackground)
        self.setAttribute(Qt.WidgetAttribute.WA_ShowWithoutActivating)
        lay = QHBoxLayout(self)
        lay.setContentsMargins(0, 0, 0, 0)
        self.label = QLabel()
        self.label.setStyleSheet(
            "background-color: #11141B; color: #FFFFFF; border: 1.5px solid #00D2FF; "
            "border-radius: 16px; padding: 7px 18px; font-size: 13px; font-weight: 500;"
        )
        lay.addWidget(self.label)

    def show_for(self, name):
        from PySide6.QtGui import QGuiApplication
        self.label.setText(f"⌨️ Cross Control: {name}   ·   Press Ctrl+Alt+Home to return to this PC")
        self.adjustSize()
        screen = QGuiApplication.primaryScreen().geometry()
        self.move(screen.x() + (screen.width() - self.width()) // 2, screen.y() + 14)
        self.show()


class StreamWindow(QWidget):
    """Watch a phone or tablet stream in a modern window with floating controls."""

    frame_ready = Signal(bytes, int, int)
    picture_ready = Signal(bytes, int, int, int, int)

    def __init__(self, ecosystem, stream):
        super().__init__(None, Qt.WindowType.Window)
        self.eco = ecosystem
        self.stream = stream
        self.owner = stream["owner"]
        self.setWindowTitle(f"{stream['owner_name']} · {stream['label']} · Cross Control")
        self.resize(440, 840)
        self.setStyleSheet("background-color: #0A0C10; color: #E8E8E8;")

        main_lay = QVBoxLayout(self)
        main_lay.setContentsMargins(0, 0, 0, 0)
        main_lay.setSpacing(0)

        # Video / Picture View
        self.view = QLabel("Waiting for live stream from device…")
        self.view.setAlignment(Qt.AlignmentFlag.AlignCenter)
        self.view.setMinimumSize(220, 220)
        self.view.setStyleSheet("background-color: #000000;")
        main_lay.addWidget(self.view, 1)

        self.can_control = stream["kind"] == "screen" and bool(
            (ecosystem.registry.get(self.owner) or {}).get("caps", {}).get("control")
        )

        # Floating Bottom Navigation Dock
        nav_card = QFrame()
        nav_card.setStyleSheet(
            "QFrame { background-color: #141720; border-top: 1px solid #252A36; padding: 6px; }"
        )
        nav_lay = QHBoxLayout(nav_card)
        nav_lay.setContentsMargins(12, 6, 12, 8)
        nav_lay.setSpacing(8)

        info = QLabel("Tap/swipe to control" if self.can_control else "Viewing stream")
        info.setStyleSheet("color: #8E9AA8; font-size: 12px; font-weight: 500;")
        nav_lay.addWidget(info, 1)

        self._muted = False
        btn_mute = QPushButton("🔊 Mute")
        btn_mute.setStyleSheet("QPushButton { background-color: #202532; border: 1px solid #333A48; "
                               "border-radius: 6px; padding: 4px 10px; font-size: 12px; }")

        def toggle_mute():
            self._muted = not self._muted
            btn_mute.setText("🔇 Unmute" if self._muted else "🔊 Mute")

        btn_mute.clicked.connect(toggle_mute)
        nav_lay.addWidget(btn_mute)

        if self.can_control:
            for label, action in (("◀ Back", "back"), ("● Home", "home"), ("■ Apps", "recents")):
                b = QPushButton(label)
                b.setStyleSheet("QPushButton { background-color: #202532; border: 1px solid #333A48; "
                                "border-radius: 6px; padding: 4px 10px; font-size: 12px; font-weight: 600; } "
                                "QPushButton:hover { background-color: #3182CE; color: white; }")
                b.clicked.connect(lambda _c=False, a=action: self._send({"ev": "global", "action": a}))
                nav_lay.addWidget(b)

        main_lay.addWidget(nav_card)

        self._pixmap = None
        self._img_size = (0, 0)
        self._dev_size = (0, 0)
        self.frame_ready.connect(self._show_frame)
        self.picture_ready.connect(self._show_picture)
        self._key = f"window-{id(self)}"
        ecosystem.streams.watch_local(stream["id"], self._key, self._on_payload)
        self._start_video()
        if self.can_control:
            self.eco.registry.send(self.owner, {"type": "control_state", "active": True,
                                                "controller": self.eco.share.pc_name})

    def _start_video(self):
        self._video = None
        self._assembler = None
        self._video_lock = threading.Lock()
        self._last_video = 0.0
        self._video_on = False
        if self.stream["kind"] != "screen" or not video.available():
            return
        try:
            self._video = video.H264Decoder(self.picture_ready.emit, on_error=self._video_failed,
                                            on_need_key=self._ask_key)
        except Exception:
            return
        self._assembler = video.H264Assembler(self._video.feed, self._ask_key)
        self._video_started = time.monotonic()
        self.eco.streams.request_h264(self.stream["id"], True)
        self._video_timer = QTimer(self)
        self._video_timer.timeout.connect(self._video_tick)
        self._video_timer.start(4000)

    def _ask_key(self):
        self.eco.streams.request_keyframe(self.stream["id"])

    def _video_tick(self):
        if self._video is None:
            return
        now = time.monotonic()
        self.eco.streams.request_h264(self.stream["id"], True)
        if self._video_on and now - self._last_video > 3.0:
            self._video_on = False
            self.eco.streams.set_local_jpeg(self.stream["id"], self._key, True)
        elif not self._video_on and self._last_video == 0.0 and now - self._video_started > 30.0:
            self._stop_video()

    def _video_failed(self, _exc):
        QTimer.singleShot(0, self._stop_video)

    def _stop_video(self):
        if self._video is None:
            return
        self._video.close()
        self._video = None
        self._assembler = None
        self._video_on = False
        self.eco.streams.request_h264(self.stream["id"], False)
        self.eco.streams.set_local_jpeg(self.stream["id"], self._key, True)
        timer = getattr(self, "_video_timer", None)
        if timer:
            timer.stop()

    def _show_picture(self, rgb, w, h, dev_w, dev_h):
        image = QImage(rgb, w, h, 3 * w, QImage.Format.Format_RGB888)
        pix = QPixmap.fromImage(image.copy())
        if pix.isNull():
            return
        self._last_video = time.monotonic()
        if not self._video_on:
            self._video_on = True
            self.eco.streams.set_local_jpeg(self.stream["id"], self._key, False)
        self._pixmap = pix
        self._img_size = (w, h)
        dev = self.eco.registry.get(self.owner) or {}
        self._dev_size = (dev_w or dev.get("screen_w") or w, dev_h or dev.get("screen_h") or h)
        self._render()

    def _on_payload(self, payload):
        import base64
        if payload.get("type") == "stream_audio":
            self._play_audio(payload.get("pcm"))
            return
        if payload.get("type") == "stream_h264":
            assembler = self._assembler
            if assembler is not None:
                with self._video_lock:
                    assembler.accept(payload)
            return
        if self._video_on and time.monotonic() - self._last_video < 1.5:
            return
        try:
            data = base64.b64decode(payload.get("frame") or "")
        except Exception:
            return
        self.frame_ready.emit(data, int(payload.get("width") or 0), int(payload.get("height") or 0))

    def _play_audio(self, pcm_b64):
        import base64
        if getattr(self, "_muted", False) or not pcm_b64:
            return
        if getattr(self, "_audio_queue", None) is None:
            import queue
            self._audio_queue = queue.Queue(maxsize=25)
            threading.Thread(target=self._audio_loop, name="MirrorAudio", daemon=True).start()
        try:
            self._audio_queue.put_nowait(base64.b64decode(pcm_b64))
        except Exception:
            pass

    def _audio_loop(self):
        try:
            import sounddevice
            out = sounddevice.RawOutputStream(samplerate=16000, channels=1, dtype="int16", latency="low")
            out.start()
        except Exception:
            return
        self._audio_out = out
        while not getattr(self, "_closed", False):
            try:
                pcm = self._audio_queue.get(timeout=1.0)
            except Exception:
                continue
            try:
                out.write(pcm)
            except Exception:
                break

    def _show_frame(self, data, w, h):
        pix = QPixmap()
        if not pix.loadFromData(data, "JPG"):
            return
        self._pixmap = pix
        self._img_size = (pix.width(), pix.height())
        dev = self.eco.registry.get(self.owner) or {}
        self._dev_size = (dev.get("screen_w") or w or pix.width(), dev.get("screen_h") or h or pix.height())
        self._render()

    def _render(self):
        if self._pixmap is not None:
            self.view.setPixmap(self._pixmap.scaled(self.view.size(), Qt.AspectRatioMode.KeepAspectRatio,
                                                    Qt.TransformationMode.SmoothTransformation))

    def resizeEvent(self, event):
        super().resizeEvent(event)
        self._render()

    def _device_point(self, pos):
        pm = self.view.pixmap()
        if pm is None or pm.isNull() or not self._dev_size[0]:
            return None
        vx = (self.view.width() - pm.width()) / 2
        vy = (self.view.height() - pm.height()) / 2
        p = self.view.mapFrom(self, pos.toPoint())
        fx = (p.x() - vx) / max(1, pm.width())
        fy = (p.y() - vy) / max(1, pm.height())
        if not (0 <= fx <= 1 and 0 <= fy <= 1):
            return None
        return int(fx * self._dev_size[0]), int(fy * self._dev_size[1])

    def _send(self, fields, point=None):
        payload = {"type": "remote_input"}
        payload.update(fields)
        if point:
            payload["x"], payload["y"] = point
        self.eco.registry.send(self.owner, payload)

    def mousePressEvent(self, event):
        if not self.can_control:
            return
        pt = self._device_point(event.position())
        if pt is None:
            return
        button = {Qt.MouseButton.LeftButton: "left", Qt.MouseButton.RightButton: "right",
                  Qt.MouseButton.MiddleButton: "middle"}.get(event.button())
        if button:
            self._send({"ev": "move"}, pt)
            self._send({"ev": "button", "button": button, "down": True}, pt)

    def mouseMoveEvent(self, event):
        if not self.can_control:
            return
        pt = self._device_point(event.position())
        if pt is not None:
            self._send({"ev": "move"}, pt)

    def mouseReleaseEvent(self, event):
        if not self.can_control:
            return
        pt = self._device_point(event.position())
        button = {Qt.MouseButton.LeftButton: "left", Qt.MouseButton.RightButton: "right",
                  Qt.MouseButton.MiddleButton: "middle"}.get(event.button())
        if button:
            self._send({"ev": "button", "button": button, "down": False}, pt)

    def wheelEvent(self, event):
        if not self.can_control:
            return
        pt = self._device_point(event.position())
        if pt is not None:
            self._send({"ev": "wheel", "dy": event.angleDelta().y() / 120.0, "dx": event.angleDelta().x() / 120.0}, pt)

    def closeEvent(self, event):
        self._stop_video()
        self.eco.streams.unwatch_local(self.stream["id"], self._key)
        self._closed = True
        out = getattr(self, "_audio_out", None)
        if out:
            try:
                out.stop()
                out.close()
            except Exception:
                pass
        if self.can_control:
            self.eco.registry.send(self.owner, {"type": "control_state", "active": False,
                                                "controller": self.eco.share.pc_name})
        super().closeEvent(event)


class DropZoneWidget(QFrame):
    """Lenovo Smart Connect-style visual drag-and-drop file transfer drop target."""

    files_dropped = Signal(list)

    def __init__(self, parent=None):
        super().__init__(parent)
        self.setAcceptDrops(True)
        self.setMinimumHeight(120)
        self.setCursor(QCursor(Qt.CursorShape.PointingHandCursor))
        self._drag_over = False
        self._update_style()

        lay = QVBoxLayout(self)
        lay.setContentsMargins(20, 16, 20, 16)
        lay.setSpacing(6)
        lay.setAlignment(Qt.AlignmentFlag.AlignCenter)

        self.lbl_icon = QLabel("📤")
        self.lbl_icon.setStyleSheet("font-size: 28px;")
        self.lbl_icon.setAlignment(Qt.AlignmentFlag.AlignCenter)
        lay.addWidget(self.lbl_icon)

        self.lbl_main = QLabel("Drag & drop files here to send instantly")
        self.lbl_main.setStyleSheet("font-size: 14px; font-weight: 600; color: #FFFFFF;")
        self.lbl_main.setAlignment(Qt.AlignmentFlag.AlignCenter)
        lay.addWidget(self.lbl_main)

        self.lbl_sub = QLabel("or click Browse files below to transfer across your devices")
        self.lbl_sub.setStyleSheet("font-size: 12px; color: #8E9AA8;")
        self.lbl_sub.setAlignment(Qt.AlignmentFlag.AlignCenter)
        lay.addWidget(self.lbl_sub)

    def _update_style(self):
        if self._drag_over:
            self.setStyleSheet("""
                QFrame {
                    background-color: rgba(0, 210, 255, 0.12);
                    border: 2px dashed #00D2FF;
                    border-radius: 12px;
                }
            """)
        else:
            self.setStyleSheet("""
                QFrame {
                    background-color: #12151D;
                    border: 1.5px dashed #2E3544;
                    border-radius: 12px;
                }
                QFrame:hover {
                    border-color: #3B82F6;
                    background-color: #151922;
                }
            """)

    def dragEnterEvent(self, event):
        if event.mimeData().hasUrls():
            self._drag_over = True
            self._update_style()
            self.lbl_main.setText("Release files to transfer to selected device!")
            event.acceptProposedAction()

    def dragLeaveEvent(self, event):
        self._drag_over = False
        self._update_style()
        self.lbl_main.setText("Drag & drop files here to send instantly")

    def dropEvent(self, event):
        self._drag_over = False
        self._update_style()
        self.lbl_main.setText("Drag & drop files here to send instantly")
        paths = [u.toLocalFile() for u in event.mimeData().urls() if u.isLocalFile()]
        if paths:
            self.files_dropped.emit(paths)
            event.acceptProposedAction()


class TransferItemWidget(QFrame):
    """Rich card for a single file transfer in the Share Hub feed."""

    def __init__(self, info, ecosystem, parent=None):
        super().__init__(parent)
        self.info = info
        self.eco = ecosystem
        self.setStyleSheet("""
            QFrame {
                background-color: #141720;
                border: 1px solid #232834;
                border-radius: 10px;
                padding: 4px;
            }
        """)

        lay = QVBoxLayout(self)
        lay.setContentsMargins(12, 10, 12, 10)
        lay.setSpacing(6)

        # Header: Direction, Filename, Peer
        top = QHBoxLayout()
        top.setSpacing(8)

        self.pill_dir = QLabel()
        top.addWidget(self.pill_dir)

        self.lbl_name = QLabel(info.get("name", "File"))
        self.lbl_name.setStyleSheet("font-weight: 600; color: #FFFFFF; font-size: 13px;")
        top.addWidget(self.lbl_name, 1)

        self.lbl_peer = QLabel()
        self.lbl_peer.setStyleSheet("color: #8E9AA8; font-size: 12px;")
        top.addWidget(self.lbl_peer)

        self.btn_action = QPushButton()
        self.btn_action.setCursor(QCursor(Qt.CursorShape.PointingHandCursor))
        self.btn_action.setStyleSheet("QPushButton { background-color: #242A38; border: 1px solid #363E50; "
                                      "border-radius: 6px; padding: 4px 10px; font-size: 11px; font-weight: 600; color: #E2E8F0; } "
                                      "QPushButton:hover { background-color: #3182CE; color: white; }")
        top.addWidget(self.btn_action)
        lay.addLayout(top)

        # Progress bar
        self.pbar = QProgressBar()
        self.pbar.setFixedHeight(6)
        self.pbar.setTextVisible(False)
        self.pbar.setStyleSheet("""
            QProgressBar {
                background-color: #1F2432;
                border-radius: 3px;
                border: none;
            }
            QProgressBar::chunk {
                background-color: qlineargradient(x1:0, y1:0, x2:1, y2:0, stop:0 #3B82F6, stop:1 #00D2FF);
                border-radius: 3px;
            }
        """)
        lay.addWidget(self.pbar)

        # Bottom details: Speed, percentage, size
        self.lbl_detail = QLabel()
        self.lbl_detail.setStyleSheet("color: #718096; font-size: 11px;")
        lay.addWidget(self.lbl_detail)

        self.update_info(info)

    def update_info(self, info):
        self.info = info
        is_out = info.get("direction") == "out"
        peer_name = (self.eco.registry.get(info.get("peer")) or {}).get("name", "Device")

        if is_out:
            self.pill_dir.setText("↑ SENDING")
            self.pill_dir.setStyleSheet("background-color: #1E3A8A; color: #93C5FD; border-radius: 4px; padding: 2px 6px; font-size: 10px; font-weight: 700;")
            self.lbl_peer.setText(f"To: {peer_name}")
        else:
            self.pill_dir.setText("↓ RECEIVING")
            self.pill_dir.setStyleSheet("background-color: #064E3B; color: #6EE7B7; border-radius: 4px; padding: 2px 6px; font-size: 10px; font-weight: 700;")
            self.lbl_peer.setText(f"From: {peer_name}")

        state = info.get("state", "waiting")
        prog = int(info.get("progress", 0.0) * 100)
        self.pbar.setValue(prog)

        if state == "done":
            self.pill_dir.setText("✓ COMPLETED")
            self.pill_dir.setStyleSheet("background-color: #065F46; color: #A7F3D0; border-radius: 4px; padding: 2px 6px; font-size: 10px; font-weight: 700;")
            self.btn_action.setText("Open File")
            self.btn_action.clicked.disconnect() if self.btn_action.receivers(self.btn_action.clicked) else None
            path = info.get("path")
            if path:
                self.btn_action.clicked.connect(lambda: self._open_file(path))
            self.lbl_detail.setText("Transfer complete.")
        elif state == "failed":
            self.pill_dir.setText("✕ FAILED")
            self.pill_dir.setStyleSheet("background-color: #7F1D1D; color: #FCA5A5; border-radius: 4px; padding: 2px 6px; font-size: 10px; font-weight: 700;")
            self.btn_action.setText("Dismiss")
            self.btn_action.clicked.disconnect() if self.btn_action.receivers(self.btn_action.clicked) else None
            self.btn_action.clicked.connect(self.deleteLater)
            self.lbl_detail.setText(info.get("error") or "Transfer failed.")
        else:
            speed_mb = info.get("speed", 0) / 1e6
            speed_str = f" · {speed_mb:.1f} MB/s" if speed_mb > 0.05 else ""
            self.btn_action.setText("Cancel")
            self.btn_action.clicked.disconnect() if self.btn_action.receivers(self.btn_action.clicked) else None
            self.btn_action.clicked.connect(lambda: self.eco.sync.cancel_transfer(info["id"]) if hasattr(self.eco.sync, "cancel_transfer") else None)
            self.lbl_detail.setText(f"{prog}%{speed_str}")

    def _open_file(self, path):
        if os.name == "nt":
            os.startfile(str(path))  # noqa: S606
        else:
            subprocess.Popen(["xdg-open", str(path)])


class SendDialog(QDialog):
    """Refined Lenovo Smart Connect-style dialog for sending files with visual target cards."""

    def __init__(self, ecosystem, paths, parent=None):
        super().__init__(parent)
        self.setWindowTitle("Share Hub · Send with Lanyard")
        self.setMinimumWidth(440)
        self.setStyleSheet("background-color: #12141A; color: #E8E8E8; border-radius: 10px;")

        lay = QVBoxLayout(self)
        lay.setContentsMargins(20, 18, 20, 18)
        lay.setSpacing(12)

        title = QLabel("Select Recipient Device")
        title.setStyleSheet("font-size: 16px; font-weight: bold; color: #FFFFFF;")
        lay.addWidget(title)

        count = len(paths)
        summary = f"{Path(paths[0]).name}" + (f" and {count - 1} other files" if count > 1 else "")
        lbl_files = QLabel(f"Sending: {summary}")
        lbl_files.setStyleSheet("color: #8E9AA8; font-size: 13px;")
        lbl_files.setWordWrap(True)
        lay.addWidget(lbl_files)

        online = [d for d in ecosystem.registry.online_devices() if d.get("caps", {}).get("files", True)]
        if not online:
            no_dev = QLabel("No phone or tablet is connected right now.\nConnect via Wi-Fi or Bluetooth first.")
            no_dev.setStyleSheet("color: #F87171; padding: 14px; background-color: #1F1515; border-radius: 8px;")
            lay.addWidget(no_dev)
        else:
            for dev in online:
                btn = QPushButton(f"📱 {dev.get('name', 'Device')}")
                btn.setCursor(QCursor(Qt.CursorShape.PointingHandCursor))
                btn.setStyleSheet("""
                    QPushButton {
                        background-color: #1E232F;
                        color: #FFFFFF;
                        border: 1px solid #313848;
                        border-radius: 8px;
                        padding: 10px 16px;
                        font-size: 13px;
                        font-weight: 600;
                        text-align: left;
                    }
                    QPushButton:hover {
                        background-color: #2B6CB0;
                        border-color: #4299E1;
                    }
                """)
                btn.clicked.connect(lambda _c=False, d=dev: self._send(ecosystem, d, paths))
                lay.addWidget(btn)

        cancel = QPushButton("Cancel")
        cancel.setCursor(QCursor(Qt.CursorShape.PointingHandCursor))
        cancel.setStyleSheet("QPushButton { background-color: transparent; color: #718096; border: none; padding: 8px; } "
                             "QPushButton:hover { color: #CBD5E0; }")
        cancel.clicked.connect(self.reject)
        lay.addWidget(cancel)

    def _send(self, ecosystem, dev, paths):
        for p in paths:
            if os.path.isfile(p):
                ecosystem.sync.send_file(dev["device_id"], p)
        self.accept()


def install_send_to_shortcut():
    """Adds Lanyard to Explorer's right-click › Send to menu."""
    if os.name != "nt":
        return False, "Only on Windows"
    send_to = Path(os.environ.get("APPDATA", "")) / "Microsoft" / "Windows" / "SendTo" / "Lanyard.lnk"
    if getattr(sys, "frozen", False):
        target, args = str(Path(sys.executable).resolve()), "--send"
    else:
        pythonw = Path(sys.executable).with_name("pythonw.exe")
        target = str(pythonw if pythonw.is_file() else Path(sys.executable))
        args = f'"{Path(__file__).with_name("app_gui.py").resolve()}" --send'
    ps = (
        "$s=(New-Object -ComObject WScript.Shell).CreateShortcut($env:LNK);"
        "$s.TargetPath=$env:TGT;$s.Arguments=$env:ARGS;$s.Save()"
    )
    env = dict(os.environ, LNK=str(send_to), TGT=target, ARGS=args)
    try:
        subprocess.run(["powershell", "-NoProfile", "-Command", ps], env=env, check=True,
                       creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0), timeout=20)
        return True, str(send_to)
    except Exception as exc:
        return False, str(exc)


def build_devices_page(app):
    """Builds the Lenovo Smart Connect-inspired Cross Control & Share Hub page."""
    page, layout = app._page(
        "Devices & Cross Control",
        "Seamlessly share your mouse and keyboard across devices, drop files to transfer, and mirror screens.",
        max_width=1040
    )

    # 1. Master Cross Control Status Banner
    status_card = QFrame()
    status_card.setObjectName("Card")
    sl = QVBoxLayout(status_card)
    sl.setContentsMargins(18, 16, 18, 16)
    sl.setSpacing(12)

    top_status = QHBoxLayout()
    top_status.setSpacing(12)

    app.lbl_share_target = QLabel("⌨️ Cross Control Active: Mouse and keyboard on This PC")
    app.lbl_share_target.setStyleSheet("font-size: 15px; font-weight: 600; color: #FFFFFF;")
    top_status.addWidget(app.lbl_share_target, 1)

    app.target_switcher_layout = QHBoxLayout()
    app.target_switcher_layout.setSpacing(6)
    top_status.addLayout(app.target_switcher_layout)

    sl.addLayout(top_status)

    # Shortcut Hints
    hint_row = QHBoxLayout()
    hint_row.setSpacing(8)
    for k_text in ("Ctrl + Alt + → Next Device", "Ctrl + Alt + ← Previous", "Ctrl + Alt + Home Return to PC"):
        pill = QLabel(k_text)
        pill.setStyleSheet("background-color: #1E232F; color: #94A3B8; border: 1px solid #2B3342; "
                           "border-radius: 6px; padding: 4px 10px; font-size: 11px; font-weight: 500;")
        hint_row.addWidget(pill)
    hint_row.addStretch()
    sl.addLayout(hint_row)

    layout.addWidget(status_card)

    # 2. Spatial Layout Canvas
    layout.addWidget(app._section_label("Spatial Layout & Display Arrangement"))
    canvas_card = QFrame()
    canvas_card.setObjectName("Card")
    cl = QVBoxLayout(canvas_card)
    cl.setContentsMargins(12, 12, 12, 12)
    cl.setSpacing(10)

    app.layout_canvas = LayoutCanvas(app.server.ecosystem)
    cl.addWidget(app.layout_canvas)

    opts = QHBoxLayout()
    app.chk_edge = QCheckBox("Cross over seamlessly when mouse reaches screen edge")
    app.chk_edge.setChecked(app.server.ecosystem.layout.edge_crossing)
    app.chk_edge.setStyleSheet("font-weight: 500;")
    opts.addWidget(app.chk_edge)

    opts.addStretch()
    opts.addWidget(QLabel("Pointer Speed:"))
    app.sld_speed = QSlider(Qt.Orientation.Horizontal)
    app.sld_speed.setRange(5, 40)
    app.sld_speed.setValue(int(app.server.ecosystem.layout.speed * 10))
    app.sld_speed.setFixedWidth(160)
    opts.addWidget(app.sld_speed)

    app.lbl_speed_val = QLabel(f"{app.server.ecosystem.layout.speed:.1f}×")
    app.lbl_speed_val.setStyleSheet("color: #00D2FF; font-weight: 600; min-width: 35px;")
    opts.addWidget(app.lbl_speed_val)

    cl.addLayout(opts)
    layout.addWidget(canvas_card)

    # 3. Share Hub (Interactive Drop Zone & Recent Transfers)
    layout.addWidget(app._section_label("Share Hub · Cross-Device File Transfer"))
    share_card = QFrame()
    share_card.setObjectName("Card")
    fl = QVBoxLayout(share_card)
    fl.setContentsMargins(18, 16, 18, 16)
    fl.setSpacing(12)

    # Drop zone
    app.drop_zone = DropZoneWidget()
    fl.addWidget(app.drop_zone)

    # Recipient Device Chips row
    recip_row = QHBoxLayout()
    recip_row.setSpacing(8)
    recip_row.addWidget(QLabel("Transfer target:"))

    app.device_chips_layout = QHBoxLayout()
    app.device_chips_layout.setSpacing(6)
    recip_row.addLayout(app.device_chips_layout)
    recip_row.addStretch()

    # Clipboard sync toggle
    app.chk_clip = QCheckBox("Smart Clipboard Sync")
    app.chk_clip.setChecked(True)
    app.chk_clip.setToolTip("Automatically sync copied text and images between this PC and your mobile devices")
    recip_row.addWidget(app.chk_clip)

    fl.addLayout(recip_row)

    # Share action buttons
    row = QHBoxLayout()
    row.setSpacing(8)
    btn_send = QPushButton("📁 Browse Files…")
    btn_send.setCursor(QCursor(Qt.CursorShape.PointingHandCursor))
    btn_send.setStyleSheet("QPushButton { background-color: #3182CE; color: white; border: none; border-radius: 6px; padding: 7px 14px; font-weight: 600; }")

    btn_send_clip = QPushButton("📋 Send Clipboard Now")
    btn_send_clip.setCursor(QCursor(Qt.CursorShape.PointingHandCursor))

    btn_open = QPushButton("📥 Open Received Folder")
    btn_open.setCursor(QCursor(Qt.CursorShape.PointingHandCursor))

    btn_sendto = QPushButton("➕ Explorer 'Send to' Menu")
    btn_sendto.setCursor(QCursor(Qt.CursorShape.PointingHandCursor))

    for b in (btn_send, btn_send_clip, btn_open, btn_sendto):
        row.addWidget(b)
    row.addStretch()
    fl.addLayout(row)

    # Transfers list container
    fl.addWidget(QLabel("Active & Recent Transfers:"))
    app.transfers_scroll = QScrollArea()
    app.transfers_scroll.setMinimumHeight(130)
    app.transfers_scroll.setMaximumHeight(260)
    app.transfers_scroll.setWidgetResizable(True)
    app.transfers_scroll.setStyleSheet("QScrollArea { border: 1px solid #232834; background: #0E1015; border-radius: 8px; }")

    app.transfers_container = QWidget()
    app.transfers_container.setStyleSheet("background: transparent;")
    app.transfers_layout = QVBoxLayout(app.transfers_container)
    app.transfers_layout.setContentsMargins(8, 8, 8, 8)
    app.transfers_layout.setSpacing(8)
    app.transfers_layout.addStretch()
    app.transfers_scroll.setWidget(app.transfers_container)
    fl.addWidget(app.transfers_scroll)

    layout.addWidget(share_card)

    # 4. Live Device Streams & Mirroring
    layout.addWidget(app._section_label("Screen Mirroring & Mobile Camera"))
    live_card = QFrame()
    live_card.setObjectName("Card")
    ll = QVBoxLayout(live_card)
    ll.setContentsMargins(18, 16, 18, 16)
    ll.setSpacing(12)

    app.list_streams = QListWidget()
    app.list_streams.setMinimumHeight(85)
    app.list_streams.setStyleSheet("QListWidget { background-color: #12141A; border: 1px solid #252A36; border-radius: 8px; }")
    ll.addWidget(app.list_streams)

    live_row = QHBoxLayout()
    live_row.setSpacing(8)
    btn_watch = QPushButton("👁️ Watch Stream")
    btn_watch.setStyleSheet("QPushButton { background-color: #3182CE; color: white; border-radius: 6px; padding: 6px 14px; font-weight: 600; }")
    live_row.addWidget(btn_watch)

    btn_mirror = QPushButton("📱 Mirror Phone Screen…")
    btn_mirror.setStyleSheet("QPushButton { background-color: #1E232F; border: 1px solid #333A48; border-radius: 6px; padding: 6px 14px; font-weight: 500; }")
    live_row.addWidget(btn_mirror)

    btn_lock = QPushButton("🔒 Lock Phone")
    btn_lock.setStyleSheet("QPushButton { background-color: #1E232F; border: 1px solid #333A48; border-radius: 6px; padding: 6px 14px; font-weight: 500; }")
    live_row.addWidget(btn_lock)

    live_note = QLabel("Phone screen can be controlled directly with this mouse & keyboard.")
    live_note.setStyleSheet("color: #8E9AA8; font-size: 12px;")
    live_row.addWidget(live_note, 1)
    ll.addLayout(live_row)

    layout.addWidget(live_card)
    layout.addStretch()

    # --- Behaviors & Wiring ---
    def eco():
        return app.server.ecosystem

    # Target Switcher Pills
    app._selected_recipient_id = None

    def refresh_device_chips():
        # Clear recipient chips
        while app.device_chips_layout.count():
            item = app.device_chips_layout.takeAt(0)
            if item.widget():
                item.widget().deleteLater()

        # Clear target switcher pills
        while app.target_switcher_layout.count():
            item = app.target_switcher_layout.takeAt(0)
            if item.widget():
                item.widget().deleteLater()

        online = [d for d in eco().registry.online_devices()]

        # Target Switcher: This PC pill
        btn_pc = QPushButton("💻 This PC")
        btn_pc.setCursor(QCursor(Qt.CursorShape.PointingHandCursor))
        is_pc_target = eco().share.target is None
        btn_pc.setStyleSheet(
            f"QPushButton {{ background-color: {'#2B6CB0' if is_pc_target else '#1E232F'}; "
            f"color: {'#FFFFFF' if is_pc_target else '#A0AEC0'}; border: 1px solid {'#4299E1' if is_pc_target else '#2D3748'}; "
            "border-radius: 14px; padding: 4px 12px; font-size: 12px; font-weight: 600; }"
        )
        btn_pc.clicked.connect(lambda: eco().share.release())
        app.target_switcher_layout.addWidget(btn_pc)

        # Recipient chips and target pills for mobile devices
        if not online:
            lbl_none = QLabel("No devices connected")
            lbl_none.setStyleSheet("color: #718096; font-size: 12px;")
            app.device_chips_layout.addWidget(lbl_none)
            app._selected_recipient_id = None
        else:
            if app._selected_recipient_id not in [d["device_id"] for d in online]:
                app._selected_recipient_id = online[0]["device_id"]

            for dev in online:
                did = dev["device_id"]
                name = dev.get("name", "Device")
                is_selected = did == app._selected_recipient_id

                # Recipient chip
                chip = QPushButton(f"📱 {name}")
                chip.setCursor(QCursor(Qt.CursorShape.PointingHandCursor))
                chip.setStyleSheet(
                    f"QPushButton {{ background-color: {'#2B6CB0' if is_selected else '#1A1E26'}; "
                    f"color: {'#FFFFFF' if is_selected else '#A0AEC0'}; border: 1px solid {'#3182CE' if is_selected else '#282D37'}; "
                    "border-radius: 12px; padding: 3px 10px; font-size: 12px; font-weight: 500; }"
                )
                chip.clicked.connect(lambda _c=False, i=did: set_recipient(i))
                app.device_chips_layout.addWidget(chip)

                # Target pill in top header
                is_curr_target = eco().share.target == did
                pill = QPushButton(f"📱 {name}")
                pill.setCursor(QCursor(Qt.CursorShape.PointingHandCursor))
                pill.setStyleSheet(
                    f"QPushButton {{ background-color: {'#2B6CB0' if is_curr_target else '#1E232F'}; "
                    f"color: {'#FFFFFF' if is_curr_target else '#A0AEC0'}; border: 1px solid {'#4299E1' if is_curr_target else '#2D3748'}; "
                    "border-radius: 14px; padding: 4px 12px; font-size: 12px; font-weight: 600; }"
                )
                pill.clicked.connect(lambda _c=False, i=did: eco().share.switch_to(i))
                app.target_switcher_layout.addWidget(pill)

    def set_recipient(device_id):
        app._selected_recipient_id = device_id
        refresh_device_chips()

    app._refresh_device_chips = refresh_device_chips

    def on_edge(checked):
        eco().layout.edge_crossing = bool(checked)
        eco().layout.save()

    def on_speed(value):
        speed = value / 10.0
        eco().layout.speed = speed
        eco().layout.save()
        app.lbl_speed_val.setText(f"{speed:.1f}×")

    app.chk_edge.toggled.connect(on_edge)
    app.sld_speed.valueChanged.connect(on_speed)

    def on_clip(checked):
        eco().sync.clipboard_enabled = bool(checked)

    app.chk_clip.toggled.connect(on_clip)

    # File sending handlers
    def send_files_action():
        paths, _ = QFileDialog.getOpenFileNames(app, "Choose files to send")
        if not paths:
            return
        target = app._selected_recipient_id
        online = eco().registry.online_devices()
        if target and any(d["device_id"] == target for d in online):
            for p in paths:
                if os.path.isfile(p):
                    eco().sync.send_file(target, p)
        else:
            SendDialog(eco(), paths, app).exec()

    btn_send.clicked.connect(send_files_action)

    def on_drop_files(paths):
        target = app._selected_recipient_id
        online = eco().registry.online_devices()
        if target and any(d["device_id"] == target for d in online):
            for p in paths:
                if os.path.isfile(p):
                    eco().sync.send_file(target, p)
        else:
            SendDialog(eco(), paths, app).exec()

    app.drop_zone.files_dropped.connect(on_drop_files)

    def send_clipboard_action():
        clipboard = QApplication.clipboard()
        text = clipboard.text()
        if text:
            threading.Thread(target=eco().sync.publish_clipboard, args=(text,), daemon=True).start()
            app.log("Sent clipboard text to connected devices.")
        else:
            img = clipboard.image()
            if not img.isNull():
                app.log("Sending clipboard image to connected devices…")

    btn_send_clip.clicked.connect(send_clipboard_action)

    def open_received():
        from sync import downloads_dir
        folder = str(downloads_dir())
        if os.name == "nt":
            os.startfile(folder)  # noqa: S606
        else:
            subprocess.Popen(["xdg-open", folder])

    btn_open.clicked.connect(open_received)

    def add_send_to():
        ok, detail = install_send_to_shortcut()
        app.log("Added Lanyard to Explorer's Send to menu." if ok else f"Could not add shortcut: {detail}")

    btn_sendto.clicked.connect(add_send_to)

    # Live streams
    app.stream_windows = []

    def watch():
        item = app.list_streams.currentItem() or (app.list_streams.item(0) if app.list_streams.count() else None)
        if item is None:
            return
        stream = item.data(Qt.ItemDataRole.UserRole)
        if not stream:
            return
        win = StreamWindow(eco(), stream)
        app.stream_windows.append(win)
        win.show()

    btn_watch.clicked.connect(watch)

    def mirror():
        devices = [d for d in eco().registry.online_devices()]
        if not devices:
            app.log("No phone or tablet is connected.")
            return
        dlg = QDialog(app)
        dlg.setWindowTitle("Mirror Phone Screen")
        dlg.setStyleSheet("background-color: #12141A; color: #E8E8E8;")
        lay = QVBoxLayout(dlg)
        lay.addWidget(QLabel("Select which device to mirror:\n(Tap 'Start now' on the device prompt)"))
        for dev in devices:
            b = QPushButton(f"📱 {dev.get('name', 'Device')}")
            b.setStyleSheet("padding: 8px 14px; font-weight: 600; text-align: left;")
            b.clicked.connect(lambda _c=False, d=dev: (dlg.accept(), open_mirror(d)))
            lay.addWidget(b)
        dlg.exec()

    def open_mirror(dev):
        device_id = dev["device_id"]
        eco().registry.send(device_id, {"type": "screen_request", "from": "pc", "from_name": eco().share.pc_name})
        stream = {"id": f"{device_id}:screen", "kind": "screen", "label": "Screen", "owner": device_id,
                  "owner_name": dev.get("name", "Device"), "viewers": 0}
        win = StreamWindow(eco(), stream)
        app.stream_windows.append(win)
        win.show()

    btn_mirror.clicked.connect(mirror)

    def lock_device():
        devices = [d for d in eco().registry.online_devices() if d.get("caps", {}).get("lock")]
        if not devices:
            app.log("No connected phone or tablet can be locked.")
            return
        dlg = QDialog(app)
        dlg.setWindowTitle("Lock a Device")
        dlg.setStyleSheet("background-color: #12141A; color: #E8E8E8;")
        lay = QVBoxLayout(dlg)
        lay.addWidget(QLabel("Lock which device immediately?"))
        for dev in devices:
            b = QPushButton(f"📱 {dev.get('name', 'Device')}")
            b.clicked.connect(lambda _c=False, d=dev: (dlg.accept(), send_lock(d)))
            lay.addWidget(b)
        dlg.exec()

    def send_lock(dev):
        eco().registry.send(dev["device_id"], {"type": "lock_device", "from_name": eco().share.pc_name})
        app.log(f"Locked {dev.get('name', 'the device')}.")

    btn_lock.clicked.connect(lock_device)
    app.list_streams.itemDoubleClicked.connect(lambda _i: watch())

    return page


def refresh_streams(app):
    lst = getattr(app, "list_streams", None)
    if lst is None or not app.server:
        return
    lst.clear()
    for s in app.server.ecosystem.streams.list():
        viewers = f" · {s['viewers']} watching" if s["viewers"] else ""
        item = QListWidgetItem(f"📱 {s['owner_name']} · {s['label']}{viewers}")
        item.setData(Qt.ItemDataRole.UserRole, s)
        lst.addItem(item)
    if lst.count() == 0:
        placeholder = QListWidgetItem("No devices are sharing screen or camera right now")
        placeholder.setFlags(Qt.ItemFlag.NoItemFlags)
        lst.addItem(placeholder)


def wire_ecosystem(app):
    """Connect the server's ecosystem to the window: clipboard, target pill, transfers, tray."""
    from PySide6.QtWidgets import QApplication

    eco = app.server.ecosystem
    if not hasattr(app, "target_pill"):
        app.target_pill = TargetPill()
    clipboard = QApplication.clipboard()

    def on_local_clipboard():
        if getattr(app, "chk_clip", None) and not app.chk_clip.isChecked():
            return
        mime = clipboard.mimeData()
        text = clipboard.text()
        if text:
            threading.Thread(target=app.server.ecosystem.sync.publish_clipboard, args=(text,), daemon=True).start()
        elif mime is not None and mime.hasImage():
            if getattr(app, "_skip_next_clip_image", False):
                app._skip_next_clip_image = False
                return
            publish_image(clipboard.image())

    def publish_image(image):
        import hashlib
        import tempfile
        if image.isNull():
            return
        if image.width() > 2560 or image.height() > 2560:
            image = image.scaled(2560, 2560, Qt.AspectRatioMode.KeepAspectRatio, Qt.TransformationMode.SmoothTransformation)
        folder = Path(tempfile.gettempdir()) / "LanyardClipboard"
        folder.mkdir(exist_ok=True)
        path = folder / "clipboard.png"
        if not image.save(str(path), "PNG"):
            return
        digest = hashlib.sha1(path.read_bytes()).hexdigest()[:12]
        if digest == getattr(app, "_last_clip_image", None):
            return
        app._last_clip_image = digest
        final = folder / f"Copied image {digest}.png"
        os.replace(path, final)
        threading.Thread(target=app.server.ecosystem.sync.publish_clipboard_image, args=(str(final),), daemon=True).start()

    if not getattr(app, "_clipboard_hooked", False):
        clipboard.dataChanged.connect(on_local_clipboard)
        app._clipboard_hooked = True

    eco.sync.on_clipboard = lambda text: app._run_on_ui(lambda: clipboard.setText(text))

    def set_image(path):
        img = QImage(path)
        if not img.isNull():
            app._skip_next_clip_image = True
            clipboard.setImage(img)

    eco.sync.on_clipboard_image = lambda path: app._run_on_ui(lambda: set_image(path))

    def on_target(target):
        def _ui():
            dev = eco.registry.get(target) if target else None
            name = dev.get("name") if dev else None
            if getattr(app, "lbl_share_target", None):
                if name:
                    app.lbl_share_target.setText(f"⌨️ Cross Control Active: Mouse and keyboard on {name}")
                else:
                    app.lbl_share_target.setText("⌨️ Cross Control Active: Mouse and keyboard on This PC")
            if name:
                app.target_pill.show_for(name)
            else:
                app.target_pill.hide()
            if getattr(app, "layout_canvas", None):
                app.layout_canvas.update()
            if hasattr(app, "_refresh_device_chips"):
                app._refresh_device_chips()
            refresh_tray_menu(app)
        app._run_on_ui(_ui)

    eco.share.on_target_change = on_target

    transfer_widgets = {}

    def on_transfer(info):
        def _ui():
            lay = getattr(app, "transfers_layout", None)
            if lay is None:
                return
            widget = transfer_widgets.get(info["id"])
            if widget is None:
                widget = TransferItemWidget(info, eco)
                # Insert at top (index 0)
                lay.insertWidget(0, widget)
                transfer_widgets[info["id"]] = widget
            else:
                widget.update_info(info)
        app._run_on_ui(_ui)

    eco.sync.on_transfer = on_transfer

    def on_received(path, from_name):
        def _ui():
            app.log(f"Received {Path(path).name} from {from_name}.")
            if app.tray_icon:
                from PySide6.QtWidgets import QSystemTrayIcon
                app.tray_icon.showMessage("Lanyard Share Hub", f"Received {Path(path).name} from {from_name}",
                                          QSystemTrayIcon.MessageIcon.Information, 4000)
        app._run_on_ui(_ui)

    eco.sync.on_file_received = on_received

    def on_devices():
        def _ui():
            if getattr(app, "layout_canvas", None):
                app.layout_canvas.eco = app.server.ecosystem
                app.layout_canvas.update()
            if hasattr(app, "_refresh_device_chips"):
                app._refresh_device_chips()
            # Also sync devices with the gallery hub if available
            gallery_hub = getattr(app, "gallery_hub", None)
            if gallery_hub:
                gallery_hub.sync_devices(eco.registry.online_devices())
            refresh_tray_menu(app)
        app._run_on_ui(_ui)

    eco.on_devices_changed = on_devices
    eco.streams.on_streams_changed = lambda: app._run_on_ui(lambda: refresh_streams(app))
    app._run_on_ui(lambda: refresh_streams(app))
    if hasattr(app, "_refresh_device_chips"):
        app._run_on_ui(lambda: app._refresh_device_chips())
    if getattr(app, "layout_canvas", None):
        app.layout_canvas.eco = eco
        app.layout_canvas.update()


def refresh_tray_menu(app):
    """Tray › Keyboard and mouse › this PC / each device."""
    menu = getattr(app, "tray_share_menu", None)
    if menu is None or not app.server:
        return
    menu.clear()
    eco = app.server.ecosystem
    share = eco.share
    for target in share.cycle_targets():
        dev = eco.registry.get(target) if target else None
        label = "This PC" if target is None else (dev.get("name") if dev else target)
        act = menu.addAction(("● " if share.target == target else "    ") + label)
        act.triggered.connect(lambda _c=False, t=target: share.switch_to(t))
