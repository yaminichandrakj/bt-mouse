"""Devices page for the desktop companion: the drag-and-drop layout of monitors and
phones/tablets, keyboard-and-mouse sharing controls, clipboard, and file sending.
"""

import os
import subprocess
import sys
import threading
from pathlib import Path

from PySide6.QtCore import QPointF, QRectF, Qt, QTimer, Signal
from PySide6.QtGui import QBrush, QColor, QFont, QPainter, QPen
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
    QSlider,
    QVBoxLayout,
    QWidget,
)

from ecosystem import Rect

MONITOR_FILL = QColor("#1F1F21")
MONITOR_EDGE = QColor("#3C3D3F")
DEVICE_FILL = QColor("#2E2E30")
DEVICE_ONLINE = QColor("#6DD58C")
DEVICE_ACTIVE = QColor("#5AAAFF")
TEXT = QColor("#E8E8E8")
MUTED = QColor("#8E8E8E")


class LayoutCanvas(QWidget):
    """Monitors drawn to scale; phones and tablets are dragged against any of their edges."""

    changed = Signal()

    TRAY_HEIGHT = 96

    def __init__(self, ecosystem, parent=None):
        super().__init__(parent)
        self.eco = ecosystem
        self.setMinimumHeight(400)
        self.setMouseTracking(True)
        self._drag = None
        self._scale = 1.0
        self._origin = QPointF(0, 0)

    # --- geometry --------------------------------------------------------
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
        pad = 0.12 * max(x1 - x0, y1 - y0)
        return Rect(x0 - pad, y0 - pad, x1 - x0 + 2 * pad, y1 - y0 + 2 * pad)

    def _update_transform(self):
        b = self._bounds()
        area_h = max(80, self.height() - self.TRAY_HEIGHT - 10)
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
        return QRectF(8, self.height() - self.TRAY_HEIGHT, self.width() - 16, self.TRAY_HEIGHT - 8)

    def _tray_slots(self, devices):
        """Unplaced devices sit in the tray at the bottom, in a row."""
        unplaced = [d for k, d in devices.items() if k not in self.eco.layout.placements]
        slots = {}
        tray = self._tray_rect()
        x = tray.x() + 12
        for dev in unplaced:
            w, h = self.eco.layout.device_size(dev)
            s = (tray.height() - 40) / max(w, h)
            rect = QRectF(x + 50, tray.y() + 8, w * s, h * s)
            slots[dev["device_id"]] = rect
            x += rect.width() + 112
        return slots

    # --- painting --------------------------------------------------------
    def paintEvent(self, _event):
        self._update_transform()
        p = QPainter(self)
        p.setRenderHint(QPainter.RenderHint.Antialiasing)
        font = QFont(self.font())
        font.setPointSizeF(max(8.0, font.pointSizeF() - 1))
        p.setFont(font)
        for i, m in enumerate(self.eco.layout.monitors):
            r = self._to_screen(m)
            p.setPen(QPen(MONITOR_EDGE, 1.5))
            p.setBrush(QBrush(MONITOR_FILL))
            p.drawRoundedRect(r, 6, 6)
            p.setPen(MUTED)
            p.drawText(r, Qt.AlignmentFlag.AlignCenter, f"Monitor {i + 1}\n{int(m.w)}×{int(m.h)}")

        devices = self._devices()
        target = self.eco.share.target
        for device_id, rect in self.eco.layout.placements.items():
            dev = devices.get(device_id)
            if dev is None:
                continue
            r = self._to_screen(self._drag["rect"]) if self._drag and self._drag["id"] == device_id else self._to_screen(rect)
            self._paint_device(p, r, dev, device_id == target)

        tray = self._tray_rect()
        p.setPen(QPen(QColor("#2A2B2D"), 1, Qt.PenStyle.DashLine))
        p.setBrush(Qt.BrushStyle.NoBrush)
        p.drawRoundedRect(tray, 10, 10)
        slots = self._tray_slots(devices)
        if not slots:
            p.setPen(MUTED)
            p.drawText(tray, Qt.AlignmentFlag.AlignCenter, "Drag a device here to reach it by hotkey only")
        else:
            p.setPen(MUTED)
            p.drawText(tray.adjusted(0, 0, -10, -6), Qt.AlignmentFlag.AlignRight | Qt.AlignmentFlag.AlignBottom,
                       "Not placed: Ctrl+Alt+→ only. Drag up next to a monitor to place.")
        for device_id, r in slots.items():
            if self._drag and self._drag["id"] == device_id:
                r = self._to_screen(self._drag["rect"])
            self._paint_device(p, r, devices[device_id], device_id == target)
        p.end()

    def _paint_device(self, p, r, dev, active):
        online = self.eco.registry.is_online(dev["device_id"])
        p.setPen(QPen(DEVICE_ACTIVE if active else (DEVICE_ONLINE if online else MONITOR_EDGE), 2))
        p.setBrush(QBrush(DEVICE_FILL))
        p.drawRoundedRect(r, 8, 8)
        label = dev.get("name", "Device")
        if not dev.get("caps", {}).get("control"):
            label += "\ncontrol is off on it"
        elif not online:
            label += "\noffline"
        box = QRectF(r.center().x() - 70, r.bottom() + 4, 140, 34)
        p.setPen(TEXT if online else MUTED)
        p.drawText(box, Qt.AlignmentFlag.AlignHCenter | Qt.AlignmentFlag.AlignTop | Qt.TextFlag.TextWordWrap, label)

    # --- dragging --------------------------------------------------------
    def mousePressEvent(self, event):
        pos = event.position()
        devices = self._devices()
        for device_id, rect in list(self.eco.layout.placements.items()):
            if device_id in devices and self._to_screen(rect).contains(pos):
                lx, ly = self._to_layout(pos)
                self._drag = {"id": device_id, "rect": Rect(rect.x, rect.y, rect.w, rect.h), "dx": lx - rect.x, "dy": ly - rect.y}
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
    """Small always-on-top note while the keyboard and mouse are on another device."""

    def __init__(self):
        super().__init__(None, Qt.WindowType.FramelessWindowHint | Qt.WindowType.WindowStaysOnTopHint |
                         Qt.WindowType.Tool | Qt.WindowType.WindowTransparentForInput)
        self.setAttribute(Qt.WidgetAttribute.WA_TranslucentBackground)
        self.setAttribute(Qt.WidgetAttribute.WA_ShowWithoutActivating)
        lay = QHBoxLayout(self)
        lay.setContentsMargins(0, 0, 0, 0)
        self.label = QLabel()
        self.label.setStyleSheet("background-color: #1B1B1C; color: #E8E8E8; border: 1px solid #5AAAFF;"
                                 " border-radius: 14px; padding: 6px 14px; font-size: 13px;")
        lay.addWidget(self.label)

    def show_for(self, name):
        from PySide6.QtGui import QGuiApplication
        self.label.setText(f"Keyboard and mouse → {name}   ·   Ctrl+Alt+Home to come back")
        self.adjustSize()
        screen = QGuiApplication.primaryScreen().geometry()
        self.move(screen.x() + (screen.width() - self.width()) // 2, screen.y() + 12)
        self.show()


class StreamWindow(QWidget):
    """Watch a phone or tablet stream. On a screen stream, click, drag and scroll to use the device."""

    frame_ready = Signal(bytes, int, int)

    def __init__(self, ecosystem, stream):
        super().__init__(None, Qt.WindowType.Window)
        self.eco = ecosystem
        self.stream = stream
        self.owner = stream["owner"]
        self.setWindowTitle(f"{stream['owner_name']} · {stream['label']}")
        self.resize(420, 820)
        self.setStyleSheet("background-color: #000000; color: #E8E8E8;")
        lay = QVBoxLayout(self)
        lay.setContentsMargins(0, 0, 0, 0)
        self.view = QLabel("Waiting for the first picture…")
        self.view.setAlignment(Qt.AlignmentFlag.AlignCenter)
        self.view.setMinimumSize(200, 200)
        lay.addWidget(self.view, 1)
        self.can_control = stream["kind"] == "screen" and bool(
            (ecosystem.registry.get(self.owner) or {}).get("caps", {}).get("control"))
        bar = QHBoxLayout()
        bar.setContentsMargins(10, 6, 10, 8)
        info = QLabel("Click to tap, drag to swipe, scroll to scroll, right-click is Back" if self.can_control
                      else "View only")
        info.setStyleSheet("color: #8E8E8E; font-size: 12px;")
        bar.addWidget(info, 1)
        if self.can_control:
            for label, action in (("Back", "back"), ("Home", "home"), ("Recents", "recents")):
                b = QPushButton(label)
                b.clicked.connect(lambda _c=False, a=action: self._send({"ev": "global", "action": a}))
                bar.addWidget(b)
        lay.addLayout(bar)
        self._pixmap = None
        self._img_size = (0, 0)
        self._dev_size = (0, 0)
        self.frame_ready.connect(self._show_frame)
        self._key = f"window-{id(self)}"
        ecosystem.streams.watch_local(stream["id"], self._key, self._on_payload)
        if self.can_control:
            self.eco.registry.send(self.owner, {"type": "control_state", "active": True,
                                                "controller": self.eco.share.pc_name})

    def _on_payload(self, payload):
        import base64
        try:
            data = base64.b64decode(payload.get("frame") or "")
        except Exception:
            return
        self.frame_ready.emit(data, int(payload.get("width") or 0), int(payload.get("height") or 0))

    def _show_frame(self, data, w, h):
        from PySide6.QtGui import QPixmap
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
        self.eco.streams.unwatch_local(self.stream["id"], self._key)
        if self.can_control:
            self.eco.registry.send(self.owner, {"type": "control_state", "active": False,
                                                "controller": self.eco.share.pc_name})
        super().closeEvent(event)


class SendDialog(QDialog):
    """Choose which device gets the files (Explorer › Send to › Lanyard, or drag and drop)."""

    def __init__(self, ecosystem, paths, parent=None):
        super().__init__(parent)
        self.setWindowTitle("Send with Lanyard")
        self.setMinimumWidth(380)
        lay = QVBoxLayout(self)
        names = ", ".join(Path(p).name for p in paths[:3]) + (f" and {len(paths) - 3} more" if len(paths) > 3 else "")
        lbl = QLabel(f"Send {names} to:")
        lbl.setWordWrap(True)
        lay.addWidget(lbl)
        online = [d for d in ecosystem.registry.online_devices() if d.get("caps", {}).get("files", True)]
        if not online:
            lay.addWidget(QLabel("No phone or tablet is connected right now."))
        for dev in online:
            btn = QPushButton(dev.get("name", "Device"))
            btn.clicked.connect(lambda _c=False, d=dev: self._send(ecosystem, d, paths))
            lay.addWidget(btn)
        cancel = QPushButton("Cancel")
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
    """Returns the page widget. ``app`` is the CompanionApp (for _page, server and _run_on_ui)."""
    page, layout = app._page("Devices", "Use this keyboard and mouse on your phone or tablet, share the clipboard, "
                                        "and send files.", max_width=980)

    # Who has the keyboard and mouse
    status = QFrame()
    status.setObjectName("Card")
    sl = QHBoxLayout(status)
    sl.setContentsMargins(20, 16, 16, 16)
    app.lbl_share_target = QLabel("Keyboard and mouse: this PC")
    app.lbl_share_target.setStyleSheet("font-weight: 500;")
    sl.addWidget(app.lbl_share_target, 1)
    btn_prev = QPushButton("◀ Previous")
    btn_next = QPushButton("Next ▶")
    btn_home = QPushButton("Back to this PC")
    for b in (btn_prev, btn_next, btn_home):
        sl.addWidget(b)
    layout.addWidget(status)

    hint = QLabel("Push the mouse off a screen edge where a device sits below, or press Ctrl+Alt+→ / Ctrl+Alt+←. "
                  "Ctrl+Alt+Home always returns here. On the phone, right-click is Back and middle-click is Home.")
    hint.setWordWrap(True)
    hint.setStyleSheet("color: #8E8E8E; font-size: 13px;")
    layout.addWidget(hint)

    layout.addWidget(app._section_label("Layout"))
    canvas_card = QFrame()
    canvas_card.setObjectName("Card")
    cl = QVBoxLayout(canvas_card)
    cl.setContentsMargins(12, 12, 12, 12)
    app.layout_canvas = LayoutCanvas(app.server.ecosystem)
    cl.addWidget(app.layout_canvas)
    opts = QHBoxLayout()
    app.chk_edge = QCheckBox("Cross over at screen edges")
    app.chk_edge.setChecked(app.server.ecosystem.layout.edge_crossing)
    opts.addWidget(app.chk_edge)
    opts.addStretch()
    opts.addWidget(QLabel("Pointer speed on the device"))
    app.sld_speed = QSlider(Qt.Orientation.Horizontal)
    app.sld_speed.setRange(5, 40)
    app.sld_speed.setValue(int(app.server.ecosystem.layout.speed * 10))
    app.sld_speed.setFixedWidth(160)
    opts.addWidget(app.sld_speed)
    cl.addLayout(opts)
    layout.addWidget(canvas_card)

    layout.addWidget(app._section_label("Live from your devices"))
    live_card = QFrame()
    live_card.setObjectName("Card")
    ll = QVBoxLayout(live_card)
    ll.setContentsMargins(20, 16, 20, 16)
    app.list_streams = QListWidget()
    app.list_streams.setMinimumHeight(90)
    ll.addWidget(app.list_streams)
    live_row = QHBoxLayout()
    btn_watch = QPushButton("Watch")
    live_row.addWidget(btn_watch)
    live_note = QLabel("Start Share screen or Camera on a phone or tablet (Devices › Live). "
                       "Phone screens can be used with this mouse right in the window.")
    live_note.setWordWrap(True)
    live_note.setStyleSheet("color: #8E8E8E; font-size: 13px;")
    live_row.addWidget(live_note, 1)
    ll.addLayout(live_row)
    layout.addWidget(live_card)

    layout.addWidget(app._section_label("Clipboard and files"))
    files_card = QFrame()
    files_card.setObjectName("Card")
    fl = QVBoxLayout(files_card)
    fl.setContentsMargins(20, 16, 20, 16)
    fl.setSpacing(10)
    app.chk_clip = QCheckBox("Share the clipboard with my phone and tablet")
    app.chk_clip.setChecked(True)
    fl.addWidget(app.chk_clip)
    row = QHBoxLayout()
    btn_send = QPushButton("Send files…")
    btn_sendto = QPushButton("Add to Explorer's Send to menu")
    btn_open = QPushButton("Open received files")
    row.addWidget(btn_send)
    row.addWidget(btn_sendto)
    row.addWidget(btn_open)
    row.addStretch()
    fl.addLayout(row)
    note = QLabel("Files from your devices are saved to Downloads\\Lanyard. You can also drop files on this window.")
    note.setStyleSheet("color: #8E8E8E; font-size: 13px;")
    note.setWordWrap(True)
    fl.addWidget(note)
    app.list_transfers = QListWidget()
    app.list_transfers.setMinimumHeight(120)
    fl.addWidget(app.list_transfers)
    layout.addWidget(files_card)
    layout.addStretch()

    # --- behaviour ----------------------------------------------------------
    def eco():
        return app.server.ecosystem

    def cycle(step):
        share = eco().share
        targets = share.cycle_targets()
        try:
            i = targets.index(share.target)
        except ValueError:
            i = 0
        share.switch_to(targets[(i + step) % len(targets)])

    btn_prev.clicked.connect(lambda: cycle(-1))
    btn_next.clicked.connect(lambda: cycle(1))
    btn_home.clicked.connect(lambda: eco().share.release())

    def on_edge(checked):
        eco().layout.edge_crossing = bool(checked)
        eco().layout.save()

    def on_speed(value):
        eco().layout.speed = value / 10.0
        eco().layout.save()

    app.chk_edge.toggled.connect(on_edge)
    app.sld_speed.valueChanged.connect(on_speed)

    def on_clip(checked):
        eco().sync.clipboard_enabled = bool(checked)

    app.chk_clip.toggled.connect(on_clip)

    def send_files():
        paths, _ = QFileDialog.getOpenFileNames(app, "Send files to a device")
        if paths:
            SendDialog(eco(), paths, app).exec()

    btn_send.clicked.connect(send_files)

    def add_send_to():
        ok, detail = install_send_to_shortcut()
        app.log("Added Lanyard to Explorer's Send to menu." if ok else f"Could not add Send to shortcut: {detail}")

    btn_sendto.clicked.connect(add_send_to)

    def open_received():
        from sync import downloads_dir
        folder = str(downloads_dir())
        if os.name == "nt":
            os.startfile(folder)  # noqa: S606 - opening our own folder
        else:
            subprocess.Popen(["xdg-open", folder])

    btn_open.clicked.connect(open_received)

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
    app.list_streams.itemDoubleClicked.connect(lambda _i: watch())
    return page


def refresh_streams(app):
    lst = getattr(app, "list_streams", None)
    if lst is None or not app.server:
        return
    lst.clear()
    for s in app.server.ecosystem.streams.list():
        viewers = f" · {s['viewers']} watching" if s["viewers"] else ""
        item = QListWidgetItem(f"{s['owner_name']} · {s['label']}{viewers}")
        item.setData(Qt.ItemDataRole.UserRole, s)
        lst.addItem(item)
    if lst.count() == 0:
        placeholder = QListWidgetItem("Nothing is being shared right now")
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
        if not getattr(app, "chk_clip", None) or app.chk_clip.isChecked():
            text = clipboard.text()
            if text:
                threading.Thread(target=app.server.ecosystem.sync.publish_clipboard, args=(text,), daemon=True).start()

    if not getattr(app, "_clipboard_hooked", False):
        clipboard.dataChanged.connect(on_local_clipboard)
        app._clipboard_hooked = True

    eco.sync.on_clipboard = lambda text: app._run_on_ui(lambda: clipboard.setText(text))

    def on_target(target):
        def _ui():
            dev = eco.registry.get(target) if target else None
            name = dev.get("name") if dev else None
            if getattr(app, "lbl_share_target", None):
                app.lbl_share_target.setText(f"Keyboard and mouse: {name}" if name else "Keyboard and mouse: this PC")
            if name:
                app.target_pill.show_for(name)
            else:
                app.target_pill.hide()
            if getattr(app, "layout_canvas", None):
                app.layout_canvas.update()
            refresh_tray_menu(app)
        app._run_on_ui(_ui)

    eco.share.on_target_change = on_target

    transfers = {}

    def on_transfer(info):
        def _ui():
            lst = getattr(app, "list_transfers", None)
            if lst is None:
                return
            item = transfers.get(info["id"])
            if item is None:
                item = QListWidgetItem()
                lst.insertItem(0, item)
                transfers[info["id"]] = item
            arrow = "→" if info["direction"] == "out" else "←"
            peer = eco.registry.get(info["peer"]) or {}
            state = {"done": "Done", "failed": info.get("error") or "Failed", "waiting": "Waiting for the device",
                     "preparing": "Preparing", "verifying": "Checking"}.get(info["state"], f"{int(info['progress'] * 100)}%")
            speed = f" · {info['speed'] / 1e6:.1f} MB/s" if info["state"] in ("sending", "receiving") else ""
            item.setText(f"{arrow} {info['name']}  ·  {peer.get('name', 'device')}  ·  {state}{speed}")
        app._run_on_ui(_ui)

    eco.sync.on_transfer = on_transfer

    def on_received(path, from_name):
        def _ui():
            app.log(f"Received {Path(path).name} from {from_name}.")
            if app.tray_icon:
                from PySide6.QtWidgets import QSystemTrayIcon
                app.tray_icon.showMessage("Lanyard", f"Received {Path(path).name} from {from_name}",
                                          QSystemTrayIcon.MessageIcon.Information, 4000)
        app._run_on_ui(_ui)

    eco.sync.on_file_received = on_received

    def on_devices():
        def _ui():
            if getattr(app, "layout_canvas", None):
                app.layout_canvas.eco = app.server.ecosystem
                app.layout_canvas.update()
            refresh_tray_menu(app)
        app._run_on_ui(_ui)

    eco.on_devices_changed = on_devices
    eco.streams.on_streams_changed = lambda: app._run_on_ui(lambda: refresh_streams(app))
    app._run_on_ui(lambda: refresh_streams(app))
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
