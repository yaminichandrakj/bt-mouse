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

    def test_edge_outside_placed_range_does_not_cross(self):
        self.share.on_mouse_move(1919, 100)
        self.share.on_mouse_move(1919, 100)
        self.assertIsNone(self.share.target)

    def test_moving_left_off_phone_returns_to_monitor(self):
        self.share.switch_to("phone1", entry=(1921, 540))
        self.assertTrue(self.share.on_mouse_move(960 - 50, 540))
        self.assertIsNone(self.share.target)
        self.assertLess(self.cursor[-1][0], 1920)

    def test_moves_are_sent_while_remote(self):
        self.share.switch_to("phone1")
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

    def test_typing_goes_to_phone_with_char(self):
        self.share.switch_to("phone1")
        self.assertTrue(self.share.on_key(0x41, True))
        self.assertTrue(self.share.on_key(0x41, False))
        self.assertTrue(self.wait_for(lambda: any(p.get("ev") == "key" and p.get("char") == "a" for p in self.sent)))

    def test_link_loss_returns_control(self):
        self.share.switch_to("phone1")
        self.registry._devices["phone1"]["last_seen"] = 0
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


if __name__ == "__main__":
    unittest.main()
