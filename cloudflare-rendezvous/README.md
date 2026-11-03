# BT Mouse — WAN rendezvous Worker

A ~90-line Cloudflare Worker that stores **one tiny signed record** per paired PC:
the current `wss://…trycloudflare.com` URL of its cloudflared quick tunnel. The PC
re-publishes the record whenever the tunnel URL changes; the phone reads it, verifies
the Ed25519 signature against the public key from the QR code, and connects.

Cloudflare only ever sees this address record (signed, not secret) — never your
mouse/keyboard/screen traffic, which rides the tunnel end to end.

## What it costs on your account
- **Workers requests:** one GET per phone connect + one PUT per tunnel (re)publish.
  Tiny against the free daily allowance; nothing like relaying the traffic itself.
- **KV:** two small keys per PC, both auto-expiring after 24 h.
- **Storage/bandwidth of your other projects:** untouched — this is its own Worker + KV.

## One-time deploy (you run this; it's on your account)

```bash
cd cloudflare-rendezvous
npm install -g wrangler        # if you don't have it
wrangler login                 # opens the browser; authorize
wrangler kv namespace create RENDEZVOUS
```

Copy the `id` it prints into `wrangler.toml` (replace `PASTE_KV_NAMESPACE_ID_HERE`), then:

```bash
wrangler deploy
```

It prints your Worker URL, e.g. `https://btmouse-rv.<your-subdomain>.workers.dev`.
Paste that URL into the companion's **Remote access** page once. That's the whole setup.

## Family relay (phones on different networks)

The same Worker also runs a tiny relay so your phones and tablets reach each other over
the internet with the PC off. It's a Durable Object (`RelayRoom`, declared in
`wrangler.toml`); `wrangler deploy` creates it. Durable Objects with SQLite storage are
included in the Workers free plan.

- One room per family. Its id is derived from the family key, so only your devices know it.
- The room only forwards. Every message is sealed end to end (ChaCha20-Poly1305) with a
  key derived from the family key; Cloudflare sees sizes and timing, never content.
- Cost: each forwarded message counts as a Worker request. Mirroring a screen over the
  relay sends many messages; everyday use (clipboard, files, control) is small.

Already deployed before v1.9.2? Run `wrangler deploy` once more to add the relay.

## Protocol (for reference)
- `PUT /r/:id` body `{data, sig, pub}` — `data` is the record JSON string, `sig` its
  Ed25519 signature (base64), `pub` the signer's 32-byte public key (base64). The Worker
  verifies the signature, binds `:id` to the first key it sees (trust-on-first-use), and
  rejects later writes from a different key.
- `GET /r/:id` → `{data, sig}` or `404`.
- `data` = `{"v":1,"url":"wss://…","ts":<unix seconds>,"host":"<pc name>"}`. The phone
  rejects records older than 10 minutes so a stale URL is never used.
- `GET /relay/:room?d=:device` (WebSocket) joins a family relay room. Send
  `{"to": "<device id>" | "*", "d": <sealed>}`; receive `{"from", "d"}` and
  `{"type": "presence", "devices": [...]}`.
