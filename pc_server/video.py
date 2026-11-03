"""Smooth mirroring on the PC: H.264 video from a phone, decoded with PyAV (FFmpeg).

The phone's hardware encoder sends each encoded frame in ~24 KB parts
(``stream_h264`` {seq, part, parts, key, config, w, h, dw, dh, data}), and sends the
codec config (SPS/PPS) right before every key frame. ``H264Assembler`` puts the parts
back together, drops frames until it has a key frame to start from, and asks for one
(``on_need_key``) when a frame goes missing. ``H264Decoder`` turns whole frames into
RGB pictures on its own thread so the network loop never waits on decoding.

When PyAV isn't installed the window keeps using the phone's JPEG pictures.
"""

import base64
import queue
import threading
import time

try:
    import av  # PyAV: FFmpeg's decoders
    HAS_AV = True
except Exception:  # pragma: no cover - depends on the machine
    av = None
    HAS_AV = False


def available():
    return HAS_AV


class H264Assembler:
    """Rebuilds whole encoded frames from parts and keeps them decodable."""

    KEY_REQUEST_GAP = 1.0
    MAX_PENDING = 32

    def __init__(self, on_frame, on_need_key):
        self.on_frame = on_frame          # (bytes annex_b, w, h, dev_w, dev_h)
        self.on_need_key = on_need_key    # ()
        self._pending = {}
        self._last = 0
        self._need_key = True
        self._config = None
        self._last_request = 0.0

    def reset(self):
        self._pending.clear()
        self._last = 0
        self._need_key = True
        self._config = None

    def accept(self, part):
        try:
            seq = int(part["seq"])
            index = int(part["part"])
            total = int(part["parts"])
            data = base64.b64decode(part.get("data") or "")
        except (KeyError, TypeError, ValueError):
            return
        if total <= 0 or not (0 <= index < total) or total > 400:
            return
        if seq <= self._last and self._last - seq < 1000:
            return  # late or repeated
        if seq < self._last:
            # The phone restarted its encoder (seq starts again): begin afresh.
            self.reset()
        slot = self._pending.setdefault(seq, [None] * total)
        if len(slot) != total:
            return
        slot[index] = data
        if any(p is None for p in slot):
            if len(self._pending) > self.MAX_PENDING:
                # Parts are being lost: forget the oldest and start from the next key frame.
                for old in sorted(self._pending)[:-self.MAX_PENDING]:
                    del self._pending[old]
                self._lost()
            return
        del self._pending[seq]
        frame = b"".join(slot)
        # Anything older that never completed is lost.
        stale = [s for s in self._pending if s < seq]
        for s in stale:
            del self._pending[s]
        gap = self._last and seq != self._last + 1
        self._last = seq
        is_config = bool(part.get("config"))
        is_key = bool(part.get("key"))
        if is_config:
            self._config = frame
            return
        if gap and not is_key:
            self._lost()
            return
        if self._need_key and not is_key:
            self._ask_key()
            return
        if is_key:
            self._need_key = False
            if self._config:
                frame = self._config + frame
        self.on_frame(frame, int(part.get("w") or 0), int(part.get("h") or 0),
                      int(part.get("dw") or 0), int(part.get("dh") or 0))

    def _lost(self):
        self._need_key = True
        self._ask_key()

    def _ask_key(self):
        now = time.monotonic()
        if now - self._last_request >= self.KEY_REQUEST_GAP:
            self._last_request = now
            try:
                self.on_need_key()
            except Exception:
                pass


class H264Decoder:
    """Decodes whole frames on a worker thread; ``on_picture(rgb_bytes, w, h, dev_w, dev_h)``.

    Only the newest few frames are kept in the queue: if decoding falls behind, the
    decoder skips ahead to the next key frame rather than drifting further behind."""

    def __init__(self, on_picture, on_error=None, on_need_key=None):
        if not HAS_AV:
            raise RuntimeError("PyAV is not installed")
        self.on_picture = on_picture
        self.on_error = on_error
        self.on_need_key = on_need_key
        self._queue = queue.Queue(maxsize=8)
        self._alive = True
        self._ctx = None
        self.decoded = 0
        self._thread = threading.Thread(target=self._loop, name="H264Decode", daemon=True)
        self._thread.start()

    def _new_context(self):
        ctx = av.CodecContext.create("h264", "r")
        try:
            ctx.flags |= av.codec.context.Flags.low_delay
        except Exception:
            pass
        try:
            ctx.thread_type = "AUTO"
        except Exception:
            pass
        return ctx

    def feed(self, frame, w, h, dev_w, dev_h):
        try:
            self._queue.put_nowait((frame, dev_w, dev_h))
        except queue.Full:
            # Behind: empty the queue and wait for a key frame.
            try:
                while True:
                    self._queue.get_nowait()
            except queue.Empty:
                pass
            if self.on_need_key:
                self.on_need_key()

    def close(self):
        self._alive = False
        try:
            self._queue.put_nowait(None)
        except queue.Full:
            pass

    def _loop(self):
        failures = 0
        while self._alive:
            item = self._queue.get()
            if item is None:
                break
            frame, dev_w, dev_h = item
            try:
                if self._ctx is None:
                    self._ctx = self._new_context()
                for picture in self._ctx.decode(av.Packet(frame)):
                    rgb = picture.to_ndarray(format="rgb24")
                    height, width = rgb.shape[0], rgb.shape[1]
                    self.decoded += 1
                    self.on_picture(rgb.tobytes(), width, height, dev_w or width, dev_h or height)
                failures = 0
            except Exception as exc:
                failures += 1
                self._ctx = None  # start over with a fresh decoder at the next key frame
                if self.on_need_key:
                    self.on_need_key()
                if failures >= 20 and self.on_error:
                    self.on_error(exc)
                    break
