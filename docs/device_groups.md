# Device groups: pairing without accounts

**Status:** built (not yet tried on devices) · **Date:** 2026-11-03

Replaces the pairing PIN and the shared family key with a Brave-Sync-style device group,
fixed where Brave's design is weak: the secret you can see is never the secret that lets
a device in, and removing a device really locks it out.

## Decisions

| Question | Decision |
|---|---|
| Accounts | None. No email, password or sign-in. The group is anonymous. |
| How a device joins | A **live QR** on a device already in the group: it changes every 20 s and each code works once, within 60 s. Scanning it is the whole pairing, with no extra taps. |
| Recovery | 24 words, offered at setup (skippable) and under Settings. Used only when every device is lost. |
| Server | Your own Cloudflare Worker (the one that already runs rendezvous and the relay). It stores no personal data. |
| Existing devices | Not moved over: each device joins once with the live QR after updating. |

## The pieces

**Device identity.** Each device makes two key pairs on first run and never shares the private halves:
an Ed25519 signing key and an X25519 key for receiving secrets. On Android they live in the
Keystore (Ed25519 on Android 13+; P-256 below that); on the PC they're protected with Windows DPAPI.

**Group.** One per person:
- `group_id`: random, derived from the recovery secret (below). It names nothing about you.
- **Roster**: the signed member list `{device_id, name, kind, sign_pub, dh_pub, added_at}` with a version number.
- **Epoch key**: a random 32-byte key shared by the current members. It replaces today's family key
  (relay room and sealing, family links). It is **replaced** whenever a device is removed.

**Recovery secret.** 256 random bits shown as 24 words (BIP-39 word list). From it come:
`group_id`, a recovery signing key and a recovery X25519 key. Devices keep only the recovery
*public* keys, never the words, so a device that's removed (or stolen) can't use them.

## Joining with the live QR

```
Existing device A (unlocked, Devices › Add a device)        New device N
  every 20 s: new one-time token t (16 bytes),
  shows QR { v, a_id, a_dh_eph, t, lan: ip:port, relay }
                                                 ── scan ──>
                         <── N connects (Wi-Fi to A's hub, else relay room "join-"+H(t)) ──
                         <── join_hello { n_sign_pub, n_dh_pub, name, kind, n_eph,
                                          proof = HMAC(t, transcript) }
  check: t is live (< 60 s) and unused → burn it
  check: proof matches
  sign roster v+1 with N added
  send sealed to N (key = X25519(a_eph, n_eph) + t):
     { group_id, epoch key, roster, recovery pubs, relay URL, PC rendezvous }
  tell every member: roster v+1
  toast on A: "Pixel 9 joined · Undo"
```

- **Why it's safe without a tap:** the QR is only on screen while A is unlocked and showing *Add a
  device*, each token works once and dies after 60 s. A photo or screenshot of an old QR is useless.
- **Undo** on A removes N straight away (the normal removal, below).
- Works across networks: if N can't reach A on Wi-Fi it meets A in a one-off relay room.
- It works the other way round too, for a device that can't scan (the PC): the **new** device shows a
  live "Join" QR and a member phone scans it. The member's user chose to scan, so it's just as safe.
  The PC's Connect page shows whichever fits: "Add a device" when the PC is in a group, "Join" when not.

## Links between members

Every link (Wi-Fi, Bluetooth, internet tunnel, relay, phone hub) starts with one handshake,
replacing today's PIN-MAC (`secure.py`) and family challenge (`AndroidHub`/`PeerLinks`):

1. Both sides send an ephemeral X25519 key.
2. Each signs the transcript (both ephemerals, both device ids, the roster version) with its identity key.
3. Each checks the other's signing key is in its roster. If the other has a newer signed roster, it's
   adopted first.
4. Session key = HKDF(X25519 shared secret, transcript). ChaCha20-Poly1305 as today.

Nothing replayable crosses the network, there's no PIN to guess offline from a captured handshake,
and every message is tied to a specific device.

## Removing a device

From any member (phone or PC): Devices › the device › Remove.
1. New roster v+1 without it, signed by the remover.
2. New random epoch key, sealed separately to each remaining member's `dh_pub`, sent with the roster.
3. The relay room id is derived from the epoch key, so the removed device can't even find the new room.
4. Its links are dropped the moment the new roster arrives, and its handshakes fail from then on.

A member that was offline during the removal gets the new roster and key from the first member it
meets. Until then it only knows the old room, where nobody is left.

## Recovery

Every roster change also uploads a backup to the Worker: `{roster, epoch key}` sealed to the
**recovery public key**, stored at `PUT /g/<H(group_id)>`. Members can write it but not read it.

On a new device, *Restore with recovery words*:
1. Words → recovery keys → `group_id` → `GET /g/...` → decrypt.
2. The device adds itself to the roster, signed with the recovery signing key, which members always
   accept, and starts a new epoch (so whatever was lost is locked out).

Backup writes are authorised by a write key tied to the current epoch (Worker checks the signature,
like the rendezvous record). A removed device could at worst overwrite the backup until the next
rotation reaches the Worker: a nuisance, never a leak.

## Existing devices

There's no automatic move. After updating, every device starts in a group of its own; join
the PC's group by scanning its live QR once (phones and tablets: Connect › Scan, or
Devices › Scan a code). The pairing PIN no longer lets anything in.

## As built

| Piece | Where |
|---|---|
| Group core | `pc_server/group.py`, `app/.../ecosystem/GroupCrypto.kt` (formats) and `Group.kt` (state, Keystore-protected keys) |
| Shared test vectors | `pc_server/make_group_vectors.py` → `group_vectors.json`, checked by `test_group.py` and `GroupCryptoTest.kt` (both run in CI) |
| PC accepts only member sessions | `server.py` `_is_authorized` / `_handle_group_preauth`; `secure.py` `handshake_v2` binds a session to a device |
| Phone handshake, no plain packets | `SecureTransport.kt` (`begin`/`onAck` v2, `wrap` returns null before the session) |
| Live QR | PC Connect page (`app_gui.py`, new code every 20 s); phone Devices › Add a device (`AndroidHub.joinCode`) |
| Join | `group_join` over UDP to the PC's port or a phone's hub port (`GroupJoin.kt`, `Group.joinOverUdp`) |
| Remove + rotation | PC Connect page or phone Devices › Remove; `group_update` spreads it; members that missed it catch up in the PC handshake |
| Recovery | Words offered once (PC dialog, phone Devices); backup on the Worker at `/g/:id`; restore on the phone (Devices › Restore with recovery words) |

**Not built yet:**
- Joining across networks (the join goes over Wi-Fi; the relay "join room" isn't there).
- Restoring from words on the PC (phones can).
- Bluetooth to the PC stays trusted without the handshake: Windows' Bluetooth pairing already
  proves the phone is yours.
- One PC per group: the PC's member id is `pc`.

## What changes in the code

| Area | Change |
|---|---|
| New: `ecosystem/Group.kt`, `pc_server/group.py` | Identity keys, roster (sign/verify, version), epoch keys, recovery words (BIP-39 list bundled), backup. |
| `Family.kt`, `ecosystem.family_key()` | Become thin wrappers over the group; then removed after migration. |
| `secure.py`, `SecureTransport.kt` | PIN-MAC handshake → identity handshake. |
| `AndroidHub.kt`, `PeerLinks.kt` | Family challenge and PIN pairing → identity handshake; PIN pairing removed. |
| `RelayLinks.kt` | Room and seal key from the epoch key; one-off `join-` rooms. |
| `pc_call` (PhoneCaller) | Signed with the PC's identity key instead of the family HMAC. |
| Pairing QR (`app_gui.pairing_payload`, Connect screen) | Static QR with PIN → live QR; PC Connect page and phone Devices › Add a device. |
| Worker | `PUT/GET /g/:id` backup records; join rooms reuse `/relay`. |
| UI | Devices › Your group (members, Remove, Add a device), Settings › Recovery words, first-run "Write these down". |

## Order of work

1. `group.py` + `Group.kt` with test vectors that both sides must pass (roster signing, handshake,
   sealed epoch key, BIP-39), like the existing crypto interop tests.
2. Live QR + join flow, PC and phone.
3. Identity handshake on every link; PIN and family challenge kept only for the migration window.
4. Removal + epoch rotation; relay room from the epoch.
5. Recovery words + Worker backup.
6. Migration from the family key; delete PIN and family key.

## Threats and answers

| Threat | Answer |
|---|---|
| Someone photographs the QR | Expires in 60 s, works once. |
| Someone on the same Wi-Fi watches traffic | Every link is encrypted per session; nothing reusable is sent. |
| A phone is lost or stolen | Remove it from any device: new epoch key it never gets, its keys stop being accepted. |
| The Cloudflare account is compromised | It holds a random id, sealed backups it can't open, and relayed ciphertext. |
| Every device lost | The 24 words. Without them the group is gone (same as Brave). |
| A removed device keeps trying | It keeps whatever it already had, but can't reach the group or read anything new. |
| A brute-force guess at a handshake | There's no PIN or short secret in it to guess. |
