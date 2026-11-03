// BT Mouse — WAN rendezvous Worker.
//
// Holds one tiny signed record per paired PC: the current wss:// tunnel URL.
// The PC PUTs a fresh record whenever its cloudflared quick-tunnel URL changes;
// the phone GETs it, verifies the Ed25519 signature with the public key it got
// from the QR code, and connects. Cloudflare never sees the control traffic —
// only this address record passes through, and it is signed, not secret.
//
// Routes:
//   PUT /r/:id   body {data, sig, pub}  -> store if signature valid
//   GET /r/:id                          -> {data, sig} or 404
//   GET /relay/:room?d=:device  (WebSocket upgrade) -> join a family relay room
//   PUT /g/:id   body {blob, version, pub, sig}  -> store a device group's sealed backup
//   GET /g/:id                                   -> {blob, version} or 404
//
// The relay lets one person's phones and tablets reach each other over the internet
// when they are on different networks and no PC is around. Each family has one room
// (its id is derived from the family key, so only family members can name it). The
// room only forwards: every message is sealed end to end with a key derived from the
// family key, so Cloudflare sees who talks to whom and how much, never what.
//
// Auth model: trust-on-first-use. The first PUT for an id binds that id to the
// public key in `pub`; later PUTs must carry the same key AND a valid signature,
// so someone who learns the id but not the private key cannot overwrite it.

const RECORD_TTL_SECONDS = 86400; // KV entry self-expires after a day of silence
const MAX_BODY_BYTES = 2048;

function json(status, obj) {
  return new Response(JSON.stringify(obj), {
    status,
    headers: { "content-type": "application/json", "cache-control": "no-store" },
  });
}

function b64ToBytes(b64) {
  const bin = atob(b64);
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}

// Record ids are PC-generated 32-char hex; reject anything else so KV keys stay bounded.
function validId(id) {
  return typeof id === "string" && /^[a-f0-9]{16,64}$/.test(id);
}

async function verifyEd25519(pubBytes, sigBytes, dataBytes) {
  try {
    const key = await crypto.subtle.importKey(
      "raw",
      pubBytes,
      { name: "Ed25519" },
      false,
      ["verify"]
    );
    return await crypto.subtle.verify({ name: "Ed25519" }, key, sigBytes, dataBytes);
  } catch (_e) {
    return false;
  }
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const parts = url.pathname.split("/").filter(Boolean); // ["r", ":id"]

    if (parts.length === 2 && parts[0] === "g") {
      return groupBackup(request, env, parts[1]);
    }

    if (parts.length === 2 && parts[0] === "relay") {
      const room = parts[1];
      const device = url.searchParams.get("d") || "";
      if (!/^[a-f0-9]{32,64}$/.test(room) || !/^[A-Za-z0-9_-]{4,64}$/.test(device)) {
        return json(404, { error: "not_found" });
      }
      if (request.headers.get("Upgrade") !== "websocket") {
        return json(426, { error: "websocket_required" });
      }
      if (!env.RELAY) return json(501, { error: "relay_not_deployed" });
      const stub = env.RELAY.get(env.RELAY.idFromName(room));
      return stub.fetch(request);
    }

    if (parts.length !== 2 || parts[0] !== "r" || !validId(parts[1])) {
      return json(404, { error: "not_found" });
    }
    const id = parts[1];
    const kv = env.RENDEZVOUS; // KV namespace binding (see wrangler.toml)

    if (request.method === "GET") {
      const stored = await kv.get(`rec:${id}`);
      if (!stored) return json(404, { error: "no_record" });
      return new Response(stored, {
        status: 200,
        headers: { "content-type": "application/json", "cache-control": "no-store" },
      });
    }

    if (request.method === "PUT") {
      const raw = await request.text();
      if (raw.length > MAX_BODY_BYTES) return json(413, { error: "too_large" });

      let body;
      try {
        body = JSON.parse(raw);
      } catch (_e) {
        return json(400, { error: "bad_json" });
      }
      const { data, sig, pub } = body || {};
      if (typeof data !== "string" || typeof sig !== "string" || typeof pub !== "string") {
        return json(400, { error: "missing_fields" });
      }

      let pubBytes, sigBytes, dataBytes;
      try {
        pubBytes = b64ToBytes(pub);
        sigBytes = b64ToBytes(sig);
        dataBytes = new TextEncoder().encode(data);
      } catch (_e) {
        return json(400, { error: "bad_base64" });
      }
      if (pubBytes.length !== 32) return json(400, { error: "bad_pub" });

      const ok = await verifyEd25519(pubBytes, sigBytes, dataBytes);
      if (!ok) return json(403, { error: "bad_signature" });

      // Trust-on-first-use: bind this id to the first key that signs for it.
      const boundPub = await kv.get(`pub:${id}`);
      if (boundPub && boundPub !== pub) {
        return json(403, { error: "id_bound_to_other_key" });
      }
      if (!boundPub) {
        await kv.put(`pub:${id}`, pub, { expirationTtl: RECORD_TTL_SECONDS });
      }

      await kv.put(`rec:${id}`, JSON.stringify({ data, sig }), {
        expirationTtl: RECORD_TTL_SECONDS,
      });
      return json(200, { ok: true });
    }

    return json(405, { error: "method_not_allowed" });
  },
};

// --- Device group backups --------------------------------------------------------
//
// The group's roster and current key, sealed to the recovery key that only the 24
// recovery words can open. Members upload it after every change; the Worker can't read
// it. Writes are signed with the group's backup key, bound on first use like the
// rendezvous records, so only a member can replace it, and never with an older version.
const MAX_BACKUP_BYTES = 64 * 1024;

async function groupBackup(request, env, id) {
  if (!/^[a-f0-9]{32}$/.test(id)) return json(404, { error: "not_found" });
  const kv = env.RENDEZVOUS;
  if (request.method === "GET") {
    const stored = await kv.get(`grp:${id}`);
    if (!stored) return json(404, { error: "no_backup" });
    const { blob, version } = JSON.parse(stored);
    return json(200, { blob, version });
  }
  if (request.method !== "PUT") return json(405, { error: "method_not_allowed" });
  const raw = await request.text();
  if (raw.length > MAX_BACKUP_BYTES) return json(413, { error: "too_large" });
  let body;
  try {
    body = JSON.parse(raw);
  } catch (_e) {
    return json(400, { error: "bad_json" });
  }
  const { blob, version, pub, sig } = body || {};
  if (typeof blob !== "string" || !Number.isInteger(version) || typeof pub !== "string" || typeof sig !== "string") {
    return json(400, { error: "missing_fields" });
  }
  let pubBytes, sigBytes;
  try {
    pubBytes = b64ToBytes(pub);
    sigBytes = b64ToBytes(sig);
  } catch (_e) {
    return json(400, { error: "bad_base64" });
  }
  if (pubBytes.length !== 32) return json(400, { error: "bad_pub" });
  const signed = new TextEncoder().encode(`${id}|${version}|${blob}`);
  if (!(await verifyEd25519(pubBytes, sigBytes, signed))) return json(403, { error: "bad_signature" });
  const boundPub = await kv.get(`grppub:${id}`);
  if (boundPub && boundPub !== pub) return json(403, { error: "id_bound_to_other_key" });
  const previous = await kv.get(`grp:${id}`);
  if (previous && JSON.parse(previous).version > version) return json(409, { error: "older_than_stored" });
  if (!boundPub) await kv.put(`grppub:${id}`, pub);
  // Backups don't expire: they're what the recovery words restore from.
  await kv.put(`grp:${id}`, JSON.stringify({ blob, version }));
  return json(200, { ok: true });
}

// --- Family relay room ---------------------------------------------------------
//
// One Durable Object per family. Devices connect with a WebSocket; the room tells
// everyone who is present and forwards each message to the device named in "to"
// (or to everyone for "*"), stamping the sender's id in "from". It uses the
// hibernation API, so an idle room costs nothing while sockets stay open.
//
// Client -> room:  {"to": "<device id>" | "*", "d": <opaque sealed payload>}
// Room -> client:  {"from": "<device id>", "d": <same payload>}
//                  {"type": "presence", "devices": ["<device id>", ...]}

const MAX_RELAY_MESSAGE = 1024 * 1024; // the Workers WebSocket frame limit
const MAX_ROOM_DEVICES = 16;

export class RelayRoom {
  constructor(ctx, env) {
    this.ctx = ctx;
    this.env = env;
  }

  async fetch(request) {
    const device = new URL(request.url).searchParams.get("d");
    const sockets = this.ctx.getWebSockets();
    if (sockets.length >= MAX_ROOM_DEVICES) return json(429, { error: "room_full" });
    // A device that reconnects replaces its old socket.
    for (const old of this.ctx.getWebSockets(device)) {
      try { old.close(4000, "replaced"); } catch (_e) {}
    }
    const pair = new WebSocketPair();
    const [client, server] = Object.values(pair);
    this.ctx.acceptWebSocket(server, [device]);
    this.ctx.setWebSocketAutoResponse(new WebSocketRequestResponsePair("ping", "pong"));
    this.broadcastPresence(server);
    return new Response(null, { status: 101, webSocket: client });
  }

  deviceOf(ws) {
    return this.ctx.getTags(ws)[0] || "";
  }

  present(except) {
    const ids = new Set();
    for (const ws of this.ctx.getWebSockets()) {
      if (ws !== except && ws.readyState === 1) ids.add(this.deviceOf(ws));
    }
    return [...ids];
  }

  broadcastPresence(joined, leaving) {
    const msg = JSON.stringify({ type: "presence", devices: this.present(leaving) });
    for (const ws of this.ctx.getWebSockets()) {
      if (ws === leaving) continue;
      try { ws.send(msg); } catch (_e) {}
    }
  }

  async webSocketMessage(ws, message) {
    const size = typeof message === "string" ? message.length : message.byteLength;
    if (size > MAX_RELAY_MESSAGE) return;
    let packet;
    try {
      packet = JSON.parse(typeof message === "string" ? message : new TextDecoder().decode(message));
    } catch (_e) {
      return;
    }
    if (!packet || typeof packet.to !== "string" || packet.d === undefined) return;
    const from = this.deviceOf(ws);
    const out = JSON.stringify({ from, d: packet.d });
    const targets = packet.to === "*"
      ? this.ctx.getWebSockets().filter((s) => s !== ws)
      : this.ctx.getWebSockets(packet.to);
    for (const target of targets) {
      try { target.send(out); } catch (_e) {}
    }
  }

  async webSocketClose(ws, code, _reason, _wasClean) {
    try { ws.close(code === 1005 ? 1000 : code, "bye"); } catch (_e) {}
    this.broadcastPresence(null, ws);
  }

  async webSocketError(ws, _error) {
    this.broadcastPresence(null, ws);
  }
}
