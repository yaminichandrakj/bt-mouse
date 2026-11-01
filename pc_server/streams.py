"""Live streams between devices, with the PC as the hub.

A phone or tablet announces a source (its screen or camera). Any other device, or this
PC's window, can watch it. The PC tells the publisher how many viewers it has so it only
captures while someone is watching, and copies each frame to every viewer.

Messages:
  stream_announce {stream_id, kind, label, active}   publisher -> PC
  streams_update  {streams: [...]}                   PC -> every device
  stream_watch    {stream_id, on}                    viewer -> PC
  stream_demand   {stream_id, viewers}               PC -> publisher
  stream_frame    {stream_id, frame (base64 JPEG), width, height}
                                                     publisher -> PC -> viewers
"""

import threading
import time

LOCAL = "pc"


class StreamHub:
    def __init__(self, registry):
        self.registry = registry
        self._lock = threading.Lock()
        self._streams = {}
        self._local_viewers = {}
        self.on_streams_changed = None
        self.alive = True
        self._pruner = threading.Thread(target=self._prune_loop, name="StreamPrune", daemon=True)
        self._pruner.start()

    def handle(self, cmd_type, data, sender_id):
        handler = {
            "stream_announce": self._on_announce,
            "stream_watch": self._on_watch,
            "stream_frame": self._on_frame,
            "stream_audio": self._on_frame,
            "streams_query": self._on_query,
        }.get(cmd_type)
        if handler is None:
            return False
        handler(data, sender_id)
        return True

    # --- publishers ----------------------------------------------------
    def _on_announce(self, data, sender_id):
        kind = str(data.get("kind") or "screen")[:20]
        stream_id = str(data.get("stream_id") or f"{sender_id}:{kind}")[:80]
        if not stream_id.startswith(sender_id + ":"):
            return
        active = bool(data.get("active", True))
        dev = self.registry.get(sender_id) or {}
        with self._lock:
            if active:
                s = self._streams.setdefault(stream_id, {"viewers": set(), "frames": 0, "last": None})
                s.update({"id": stream_id, "kind": kind, "owner": sender_id,
                          "owner_name": dev.get("name", "Device"),
                          "label": str(data.get("label") or kind.title())[:60]})
            else:
                self._streams.pop(stream_id, None)
        self._announce()
        if active:
            self._send_demand(stream_id)

    def _on_frame(self, data, sender_id):
        stream_id = str(data.get("stream_id") or "")
        with self._lock:
            s = self._streams.get(stream_id)
            if not s or s["owner"] != sender_id:
                return
            if data.get("type") != "stream_audio":
                s["frames"] += 1
                s["last"] = time.monotonic()
            viewers = list(s["viewers"])
            locals_ = list(self._local_viewers.get(stream_id, {}).values())
        if data.get("type") == "stream_audio":
            payload = {"type": "stream_audio", "stream_id": stream_id, "pcm": data.get("pcm")}
        else:
            payload = {"type": "stream_frame", "stream_id": stream_id, "frame": data.get("frame"),
                       "width": data.get("width"), "height": data.get("height")}
        for v in viewers:
            if v != sender_id:
                self.registry.send(v, payload)
        for cb in locals_:
            try:
                cb(payload)
            except Exception:
                pass

    # --- viewers -------------------------------------------------------
    def _on_watch(self, data, sender_id):
        stream_id = str(data.get("stream_id") or "")
        on = bool(data.get("on", True))
        with self._lock:
            s = self._streams.get(stream_id)
            if not s:
                return
            (s["viewers"].add if on else s["viewers"].discard)(sender_id)
        self._send_demand(stream_id)
        self._announce()

    def _on_query(self, data, sender_id):
        self.registry.send(sender_id, {"type": "streams_update", "streams": self.list()})

    def watch_local(self, stream_id, key, callback):
        """This PC's window starts watching; ``callback(payload)`` gets each frame."""
        with self._lock:
            self._local_viewers.setdefault(stream_id, {})[key] = callback
        self._send_demand(stream_id)

    def unwatch_local(self, stream_id, key):
        with self._lock:
            self._local_viewers.get(stream_id, {}).pop(key, None)
        self._send_demand(stream_id)

    def viewer_count(self, stream_id):
        with self._lock:
            s = self._streams.get(stream_id)
            if not s:
                return 0
            return len(s["viewers"]) + len(self._local_viewers.get(stream_id, {}))

    def _send_demand(self, stream_id):
        with self._lock:
            s = self._streams.get(stream_id)
            owner = s["owner"] if s else None
        if owner:
            self.registry.send(owner, {"type": "stream_demand", "stream_id": stream_id,
                                       "viewers": self.viewer_count(stream_id)})

    # --- listing -------------------------------------------------------
    def list(self):
        with self._lock:
            return [{"id": s["id"], "kind": s["kind"], "label": s["label"], "owner": s["owner"],
                     "owner_name": s["owner_name"],
                     "viewers": len(s["viewers"]) + len(self._local_viewers.get(s["id"], {}))}
                    for s in self._streams.values()]

    def _announce(self):
        self.registry.broadcast({"type": "streams_update", "streams": self.list()})
        cb = self.on_streams_changed
        if cb:
            try:
                cb()
            except Exception:
                pass

    def _prune_loop(self):
        while self.alive:
            time.sleep(5)
            changed = []
            with self._lock:
                for stream_id, s in list(self._streams.items()):
                    if not self.registry.is_online(s["owner"]):
                        del self._streams[stream_id]
                        changed.append(stream_id)
                        continue
                    gone = {v for v in s["viewers"] if not self.registry.is_online(v)}
                    if gone:
                        s["viewers"] -= gone
                        changed.append(stream_id)
            if changed:
                for stream_id in changed:
                    self._send_demand(stream_id)
                self._announce()
