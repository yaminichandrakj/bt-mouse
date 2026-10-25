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
