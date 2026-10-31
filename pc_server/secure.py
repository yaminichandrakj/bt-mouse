"""Encrypted sessions between the PC and a phone or tablet.

After a link connects, the phone sends a fresh X25519 public key with an HMAC keyed by
the pairing PIN; the PC answers with its own, also MACed. Both derive a 32-byte session
key from the X25519 shared secret, so someone who only listens on the network cannot read
the traffic even if they later learn the PIN. Every message after that is sealed with
ChaCha20-Poly1305 (12-byte random nonce in front, IETF variant) and travels as
``{"enc": <session id>, "c": <base64>}``. Phones that never start the exchange keep
working unencrypted.
"""

import base64
import hashlib
import hmac
import json
import os
import secrets
import threading
import time

try:
    from nacl import bindings as _nb
    HAS_CRYPTO = True
except Exception:  # pragma: no cover - PyNaCl is in requirements.txt
    _nb = None
    HAS_CRYPTO = False


def pin_key(pin):
    return hashlib.sha256(b"lanyard-v1|" + str(pin).encode("utf-8")).digest()


def mac(key, *parts):
    return hmac.new(key, b"|".join(parts), hashlib.sha256).hexdigest()


def seal(key, plaintext):
    nonce = os.urandom(12)
    return nonce + _nb.crypto_aead_chacha20poly1305_ietf_encrypt(plaintext, None, nonce, key)


def unseal(key, blob):
    return _nb.crypto_aead_chacha20poly1305_ietf_decrypt(blob[12:], None, blob[:12], key)


class SessionStore:
    """Session keys by id. A session ends when unused for a day or when the PIN changes."""

    IDLE_LIMIT = 24 * 3600

    def __init__(self):
        self._lock = threading.Lock()
        self._sessions = {}

    def handshake(self, data, pin):
        """Answer a phone's key_exchange. Returns the reply dict, or None to ignore it."""
        if not HAS_CRYPTO:
            return None
        try:
            client_pub = base64.b64decode(str(data.get("pub") or ""))
        except Exception:
            return None
        if len(client_pub) != 32:
            return None
        pk = pin_key(pin)
        if not hmac.compare_digest(str(data.get("mac") or ""), mac(pk, b"c", client_pub)):
            return None
        private = os.urandom(32)
        server_pub = _nb.crypto_scalarmult_base(private)
        shared = _nb.crypto_scalarmult(private, client_pub)
        key = hmac.new(pk, shared + client_pub + server_pub, hashlib.sha256).digest()
        sid = secrets.token_hex(8)
        with self._lock:
            self._prune()
            self._sessions[sid] = [key, time.monotonic()]
        return {"type": "key_exchange_ack", "sid": sid, "pub": base64.b64encode(server_pub).decode("ascii"),
                "mac": mac(pk, b"s", client_pub, server_pub)}

    def open(self, data):
        """(inner dict, sealer) for an encrypted packet, or (None, None) if it can't be read."""
        sid = str(data.get("enc") or "")
        with self._lock:
            entry = self._sessions.get(sid)
            if entry:
                entry[1] = time.monotonic()
        if not entry or not HAS_CRYPTO:
            return None, None
        key = entry[0]
        try:
            inner = json.loads(unseal(key, base64.b64decode(str(data.get("c") or ""))).decode("utf-8"))
        except Exception:
            return None, None
        if not isinstance(inner, dict):
            return None, None

        def sealer(payload):
            blob = seal(key, json.dumps(payload).encode("utf-8"))
            return {"enc": sid, "c": base64.b64encode(blob).decode("ascii")}

        return inner, sealer

    def clear(self):
        """The PIN changed: every phone has to exchange keys again."""
        with self._lock:
            self._sessions.clear()

    def _prune(self):
        now = time.monotonic()
        for sid in [s for s, (_, last) in self._sessions.items() if now - last > self.IDLE_LIMIT]:
            del self._sessions[sid]


class ClientSession:
    """The phone's side, used by tests (the real one is SecureTransport.kt)."""

    def __init__(self, pin):
        self.pk = pin_key(pin)
        self.private = os.urandom(32)
        self.public = _nb.crypto_scalarmult_base(self.private)
        self.key = None
        self.sid = None

    def hello(self):
        return {"type": "key_exchange", "pub": base64.b64encode(self.public).decode("ascii"),
                "mac": mac(self.pk, b"c", self.public)}

    def finish(self, ack):
        server_pub = base64.b64decode(ack["pub"])
        if not hmac.compare_digest(ack["mac"], mac(self.pk, b"s", self.public, server_pub)):
            return False
        shared = _nb.crypto_scalarmult(self.private, server_pub)
        self.key = hmac.new(self.pk, shared + self.public + server_pub, hashlib.sha256).digest()
        self.sid = ack["sid"]
        return True

    def wrap(self, payload):
        return {"enc": self.sid, "c": base64.b64encode(seal(self.key, json.dumps(payload).encode())).decode()}

    def unwrap(self, data):
        return json.loads(unseal(self.key, base64.b64decode(data["c"])).decode())
