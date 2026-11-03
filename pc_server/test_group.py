import base64
import hashlib
import hmac
import json
import os
import tempfile
import unittest
from pathlib import Path

import group as g


def joiner_request(inviter_fields, tid, joiner_group, token_override=None):
    """What a scanning device sends after reading the live QR (mirrors Group.kt)."""
    token = token_override or base64.b64decode(inviter_fields["t"])
    j_eph = os.urandom(32)
    me = joiner_group.me()
    req = {"type": "group_join", "t_id": tid, "id": me["id"], "name": me["name"], "kind": me["kind"],
           "sign": me["sign"], "dh": me["dh"], "eph": g.b64(g.x25519_pub(j_eph))}
    t = g.join_transcript(tid, inviter_fields["eph"], req["eph"], me)
    req["proof"] = hmac.new(token, t, hashlib.sha256).hexdigest()
    return req, j_eph, t, token


def open_join(reply, inviter_fields, j_eph, t, token):
    key = g.join_key(g.x25519(j_eph, base64.b64decode(inviter_fields["eph"])), token, t)
    return json.loads(g.aead_open(key, base64.b64decode(reply["box"])))


@unittest.skipUnless(g.HAS_CRYPTO, "PyNaCl not installed")
class VectorTests(unittest.TestCase):
    def setUp(self):
        self.v = json.loads((Path(__file__).with_name("group_vectors.json")).read_text(encoding="utf-8"))

    def test_hkdf_matches_rfc5869_case_1(self):
        ikm = bytes.fromhex("0b" * 22)
        out = g.hkdf(ikm, bytes.fromhex("f0f1f2f3f4f5f6f7f8f9"), 42, salt=bytes.fromhex("000102030405060708090a0b0c"))
        self.assertEqual(out.hex(), "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865")

    def test_fixed_vectors_still_hold(self):
        v = self.v
        secret = bytes.fromhex(v["secret"])
        self.assertEqual(g.hkdf(secret, b"lanyard-test").hex(), v["hkdf"]["out"])
        self.assertEqual(" ".join(g.to_words(secret)), v["words"])
        self.assertEqual(g.from_words(v["words"]), secret)
        self.assertTrue(v["words_zero"].endswith(" art"))
        gid, rec_seed, rec_dh = g.recovery_keys(secret)
        self.assertEqual(gid, v["group_id"])
        self.assertEqual(g.b64(g.sign_pub(rec_seed)), v["rec_sign_pub"])
        self.assertEqual(g.roster_bytes(v["roster"]).decode(), v["roster_bytes"])
        # Ed25519 is deterministic: the same seed and bytes give the same signature.
        self.assertEqual(g.sign_roster(v["roster"], "pc", bytes.fromhex(v["seed_a"]))["sig"], v["roster"]["sig"])
        plain = g.open_from(bytes.fromhex(v["dh_a"]), base64.b64decode(v["sealed"]["blob"]), v["sealed"]["info"].encode())
        self.assertEqual(plain.decode(), v["sealed"]["plain"])
        t = v["transcript"].encode()
        self.assertEqual(g.session_key(bytes.fromhex(v["shared"]), t).hex(), v["session_key"])
        self.assertEqual(g.join_key(bytes.fromhex(v["shared"]), bytes.fromhex(v["token"]), v["join_transcript"].encode()).hex(),
                         v["join_key"])

    def test_wrong_words_are_refused(self):
        words = self.v["words"].split()
        words[0], words[1] = words[1], words[0]
        self.assertIsNone(g.from_words(words))
        self.assertIsNone(g.from_words("not enough words"))


@unittest.skipUnless(g.HAS_CRYPTO, "PyNaCl not installed")
class GroupFlowTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        d = Path(self.tmp.name)
        self.pc = g.Group(d / "pc.json", device_id="pc", name="Laptop", kind="pc")
        self.phone = g.Group(d / "phone.json", device_id="phone1", name="Pixel", kind="phone")
        self.tab = g.Group(d / "tab.json", device_id="tab1", name="Tab", kind="tablet")

    def tearDown(self):
        self.tmp.cleanup()

    def join(self, inviter, joiner):
        tid, fields = inviter.new_offer()
        req, j_eph, t, token = joiner_request(fields, tid, joiner)
        reply, added = inviter.accept_join(req)
        self.assertTrue(reply["ok"], reply)
        state = open_join(reply, fields, j_eph, t, token)
        # The joiner adopts the group (what Group.kt does).
        joiner.roster = state["roster"]
        joiner.epoch_key = base64.b64decode(state["epoch_key"])
        joiner._save()
        return state

    def test_new_group_offers_words_and_survives_restart(self):
        self.assertEqual(len(self.pc.pending_words.split()), 24)
        again = g.Group(self.pc.path, device_id="pc")
        self.assertEqual(again.group_id, self.pc.group_id)
        self.assertEqual(again.epoch_key, self.pc.epoch_key)

    def test_join_adds_device_and_shares_the_key(self):
        state = self.join(self.pc, self.phone)
        self.assertEqual(state["group_id"], self.pc.group_id)
        self.assertTrue(self.pc.is_member("phone1"))
        self.assertEqual(self.phone.family(), self.pc.family())
        self.assertTrue(g.roster_signed_by(state["roster"], g.unb64(self.pc.me()["sign"])))

    def test_codes_work_once_and_need_the_token(self):
        tid, fields = self.pc.new_offer()
        req, *_ = joiner_request(fields, tid, self.phone, token_override=b"x" * 16)
        self.assertEqual(self.pc.accept_join(req)[0]["error"], "bad_proof")
        req, j_eph, t, token = joiner_request(fields, tid, self.phone)
        self.assertTrue(self.pc.accept_join(req)[0]["ok"])
        req2, *_ = joiner_request(fields, tid, self.tab)
        self.assertEqual(self.pc.accept_join(req2)[0]["error"], "expired")

    def test_codes_expire(self):
        tid, fields = self.pc.new_offer()
        token, eph, at = self.pc._offers[tid]
        self.pc._offers[tid] = (token, eph, at - g.OFFER_LIFETIME - 1)
        req, *_ = joiner_request(fields, tid, self.phone)
        self.assertEqual(self.pc.accept_join(req)[0]["error"], "expired")

    def test_handshake_only_for_members(self):
        self.join(self.pc, self.phone)
        c_eph = os.urandom(32)
        eph_b64 = g.b64(g.x25519_pub(c_eph))
        hello = {"type": "key_exchange", "v": 2, "id": "phone1", "g": self.phone.group_id, "eph": eph_b64,
                 "rv": self.phone.version, "sig": g.b64(g.sign(self.phone.sign_seed, g.hello_bytes(self.phone.group_id, "phone1", eph_b64)))}
        reply, key, device = self.pc.accept_hello(hello)
        self.assertTrue(reply["ok"])
        self.assertEqual(device, "phone1")
        t = g.transcript(self.pc.group_id, "phone1", "pc", eph_b64, reply["eph"])
        self.assertTrue(g.verify(g.unb64(self.pc.me()["sign"]), t, g.unb64(reply["sig"])))
        self.assertEqual(g.session_key(g.x25519(c_eph, g.unb64(reply["eph"])), t), key)
        # A device outside the group, or a forged signature, gets nothing.
        stranger = dict(hello, id="tab1")
        self.assertFalse(self.pc.accept_hello(stranger)[0]["ok"])
        forged = dict(hello, sig=g.b64(b"\x00" * 64))
        self.assertFalse(self.pc.accept_hello(forged)[0]["ok"])

    def test_removing_a_device_locks_it_out(self):
        self.join(self.pc, self.phone)
        self.join(self.pc, self.tab)
        self.phone.apply_update({"roster": self.pc.roster})
        old_key = self.pc.epoch_key
        upd = self.pc.remove("tab1")
        self.assertNotEqual(self.pc.epoch_key, old_key)
        self.assertEqual(self.phone.apply_update(upd), "applied")
        self.assertEqual(self.phone.epoch_key, self.pc.epoch_key)
        self.assertNotIn("tab1", upd["keys"])
        # The removed tablet learns it was removed and starts over on its own.
        self.assertEqual(self.tab.apply_update(upd), "removed")
        self.assertNotEqual(self.tab.group_id, self.pc.group_id)
        self.assertNotEqual(self.tab.epoch_key, self.pc.epoch_key)
        # It can't open the new key even with the update in hand.
        with self.assertRaises(Exception):
            g.open_from(self.tab.dh_priv, g.unb64(upd["keys"]["phone1"]), g.epoch_info(self.pc.group_id, self.pc.epoch))

    def test_updates_must_be_signed_by_a_member(self):
        self.join(self.pc, self.phone)
        forged = g.sign_roster(dict(self.pc.roster, version=99, members=self.pc.roster["members"][:1]),
                               "phone1", os.urandom(32))
        self.assertIsNone(self.phone.apply_update({"roster": forged}))
        old = dict(self.pc.roster)
        self.assertIsNone(self.phone.apply_update({"roster": old}))  # not newer

    def test_a_device_that_missed_a_rotation_catches_up_on_connect(self):
        self.join(self.pc, self.phone)
        self.join(self.pc, self.tab)
        self.phone.apply_update({"roster": self.pc.roster})
        self.pc.remove("tab1")
        upd = self.pc.update_for("phone1")
        self.assertEqual(self.phone.apply_update(upd), "applied")
        self.assertEqual(self.phone.epoch_key, self.pc.epoch_key)

    def test_restore_with_words(self):
        words = self.pc.pending_words
        self.join(self.pc, self.phone)
        backup = self.pc.backup_record()
        fresh = g.Group(Path(self.tmp.name) / "new.json", device_id="phone9", name="New phone")
        self.assertFalse(fresh.restore("wrong " * 24, backup["blob"]))
        self.assertTrue(fresh.restore(words, backup["blob"]))
        self.assertEqual(fresh.group_id, self.pc.group_id)
        self.assertTrue(fresh.is_member("phone9"))
        # Members accept the recovery-signed roster and the new epoch key.
        self.assertEqual(self.pc.apply_update({"roster": fresh.roster, "keys": fresh._pending_keys}), "applied")
        self.assertEqual(self.pc.epoch_key, fresh.epoch_key)


@unittest.skipUnless(g.HAS_CRYPTO, "PyNaCl not installed")
class ServerGroupModeTests(unittest.TestCase):
    """The PC's packet handler with a device group: only member sessions get anywhere."""
    CLIENT = ("192.0.2.10", 40000)

    def setUp(self):
        from unittest.mock import Mock
        import server
        from secure import SessionStore
        self.tmp = tempfile.TemporaryDirectory()
        s = object.__new__(server.Server)
        s.auth_token = "123456"
        s.auth_failures = {}
        s.connected_client = None
        s.on_client_callback = None
        s.on_packet_callback = None
        s.on_group_changed = None
        s.last_buttons = 0
        s.last_pen_button = None
        s.last_input_at = 0
        s.pressed_keys = set()
        s.mouse = Mock()
        s.keyboard = Mock()
        s.ecosystem = None
        s.secure_sessions = SessionStore()
        s.group = g.Group(Path(self.tmp.name) / "pc.json", device_id="pc", name="Laptop", kind="pc")
        s._secure_nudged = {}
        s._upload_group_backup = lambda: None
        self.server = s
        self.phone = g.Group(Path(self.tmp.name) / "phone.json", device_id="phone1", name="Pixel")

    def tearDown(self):
        self.tmp.cleanup()

    def send(self, packet):
        out = []
        self.server.handle_packet(packet, self.CLIENT, out.append)
        return out

    def join_and_handshake(self):
        fields = self.server.group_offer()
        tid = g.token_id(base64.b64decode(fields["t"]))
        req, j_eph, t, token = joiner_request(fields, tid, self.phone)
        reply = self.send(req)[0]
        self.assertTrue(reply["ok"], reply)
        state = open_join(reply, fields, j_eph, t, token)
        self.phone.roster, self.phone.epoch_key = state["roster"], base64.b64decode(state["epoch_key"])
        c_eph = os.urandom(32)
        eph = g.b64(g.x25519_pub(c_eph))
        ack = self.send({"type": "key_exchange", "v": 2, "id": "phone1", "g": self.phone.group_id, "eph": eph,
                         "rv": self.phone.version,
                         "sig": g.b64(g.sign(self.phone.sign_seed, g.hello_bytes(self.phone.group_id, "phone1", eph)))})[0]
        self.assertTrue(ack["ok"], ack)
        tr = g.transcript(self.phone.group_id, "phone1", "pc", eph, ack["eph"])
        return ack["sid"], g.session_key(g.x25519(c_eph, g.unb64(ack["eph"])), tr)

    def sealed(self, sid, key, payload):
        import secure
        return {"enc": sid, "c": g.b64(secure.seal(key, json.dumps(payload).encode()))}

    def opened(self, key, packet):
        import secure
        return json.loads(secure.unseal(key, g.unb64(packet["c"])))

    def test_the_pin_alone_gets_nothing(self):
        out = self.send({"type": "ping", "token": "123456", "client_time": 5})
        self.assertEqual(out, [{"type": "secure_reset"}])
        self.assertEqual(self.send({"type": "ping", "token": "123456"}), [])  # rate-limited
        old = self.send({"type": "key_exchange", "pub": "x", "mac": "y"})
        self.assertEqual(old[0]["type"], "auth_error")
        ack = self.send({"type": "connect", "token": "nope"})[0]
        self.assertEqual(ack["secure"], "group")

    def test_member_session_works_and_removal_ends_it(self):
        sid, key = self.join_and_handshake()
        out = self.send(self.sealed(sid, key, {"type": "ping", "client_time": 7}))
        self.assertEqual(self.opened(key, out[0])["type"], "pong")
        self.assertTrue(self.server.remove_group_member("phone1"))
        out = self.send(self.sealed(sid, key, {"type": "ping", "client_time": 8}))
        self.assertEqual(out, [{"type": "secure_reset"}])
        # And it can't start a new session.
        c_eph = os.urandom(32)
        eph = g.b64(g.x25519_pub(c_eph))
        ack = self.send({"type": "key_exchange", "v": 2, "id": "phone1", "g": self.phone.group_id, "eph": eph,
                         "sig": g.b64(g.sign(self.phone.sign_seed, g.hello_bytes(self.phone.group_id, "phone1", eph)))})[0]
        self.assertFalse(ack["ok"])


if __name__ == "__main__":
    unittest.main()
