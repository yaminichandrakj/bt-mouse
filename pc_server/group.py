"""Device groups: pairing without accounts (see docs/device_groups.md).

Every device has its own identity keys (Ed25519 to sign, X25519 to receive secrets).
A group is a signed member list (the roster) plus an epoch key that all members share.
The epoch key is what used to be the "family key": it seals the relay, phone-to-phone
links and PC calls, and it is replaced whenever a device is removed, so a removed device
is locked out of everything new. A device joins by scanning a live QR on a member: the
QR changes every 20 s and each code works once within 60 s.

Recovery: 256 random bits shown once as 24 words (BIP-39). Members keep only the
recovery public keys; the words can restore the group from a sealed backup.

Everything here is mirrored byte for byte in Group.kt; the test vectors in
test_group.py pin the formats down.
"""

import base64
import hashlib
import hmac
import json
import os
import secrets
import threading
import time
from pathlib import Path

try:
    from nacl import bindings as _nb
    from nacl.signing import SigningKey, VerifyKey
    HAS_CRYPTO = True
except Exception:  # pragma: no cover - PyNaCl is in requirements.txt
    _nb = None
    HAS_CRYPTO = False

PC_ID = "pc"
OFFER_LIFETIME = 60.0
OFFER_REFRESH = 20.0
# Next to this file when run from source; in the unpacked bundle when frozen by PyInstaller.
WORDS_PATH = Path(getattr(__import__("sys"), "_MEIPASS", Path(__file__).parent)) / "bip39_english.txt"


# --- primitives -------------------------------------------------------------
def b64(data):
    return base64.b64encode(data).decode("ascii")


def unb64(text):
    return base64.b64decode(str(text or ""))


def hkdf(ikm, info, length=32, salt=b""):
    """RFC 5869 HKDF-SHA256."""
    prk = hmac.new(salt or b"\x00" * 32, ikm, hashlib.sha256).digest()
    out, block, counter = b"", b"", 1
    while len(out) < length:
        block = hmac.new(prk, block + info + bytes([counter]), hashlib.sha256).digest()
        out += block
        counter += 1
    return out[:length]


def aead_seal(key, plaintext, aad=b""):
    nonce = os.urandom(12)
    return nonce + _nb.crypto_aead_chacha20poly1305_ietf_encrypt(plaintext, aad or None, nonce, key)


def aead_open(key, blob, aad=b""):
    return _nb.crypto_aead_chacha20poly1305_ietf_decrypt(blob[12:], aad or None, blob[:12], key)


def x25519_pub(private):
    return _nb.crypto_scalarmult_base(private)


def x25519(private, public):
    return _nb.crypto_scalarmult(private, public)


def seal_to(dh_pub, plaintext, info):
    """Encrypt to an X25519 public key: eph_pub(32) || nonce(12) || ChaCha20-Poly1305."""
    eph = os.urandom(32)
    eph_pub = x25519_pub(eph)
    key = hkdf(x25519(eph, dh_pub), info + eph_pub + dh_pub)
    return eph_pub + aead_seal(key, plaintext)


def open_from(dh_priv, blob, info):
    eph_pub = blob[:32]
    key = hkdf(x25519(dh_priv, eph_pub), info + eph_pub + x25519_pub(dh_priv))
    return aead_open(key, blob[32:])


def sign(seed, message):
    return SigningKey(seed).sign(message).signature


def verify(pub, message, signature):
    try:
        VerifyKey(pub).verify(message, signature)
        return True
    except Exception:
        return False


def sign_pub(seed):
    return bytes(SigningKey(seed).verify_key)


# --- recovery words (BIP-39, 24 words for 256 bits) -----------------------------
_WORDS = None


def _wordlist():
    global _WORDS
    if _WORDS is None:
        _WORDS = WORDS_PATH.read_text(encoding="utf-8").split()
    return _WORDS


def to_words(secret):
    bits = "".join(f"{b:08b}" for b in secret) + f"{hashlib.sha256(secret).digest()[0]:08b}"
    return [_wordlist()[int(bits[i:i + 11], 2)] for i in range(0, len(bits), 11)]


def from_words(words):
    """The 32-byte secret, or None if a word is wrong or the checksum doesn't match."""
    words = [w.strip().lower() for w in (words.split() if isinstance(words, str) else words) if w.strip()]
    if len(words) != 24:
        return None
    index = {w: i for i, w in enumerate(_wordlist())}
    try:
        bits = "".join(f"{index[w]:011b}" for w in words)
    except KeyError:
        return None
    secret = bytes(int(bits[i:i + 8], 2) for i in range(0, 256, 8))
    if f"{hashlib.sha256(secret).digest()[0]:08b}" != bits[256:]:
        return None
    return secret


def recovery_keys(secret):
    """group id, recovery signing seed, recovery X25519 private key."""
    return (hkdf(secret, b"lanyard-group-id", 8).hex(),
            hkdf(secret, b"lanyard-recovery-sign"),
            hkdf(secret, b"lanyard-recovery-dh"))


def backup_id(group_id):
    return hashlib.sha256(b"lanyard-backup|" + group_id.encode()).hexdigest()[:32]


def epoch_check(key):
    return hkdf(key, b"lanyard-epoch-check", 16).hex()


# --- roster -------------------------------------------------------------------
def roster_bytes(r):
    lines = ["lanyard-roster-v1", r["group_id"], str(int(r["version"])), str(int(r["epoch"])),
             r["epoch_check"], r["rec_sign"], r["rec_dh"]]
    for m in sorted(r["members"], key=lambda m: m["id"]):
        lines.append("\t".join([m["id"], m.get("kind", "phone"), m["sign"], m["dh"],
                                b64(str(m.get("name", "")).encode("utf-8"))]))
    return ("\n".join(lines) + "\n").encode("utf-8")


def sign_roster(r, signer, seed):
    r = dict(r)
    r.pop("sig", None)
    r["signer"] = signer
    r["sig"] = b64(sign(seed, roster_bytes(r)))
    return r


def member(r, device_id):
    return next((m for m in r.get("members", []) if m["id"] == device_id), None)


def roster_follows(current, new):
    """True when ``new`` is a valid next roster after ``current``: same group, newer,
    same recovery keys, signed by a current member or the recovery key."""
    try:
        if new["group_id"] != current["group_id"] or int(new["version"]) <= int(current["version"]):
            return False
        if new["rec_sign"] != current["rec_sign"] or new["rec_dh"] != current["rec_dh"]:
            return False
        signer = new.get("signer")
        if signer == "recovery":
            pub = unb64(current["rec_sign"])
        else:
            m = member(current, signer)
            if not m:
                return False
            pub = unb64(m["sign"])
        return verify(pub, roster_bytes(new), unb64(new["sig"]))
    except (KeyError, TypeError, ValueError):
        return False


def roster_signed_by(r, signer_sign_pub):
    try:
        return verify(signer_sign_pub, roster_bytes(r), unb64(r["sig"]))
    except (KeyError, TypeError, ValueError):
        return False


# --- link handshake (replaces the PIN-MAC exchange) ---------------------------
def hello_bytes(group_id, device_id, eph_b64):
    return f"lanyard-hs-c|{group_id}|{device_id}|{eph_b64}".encode("utf-8")


def transcript(group_id, client_id, server_id, client_eph_b64, server_eph_b64):
    return f"lanyard-hs|v2|{group_id}|{client_id}|{server_id}|{client_eph_b64}|{server_eph_b64}".encode("utf-8")


def session_key(shared, t):
    return hkdf(shared, b"lanyard-session|" + t)


# --- join (live QR) -------------------------------------------------------------
def join_transcript(t_id, inviter_eph_b64, joiner_eph_b64, joiner):
    return "|".join(["lanyard-join-v1", t_id, inviter_eph_b64, joiner_eph_b64,
                     joiner["id"], joiner["sign"], joiner["dh"]]).encode("utf-8")


def join_key(shared, token, t):
    return hkdf(shared, b"lanyard-join|" + token + t)


def token_id(token):
    return hashlib.sha256(token).hexdigest()[:16]


def epoch_info(group_id, epoch):
    return f"lanyard-epoch|{group_id}|{epoch}".encode("utf-8")


# --- Windows DPAPI for the private keys at rest ---------------------------------
def _dpapi(data, protect):
    if os.name != "nt":
        return data
    import ctypes
    from ctypes import wintypes

    class BLOB(ctypes.Structure):
        _fields_ = [("cbData", wintypes.DWORD), ("pbData", ctypes.POINTER(ctypes.c_char))]

    src = BLOB(len(data), ctypes.cast(ctypes.create_string_buffer(data, len(data)), ctypes.POINTER(ctypes.c_char)))
    out = BLOB()
    fn = ctypes.windll.crypt32.CryptProtectData if protect else ctypes.windll.crypt32.CryptUnprotectData
    if not fn(ctypes.byref(src), None, None, None, None, 0, ctypes.byref(out)):
        raise OSError("DPAPI failed")
    try:
        return ctypes.string_at(out.pbData, out.cbData)
    finally:
        ctypes.windll.kernel32.LocalFree(out.pbData)


# --- the group as this device holds it --------------------------------------
class Group:
    """This device's identity and its group. Thread-safe; saved to ``path`` on change."""

    def __init__(self, path, device_id=PC_ID, name="Laptop", kind="pc"):
        self.path = Path(path)
        self.device_id = device_id
        self.name = name
        self.kind = kind
        self._lock = threading.RLock()
        self._offers = {}         # token id -> (token, eph_priv, created)
        self._pending_keys = {}   # last rotation's sealed keys, for members that missed it
        self.relay_url = ""
        self.on_change = None
        self.pending_words = None  # set when a group is created here, until the user has seen them
        self._load()

    # persistence
    def _load(self):
        try:
            data = json.loads(self.path.read_text(encoding="utf-8"))
            secrets_blob = json.loads(_dpapi(unb64(data["secrets"]), False).decode("utf-8"))
            self.sign_seed = unb64(secrets_blob["sign"])
            self.dh_priv = unb64(secrets_blob["dh"])
            self.epoch_key = unb64(secrets_blob["epoch"])
            self.backup_seed = unb64(secrets_blob["backup"])
            self.roster = data["roster"]
            self._pending_keys = data.get("pending_keys", {})
            self.pending_words = data.get("pending_words")
            if member(self.roster, self.device_id) is None:
                raise ValueError("not in own roster")
        except (OSError, ValueError, KeyError, TypeError):
            self.sign_seed = os.urandom(32)
            self.dh_priv = os.urandom(32)
            self._create_group()

    def _save(self):
        secrets_blob = json.dumps({"sign": b64(self.sign_seed), "dh": b64(self.dh_priv),
                                   "epoch": b64(self.epoch_key), "backup": b64(self.backup_seed)}).encode("utf-8")
        data = {"v": 1, "secrets": b64(_dpapi(secrets_blob, True)), "roster": self.roster,
                "pending_keys": self._pending_keys}
        if self.pending_words:
            data["pending_words"] = self.pending_words
        tmp = self.path.with_suffix(".tmp")
        tmp.write_text(json.dumps(data), encoding="utf-8")
        os.replace(tmp, self.path)
        cb = self.on_change
        if cb:
            try:
                cb()
            except Exception:
                pass

    def me(self):
        return {"id": self.device_id, "name": self.name, "kind": self.kind,
                "sign": b64(sign_pub(self.sign_seed)), "dh": b64(x25519_pub(self.dh_priv))}

    def _create_group(self, secret=None):
        """A new group with only this device (first run, or after being removed)."""
        secret = secret or os.urandom(32)
        group_id, rec_seed, rec_dh = recovery_keys(secret)
        self.epoch_key = os.urandom(32)
        # Signs backup uploads to the Worker; shared with members, kept across epochs.
        self.backup_seed = os.urandom(32)
        self.roster = sign_roster({
            "group_id": group_id, "version": 1, "epoch": 1, "epoch_check": epoch_check(self.epoch_key),
            "rec_sign": b64(sign_pub(rec_seed)), "rec_dh": b64(x25519_pub(rec_dh)),
            "members": [self.me()],
        }, self.device_id, self.sign_seed)
        self.pending_words = " ".join(to_words(secret))
        self._pending_keys = {}
        self._save()

    # views
    @property
    def group_id(self):
        return self.roster["group_id"]

    @property
    def version(self):
        return int(self.roster["version"])

    @property
    def epoch(self):
        return int(self.roster["epoch"])

    def family(self):
        """What the rest of the PC used to call the family key."""
        return {"id": self.group_id, "key": self.epoch_key.hex()}

    def members(self):
        with self._lock:
            return [dict(m) for m in self.roster["members"]]

    def is_member(self, device_id):
        return member(self.roster, device_id) is not None

    def words_seen(self):
        with self._lock:
            self.pending_words = None
            self._save()

    # link handshake, server side
    def accept_hello(self, data):
        """A device's key_exchange v2. Returns (reply, session key, device id) or (error reply, None, None)."""
        with self._lock:
            device_id = str(data.get("id") or "")
            m = member(self.roster, device_id)
            if data.get("g") != self.group_id or m is None or device_id == self.device_id:
                return {"type": "key_exchange_ack", "v": 2, "ok": False, "error": "not_member",
                        "message": "This device isn't in this PC's group. Scan the QR on the PC's Connect page."}, None, None
            c_eph_b64 = str(data.get("eph") or "")
            c_eph = unb64(c_eph_b64)
            if len(c_eph) != 32 or not verify(unb64(m["sign"]), hello_bytes(self.group_id, device_id, c_eph_b64),
                                              unb64(data.get("sig"))):
                return {"type": "key_exchange_ack", "v": 2, "ok": False, "error": "bad_signature"}, None, None
            s_eph = os.urandom(32)
            s_eph_b64 = b64(x25519_pub(s_eph))
            t = transcript(self.group_id, device_id, self.device_id, c_eph_b64, s_eph_b64)
            key = session_key(x25519(s_eph, c_eph), t)
            reply = {"type": "key_exchange_ack", "v": 2, "ok": True, "id": self.device_id, "eph": s_eph_b64,
                     "sig": b64(sign(self.sign_seed, t)), "rv": self.version}
            if int(data.get("rv") or 0) < self.version:
                reply["update"] = self.update_for(device_id)
            return reply, key, device_id

    def update_for(self, device_id):
        """The current roster, plus this device's sealed epoch key if it may have missed a rotation."""
        upd = {"type": "group_update", "roster": self.roster}
        sealed = self._pending_keys.get(device_id)
        if sealed:
            upd["keys"] = {device_id: sealed}
        return upd

    # join (live QR), inviter side
    def new_offer(self):
        """A fresh one-time code for the live QR: (token id, fields for the QR)."""
        with self._lock:
            now = time.monotonic()
            for tid in [k for k, (_, _, at) in self._offers.items() if now - at > OFFER_LIFETIME]:
                del self._offers[tid]
            token = os.urandom(16)
            eph = os.urandom(32)
            tid = token_id(token)
            self._offers[tid] = (token, eph, now)
            return tid, {"by": self.device_id, "eph": b64(x25519_pub(eph)), "t": b64(token)}

    def accept_join(self, data, extra_state=None):
        """A scanner's group_join. Returns (reply, new member or None)."""
        with self._lock:
            tid = str(data.get("t_id") or "")
            offer = self._offers.get(tid)
            if not offer or time.monotonic() - offer[2] > OFFER_LIFETIME:
                return {"type": "group_join_ack", "ok": False, "error": "expired",
                        "message": "That code expired. Scan the QR again."}, None
            token, eph, _ = offer
            joiner = {"id": str(data.get("id") or "")[:64], "name": str(data.get("name") or "Device")[:60],
                      "kind": "tablet" if data.get("kind") == "tablet" else ("pc" if data.get("kind") == "pc" else "phone"),
                      "sign": str(data.get("sign") or ""), "dh": str(data.get("dh") or "")}
            j_eph_b64 = str(data.get("eph") or "")
            if (not joiner["id"] or joiner["id"] == self.device_id or len(unb64(joiner["sign"])) != 32
                    or len(unb64(joiner["dh"])) != 32 or len(unb64(j_eph_b64)) != 32):
                return {"type": "group_join_ack", "ok": False, "error": "bad_request"}, None
            inviter_eph_b64 = b64(x25519_pub(eph))
            t = join_transcript(tid, inviter_eph_b64, j_eph_b64, joiner)
            if not hmac.compare_digest(str(data.get("proof") or ""), hmac.new(token, t, hashlib.sha256).hexdigest()):
                return {"type": "group_join_ack", "ok": False, "error": "bad_proof"}, None
            del self._offers[tid]  # one use only
            members = [m for m in self.roster["members"] if m["id"] != joiner["id"]] + [joiner]
            self.roster = sign_roster(dict(self.roster, version=self.version + 1, members=members),
                                      self.device_id, self.sign_seed)
            self._save()
            state = {"group_id": self.group_id, "epoch_key": b64(self.epoch_key), "roster": self.roster,
                     "relay_url": self.relay_url, "backup_seed": b64(self.backup_seed)}
            state.update(extra_state or {})
            key = join_key(x25519(eph, unb64(j_eph_b64)), token, t)
            box = aead_seal(key, json.dumps(state).encode("utf-8"))
            return {"type": "group_join_ack", "ok": True, "t_id": tid, "box": b64(box)}, joiner

    # removing a device
    def remove(self, device_id):
        """Drop a device and start a new epoch it never learns. Returns the group_update to send."""
        with self._lock:
            if device_id == self.device_id or not self.is_member(device_id):
                return None
            return self._rotate([m for m in self.roster["members"] if m["id"] != device_id], self.device_id, self.sign_seed)

    def _rotate(self, members, signer, seed):
        self.epoch_key = os.urandom(32)
        epoch = self.epoch + 1
        keys = {m["id"]: b64(seal_to(unb64(m["dh"]), self.epoch_key, epoch_info(self.group_id, epoch)))
                for m in members if m["id"] != self.device_id}
        self.roster = sign_roster(dict(self.roster, version=self.version + 1, epoch=epoch,
                                       epoch_check=epoch_check(self.epoch_key), members=members), signer, seed)
        self._pending_keys = keys
        self._save()
        return {"type": "group_update", "roster": self.roster, "keys": keys}

    # updates from other members
    def apply_update(self, upd):
        """Returns "applied", "removed" (this device is no longer in the group) or None."""
        with self._lock:
            new = upd.get("roster") if isinstance(upd, dict) else None
            if not isinstance(new, dict) or not roster_follows(self.roster, new):
                return None
            if member(new, self.device_id) is None:
                self._create_group()
                return "removed"
            if int(new["epoch"]) != self.epoch:
                sealed = (upd.get("keys") or {}).get(self.device_id)
                if not sealed:
                    return None
                try:
                    key = open_from(self.dh_priv, unb64(sealed), epoch_info(new["group_id"], int(new["epoch"])))
                except Exception:
                    return None
                if epoch_check(key) != new["epoch_check"]:
                    return None
                self.epoch_key = key
                self._pending_keys = dict(upd.get("keys") or {})
            self.roster = new
            self._save()
            return "applied"

    # recovery backup
    def backup_record(self):
        """The sealed backup for the Worker: readable only with the recovery words."""
        with self._lock:
            blob = seal_to(unb64(self.roster["rec_dh"]),
                           json.dumps({"roster": self.roster, "epoch_key": b64(self.epoch_key),
                                       "backup_seed": b64(self.backup_seed)}).encode("utf-8"),
                           b"lanyard-backup|" + self.group_id.encode())
            bid = backup_id(self.group_id)
            blob_b64 = b64(blob)
            return {"id": bid, "blob": blob_b64, "version": self.version,
                    "pub": b64(sign_pub(self.backup_seed)),
                    "sig": b64(sign(self.backup_seed, f"{bid}|{self.version}|{blob_b64}".encode("utf-8")))}

    def restore(self, words, backup_blob):
        """Rejoin with the 24 words and the Worker's backup. Starts a new epoch. True on success."""
        secret = from_words(words)
        if secret is None:
            return False
        group_id, rec_seed, rec_dh = recovery_keys(secret)
        try:
            state = json.loads(open_from(rec_dh, unb64(backup_blob), b"lanyard-backup|" + group_id.encode()))
            roster = state["roster"]
        except Exception:
            return False
        if roster.get("group_id") != group_id or roster.get("rec_sign") != b64(sign_pub(rec_seed)):
            return False
        with self._lock:
            self.roster = roster
            self.epoch_key = unb64(state["epoch_key"])
            self.backup_seed = unb64(state.get("backup_seed")) or os.urandom(32)
            members = [m for m in roster["members"] if m["id"] != self.device_id] + [self.me()]
            self._rotate(members, "recovery", rec_seed)
            self.pending_words = None
            self._save()
        return True


def default_group():
    from ecosystem import _data_dir
    import socket
    return Group(_data_dir() / "group.json", device_id=PC_ID, name=socket.gethostname(), kind="pc")
