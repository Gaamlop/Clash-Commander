import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

// Import the deployable Worker without requiring a package.json or dependencies.
const source = readFileSync(new URL("./worker.js", import.meta.url), "utf8");
const worker = (await import("data:text/javascript;base64," + Buffer.from(source).toString("base64"))).default;
const env = { APP_ACCESS_TOKEN: "local-test-only-".repeat(4), GEMINI_API_KEY: "local-provider-stub" };
const validBody = () => ({
  contents: [{ parts: [{ text: "Choose a card or wait." }] }],
  systemInstruction: { parts: [{ text: "Return JSON." }] },
  generationConfig: {
    maxOutputTokens: 4096, temperature: 0.1,
    thinkingConfig: { thinkingLevel: "minimal" }, responseMimeType: "application/json",
  },
});

function request({ path = "/v1/gemini", method = "POST", body = validBody(),
  authorization = true, contentType = "application/json", headers = {} } = {}) {
  if (authorization) headers = { ...headers, Authorization: "Bearer " + env.APP_ACCESS_TOKEN };
  if (contentType !== null) headers = { ...headers, "content-type": contentType };
  return new Request("https://worker.invalid" + path, {
    method, headers,
    ...(method === "GET" || method === "HEAD" ? {} : {
      body: typeof body === "string" ? body : JSON.stringify(body),
    }),
  });
}

function upstream(t, implementation = () => new Response('{"candidates":[]}', { status: 200 })) {
  const calls = [];
  t.mock.method(globalThis, "fetch", async (...args) => {
    calls.push(args);
    return implementation(...args);
  });
  return calls;
}

test("pairing health is local, authenticated, and explicitly not provider verification", async (t) => {
  const calls = upstream(t);
  const response = await worker.fetch(request({ path: "/health", method: "GET" }), env);
  assert.equal(response.status, 200);
  assert.deepEqual(await response.json(), { ok: true, status: "paired", providerVerified: false });
  assert.equal(calls.length, 0);
  assert.equal(response.headers.get("cache-control"), "no-store");
});

test("configuration, routes, methods and authentication reject before upstream", async (t) => {
  const calls = upstream(t);
  for (const [options, configuredEnv, expected] of [
    [{ path: "/unknown" }, env, 404],
    [{ authorization: false }, env, 401],
    [{ headers: { Authorization: "Bearer wrong" }, authorization: false }, env, 401],
    [{}, { ...env, APP_ACCESS_TOKEN: "short" }, 503],
    [{}, { APP_ACCESS_TOKEN: env.APP_ACCESS_TOKEN }, 503],
    [{ path: "/health", method: "GET" }, { ...env, GEMINI_MODEL: "invalid/model" }, 503],
    [{}, { ...env, GEMINI_MODEL: "invalid/model" }, 503],
    [{ path: "/health" }, env, 405],
    [{ method: "GET" }, env, 405],
    [{ contentType: "text/plain" }, env, 415],
    [{ contentType: "not-application/json" }, env, 415],
  ]) {
    assert.equal((await worker.fetch(request(options), configuredEnv)).status, expected);
  }
  assert.equal(calls.length, 0);
});

test("malformed JSON, unreadable bodies and oversized requests fail locally", async (t) => {
  const calls = upstream(t);
  assert.equal((await worker.fetch(request({ body: "{" }), env)).status, 400);
  assert.equal((await worker.fetch(request({ headers: { "content-length": "2000001" } }), env)).status, 413);
  assert.equal((await worker.fetch(request({ body: " ".repeat(2_000_001) }), env)).status, 413);
  const unreadable = request();
  await unreadable.text();
  assert.equal((await worker.fetch(unreadable, env)).status, 400);
  assert.equal(calls.length, 0);
});

test("request contract rejects malformed content and generation configuration", async (t) => {
  const calls = upstream(t);
  for (const body of [
    null, [], {}, { contents: [] }, { contents: [null] }, { contents: [{}] },
    { contents: [{ parts: [] }] }, { contents: [{ parts: [{ text: 42 }] }] },
    { contents: [{ parts: [{ inlineData: { mimeType: "text/plain", data: "eA==" } }] }] },
    { contents: [{ parts: [{ inlineData: { mimeType: "image/jpeg", data: "" } }] }] },
    { ...validBody(), systemInstruction: "bad shape" },
    { ...validBody(), generationConfig: null },
    { ...validBody(), generationConfig: [] },
  ]) {
    assert.equal((await worker.fetch(request({ body }), env)).status, 400);
  }
  assert.equal(calls.length, 0);
});

test("token budgets are positive integers and candidate count stays one", async (t) => {
  const calls = upstream(t);
  for (const maxOutputTokens of [null, -1, 0, 1.5, 8193, "8192", "invalid"]) {
    const body = { ...validBody(), generationConfig: { maxOutputTokens } };
    assert.equal((await worker.fetch(request({ body }), env)).status, 400);
  }
  for (const candidateCount of [null, -1, 0, 2, 8, "1"]) {
    const body = { ...validBody(), generationConfig: { maxOutputTokens: 8192, candidateCount } };
    assert.equal((await worker.fetch(request({ body }), env)).status, 400);
  }
  assert.equal(calls.length, 0);
});

test("missing generation budget receives a bounded server default", async (t) => {
  const calls = upstream(t);
  const body = validBody();
  delete body.generationConfig;
  assert.equal((await worker.fetch(request({ body }), env)).status, 200);
  assert.deepEqual(JSON.parse(calls[0][1].body).generationConfig,
    { maxOutputTokens: 2048, candidateCount: 1 });
});

test("text and screenshot requests preserve app settings and use only configured HTTPS upstream", async (t) => {
  const calls = upstream(t);
  const body = validBody();
  body.contents[0].parts.push({ inlineData: { mimeType: "image/jpeg", data: "eA==" } });
  const response = await worker.fetch(request({ body, contentType: "Application/JSON; charset=utf-8" }),
    { ...env, GEMINI_MODEL: "configured-flash-model" });
  assert.equal(response.status, 200);
  assert.deepEqual(await response.json(), { candidates: [] });
  assert.equal(calls.length, 1);
  assert.equal(calls[0][0], "https://generativelanguage.googleapis.com/v1beta/models/configured-flash-model:generateContent");
  assert.equal(calls[0][1].redirect, "manual");
  assert.equal(calls[0][1].method, "POST");
  const forwarded = JSON.parse(calls[0][1].body);
  assert.deepEqual(forwarded.contents, body.contents);
  assert.deepEqual(forwarded.systemInstruction, body.systemInstruction);
  assert.deepEqual(forwarded.generationConfig, { ...body.generationConfig, candidateCount: 1 });
});

test("APK 1.3 screenshot payload with snake_case fields remains compatible", async (t) => {
  const calls = upstream(t);
  // Match generateWithImages/buildRequestBody from the released 1.3 APK:
  // image parts first, then text; JSON/thinking settings remain unchanged.
  const body = {
    contents: [{ parts: [
      { inline_data: { mime_type: "image/jpeg", data: "eA==" } },
      { inline_data: { mime_type: "image/jpeg", data: "eQ==" } },
      { text: "Choose a card or wait." },
    ] }],
    systemInstruction: { parts: [{ text: "Return JSON." }] },
    generationConfig: {
      temperature: 0.1, maxOutputTokens: 1024,
      thinkingConfig: { thinkingLevel: "minimal" }, responseMimeType: "application/json",
    },
  };
  assert.equal((await worker.fetch(request({ body }), env)).status, 200);
  assert.equal(calls.length, 1);
  assert.deepEqual(JSON.parse(calls[0][1].body), {
    ...body, generationConfig: { ...body.generationConfig, candidateCount: 1 },
  });
});

test("ambiguous camelCase and snake_case image fields are rejected", async (t) => {
  const calls = upstream(t);
  const image = { mimeType: "image/jpeg", data: "eA==" };
  for (const part of [
    { inlineData: image, inline_data: { mime_type: "image/jpeg", data: "eA==" } },
    { inlineData: null, inline_data: { mime_type: "image/jpeg", data: "eA==" } },
    { inlineData: { ...image, mime_type: "image/jpeg" } },
    { inline_data: { ...image, mime_type: "image/jpeg" } },
    { text: "text and image in one part", inline_data: { mime_type: "image/jpeg", data: "eA==" } },
  ]) {
    const body = { ...validBody(), contents: [{ parts: [part] }] };
    assert.equal((await worker.fetch(request({ body }), env)).status, 400);
  }
  assert.equal(calls.length, 0);
});

test("provider redirects are rejected without returning upstream content", async (t) => {
  upstream(t, () => new Response("sensitive upstream detail", {
    status: 302, headers: { location: "https://untrusted.invalid/" },
  }));
  const response = await worker.fetch(request(), env);
  assert.equal(response.status, 502);
  assert.deepEqual(await response.json(), { error: "Unexpected Gemini redirect" });
});

test("provider failures retain HTTP codes without exposing bodies or logging secrets", async (t) => {
  let status = 400;
  upstream(t, () => new Response("sensitive upstream detail " + env.GEMINI_API_KEY, { status }));
  const errors = t.mock.method(console, "error", () => {});
  for (status of [400, 401, 403, 404, 429, 500, 503]) {
    const response = await worker.fetch(request(), env);
    assert.equal(response.status, status);
    assert.deepEqual(await response.json(), { error: "Gemini provider request failed", providerStatus: status });
  }
  assert.equal(errors.mock.callCount(), 0);
});

test("transport exceptions return a fixed error without logging exception details", async (t) => {
  upstream(t, () => { throw new Error("sensitive upstream detail " + env.GEMINI_API_KEY); });
  const errors = t.mock.method(console, "error", () => {});
  const response = await worker.fetch(request(), env);
  assert.equal(response.status, 502);
  assert.deepEqual(await response.json(), { error: "Gemini upstream unavailable" });
  assert.equal(errors.mock.callCount(), 0);
});
