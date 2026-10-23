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
import secrets
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

    def get_media_state(self, include_preview=False, max_artwork_bytes=12 * 1024):
        vol, muted = self.get_volume()
        self.last_state["volume"] = round(vol, 2)
        self.last_state["muted"] = muted
        self.last_state["is_muted"] = muted

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
                else:
                    self.last_state.update({
                        "has_media": False, "title": "", "artist": "", "album": "",
                        "status": "NoMedia", "position": 0.0, "duration": 0.0,
                        "artwork": ""
                    })
            except Exception:
                pass
        return self.last_state

    def seek(self, seconds):
        if self.has_winrt:
            try:
                import asyncio
                import datetime

                async def _seek():
                    mgr = await self.wmc.GlobalSystemMediaTransportControlsSessionManager.request_async()
                    session = mgr.get_current_session()
                    if not session:
                        sessions = mgr.get_sessions()
                        if sessions:
                            session = sessions[0]
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
                    mgr = await self.wmc.GlobalSystemMediaTransportControlsSessionManager.request_async()
                    session = mgr.get_current_session()
                    if not session:
                        sessions = mgr.get_sessions()
                        if sessions:
                            session = sessions[0]
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
                    mgr = await self.wmc.GlobalSystemMediaTransportControlsSessionManager.request_async()
                    session = mgr.get_current_session()
                    if not session:
                        sessions = mgr.get_sessions()
                        if sessions:
                            session = sessions[0]
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
                    mgr = await self.wmc.GlobalSystemMediaTransportControlsSessionManager.request_async()
                    session = mgr.get_current_session()
                    if not session:
                        sessions = mgr.get_sessions()
                        if sessions:
                            session = sessions[0]
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
        self.lock = threading.Lock()
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

    def start_stream(self, on_frame_callback, max_width=640, quality=65, max_frame_bytes=None, fps=15):
        if not self.has_cv2:
            return False
        try:
            import cv2
            cap = cv2.VideoCapture(0, cv2.CAP_DSHOW)
            if not cap.isOpened():
                cap.release()
                cap = cv2.VideoCapture(0)
            if not cap.isOpened():
                cap.release()
                return False
            cap.set(cv2.CAP_PROP_FRAME_WIDTH, max_width)
            cap.set(cv2.CAP_PROP_FRAME_HEIGHT, max(90, int(max_width * 3 / 4)))
        except Exception as e:
            print(f"[-] Camera open error: {e}")
            return False
        with self.lock:
            if self.streaming:
                cap.release()
                return True
            self.streaming = True

        def _loop():
            try:
                while self.streaming:
                    ret, frame = cap.read()
                    if ret and frame is not None:
                        height, width = frame.shape[:2]
                        if width > max_width:
                            frame = cv2.resize(frame, (max_width, int(height * max_width / width)))
                        ret, buf = False, None
                        for frame_quality in (quality, max(20, quality - 10), max(15, quality - 20)):
                            ret, buf = cv2.imencode('.jpg', frame, [int(cv2.IMWRITE_JPEG_QUALITY), frame_quality])
                            if ret and (max_frame_bytes is None or buf.nbytes <= max_frame_bytes):
                                break
                        if ret and (max_frame_bytes is None or buf.nbytes <= max_frame_bytes):
                            b64 = base64.b64encode(buf).decode('utf-8')
                            on_frame_callback(b64)
                    time.sleep(1.0 / max(1, fps))
                cap.release()
            except Exception as e:
                print(f"[-] Camera stream error: {e}")
            finally:
                self.streaming = False

        self.stream_thread = threading.Thread(target=_loop, daemon=True)
        self.stream_thread.start()
        return True

    def stop_stream(self):
        with self.lock:
            self.streaming = False


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
        self.media_controller = MediaController(fallback_media_func=self._execute_media)

        # Bluetooth RFCOMM Server for mobile companion
        self.bt_server = BluetoothRfcommServer(self.handle_packet, on_client_callback=self._on_bt_client)

        # Remote WebSocket Server for WAN / Internet connections
        self.ws_server = RemoteWebSocketServer(port=self.ws_port, command_handler=self.handle_packet,
                                               on_client_callback=self._on_ws_client, auth_token=self.auth_token)

        # Camera & Audio Managers
        self.camera_manager = CameraManager()
        self.audio_manager = AudioManager()

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
        if self.on_client_callback:
            self.on_client_callback(self.connected_client, is_connected)

    def _on_ws_client(self, client_name, is_connected):
        if is_connected:
            self.connected_client = client_name
        else:
            if self.connected_client == client_name:
                self.connected_client = None
        if self.on_client_callback:
            self.on_client_callback(self.connected_client, is_connected)

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
                    "version": "1.3.6"
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

        if cmd_type == "ping":
            respond({"type": "pong", "time": time.time()})
            return

        if cmd_type == "disconnect":
            self._release_mouse_buttons()
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
            apps = self._get_installed_apps()
            resp = {"type": "installed_apps_response", "apps": apps}
            if responder:
                responder(resp)
            elif addr and self.sock:
                self._send_udp_json(resp, addr)

        elif cmd_type in ("query_processes", "list_processes"):
            procs = self._get_running_processes()
            resp = {"type": "process_list_response", "processes": procs}
            if responder:
                responder(resp)
            elif addr and self.sock:
                self._send_udp_json(resp, addr)

        elif cmd_type == "kill_process":
            pid = data.get("pid")
            success = self._kill_process(int(pid)) if pid else False
            resp = {"type": "kill_process_response", "pid": pid, "success": success}
            if responder:
                responder(resp)
            elif addr and self.sock:
                self._send_udp_json(resp, addr)

        elif cmd_type in ("query_stats", "system_stats", "query_system_stats"):
            stats = self._get_system_stats()
            resp = {"type": "system_stats_response"}
            resp.update(stats)
            if responder:
                responder(resp)
            elif addr and self.sock:
                self._send_udp_json(resp, addr)

        elif cmd_type == "camera_snapshot":
            # Limit UDP snapshots to a bounded datagram; WebSocket/RFCOMM can
            # carry the higher quality default image without UDP fragmentation.
            b64 = self.camera_manager.get_snapshot(
                max_width=320 if responder is None else 800,
                quality=45 if responder is None else 75,
                max_encoded_bytes=24 * 1024 if responder is None else None
            )
            resp = {"type": "camera_frame_response", "frame": b64, "is_snapshot": True, "success": b64 is not None}
            if responder:
                responder(resp)
            elif addr and self.sock:
                self._send_udp_json(resp, addr)

        elif cmd_type in ("camera_stream_start", "start_camera_stream"):
            def _on_frame(b64):
                frame_data = {"type": "camera_frame_response", "frame": b64, "is_streaming": True}
                respond(frame_data)
            max_bytes = 16 * 1024 if responder is None else None
            started = self.camera_manager.start_stream(
                _on_frame,
                max_width=160 if max_bytes else 640,
                quality=35 if max_bytes else 65,
                max_frame_bytes=max_bytes,
                fps=8 if max_bytes else 15
            )
            resp = {"type": "camera_stream_status", "streaming": bool(started)}
            if responder:
                responder(resp)
            elif addr and self.sock:
                self._send_udp_json(resp, addr)

        elif cmd_type in ("camera_stream_stop", "stop_camera_stream"):
            self.camera_manager.stop_stream()
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
        return apps[:150]

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
                    cpu_times = p.cpu_times()
                    cpu_total = cpu_times.user + cpu_times.system
                    previous = self._process_cpu_samples.get(pid)
                    cpu_percent = 0.0
                    if previous:
                        elapsed = max(0.001, now - previous[1])
                        cpu_percent = max(0.0, (cpu_total - previous[0]) * 100.0 / elapsed / logical_cpus)
                    self._process_cpu_samples[pid] = (cpu_total, now)
                    memory_mb = p.memory_info().rss / (1024.0 * 1024.0)
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
            stats["cpu"] = round(psutil.cpu_percent(interval=0.1), 1)
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
        return stats

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
                self.camera_manager.stop_stream()
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
