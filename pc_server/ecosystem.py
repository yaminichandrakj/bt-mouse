"""Device ecosystem: paired phones/tablets, the drag-and-drop layout, and sharing this
PC's keyboard and mouse with them.

Everything here except ``WindowsInputHooks`` is plain Python so it can be unit tested on
any OS. The hooks use pynput's Windows low-level hooks to capture and suppress local
input while a phone or tablet is the target.
"""

import json
import os
import queue
import threading
import time
from pathlib import Path

from library import LibrarySync
from streams import StreamHub
from sync import SyncHub, TransferHttpServer


NEVER = float("-inf")


def _data_dir():
    base = Path(os.environ.get("LOCALAPPDATA") or os.path.expanduser("~")) / "BTMouse"
    try:
        base.mkdir(parents=True, exist_ok=True)
    except OSError:
        pass
    return base


# ----------------------------------------------------------------------
# Paired devices
# ----------------------------------------------------------------------
class DeviceRegistry:
    """Phones and tablets that said hello, with the newest reply path to each.

    A device can be connected over several links at once (Wi-Fi, Bluetooth, internet);
    whichever link carried its latest packet is used to reach it.
    """

    ONLINE_TIMEOUT = 6.0

    def __init__(self):
        self._lock = threading.Lock()
        self._devices = {}
        self._session_to_device = {}
        self.on_change = None

    def hello(self, data, respond):
        device_id = str(data.get("device_id") or "").strip()[:64]
        if not device_id:
            return None
        session = str(data.get("client_session") or "")
        with self._lock:
            dev = self._devices.setdefault(device_id, {"device_id": device_id})
            was_online = time.monotonic() - dev.get("last_seen", NEVER) <= self.ONLINE_TIMEOUT
            before = (dev.get("name"), dev.get("caps"), dev.get("screen_w"), dev.get("screen_h"))
            dev.update({
                "name": str(data.get("name") or "Android device")[:60],
                "kind": "tablet" if data.get("kind") == "tablet" else "phone",
                "screen_w": max(1, int(data.get("screen_w") or 1080)),
                "screen_h": max(1, int(data.get("screen_h") or 2400)),
                "density": float(data.get("density") or 2.75),
                "rotation": int(data.get("rotation") or 0),
                "caps": dict(data.get("caps") or {}),
                "respond": respond,
                "last_seen": time.monotonic(),
            })
            if session:
                self._session_to_device[session] = device_id
            after = (dev.get("name"), dev.get("caps"), dev.get("screen_w"), dev.get("screen_h"))
        if not was_online or before != after:
            self._changed()
        return device_id

    def note_packet(self, data, respond):
        session = data.get("client_session") if isinstance(data, dict) else None
        if not session:
            return
        with self._lock:
            device_id = self._session_to_device.get(str(session))
            dev = self._devices.get(device_id) if device_id else None
            if dev is not None:
                dev["respond"] = respond
                dev["last_seen"] = time.monotonic()

    def forget_session(self, data):
        session = data.get("client_session") if isinstance(data, dict) else None
        if not session:
            return
        with self._lock:
            device_id = self._session_to_device.get(str(session))
            dev = self._devices.get(device_id) if device_id else None
            if dev is not None:
                dev["last_seen"] = NEVER
        self._changed()

    def get(self, device_id):
        with self._lock:
            dev = self._devices.get(device_id)
            return dict(dev) if dev else None

    def is_online(self, device_id):
        dev = self.get(device_id)
        return bool(dev) and time.monotonic() - dev.get("last_seen", NEVER) <= self.ONLINE_TIMEOUT

    def online_devices(self):
        now = time.monotonic()
        with self._lock:
            return [
                {k: v for k, v in dev.items() if k != "respond"}
                for dev in self._devices.values()
                if now - dev.get("last_seen", NEVER) <= self.ONLINE_TIMEOUT
            ]

    def all_devices(self):
        with self._lock:
            return [{k: v for k, v in dev.items() if k != "respond"} for dev in self._devices.values()]

    def send(self, device_id, payload):
        with self._lock:
            dev = self._devices.get(device_id)
            respond = dev.get("respond") if dev else None
        if not respond:
            return False
        try:
            respond(payload)
            return True
        except Exception:
            return False

    def broadcast(self, payload, exclude=None):
        sent = 0
        for dev in self.online_devices():
            if dev["device_id"] != exclude and self.send(dev["device_id"], payload):
                sent += 1
        return sent

    def _changed(self):
        cb = self.on_change
        if cb:
            try:
                cb()
            except Exception:
                pass


# ----------------------------------------------------------------------
# Layout: monitors are fixed; devices are rectangles placed next to them
# ----------------------------------------------------------------------
class Rect:
    __slots__ = ("x", "y", "w", "h")

    def __init__(self, x, y, w, h):
        self.x, self.y, self.w, self.h = float(x), float(y), float(w), float(h)

    @property
    def right(self):
        return self.x + self.w

    @property
    def bottom(self):
        return self.y + self.h

    def contains(self, px, py):
        return self.x <= px < self.right and self.y <= py < self.bottom

    def as_dict(self):
        return {"x": round(self.x, 1), "y": round(self.y, 1), "w": round(self.w, 1), "h": round(self.h, 1)}

    def __repr__(self):
        return f"Rect({self.x}, {self.y}, {self.w}, {self.h})"


class Layout:
    """Where each device sits around the PC's monitors, in Windows virtual-screen pixels.

    Saved to ``layout.json``. A device without a placement is reachable by hotkey only.
    """

    SNAP = 24.0

    def __init__(self, path=None):
        self.path = Path(path) if path else _data_dir() / "layout.json"
        self.monitors = []
        self.placements = {}
        self.edge_crossing = True
        self.speed = 1.6
        self.load()

    def load(self):
        try:
            data = json.loads(self.path.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            data = {}
        self.placements = {}
        for device_id, p in (data.get("devices") or {}).items():
            try:
                self.placements[str(device_id)] = Rect(p["x"], p["y"], p["w"], p["h"])
            except (KeyError, TypeError, ValueError):
                continue
        self.edge_crossing = bool(data.get("edge_crossing", True))
        try:
            self.speed = min(5.0, max(0.3, float(data.get("speed", 1.6))))
        except (TypeError, ValueError):
            self.speed = 1.6

    def save(self):
        data = {
            "devices": {k: r.as_dict() for k, r in self.placements.items()},
            "edge_crossing": self.edge_crossing,
            "speed": self.speed,
        }
        tmp = self.path.with_suffix(".tmp")
        try:
            tmp.write_text(json.dumps(data, indent=2), encoding="utf-8")
            os.replace(tmp, self.path)
        except OSError:
            pass

    def set_monitors(self, rects):
        self.monitors = [r if isinstance(r, Rect) else Rect(*r) for r in rects] or [Rect(0, 0, 1920, 1080)]

    def device_size(self, dev):
        """Size of a device's rectangle in layout pixels: real aspect, about 60% of a monitor tall."""
        ref = self.monitors[0] if self.monitors else Rect(0, 0, 1920, 1080)
        sw, sh = max(1, dev.get("screen_w", 1080)), max(1, dev.get("screen_h", 2400))
        tall = ref.h * (0.6 if dev.get("kind") != "tablet" else 0.75)
        if sh >= sw:
            return tall * sw / sh, tall
        wide = tall * 1.2
        return wide, wide * sh / sw

    def default_placement(self, dev):
        """Right of the rightmost monitor for a phone, left of the leftmost for a tablet."""
        w, h = self.device_size(dev)
        if not self.monitors:
            self.set_monitors([])
        if dev.get("kind") == "tablet":
            mon = min(self.monitors, key=lambda m: m.x)
            return Rect(mon.x - w, mon.y + (mon.h - h) / 2, w, h)
        mon = max(self.monitors, key=lambda m: m.right)
        return Rect(mon.right, mon.y + (mon.h - h) / 2, w, h)

    def resize_for(self, device_id, dev):
        """Keep the placed rectangle's centre when a device rotates."""
        rect = self.placements.get(device_id)
        if not rect:
            return
        w, h = self.device_size(dev)
        if abs(rect.w - w) < 1 and abs(rect.h - h) < 1:
            return
        cx, cy = rect.x + rect.w / 2, rect.y + rect.h / 2
        self.placements[device_id] = self.snap(device_id, Rect(cx - w / 2, cy - h / 2, w, h))

    def snap(self, device_id, rect):
        """Pull a dropped rectangle flush against the nearest monitor or device edge."""
        others = list(self.monitors) + [r for k, r in self.placements.items() if k != device_id]
        best = None
        for o in others:
            v_overlap = min(rect.bottom, o.bottom) - max(rect.y, o.y)
            h_overlap = min(rect.right, o.right) - max(rect.x, o.x)
            candidates = []
            if v_overlap > 0:
                candidates.append((abs(rect.x - o.right), o.right, None))
                candidates.append((abs(rect.right - o.x), o.x - rect.w, None))
            if h_overlap > 0:
                candidates.append((abs(rect.y - o.bottom), None, o.bottom))
                candidates.append((abs(rect.bottom - o.y), None, o.y - rect.h))
            for dist, nx, ny in candidates:
                if best is None or dist < best[0]:
                    best = (dist, nx, ny)
        if best is None or best[0] > max(self.SNAP, rect.w, rect.h):
            return rect
        _, nx, ny = best
        out = Rect(nx if nx is not None else rect.x, ny if ny is not None else rect.y, rect.w, rect.h)
        # Never overlap a monitor: push out if the snap landed inside one.
        for m in self.monitors:
            if min(out.right, m.right) - max(out.x, m.x) > 1 and min(out.bottom, m.bottom) - max(out.y, m.y) > 1:
                return rect
        return out

    def monitor_at(self, x, y):
        for m in self.monitors:
            if m.contains(x, y):
                return m
        return None

    def device_at(self, x, y, exclude=None):
        for device_id, r in self.placements.items():
            if device_id != exclude and r.contains(x, y):
                return device_id
        return None

    def target_at(self, x, y, exclude=None):
        """("monitor", rect) / ("device", id) / (None, None) for a layout point."""
        m = self.monitor_at(x, y)
        if m is not None:
            return "monitor", m
        d = self.device_at(x, y, exclude=exclude)
        if d is not None:
            return "device", d
        return None, None


# ----------------------------------------------------------------------
# Windows virtual-key -> Android KeyEvent codes
# ----------------------------------------------------------------------
VK_TO_ANDROID = {
    0x08: 67, 0x09: 61, 0x0D: 66, 0x1B: 111, 0x20: 62,
    0x21: 92, 0x22: 93, 0x23: 123, 0x24: 122,
    0x25: 21, 0x26: 19, 0x27: 22, 0x28: 20,
    0x2D: 124, 0x2E: 112,
    0x10: 59, 0xA0: 59, 0xA1: 60, 0x11: 113, 0xA2: 113, 0xA3: 114,
    0x12: 57, 0xA4: 57, 0xA5: 58, 0x5B: 117, 0x5C: 118, 0x14: 115, 0x90: 143,
    0xBA: 74, 0xBB: 70, 0xBC: 55, 0xBD: 69, 0xBE: 56, 0xBF: 76, 0xC0: 68,
    0xDB: 71, 0xDC: 73, 0xDD: 72, 0xDE: 75,
    0x6A: 155, 0x6B: 157, 0x6D: 156, 0x6E: 158, 0x6F: 154,
    0xAD: 164, 0xAE: 25, 0xAF: 24, 0xB0: 87, 0xB1: 88, 0xB3: 85,
}
for _i in range(10):
    VK_TO_ANDROID[0x30 + _i] = 7 + _i          # 0-9
    VK_TO_ANDROID[0x60 + _i] = 144 + _i        # numpad 0-9
for _i in range(26):
    VK_TO_ANDROID[0x41 + _i] = 29 + _i         # A-Z
for _i in range(12):
    VK_TO_ANDROID[0x70 + _i] = 131 + _i        # F1-F12

_SHIFT_DIGITS = ")!@#$%^&*("
_PUNCT = {0xBA: (";", ":"), 0xBB: ("=", "+"), 0xBC: (",", "<"), 0xBD: ("-", "_"), 0xBE: (".", ">"),
          0xBF: ("/", "?"), 0xC0: ("`", "~"), 0xDB: ("[", "{"), 0xDC: ("\\", "|"), 0xDD: ("]", "}"),
          0xDE: ("'", '"'), 0x20: (" ", " "), 0x6A: ("*", "*"), 0x6B: ("+", "+"), 0x6D: ("-", "-"),
          0x6E: (".", "."), 0x6F: ("/", "/")}

META_SHIFT, META_ALT, META_CTRL, META_META = 0x1, 0x2, 0x1000, 0x10000
META_CAPS = 0x100000


def vk_to_char(vk, shift=False, caps=False):
    """Printable character for a US layout key, used when the phone has no Lanyard keyboard."""
    if 0x41 <= vk <= 0x5A:
        upper = shift != caps
        return chr(vk) if upper else chr(vk).lower()
    if 0x30 <= vk <= 0x39:
        return _SHIFT_DIGITS[vk - 0x30] if shift else chr(vk)
    if 0x60 <= vk <= 0x69:
        return chr(0x30 + vk - 0x60)
    pair = _PUNCT.get(vk)
    if pair:
        return pair[1] if shift else pair[0]
    return None


# ----------------------------------------------------------------------
# Keyboard and mouse sharing
# ----------------------------------------------------------------------
class InputShare:
    """Which device gets this PC's keyboard and mouse, and the edge/hotkey switching.

    The hooks feed raw events in; methods return True when the event must be kept away
    from Windows (it went to a phone). Network sends happen on a worker thread so the
    hook callback never blocks.
    """

    MOVE_INTERVAL = 0.008
    LINK_TIMEOUT = 4.0
    EDGE_PUSHES = 2

    def __init__(self, registry, layout, set_cursor=None, get_park=None, pc_name="Laptop"):
        self.registry = registry
        self.layout = layout
        self.set_cursor = set_cursor or (lambda x, y: None)
        self.get_park = get_park
        self.pc_name = pc_name
        self.enabled = True
        self.target = None
        self.rx = self.ry = 0.0
        self.park = (0, 0)
        self.on_target_change = None
        self._lock = threading.RLock()
        self._mods = set()
        self._held = set()
        self._key_dest = {}
        self._buttons_down = set()
        self._edge_hits = 0
        self._last_local = None
        self._settle_until = 0.0
        self._pending_move = None
        self._last_move_sent = 0.0
        self._queue = queue.Queue(maxsize=2000)
        self._worker = threading.Thread(target=self._send_loop, name="InputShareSend", daemon=True)
        self._worker.start()
        self._watchdog = threading.Thread(target=self._watch_loop, name="InputShareWatch", daemon=True)
        self._watchdog.start()

    # --- targets -------------------------------------------------------
    def cycle_targets(self):
        """Laptop first, then placed devices left-to-right, then any other online device."""
        online = {d["device_id"]: d for d in self.registry.online_devices() if d.get("caps", {}).get("control")}
        placed = sorted((k for k in online if k in self.layout.placements),
                        key=lambda k: (self.layout.placements[k].x, self.layout.placements[k].y))
        rest = sorted((k for k in online if k not in placed), key=lambda k: online[k].get("name", ""))
        return [None] + placed + rest

    def switch_to(self, device_id, entry=None):
        """Give input to ``device_id`` (None = this PC). ``entry`` is a layout point for edge entry."""
        with self._lock:
            if device_id == self.target:
                return
            previous = self.target
            if previous is not None:
                self._release_remote(previous)
            if device_id is None:
                self.target = None
                if entry is not None:
                    self.set_cursor(int(entry[0]), int(entry[1]))
                elif previous is not None:
                    m = self.layout.monitors[0] if self.layout.monitors else Rect(0, 0, 1920, 1080)
                    self.set_cursor(int(m.x + m.w / 2), int(m.y + m.h / 2))
            else:
                dev = self.registry.get(device_id)
                if not dev:
                    return
                self.target = device_id
                sw, sh = dev["screen_w"], dev["screen_h"]
                rect = self.layout.placements.get(device_id)
                if entry is not None and rect is not None:
                    self.rx = min(sw - 1, max(0, (entry[0] - rect.x) / rect.w * sw))
                    self.ry = min(sh - 1, max(0, (entry[1] - rect.y) / rect.h * sh))
                else:
                    self.rx, self.ry = sw / 2, sh / 2
                self.park = self.get_park() if self.get_park else (0, 0)
                self.set_cursor(*self.park)
                # Events already queued from before the cursor jumped to the park point are stale.
                self._settle_until = time.monotonic() + 0.03
                self._enqueue(device_id, {"type": "control_state", "active": True, "controller": self.pc_name})
                self._queue_move(force=True)
            self._edge_hits = 0
        cb = self.on_target_change
        if cb:
            try:
                cb(self.target)
            except Exception:
                pass

    def release(self):
        self.switch_to(None)

    def _release_remote(self, device_id):
        for vk, dest in list(self._key_dest.items()):
            if dest == device_id:
                self._send_key(device_id, vk, False)
                del self._key_dest[vk]
        for button in list(self._buttons_down):
            self._enqueue(device_id, self._button_payload(button, False))
        self._buttons_down.clear()
        self._flush_move()
        self._enqueue(device_id, {"type": "control_state", "active": False, "controller": self.pc_name})

    # --- mouse ---------------------------------------------------------
    def on_mouse_move(self, x, y):
        """Raw cursor position from the hook. Returns True to suppress it."""
        with self._lock:
            if not self.enabled:
                return False
            if self.target is None:
                self._check_edge(x, y)
                return self.target is not None
            px, py = self.park
            dx, dy = x - px, y - py
            if (dx == 0 and dy == 0) or time.monotonic() < self._settle_until:
                return True
            dx = max(-200, min(200, dx))
            dy = max(-200, min(200, dy))
            dev = self.registry.get(self.target)
            if not dev:
                self.switch_to(None)
                return False
            s = self.layout.speed
            nx, ny = self.rx + dx * s, self.ry + dy * s
            sw, sh = dev["screen_w"], dev["screen_h"]
            if not (0 <= nx < sw and 0 <= ny < sh) and self.layout.edge_crossing and not self._buttons_down:
                if self._leave_device(dev, nx, ny):
                    return True
            self.rx = min(sw - 1, max(0, nx))
            self.ry = min(sh - 1, max(0, ny))
            self._queue_move()
            return True

    def _check_edge(self, x, y):
        if not self.layout.edge_crossing or self._buttons_down or not self.layout.placements:
            self._last_local = (x, y)
            return
        last = self._last_local
        self._last_local = (x, y)
        mon = self.layout.monitor_at(x, y)
        if mon is None:
            # Unclipped hook coordinates past the desktop: probe right where the cursor wants to go.
            probe = (x, y)
            base = self.layout.monitor_at(*(last or (x, y))) or (self.layout.monitors[0] if self.layout.monitors else None)
            if base is not None:
                x = min(max(x, base.x), base.right - 1)
                y = min(max(y, base.y), base.bottom - 1)
        else:
            dx = x - last[0] if last else 0
            dy = y - last[1] if last else 0
            probe = None
            # A clipped push into the edge repeats the same point; sliding along the edge does not count.
            still = dx == 0 and dy == 0
            if x >= mon.right - 1 and (dx > 0 or still):
                probe = (mon.right + 1, y)
            elif x <= mon.x and (dx < 0 or still):
                probe = (mon.x - 2, y)
            elif y >= mon.bottom - 1 and (dy > 0 or still):
                probe = (x, mon.bottom + 1)
            elif y <= mon.y and (dy < 0 or still):
                probe = (x, mon.y - 2)
            if probe is None or self.layout.monitor_at(*probe) is not None:
                self._edge_hits = 0
                return
        device_id = self.layout.device_at(*probe)
        if device_id is None or not self.registry.is_online(device_id):
            self._edge_hits = 0
            return
        dev = self.registry.get(device_id)
        if not dev or not dev.get("caps", {}).get("control"):
            return
        self._edge_hits += 1
        if self._edge_hits >= self.EDGE_PUSHES:
            self.switch_to(device_id, entry=probe)

    def _leave_device(self, dev, nx, ny):
        rect = self.layout.placements.get(self.target)
        if rect is None:
            return False
        sw, sh = dev["screen_w"], dev["screen_h"]
        lx = rect.x + nx / sw * rect.w
        ly = rect.y + ny / sh * rect.h
        # Step one layout pixel past the edge we crossed.
        if nx < 0:
            lx = rect.x - 1
        elif nx >= sw:
            lx = rect.right + 1
        if ny < 0:
            ly = rect.y - 1
        elif ny >= sh:
            ly = rect.bottom + 1
        kind, what = self.layout.target_at(lx, ly, exclude=self.target)
        if kind == "monitor":
            ex = min(max(lx, what.x + 2), what.right - 3)
            ey = min(max(ly, what.y + 2), what.bottom - 3)
            self.switch_to(None, entry=(ex, ey))
            return True
        if kind == "device" and self.registry.is_online(what):
            self.switch_to(what, entry=(lx, ly))
            return True
        return False

    def on_mouse_button(self, button, down):
        with self._lock:
            if self.target is None:
                if down:
                    self._buttons_down.add(("local", button))
                else:
                    self._buttons_down.discard(("local", button))
                return False
            if down:
                self._buttons_down.add(button)
            else:
                if ("local", button) in self._buttons_down:
                    # Pressed on the PC before crossing over: let Windows see the release.
                    self._buttons_down.discard(("local", button))
                    return False
                self._buttons_down.discard(button)
            self._flush_move()
            self._enqueue(self.target, self._button_payload(button, down))
            return True

    def on_mouse_wheel(self, dy_notches, dx_notches=0.0):
        with self._lock:
            if self.target is None:
                return False
            self._flush_move()
            self._enqueue(self.target, {"type": "remote_input", "ev": "wheel", "dy": dy_notches, "dx": dx_notches,
                                        "x": int(self.rx), "y": int(self.ry)})
            return True

    def _button_payload(self, button, down):
        return {"type": "remote_input", "ev": "button", "button": button, "down": bool(down),
                "x": int(self.rx), "y": int(self.ry)}

    # --- keyboard ------------------------------------------------------
    HOTKEY_NEXT, HOTKEY_PREV, HOTKEY_HOME = 0x27, 0x25, 0x24

    def on_key(self, vk, down):
        """Returns True to suppress the key from Windows."""
        with self._lock:
            mod = _modifier_name(vk)
            if mod:
                (self._mods.add if down else self._mods.discard)(mod)
                (self._held.add if down else self._held.discard)(vk)
            # Left Alt only: AltGr arrives as Ctrl + right Alt and types @, € and friends.
            hotkey_mods = 0xA4 in self._held and (self._held & {0xA2, 0xA3, 0x11})
            if down and hotkey_mods and not mod and self._hotkey(vk):
                self._key_dest[vk] = "hotkey"
                return True
            if not down and self._key_dest.get(vk) == "hotkey":
                del self._key_dest[vk]
                return True
            if down:
                dest = self.target
                if dest is not None and not self.enabled:
                    dest = None
                # Auto-repeat keeps going wherever the first press went.
                dest = self._key_dest.setdefault(vk, dest)
            else:
                dest = self._key_dest.pop(vk, self.target)
            if dest is None:
                return False
            self._send_key(dest, vk, down)
            return True

    def _hotkey(self, vk):
        if vk == self.HOTKEY_HOME:
            self.switch_to(None)
            return True
        if vk in (self.HOTKEY_NEXT, self.HOTKEY_PREV):
            targets = self.cycle_targets()
            if len(targets) <= 1 and self.target is None:
                return False
            try:
                i = targets.index(self.target)
            except ValueError:
                i = 0
            step = 1 if vk == self.HOTKEY_NEXT else -1
            self.switch_to(targets[(i + step) % len(targets)])
            return True
        if 0x31 <= vk <= 0x39:
            targets = self.cycle_targets()
            n = vk - 0x31
            if n < len(targets):
                self.switch_to(targets[n])
                return True
        return False

    def _meta_state(self):
        meta = 0
        if "shift" in self._mods:
            meta |= META_SHIFT
        if "alt" in self._mods:
            meta |= META_ALT
        if "ctrl" in self._mods:
            meta |= META_CTRL
        if "win" in self._mods:
            meta |= META_META
        return meta

    def _send_key(self, device_id, vk, down):
        code = VK_TO_ANDROID.get(vk)
        if code is None:
            return
        shift = "shift" in self._mods
        char = None
        if down and not ({"ctrl", "alt", "win"} & self._mods):
            char = vk_to_char(vk, shift=shift)
        self._flush_move()
        self._enqueue(device_id, {"type": "remote_input", "ev": "key", "code": code, "down": bool(down),
                                  "meta": self._meta_state(), "char": char})

    # --- sending -------------------------------------------------------
    def _queue_move(self, force=False):
        self._pending_move = (self.target, int(self.rx), int(self.ry))
        now = time.monotonic()
        if force or now - self._last_move_sent >= self.MOVE_INTERVAL:
            self._flush_move()

    def _flush_move(self):
        pending, self._pending_move = self._pending_move, None
        if pending and pending[0] is not None:
            self._last_move_sent = time.monotonic()
            self._enqueue(pending[0], {"type": "remote_input", "ev": "move", "x": pending[1], "y": pending[2]})

    def _enqueue(self, device_id, payload):
        try:
            self._queue.put_nowait((device_id, payload))
        except queue.Full:
            pass

    def _send_loop(self):
        while True:
            try:
                device_id, payload = self._queue.get(timeout=self.MOVE_INTERVAL)
            except queue.Empty:
                with self._lock:
                    if self._pending_move:
                        self._flush_move()
                continue
            self.registry.send(device_id, payload)

    def _watch_loop(self):
        while True:
            time.sleep(0.5)
            target = self.target
            if target is not None:
                dev = self.registry.get(target)
                if not dev or time.monotonic() - dev.get("last_seen", NEVER) > self.LINK_TIMEOUT:
                    self.switch_to(None)

    def status(self):
        dev = self.registry.get(self.target) if self.target else None
        return {"target": self.target, "target_name": dev.get("name") if dev else None}


def _modifier_name(vk):
    if vk in (0x10, 0xA0, 0xA1):
        return "shift"
    if vk in (0x11, 0xA2, 0xA3):
        return "ctrl"
    if vk in (0x12, 0xA4, 0xA5):
        return "alt"
    if vk in (0x5B, 0x5C):
        return "win"
    return None


# ----------------------------------------------------------------------
# Windows hooks
# ----------------------------------------------------------------------
WM_MOUSEMOVE, WM_LBUTTONDOWN, WM_LBUTTONUP = 0x200, 0x201, 0x202
WM_RBUTTONDOWN, WM_RBUTTONUP, WM_MBUTTONDOWN, WM_MBUTTONUP = 0x204, 0x205, 0x207, 0x208
WM_MOUSEWHEEL, WM_MOUSEHWHEEL = 0x20A, 0x20E
WM_KEYDOWN, WM_KEYUP, WM_SYSKEYDOWN, WM_SYSKEYUP = 0x100, 0x101, 0x104, 0x105
LLMHF_INJECTED, LLKHF_INJECTED = 0x01, 0x10

_BUTTON_MSGS = {
    WM_LBUTTONDOWN: ("left", True), WM_LBUTTONUP: ("left", False),
    WM_RBUTTONDOWN: ("right", True), WM_RBUTTONUP: ("right", False),
    WM_MBUTTONDOWN: ("middle", True), WM_MBUTTONUP: ("middle", False),
}


def windows_monitors():
    """Monitor rectangles in virtual-screen pixels (Windows only)."""
    if os.name != "nt":
        return []
    import ctypes
    from ctypes import wintypes
    rects = []

    class RECT(ctypes.Structure):
        _fields_ = [("left", wintypes.LONG), ("top", wintypes.LONG), ("right", wintypes.LONG), ("bottom", wintypes.LONG)]

    proc_t = ctypes.WINFUNCTYPE(ctypes.c_int, wintypes.HMONITOR, wintypes.HDC, ctypes.POINTER(RECT), wintypes.LPARAM)

    def _cb(_hmon, _hdc, rect, _data):
        r = rect.contents
        rects.append(Rect(r.left, r.top, r.right - r.left, r.bottom - r.top))
        return 1

    try:
        ctypes.windll.user32.EnumDisplayMonitors(None, None, proc_t(_cb), 0)
    except Exception:
        return []
    return rects


def windows_set_cursor(x, y):
    if os.name == "nt":
        try:
            import ctypes
            ctypes.windll.user32.SetCursorPos(int(x), int(y))
        except Exception:
            pass


class WindowsInputHooks:
    """Low-level keyboard and mouse hooks that feed ``InputShare``.

    Injected events (including the phone driving this PC through pynput) pass through
    untouched, so phone -> PC control keeps working while the hooks are installed.
    """

    def __init__(self, share):
        self.share = share
        self._mouse = None
        self._keyboard = None

    @property
    def available(self):
        return os.name == "nt"

    def start(self):
        if not self.available or self._mouse:
            return False
        try:
            from pynput import keyboard, mouse
        except Exception:
            return False
        share = self.share

        def mouse_filter(msg, data):
            if data.flags & LLMHF_INJECTED:
                return True
            suppress = False
            if msg == WM_MOUSEMOVE:
                suppress = share.on_mouse_move(data.pt.x, data.pt.y)
            elif msg in _BUTTON_MSGS:
                button, down = _BUTTON_MSGS[msg]
                suppress = share.on_mouse_button(button, down)
            elif msg in (WM_MOUSEWHEEL, WM_MOUSEHWHEEL):
                delta = ((data.mouseData >> 16) & 0xFFFF)
                if delta >= 0x8000:
                    delta -= 0x10000
                notches = delta / 120.0
                if msg == WM_MOUSEWHEEL:
                    suppress = share.on_mouse_wheel(notches, 0.0)
                else:
                    suppress = share.on_mouse_wheel(0.0, notches)
            if suppress:
                self._mouse.suppress_event()
            return True

        def key_filter(msg, data):
            if data.flags & LLKHF_INJECTED:
                return True
            if msg in (WM_KEYDOWN, WM_SYSKEYDOWN):
                down = True
            elif msg in (WM_KEYUP, WM_SYSKEYUP):
                down = False
            else:
                return True
            if share.on_key(int(data.vkCode), down):
                self._keyboard.suppress_event()
            return True

        self._mouse = mouse.Listener(win32_event_filter=mouse_filter)
        self._keyboard = keyboard.Listener(win32_event_filter=key_filter)
        self._mouse.start()
        self._keyboard.start()
        return True

    def stop(self):
        for listener in (self._mouse, self._keyboard):
            if listener:
                try:
                    listener.stop()
                except Exception:
                    pass
        self._mouse = self._keyboard = None


def _media_dirs():
    base = _data_dir()
    dirs = {"videos": base / "Videos", "audios": base / "Audios", "snapshots": base / "Snapshots"}
    for d in dirs.values():
        try:
            d.mkdir(parents=True, exist_ok=True)
        except OSError:
            pass
    return dirs


def family_key():
    """One secret shared by every phone and tablet paired with this PC, so they can find
    and trust each other on the network without scanning codes between them."""
    import secrets
    path = _data_dir() / "family.json"
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
        if len(data.get("key", "")) == 64 and data.get("id"):
            return data
    except (OSError, ValueError):
        pass
    data = {"id": secrets.token_hex(6), "key": secrets.token_hex(32)}
    try:
        path.write_text(json.dumps(data), encoding="utf-8")
    except OSError:
        pass
    return data


class Ecosystem:
    """Glue owned by ``Server``: registry, layout, and input sharing."""

    def __init__(self, pc_name="Laptop"):
        self.registry = DeviceRegistry()
        self.layout = Layout()
        self.layout.set_monitors(windows_monitors())
        self.share = InputShare(self.registry, self.layout, set_cursor=windows_set_cursor,
                                get_park=self._park_point, pc_name=pc_name)
        self.hooks = WindowsInputHooks(self.share)
        self.sync = SyncHub(self.registry)
        self.streams = StreamHub(self.registry)
        self.sync.extra_handlers.append(self._handle_streams)
        self.library = LibrarySync(self.registry, self.sync, _media_dirs)
        self.sync.extra_handlers.append(self.library.handle)
        self.registry.on_change = self._on_devices_changed
        self.on_devices_changed = None

    def _park_point(self):
        m = self.layout.monitors[0] if self.layout.monitors else Rect(0, 0, 1920, 1080)
        return int(m.x + m.w / 2), int(m.y + m.h / 2)

    def start(self):
        if self.sync.http is None:
            self.sync.http = TransferHttpServer(self.sync)
            if not self.sync.http.start():
                self.sync.http = None
        return self.hooks.start()

    def stop(self):
        self.share.release()
        self.hooks.stop()
        if self.sync.http:
            self.sync.http.stop()
            self.sync.http = None

    def refresh_monitors(self, rects=None):
        self.layout.set_monitors(rects if rects is not None else windows_monitors())

    def handle_hello(self, data, respond):
        device_id = self.registry.hello(data, respond)
        if device_id:
            dev = self.registry.get(device_id)
            if device_id not in self.layout.placements and dev.get("caps", {}).get("control"):
                self.layout.placements[device_id] = self.layout.default_placement(dev)
                self.layout.save()
            else:
                self.layout.resize_for(device_id, dev)
            group = family_key()
            respond({"type": "device_hello_ack", "pc_name": self.share.pc_name,
                     "controlling": self.share.target == device_id,
                     "group_id": group["id"], "group_key": group["key"]})
        return device_id

    def handle_release_request(self, data):
        session = str(data.get("client_session") or "")
        target = self.share.target
        if target and self.registry._session_to_device.get(session) == target:
            self.share.release()

    def _handle_streams(self, cmd_type, data, respond):
        return self.streams.handle(cmd_type, data, self.sync._sender_id(data))

    def _on_devices_changed(self):
        threading.Thread(target=self.sync.announce_devices, daemon=True).start()
        cb = self.on_devices_changed
        if cb:
            cb()
