"""
Phase 1 WAN access for the BT Mouse companion.

Runs a cloudflared *quick tunnel* (no account, no domain) in front of the local
WebSocket server, and publishes the tunnel's current public URL — signed — to the
Cloudflare rendezvous Worker. The phone resolves that record to learn the wss URL,
so a tunnel restart (which hands out a fresh trycloudflare.com address) no longer
means re-scanning a QR.

Design notes
------------
* Identity is an Ed25519 key kept in ``remote.json`` next to ``pairing.json``.
  Its public half goes in the QR; its private half signs each published record.
  The record id is also persisted, so the QR stays valid across restarts — only
  the URL *inside* the signed record changes.
* No third-party HTTP client: the record is a <2 KB JSON PUT over urllib.
* Only PyNaCl is new; everything else is stdlib.
"""

import base64
import json
import os
import re
import shutil
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
from pathlib import Path

try:
    from nacl import signing
    HAS_NACL = True
except Exception:  # pragma: no cover - import guard
    HAS_NACL = False

# Record freshness the phone also enforces; we re-publish well inside this.
RECORD_REFRESH_SECONDS = 240
# Cloudflare rejects urllib's default "Python-urllib/x.y" agent with error 1010
# before the request reaches the Worker, so always identify ourselves.
USER_AGENT = "BTMouse-Companion"
_TUNNEL_URL_RE = re.compile(r"https://[a-z0-9-]+\.trycloudflare\.com", re.IGNORECASE)


def _config_dir():
    local_app_data = os.environ.get("LOCALAPPDATA")
    if local_app_data:
        return Path(local_app_data) / "BTMouse"
    return Path.home() / ".config" / "bt-mouse"


REMOTE_CONFIG_PATH = _config_dir() / "remote.json"


def _b64(raw: bytes) -> str:
    return base64.b64encode(raw).decode("ascii")


def find_cloudflared():
    """Locate cloudflared: PATH, then the known winget/MSI install dir, then bundled."""
    found = shutil.which("cloudflared")
    if found:
        return found
    candidates = [
        Path(os.environ.get("ProgramFiles(x86)", r"C:\Program Files (x86)")) / "cloudflared" / "cloudflared.exe",
        Path(os.environ.get("ProgramFiles", r"C:\Program Files")) / "cloudflared" / "cloudflared.exe",
    ]
    # When frozen by PyInstaller, a bundled copy sits next to the executable payload.
    meipass = getattr(sys, "_MEIPASS", None)
    if meipass:
        candidates.insert(0, Path(meipass) / "cloudflared.exe")
    for c in candidates:
        try:
            if c.is_file():
                return str(c)
        except OSError:
            continue
    return None


class RemoteIdentity:
    """Stable Ed25519 signing identity + record id + saved Worker URL."""

    def __init__(self):
        self._signing_key = None
        self.record_id = ""
        self.worker_url = ""
        self.tunnel_enabled = False
        self._load_or_create()

    def _load_or_create(self):
        data = {}
        try:
            data = json.loads(REMOTE_CONFIG_PATH.read_text(encoding="utf-8"))
        except (OSError, ValueError, TypeError):
            data = {}

        seed_b64 = str(data.get("seed", "")).strip()
        self.record_id = str(data.get("record_id", "")).strip()
        self.worker_url = str(data.get("worker_url", "")).strip()
        self.tunnel_enabled = bool(data.get("tunnel_enabled", False))

        changed = False
        if HAS_NACL:
            seed = None
            if seed_b64:
                try:
                    seed = base64.b64decode(seed_b64)
                    if len(seed) != 32:
                        seed = None
                except Exception:
                    seed = None
            if seed is None:
                self._signing_key = signing.SigningKey.generate()
                seed_b64 = _b64(bytes(self._signing_key))
                changed = True
            else:
                self._signing_key = signing.SigningKey(seed)

        if not re.fullmatch(r"[a-f0-9]{32}", self.record_id or ""):
            self.record_id = os.urandom(16).hex()
            changed = True

        if changed:
            self._save(seed_b64)

    def _save(self, seed_b64):
        try:
            REMOTE_CONFIG_PATH.parent.mkdir(parents=True, exist_ok=True)
            tmp = REMOTE_CONFIG_PATH.with_suffix(".tmp")
            tmp.write_text(
                json.dumps({
                    "seed": seed_b64,
                    "record_id": self.record_id,
                    "worker_url": self.worker_url,
                    "tunnel_enabled": self.tunnel_enabled,
                }),
                encoding="utf-8",
            )
            if os.name != "nt":
                os.chmod(tmp, 0o600)
            os.replace(tmp, REMOTE_CONFIG_PATH)
        except OSError as e:
            print(f"[!] Could not persist remote identity: {e}")

    def _seed_b64(self) -> str:
        return _b64(bytes(self._signing_key)) if self._signing_key else ""

    def set_worker_url(self, url: str):
        self.worker_url = (url or "").strip().rstrip("/")
        self._save(self._seed_b64())

    def set_tunnel_enabled(self, enabled: bool):
        """Remembered so internet access comes back after a reboot or an update."""
        self.tunnel_enabled = bool(enabled)
        self._save(self._seed_b64())

    @property
    def public_b64(self) -> str:
        if not self._signing_key:
            return ""
        return _b64(bytes(self._signing_key.verify_key))

    def sign_b64(self, message: bytes) -> str:
        if not self._signing_key:
            return ""
        return _b64(self._signing_key.sign(message).signature)

    @property
    def available(self) -> bool:
        return HAS_NACL and self._signing_key is not None and bool(self.worker_url)


class TunnelPublisher:
    """
    Starts cloudflared, tracks the current public URL, and keeps the signed record
    on the Worker fresh. All callbacks fire from a background thread — the GUI wraps
    them onto the Qt thread.
    """

    def __init__(self, identity: RemoteIdentity, ws_port: int, hostname: str,
                 on_status=None, on_url=None):
        self.identity = identity
        self.ws_port = ws_port
        self.hostname = hostname
        self.on_status = on_status or (lambda msg: None)
        self.on_url = on_url or (lambda url: None)

        self._proc = None
        self._thread = None
        self._publish_thread = None
        self._running = False
        self._current_url = None
        self._lock = threading.Lock()

    @property
    def current_url(self):
        with self._lock:
            return self._current_url

    def start(self):
        if self._running:
            return
        if not self.identity.available:
            self.on_status("Set the Worker URL first (Remote access page).")
            return
        exe = find_cloudflared()
        if not exe:
            self.on_status("cloudflared not found. Install it, then try again.")
            return
        self._running = True
        self._thread = threading.Thread(target=self._run, args=(exe,), daemon=True)
        self._thread.start()

    def stop(self):
        self._running = False
        proc = self._proc
        if proc and proc.poll() is None:
            try:
                proc.terminate()
            except Exception:
                pass
        self._proc = None
        with self._lock:
            self._current_url = None
        self.on_url(None)
        self.on_status("Internet access off")

    def _run(self, exe):
        backoff = 2
        while self._running:
            self.on_status("Starting tunnel…")
            try:
                self._proc = subprocess.Popen(
                    [exe, "tunnel", "--no-autoupdate",
                     "--url", f"http://127.0.0.1:{self.ws_port}",
                     "--loglevel", "info"],
                    stdout=subprocess.PIPE,
                    stderr=subprocess.STDOUT,
                    text=True,
                    bufsize=1,
                    creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
                )
            except Exception as e:
                self.on_status(f"Tunnel failed to start: {e}")
                if not self._wait_backoff(backoff):
                    return
                backoff = min(backoff * 2, 30)
                continue

            backoff = 2
            got_url = False
            for line in self._proc.stdout:  # type: ignore[union-attr]
                if not self._running:
                    break
                m = _TUNNEL_URL_RE.search(line)
                if m and not got_url:
                    got_url = True
                    public = m.group(0).replace("https://", "wss://", 1)
                    with self._lock:
                        self._current_url = public
                    self.on_url(public)
                    self.on_status("Internet access on")
                    self._start_publish_loop()

            # stdout closed -> cloudflared exited.
            try:
                self._proc.wait(timeout=1)
            except Exception:
                pass
            with self._lock:
                self._current_url = None
            self.on_url(None)
            if not self._running:
                return
            self.on_status("Tunnel dropped — reconnecting…")
            if not self._wait_backoff(backoff):
                return
            backoff = min(backoff * 2, 30)

    def _wait_backoff(self, seconds):
        for _ in range(seconds * 2):
            if not self._running:
                return False
            time.sleep(0.5)
        return self._running

    def _start_publish_loop(self):
        if self._publish_thread and self._publish_thread.is_alive():
            return
        self._publish_thread = threading.Thread(target=self._publish_loop, daemon=True)
        self._publish_thread.start()

    def _publish_loop(self):
        while self._running:
            url = self.current_url
            if not url:
                return
            self._publish(url)
            for _ in range(RECORD_REFRESH_SECONDS * 2):
                if not self._running or self.current_url != url:
                    break
                time.sleep(0.5)

    def _publish(self, url):
        record = json.dumps(
            {"v": 1, "url": url, "ts": int(time.time()), "host": self.hostname},
            separators=(",", ":"),
        )
        body = json.dumps(
            {"data": record, "sig": self.identity.sign_b64(record.encode("utf-8")),
             "pub": self.identity.public_b64}
        ).encode("utf-8")
        endpoint = f"{self.identity.worker_url}/r/{self.identity.record_id}"
        req = urllib.request.Request(endpoint, data=body, method="PUT",
                                     headers={"content-type": "application/json",
                                              "user-agent": USER_AGENT})
        try:
            with urllib.request.urlopen(req, timeout=10) as resp:
                if resp.status == 200:
                    return
                self.on_status(f"Publish returned HTTP {resp.status}")
        except urllib.error.HTTPError as e:
            try:
                detail = e.read(200).decode("utf-8", "replace").strip()
            except Exception:
                detail = ""
            self.on_status(f"Publish rejected (HTTP {e.code}){': ' + detail if detail else ''}")
        except Exception as e:
            self.on_status(f"Publish failed: {e}")
