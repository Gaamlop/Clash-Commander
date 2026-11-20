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
const json = (value, status, extraHeaders = {}) =>
  new Response(JSON.stringify(value), { status, headers: { ...responseHeaders, ...extraHeaders } });

// Forward only a bounded delay, never arbitrary provider headers or response text.
function retryHeaders(upstream) {
  if (![429, 503].includes(upstream.status)) return {};
  const value = upstream.headers.get("retry-after")?.trim();
  if (!value) return {};
  const seconds = /^\d{1,9}$/.test(value)
    ? Number(value) : (Date.parse(value) - Date.now()) / 1000;
  return Number.isFinite(seconds)
    ? { "retry-after": String(Math.ceil(Math.max(0, Math.min(86400, seconds)))) } : {};
}

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

const isObject = (value) => value !== null && typeof value === "object"
  && !Array.isArray(value);

function validContent(content) {
  return isObject(content) && Array.isArray(content.parts)
    && content.parts.length > 0 && content.parts.every((part) => {
      if (!isObject(part)) return false;
      const camelData = Object.hasOwn(part, "inlineData");
      const snakeData = Object.hasOwn(part, "inline_data");
      if (typeof part.text === "string") return !camelData && !snakeData;
      // APK 1.3 sends the protobuf snake_case aliases. Keep both spellings
      // compatible with Gemini, but reject ambiguous duplicate fields.
      if (camelData === snakeData || Object.hasOwn(part, "text")) return false;
      const data = camelData ? part.inlineData : part.inline_data;
      if (!isObject(data)) return false;
      const camelMime = Object.hasOwn(data, "mimeType");
      const snakeMime = Object.hasOwn(data, "mime_type");
      if (camelMime === snakeMime) return false;
      const mimeType = camelMime ? data.mimeType : data.mime_type;
      return typeof mimeType === "string"
        && /^image\/(jpeg|png|webp)$/.test(mimeType)
        && typeof data.data === "string"
        && data.data.length > 0;
    });
}

function boundedRequest(body) {
  if (!isObject(body) || !Array.isArray(body.contents)
      || body.contents.length < 1 || !body.contents.every(validContent)
      || (body.systemInstruction !== undefined && !validContent(body.systemInstruction))) {
    return null;
  }
  if (body.generationConfig !== undefined && !isObject(body.generationConfig)) return null;
  const generationConfig = { ...body.generationConfig };
  const maxTokens = generationConfig.maxOutputTokens === undefined
    ? 2048 : generationConfig.maxOutputTokens;
  if (!Number.isInteger(maxTokens) || maxTokens < 1 || maxTokens > 8192
      || (generationConfig.candidateCount !== undefined && generationConfig.candidateCount !== 1)) {
    return null;
  }
  generationConfig.maxOutputTokens = maxTokens;
  generationConfig.candidateCount = 1;
  return { ...body, generationConfig };
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
    const model = env.GEMINI_MODEL || "gemini-3-flash-preview";
    if (typeof model !== "string" || !/^[a-zA-Z0-9_.-]+$/.test(model)) {
      return json({ error: "Server model misconfigured" }, 503);
    }
    if (pathname === "/health") {
      // Pairing checks do not incur a generation or prove provider availability.
      return request.method === "GET"
        ? json({ ok: true, status: "paired", providerVerified: false }, 200)
        : json({ error: "Method not allowed" }, 405);
    }
    if (request.method !== "POST") {
      return json({ error: "Method not allowed" }, 405);
    }
    if ((request.headers.get("content-type") || "").split(";")[0].trim().toLowerCase()
        !== "application/json") {
      return json({ error: "Expected JSON" }, 415);
    }

    // Keep proxy narrow and inexpensive: no arbitrary upstream URLs or models.
    const maxBytes = 2_000_000;
    if (Number(request.headers.get("content-length") || "0") > maxBytes) {
      return json({ error: "Request too large" }, 413);
    }
    let input;
    try {
      input = await request.text();
    } catch {
      return json({ error: "Unable to read request" }, 400);
    }
    if (new TextEncoder().encode(input).length > maxBytes) {
      return json({ error: "Request too large" }, 413);
    }
    let body;
    try {
      body = JSON.parse(input);
    } catch {
      return json({ error: "Malformed JSON" }, 400);
    }
    const bounded = boundedRequest(body);
    if (!bounded) {
      return json({ error: "Invalid Gemini request" }, 400);
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
        body: JSON.stringify(bounded),
        // Cloudflare Workers supports only "follow" and "manual".
        // Manual also prevents forwarding the Google API key to a redirect host.
        redirect: "manual",
      });
      if (upstream.status >= 300 && upstream.status < 400) {
        return json({ error: "Unexpected Gemini redirect" }, 502);
      }
      if (!upstream.ok) {
        // Provider error payloads can contain sensitive request details. Keep
        // the HTTP status for retry handling without returning or logging them.
        return json({ error: "Gemini provider request failed", providerStatus: upstream.status },
          upstream.status, retryHeaders(upstream));
      }
      return new Response(upstream.body, {
        status: upstream.status,
        headers: responseHeaders,
      });
    } catch {
      return json({ error: "Gemini upstream unavailable" }, 502);
    }
  },
};
