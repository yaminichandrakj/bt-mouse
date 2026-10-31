"""One library across devices: this PC keeps a copy of every recording and snapshot
made on a phone or tablet, so every device can browse and play all of them through
the PC's existing Library (recordings_list / download / stream).

  library_list  {}                       PC -> device: what do you have?
  library_items {items: [{id, name, size, modified, type}]}
  library_fetch {id}                      PC -> device: send me this one
The device answers a fetch with the normal file_offer, marked ``library: true``, and
the PC files it under Videos, Audios or Snapshots.
"""

import threading
import time
from pathlib import Path

AUDIO_EXT = {".wav", ".m4a", ".mp3", ".aac", ".ogg"}
IMAGE_EXT = {".jpg", ".jpeg", ".png", ".webp"}


def media_folder(media_dirs, name):
    ext = Path(name).suffix.lower()
    if ext in AUDIO_EXT:
        return Path(media_dirs["audios"])
    if ext in IMAGE_EXT:
        return Path(media_dirs["snapshots"])
    return Path(media_dirs["videos"])


class LibrarySync:
    INTERVAL = 60.0
    MAX_AUTO_BYTES = 2 * 1024 ** 3

    def __init__(self, registry, sync, media_dirs_fn):
        self.registry = registry
        self.sync = sync
        self.media_dirs_fn = media_dirs_fn
        self.auto_copy = True
        self.remote_items = {}
        self._requested = {}
        self._lock = threading.Lock()
        sync.library_dir = lambda name: media_folder(self.media_dirs_fn(), name)
        threading.Thread(target=self._loop, name="LibrarySync", daemon=True).start()

    def handle(self, cmd_type, data, respond):
        if cmd_type != "library_items":
            return False
        sender = self.sync._sender_id(data)
        items = [i for i in (data.get("items") or []) if isinstance(i, dict) and i.get("name")]
        with self._lock:
            self.remote_items[sender] = items
        if self.auto_copy:
            self._fetch_missing(sender, items)
        return True

    def have_locally(self, name, size):
        path = media_folder(self.media_dirs_fn(), name) / Path(str(name)).name
        try:
            return path.is_file() and (not size or path.stat().st_size == int(size))
        except (OSError, ValueError):
            return False

    def _fetch_missing(self, device_id, items):
        now = time.monotonic()
        for item in items:
            name = Path(str(item["name"])).name
            size = int(item.get("size") or 0)
            if size > self.MAX_AUTO_BYTES or self.have_locally(name, size):
                continue
            key = (device_id, name)
            with self._lock:
                if now - self._requested.get(key, 0) < 600:
                    continue
                self._requested[key] = now
            self.registry.send(device_id, {"type": "library_fetch", "id": item.get("id") or name})

    def request_all(self):
        for dev in self.registry.online_devices():
            if dev.get("caps", {}).get("library"):
                self.registry.send(dev["device_id"], {"type": "library_list"})

    def _loop(self):
        time.sleep(15)
        while True:
            if self.auto_copy:
                try:
                    self.request_all()
                except Exception:
                    pass
            time.sleep(self.INTERVAL)
