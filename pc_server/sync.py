"""Clipboard sync, file transfer, and relaying between devices.

Files move in 32 KB chunks so a chunk always fits a UDP datagram. Each chunk is acked,
unacked chunks are resent, and a transfer resumes from the chunks already received if
the link drops. The same protocol runs on the phone, so either side can send.

Messages:
  clipboard_push {text, id}
  file_offer     {transfer_id, name, size, sha256, chunk_size, chunks}
  file_accept    {transfer_id, have: [indexes already received]}
  file_chunk     {transfer_id, index, data}
  file_chunk_ack {transfer_id, index}
  file_done      {transfer_id}
  file_result    {transfer_id, ok, error?}
  file_cancel    {transfer_id}
Any of them may carry ``to`` (a device id); the PC forwards those to that device.
"""

import base64
import hashlib
import json
import os
import re
import threading
import time
import uuid
from pathlib import Path

CHUNK_SIZE = 32 * 1024
WINDOW = 8
RESEND_AFTER = 1.5
GIVE_UP_AFTER = 30.0
MAX_CLIP_CHARS = 30000
PC_ID = "pc"


def downloads_dir():
    home = Path(os.environ.get("USERPROFILE") or os.path.expanduser("~"))
    target = home / "Downloads" / "Lanyard"
    try:
        target.mkdir(parents=True, exist_ok=True)
    except OSError:
        pass
    return target


def safe_name(name):
    name = os.path.basename(str(name or "file").replace("\\", "/"))
    name = re.sub(r'[<>:"/\\|?*\x00-\x1f]', "_", name).strip(" .")
    return name[:180] or "file"


def unique_path(folder, name):
    path = Path(folder) / name
    stem, suffix = path.stem, path.suffix
    n = 1
    while path.exists():
        path = Path(folder) / f"{stem} ({n}){suffix}"
        n += 1
    return path


def file_sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1024 * 1024), b""):
            h.update(block)
    return h.hexdigest()


class Transfer:
    def __init__(self, transfer_id, name, size, sha256, peer, direction, path=None):
        self.id = transfer_id
        self.name = name
        self.size = int(size)
        self.sha256 = sha256
        self.peer = peer
        self.direction = direction
        self.path = path
        self.chunks = max(1, (self.size + CHUNK_SIZE - 1) // CHUNK_SIZE)
        self.done = set()
        self.state = "waiting"
        self.error = None
        self.started = time.monotonic()
        self.last_progress = time.monotonic()
        self.cancelled = threading.Event()
        self.accepted = threading.Event()
        self.cond = threading.Condition()

    @property
    def progress(self):
        return len(self.done) / self.chunks

    def info(self):
        elapsed = max(0.001, time.monotonic() - self.started)
        sent = min(self.size, len(self.done) * CHUNK_SIZE)
        return {"id": self.id, "name": self.name, "size": self.size, "peer": self.peer,
                "direction": self.direction, "state": self.state, "progress": self.progress,
                "speed": sent / elapsed, "error": self.error, "path": str(self.path) if self.path else None}


class SyncHub:
    """Clipboard, files, and relay for the PC. ``registry`` is ``ecosystem.DeviceRegistry``."""

    def __init__(self, registry, receive_dir=None):
        self.registry = registry
        self.receive_dir = Path(receive_dir) if receive_dir else None
        self.clipboard_enabled = True
        self.on_clipboard = None        # (text) -> None: put text on the PC clipboard
        self.on_clipboard_image = None  # (path) -> None: put a picture on the PC clipboard
        self.on_transfer = None         # (info dict) -> None: progress for the UI
        self.on_file_received = None    # (path, from_name) -> None
        self._last_clip_id = None
        self.library_dir = None         # (name) -> folder for library files; set by LibrarySync
        self.extra_handlers = []        # (cmd_type, data, respond) -> bool, for streams and library
        self._transfers = {}
        self._lock = threading.Lock()

    # --- routing -------------------------------------------------------
    def handle(self, cmd_type, data, respond):
        """Returns True when the packet was a sync/relay message."""
        to = data.get("to")
        if to and to != PC_ID:
            return self._relay(to, data)
        handler = {
            "clipboard_push": self._on_clipboard_push,
            "devices_query": self._on_devices_query,
            "file_offer": self._on_offer,
            "file_accept": self._on_accept,
            "file_chunk": self._on_chunk,
            "file_chunk_ack": self._on_chunk_ack,
            "file_done": self._on_done,
            "file_result": self._on_result,
            "file_cancel": self._on_cancel,
        }.get(cmd_type)
        if handler is None:
            return any(h(cmd_type, data, respond) for h in self.extra_handlers)
        handler(data, respond)
        return True

    def _sender_id(self, data):
        session = str(data.get("client_session") or "")
        return self.registry._session_to_device.get(session) or session or "unknown"

    def _relay(self, to, data):
        payload = {k: v for k, v in data.items() if k not in ("token", "auth_token", "client_session", "seq", "to")}
        payload["from"] = self._sender_id(data)
        sender = self.registry.get(payload["from"])
        payload["from_name"] = sender.get("name") if sender else "Device"
        if not self.registry.send(to, payload):
            self.registry.send(payload["from"], {"type": "error", "message": "That device is not connected"})
        return True

    def device_list(self):
        devices = [{"device_id": PC_ID, "name": self.pc_name(), "kind": "pc",
                    "caps": {"clipboard": True, "files": True}}]
        for dev in self.registry.online_devices():
            devices.append({"device_id": dev["device_id"], "name": dev.get("name"), "kind": dev.get("kind"),
                            "caps": dev.get("caps", {})})
        return devices

    def pc_name(self):
        import socket
        return socket.gethostname()

    def announce_devices(self):
        self.registry.broadcast({"type": "devices_update", "devices": self.device_list()})

    def _on_devices_query(self, data, respond):
        respond({"type": "devices_update", "devices": self.device_list()})

    # --- clipboard -----------------------------------------------------
    def publish_clipboard(self, text):
        """The PC clipboard changed: send it to every device."""
        if not self.clipboard_enabled or not isinstance(text, str) or not text:
            return 0
        text = text[:MAX_CLIP_CHARS]
        clip_id = hashlib.sha1(text.encode("utf-8", "replace")).hexdigest()[:16]
        if clip_id == self._last_clip_id:
            return 0
        self._last_clip_id = clip_id
        return self.registry.broadcast({"type": "clipboard_push", "text": text, "id": clip_id,
                                        "from": PC_ID, "from_name": self.pc_name()})

    def _on_clipboard_push(self, data, respond):
        if not self.clipboard_enabled:
            return
        text = data.get("text")
        if not isinstance(text, str) or not text:
            return
        text = text[:MAX_CLIP_CHARS]
        clip_id = hashlib.sha1(text.encode("utf-8", "replace")).hexdigest()[:16]
        if clip_id == self._last_clip_id:
            return
        self._last_clip_id = clip_id
        sender = self._sender_id(data)
        dev = self.registry.get(sender)
        self.registry.broadcast({"type": "clipboard_push", "text": text, "id": clip_id, "from": sender,
                                 "from_name": dev.get("name") if dev else "Device"}, exclude=sender)
        cb = self.on_clipboard
        if cb:
            try:
                cb(text)
            except Exception:
                pass

    def publish_clipboard_image(self, png_path):
        """A copied picture goes to every device as a small file it puts on its clipboard."""
        if not self.clipboard_enabled:
            return 0
        sent = 0
        for dev in self.registry.online_devices():
            if dev.get("caps", {}).get("clipboard_image"):
                self.send_file(dev["device_id"], png_path, extra={"clipboard": True})
                sent += 1
        return sent

    # --- sending files -------------------------------------------------
    def send_file(self, device_id, path, extra=None):
        """Start sending ``path`` to ``device_id`` in the background. Returns the transfer id.

        ``extra`` adds fields to the offer, e.g. ``{"clipboard": True}`` for a copied image.
        """
        path = Path(path)
        size = path.stat().st_size
        t = Transfer(uuid.uuid4().hex[:16], path.name, size, None, device_id, "out", path)
        t.extra = dict(extra or {})
        with self._lock:
            self._transfers[t.id] = t
        threading.Thread(target=self._send_worker, args=(t,), name=f"FileSend-{t.id}", daemon=True).start()
        return t.id

    def _send_worker(self, t):
        try:
            t.state = "preparing"
            self._notify(t)
            t.sha256 = file_sha256(t.path)
            offer = {"type": "file_offer", "transfer_id": t.id, "name": t.name, "size": t.size, "sha256": t.sha256,
                     "chunk_size": CHUNK_SIZE, "chunks": t.chunks, "from": PC_ID, "from_name": self.pc_name()}
            offer.update(getattr(t, "extra", {}))
            deadline = time.monotonic() + GIVE_UP_AFTER
            while not t.accepted.wait(2.0):
                if t.cancelled.is_set() or time.monotonic() > deadline:
                    raise TimeoutError("The device did not answer")
                self.registry.send(t.peer, offer)
            t.state = "sending"
            self._notify(t)
            sent_at = {}
            with open(t.path, "rb") as f:
                while len(t.done) < t.chunks:
                    if t.cancelled.is_set():
                        raise InterruptedError("Cancelled")
                    now = time.monotonic()
                    if now - t.last_progress > GIVE_UP_AFTER:
                        raise TimeoutError("The connection was lost")
                    in_flight = [i for i, ts in sent_at.items() if i not in t.done and now - ts < RESEND_AFTER]
                    budget = WINDOW - len(in_flight)
                    for i in range(t.chunks):
                        if budget <= 0:
                            break
                        if i in t.done or (i in sent_at and now - sent_at[i] < RESEND_AFTER):
                            continue
                        f.seek(i * CHUNK_SIZE)
                        chunk = f.read(CHUNK_SIZE)
                        self.registry.send(t.peer, {"type": "file_chunk", "transfer_id": t.id, "index": i,
                                                    "data": base64.b64encode(chunk).decode("ascii")})
                        sent_at[i] = now
                        budget -= 1
                    with t.cond:
                        t.cond.wait(0.05)
                    self._notify(t, throttle=True)
            t.state = "verifying"
            self._notify(t)
            deadline = time.monotonic() + 15
            while t.state == "verifying" and time.monotonic() < deadline and not t.cancelled.is_set():
                self.registry.send(t.peer, {"type": "file_done", "transfer_id": t.id})
                with t.cond:
                    t.cond.wait(2.0)
            if t.state == "verifying":
                raise TimeoutError("The device did not confirm the file")
        except Exception as exc:
            if t.state not in ("done", "failed"):
                t.state = "failed"
                t.error = str(exc) or exc.__class__.__name__
                self.registry.send(t.peer, {"type": "file_cancel", "transfer_id": t.id})
        self._notify(t)

    def _on_accept(self, data, respond):
        t = self._transfers.get(str(data.get("transfer_id")))
        if not t or t.direction != "out":
            return
        for i in data.get("have") or []:
            if isinstance(i, int) and 0 <= i < t.chunks:
                t.done.add(i)
        t.last_progress = time.monotonic()
        t.accepted.set()

    def _on_chunk_ack(self, data, respond):
        t = self._transfers.get(str(data.get("transfer_id")))
        if not t or t.direction != "out":
            return
        index = data.get("index")
        if isinstance(index, int):
            t.done.add(index)
            t.last_progress = time.monotonic()
            with t.cond:
                t.cond.notify_all()

    def _on_result(self, data, respond):
        t = self._transfers.get(str(data.get("transfer_id")))
        if not t or t.direction != "out":
            return
        if data.get("ok"):
            t.state = "done"
        else:
            t.state = "failed"
            t.error = str(data.get("error") or "The device rejected the file")
        with t.cond:
            t.cond.notify_all()

    def cancel(self, transfer_id):
        t = self._transfers.get(transfer_id)
        if t:
            t.cancelled.set()
            if t.direction == "in":
                self._finish_in(t, False, "Cancelled")

    # --- receiving files -----------------------------------------------
    def _part_paths(self, sha256, size):
        folder = (self.receive_dir or downloads_dir()) / ".partial"
        folder.mkdir(parents=True, exist_ok=True)
        key = f"{sha256[:24]}_{size}"
        return folder / f"{key}.part", folder / f"{key}.json"

    def _on_offer(self, data, respond):
        transfer_id = str(data.get("transfer_id") or "")[:40]
        sha256 = str(data.get("sha256") or "")
        if not transfer_id or not re.fullmatch(r"[0-9a-f]{64}", sha256):
            return
        if int(data.get("chunk_size") or CHUNK_SIZE) != CHUNK_SIZE:
            respond({"type": "file_result", "transfer_id": transfer_id, "ok": False, "error": "Unsupported chunk size"})
            return
        t = self._transfers.get(transfer_id)
        if t is None:
            sender = self._sender_id(data)
            t = Transfer(transfer_id, safe_name(data.get("name")), int(data.get("size") or 0), sha256, sender, "in")
            t.from_name = str(data.get("from_name") or (self.registry.get(sender) or {}).get("name") or "Device")
            t.library = bool(data.get("library")) and self.library_dir is not None
            t.clipboard = bool(data.get("clipboard"))
            t.part, t.meta = self._part_paths(sha256, t.size)
            try:
                t.done = set(json.loads(t.meta.read_text()).get("have", []))
            except (OSError, ValueError):
                t.done = set()
            if not t.part.exists():
                t.done = set()
                with open(t.part, "wb") as f:
                    f.truncate(t.size)
            t.state = "receiving"
            with self._lock:
                self._transfers[transfer_id] = t
            self._notify(t)
        t.respond = respond
        respond({"type": "file_accept", "transfer_id": transfer_id, "have": sorted(t.done)})

    def _on_chunk(self, data, respond):
        t = self._transfers.get(str(data.get("transfer_id")))
        index = data.get("index")
        if not t or t.direction != "in" or t.state != "receiving" or not isinstance(index, int):
            return
        if 0 <= index < t.chunks and index not in t.done:
            try:
                chunk = base64.b64decode(data.get("data") or "")
                with open(t.part, "r+b") as f:
                    f.seek(index * CHUNK_SIZE)
                    f.write(chunk)
                t.done.add(index)
                t.last_progress = time.monotonic()
                if len(t.done) % 32 == 0:
                    t.meta.write_text(json.dumps({"have": sorted(t.done)}))
            except Exception:
                return
        respond({"type": "file_chunk_ack", "transfer_id": t.id, "index": index})
        self._notify(t, throttle=True)

    def _on_done(self, data, respond):
        t = self._transfers.get(str(data.get("transfer_id")))
        if not t or t.direction != "in":
            return
        if t.state == "done":
            respond({"type": "file_result", "transfer_id": t.id, "ok": True})
            return
        if len(t.done) < t.chunks:
            return
        try:
            ok = file_sha256(t.part) == t.sha256
        except OSError:
            ok = False
        if ok:
            if getattr(t, "clipboard", False):
                import tempfile
                folder = Path(tempfile.gettempdir()) / "LanyardClipboard"
                folder.mkdir(parents=True, exist_ok=True)
                dest = folder / t.name
            elif getattr(t, "library", False):
                folder = Path(self.library_dir(t.name))
                folder.mkdir(parents=True, exist_ok=True)
                dest = folder / t.name
                if dest.exists():
                    dest = unique_path(folder, t.name)
            else:
                dest = unique_path(self.receive_dir or downloads_dir(), t.name)
            try:
                os.replace(t.part, dest)
                t.path = dest
            except OSError as exc:
                ok = False
                t.error = str(exc)
        respond({"type": "file_result", "transfer_id": t.id, "ok": ok, "error": None if ok else (t.error or "Checksum mismatch")})
        self._finish_in(t, ok, None if ok else (t.error or "Checksum mismatch"))

    def _finish_in(self, t, ok, error):
        t.state = "done" if ok else "failed"
        t.error = error
        for p in (getattr(t, "meta", None), None if ok else getattr(t, "part", None)):
            if p is not None:
                try:
                    p.unlink()
                except OSError:
                    pass
        self._notify(t)
        if ok and getattr(t, "clipboard", False):
            if self.on_clipboard_image and self.clipboard_enabled:
                try:
                    self.on_clipboard_image(str(t.path))
                except Exception:
                    pass
            return
        if ok and self.on_file_received and not getattr(t, "library", False):
            try:
                self.on_file_received(t.path, getattr(t, "from_name", "Device"))
            except Exception:
                pass

    def _on_cancel(self, data, respond):
        t = self._transfers.get(str(data.get("transfer_id")))
        if not t:
            return
        t.cancelled.set()
        if t.direction == "in" and t.state == "receiving":
            # Keep the partial file so a resend resumes; just stop tracking this attempt.
            try:
                t.meta.write_text(json.dumps({"have": sorted(t.done)}))
            except OSError:
                pass
            t.state = "failed"
            t.error = "Cancelled by sender"
            self._notify(t)
        elif t.direction == "out":
            t.state = "failed"
            t.error = "Cancelled by receiver"

    # --- UI ------------------------------------------------------------
    def transfers(self):
        with self._lock:
            return [t.info() for t in self._transfers.values()]

    def _notify(self, t, throttle=False):
        now = time.monotonic()
        if throttle and now - getattr(t, "_last_notify", 0) < 0.25:
            return
        t._last_notify = now
        cb = self.on_transfer
        if cb:
            try:
                cb(t.info())
            except Exception:
                pass
