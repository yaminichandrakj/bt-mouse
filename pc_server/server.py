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
        0x58: Key.enter,
    }


class MediaController:
    """Windows native media transport & master volume controller"""
    def __init__(self, fallback_media_func=None):
        self.fallback_media_func = fallback_media_func
        self.has_winrt = False
        self.has_pycaw = False
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
            try:
                speakers = self.audio_utilities.GetSpeakers()
                vol = speakers.EndpointVolume
                return vol.GetMasterVolumeLevelScalar(), bool(vol.GetMute())
            except Exception:
                pass
        return 0.5, False

    def set_volume(self, scalar):
        if self.has_pycaw:
            try:
                speakers = self.audio_utilities.GetSpeakers()
                vol = speakers.EndpointVolume
                val = max(0.0, min(1.0, float(scalar)))
                vol.SetMasterVolumeLevelScalar(val, None)
                self.last_state["volume"] = round(val, 2)
                return True
            except Exception:
                pass
        return False

    def set_mute(self, muted):
        if self.has_pycaw:
            try:
                speakers = self.audio_utilities.GetSpeakers()
                vol = speakers.EndpointVolume
                vol.SetMute(1 if muted else 0, None)
                self.last_state["muted"] = bool(muted)
                return True
            except Exception:
                pass
        return False

    def get_media_state(self):
        vol, muted = self.get_volume()
        self.last_state["volume"] = round(vol, 2)
        self.last_state["muted"] = muted

        if self.has_winrt:
            try:
                import asyncio

                async def _query():
                    mgr = await self.wmc.GlobalSystemMediaTransportControlsSessionManager.request_async()
                    session = mgr.get_current_session()
                    if session:
                        props = await session.try_get_media_properties_async()
                        info = session.get_playback_info()
                        timeline = session.get_timeline_properties()
                        status_map = {0: "Closed", 1: "Opened", 2: "Changing", 3: "Stopped", 4: "Paused", 5: "Playing"}
                        status_str = status_map.get(int(info.playback_status), "Playing")
                        pos_sec = timeline.position.total_seconds() if timeline and timeline.position else 0.0
                        end_sec = timeline.end_time.total_seconds() if timeline and timeline.end_time else 0.0
                        return {
                            "has_media": True,
                            "title": props.title or "Unknown Track",
                            "artist": props.artist or "Unknown Artist",
                            "album": props.album_title or "",
                            "status": status_str,
                            "position": round(pos_sec, 1),
                            "duration": round(end_sec, 1),
                            "volume": round(vol, 2),
                            "muted": muted
                        }
                    return None

                res = asyncio.run(_query())
                if res:
                    self.last_state.update(res)
                    return self.last_state
                else:
                    self.last_state["has_media"] = False
                    self.last_state["status"] = "NoMedia"
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
        if self.has_winrt:
            try:
                import asyncio

                async def _toggle():
                    mgr = await self.wmc.GlobalSystemMediaTransportControlsSessionManager.request_async()
                    session = mgr.get_current_session()
                    if session:
                        await session.try_toggle_play_pause_async()
                        return True
                    return False

                if asyncio.run(_toggle()):
                    return True
            except Exception:
                pass
        if self.fallback_media_func:
            self.fallback_media_func(0xCD)
        return True

    def next_track(self):
        if self.has_winrt:
            try:
                import asyncio

                async def _next():
                    mgr = await self.wmc.GlobalSystemMediaTransportControlsSessionManager.request_async()
                    session = mgr.get_current_session()
                    if session:
                        await session.try_skip_next_async()
                        return True
                    return False

                if asyncio.run(_next()):
                    return True
            except Exception:
                pass
        if self.fallback_media_func:
            self.fallback_media_func(0xB5)
        return True

    def prev_track(self):
        if self.has_winrt:
            try:
                import asyncio

                async def _prev():
                    mgr = await self.wmc.GlobalSystemMediaTransportControlsSessionManager.request_async()
                    session = mgr.get_current_session()
                    if session:
                        await session.try_skip_previous_async()
                        return True
                    return False

                if asyncio.run(_prev()):
                    return True
            except Exception:
                pass
        if self.fallback_media_func:
            self.fallback_media_func(0xB6)
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
                                try:
                                    payload = json.dumps(resp_dict) + "\n"
                                    writer.write_string(payload)
                                    if self.loop and self.loop.is_running():
                                        asyncio.run_coroutine_threadsafe(writer.store_async(), self.loop)
                                except Exception:
                                    pass
                            self.command_handler(data, addr=("Bluetooth", 0), responder=_respond)
                        except Exception:
                            pass
        except Exception:
            pass
        finally:
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

    def get_snapshot(self):
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
            if w > 800:
                frame = cv2.resize(frame, (800, int(h * 800 / w)))
            ret, buf = cv2.imencode('.jpg', frame, [int(cv2.IMWRITE_JPEG_QUALITY), 75])
            if ret:
                return base64.b64encode(buf).decode('utf-8')
        except Exception as e:
            print(f"[-] Camera snapshot error: {e}")
        return None

    def start_stream(self, on_frame_callback):
        if not self.has_cv2:
            return
        with self.lock:
            if self.streaming:
                return
            self.streaming = True

        def _loop():
            try:
                import cv2
                cap = cv2.VideoCapture(0, cv2.CAP_DSHOW)
                if not cap.isOpened():
                    cap = cv2.VideoCapture(0)
                if not cap.isOpened():
                    self.streaming = False
                    return
                cap.set(cv2.CAP_PROP_FRAME_WIDTH, 640)
                cap.set(cv2.CAP_PROP_FRAME_HEIGHT, 480)
                while self.streaming:
                    ret, frame = cap.read()
                    if ret and frame is not None:
                        ret, buf = cv2.imencode('.jpg', frame, [int(cv2.IMWRITE_JPEG_QUALITY), 60])
                        if ret:
                            b64 = base64.b64encode(buf).decode('utf-8')
                            on_frame_callback(b64)
                    time.sleep(0.066) # ~15 FPS
                cap.release()
            except Exception as e:
                print(f"[-] Camera stream error: {e}")
            finally:
                self.streaming = False

        self.stream_thread = threading.Thread(target=_loop, daemon=True)
        self.stream_thread.start()

    def stop_stream(self):
        with self.lock:
            self.streaming = False


class AudioManager:
    """Manages laptop microphone audio recording"""
    def __init__(self):
        self.recording = False
        self.record_thread = None
        self.recordings_dir = os.path.join(os.path.dirname(os.path.abspath(__file__)), "recordings")
        os.makedirs(self.recordings_dir, exist_ok=True)
        self.current_filename = None
        self.has_audio = False
        try:
            import sounddevice as sd
            import wave
            self.has_audio = True
        except ImportError:
            pass

    def start_recording(self):
        if not self.has_audio:
            return None
        if self.recording:
            return self.current_filename
        self.recording = True
        ts = time.strftime("%Y%m%d_%H%M%S")
        self.current_filename = os.path.join(self.recordings_dir, f"audio_{ts}.wav")

        def _loop():
            try:
                import sounddevice as sd
                import wave
                fs = 16000
                with wave.open(self.current_filename, 'wb') as wf:
                    wf.setnchannels(1)
                    wf.setsampwidth(2)
                    wf.setframerate(fs)
                    with sd.InputStream(samplerate=fs, channels=1, dtype='int16') as stream:
                        while self.recording:
                            data, _ = stream.read(1024)
                            wf.writeframes(data.tobytes())
            except Exception as e:
                print(f"[-] Audio recording error: {e}")
            finally:
                self.recording = False

        self.record_thread = threading.Thread(target=_loop, daemon=True)
        self.record_thread.start()
        print(f"[+] Started microphone recording to {self.current_filename}")
        return self.current_filename

    def stop_recording(self):
        self.recording = False
        if self.record_thread and self.record_thread.is_alive():
            self.record_thread.join(timeout=1.0)
        print(f"[+] Stopped microphone recording: {self.current_filename}")
        return self.current_filename


class RemoteWebSocketServer:
    """Secure WebSocket Server for remote WAN & internet connections"""
    def __init__(self, port=53531, command_handler=None, on_client_callback=None, auth_token="123456"):
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
            async with websockets.serve(self._handle_client, "0.0.0.0", self.port):
                print(f"[*] Remote WAN WebSocket Server listening on ws://0.0.0.0:{self.port}")
                while self.running:
                    await asyncio.sleep(1)
        except Exception as e:
            print(f"[!] WebSocket server start error: {e}")

    async def _handle_client(self, websocket):
        self.clients.add(websocket)
        authenticated = False
        client_id = "Remote-WAN Client"
        if self.on_client_callback:
            self.on_client_callback(client_id, True)
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
                        if not self.auth_token or token == self.auth_token:
                            authenticated = True
                            await websocket.send(json.dumps({"type": "auth_response", "authenticated": True, "message": "Access granted"}))
                            continue
                        else:
                            await websocket.send(json.dumps({"type": "auth_response", "authenticated": False, "message": "Invalid Security Key"}))
                            await websocket.close()
                            return
                    else:
                        if not self.auth_token:
                            authenticated = True
                        else:
                            await websocket.send(json.dumps({"type": "auth_response", "authenticated": False, "message": "Authentication required"}))
                            continue

                def _respond(resp_dict):
                    if self.loop and self.loop.is_running():
                        try:
                            payload = json.dumps(resp_dict)
                            asyncio.run_coroutine_threadsafe(websocket.send(payload), self.loop)
                        except Exception:
                            pass

                if self.command_handler:
                    self.command_handler(data, addr=("WebSocket", 0), responder=_respond)
        except Exception:
            pass
        finally:
            self.clients.discard(websocket)
            if self.on_client_callback:
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
    def __init__(self, port=53530, discovery_port=53535, ws_port=53531, auth_token="123456"):
        ensure_interactive_desktop()
        self.port = port
        self.discovery_port = discovery_port
        self.ws_port = ws_port
        self.auth_token = auth_token
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
        self.packets_received = 0
        self.last_log_time = 0
        self.connected_client = None
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
        cmd_type = data.get("type")

        if self.on_packet_callback:
            try:
                self.on_packet_callback(cmd_type, addr, data)
            except Exception:
                pass

        if cmd_type == "mouse":
            dx = int(round(data.get("dx", 0)))
            dy = int(round(data.get("dy", 0)))
            btn = int(data.get("btn", 0))
            v_scroll = int(round(data.get("v_scroll", 0)))
            h_scroll = int(round(data.get("h_scroll", 0)))

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

        elif cmd_type == "pen":
            # Tablet Drawing / Writing Pad mode (1:1 Touchscreen & Stylus replacement)
            action = data.get("action")  # "down", "move", "up"
            x_ratio = float(data.get("x", 0.0))
            y_ratio = float(data.get("y", 0.0))
            is_eraser = bool(data.get("eraser", False))

            if IS_WINDOWS:
                norm_x = int(max(0.0, min(1.0, x_ratio)) * 65535)
                norm_y = int(max(0.0, min(1.0, y_ratio)) * 65535)

                btn_down = MOUSEEVENTF_RIGHTDOWN if is_eraser else MOUSEEVENTF_LEFTDOWN
                btn_up = MOUSEEVENTF_RIGHTUP if is_eraser else MOUSEEVENTF_LEFTUP

                if action == "down":
                    u32.mouse_event(MOUSEEVENTF_MOVE | MOUSEEVENTF_ABSOLUTE, norm_x, norm_y, 0, 0)
                    u32.mouse_event(btn_down, 0, 0, 0, 0)
                elif action == "move":
                    u32.mouse_event(MOUSEEVENTF_MOVE | MOUSEEVENTF_ABSOLUTE, norm_x, norm_y, 0, 0)
                elif action == "up":
                    u32.mouse_event(btn_up, 0, 0, 0, 0)
            elif self.mouse:
                if action == "down":
                    self.mouse.press(Button.right if is_eraser else Button.left)
                elif action == "up":
                    self.mouse.release(Button.right if is_eraser else Button.left)

        elif cmd_type == "media_query":
            # Mobile app querying live media status, track title, progression, and volume
            state = self.media_controller.get_media_state()
            response = {"type": "media_status"}
            response.update(state)
            if responder:
                responder(response)
            elif addr and self.sock:
                try:
                    payload = json.dumps(response).encode("utf-8")
                    self.sock.sendto(payload, addr)
                except Exception:
                    pass

        elif cmd_type == "media_seek":
            pos = float(data.get("position", 0.0))
            self.media_controller.seek(pos)

        elif cmd_type == "media_toggle":
            self.media_controller.toggle_play_pause()

        elif cmd_type == "media_next":
            self.media_controller.next_track()

        elif cmd_type == "media_prev":
            self.media_controller.prev_track()

        elif cmd_type == "set_volume":
            vol = float(data.get("volume", 0.5))
            self.media_controller.set_volume(vol)

        elif cmd_type == "set_mute":
            muted = bool(data.get("mute", False))
            self.media_controller.set_mute(muted)

        elif cmd_type == "launch_app":
            app_id = str(data.get("app", "")).strip()
            self._launch_application(app_id)

        elif cmd_type == "system_power":
            action = str(data.get("action", "")).lower().strip()
            self._handle_system_power(action)

        elif cmd_type == "unlock":
            password = str(data.get("password", ""))
            self._unlock_workstation(password)

        elif cmd_type in ("query_apps", "list_apps"):
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

        elif cmd_type in ("query_stats", "system_stats"):
            stats = self._get_system_stats()
            resp = {"type": "system_stats_response"}
            resp.update(stats)
            if responder:
                responder(resp)
            elif addr and self.sock:
                self._send_udp_json(resp, addr)

        elif cmd_type == "camera_snapshot":
            b64 = self.camera_manager.get_snapshot()
            resp = {"type": "camera_frame_response", "frame": b64, "is_snapshot": True, "success": b64 is not None}
            if responder:
                responder(resp)
            elif addr and self.sock:
                self._send_udp_json(resp, addr)

        elif cmd_type == "camera_stream_start":
            def _on_frame(b64):
                frame_data = {"type": "camera_frame_response", "frame": b64, "is_streaming": True}
                if responder:
                    responder(frame_data)
                if hasattr(self, "ws_server") and self.ws_server:
                    self.ws_server.broadcast(frame_data)
            self.camera_manager.start_stream(_on_frame)
            resp = {"type": "camera_stream_status", "streaming": True}
            if responder:
                responder(resp)

        elif cmd_type == "camera_stream_stop":
            self.camera_manager.stop_stream()
            resp = {"type": "camera_stream_status", "streaming": False}
            if responder:
                responder(resp)

        elif cmd_type == "audio_record_start":
            rec_file = self.audio_manager.start_recording()
            resp = {"type": "audio_status_response", "recording": True, "file": os.path.basename(rec_file) if rec_file else ""}
            if responder:
                responder(resp)

        elif cmd_type == "audio_record_stop":
            rec_file = self.audio_manager.stop_recording()
            resp = {"type": "audio_status_response", "recording": False, "file": os.path.basename(rec_file) if rec_file else ""}
            if responder:
                responder(resp)

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

        elif cmd_type == "connect":
            client = data.get("client", "Phone")
            self.connected_client = f"{client} ({addr[0] if addr else ''})"
            print(f"[+] Client connected: {self.connected_client}")
            if self.on_client_callback:
                self.on_client_callback(self.connected_client, True)

        elif cmd_type == "disconnect":
            print(f"[-] Client disconnected: {self.connected_client}")
            if self.on_client_callback:
                self.on_client_callback(self.connected_client, False)
            self.connected_client = None

    def _send_udp_json(self, resp_dict, addr):
        try:
            payload = json.dumps(resp_dict).encode("utf-8")
            if len(payload) <= 65000:
                self.sock.sendto(payload, addr)
        except Exception:
            pass

    def _get_installed_apps(self):
        apps = []
        seen = set()
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
                            apps.append({"name": name, "path": os.path.join(root, f)})
        apps.sort(key=lambda x: x["name"].lower())
        return apps[:150]

    def _get_running_processes(self):
        procs = []
        try:
            import psutil
            for p in psutil.process_iter(['pid', 'name', 'cpu_percent', 'memory_percent']):
                try:
                    info = p.info
                    mem = info.get('memory_percent') or 0.0
                    if mem >= 0.3:
                        procs.append({
                            'pid': info['pid'],
                            'name': info['name'],
                            'cpu': round(info.get('cpu_percent') or 0, 1),
                            'ram': round(mem, 1)
                        })
                except Exception:
                    pass
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
            battery = psutil.sensors_battery()
            if battery:
                stats["battery"] = round(battery.percent, 1)
                stats["power_plugged"] = battery.power_plugged
            disk = psutil.disk_usage(os.path.abspath(os.sep))
            stats["disk"] = round(disk.percent, 1)
        except Exception as e:
            print(f"[-] Error querying system stats: {e}")
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
        import subprocess
        apps = {
            "onenote": "start onenote:",
            "spotify": "start spotify:",
            "chrome": "start chrome",
            "terminal": "start wt || start powershell",
            "taskmgr": "start taskmgr",
            "calc": "start calc:",
            "explorer": "explorer",
            "settings": "start ms-settings:",
            "notepad": "start notepad",
            "vscode": "start code",
            "edge": "start msedge",
        }
        if os.path.exists(app_id):
            try:
                os.startfile(app_id)
                print(f"[+] Launched file/shortcut: {app_id}")
                return
            except Exception as e:
                print(f"[-] os.startfile failed for {app_id}: {e}")
        clean_key = app_id.lower().strip()
        cmd = apps.get(clean_key, f"start {app_id}" if app_id else "")
        if cmd:
            try:
                subprocess.Popen(cmd, shell=True)
                print(f"[+] Launched application: {app_id} ({cmd})")
            except Exception as e:
                print(f"[-] Failed to launch application {app_id}: {e}")

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

    def _unlock_workstation(self, password):
        if not password:
            return
        def _do_unlock():
            try:
                if self.keyboard:
                    from pynput.keyboard import Key
                    # Wake up display and dismiss lock screen
                    self.keyboard.tap(Key.enter)
                    time.sleep(0.3)
                    self.keyboard.tap(Key.esc)
                    time.sleep(0.3)
                    self.keyboard.tap(Key.space)
                    time.sleep(0.7)
                    # Clear any stale text in PIN input
                    for _ in range(12):
                        self.keyboard.tap(Key.backspace)
                    time.sleep(0.1)
                    # Type password/PIN
                    self.keyboard.type(password)
                    time.sleep(0.2)
                    self.keyboard.tap(Key.enter)
                    print("[+] Unlock keystrokes dispatched")
            except Exception as e:
                print(f"[-] Unlock error: {e}")
        threading.Thread(target=_do_unlock, daemon=True).start()

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
        if mod & 1:
            self.keyboard.press(Key.ctrl)
        if mod & 2:
            self.keyboard.press(Key.shift)
        if mod & 4:
            self.keyboard.press(Key.alt)
        if mod & 8:
            self.keyboard.press(Key.cmd)
        key = self._resolve_key(code)
        if key:
            self.keyboard.press(key)

    def _execute_key_up(self, code, mod):
        if not self.keyboard:
            return
        key = self._resolve_key(code)
        if key:
            self.keyboard.release(key)
        if mod & 8:
            self.keyboard.release(Key.cmd)
        if mod & 4:
            self.keyboard.release(Key.alt)
        if mod & 2:
            self.keyboard.release(Key.shift)
        if mod & 1:
            self.keyboard.release(Key.ctrl)

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
