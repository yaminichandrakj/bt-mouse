import os
import tempfile
import threading
import time
import unittest
from pathlib import Path

import ecosystem
from ecosystem import DeviceRegistry, InputShare, Layout, Rect, vk_to_char
from sync import CHUNK_SIZE, SyncHub


def hello(registry, device_id="phone1", session="s1", w=1080, h=2400, control=True, sink=None):
    sink = sink if sink is not None else []
    registry.hello({"device_id": device_id, "client_session": session, "name": device_id, "kind": "phone",
                    "screen_w": w, "screen_h": h, "caps": {"control": control, "files": True}}, sink.append)
    return sink


class LayoutTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.layout = Layout(Path(self.tmp.name) / "layout.json")
        self.layout.set_monitors([Rect(0, 0, 1920, 1080)])

    def tearDown(self):
        self.tmp.cleanup()

    def test_default_phone_goes_right_of_monitor(self):
        rect = self.layout.default_placement({"screen_w": 1080, "screen_h": 2400, "kind": "phone"})
        self.assertEqual(rect.x, 1920)
        self.assertAlmostEqual(rect.w / rect.h, 1080 / 2400, places=3)

    def test_snap_pulls_rect_flush_to_monitor_edge(self):
        snapped = self.layout.snap("d", Rect(1935, 200, 300, 600))
        self.assertEqual(snapped.x, 1920)
        snapped = self.layout.snap("d", Rect(-310, 100, 300, 600))
        self.assertEqual(snapped.right, 0)

    def test_save_and_load_round_trip(self):
        self.layout.placements["d"] = Rect(1920, 10, 300, 600)
        self.layout.speed = 2.5
        self.layout.save()
        again = Layout(self.layout.path)
        self.assertEqual(again.placements["d"].x, 1920)
        self.assertEqual(again.speed, 2.5)


class InputShareTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.registry = DeviceRegistry()
        self.layout = Layout(Path(self.tmp.name) / "layout.json")
        self.layout.set_monitors([Rect(0, 0, 1920, 1080)])
        self.sent = hello(self.registry)
        self.layout.placements["phone1"] = Rect(1920, 240, 270, 600)
        self.cursor = []
        self.share = InputShare(self.registry, self.layout, set_cursor=lambda x, y: self.cursor.append((x, y)),
                                get_park=lambda: (960, 540))

    def tearDown(self):
        self.tmp.cleanup()

    def wait_for(self, predicate):
        deadline = time.time() + 2
        while time.time() < deadline:
            if predicate():
                return True
            time.sleep(0.01)
        return False

    def test_pushing_right_edge_enters_phone(self):
        self.assertFalse(self.share.on_mouse_move(1900, 500))
        self.share.on_mouse_move(1919, 500)
        self.share.on_mouse_move(1919, 500)
        self.assertEqual(self.share.target, "phone1")
        self.assertEqual(self.cursor[-1], (960, 540))
        self.assertTrue(self.wait_for(lambda: any(p.get("type") == "control_state" and p["active"] for p in self.sent)))
        # Entered at the left edge, 260/600 of the way down.
        self.assertLess(self.share.rx, 5)
        self.assertAlmostEqual(self.share.ry, 2400 * 260 / 600, delta=5)

    def test_sliding_along_edge_does_not_cross(self):
        self.share.on_mouse_move(1919, 400)
        self.share.on_mouse_move(1919, 420)
        self.share.on_mouse_move(1919, 440)
        self.assertIsNone(self.share.target)

    def test_edge_outside_placed_range_does_not_cross(self):
        self.share.on_mouse_move(1919, 100)
        self.share.on_mouse_move(1919, 100)
        self.assertIsNone(self.share.target)

    def test_moving_left_off_phone_returns_to_monitor(self):
        self.share.switch_to("phone1", entry=(1921, 540))
        time.sleep(0.05)
        self.assertTrue(self.share.on_mouse_move(960 - 50, 540))
        self.assertIsNone(self.share.target)
        self.assertLess(self.cursor[-1][0], 1920)

    def test_moves_are_sent_while_remote(self):
        self.share.switch_to("phone1")
        time.sleep(0.05)
        self.share.on_mouse_move(970, 545)
        self.assertTrue(self.wait_for(lambda: any(p.get("ev") == "move" for p in self.sent)))

    def test_hotkeys_cycle_and_panic(self):
        self.assertFalse(self.share.on_key(0xA2, True))
        self.assertFalse(self.share.on_key(0xA4, True))
        self.assertTrue(self.share.on_key(0x27, True))
        self.assertEqual(self.share.target, "phone1")
        self.assertTrue(self.share.on_key(0x27, False))
        # Modifiers pressed on the PC are released on the PC.
        self.assertFalse(self.share.on_key(0xA4, False))
        self.share.on_key(0xA4, True)
        self.assertTrue(self.share.on_key(0x24, True))
        self.assertIsNone(self.share.target)

    def test_altgr_does_not_trigger_hotkeys(self):
        # AltGr = left Ctrl + right Alt; AltGr+2 types @ on many layouts.
        self.share.on_key(0xA2, True)
        self.share.on_key(0xA5, True)
        self.assertFalse(self.share.on_key(0x27, True))
        self.assertFalse(self.share.on_key(0x32, True))
        self.assertIsNone(self.share.target)

    def test_altgr_characters_are_typed_not_shortcuts(self):
        self.share.switch_to("phone1")
        self.share.on_key(0xA2, True)
        self.share.on_key(0xA5, True)
        self.share.on_key(0x51, True)
        keys = []
        self.assertTrue(self.wait_for(lambda: keys.extend(p for p in self.sent if p.get("ev") == "key" and p["code"] == 45) or keys))
        # Off Windows the US fallback has no AltGr characters, but the meta must not read as Ctrl+Alt.
        self.assertFalse(keys[0]["meta"] & (ecosystem.META_CTRL | ecosystem.META_ALT))

    def test_typing_goes_to_phone_with_char(self):
        self.share.switch_to("phone1")
        self.assertTrue(self.share.on_key(0x41, True))
        self.assertTrue(self.share.on_key(0x41, False))
        self.assertTrue(self.wait_for(lambda: any(p.get("ev") == "key" and p.get("char") == "a" for p in self.sent)))

    def test_link_loss_returns_control(self):
        self.share.switch_to("phone1")
        self.registry._devices["phone1"]["last_seen"] = ecosystem.NEVER
        self.assertTrue(self.wait_for(lambda: self.share.target is None))

    def test_vk_to_char(self):
        self.assertEqual(vk_to_char(0x41), "a")
        self.assertEqual(vk_to_char(0x41, shift=True), "A")
        self.assertEqual(vk_to_char(0x31, shift=True), "!")
        self.assertEqual(vk_to_char(0xBF), "/")


class LoopbackRegistry:
    """Connects two SyncHubs as if one were the phone."""

    def __init__(self):
        self.peer = None
        self._session_to_device = {"s-peer": "peer"}

    def send(self, device_id, payload):
        payload = dict(payload, client_session="s-peer")
        threading.Thread(target=self.peer_handle, args=(payload,), daemon=True).start()
        return True

    def peer_handle(self, payload):
        self.peer.handle(payload["type"], payload, lambda reply: self.peer_reply(reply))

    def peer_reply(self, reply):
        reply = dict(reply, client_session="s-peer")
        threading.Thread(target=self.hub.handle, args=(reply["type"], reply, lambda r: None), daemon=True).start()

    def get(self, device_id):
        return {"name": "Peer"}

    def broadcast(self, payload, exclude=None):
        return 0

    def online_devices(self):
        return []


class SyncTests(unittest.TestCase):
    def test_file_round_trip_with_resume_state_cleanup(self):
        with tempfile.TemporaryDirectory() as src_dir, tempfile.TemporaryDirectory() as dst_dir:
            src = Path(src_dir) / "photo.bin"
            src.write_bytes(os.urandom(CHUNK_SIZE * 5 + 123))
            reg = LoopbackRegistry()
            sender = SyncHub(reg)
            receiver_reg = LoopbackRegistry()
            receiver = SyncHub(receiver_reg, receive_dir=dst_dir)
            reg.hub, reg.peer = sender, receiver
            received = []
            receiver.on_file_received = lambda path, name: received.append(path)
            tid = sender.send_file("peer", src)
            deadline = time.time() + 20
            while time.time() < deadline and not received:
                time.sleep(0.05)
            self.assertTrue(received, sender.transfers())
            self.assertEqual(Path(received[0]).read_bytes(), src.read_bytes())
            deadline = time.time() + 5
            while time.time() < deadline and sender._transfers[tid].state != "done":
                time.sleep(0.05)
            self.assertEqual(sender._transfers[tid].state, "done")
            self.assertFalse(any((Path(dst_dir) / ".partial").glob("*.part")))

    def test_clipboard_loop_protection_and_rebroadcast(self):
        registry = DeviceRegistry()
        a, b = [], []
        hello(registry, "a", "sa", sink=a)
        hello(registry, "b", "sb", sink=b)
        hub = SyncHub(registry)
        got = []
        hub.on_clipboard = got.append
        self.assertTrue(hub.handle("clipboard_push", {"text": "hi", "client_session": "sa"}, a.append))
        self.assertEqual(got, ["hi"])
        self.assertTrue(any(p.get("type") == "clipboard_push" and p["text"] == "hi" for p in b))
        self.assertFalse(any(p.get("type") == "clipboard_push" for p in a))
        self.assertEqual(hub.publish_clipboard("hi"), 0)

    def test_relay_forwards_to_target_device(self):
        registry = DeviceRegistry()
        a, b = [], []
        hello(registry, "a", "sa", sink=a)
        hello(registry, "b", "sb", sink=b)
        hub = SyncHub(registry)
        hub.handle("remote_input", {"type": "remote_input", "ev": "move", "to": "b", "client_session": "sa",
                                    "token": "123456"}, a.append)
        relayed = [p for p in b if p.get("type") == "remote_input"]
        self.assertEqual(relayed[0]["from"], "a")
        self.assertNotIn("token", relayed[0])


class HttpTransferTests(unittest.TestCase):
    def _hub(self, receive_dir=None):
        from sync import TransferHttpServer
        registry = DeviceRegistry()
        sink = []
        hello(registry, "phone", "sp", sink=sink)
        hub = SyncHub(registry, receive_dir=receive_dir)
        hub.http = TransferHttpServer(hub, port=53736)
        self.assertTrue(hub.http.start())
        self.addCleanup(hub.http.stop)
        return hub, sink

    def wait(self, pred, timeout=10):
        end = time.time() + timeout
        while time.time() < end and not pred():
            time.sleep(0.05)
        return pred()

    def test_phone_pulls_file_over_http_with_resume(self):
        import hashlib
        import urllib.request
        hub, sink = self._hub()
        with tempfile.TemporaryDirectory() as d:
            src = Path(d) / "big.bin"
            data = os.urandom(CHUNK_SIZE * 7 + 99)
            src.write_bytes(data)
            tid = hub.send_file("phone", src)
            self.assertTrue(self.wait(lambda: any(p.get("type") == "file_offer" for p in sink)))
            offer = [p for p in sink if p.get("type") == "file_offer"][0]
            self.assertIn("http_token", offer)
            hub.handle("file_accept", {"transfer_id": tid, "have": [], "pull": True, "client_session": "sp"}, sink.append)
            url = f"http://127.0.0.1:{offer['http_port']}/t/{tid}?k={offer['http_token']}"
            req = urllib.request.Request(url, headers={"Range": f"bytes={CHUNK_SIZE * 2}-"})
            got = urllib.request.urlopen(req, timeout=5).read()
            self.assertEqual(got, data[CHUNK_SIZE * 2:])
            bad = urllib.request.Request(url.replace(offer["http_token"], "0" * 32))
            with self.assertRaises(Exception):
                urllib.request.urlopen(bad, timeout=5)
            hub.handle("file_result", {"transfer_id": tid, "ok": True, "client_session": "sp"}, sink.append)
            self.assertTrue(self.wait(lambda: hub._transfers[tid].state == "done"))
            self.assertEqual(offer["sha256"], hashlib.sha256(data).hexdigest())

    def test_phone_uploads_file_over_http(self):
        import hashlib
        import urllib.request
        with tempfile.TemporaryDirectory() as d:
            hub, sink = self._hub(receive_dir=d)
            data = os.urandom(CHUNK_SIZE * 3 + 5)
            sha = hashlib.sha256(data).hexdigest()
            got = []
            hub.on_file_received = lambda path, name: got.append(path)
            hub.handle("file_offer", {"transfer_id": "abc123", "name": "clip.mp4", "size": len(data), "sha256": sha,
                                      "chunk_size": CHUNK_SIZE, "http_upload": True, "client_session": "sp"}, sink.append)
            accept = [p for p in sink if p.get("type") == "file_accept"][-1]
            url = f"http://127.0.0.1:{accept['upload_port']}/u/abc123?k={accept['upload_token']}&offset=0"
            urllib.request.urlopen(urllib.request.Request(url, data=data, method="PUT"), timeout=5).read()
            hub.handle("file_done", {"transfer_id": "abc123", "client_session": "sp"}, sink.append)
            self.assertTrue(self.wait(lambda: any(p.get("type") == "file_result" for p in sink)))
            result = [p for p in sink if p.get("type") == "file_result"][-1]
            self.assertTrue(result["ok"], result)
            self.assertEqual(Path(got[0]).read_bytes(), data)


class StreamTests(unittest.TestCase):
    def test_frames_fan_out_to_viewers_and_demand_reaches_publisher(self):
        from streams import StreamHub
        registry = DeviceRegistry()
        phone, tab = [], []
        hello(registry, "phone", "sp", sink=phone)
        hello(registry, "tab", "st", sink=tab)
        hub = SyncHub(registry)
        from ecosystem import Ecosystem
        streams = StreamHub(registry)
        hub.extra_handlers.append(lambda t, d, r: streams.handle(t, d, hub._sender_id(d)))
        hub.handle("stream_announce", {"kind": "screen", "client_session": "sp"}, phone.append)
        self.assertTrue(any(p.get("type") == "streams_update" and p["streams"] for p in tab))
        hub.handle("stream_watch", {"stream_id": "phone:screen", "on": True, "client_session": "st"}, tab.append)
        self.assertTrue(any(p.get("type") == "stream_demand" and p["viewers"] == 1 for p in phone))
        local = []
        streams.watch_local("phone:screen", "win", local.append)
        hub.handle("stream_frame", {"stream_id": "phone:screen", "frame": "abc", "width": 2, "height": 3,
                                    "client_session": "sp"}, phone.append)
        self.assertTrue(any(p.get("type") == "stream_frame" and p["frame"] == "abc" for p in tab))
        self.assertEqual(local[0]["frame"], "abc")
        # A device cannot publish frames for someone else's stream.
        hub.handle("stream_frame", {"stream_id": "phone:screen", "frame": "evil", "client_session": "st"}, tab.append)
        self.assertFalse(any(p.get("frame") == "evil" for p in tab))


class FamilyKeyTests(unittest.TestCase):
    def test_family_key_is_stable(self):
        with tempfile.TemporaryDirectory() as d:
            old = os.environ.get("LOCALAPPDATA")
            os.environ["LOCALAPPDATA"] = d
            try:
                a = ecosystem.family_key()
                b = ecosystem.family_key()
            finally:
                if old is None:
                    os.environ.pop("LOCALAPPDATA")
                else:
                    os.environ["LOCALAPPDATA"] = old
            self.assertEqual(a, b)
            self.assertEqual(len(a["key"]), 64)


class LibraryTests(unittest.TestCase):
    def test_fetches_only_missing_items_once(self):
        from library import LibrarySync
        with tempfile.TemporaryDirectory() as d:
            dirs = {"videos": Path(d) / "V", "audios": Path(d) / "A", "snapshots": Path(d) / "S"}
            for p in dirs.values():
                p.mkdir()
            (dirs["snapshots"] / "have.jpg").write_bytes(b"12345")
            registry = DeviceRegistry()
            phone = []
            hello(registry, "phone", "sp", sink=phone)
            hub = SyncHub(registry)
            lib = LibrarySync(registry, hub, lambda: dirs)
            hub.extra_handlers.append(lib.handle)
            items = [{"id": "have.jpg", "name": "have.jpg", "size": 5}, {"id": "new.mp4", "name": "new.mp4", "size": 9}]
            hub.handle("library_items", {"items": items, "client_session": "sp"}, phone.append)
            hub.handle("library_items", {"items": items, "client_session": "sp"}, phone.append)
            fetches = [p["id"] for p in phone if p.get("type") == "library_fetch"]
            self.assertEqual(fetches, ["new.mp4"])
            self.assertEqual(hub.library_dir("x.wav"), dirs["audios"])


if __name__ == "__main__":
    unittest.main()
