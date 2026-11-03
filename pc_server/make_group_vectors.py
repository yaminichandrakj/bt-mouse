"""Writes group_vectors.json: fixed inputs and outputs that group.py and Group.kt must both
reproduce (the Android unit test reads a copy). Run again only if the formats change."""

import json
from pathlib import Path

import group as g

HERE = Path(__file__).parent


def build():
    secret = bytes(range(32))
    gid, rec_seed, rec_dh = g.recovery_keys(secret)
    seed_a = bytes([7]) * 32
    dh_a = bytes([9]) * 32
    epoch_key = bytes([0x11]) * 32
    roster = {
        "group_id": gid, "version": 3, "epoch": 2, "epoch_check": g.epoch_check(epoch_key),
        "rec_sign": g.b64(g.sign_pub(rec_seed)), "rec_dh": g.b64(g.x25519_pub(rec_dh)),
        "members": [
            {"id": "pc", "name": "Laptop", "kind": "pc", "sign": g.b64(g.sign_pub(seed_a)), "dh": g.b64(g.x25519_pub(dh_a))},
            {"id": "a1b2", "name": "Pixel ✓", "kind": "phone", "sign": g.b64(g.sign_pub(bytes([5]) * 32)),
             "dh": g.b64(g.x25519_pub(bytes([6]) * 32))},
        ],
    }
    signed = g.sign_roster(roster, "pc", seed_a)
    sealed = g.seal_to(g.x25519_pub(dh_a), b"epoch key here", g.epoch_info(gid, 2))
    shared = bytes([0x22]) * 32
    t = g.transcript(gid, "a1b2", "pc", "Y2xpZW50", "c2VydmVy")
    token = bytes([0x33]) * 16
    joiner = {"id": "a1b2", "sign": roster["members"][1]["sign"], "dh": roster["members"][1]["dh"]}
    jt = g.join_transcript(g.token_id(token), "aW52", "am9pbg==", joiner)
    import hashlib
    import hmac
    return {
        "hkdf": {"ikm": secret.hex(), "info": "lanyard-test", "out": g.hkdf(secret, b"lanyard-test").hex()},
        "words_zero": " ".join(g.to_words(bytes(32))),
        "words": " ".join(g.to_words(secret)),
        "secret": secret.hex(),
        "group_id": gid,
        "rec_sign_pub": roster["rec_sign"],
        "rec_dh_pub": roster["rec_dh"],
        "backup_id": g.backup_id(gid),
        "epoch_key": epoch_key.hex(),
        "epoch_check": roster["epoch_check"],
        "seed_a": seed_a.hex(), "dh_a": dh_a.hex(),
        "roster": signed,
        "roster_bytes": g.roster_bytes(signed).decode("utf-8"),
        "sealed": {"blob": g.b64(sealed), "info": g.epoch_info(gid, 2).decode(), "plain": "epoch key here"},
        "hello_bytes": g.hello_bytes(gid, "a1b2", "Y2xpZW50").decode(),
        "transcript": t.decode(),
        "session_key": g.session_key(shared, t).hex(),
        "shared": shared.hex(),
        "token": token.hex(),
        "token_id": g.token_id(token),
        "join_transcript": jt.decode(),
        "join_key": g.join_key(shared, token, jt).hex(),
        "join_proof": hmac.new(token, jt, hashlib.sha256).hexdigest(),
    }


if __name__ == "__main__":
    data = build()
    text = json.dumps(data, indent=1, ensure_ascii=False)
    (HERE / "group_vectors.json").write_text(text, encoding="utf-8")
    android = HERE.parent / "app" / "src" / "test" / "resources" / "group_vectors.json"
    android.parent.mkdir(parents=True, exist_ok=True)
    android.write_text(text, encoding="utf-8")
    print("wrote", len(data), "vectors")
