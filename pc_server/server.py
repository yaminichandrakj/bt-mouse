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


class Server:
    def __init__(self, port=53530, discovery_port=53535):
        ensure_interactive_desktop()
        self.port = port
        self.discovery_port = discovery_port
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

        # Callbacks for PC Companion GUI
        self.on_packet_callback = None
        self.on_client_callback = None
        self.on_status_callback = None

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

    def handle_packet(self, data, addr=None):
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
            if addr and self.sock:
                state = self.media_controller.get_media_state()
                response = {"type": "media_status"}
                response.update(state)
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
        print(f"[*] Input Backend       : {'Native Windows user32 + pynput' if IS_WINDOWS else 'pynput'}")
        print(f"[*] Status              : READY & LISTENING")
        print("=" * 60)
        print("Tips:")
        print("1. In the Android App -> Go to 'Connect' tab -> Select 'Wi-Fi Network'")
        print("2. Tap 'Auto-Discover PC Server' or enter the IP above.")
        print("3. Press Ctrl+C to stop.\n")

        if self.on_status_callback:
            self.on_status_callback(True)

        self.start_discovery_responder()

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
