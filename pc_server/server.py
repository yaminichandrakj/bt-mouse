"""
BT Mouse & Keyboard - PC Companion Server
Ultra-low latency UDP input receiver with auto-discovery beacon.
Works on Windows, macOS, and Linux.
"""

import socket
import json
import threading
import time
import sys

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

class Server:
    def __init__(self, port=53530, discovery_port=53535):
        self.port = port
        self.discovery_port = discovery_port
        self.running = True

        if HAS_PYNPUT:
            self.mouse = MouseController()
            self.keyboard = KeyboardController()
        else:
            self.mouse = None
            self.keyboard = None

        self.last_buttons = 0

    def get_local_ip(self):
        try:
            s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            s.connect(("8.8.8.8", 80))
            ip = s.getsockname()[0]
            s.close()
            return ip
        except Exception:
            return "127.0.0.1"

    def start_discovery_responder(self):
        """Listens for UDP broadcast packets from Android app and responds with server details"""
        def listen_discovery():
            sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            try:
                sock.bind(("", self.discovery_port))
            except Exception as e:
                print(f"[!] Warning: Could not bind discovery port {self.discovery_port}: {e}")
                return

            hostname = socket.gethostname()
            while self.running:
                try:
                    data, addr = sock.recvfrom(1024)
                    message = data.decode("utf-8", errors="ignore").strip()
                    if message == "BT_MOUSE_DISCOVER":
                        reply = f"BT_MOUSE_SERVER:{hostname}:{self.port}"
                        sock.sendto(reply.encode("utf-8"), addr)
                        print(f"[*] Discovery ping from {addr[0]} -> Responded with '{reply}'")
                except Exception:
                    break
            sock.close()

        thread = threading.Thread(target=listen_discovery, daemon=True)
        thread.start()

    def handle_packet(self, data):
        if not HAS_PYNPUT:
            return

        cmd_type = data.get("type")

        if cmd_type == "mouse":
            dx = data.get("dx", 0)
            dy = data.get("dy", 0)
            btn = data.get("btn", 0)
            v_scroll = data.get("v_scroll", 0)
            h_scroll = data.get("h_scroll", 0)

            # Movement
            if dx != 0 or dy != 0:
                self.mouse.move(dx, dy)

            # Scrolling
            if v_scroll != 0 or h_scroll != 0:
                self.mouse.scroll(h_scroll, v_scroll)

            # Button changes
            if btn != self.last_buttons:
                # Left button
                if (btn & 1) and not (self.last_buttons & 1):
                    self.mouse.press(Button.left)
                elif not (btn & 1) and (self.last_buttons & 1):
                    self.mouse.release(Button.left)

                # Right button
                if (btn & 2) and not (self.last_buttons & 2):
                    self.mouse.press(Button.right)
                elif not (btn & 2) and (self.last_buttons & 2):
                    self.mouse.release(Button.right)

                # Middle button
                if (btn & 4) and not (self.last_buttons & 4):
                    self.mouse.press(Button.middle)
                elif not (btn & 4) and (self.last_buttons & 4):
                    self.mouse.release(Button.middle)

                self.last_buttons = btn

        elif cmd_type == "text":
            text = data.get("text", "")
            if text:
                self.keyboard.type(text)

        elif cmd_type == "key_stroke":
            code = data.get("code")
            mod = data.get("mod", 0)
            self._execute_keystroke(code, mod)

        elif cmd_type == "media":
            code = data.get("code")
            self._execute_media(code)

        elif cmd_type == "connect":
            client = data.get("client", "Phone")
            print(f"[+] Client connected: {client}")

        elif cmd_type == "disconnect":
            print(f"[-] Client disconnected")

    def _execute_keystroke(self, code, mod):
        # Apply modifiers
        mods_to_release = []
        if mod & 1:  # Ctrl
            self.keyboard.press(Key.ctrl)
            mods_to_release.append(Key.ctrl)
        if mod & 2:  # Shift
            self.keyboard.press(Key.shift)
            mods_to_release.append(Key.shift)
        if mod & 4:  # Alt
            self.keyboard.press(Key.alt)
            mods_to_release.append(Key.alt)
        if mod & 8:  # Win/Cmd
            self.keyboard.press(Key.cmd)
            mods_to_release.append(Key.cmd)

        key = KEY_MAP.get(code)
        if key:
            self.keyboard.press(key)
            self.keyboard.release(key)
        else:
            # Check ASCII letters / digits
            if 0x04 <= code <= 0x1D:  # A-Z
                char = chr(ord('a') + (code - 0x04))
                self.keyboard.press(char)
                self.keyboard.release(char)
            elif 0x1E <= code <= 0x27:  # 1-9, 0
                char = '0' if code == 0x27 else str(code - 0x1E + 1)
                self.keyboard.press(char)
                self.keyboard.release(char)

        for m in reversed(mods_to_release):
            self.keyboard.release(m)

    def _execute_media(self, code):
        # Media key mapping
        if code == 0xCD:  # Play/Pause
            self.keyboard.press(Key.media_play_pause)
            self.keyboard.release(Key.media_play_pause)
        elif code == 0xB5:  # Next
            self.keyboard.press(Key.media_next)
            self.keyboard.release(Key.media_next)
        elif code == 0xB6:  # Prev
            self.keyboard.press(Key.media_previous)
            self.keyboard.release(Key.media_previous)
        elif code == 0xE9:  # Volume Up
            self.keyboard.press(Key.media_volume_up)
            self.keyboard.release(Key.media_volume_up)
        elif code == 0xEA:  # Volume Down
            self.keyboard.press(Key.media_volume_down)
            self.keyboard.release(Key.media_volume_down)
        elif code == 0xE2:  # Mute
            self.keyboard.press(Key.media_volume_mute)
            self.keyboard.release(Key.media_volume_mute)

    def start(self):
        local_ip = self.get_local_ip()
        print("=" * 60)
        print("        BT MOUSE & KEYBOARD - PC COMPANION SERVER")
        print("=" * 60)
        print(f"[*] Local PC IP Address : {local_ip}")
        print(f"[*] Input UDP Port      : {self.port}")
        print(f"[*] Auto-Discovery Port : {self.discovery_port}")
        print(f"[*] Status              : READY & LISTENING")
        print("=" * 60)
        print("Tips:")
        print("1. In the Android App -> Go to 'Connect' tab -> Select 'Wi-Fi Network'")
        print("2. Tap 'Auto-Discover PC Server' or enter the IP above.")
        print("3. Press Ctrl+C in this terminal window to stop.\n")

        self.start_discovery_responder()

        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        sock.bind(("0.0.0.0", self.port))

        while self.running:
            try:
                data, addr = sock.recvfrom(2048)
                msg_str = data.decode("utf-8")
                parsed = json.loads(msg_str)
                self.handle_packet(parsed)
            except KeyboardInterrupt:
                print("\n[!] Stopping server...")
                self.running = False
                break
            except Exception as e:
                pass

        sock.close()

if __name__ == "__main__":
    server = Server()
    server.start()
