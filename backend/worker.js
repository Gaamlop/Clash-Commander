/**
 * Personal Gemini relay for Clash Commander.
 * Deploy as a Cloudflare Worker. Set GEMINI_API_KEY and APP_ACCESS_TOKEN
 * as Cloudflare *secrets*, never in source code or GitHub.
 */
const responseHeaders = {
  "content-type": "application/json; charset=utf-8",
  "cache-control": "no-store",
  "x-content-type-options": "nosniff",
};
const json = (value, status) =>
  new Response(JSON.stringify(value), { status, headers: responseHeaders });

function secureEqual(a, b) {
  if (!a || !b) return false;
  const left = new TextEncoder().encode(a);
  const right = new TextEncoder().encode(b);
  if (left.length !== right.length) return false;
  let difference = 0;
  for (let index = 0; index < left.length; index++) {
    difference |= left[index] ^ right[index];
  }
  return difference === 0;
}

export default {
  async fetch(request, env) {
    const pathname = new URL(request.url).pathname;
    if (pathname !== "/health" && pathname !== "/v1/gemini") {
      return json({ error: "Not found" }, 404);
    }
    if (!env.APP_ACCESS_TOKEN || env.APP_ACCESS_TOKEN.length < 32) {
      return json({ error: "Server pairing token not configured" }, 503);
    }
    const authorization = request.headers.get("Authorization") || "";
    if (!secureEqual(authorization, "Bearer " + env.APP_ACCESS_TOKEN)) {
      return json({ error: "Unauthorized" }, 401);
    }
    if (!env.GEMINI_API_KEY) {
      return json({ error: "Gemini provider not configured" }, 503);
    }
    if (pathname === "/health") {
      return request.method === "GET" ? json({ ok: true }, 200)
        : json({ error: "Method not allowed" }, 405);
    }
    if (request.method !== "POST") {
      return json({ error: "Method not allowed" }, 405);
    }
    if (!(request.headers.get("content-type") || "").includes("application/json")) {
      return json({ error: "Expected JSON" }, 415);
    }

    // Keep proxy narrow and inexpensive: no arbitrary upstream URLs or models.
    const maxBytes = 2_000_000;
    if (Number(request.headers.get("content-length") || "0") > maxBytes) {
      return json({ error: "Request too large" }, 413);
    }
    const input = await request.text();
    if (new TextEncoder().encode(input).length > maxBytes) {
      return json({ error: "Request too large" }, 413);
    }
    let body;
    try {
      body = JSON.parse(input);
    } catch {
      return json({ error: "Malformed JSON" }, 400);
    }
    if (!body || !Array.isArray(body.contents) || body.contents.length < 1
        || body.generationConfig?.maxOutputTokens > 8192) {
      return json({ error: "Invalid Gemini request" }, 400);
    }

    const model = env.GEMINI_MODEL || "gemini-3-flash-preview";
    if (!/^[a-zA-Z0-9_.-]+$/.test(model)) {
      return json({ error: "Server model misconfigured" }, 503);
    }
    const upstreamUrl =
      "https://generativelanguage.googleapis.com/v1beta/models/" +
      model + ":generateContent";
    try {
      const upstream = await fetch(upstreamUrl, {
        method: "POST",
        headers: {
          "content-type": "application/json",
          "x-goog-api-key": env.GEMINI_API_KEY,
        },
        body: input,
        redirect: "error",
      });
      return new Response(upstream.body, {
        status: upstream.status,
        headers: responseHeaders,
      });
    } catch {
      return json({ error: "Gemini upstream unavailable" }, 502);
    }
  },
};
