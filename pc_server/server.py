"""
BT Mouse & Keyboard - PC Companion Server
Ultra-low latency UDP input receiver with auto-discovery beacon and tablet writing pad support.
Works on Windows, macOS, and Linux.
"""

import socket
import json
import threading
import time
import sys
import os
import traceback
import base64
import asyncio
import hmac
import hashlib
import secrets
import queue
import re
import shutil
from pathlib import Path


def _pairing_pin_config_path():
    """Return the per-user path used to keep the PC pairing PIN stable."""
    local_app_data = os.environ.get("LOCALAPPDATA")
    if local_app_data:
        config_dir = Path(local_app_data) / "BTMouse"
    else:
        config_dir = Path.home() / ".config" / "bt-mouse"
    return config_dir / "pairing.json"


PAIRING_PIN_PATH = _pairing_pin_config_path()
QUICK_APPS_PATH = PAIRING_PIN_PATH.with_name("quick_apps.json")
MEDIA_PREVIEW_REGION_PATH = PAIRING_PIN_PATH.with_name("media_preview_region.json")


def load_media_preview_region():
    """Return the explicitly selected screen rectangle, or None if not configured."""
    try:
        region = json.loads(MEDIA_PREVIEW_REGION_PATH.read_text(encoding="utf-8"))
        x, y, width, height = (int(region[key]) for key in ("x", "y", "width", "height"))
        if width < 80 or height < 60 or width > 8192 or height > 8192:
            return None
        return {"x": x, "y": y, "width": width, "height": height}
    except (OSError, ValueError, TypeError, KeyError):
        return None


def save_media_preview_region(region):
    clean = {key: int(region[key]) for key in ("x", "y", "width", "height")}
    if clean["width"] < 80 or clean["height"] < 60:
        raise ValueError("Select a region at least 80 by 60 pixels")
    MEDIA_PREVIEW_REGION_PATH.parent.mkdir(parents=True, exist_ok=True)
    temporary = MEDIA_PREVIEW_REGION_PATH.with_suffix(".tmp")
    temporary.write_text(json.dumps(clean), encoding="utf-8")
    os.replace(temporary, MEDIA_PREVIEW_REGION_PATH)
    return clean


def capture_selected_media_preview():
    """Capture only the rectangle explicitly selected in the desktop companion."""
    region = load_media_preview_region()
    if not region:
        return ""
    try:
        from PIL import Image, ImageGrab
        from io import BytesIO
        x, y = region["x"], region["y"]
        image = ImageGrab.grab(bbox=(x, y, x + region["width"], y + region["height"]))
        image.thumbnail((480, 270), Image.Resampling.LANCZOS)
        stream = BytesIO()
        image.save(stream, format="JPEG", quality=48, optimize=True)
        return base64.b64encode(stream.getvalue()).decode("ascii")
    except Exception as exc:
        print(f"[-] Selected media preview capture failed: {exc}")
        return ""


def prepare_recording_storage(directory, retention_days=0, max_storage_mb=0, min_free_mb=512,
                              protected_paths=()):
    """Apply user-selected retention without touching active files; reject new capture below free-space floor."""
    folder = Path(directory)
    folder.mkdir(parents=True, exist_ok=True)
    protected = {os.path.normcase(os.path.realpath(str(path))) for path in protected_paths if path}
    files = []
    for path in folder.iterdir():
        try:
            if path.is_file() and os.path.normcase(os.path.realpath(str(path))) not in protected:
                stat = path.stat()
                files.append((path, stat.st_mtime, stat.st_size))
        except OSError:
            continue
    files.sort(key=lambda item: item[1])
    now = time.time()
    if retention_days > 0:
        cutoff = now - retention_days * 86400
        remaining = []
        for entry in files:
            if entry[1] < cutoff:
                try:
                    entry[0].unlink()
                except OSError:
                    remaining.append(entry)
            else:
                remaining.append(entry)
        files = remaining
    if max_storage_mb > 0:
        total = sum(entry[2] for entry in files)
        for entry in files:
            if total <= max_storage_mb * 1024 * 1024:
                break
            try:
                entry[0].unlink()
                total -= entry[2]
            except OSError:
                continue
    try:
        free_mb = shutil.disk_usage(folder).free / (1024 * 1024)
    except OSError as exc:
        return False, f"Could not check recording storage: {exc}"
    if min_free_mb > 0 and free_mb < min_free_mb:
        return False, f"Recording skipped: only {free_mb:.0f} MB is free; the configured minimum is {min_free_mb} MB"
    return True, ""


def save_quick_apps(apps):
    """Persist curated Start Menu shortcuts; arbitrary commands are never accepted."""
    roots = [
        os.path.realpath(os.path.expandvars(r"%ProgramData%\Microsoft\Windows\Start Menu\Programs")),
        os.path.realpath(os.path.expandvars(r"%AppData%\Microsoft\Windows\Start Menu\Programs")),
    ]
    clean_apps = []
    seen = set()
    for app in apps:
        name = str(app.get("name", "")).strip()[:60]
        path = os.path.realpath(str(app.get("path", "")).strip())
        if not name or not path.lower().endswith(".lnk") or path.lower() in seen:
            continue
        try:
            if not any(os.path.commonpath((path, root)).lower() == root.lower() for root in roots):
                continue
        except (OSError, ValueError):
            continue
        if not os.path.isfile(path):
            continue
        clean_apps.append({"name": name, "path": path})
        seen.add(path.lower())
    QUICK_APPS_PATH.parent.mkdir(parents=True, exist_ok=True)
    temp_path = QUICK_APPS_PATH.with_suffix(".tmp")
    temp_path.write_text(json.dumps(clean_apps, ensure_ascii=False, indent=2), encoding="utf-8")
    if os.name != "nt":
        os.chmod(temp_path, 0o600)
    os.replace(temp_path, QUICK_APPS_PATH)
    return clean_apps


def load_quick_apps(available_apps=None):
    """Load saved quick apps, seeding only defaults actually present on this PC."""
    try:
        saved = json.loads(QUICK_APPS_PATH.read_text(encoding="utf-8"))
        return save_quick_apps(saved if isinstance(saved, list) else [])
    except FileNotFoundError:
        pass
    except (OSError, ValueError, TypeError):
        pass

    available_apps = available_apps or []
    preferred_names = ("chrome", "onenote", "spotify", "terminal", "task manager", "calculator")
    seeded = []
    for preferred in preferred_names:
        match = next((app for app in available_apps if app.get("name", "").strip().lower() == preferred), None)
        if match:
            seeded.append({"name": match["name"], "path": match["path"]})
    return save_quick_apps(seeded)


def save_pairing_pin(pin):
    pin = str(pin).strip()
    if len(pin) != 6 or not pin.isdigit():
        raise ValueError("Pairing PIN must contain exactly six digits")
    PAIRING_PIN_PATH.parent.mkdir(parents=True, exist_ok=True)
    temp_path = PAIRING_PIN_PATH.with_suffix(".tmp")
    temp_path.write_text(json.dumps({"pin": pin}), encoding="utf-8")
    if os.name != "nt":
        os.chmod(temp_path, 0o600)
    os.replace(temp_path, PAIRING_PIN_PATH)


def load_or_create_pairing_pin():
    try:
        saved = json.loads(PAIRING_PIN_PATH.read_text(encoding="utf-8"))
        pin = str(saved.get("pin", "")).strip()
        if len(pin) == 6 and pin.isdigit():
            return pin
    except (OSError, ValueError, TypeError):
        pass

    pin = f"{secrets.randbelow(1_000_000):06d}"
    try:
        save_pairing_pin(pin)
    except OSError as e:
        print(f"[!] Could not persist pairing PIN: {e}")
    return pin

# Prevent crash when running as a windowed GUI application without a console attached
if sys.stdout is None:
    sys.stdout = open(os.devnull, "w")
if sys.stderr is None:
    sys.stderr = open(os.devnull, "w")

# Desktop attachment helper for Windows to ensure background / service tasks can interact with the active user desktop
def ensure_interactive_desktop():
    if sys.platform == "win32":
        try:
            import ctypes
            u = ctypes.windll.user32
            hdesk = u.OpenDesktopW("default", 0, False, 0x01FF)
            if hdesk:
                u.SetThreadDesktop(hdesk)
        except Exception:
            pass

ensure_interactive_desktop()

# Native Windows mouse input helper for zero-latency, hardware-accelerated movement
IS_WINDOWS = sys.platform == "win32"
if IS_WINDOWS:
    import ctypes
    u32 = ctypes.windll.user32

    MOUSEEVENTF_MOVE = 0x0001
    MOUSEEVENTF_LEFTDOWN = 0x0002
    MOUSEEVENTF_LEFTUP = 0x0004
    MOUSEEVENTF_RIGHTDOWN = 0x0008
    MOUSEEVENTF_RIGHTUP = 0x0010
    MOUSEEVENTF_MIDDLEDOWN = 0x0020
    MOUSEEVENTF_MIDDLEUP = 0x0040
    MOUSEEVENTF_WHEEL = 0x0800
    MOUSEEVENTF_HWHEEL = 0x1000
    MOUSEEVENTF_ABSOLUTE = 0x8000

try:
    from pynput.mouse import Controller as MouseController, Button
    from pynput.keyboard import Controller as KeyboardController, Key, KeyCode
    HAS_PYNPUT = True
except ImportError:
    HAS_PYNPUT = False
    print("\n[!] 'pynput' library not found. Installing via pip is recommended:")
    print("    pip install -r requirements.txt\n")

# HID Scancode mapping to pynput Key
KEY_MAP = {}
if HAS_PYNPUT:
    KEY_MAP = {
        0x28: Key.enter,
        0x29: Key.esc,
        0x2A: Key.backspace,
        0x2B: Key.tab,
        0x2C: Key.space,
        0x3A: Key.f1,
        0x3B: Key.f2,
        0x3C: Key.f3,
        0x3D: Key.f4,
        0x3E: Key.f5,
        0x3F: Key.f6,
        0x40: Key.f7,
        0x41: Key.f8,
        0x42: Key.f9,
        0x43: Key.f10,
        0x44: Key.f11,
        0x45: Key.f12,
        0x49: Key.insert,
        0x4A: Key.home,
        0x4B: Key.page_up,
        0x4C: Key.delete,
        0x4D: Key.end,
        0x4E: Key.page_down,
        0x4F: Key.right,
        0x50: Key.left,
        0x51: Key.down,
        0x52: Key.up,
        # Numpad
        0x53: Key.num_lock,
        0x54: KeyCode.from_vk(0x6F),  # Keypad divide
        0x55: KeyCode.from_vk(0x6A),  # Keypad multiply
        0x56: KeyCode.from_vk(0x6D),  # Keypad subtract
        0x57: KeyCode.from_vk(0x6B),  # Keypad add
        0x58: Key.enter,
        0x59: KeyCode.from_vk(0x61),
        0x5A: KeyCode.from_vk(0x62),
        0x5B: KeyCode.from_vk(0x63),
        0x5C: KeyCode.from_vk(0x64),
        0x5D: KeyCode.from_vk(0x65),
        0x5E: KeyCode.from_vk(0x66),
        0x5F: KeyCode.from_vk(0x67),
        0x60: KeyCode.from_vk(0x68),
        0x61: KeyCode.from_vk(0x69),
        0x62: KeyCode.from_vk(0x60),
        0x63: KeyCode.from_vk(0x6E),
    }


class MediaController:
    """Windows native media transport & master volume controller"""
    def __init__(self, fallback_media_func=None):
        self.fallback_media_func = fallback_media_func
        self.has_winrt = False
        self.has_pycaw = False
        self._artwork_cache_key = None
        self._artwork_cache_value = ""
        self.last_state = {
            "has_media": False,
            "title": "",
            "artist": "",
            "album": "",
            "status": "Stopped",
            "position": 0.0,
            "duration": 0.0,
            "volume": 0.5,
            "muted": False
        }
        self._init_backends()

    def _init_backends(self):
        if IS_WINDOWS:
            try:
                import winrt.windows.media.control as wmc
                self.wmc = wmc
                self.has_winrt = True
            except Exception:
                self.has_winrt = False

            try:
                from pycaw.pycaw import AudioUtilities
                self.audio_utilities = AudioUtilities
                self.has_pycaw = True
            except Exception:
                self.has_pycaw = False

    def get_volume(self):
        if self.has_pycaw:
            initialized = False
            try:
                import comtypes
                comtypes.CoInitialize()
                initialized = True
                speakers = self.audio_utilities.GetSpeakers()
                if speakers is None:
                    raise RuntimeError("Windows has no default playback device.")
                vol = speakers.EndpointVolume
                level, muted = vol.GetMasterVolumeLevelScalar(), bool(vol.GetMute())
                self.last_state["volume_available"] = True
                self.last_state["volume_error"] = ""
                return level, muted
            except Exception as e:
                self.last_state["volume_available"] = False
                self.last_state["volume_error"] = str(e)
            finally:
                if initialized:
                    comtypes.CoUninitialize()
        else:
            self.last_state["volume_available"] = False
            self.last_state["volume_error"] = "Windows audio control is unavailable."
        return self.last_state.get("volume", 0.5), self.last_state.get("muted", False)

    def set_volume(self, scalar):
        if self.has_pycaw:
            initialized = False
            try:
                import comtypes
                comtypes.CoInitialize()
                initialized = True
                speakers = self.audio_utilities.GetSpeakers()
                if speakers is None:
                    raise RuntimeError("Windows has no default playback device.")
                vol = speakers.EndpointVolume
                val = max(0.0, min(1.0, float(scalar)))
                vol.SetMasterVolumeLevelScalar(val, None)
                self.last_state["volume"] = round(vol.GetMasterVolumeLevelScalar(), 3)
                self.last_state["volume_available"] = True
                self.last_state["volume_error"] = ""
                return True
            except Exception as e:
                self.last_state["volume_available"] = False
                self.last_state["volume_error"] = str(e)
            finally:
                if initialized:
                    comtypes.CoUninitialize()
        return False

    def set_mute(self, muted):
        if self.has_pycaw:
            initialized = False
            try:
                import comtypes
                comtypes.CoInitialize()
                initialized = True
                speakers = self.audio_utilities.GetSpeakers()
                if speakers is None:
                    raise RuntimeError("Windows has no default playback device.")
                vol = speakers.EndpointVolume
                vol.SetMute(1 if muted else 0, None)
                self.last_state["muted"] = bool(vol.GetMute())
                self.last_state["is_muted"] = self.last_state["muted"]
                self.last_state["volume_available"] = True
                self.last_state["volume_error"] = ""
                return True
            except Exception as e:
                self.last_state["volume_available"] = False
                self.last_state["volume_error"] = str(e)
            finally:
                if initialized:
                    comtypes.CoUninitialize()
        return False

    async def _get_media_artwork(self, props, cache_key, max_encoded_bytes):
        if cache_key == self._artwork_cache_key:
            return self._artwork_cache_value
        self._artwork_cache_key = cache_key
        self._artwork_cache_value = ""
        try:
            thumbnail = props.thumbnail
            if not thumbnail:
                return ""
            stream = await thumbnail.open_read_async()
            size = int(stream.size)
            if size <= 0 or size > 4 * 1024 * 1024:
                return ""
            from winrt.windows.storage.streams import DataReader
            reader = DataReader(stream.get_input_stream_at(0))
            loaded = int(await reader.load_async(size))
            raw = bytearray(loaded)
            reader.read_bytes(raw)
            reader.close()
            stream.close()

            from io import BytesIO
            from PIL import Image
            image = Image.open(BytesIO(raw)).convert("RGB")
            image.thumbnail((192, 192), Image.Resampling.LANCZOS)
            for quality in (70, 55, 40, 28):
                output = BytesIO()
                image.save(output, format="JPEG", quality=quality, optimize=True)
                encoded = base64.b64encode(output.getvalue()).decode("ascii")
                if len(encoded) <= max_encoded_bytes:
                    self._artwork_cache_value = encoded
                    return encoded
        except Exception as e:
            print(f"[-] Media artwork unavailable: {e}")
        return ""

    def _detect_browser_audio_session(self):
        """Recognize active browser audio even when it does not publish an SMTC session."""
        if not IS_WINDOWS or not self.has_pycaw:
            return None
        browser_names = {"chrome.exe", "msedge.exe", "firefox.exe", "brave.exe", "opera.exe", "vivaldi.exe"}
        initialized = False
        try:
            import comtypes
            comtypes.CoInitialize()
            initialized = True
            for session in self.audio_utilities.GetAllSessions():
                process = getattr(session, "Process", None)
                if process and process.name().lower() in browser_names and int(session.State) == 1:
                    return process.name().removesuffix(".exe"), process.pid
        except Exception:
            return None
        finally:
            if initialized:
                try:
                    comtypes.CoUninitialize()
                except Exception:
                    pass
        return None

    @staticmethod
    def _get_process_window_title(process_id):
        """Return the browser window title only; never capture the page or screen."""
        if not IS_WINDOWS or not process_id:
            return ""
        try:
            import ctypes
            from ctypes import wintypes
            user32 = ctypes.windll.user32
            titles = []
            callback_type = ctypes.WINFUNCTYPE(wintypes.BOOL, wintypes.HWND, wintypes.LPARAM)

            def _visit(hwnd, _lparam):
                if not user32.IsWindowVisible(hwnd):
                    return True
                owner = wintypes.DWORD()
                user32.GetWindowThreadProcessId(hwnd, ctypes.byref(owner))
                if owner.value != process_id:
                    return True
                length = min(512, user32.GetWindowTextLengthW(hwnd))
                if length:
                    title = ctypes.create_unicode_buffer(length + 1)
                    user32.GetWindowTextW(hwnd, title, length + 1)
                    if title.value.strip():
                        titles.append(title.value.strip())
                return not titles

            user32.EnumWindows(callback_type(_visit), 0)
            return titles[0] if titles else ""
        except Exception:
            return ""

    def get_media_state(self, include_preview=False, max_artwork_bytes=12 * 1024):
        vol, muted = self.get_volume()
        self.last_state["volume"] = round(vol, 2)
        self.last_state["muted"] = muted
        self.last_state["is_muted"] = muted
        # Rebuild playback identity for every poll; a lost SMTC/audio session
        # must not leave the previous track looking active indefinitely.
        self.last_state.update({
            "has_media": False, "title": "", "artist": "", "album": "",
            "status": "NoMedia", "position": 0.0, "duration": 0.0,
            "artwork": ""
        })

        if self.has_winrt:
            try:
                import asyncio

                async def _query():
                    mgr = await self.wmc.GlobalSystemMediaTransportControlsSessionManager.request_async()
                    session = mgr.get_current_session()
                    sessions = list(mgr.get_sessions() or [])
                    candidates = ([session] if session else []) + [s for s in sessions if s != session]
                    # Prefer an actually Playing session, then Paused. A stale
                    # current session must not hide an active player.
                    active = []
                    for candidate in candidates:
                        try:
                            playback = candidate.get_playback_info()
                            status = int(playback.playback_status) if playback else 0
                            if status in (4, 5):
                                active.append((status, candidate))
                        except Exception:
                            continue
                    if active:
                        session = next((item for status, item in active if status == 5), active[0][1])
                    else:
                        # Do not report an idle current-session pointer as the
                        # media that is currently playing in the browser.
                        session = None

                    if session:
                        props = await session.try_get_media_properties_async()
                        info = session.get_playback_info()
                        timeline = session.get_timeline_properties()
                        status_map = {0: "Closed", 1: "Opened", 2: "Changing", 3: "Stopped", 4: "Paused", 5: "Playing"}
                        status_str = status_map.get(int(info.playback_status), "Unknown")
                        pos_sec = timeline.position.total_seconds() if timeline and timeline.position else 0.0
                        end_sec = timeline.end_time.total_seconds() if timeline and timeline.end_time else 0.0
                        state = {
                            "has_media": bool(props and (props.title or props.artist or status_str in ("Playing", "Paused"))),
                            "title": (props.title or "Unknown Track") if props else "",
                            "artist": (props.artist or "Unknown Artist") if props else "",
                            "album": (props.album_title or "") if props else "",
                            "status": status_str,
                            "position": round(pos_sec, 1),
                            "duration": round(end_sec, 1),
                            "volume": round(vol, 2),
                            "muted": muted,
                            "is_muted": muted
                        }
                        if include_preview:
                            cache_key = (props.title, props.artist, props.album_title) if props else ()
                            state["artwork"] = await self._get_media_artwork(props, cache_key, max_artwork_bytes) if props else ""
                        return state
                    return None

                res = asyncio.run(_query())
                if res:
                    self.last_state.update(res)
                    return self.last_state
            except Exception:
                pass
        if not self.last_state.get("has_media"):
            browser_audio = self._detect_browser_audio_session()
            if browser_audio:
                browser_name, process_id = browser_audio
                window_title = self._get_process_window_title(process_id)
                self.last_state.update({
                    "has_media": True,
                    "title": window_title or f"Playing in {browser_name}",
                    "artist": "Browser audio",
                    "album": "",
                    "status": "Playing",
                    "position": 0.0,
                    "duration": 0.0,
                    "artwork": "",
                })
        return self.last_state

    async def _active_smtc_session(self):
        if not self.has_winrt:
            return None
        manager = await self.wmc.GlobalSystemMediaTransportControlsSessionManager.request_async()
        sessions = list(manager.get_sessions() or [])
        current = manager.get_current_session()
        if current and current not in sessions:
            sessions.insert(0, current)
        active = []
        for session in sessions:
            try:
                playback = session.get_playback_info()
                status = int(playback.playback_status) if playback else 0
                if status in (4, 5):
                    active.append((status, session))
            except Exception:
                continue
        return next((session for status, session in active if status == 5), active[0][1] if active else None)

    def seek(self, seconds):
        if self.has_winrt:
            try:
                import asyncio
                import datetime

                async def _seek():
                    session = await self._active_smtc_session()
                    if session:
                        delta = datetime.timedelta(seconds=float(seconds))
                        await session.try_change_playback_position_async(delta)
                        return True
                    return False

                return asyncio.run(_seek())
            except Exception:
                pass
        return False

    def toggle_play_pause(self):
        handled = False
        if self.has_winrt:
            try:
                import asyncio

                async def _toggle():
                    session = await self._active_smtc_session()
                    if session:
                        return bool(await session.try_toggle_play_pause_async())
                    return False

                if asyncio.run(_toggle()):
                    handled = True
            except Exception:
                handled = False

        if not handled:
            # Universal Windows virtual key fallback: VK_MEDIA_PLAY_PAUSE (0xB3)
            if IS_WINDOWS:
                u32.keybd_event(0xB3, 0, 0, 0)
                time.sleep(0.01)
                u32.keybd_event(0xB3, 0, 2, 0)
                handled = True
            elif self.fallback_media_func:
                self.fallback_media_func(0xCD)
                handled = True
        return handled

    def next_track(self):
        handled = False
        if self.has_winrt:
            try:
                import asyncio

                async def _next():
                    session = await self._active_smtc_session()
                    if session:
                        return bool(await session.try_skip_next_async())
                    return False

                if asyncio.run(_next()):
                    handled = True
            except Exception:
                handled = False

        if not handled:
            # Universal Windows virtual key fallback: VK_MEDIA_NEXT_TRACK (0xB0)
            if IS_WINDOWS:
                u32.keybd_event(0xB0, 0, 0, 0)
                time.sleep(0.01)
                u32.keybd_event(0xB0, 0, 2, 0)
                handled = True
            elif self.fallback_media_func:
                self.fallback_media_func(0xB5)
                handled = True
        return handled

    def prev_track(self):
        handled = False
        if self.has_winrt:
            try:
                import asyncio

                async def _prev():
                    session = await self._active_smtc_session()
                    if session:
                        return bool(await session.try_skip_previous_async())
                    return False

                if asyncio.run(_prev()):
                    handled = True
            except Exception:
                handled = False

        if not handled:
            # Universal Windows virtual key fallback: VK_MEDIA_PREV_TRACK (0xB1)
            if IS_WINDOWS:
                u32.keybd_event(0xB1, 0, 0, 0)
                time.sleep(0.01)
                u32.keybd_event(0xB1, 0, 2, 0)
                handled = True
            elif self.fallback_media_func:
                self.fallback_media_func(0xB6)
                handled = True
        return handled
        return True


class BluetoothRfcommServer:
    """Windows native Bluetooth RFCOMM (Serial Port Profile) Service.
    Allows any paired mobile phone to communicate seamlessly with the PC companion
    using standard Bluetooth serial without requiring OEM HID emulation permissions.
    """
    def __init__(self, command_handler, on_client_callback=None):
        self.command_handler = command_handler
        self.on_client_callback = on_client_callback
        self.running = False
        self.thread = None
        self.loop = None
        self.provider = None
        self.listener = None

    def start(self):
        if not IS_WINDOWS:
            return
        self.running = True
        self.thread = threading.Thread(target=self._run_thread, daemon=True)
        self.thread.start()

    def _run_thread(self):
        try:
            self.loop = asyncio.new_event_loop()
            asyncio.set_event_loop(self.loop)
            self.loop.run_until_complete(self._async_serve())
        except Exception as e:
            print(f"[-] Bluetooth RFCOMM thread note: {e}")

    async def _async_serve(self):
        try:
            import winrt.windows.devices.bluetooth.rfcomm as rfcomm
            import winrt.windows.networking.sockets as sockets
            import uuid

            srv_uuid = uuid.UUID("00001101-0000-1000-8000-00805F9B34FB")  # SerialPortServiceClass_UUID
            rfcomm_id = rfcomm.RfcommServiceId.from_uuid(srv_uuid)
            self.provider = await rfcomm.RfcommServiceProvider.create_async(rfcomm_id)
            self.listener = sockets.StreamSocketListener()

            def _on_connection(sender, args):
                sock = args.socket
                if self.loop and self.loop.is_running():
                    asyncio.run_coroutine_threadsafe(self._handle_client(sock), self.loop)

            self.listener.add_connection_received(_on_connection)
            await self.listener.bind_service_name_async(self.provider.service_id.as_string())
            self.provider.start_advertising(self.listener)
            print("[*] Bluetooth RFCOMM Service (SPP) Advertising on Windows!")
            while self.running:
                await asyncio.sleep(1.0)
        except Exception as e:
            print(f"[-] Bluetooth RFCOMM service unavailable: {e}")

    async def _handle_client(self, socket):
        import winrt.windows.storage.streams as streams
        remote_name = "Phone"
        try:
            remote_name = str(socket.information.remote_host_name.display_name)
        except Exception:
            pass
        print(f"[+] Bluetooth Client connected: {remote_name}")
        if self.on_client_callback:
            self.on_client_callback(f"Bluetooth: {remote_name}", True)

        reader = streams.DataReader(socket.input_stream)
        reader.input_stream_options = streams.InputStreamOptions.PARTIAL
        writer = streams.DataWriter(socket.output_stream)
        buffer = ""
        outbound = asyncio.Queue(maxsize=32)

        async def _send_responses():
            while True:
                resp_dict = await outbound.get()
                writer.write_string(json.dumps(resp_dict) + "\n")
                await writer.store_async()

        sender_task = asyncio.create_task(_send_responses())

        try:
            while self.running:
                count = await reader.load_async(2048)
                if count == 0:
                    break
                text = reader.read_string(count)
                buffer += text
                while "\n" in buffer:
                    line, buffer = buffer.split("\n", 1)
                    line = line.strip()
                    if line:
                        try:
                            data = json.loads(line)
                            def _respond(resp_dict):
                                def _enqueue_on_loop():
                                    try:
                                        outbound.put_nowait(resp_dict)
                                    except asyncio.QueueFull:
                                        print("[-] Dropped RFCOMM response because its queue is full")
                                if self.loop and self.loop.is_running():
                                    self.loop.call_soon_threadsafe(_enqueue_on_loop)
                                else:
                                    print("[-] Dropped RFCOMM response because its event loop stopped")
                            self.command_handler(data, addr=("Bluetooth", 0), responder=_respond)
                            if sender_task.done():
                                sender_task.result()
                        except Exception:
                            pass
        except Exception:
            pass
        finally:
            try:
                self.command_handler({"type": "disconnect"}, addr=("Bluetooth", 0))
            except Exception as e:
                print(f"[-] Could not release Bluetooth input state: {e}")
            sender_task.cancel()
            try:
                await sender_task
            except asyncio.CancelledError:
                pass
            except Exception as e:
                print(f"[-] RFCOMM response write failed: {e}")
            print(f"[-] Bluetooth Client disconnected: {remote_name}")
            if self.on_client_callback:
                self.on_client_callback(f"Bluetooth: {remote_name}", False)
            try:
                socket.close()
            except Exception:
                pass

    def stop(self):
        self.running = False
        try:
            if self.provider:
                self.provider.stop_advertising()
            if self.listener:
                self.listener.close()
        except Exception:
            pass


class CameraManager:
    """Manages laptop webcam snapshots and video streaming"""
    def __init__(self):
        self.streaming = False
        self.stream_thread = None
        self.stream_capture = None
        self.lock = threading.Lock()
        self.lifecycle_lock = threading.RLock()
        self.generation = 0
        user_data = os.environ.get("LOCALAPPDATA") or os.path.expanduser("~")
        self.recordings_dir = os.path.join(user_data, "BTMouse", "Recordings")
        os.makedirs(self.recordings_dir, exist_ok=True)
        self.auto_record_enabled = False
        self.last_recording_path = None
        self.last_recording_error = ""
        self.recording_policy = {"retention_days": 0, "max_storage_mb": 0, "min_free_mb": 512}
        self.has_cv2 = False
        try:
            import cv2
            self.has_cv2 = True
        except ImportError:
            pass

    def get_snapshot(self, max_width=800, quality=75, max_encoded_bytes=None):
        if not self.has_cv2:
            return None
        try:
            import cv2
            cap = cv2.VideoCapture(0, cv2.CAP_DSHOW)
            if not cap.isOpened():
                cap = cv2.VideoCapture(0)
            if not cap.isOpened():
                return None
            # Ask the camera for a still-sized frame. The live stream uses a
            # separate capture object with its own adaptive preview settings.
            cap.set(cv2.CAP_PROP_FRAME_WIDTH, max_width)
            cap.set(cv2.CAP_PROP_FRAME_HEIGHT, int(max_width * 9 / 16))
            ret, frame = cap.read()
            cap.release()
            if not ret or frame is None:
                return None
            h, w = frame.shape[:2]
            if w > max_width:
                frame = cv2.resize(frame, (max_width, int(h * max_width / w)))
            ret, buf = cv2.imencode('.jpg', frame, [int(cv2.IMWRITE_JPEG_QUALITY), quality])
            if ret:
                if max_encoded_bytes and buf.nbytes > max_encoded_bytes:
                    return None
                return base64.b64encode(buf).decode('utf-8')
        except Exception as e:
            print(f"[-] Camera snapshot error: {e}")
        return None

    def start_stream(self, on_frame_callback, max_width=640, quality=65, max_frame_bytes=None, fps=15,
                     on_stream_end_callback=None):
        with self.lifecycle_lock:
            return self._start_stream(
                on_frame_callback, max_width, quality, max_frame_bytes, fps, on_stream_end_callback
            )

    def _start_stream(self, on_frame_callback, max_width=640, quality=65, max_frame_bytes=None, fps=15,
                      on_stream_end_callback=None):
        if not self.has_cv2:
            return False
        if not self.stop_stream():
            return False
        record_enabled = bool(self.auto_record_enabled)
        self.last_recording_path = None
        self.last_recording_error = ""
        try:
            import cv2
            cap = cv2.VideoCapture(0, cv2.CAP_DSHOW)
            if not cap.isOpened():
                cap.release()
                cap = cv2.VideoCapture(0)
            if not cap.isOpened():
                cap.release()
                return False
            capture_width = 1280 if record_enabled else max_width
            capture_height = 720 if record_enabled else max(90, int(max_width * 3 / 4))
            cap.set(cv2.CAP_PROP_FRAME_WIDTH, capture_width)
            cap.set(cv2.CAP_PROP_FRAME_HEIGHT, capture_height)
        except Exception as e:
            try:
                cap.release()
            except Exception:
                pass
            print(f"[-] Camera open error: {e}")
            return False
        if record_enabled:
            allowed, message = prepare_recording_storage(
                self.recordings_dir,
                **self.recording_policy,
                protected_paths=(self.last_recording_path,),
            )
            if not allowed:
                record_enabled = False
                self.last_recording_error = message
                print(f"[-] {message}")
        with self.lock:
            self.generation += 1
            generation = self.generation
            self.streaming = True
            self.stream_capture = cap

        def _loop():
            writer = None
            recording_path = None
            failure_reason = None
            consecutive_read_failures = 0
            consecutive_frame_drops = 0
            try:
                while True:
                    with self.lock:
                        if not self.streaming or generation != self.generation:
                            break
                    ret, frame = cap.read()
                    if not ret or frame is None:
                        consecutive_read_failures += 1
                        if consecutive_read_failures >= 5:
                            failure_reason = "The camera stopped returning frames."
                            break
                        time.sleep(1.0 / max(1, fps))
                        continue
                    consecutive_read_failures = 0
                    if ret and frame is not None:
                        if record_enabled:
                            if writer is None:
                                height, width = frame.shape[:2]
                                stamp = time.strftime("%Y%m%d_%H%M%S")
                                recording_path = os.path.join(
                                    self.recordings_dir,
                                    f"webcam_{stamp}_{int(time.time() * 1000) % 1000:03d}.avi"
                                )
                                fourcc = cv2.VideoWriter_fourcc(*"MJPG")
                                writer = cv2.VideoWriter(recording_path, fourcc, max(1, fps), (width, height))
                                if not writer.isOpened():
                                    writer.release()
                                    writer = None
                                    recording_path = None
                                    print("[-] Could not start webcam video recording (MJPG codec unavailable)")
                                else:
                                    self.last_recording_path = recording_path
                                    print(f"[+] Recording webcam stream to {recording_path}")
                            if writer is not None:
                                writer.write(frame)
                        height, width = frame.shape[:2]
                        live_width = min(width, max_width)
                        buf = None
                        while live_width >= min(80, min(width, max_width)):
                            live_frame = frame
                            if width > live_width:
                                live_frame = cv2.resize(
                                    frame, (live_width, max(1, int(height * live_width / width)))
                                )
                            for frame_quality in (quality, max(20, quality - 10), max(15, quality - 20)):
                                encoded, candidate = cv2.imencode(
                                    '.jpg', live_frame, [int(cv2.IMWRITE_JPEG_QUALITY), frame_quality]
                                )
                                if encoded and (max_frame_bytes is None or candidate.nbytes <= max_frame_bytes):
                                    buf = candidate
                                    break
                            if buf is not None or max_frame_bytes is None or live_width <= 80:
                                break
                            live_width = max(80, int(live_width * 0.75))
                        if buf is None:
                            consecutive_frame_drops += 1
                            if consecutive_frame_drops >= 10:
                                failure_reason = "Live frames could not fit the connection's frame-size limit."
                                break
                            time.sleep(1.0 / max(1, fps))
                            continue
                        consecutive_frame_drops = 0
                        if buf is not None:
                            b64 = base64.b64encode(buf).decode('utf-8')
                            with self.lock:
                                is_current = self.streaming and generation == self.generation
                            if is_current:
                                on_frame_callback(b64)
                    time.sleep(1.0 / max(1, fps))
            except Exception as e:
                failure_reason = f"Camera stream error: {e}"
                print(f"[-] {failure_reason}")
            finally:
                if writer is not None:
                    writer.release()
                    print(f"[+] Finished webcam recording: {recording_path}")
                with self.lock:
                    release_capture = self.stream_capture is cap
                    if release_capture:
                        self.stream_capture = None
                if release_capture:
                    cap.release()
                with self.lock:
                    is_current = generation == self.generation
                    if is_current:
                        self.streaming = False
                        if self.stream_thread is threading.current_thread():
                            self.stream_thread = None
                if is_current and failure_reason and on_stream_end_callback:
                    try:
                        on_stream_end_callback(failure_reason)
                    except Exception as e:
                        print(f"[-] Camera stop notification failed: {e}")

        thread = threading.Thread(target=_loop, daemon=True)
        with self.lock:
            if not self.streaming or generation != self.generation:
                cap.release()
                return False
            self.stream_thread = thread
            thread.start()
        return True

    def stop_stream(self):
        with self.lifecycle_lock:
            return self._stop_stream()

    def _stop_stream(self):
        with self.lock:
            self.streaming = False
            self.generation += 1
            thread = self.stream_thread
            capture = self.stream_capture
            self.stream_capture = None
        # Releasing a capture handle from the stop path helps unblock a driver
        # stuck inside read(), so a lost phone cannot leave the webcam active.
        if capture is not None:
            try:
                capture.release()
            except Exception:
                pass
        if thread and thread is not threading.current_thread() and thread.is_alive():
            thread.join(timeout=2.0)
        if thread and thread.is_alive():
            print("[-] Camera stream did not stop before timeout")
            return False
        return True


class AudioManager:
    """Manages laptop microphone audio recording"""
    def __init__(self):
        self.recording = False
        self.record_thread = None
        user_data = os.environ.get("LOCALAPPDATA") or os.path.expanduser("~")
        self.recordings_dir = os.path.join(user_data, "BTMouse", "Recordings")
        os.makedirs(self.recordings_dir, exist_ok=True)
        self.current_filename = None
        self.last_error = None
        self.recording_policy = {"retention_days": 0, "max_storage_mb": 0, "min_free_mb": 512}
        self.has_audio = False
        try:
            import sounddevice as sd
            import wave
            self.has_audio = True
        except ImportError:
            pass

    def start_recording(self):
        if not self.has_audio:
            self.last_error = "Microphone support is unavailable. Install the companion audio dependencies."
            return None
        if self.recording:
            return self.current_filename
        allowed, message = prepare_recording_storage(
            self.recordings_dir,
            **self.recording_policy,
            protected_paths=(self.current_filename,),
        )
        if not allowed:
            self.last_error = message
            return None
        self.last_error = None
        ts = time.strftime("%Y%m%d_%H%M%S")
        self.current_filename = os.path.join(self.recordings_dir, f"audio_{ts}.wav")
        ready = threading.Event()
        startup = {"error": None}

        def _loop():
            try:
                import sounddevice as sd
                import wave
                device = sd.query_devices(kind='input')
                fs = int(device.get('default_samplerate') or 16000)
                channels = 1
                with wave.open(self.current_filename, 'wb') as wf:
                    wf.setnchannels(channels)
                    wf.setsampwidth(2)
                    wf.setframerate(fs)
                    with sd.InputStream(samplerate=fs, channels=channels, dtype='int16') as stream:
                        self.recording = True
                        ready.set()
                        while self.recording:
                            data, _ = stream.read(1024)
                            wf.writeframes(data.tobytes())
            except Exception as e:
                startup["error"] = str(e)
                self.last_error = str(e)
                print(f"[-] Audio recording error: {e}")
            finally:
                self.recording = False
                ready.set()

        self.record_thread = threading.Thread(target=_loop, daemon=True)
        self.record_thread.start()
        if not ready.wait(timeout=5.0) or not self.recording:
            self.recording = False
            self.record_thread.join(timeout=1.0)
            self.current_filename = None
            if not self.last_error:
                self.last_error = startup["error"] or "Microphone did not become ready."
            return None
        print(f"[+] Started microphone recording to {self.current_filename}")
        return self.current_filename

    def stop_recording(self):
        self.recording = False
        if self.record_thread and self.record_thread.is_alive():
            self.record_thread.join(timeout=1.0)
        if self.current_filename and os.path.exists(self.current_filename):
            print(f"[+] Stopped microphone recording: {self.current_filename}")
        return self.current_filename


class LiveAudioManager:
    """Relays low-latency mono PCM between the PC microphone and one client."""
    SAMPLE_RATE = 16000
    BLOCK_SAMPLES = 320  # 20 ms at 16 kHz

    def __init__(self, recordings_dir):
        self.recordings_dir = recordings_dir
        self.recording_policy = {"retention_days": 0, "max_storage_mb": 0, "min_free_mb": 512}
        self.lock = threading.RLock()
        self.capture_stream = None
        self.capture_thread = None
        self.capture_running = False
        self.capture_owner = None
        self.capture_recorder = None
        self.capture_path = None
        self.capture_respond = None
        self.capture_last_seen = 0.0
        self.output_stream = None
        self.output_owner = None
        self.output_recorder = None
        self.output_path = None
        self.output_queue = None
        self.output_thread = None
        self.output_running = False
        self.output_respond = None
        self.output_last_seen = 0.0
        self.lease_thread = None

    def start_pc_to_phone(self, owner, respond, record=False):
        if not self.stop_pc_to_phone():
            return False, "The previous PC microphone stream is still stopping"
        try:
            import sounddevice as sd
            import wave
            stream = sd.InputStream(samplerate=self.SAMPLE_RATE, channels=1, dtype="int16",
                                    blocksize=self.BLOCK_SAMPLES)
            stream.start()
            recorder = None
            recording_path = None
            record_warning = ""
            if record:
                allowed, record_warning = prepare_recording_storage(
                    self.recordings_dir, **self.recording_policy,
                    protected_paths=(self.capture_path, self.output_path),
                )
                if allowed:
                    recording_path = self._new_recording_path("live_pc_mic")
                    recorder = wave.open(recording_path, "wb")
                    recorder.setnchannels(1)
                    recorder.setsampwidth(2)
                    recorder.setframerate(self.SAMPLE_RATE)
            with self.lock:
                self.capture_stream = stream
                self.capture_running = True
                self.capture_owner = owner
                self.capture_recorder = recorder
                self.capture_path = recording_path
                self.capture_respond = respond
                self.capture_last_seen = time.monotonic()
                self.capture_thread = threading.Thread(
                    target=self._capture_loop, args=(stream, respond),
                    name="LivePcAudioCapture", daemon=True)
                self.capture_thread.start()
                self._start_lease_watcher()
            message = "PC microphone audio is live"
            if record and not recorder:
                message += f"; recording skipped: {record_warning}"
            return True, message
        except Exception as exc:
            try:
                recorder.close()
            except Exception:
                pass
            try:
                stream.stop()
                stream.close()
            except Exception:
                pass
            return False, f"Could not start PC microphone audio: {exc}"

    def _capture_loop(self, stream, respond):
        try:
            while True:
                with self.lock:
                    if not self.capture_running or self.capture_stream is not stream:
                        break
                    recorder = self.capture_recorder
                data, _overflowed = stream.read(self.BLOCK_SAMPLES)
                pcm = data.tobytes()
                if recorder:
                    recorder.writeframesraw(pcm)
                respond({
                    "type": "live_audio_frame",
                    "direction": "pc_to_phone",
                    "rate": self.SAMPLE_RATE,
                    "data": base64.b64encode(pcm).decode("ascii"),
                })
        except Exception as exc:
            print(f"[-] Live PC audio capture ended: {exc}")
            with self.lock:
                owner = self.capture_owner if self.capture_running else None
            if owner is not None:
                try:
                    respond({"type": "live_audio_status", "direction": "pc_to_phone",
                             "active": False, "message": f"PC microphone stream ended: {exc}"})
                except Exception:
                    pass
        finally:
            finished_path = None
            with self.lock:
                if self.capture_stream is stream:
                    self.capture_running = False
                    self.capture_stream = None
                    self.capture_thread = None
                    self.capture_owner = None
                    self.capture_respond = None
                    recorder = self.capture_recorder
                    self.capture_recorder = None
                    finished_path = self.capture_path
                else:
                    recorder = None
            if recorder:
                try:
                    recorder.close()
                except Exception:
                    pass
            with self.lock:
                if self.capture_path == finished_path:
                    self.capture_path = None
            try:
                stream.stop()
                stream.close()
            except Exception:
                pass

    def stop_pc_to_phone(self, owner=None):
        with self.lock:
            if owner is not None and self.capture_owner != owner:
                return False
            stream = self.capture_stream
            thread = self.capture_thread
            self.capture_running = False
        if stream:
            try:
                stream.stop()
            except Exception:
                pass
        if thread and thread is not threading.current_thread():
            thread.join(timeout=2.0)
            if thread.is_alive():
                print("[-] Live PC audio capture did not stop before timeout")
                return False
        return True

    def _new_recording_path(self, prefix):
        stamp = time.strftime("%Y%m%d_%H%M%S")
        unique = f"{time.time_ns() % 1_000_000_000:09d}"
        return os.path.join(self.recordings_dir, f"{prefix}_{stamp}_{unique}.wav")

    def touch_owner(self, owner):
        now = time.monotonic()
        with self.lock:
            if self.capture_owner == owner:
                self.capture_last_seen = now
            if self.output_owner == owner:
                self.output_last_seen = now

    def _start_lease_watcher(self):
        if self.lease_thread and self.lease_thread.is_alive():
            return
        self.lease_thread = threading.Thread(target=self._watch_leases, name="LiveAudioLease", daemon=True)
        self.lease_thread.start()

    def _watch_leases(self):
        while True:
            stale_capture = stale_output = False
            with self.lock:
                if not self.capture_running and not self.output_running:
                    return
                now = time.monotonic()
                stale_capture = bool(self.capture_owner and self.capture_owner[0] == "wifi" and
                                     now - self.capture_last_seen > 8.0)
                stale_output = bool(self.output_owner and self.output_owner[0] == "wifi" and
                                    now - self.output_last_seen > 8.0)
                capture_respond = self.capture_respond
                output_respond = self.output_respond
                capture_owner = self.capture_owner
                output_owner = self.output_owner
            if stale_capture:
                if capture_respond:
                    try:
                        capture_respond({"type": "live_audio_status", "direction": "pc_to_phone",
                                         "active": False, "message": "Audio stopped after the Wi-Fi client disconnected"})
                    except Exception:
                        pass
                self.stop_pc_to_phone(capture_owner)
            if stale_output:
                if output_respond:
                    try:
                        output_respond({"type": "live_audio_status", "direction": "phone_to_pc",
                                        "active": False, "message": "Audio stopped after the Wi-Fi client disconnected"})
                    except Exception:
                        pass
                self.stop_phone_to_pc(output_owner)
            time.sleep(0.5)

    def start_phone_to_pc(self, owner, record=False, respond=None):
        if not self.stop_phone_to_pc():
            return False, "The previous phone audio stream is still stopping"
        stream = None
        recorder = None
        try:
            import sounddevice as sd
            import wave
            stream = sd.OutputStream(samplerate=self.SAMPLE_RATE, channels=1, dtype="int16",
                                     blocksize=self.BLOCK_SAMPLES)
            stream.start()
            recording_path = None
            record_warning = ""
            if record:
                allowed, record_warning = prepare_recording_storage(
                    self.recordings_dir, **self.recording_policy,
                    protected_paths=(self.capture_path, self.output_path),
                )
                if allowed:
                    recording_path = self._new_recording_path("live_phone_mic")
                    recorder = wave.open(recording_path, "wb")
                    recorder.setnchannels(1)
                    recorder.setsampwidth(2)
                    recorder.setframerate(self.SAMPLE_RATE)
            with self.lock:
                self.output_stream = stream
                self.output_owner = owner
                self.output_respond = respond
                self.output_last_seen = time.monotonic()
                self.output_recorder = recorder
                self.output_path = recording_path
                self.output_queue = queue.Queue(maxsize=16)
                self.output_running = True
                self.output_thread = threading.Thread(
                    target=self._output_loop, args=(stream,), name="LivePhoneAudioPlayback", daemon=True)
                self.output_thread.start()
            message = "Phone microphone audio is playing on the PC"
            if record and not recorder:
                message += f"; recording skipped: {record_warning}"
            return True, message
        except Exception as exc:
            if recorder:
                try:
                    recorder.close()
                except Exception:
                    pass
            if stream:
                try:
                    stream.stop()
                    stream.close()
                except Exception:
                    pass
            return False, f"Could not start PC audio output: {exc}"

    def accept_phone_audio(self, owner, pcm):
        with self.lock:
            if not self.output_running or self.output_owner != owner:
                return False
            audio_queue = self.output_queue
        try:
            audio_queue.put_nowait(pcm)
            return True
        except queue.Full:
            try:
                audio_queue.get_nowait()
                audio_queue.put_nowait(pcm)
                return True
            except queue.Empty:
                return False

    def _output_loop(self, stream):
        try:
            while True:
                with self.lock:
                    if not self.output_running or self.output_stream is not stream:
                        break
                    audio_queue = self.output_queue
                    recorder = self.output_recorder
                try:
                    pcm = audio_queue.get(timeout=0.2)
                except queue.Empty:
                    continue
                if len(pcm) != self.BLOCK_SAMPLES * 2:
                    continue
                stream.write(pcm)
                if recorder:
                    recorder.writeframesraw(pcm)
        except Exception as exc:
            print(f"[-] Live phone audio output ended: {exc}")
            with self.lock:
                respond = self.output_respond
            if respond:
                try:
                    respond({"type": "live_audio_status", "direction": "phone_to_pc",
                             "active": False, "message": f"PC audio output ended: {exc}"})
                except Exception:
                    pass
        finally:
            self.stop_phone_to_pc()

    def stop_phone_to_pc(self, owner=None):
        with self.lock:
            if owner is not None and self.output_owner != owner:
                return False
            stream = self.output_stream
            thread = self.output_thread
            self.output_running = False
        if stream:
            try:
                stream.stop()
            except Exception:
                pass
        if thread and thread is not threading.current_thread():
            thread.join(timeout=2.0)
            if thread.is_alive():
                print("[-] Live phone audio output did not stop before timeout")
                return False
        finished_path = None
        with self.lock:
            if self.output_stream is stream:
                self.output_stream = None
                self.output_thread = None
                self.output_owner = None
                self.output_respond = None
                self.output_queue = None
                self.output_running = False
                recorder = self.output_recorder
                self.output_recorder = None
                finished_path = self.output_path
            else:
                recorder = None
        if recorder:
            try:
                recorder.close()
            except Exception:
                pass
        with self.lock:
            if self.output_path == finished_path:
                self.output_path = None
        if stream:
            try:
                stream.close()
            except Exception:
                pass
        return True

class RemoteWebSocketServer:
    """Secure WebSocket Server for remote WAN & internet connections"""
    def __init__(self, port=53531, command_handler=None, on_client_callback=None, auth_token=None):
        self.port = port
        self.command_handler = command_handler
        self.on_client_callback = on_client_callback
        self.auth_token = auth_token
        self.clients = set()
        self.running = False
        self.loop = None
        self.has_ws = False
        try:
            import websockets
            self.has_ws = True
        except ImportError:
            pass

    def start(self):
        if not self.has_ws or self.running:
            return
        self.running = True
        def _runner():
            self.loop = asyncio.new_event_loop()
            asyncio.set_event_loop(self.loop)
            self.loop.run_until_complete(self._listen())
        threading.Thread(target=_runner, daemon=True).start()

    async def _listen(self):
        try:
            import websockets
            # WAN latency and large media/process responses can vary. Keep
            # protocol pings active with a more tolerant timeout, and bound
            # messages so a client cannot enqueue unbounded frames.
            async with websockets.serve(
                self._handle_client,
                "0.0.0.0",
                self.port,
                ping_interval=20,
                ping_timeout=60,
                close_timeout=10,
                max_size=2 * 1024 * 1024,
            ):
                print(f"[*] Remote WAN WebSocket Server listening on ws://0.0.0.0:{self.port}")
                while self.running:
                    await asyncio.sleep(1)
        except Exception as e:
            print(f"[!] WebSocket server start error: {e}")

    async def _handle_client(self, websocket):
        authenticated = False
        client_id = "Remote-WAN Client"
        client_reported = False
        try:
            async for message in websocket:
                try:
                    data = json.loads(message)
                except Exception:
                    continue

                cmd_type = data.get("type")
                if not authenticated:
                    if cmd_type == "auth":
                        token = str(data.get("token", "")).strip()
                        if self.auth_token and hmac.compare_digest(token, str(self.auth_token)):
                            authenticated = True
                            self.clients.add(websocket)
                            if self.on_client_callback:
                                self.on_client_callback(client_id, True)
                                client_reported = True
                            await websocket.send(json.dumps({"type": "auth_response", "authenticated": True, "message": "Access granted"}))
                            continue
                        else:
                            await websocket.send(json.dumps({"type": "auth_response", "authenticated": False, "message": "Invalid Security Key"}))
                            await websocket.close()
                            return
                    else:
                        await websocket.send(json.dumps({"type": "auth_response", "authenticated": False, "message": "Authentication required"}))
                        await websocket.close()
                        return

                def _respond(resp_dict):
                    if self.loop and self.loop.is_running():
                        try:
                            payload = json.dumps(resp_dict)
                            future = asyncio.run_coroutine_threadsafe(websocket.send(payload), self.loop)
                            def _report_send_error(done_future):
                                try:
                                    done_future.result()
                                except Exception as e:
                                    print(f"[-] WebSocket response write failed: {e}")
                            future.add_done_callback(_report_send_error)
                        except Exception as e:
                            print(f"[-] Could not serialize WebSocket response: {e}")

                if self.command_handler:
                    # Media queries use asyncio/WinRT and process, camera, and
                    # audio commands may block. Never run them on the socket's
                    # event loop: that loop must keep servicing WebSocket
                    # ping/pong frames while a command is working.
                    await asyncio.to_thread(
                        self.command_handler,
                        data,
                        addr=("WebSocket", 0),
                        responder=_respond,
                    )
        except Exception:
            pass
        finally:
            if authenticated and self.command_handler:
                try:
                    self.command_handler({"type": "disconnect"}, addr=("WebSocket", 0))
                except Exception as e:
                    print(f"[-] Could not release WebSocket input state: {e}")
            self.clients.discard(websocket)
            if client_reported and self.on_client_callback:
                self.on_client_callback(client_id, False)

    def broadcast(self, data_dict):
        if not self.clients or not self.loop or not self.loop.is_running():
            return
        payload = json.dumps(data_dict)
        for client in list(self.clients):
            try:
                asyncio.run_coroutine_threadsafe(client.send(payload), self.loop)
            except Exception:
                pass

    def stop(self):
        self.running = False


class Server:
    def __init__(self, port=53530, discovery_port=53535, ws_port=53531, auth_token=None):
        ensure_interactive_desktop()
        self.port = port
        self.discovery_port = discovery_port
        self.ws_port = ws_port
        self.auth_token = str(auth_token or load_or_create_pairing_pin())
        self.running = False
        self.sock = None
        self.discovery_sock = None

        if HAS_PYNPUT:
            try:
                self.mouse = MouseController()
            except Exception:
                self.mouse = None
            try:
                self.keyboard = KeyboardController()
            except Exception:
                self.keyboard = None
        else:
            self.mouse = None
            self.keyboard = None

        self.last_buttons = 0
        self.last_pen_button = None
        self.pressed_keys = set()
        self.last_input_at = time.monotonic()
        self.packets_received = 0
        self.last_log_time = 0
        self.connected_client = None
        self.auth_failures = {}
        self._process_cpu_samples = {}
        self._query_cache = {}
        self._query_cache_lock = threading.Lock()
        self._query_cache_key_locks = {}
        self._apps_refresh_lock = threading.Lock()
        self._apps_refresh_waiters = {}
        self._apps_refresh_running = False
        self._async_query_lock = threading.Lock()
        self._async_query_waiters = {}
        self._async_query_running = set()
        self._snapshot_transfers = {}
        self._snapshot_transfer_lock = threading.Lock()
        self._recording_download_acks = {}
        self._recording_download_lock = threading.Lock()
        self._active_recording_downloads = set()
        self._recording_download_cancels = {}
        self._recording_download_names = {}
        self._recording_download_owners = {}
        self._hardware_metrics_lock = threading.Lock()
        self._hardware_metrics = {"disk_activity": -1.0, "gpus": [], "npu": -1.0}
        self._performance_sampler_running = False
        self._performance_sampler_thread = None
        self._camera_stream_owner = None
        self._camera_stream_owner_lock = threading.Lock()
        self.auto_record_audio_with_video = False
        self._camera_auto_audio_recording = False
        self.media_controller = MediaController(fallback_media_func=self._execute_media)

        # Bluetooth RFCOMM Server for mobile companion
        self.bt_server = BluetoothRfcommServer(self.handle_packet, on_client_callback=self._on_bt_client)

        # Remote WebSocket Server for WAN / Internet connections
        self.ws_server = RemoteWebSocketServer(port=self.ws_port, command_handler=self.handle_packet,
                                               on_client_callback=self._on_ws_client, auth_token=self.auth_token)

        # Camera & Audio Managers
        self.camera_manager = CameraManager()
        self.audio_manager = AudioManager()
        self.live_audio_manager = LiveAudioManager(self.audio_manager.recordings_dir)

        # Callbacks for PC Companion GUI
        self.on_packet_callback = None
        self.on_client_callback = None
        self.on_status_callback = None

    def _is_authorized(self, addr, data):
        if addr and addr[0] in ("Bluetooth", "WebSocket"):
            return True
        if not addr:
            return False
        req_token = str(data.get("token", data.get("auth_token", ""))).strip()
        return bool(req_token) and hmac.compare_digest(req_token, self.auth_token)

    def _auth_attempt_allowed(self, addr):
        if not addr:
            return False
        key = addr[0]
        now = time.monotonic()
        started, attempts = self.auth_failures.get(key, (now, 0))
        if now - started >= 60:
            started, attempts = now, 0
        if attempts >= 10:
            self.auth_failures[key] = (started, attempts)
            return False
        return True

    def _record_auth_failure(self, addr):
        if not addr:
            return
        key = addr[0]
        now = time.monotonic()
        started, attempts = self.auth_failures.get(key, (now, 0))
        if now - started >= 60:
            started, attempts = now, 0
        self.auth_failures[key] = (started, attempts + 1)

    def _on_bt_client(self, client_name, is_connected):
        if is_connected:
            self.connected_client = client_name
        else:
            if self.connected_client == client_name:
                self.connected_client = None
            self._stop_camera_stream_for_client(("bluetooth", None))
        if self.on_client_callback:
            self.on_client_callback(self.connected_client, is_connected)

    def _on_ws_client(self, client_name, is_connected):
        if is_connected:
            self.connected_client = client_name
        else:
            if self.connected_client == client_name:
                self.connected_client = None
            self._stop_camera_stream_for_client(("wan", None))
        if self.on_client_callback:
            self.on_client_callback(self.connected_client, is_connected)

    @staticmethod
    def _camera_client_key(addr):
        if not addr:
            return ("unknown", None)
        transport = str(addr[0]).lower()
        if transport == "bluetooth":
            return ("bluetooth", None)
        if transport == "websocket":
            return ("wan", None)
        return ("wifi", tuple(addr))

    def _refresh_camera_stream_lease(self, addr):
        key = self._camera_client_key(addr)
        owner_lock = getattr(self, "_camera_stream_owner_lock", None)
        if owner_lock is not None:
            with owner_lock:
                owner = getattr(self, "_camera_stream_owner", None)
                if owner and owner.get("key") == key:
                    owner["last_seen"] = time.monotonic()
        if hasattr(self, "live_audio_manager"):
            self.live_audio_manager.touch_owner(key)

    def _cancel_recording_transfers_for_client(self, owner):
        with self._recording_download_lock:
            for transfer_id, transfer_owner in self._recording_download_owners.items():
                if transfer_owner == owner:
                    cancel = self._recording_download_cancels.get(transfer_id)
                    if cancel:
                        cancel.set()

    def _stop_camera_stream_for_client(self, key=None):
        should_stop = False
        with self._camera_stream_owner_lock:
            owner = self._camera_stream_owner
            if owner and (key is None or owner["key"] == key):
                self._camera_stream_owner = None
                should_stop = True
        if should_stop:
            self.camera_manager.stop_stream()
            if self._camera_auto_audio_recording:
                self.audio_manager.stop_recording()
                self._camera_auto_audio_recording = False

    def _on_camera_stream_ended(self, session_id, owner_key, respond, reason):
        with self._camera_stream_owner_lock:
            owner = self._camera_stream_owner
            if not owner or owner.get("session_id") != session_id or owner.get("key") != owner_key:
                return
            self._camera_stream_owner = None
            stop_auto_audio = self._camera_auto_audio_recording
            self._camera_auto_audio_recording = False
        if stop_auto_audio:
            self.audio_manager.stop_recording()
        print(f"[-] Webcam stream ended: {reason}")
        try:
            respond({"type": "camera_stream_status", "streaming": False, "message": reason})
        except Exception:
            pass

    def _expire_udp_camera_stream(self):
        with self._camera_stream_owner_lock:
            owner = self._camera_stream_owner
            expired = bool(owner and owner["key"][0] == "wifi" and time.monotonic() - owner["last_seen"] > 8.0)
            key = owner["key"] if expired else None
        if key:
            print("[*] Wi-Fi camera client timed out; stopping webcam capture")
            self._stop_camera_stream_for_client(key)

    def get_local_ip(self):
        try:
            s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            s.connect(("8.8.8.8", 80))
            ip = s.getsockname()[0]
            s.close()
            return ip
        except Exception:
            return "127.0.0.1"

    def get_all_ips(self):
        """Returns list of (adapter_name, ip_address) found on this machine"""
        results = []
        primary_ip = self.get_local_ip()
        results.append(("Auto (Primary)", primary_ip))

        if IS_WINDOWS:
            try:
                import subprocess
                cmd = 'powershell -NoProfile -Command "Get-NetIPAddress -AddressFamily IPv4 | Where-Object { $_.InterfaceAlias -notlike \'*Loopback*\' } | Select-Object -Property InterfaceAlias, IPAddress | ConvertTo-Json"'
                out = subprocess.check_output(cmd, shell=True, text=True, timeout=3)
                data = json.loads(out)
                if isinstance(data, dict):
                    data = [data]
                for item in data:
                    alias = item.get("InterfaceAlias", "Adapter")
                    ip = item.get("IPAddress", "")
                    if ip and not ip.startswith("169.254.") and ip != primary_ip:
                        results.append((alias, ip))
            except Exception:
                pass

        return results

    def start_discovery_responder(self):
        """Listens for UDP broadcast packets from Android app and responds with server details"""
        def listen_discovery():
            self.discovery_sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            try:
                self.discovery_sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            except Exception:
                pass
            try:
                self.discovery_sock.bind(("", self.discovery_port))
                self.discovery_sock.settimeout(1.0)
            except Exception as e:
                print(f"[!] Warning: Could not bind discovery port {self.discovery_port}: {e}")
                return

            hostname = socket.gethostname()
            while self.running:
                try:
                    data, addr = self.discovery_sock.recvfrom(1024)
                    message = data.decode("utf-8", errors="ignore").strip()
                    if message == "BT_MOUSE_DISCOVER":
                        # Discovery is intentionally unauthenticated but never discloses the PIN.
                        reply = f"BT_MOUSE_SERVER:{hostname}:{self.port}"
                        self.discovery_sock.sendto(reply.encode("utf-8"), addr)
                        print(f"[*] Discovery ping from {addr[0]} -> Responded with '{reply}'")
                except socket.timeout:
                    continue
                except Exception:
                    break
            if self.discovery_sock:
                try:
                    self.discovery_sock.close()
                except Exception:
                    pass

        thread = threading.Thread(target=listen_discovery, daemon=True)
        thread.start()

    def handle_packet(self, data, addr=None, responder=None):
        if not isinstance(data, dict):
            return
        cmd_type = data.get("type")

        def respond(payload):
            if responder:
                responder(payload)
            elif addr and self.sock:
                self._send_udp_json(payload, addr)

        def respond_media_state(action_success=None):
            response = {"type": "media_status"}
            response.update(self.media_controller.get_media_state())
            if action_success is not None:
                response["action_success"] = bool(action_success)
            respond(response)

        if cmd_type in ("connect", "client_connect"):
            if not self._auth_attempt_allowed(addr):
                return
            is_valid = self._is_authorized(addr, data)
            if is_valid:
                self.auth_failures.pop(addr[0], None)
                client_name = str(data.get("client", "Android Client"))[:80]
                self.connected_client = f"{client_name} ({addr[0]})" if addr else client_name
                if self.on_client_callback:
                    self.on_client_callback(self.connected_client, True)
                response = {
                    "type": "connect_ack",
                    "success": True,
                    "server_name": socket.gethostname(),
                    "version": "1.3.8"
                }
            else:
                self._record_auth_failure(addr)
                response = {"type": "connect_ack", "success": False, "error": "Invalid pairing PIN"}
            respond(response)
            return

        # Every UDP operation requires the pairing PIN. RFCOMM is trusted only
        # after the OS Bluetooth session is established; WebSocket is gated by
        # its own authenticated handshake before it calls this dispatcher.
        if not self._is_authorized(addr, data):
            if self._auth_attempt_allowed(addr):
                self._record_auth_failure(addr)
                respond({"type": "auth_error", "message": "Pairing PIN required or invalid"})
            return

        self._refresh_camera_stream_lease(addr)

        if cmd_type == "ping":
            respond({"type": "pong", "time": time.time()})
            return

        if cmd_type == "disconnect":
            self._release_mouse_buttons()
            owner_key = self._camera_client_key(addr)
            self._stop_camera_stream_for_client(owner_key)
            self.live_audio_manager.stop_pc_to_phone(owner_key)
            self.live_audio_manager.stop_phone_to_pc(owner_key)
            self._cancel_recording_transfers_for_client(owner_key)
            if self.connected_client and self.on_client_callback:
                self.on_client_callback(self.connected_client, False)
            self.connected_client = None
            return

        if self.on_packet_callback:
            try:
                self.on_packet_callback(cmd_type, addr, {"type": cmd_type})
            except Exception:
                pass

        if cmd_type == "mouse":
            dx = int(round(data.get("dx", 0)))
            dy = int(round(data.get("dy", 0)))
            btn = int(data.get("btn", 0))
            v_scroll = int(round(data.get("v_scroll", 0)))
            h_scroll = int(round(data.get("h_scroll", 0)))
            self.last_input_at = time.monotonic()

            if IS_WINDOWS:
                if dx != 0 or dy != 0:
                    u32.mouse_event(MOUSEEVENTF_MOVE, dx, dy, 0, 0)

                if v_scroll != 0:
                    u32.mouse_event(MOUSEEVENTF_WHEEL, 0, 0, v_scroll * 120, 0)

                if h_scroll != 0:
                    u32.mouse_event(MOUSEEVENTF_HWHEEL, 0, 0, h_scroll * 120, 0)

                if btn != self.last_buttons:
                    # Left
                    if (btn & 1) and not (self.last_buttons & 1):
                        u32.mouse_event(MOUSEEVENTF_LEFTDOWN, 0, 0, 0, 0)
                    elif not (btn & 1) and (self.last_buttons & 1):
                        u32.mouse_event(MOUSEEVENTF_LEFTUP, 0, 0, 0, 0)

                    # Right
                    if (btn & 2) and not (self.last_buttons & 2):
                        u32.mouse_event(MOUSEEVENTF_RIGHTDOWN, 0, 0, 0, 0)
                    elif not (btn & 2) and (self.last_buttons & 2):
                        u32.mouse_event(MOUSEEVENTF_RIGHTUP, 0, 0, 0, 0)

                    # Middle
                    if (btn & 4) and not (self.last_buttons & 4):
                        u32.mouse_event(MOUSEEVENTF_MIDDLEDOWN, 0, 0, 0, 0)
                    elif not (btn & 4) and (self.last_buttons & 4):
                        u32.mouse_event(MOUSEEVENTF_MIDDLEUP, 0, 0, 0, 0)

                    self.last_buttons = btn
            elif self.mouse:
                if dx != 0 or dy != 0:
                    self.mouse.move(dx, dy)
                if v_scroll != 0 or h_scroll != 0:
                    self.mouse.scroll(h_scroll, v_scroll)
                if btn != self.last_buttons:
                    if (btn & 1) and not (self.last_buttons & 1):
                        self.mouse.press(Button.left)
                    elif not (btn & 1) and (self.last_buttons & 1):
                        self.mouse.release(Button.left)

                    if (btn & 2) and not (self.last_buttons & 2):
                        self.mouse.press(Button.right)
                    elif not (btn & 2) and (self.last_buttons & 2):
                        self.mouse.release(Button.right)

                    if (btn & 4) and not (self.last_buttons & 4):
                        self.mouse.press(Button.middle)
                    elif not (btn & 4) and (self.last_buttons & 4):
                        self.mouse.release(Button.middle)

                    self.last_buttons = btn

        elif cmd_type in ("button", "mouse_button"):
            btn = int(data.get("btn", 0))
            self.last_input_at = time.monotonic()
            if IS_WINDOWS:
                # Left
                if (btn & 1) and not (self.last_buttons & 1):
                    u32.mouse_event(MOUSEEVENTF_LEFTDOWN, 0, 0, 0, 0)
                elif not (btn & 1) and (self.last_buttons & 1):
                    u32.mouse_event(MOUSEEVENTF_LEFTUP, 0, 0, 0, 0)

                # Right
                if (btn & 2) and not (self.last_buttons & 2):
                    u32.mouse_event(MOUSEEVENTF_RIGHTDOWN, 0, 0, 0, 0)
                elif not (btn & 2) and (self.last_buttons & 2):
                    u32.mouse_event(MOUSEEVENTF_RIGHTUP, 0, 0, 0, 0)

                # Middle
                if (btn & 4) and not (self.last_buttons & 4):
                    u32.mouse_event(MOUSEEVENTF_MIDDLEDOWN, 0, 0, 0, 0)
                elif not (btn & 4) and (self.last_buttons & 4):
                    u32.mouse_event(MOUSEEVENTF_MIDDLEUP, 0, 0, 0, 0)

                self.last_buttons = btn
            elif self.mouse:
                if (btn & 1) and not (self.last_buttons & 1):
                    self.mouse.press(Button.left)
                elif not (btn & 1) and (self.last_buttons & 1):
                    self.mouse.release(Button.left)

                if (btn & 2) and not (self.last_buttons & 2):
                    self.mouse.press(Button.right)
                elif not (btn & 2) and (self.last_buttons & 2):
                    self.mouse.release(Button.right)

                if (btn & 4) and not (self.last_buttons & 4):
                    self.mouse.press(Button.middle)
                elif not (btn & 4) and (self.last_buttons & 4):
                    self.mouse.release(Button.middle)

                self.last_buttons = btn

        elif cmd_type == "pen":
            # Tablet Drawing / Writing Pad mode (1:1 Touchscreen & Stylus replacement)
            action = data.get("action")  # "down", "move", "up"
            x_ratio = float(data.get("x", 0.0))
            y_ratio = float(data.get("y", 0.0))
            is_eraser = bool(data.get("eraser", False))
            if action in ("down", "move", "up"):
                self.last_input_at = time.monotonic()

            if IS_WINDOWS:
                norm_x = int(max(0.0, min(1.0, x_ratio)) * 65535)
                norm_y = int(max(0.0, min(1.0, y_ratio)) * 65535)

                btn_down = MOUSEEVENTF_RIGHTDOWN if is_eraser else MOUSEEVENTF_LEFTDOWN
                btn_up = MOUSEEVENTF_RIGHTUP if is_eraser else MOUSEEVENTF_LEFTUP

                if action == "down":
                    u32.mouse_event(MOUSEEVENTF_MOVE | MOUSEEVENTF_ABSOLUTE, norm_x, norm_y, 0, 0)
                    u32.mouse_event(btn_down, 0, 0, 0, 0)
                    self.last_pen_button = ("right" if is_eraser else "left")
                elif action == "move":
                    u32.mouse_event(MOUSEEVENTF_MOVE | MOUSEEVENTF_ABSOLUTE, norm_x, norm_y, 0, 0)
                elif action == "up":
                    u32.mouse_event(btn_up, 0, 0, 0, 0)
                    self.last_pen_button = None
            elif self.mouse:
                if action == "down":
                    pen_button = Button.right if is_eraser else Button.left
                    self.mouse.press(pen_button)
                    self.last_pen_button = pen_button
                elif action == "up":
                    pen_button = Button.right if is_eraser else Button.left
                    self.mouse.release(pen_button)
                    self.last_pen_button = None

        elif cmd_type in ("media_query", "query_media"):
            # Mobile app querying live media status, track title, progression, and volume
            state = self.media_controller.get_media_state(
                include_preview=bool(data.get("include_preview", False))
            )
            response = {"type": "media_status"}
            response.update(state)
            if data.get("include_video_preview"):
                response["video_preview"] = capture_selected_media_preview()
            if responder:
                responder(response)
            elif addr and self.sock:
                self._send_udp_json(response, addr)

        elif cmd_type == "media_seek":
            pos = float(data.get("position", 0.0))
            respond_media_state(self.media_controller.seek(pos))

        elif cmd_type == "media_toggle":
            respond_media_state(self.media_controller.toggle_play_pause())

        elif cmd_type == "media_next":
            respond_media_state(self.media_controller.next_track())

        elif cmd_type == "media_prev":
            respond_media_state(self.media_controller.prev_track())

        elif cmd_type == "set_volume":
            vol = float(data.get("volume", 0.5))
            respond_media_state(self.media_controller.set_volume(vol))

        elif cmd_type == "set_mute":
            muted = bool(data.get("mute", False))
            respond_media_state(self.media_controller.set_mute(muted))

        elif cmd_type == "launch_app":
            app_id = str(data.get("app", "")).strip()
            success = self._launch_application(app_id)
            respond({"type": "launch_app_response", "success": success, "app": app_id[:120]})

        elif cmd_type == "system_power":
            action = str(data.get("action", "")).lower().strip()
            self._handle_system_power(action)

        elif cmd_type == "unlock":
            respond({"type": "error", "message": "Workstation unlock is not supported"})

        elif cmd_type in ("query_apps", "query_installed_apps", "list_apps"):
            self._respond_installed_apps_async(addr, responder)

        elif cmd_type == "update_quick_apps":
            requested_paths = data.get("paths", [])
            if not isinstance(requested_paths, list) or len(requested_paths) > 150:
                respond({"type": "quick_apps_update_response", "success": False,
                         "message": "Invalid Quick Apps list"})
                return
            with self._query_cache_lock:
                cached_apps = self._query_cache.get("apps")
                discovered_apps = cached_apps[1] if cached_apps else None
            discovered_by_path = {
                os.path.normcase(os.path.realpath(app.get("path", ""))): app
                for app in (discovered_apps or [])
                if isinstance(app, dict) and app.get("path")
            }
            selected = []
            seen_paths = set()
            for requested_path in requested_paths:
                if not isinstance(requested_path, str) or len(requested_path) > 2048:
                    continue
                resolved = os.path.normcase(os.path.realpath(requested_path.strip()))
                app = discovered_by_path.get(resolved)
                if app and resolved not in seen_paths:
                    selected.append({"name": app.get("name", "App"), "path": app["path"]})
                    seen_paths.add(resolved)
            try:
                saved = save_quick_apps(selected)
                saved_paths = {os.path.normcase(os.path.realpath(app["path"])) for app in saved}
                with self._query_cache_lock:
                    if cached_apps:
                        current_apps = cached_apps[1]
                        for app in current_apps:
                            app["is_quick"] = os.path.normcase(os.path.realpath(app.get("path", ""))) in saved_paths
                        self._query_cache["apps"] = (time.monotonic(), current_apps)
                respond({
                    "type": "quick_apps_update_response",
                    "success": True,
                    "paths": [app["path"] for app in saved],
                    "message": f"Saved {len(saved)} Quick Apps on the PC",
                })
            except (OSError, ValueError) as exc:
                respond({"type": "quick_apps_update_response", "success": False,
                         "message": f"Could not save PC Quick Apps: {str(exc)[:140]}"})

        elif cmd_type in ("query_processes", "list_processes"):
            self._respond_query_async(
                "processes", 3.0, self._get_running_processes,
                "process_list_response", "processes", [], addr, responder
            )

        elif cmd_type == "kill_process":
            pid = data.get("pid")
            success = self._kill_process(int(pid)) if pid else False
            resp = {"type": "kill_process_response", "pid": pid, "success": success}
            if responder:
                responder(resp)
            elif addr and self.sock:
                self._send_udp_json(resp, addr)

        elif cmd_type in ("query_stats", "system_stats", "query_system_stats"):
            self._respond_query_async(
                "stats", 1.0, self._get_system_stats,
                "system_stats_response", None, {}, addr, responder
            )

        elif cmd_type == "camera_snapshot_ack":
            self._ack_udp_snapshot_transfer(data, addr)

        elif cmd_type == "recordings_list":
            respond(self._get_recordings_list())

        elif cmd_type == "live_audio_start":
            owner = self._camera_client_key(addr)
            started, message = self.live_audio_manager.start_pc_to_phone(
                owner, respond, record=bool(data.get("record", False)))
            respond({"type": "live_audio_status", "direction": "pc_to_phone",
                     "active": started, "recording": bool(started and data.get("record", False)),
                     "message": message})

        elif cmd_type == "live_audio_stop":
            self.live_audio_manager.stop_pc_to_phone(self._camera_client_key(addr))
            respond({"type": "live_audio_status", "direction": "pc_to_phone", "active": False})

        elif cmd_type == "phone_audio_start":
            started, message = self.live_audio_manager.start_phone_to_pc(
                self._camera_client_key(addr), record=bool(data.get("record", False)), respond=respond)
            respond({"type": "live_audio_status", "direction": "phone_to_pc",
                     "active": started, "recording": bool(started and data.get("record", False)),
                     "message": message})

        elif cmd_type == "phone_audio_stop":
            self.live_audio_manager.stop_phone_to_pc(self._camera_client_key(addr))
            respond({"type": "live_audio_status", "direction": "phone_to_pc", "active": False})

        elif cmd_type == "phone_audio_frame":
            try:
                pcm = base64.b64decode(str(data.get("data", "")), validate=True)
                if len(pcm) == LiveAudioManager.BLOCK_SAMPLES * 2:
                    self.live_audio_manager.accept_phone_audio(self._camera_client_key(addr), pcm)
            except Exception:
                pass

        elif cmd_type == "recording_download":
            self._start_recording_download(data, addr, respond)

        elif cmd_type == "recording_stream":
            self._start_recording_download(data, addr, respond, stream_mode=True)

        elif cmd_type in ("recording_stream_stop", "recording_download_stop"):
            transfer_id = str(data.get("transfer_id", ""))
            with self._recording_download_lock:
                if transfer_id:
                    cancel = self._recording_download_cancels.get(transfer_id)
                    if cancel:
                        cancel.set()
                else:
                    stop_name = str(data.get("id", ""))
                    for active_id, active_name in self._recording_download_names.items():
                        if active_name == stop_name and (cancel := self._recording_download_cancels.get(active_id)):
                            cancel.set()

        elif cmd_type == "recording_download_ack":
            transfer_id = str(data.get("transfer_id", ""))
            index = data.get("index")
            if transfer_id and isinstance(index, int):
                with self._recording_download_lock:
                    ack = self._recording_download_acks.get((transfer_id, index))
                if ack:
                    ack.set()

        elif cmd_type == "camera_snapshot":
            # Snapshot quality is independent of the lower-resolution live
            # preview. UDP transfers are split into MTU-safe chunks and can be
            # selectively retried; reliable transports receive one full frame.
            b64 = self.camera_manager.get_snapshot(max_width=1920, quality=92)
            if responder:
                resp = {"type": "camera_frame_response", "frame": b64, "is_snapshot": True, "success": b64 is not None}
                responder(resp)
            elif addr and self.sock and b64:
                try:
                    raw_frame = base64.b64decode(b64, validate=True)
                    if not raw_frame or len(raw_frame) > 8 * 1024 * 1024:
                        raise ValueError("Snapshot exceeds the 8 MiB transfer limit")
                    self._start_udp_snapshot_transfer(raw_frame, addr)
                except Exception as exc:
                    self._send_udp_json({"type": "error", "message": f"Snapshot transfer failed: {exc}"}, addr)
            elif addr and self.sock:
                self._send_udp_json({"type": "camera_frame_response", "frame": None, "is_snapshot": True, "success": False}, addr)

        elif cmd_type in ("camera_stream_start", "start_camera_stream"):
            self._stop_camera_stream_for_client()
            owner_key = self._camera_client_key(addr)
            session_id = secrets.token_hex(8)
            with self._camera_stream_owner_lock:
                self._camera_stream_owner = {
                    "key": owner_key,
                    "session_id": session_id,
                    "last_seen": time.monotonic(),
                }
            def _on_frame(b64):
                frame_data = {"type": "camera_frame_response", "frame": b64, "is_streaming": True}
                respond(frame_data)
            max_bytes = 16 * 1024 if responder is None else None
            started = self.camera_manager.start_stream(
                _on_frame,
                max_width=160 if max_bytes else 640,
                quality=35 if max_bytes else 65,
                max_frame_bytes=max_bytes,
                fps=8 if max_bytes else 15,
                on_stream_end_callback=lambda reason: self._on_camera_stream_ended(
                    session_id, owner_key, respond, reason
                ),
            )
            if started:
                if self.auto_record_audio_with_video and not self.audio_manager.recording:
                    audio_path = self.audio_manager.start_recording()
                    with self._camera_stream_owner_lock:
                        owner = self._camera_stream_owner
                        still_active = bool(owner and owner.get("session_id") == session_id)
                        self._camera_auto_audio_recording = bool(audio_path) and still_active
                    if audio_path and not still_active:
                        self.audio_manager.stop_recording()
                    if not audio_path:
                        print(f"[-] Could not record PC microphone with webcam session: {self.audio_manager.last_error}")
            else:
                with self._camera_stream_owner_lock:
                    if self._camera_stream_owner and self._camera_stream_owner.get("session_id") == session_id:
                        self._camera_stream_owner = None
            resp = {"type": "camera_stream_status", "streaming": bool(started)}
            if started and self.camera_manager.last_recording_error:
                resp["message"] = self.camera_manager.last_recording_error
            if responder:
                responder(resp)
            elif addr and self.sock:
                self._send_udp_json(resp, addr)

        elif cmd_type in ("camera_stream_stop", "stop_camera_stream"):
            self._stop_camera_stream_for_client(self._camera_client_key(addr))
            resp = {"type": "camera_stream_status", "streaming": False}
            if responder:
                responder(resp)
            elif addr and self.sock:
                self._send_udp_json(resp, addr)

        elif cmd_type in ("audio_record_start", "start_audio_record"):
            rec_file = self.audio_manager.start_recording()
            resp = {
                "type": "audio_status_response",
                "recording": bool(rec_file),
                "file": os.path.basename(rec_file) if rec_file else "",
                "message": self.audio_manager.last_error or ""
            }
            if responder:
                responder(resp)
            elif addr and self.sock:
                self._send_udp_json(resp, addr)

        elif cmd_type in ("audio_record_stop", "stop_audio_record"):
            rec_file = self.audio_manager.stop_recording()
            resp = {"type": "audio_status_response", "recording": False, "file": os.path.basename(rec_file) if rec_file else ""}
            if responder:
                responder(resp)
            elif addr and self.sock:
                self._send_udp_json(resp, addr)

        elif cmd_type == "text":
            text = data.get("text", "")
            if text and self.keyboard:
                self.keyboard.type(text)

        elif cmd_type == "key_stroke":
            code = data.get("code")
            mod = data.get("mod", 0)
            self._execute_keystroke(code, mod)

        elif cmd_type == "key_down":
            code = data.get("code")
            mod = data.get("mod", 0)
            self._execute_key_down(code, mod)

        elif cmd_type == "key_up":
            code = data.get("code")
            mod = data.get("mod", 0)
            self._execute_key_up(code, mod)

        elif cmd_type == "media":
            code = data.get("code")
            self._execute_media(code)

    def _send_udp_json(self, resp_dict, addr):
        try:
            payload = json.dumps(resp_dict).encode("utf-8")
            if len(payload) <= 60000:
                self.sock.sendto(payload, addr)
                return True
            print(f"[-] Dropped oversized UDP response ({len(payload)} bytes)")
        except Exception:
            pass
        return False

    def _get_recordings_list(self):
        folder = Path(self.audio_manager.recordings_dir).resolve()
        allowed_extensions = {".wav", ".avi", ".mp4", ".m4a", ".mp3", ".webm", ".mkv"}
        files = []
        try:
            for path in folder.iterdir():
                if not path.is_file() or path.suffix.lower() not in allowed_extensions:
                    continue
                if self._is_recording_active(path):
                    continue
                try:
                    stat = path.stat()
                    files.append({
                        "id": path.name,
                        "name": path.name,
                        "size": stat.st_size,
                        "modified": int(stat.st_mtime * 1000),
                        "media_type": "audio" if path.suffix.lower() in {".wav", ".m4a", ".mp3"} else "video",
                    })
                except OSError:
                    continue
        except OSError as exc:
            return {"type": "recordings_list_response", "files": [], "message": str(exc)}
        files.sort(key=lambda item: item["modified"], reverse=True)
        return {"type": "recordings_list_response", "files": files[:500]}

    def _is_recording_active(self, path):
        resolved = str(Path(path).resolve())
        return bool(
            (self.camera_manager.stream_thread and self.camera_manager.stream_thread.is_alive() and self.camera_manager.last_recording_path and
             str(Path(self.camera_manager.last_recording_path).resolve()) == resolved) or
            (self.audio_manager.recording and self.audio_manager.current_filename and
             str(Path(self.audio_manager.current_filename).resolve()) == resolved) or
            (self.live_audio_manager.capture_path and
             str(Path(self.live_audio_manager.capture_path).resolve()) == resolved) or
            (self.live_audio_manager.output_path and
             str(Path(self.live_audio_manager.output_path).resolve()) == resolved)
        )

    def _recording_path(self, recording_id):
        if not isinstance(recording_id, str) or not recording_id or len(recording_id) > 255:
            return None
        if Path(recording_id).name != recording_id:
            return None
        if Path(recording_id).suffix.lower() not in {".wav", ".avi", ".mp4", ".m4a", ".mp3", ".webm", ".mkv"}:
            return None
        folder = Path(self.audio_manager.recordings_dir).resolve()
        candidate = (folder / recording_id).resolve()
        try:
            if os.path.commonpath((str(folder), str(candidate))) != str(folder):
                return None
        except (OSError, ValueError):
            return None
        return candidate if candidate.is_file() else None

    def _start_recording_download(self, data, addr, respond, stream_mode=False):
        prefix = "recording_stream" if stream_mode else "recording_download"
        path = self._recording_path(data.get("id", ""))
        if path is None:
            respond({"type": f"{prefix}_error", "message": "Recording was not found"})
            return
        if self._is_recording_active(path):
            respond({"type": f"{prefix}_error", "message": "Recording is still in progress"})
            return
        try:
            size = path.stat().st_size
        except OSError:
            respond({"type": f"{prefix}_error", "message": "Recording is unavailable"})
            return
        if size <= 0 or size > 1024 * 1024 * 1024:
            respond({"type": f"{prefix}_error", "message": "Recording size is outside the 1 GiB transfer limit"})
            return
        transport = str(addr[0]).lower() if addr else "unknown"
        chunk_size = 900 if transport not in ("bluetooth", "websocket") else (8192 if transport == "bluetooth" else 49152)
        transfer_id = secrets.token_hex(12)
        with self._recording_download_lock:
            if len(self._active_recording_downloads) >= 3:
                respond({"type": f"{prefix}_error", "message": "Too many active recording downloads"})
                return
            self._active_recording_downloads.add(transfer_id)
            cancel = threading.Event()
            self._recording_download_cancels[transfer_id] = cancel
            self._recording_download_names[transfer_id] = path.name
            self._recording_download_owners[transfer_id] = self._camera_client_key(addr)
        threading.Thread(
            target=self._recording_download_worker,
            args=(path, size, chunk_size, transfer_id, transport, respond, stream_mode, cancel),
            daemon=True,
        ).start()

    def _recording_download_worker(self, path, size, chunk_size, transfer_id, transport, respond, stream_mode=False, cancel=None):
        prefix = "recording_stream" if stream_mode else "recording_download"

        def wait_for_ack(ack, timeout):
            deadline = time.monotonic() + timeout
            while time.monotonic() < deadline:
                if cancel and cancel.is_set():
                    return False
                if ack.wait(min(0.1, max(0.0, deadline - time.monotonic()))):
                    return True
            return False

        try:
            digest = hashlib.sha256()
            with path.open("rb") as source:
                while True:
                    if cancel and cancel.is_set():
                        return
                    block = source.read(1024 * 1024)
                    if not block:
                        break
                    digest.update(block)
            if path.stat().st_size != size:
                raise OSError("Recording changed before download started")
            chunk_count = (size + chunk_size - 1) // chunk_size
            start_message = {
                "type": f"{prefix}_start",
                "transfer_id": transfer_id,
                "id": path.name,
                "name": path.name,
                "size": size,
                "chunk_size": chunk_size,
                "chunk_count": chunk_count,
                "sha256": digest.hexdigest(),
            }
            start_ack_key = (transfer_id, -1)
            start_acknowledged = False
            for _attempt in range(5):
                if cancel and cancel.is_set():
                    return
                start_ack = threading.Event()
                with self._recording_download_lock:
                    self._recording_download_acks[start_ack_key] = start_ack
                respond(start_message)
                if wait_for_ack(start_ack, 0.8 if transport == "wifi" else 8.0):
                    start_acknowledged = True
                    break
            with self._recording_download_lock:
                self._recording_download_acks.pop(start_ack_key, None)
            if not start_acknowledged:
                raise TimeoutError("Phone did not accept the recording download")
            with path.open("rb") as source:
                for index in range(chunk_count):
                    if cancel and cancel.is_set():
                        return
                    chunk = source.read(chunk_size)
                    if not chunk:
                        raise OSError("Recording ended before all data was transferred")
                    ack_key = (transfer_id, index)
                    acknowledged = False
                    for attempt in range(5):
                        ack = threading.Event()
                        with self._recording_download_lock:
                            self._recording_download_acks[ack_key] = ack
                        respond({
                            "type": f"{prefix}_chunk",
                            "transfer_id": transfer_id,
                            "index": index,
                            "data": base64.b64encode(chunk).decode("ascii"),
                        })
                        if wait_for_ack(ack, 0.8 if transport == "wifi" else 8.0):
                            acknowledged = True
                            break
                    with self._recording_download_lock:
                        self._recording_download_acks.pop(ack_key, None)
                    if not acknowledged:
                        raise TimeoutError(f"Phone did not acknowledge recording chunk {index + 1}/{chunk_count}")
            respond({"type": f"{prefix}_complete", "transfer_id": transfer_id})
        except Exception as exc:
            print(f"[-] Recording download failed for {path.name}: {exc}")
            if not (cancel and cancel.is_set()):
                respond({"type": f"{prefix}_error", "transfer_id": transfer_id, "message": str(exc)[:200]})
        finally:
            with self._recording_download_lock:
                self._active_recording_downloads.discard(transfer_id)
                self._recording_download_cancels.pop(transfer_id, None)
                self._recording_download_names.pop(transfer_id, None)
                self._recording_download_owners.pop(transfer_id, None)
                stale = [key for key in self._recording_download_acks if key[0] == transfer_id]
                for key in stale:
                    self._recording_download_acks.pop(key, None)

    def _start_udp_snapshot_transfer(self, raw_frame, addr):
        chunk_size = 900
        chunks = [raw_frame[offset:offset + chunk_size] for offset in range(0, len(raw_frame), chunk_size)]
        snapshot_id = secrets.token_hex(12)
        now = time.monotonic()
        with self._snapshot_transfer_lock:
            expired = [key for key, value in self._snapshot_transfers.items() if now - value["created"] > 45.0]
            for key in expired:
                self._snapshot_transfers.pop(key, None)
            # Bound retained image data to at most two recent snapshots.
            while len(self._snapshot_transfers) >= 2:
                oldest = min(self._snapshot_transfers, key=lambda key: self._snapshot_transfers[key]["created"])
                self._snapshot_transfers.pop(oldest, None)
            self._snapshot_transfers[snapshot_id] = {
                "addr": addr,
                "chunks": chunks,
                "created": now,
                "total_bytes": len(raw_frame),
            }
        self._send_udp_json({
            "type": "camera_snapshot_start",
            "snapshot_id": snapshot_id,
            "chunk_count": len(chunks),
            "total_bytes": len(raw_frame),
            "sha256": hashlib.sha256(raw_frame).hexdigest(),
        }, addr)
        threading.Thread(
            target=self._send_udp_snapshot_chunks,
            args=(snapshot_id, list(range(len(chunks)))),
            daemon=True,
        ).start()

    def _send_udp_snapshot_chunks(self, snapshot_id, indexes):
        with self._snapshot_transfer_lock:
            transfer = self._snapshot_transfers.get(snapshot_id)
            if not transfer:
                return
            addr = transfer["addr"]
            chunks = transfer["chunks"]
            created = transfer["created"]
        for offset, index in enumerate(indexes):
            if time.monotonic() - created > 45.0:
                return
            if index < 0 or index >= len(chunks):
                continue
            chunk = chunks[index]
            self._send_udp_json({
                "type": "camera_snapshot_chunk",
                "snapshot_id": snapshot_id,
                "index": index,
                "data": base64.b64encode(chunk).decode("ascii"),
            }, addr)
            if offset % 16 == 15:
                time.sleep(0.001)

    def _ack_udp_snapshot_transfer(self, data, addr):
        snapshot_id = str(data.get("snapshot_id", ""))
        if not snapshot_id or len(snapshot_id) > 32 or not addr:
            return
        try:
            bitmap = base64.b64decode(str(data.get("received_bitmap", "")), validate=True)
        except Exception:
            return
        with self._snapshot_transfer_lock:
            transfer = self._snapshot_transfers.get(snapshot_id)
            if not transfer or transfer["addr"] != addr or time.monotonic() - transfer["created"] > 45.0:
                self._snapshot_transfers.pop(snapshot_id, None)
                return
            chunks = transfer["chunks"]
            if len(bitmap) != (len(chunks) + 7) // 8:
                return
            missing = [index for index in range(len(chunks)) if not (bitmap[index // 8] & (1 << (index % 8)))]
            if not missing:
                self._snapshot_transfers.pop(snapshot_id, None)
                return
        threading.Thread(target=self._send_udp_snapshot_chunks, args=(snapshot_id, missing), daemon=True).start()

    def _release_mouse_buttons(self):
        buttons = self.last_buttons
        if buttons and IS_WINDOWS:
            for mask, flag in ((1, MOUSEEVENTF_LEFTUP), (2, MOUSEEVENTF_RIGHTUP), (4, MOUSEEVENTF_MIDDLEUP)):
                if buttons & mask:
                    u32.mouse_event(flag, 0, 0, 0, 0)
        elif buttons and self.mouse:
            for mask, button in ((1, Button.left), (2, Button.right), (4, Button.middle)):
                if buttons & mask:
                    try:
                        self.mouse.release(button)
                    except Exception:
                        pass
        self.last_buttons = 0

        if self.last_pen_button is not None:
            try:
                if IS_WINDOWS:
                    flag = MOUSEEVENTF_RIGHTUP if self.last_pen_button == "right" else MOUSEEVENTF_LEFTUP
                    u32.mouse_event(flag, 0, 0, 0, 0)
                elif self.mouse:
                    self.mouse.release(self.last_pen_button)
            except Exception:
                pass
            self.last_pen_button = None

        if self.keyboard and self.pressed_keys:
            for key in tuple(self.pressed_keys):
                try:
                    self.keyboard.release(key)
                except Exception:
                    pass
            self.pressed_keys.clear()

    def _release_stale_inputs(self):
        if (self.last_buttons or self.last_pen_button is not None or self.pressed_keys) and time.monotonic() - self.last_input_at >= 30.0:
            self._release_mouse_buttons()
            print("[*] Released held input after 30 seconds without client input")

    def _cached_query(self, key, ttl_seconds, loader):
        now = time.monotonic()
        with self._query_cache_lock:
            cached = self._query_cache.get(key)
            if cached and now - cached[0] < ttl_seconds:
                return cached[1]
            key_lock = self._query_cache_key_locks.setdefault(key, threading.Lock())
        with key_lock:
            now = time.monotonic()
            with self._query_cache_lock:
                cached = self._query_cache.get(key)
                if cached and now - cached[0] < ttl_seconds:
                    return cached[1]
            value = loader()
            with self._query_cache_lock:
                self._query_cache[key] = (time.monotonic(), value)
            return value

    def _respond_query_async(self, cache_key, ttl_seconds, loader, response_type,
                             value_key, default_value, addr, responder=None):
        """Serve a cached snapshot now and refresh slower telemetry outside input handlers."""
        client_key = self._camera_client_key(addr)
        send = responder
        if send is None and addr and self.sock:
            send = lambda payload: self._send_udp_json(payload, addr)
        if send is None:
            return

        now = time.monotonic()
        with self._query_cache_lock:
            cached = self._query_cache.get(cache_key)
            if cached and now - cached[0] < ttl_seconds:
                fresh_value = cached[1]
                stale_value = fresh_value
            else:
                fresh_value = None
                stale_value = cached[1] if cached else default_value
        if fresh_value is not None:
            payload = {"type": response_type}
            if value_key is None:
                payload.update(fresh_value)
            else:
                payload[value_key] = fresh_value
            send(payload)
            return

        with self._async_query_lock:
            self._async_query_waiters.setdefault(cache_key, {})[client_key] = send
            start_refresh = cache_key not in self._async_query_running
            if start_refresh:
                self._async_query_running.add(cache_key)
        payload = {"type": response_type, "loading": True}
        if value_key is None:
            payload.update(stale_value)
        else:
            payload[value_key] = stale_value
        send(payload)
        if start_refresh:
            threading.Thread(
                target=self._refresh_query_for_waiters,
                args=(cache_key, ttl_seconds, loader, response_type, value_key, default_value),
                name=f"Refresh-{cache_key}", daemon=True
            ).start()

    def _refresh_query_for_waiters(self, cache_key, ttl_seconds, loader, response_type,
                                  value_key, default_value):
        value = default_value
        error = ""
        try:
            value = self._cached_query(cache_key, ttl_seconds, loader)
        except Exception as exc:
            error = str(exc)
            print(f"[-] {cache_key} refresh failed: {exc}")
            with self._query_cache_lock:
                cached = self._query_cache.get(cache_key)
                if cached:
                    value = cached[1]
        with self._async_query_lock:
            waiters = list(self._async_query_waiters.pop(cache_key, {}).values())
            self._async_query_running.discard(cache_key)
        payload = {"type": response_type, "loading": False}
        if value_key is None:
            payload.update(value or {})
        else:
            payload[value_key] = value
        if error:
            payload["message"] = f"Could not refresh {cache_key}: {error[:160]}"
        for send in waiters:
            try:
                send(payload)
            except Exception as exc:
                print(f"[-] Could not deliver {cache_key} refresh: {exc}")

    def _respond_installed_apps_async(self, addr, responder=None):
        """Return cached apps immediately and complete slow Start Menu scans off the command path."""
        client_key = self._camera_client_key(addr)
        send = responder
        if send is None and addr and self.sock:
            send = lambda payload: self._send_udp_json(payload, addr)
        if send is None:
            return

        now = time.monotonic()
        fresh_apps = None
        with self._query_cache_lock:
            cached = self._query_cache.get("apps")
            if cached and now - cached[0] < 10.0:
                fresh_apps = cached[1]
            fallback_apps = cached[1] if cached else None
        if fresh_apps is not None:
            send({"type": "installed_apps_response", "apps": fresh_apps, "loading": False})
            return
        if fallback_apps is None:
            try:
                fallback_apps = [dict(app, is_quick=True) for app in load_quick_apps()]
            except Exception:
                fallback_apps = []

        with self._apps_refresh_lock:
            self._apps_refresh_waiters[client_key] = send
            start_refresh = not self._apps_refresh_running
            if start_refresh:
                self._apps_refresh_running = True

        # Send existing quick apps or stale data right away; never hold the UDP
        # input loop, RFCOMM reader, or WebSocket command worker for disk scans.
        send({"type": "installed_apps_response", "apps": fallback_apps, "loading": True})
        if start_refresh:
            threading.Thread(
                target=self._refresh_installed_apps_for_waiters,
                name="InstalledAppsRefresh",
                daemon=True,
            ).start()

    def _refresh_installed_apps_for_waiters(self):
        apps = None
        error = ""
        try:
            apps = self._cached_query("apps", 10.0, self._get_installed_apps)
        except Exception as exc:
            error = str(exc)
            print(f"[-] Installed-app refresh failed: {exc}")
        with self._apps_refresh_lock:
            waiters = list(self._apps_refresh_waiters.values())
            self._apps_refresh_waiters.clear()
            self._apps_refresh_running = False
        if apps is None:
            with self._query_cache_lock:
                cached = self._query_cache.get("apps")
                apps = cached[1] if cached else []
        payload = {"type": "installed_apps_response", "apps": apps, "loading": False}
        if error:
            payload["message"] = f"Could not refresh installed apps: {error[:160]}"
        for send in waiters:
            try:
                send(payload)
            except Exception as exc:
                print(f"[-] Could not deliver installed-app refresh: {exc}")

    def _prefetch_common_queries(self):
        # Prime the first phone views without making connection handshakes wait
        # for Start Menu and process enumeration.
        for key, ttl, loader in (
            ("apps", 10.0, self._get_installed_apps),
            ("processes", 3.0, self._get_running_processes),
            ("stats", 1.0, self._get_system_stats),
        ):
            try:
                self._cached_query(key, ttl, loader)
            except Exception as exc:
                print(f"[-] Could not prefetch {key}: {exc}")

    def _get_installed_apps(self):
        apps = []
        seen = set()
        quick_paths = set()
        if QUICK_APPS_PATH.exists():
            quick_paths = {os.path.realpath(app["path"]).lower() for app in load_quick_apps()}
        start_paths = [
            os.path.expandvars(r"%ProgramData%\Microsoft\Windows\Start Menu\Programs"),
            os.path.expandvars(r"%AppData%\Microsoft\Windows\Start Menu\Programs")
        ]
        for p in start_paths:
            if not os.path.exists(p):
                continue
            for root, _, files in os.walk(p):
                for f in files:
                    if f.lower().endswith(".lnk"):
                        name = os.path.splitext(f)[0]
                        clean_name = name.lower()
                        if any(skip in clean_name for skip in ["uninstall", "help", "readme", "documentation", "setup", "install"]):
                            continue
                        if clean_name not in seen:
                            seen.add(clean_name)
                            path = os.path.realpath(os.path.join(root, f))
                            apps.append({"name": name, "path": path, "is_quick": path.lower() in quick_paths})
        apps.sort(key=lambda x: x["name"].lower())
        apps = apps[:150]
        if not QUICK_APPS_PATH.exists():
            seeded = load_quick_apps(apps)
            quick_paths = {os.path.realpath(app["path"]).lower() for app in seeded}
        for app in apps:
            app["is_quick"] = os.path.realpath(app["path"]).lower() in quick_paths
        return apps

    def _get_running_processes(self):
        procs = []
        try:
            import psutil
            now = time.monotonic()
            logical_cpus = max(1, psutil.cpu_count() or 1)
            live_pids = set()
            for p in psutil.process_iter(['pid', 'name']):
                try:
                    info = p.info
                    pid = info.get('pid')
                    if not pid:
                        continue
                    live_pids.add(pid)
                    with p.oneshot():
                        cpu_times = p.cpu_times()
                        memory_mb = p.memory_info().rss / (1024.0 * 1024.0)
                    cpu_total = cpu_times.user + cpu_times.system
                    previous = self._process_cpu_samples.get(pid)
                    cpu_percent = 0.0
                    if previous:
                        elapsed = max(0.001, now - previous[1])
                        cpu_percent = max(0.0, (cpu_total - previous[0]) * 100.0 / elapsed / logical_cpus)
                    self._process_cpu_samples[pid] = (cpu_total, now)
                    if memory_mb >= 1.0:
                        procs.append({
                            'pid': pid,
                            'name': info.get('name') or "Unknown process",
                            'cpu': round(cpu_percent, 1),
                            'ram': round(memory_mb, 1)
                        })
                except Exception:
                    pass
            self._process_cpu_samples = {pid: sample for pid, sample in self._process_cpu_samples.items() if pid in live_pids}
            procs.sort(key=lambda x: x['ram'], reverse=True)
        except Exception as e:
            print(f"[-] Error querying processes: {e}")
        return procs[:35]

    def _get_system_stats(self):
        stats = {"cpu": 0.0, "ram": 0.0, "battery": -1, "power_plugged": True, "disk": 0.0}
        try:
            import psutil
            stats["cpu"] = round(psutil.cpu_percent(interval=None), 1)
            stats["ram"] = round(psutil.virtual_memory().percent, 1)
            disk = psutil.disk_usage(os.path.abspath(os.sep))
            stats["disk"] = round(disk.percent, 1)
        except Exception as e:
            print(f"[-] Error querying system stats: {e}")
        try:
            import psutil
            battery = psutil.sensors_battery()
            if battery:
                stats["battery"] = round(battery.percent, 1)
                stats["power_plugged"] = bool(battery.power_plugged)
        except Exception:
            pass
        with self._hardware_metrics_lock:
            stats.update(self._hardware_metrics)
        return stats

    def _start_performance_sampler(self):
        if not IS_WINDOWS or (self._performance_sampler_thread and self._performance_sampler_thread.is_alive()):
            return
        self._performance_sampler_running = True

        def _run():
            while self.running and self._performance_sampler_running:
                metrics = self._collect_windows_performance_metrics()
                if metrics is not None:
                    with self._hardware_metrics_lock:
                        self._hardware_metrics = metrics
                for _ in range(20):
                    if not self.running or not self._performance_sampler_running:
                        break
                    time.sleep(0.1)

        self._performance_sampler_thread = threading.Thread(target=_run, name="HardwareMetricSampler", daemon=True)
        self._performance_sampler_thread.start()

    @staticmethod
    def _collect_windows_performance_metrics():
        if not IS_WINDOWS:
            return None
        script = r"""
$ErrorActionPreference = 'SilentlyContinue'
$disk = -1.0
$gpu = @()
$adapters = @()
try {
  $paths = @('\PhysicalDisk(_Total)\% Disk Time', '\GPU Engine(*)\Utilization Percentage')
  $samples = Get-Counter -Counter $paths -SampleInterval 1 -MaxSamples 2
  $counters = @($samples[-1].CounterSamples)
  $diskSample = $counters | Where-Object { $_.Path -like '*PhysicalDisk*' } | Select-Object -Last 1
  if ($diskSample) { $disk = [math]::Min(100, [math]::Max(0, [double]$diskSample.CookedValue)) }
  $gpu = @($counters | Where-Object { $_.Path -like '*GPU Engine*' } | Select-Object Path, CookedValue)
} catch {}
try { $adapters = @(Get-CimInstance -ClassName Win32_VideoController | ForEach-Object { $_.Name }) } catch {}
[pscustomobject]@{ disk_activity = $disk; gpu_samples = $gpu; adapters = $adapters } | ConvertTo-Json -Compress -Depth 5
"""
        try:
            flags = getattr(subprocess, "CREATE_NO_WINDOW", 0)
            result = subprocess.run(
                ["powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script],
                capture_output=True, text=True, timeout=15, creationflags=flags,
            )
            if result.returncode != 0 or not result.stdout.strip():
                return None
            payload = json.loads(result.stdout.strip().splitlines()[-1])
            return Server._parse_windows_performance_payload(payload)
        except Exception as exc:
            print(f"[-] Windows hardware metrics unavailable: {exc}")
            return None

    @staticmethod
    def _parse_windows_performance_payload(payload):
        adapter_names = payload.get("adapters") or []
        if isinstance(adapter_names, str):
            adapter_names = [adapter_names]
        gpu_samples = payload.get("gpu_samples") or []
        if isinstance(gpu_samples, dict):
            gpu_samples = [gpu_samples]
        engines = {}
        for sample in gpu_samples:
            path = str(sample.get("Path", ""))
            match = re.search(r"phys_(\d+).*?engtype_([^\\)]+)", path, re.IGNORECASE)
            if not match:
                continue
            adapter_index = int(match.group(1))
            engine_type = match.group(2).split("_")[0]
            utilization = max(0.0, min(100.0, float(sample.get("CookedValue", 0.0))))
            by_engine = engines.setdefault(adapter_index, {})
            by_engine[engine_type] = min(100.0, by_engine.get(engine_type, 0.0) + utilization)

        gpus = []
        count = max(len(adapter_names), (max(engines) + 1) if engines else 0)
        for index in range(count):
            name = str(adapter_names[index]) if index < len(adapter_names) else f"GPU {index}"
            by_engine = engines.get(index, {})
            peak_engine = max(by_engine, key=by_engine.get) if by_engine else ""
            utilization = round(by_engine.get(peak_engine, -1.0), 1) if peak_engine else -1.0
            gpus.append({"index": index, "name": name, "utilization": utilization, "engine": peak_engine})
        raw_disk_activity = payload.get("disk_activity", -1.0)
        disk_activity = float(raw_disk_activity) if raw_disk_activity not in (None, "") else -1.0
        return {
            "disk_activity": round(max(0.0, min(100.0, disk_activity)), 1) if disk_activity >= 0 else -1.0,
            "gpus": gpus,
            # Windows Task Manager exposes NPU data through its neural-processing
            # diagnostics, but those counters are not the GPU Engine counters used
            # here. Don't infer NPU load from a GPU or adapter name.
            "npu": -1.0,
        }

    def _kill_process(self, pid):
        try:
            import psutil
            p = psutil.Process(pid)
            name = p.name()
            p.terminate()
            print(f"[+] Terminated process PID {pid} ({name})")
            return True
        except Exception as e:
            print(f"[-] Could not kill PID {pid}: {e}")
            return False

    def _launch_application(self, app_id):
        if not app_id:
            return False
        app_id_clean = str(app_id).strip()

        # Only launch discovered Windows shortcuts rooted in the two Start Menu
        # locations. Never accept arbitrary executable paths from a client.
        if IS_WINDOWS and os.path.isfile(app_id_clean) and app_id_clean.lower().endswith(".lnk"):
            real_path = os.path.realpath(app_id_clean)
            roots = [
                os.path.realpath(os.path.expandvars(r"%ProgramData%\Microsoft\Windows\Start Menu\Programs")),
                os.path.realpath(os.path.expandvars(r"%AppData%\Microsoft\Windows\Start Menu\Programs"))
            ]
            try:
                if any(os.path.commonpath((real_path, root)).lower() == root.lower() for root in roots):
                    os.startfile(real_path)
                    print(f"[+] Launched discovered shortcut: {real_path}")
                    return True
            except (OSError, ValueError) as e:
                print(f"[-] Shortcut validation failed: {e}")
                return False

        # 2. Strict system tools mapping without shell=True
        clean_key = app_id_clean.lower()
        try:
            if clean_key == "calc":
                os.startfile("calc:")
                return True
            elif clean_key == "settings":
                os.startfile("ms-settings:")
                return True
            elif clean_key == "explorer":
                import subprocess
                subprocess.Popen(["explorer.exe"])
                return True
            elif clean_key == "taskmgr":
                import subprocess
                subprocess.Popen(["taskmgr.exe"])
                return True
            elif clean_key == "notepad":
                import subprocess
                subprocess.Popen(["notepad.exe"])
                return True
            elif clean_key in ("terminal", "cmd", "powershell") and IS_WINDOWS:
                import subprocess
                try:
                    subprocess.Popen(["wt.exe"])
                except Exception:
                    subprocess.Popen(["powershell.exe"])
                return True
            elif clean_key in ("browser", "chrome", "edge") and IS_WINDOWS:
                os.startfile("https://www.google.com")
                return True
            elif clean_key == "onenote" and IS_WINDOWS:
                os.startfile("onenote:")
                return True
            elif clean_key == "spotify" and IS_WINDOWS:
                os.startfile("spotify:")
                return True
        except Exception as e:
            print(f"[-] Safe app launch failed for {clean_key}: {e}")
            return False

        print(f"[-] Blocked unrecognized or untrusted app launch: {app_id_clean}")
        return False

    def _handle_system_power(self, action):
        import subprocess
        try:
            if action == "lock":
                if IS_WINDOWS:
                    u32.LockWorkStation()
                    print("[+] Workstation locked")
            elif action == "sleep":
                if IS_WINDOWS:
                    ctypes.windll.PowrProf.SetSuspendState(0, 1, 0)
                    print("[+] System sleep initiated")
            elif action == "restart":
                if IS_WINDOWS:
                    subprocess.Popen(["shutdown", "/r", "/t", "0"])
                    print("[+] System restart initiated")
            elif action == "shutdown":
                if IS_WINDOWS:
                    subprocess.Popen(["shutdown", "/s", "/t", "0"])
                    print("[+] System shutdown initiated")
        except Exception as e:
            print(f"[-] Power action error ({action}): {e}")

    def _resolve_key(self, code):
        if not HAS_PYNPUT:
            return None
        key = KEY_MAP.get(code)
        if key:
            return key
        if 0x04 <= code <= 0x1D:  # A-Z
            return chr(ord('a') + (code - 0x04))
        elif 0x1E <= code <= 0x27:  # 1-9, 0
            return '0' if code == 0x27 else str(code - 0x1E + 1)
        return None

    def _execute_key_down(self, code, mod):
        if not self.keyboard:
            return
        self.last_input_at = time.monotonic()
        if mod & 1:
            self._press_key(Key.ctrl)
        if mod & 2:
            self._press_key(Key.shift)
        if mod & 4:
            self._press_key(Key.alt)
        if mod & 8:
            self._press_key(Key.cmd)
        key = self._resolve_key(code)
        if key:
            self._press_key(key)

    def _press_key(self, key):
        if key not in self.pressed_keys:
            self.keyboard.press(key)
            self.pressed_keys.add(key)

    def _execute_key_up(self, code, mod):
        if not self.keyboard:
            return
        self.last_input_at = time.monotonic()
        key = self._resolve_key(code)
        if key and key in self.pressed_keys:
            self.keyboard.release(key)
            self.pressed_keys.discard(key)
        if mod & 8:
            self._release_key(Key.cmd)
        if mod & 4:
            self._release_key(Key.alt)
        if mod & 2:
            self._release_key(Key.shift)
        if mod & 1:
            self._release_key(Key.ctrl)

    def _release_key(self, key):
        if key in self.pressed_keys:
            self.keyboard.release(key)
            self.pressed_keys.discard(key)

    def _execute_keystroke(self, code, mod):
        self._execute_key_down(code, mod)
        time.sleep(0.01)
        self._execute_key_up(code, mod)

    def _execute_media(self, code):
        if not self.keyboard:
            return
        mapping = {
            0xCD: Key.media_play_pause,
            0xB5: Key.media_next,
            0xB6: Key.media_previous,
            0xE9: Key.media_volume_up,
            0xEA: Key.media_volume_down,
            0xE2: Key.media_volume_mute,
        }
        key = mapping.get(code)
        if key:
            self.keyboard.press(key)
            self.keyboard.release(key)

    def stop(self):
        self.running = False
        self._performance_sampler_running = False
        with self._recording_download_lock:
            for cancel in self._recording_download_cancels.values():
                cancel.set()
        if hasattr(self, "live_audio_manager") and self.live_audio_manager:
            try:
                self.live_audio_manager.stop_pc_to_phone()
                self.live_audio_manager.stop_phone_to_pc()
            except Exception:
                pass
        self._release_mouse_buttons()
        if hasattr(self, "bt_server") and self.bt_server:
            try:
                self.bt_server.stop()
            except Exception:
                pass
        if hasattr(self, "ws_server") and self.ws_server:
            try:
                self.ws_server.stop()
            except Exception:
                pass
        if hasattr(self, "camera_manager") and self.camera_manager:
            try:
                self._stop_camera_stream_for_client()
            except Exception:
                pass
        if hasattr(self, "audio_manager") and self.audio_manager:
            try:
                self.audio_manager.stop_recording()
            except Exception:
                pass
        if self.sock:
            try:
                self.sock.close()
            except Exception:
                pass
            self.sock = None
        if self.discovery_sock:
            try:
                self.discovery_sock.close()
            except Exception:
                pass
            self.discovery_sock = None
        if self.on_status_callback:
            self.on_status_callback(False)

    def start(self):
        ensure_interactive_desktop()
        self.running = True
        self._start_performance_sampler()
        threading.Thread(target=self._prefetch_common_queries, name="InitialPhoneData", daemon=True).start()
        local_ip = self.get_local_ip()
        print("=" * 60)
        print("        BT MOUSE & KEYBOARD - PC COMPANION SERVER")
        print("=" * 60)
        print(f"[*] Local PC IP Address : {local_ip}")
        print(f"[*] Input UDP Port      : {self.port}")
        print(f"[*] Auto-Discovery Port : {self.discovery_port}")
        print(f"[*] Remote WAN WS Port  : {self.ws_port}")
        print(f"[*] Pairing PIN         : {self.auth_token}")
        print(f"[*] Bluetooth RFCOMM    : Active (SPP)")
        print(f"[*] Input Backend       : {'Native Windows user32 + pynput' if IS_WINDOWS else 'pynput'}")
        print(f"[*] Status              : READY & LISTENING")
        print("=" * 60)
        print("Tips:")
        print("1. In Android App: Connect via Wi-Fi, Bluetooth (SPP), or Remote WAN!")
        print("2. Tap 'Auto-Discover PC Server' or select your PC in Bluetooth tab.")
        print("3. Press Ctrl+C to stop.\n")

        if self.on_status_callback:
            self.on_status_callback(True)

        self.start_discovery_responder()
        if hasattr(self, "bt_server") and self.bt_server:
            try:
                self.bt_server.start()
            except Exception as e:
                print(f"[!] Bluetooth RFCOMM server start note: {e}")

        if hasattr(self, "ws_server") and self.ws_server:
            try:
                self.ws_server.start()
            except Exception as e:
                print(f"[!] WebSocket server start note: {e}")

        self.sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        try:
            self.sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        except Exception:
            pass
        try:
            self.sock.bind(("0.0.0.0", self.port))
            self.sock.settimeout(0.5)
        except Exception as e:
            print(f"[!] Socket bind error: {e}")
            if self.on_status_callback:
                self.on_status_callback(False)
            return

        while self.running:
            try:
                data, addr = self.sock.recvfrom(2048)
                msg_str = data.decode("utf-8")
                parsed = json.loads(msg_str)
                self.handle_packet(parsed, addr)

                self.packets_received += 1
                now = time.time()
                if now - self.last_log_time >= 3.0:
                    if self.packets_received > 0:
                        print(f"[*] Active - Received {self.packets_received} input packets from {addr[0]}")
                    self.last_log_time = now
            except socket.timeout:
                self._release_stale_inputs()
                self._expire_udp_camera_stream()
                continue
            except KeyboardInterrupt:
                print("\n[!] Stopping server...")
                self.running = False
                break
            except Exception as e:
                if self.running:
                    print(f"[!] Error processing packet: {e}")
                    traceback.print_exc()

        if self.sock:
            try:
                self.sock.close()
            except Exception:
                pass
            self.sock = None

        if self.on_status_callback:
            self.on_status_callback(False)

if __name__ == "__main__":
    server = Server()
    try:
        server.start()
    except KeyboardInterrupt:
        server.stop()
