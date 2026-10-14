/**
 * Altair Engine — Phase 0 skeleton.
 *
 * OpenAI-compatible API server with SSE streaming:
 *   GET  /health                 → engine status
 *   GET  /v1/models              → model catalogue (built-in sim + optional upstream)
 *   POST /v1/chat/completions    → streaming chat (sim model; proxies to upstream
 *                                  when UPSTREAM_BASE_URL + UPSTREAM_API_KEY are set)
 *   GET  /*                      → serves the bundled web UI (../../web/dist)
 *
 * Local llama.cpp inference lands in Phase 1; this skeleton keeps the HTTP
 * surface, SSE plumbing and provider routing stable from day one.
 */
import http from "node:http";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const PORT = Number(process.env.PORT ?? 8787);
const HOST = process.env.HOST ?? "0.0.0.0";
const VERSION = "0.1.0";

const UPSTREAM_BASE = process.env.UPSTREAM_BASE_URL ?? "";
const UPSTREAM_KEY = process.env.UPSTREAM_API_KEY ?? "";

const SIM_MODEL = "altair-sim";

const MIME: Record<string, string> = {
  ".html": "text/html; charset=utf-8",
  ".js": "text/javascript; charset=utf-8",
  ".css": "text/css; charset=utf-8",
  ".json": "application/json",
  ".svg": "image/svg+xml",
  ".png": "image/png",
  ".ico": "image/x-icon",
  ".woff2": "font/woff2",
};

const WEB_DIST = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../../web/dist");

function json(res: http.ServerResponse, status: number, body: unknown) {
  const data = JSON.stringify(body);
  res.writeHead(status, {
    "Content-Type": "application/json",
    "Content-Length": Buffer.byteLength(data),
    "Access-Control-Allow-Origin": "*",
  });
  res.end(data);
}

function sseHead(res: http.ServerResponse) {
  res.writeHead(200, {
    "Content-Type": "text/event-stream",
    "Cache-Control": "no-cache",
    Connection: "keep-alive",
    "Access-Control-Allow-Origin": "*",
  });
}

function sseChunk(res: http.ServerResponse, model: string, delta: { content?: string; reasoning?: string }, id: string) {
  const payload = {
    id,
    object: "chat.completion.chunk",
    created: Math.floor(Date.now() / 1000),
    model,
    choices: [{ index: 0, delta, finish_reason: null }],
  };
  res.write(`data: ${JSON.stringify(payload)}\n\n`);
}

/** Built-in simulation model — proves the full streaming path end to end. */
async function simulate(req: http.IncomingMessage, res: http.ServerResponse, body: any) {
  const id = `chatcmpl-sim-${Date.now().toString(36)}`;
  const messages: Array<{ role: string; content: string }> = body?.messages ?? [];
  const last = messages.filter((m) => m.role === "user").at(-1)?.content ?? "";

  sseHead(res);
  sseChunk(res, SIM_MODEL, { reasoning: "" }, id);

  const reply =
    `**Altair Engine ${VERSION}** (simulated model — llama.cpp lands in Phase 1)\n\n` +
    `You said: ${JSON.stringify(last)}\n\n` +
    "The HTTP surface, SSE streaming and provider routing you just exercised are the real " +
    "production plumbing — local GGUF inference will plug in behind this exact API.\n";

  // Stream word-by-word for a realistic cadence.
  const tokens = reply.match(/\S+\s*/g) ?? [reply];
  for (const t of tokens) {
    sseChunk(res, SIM_MODEL, { content: t }, id);
    await new Promise((r) => setTimeout(r, 15));
  }

  const done = {
    id,
    object: "chat.completion.chunk",
    created: Math.floor(Date.now() / 1000),
    model: SIM_MODEL,
    choices: [{ index: 0, delta: {}, finish_reason: "stop" }],
  };
  res.write(`data: ${JSON.stringify(done)}\n\n`);
  res.write("data: [DONE]\n\n");
  res.end();
}

/** Proxy a streaming chat request to a configured OpenAI-compatible upstream. */
async function proxyChat(req: http.IncomingMessage, res: http.ServerResponse, body: any) {
  try {
    const upstream = await fetch(`${UPSTREAM_BASE.replace(/\/$/, "")}/chat/completions`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Authorization: `Bearer ${UPSTREAM_KEY}`,
      },
      body: JSON.stringify(body),
    });

    if (!upstream.ok || !upstream.body) {
      const text = await upstream.text().catch(() => "");
      json(res, upstream.status || 502, {
        error: { message: `Upstream error ${upstream.status}`, detail: text.slice(0, 2000) },
      });
      return;
    }

    res.writeHead(upstream.status, {
      "Content-Type": upstream.headers.get("content-type") ?? "text/event-stream",
      "Cache-Control": "no-cache",
      "Access-Control-Allow-Origin": "*",
    });
    const reader = upstream.body.getReader();
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      res.write(Buffer.from(value));
    }
    res.end();
  } catch (err: any) {
    json(res, 502, { error: { message: `Upstream unreachable: ${err?.message ?? String(err)}` } });
  }
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url ?? "/", `http://${req.headers.host ?? "localhost"}`);
  const route = url.pathname;

  if (req.method === "OPTIONS") {
    res.writeHead(204, {
      "Access-Control-Allow-Origin": "*",
      "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
      "Access-Control-Allow-Headers": "Content-Type, Authorization",
    });
    return res.end();
  }

  if (route === "/health") {
    return json(res, 200, {
      ok: true,
      name: "altair-engine",
      version: VERSION,
      upstreamConfigured: Boolean(UPSTREAM_BASE && UPSTREAM_KEY),
    });
  }

  if (route === "/v1/models" && req.method === "GET") {
    const models: Array<Record<string, unknown>> = [
      { id: SIM_MODEL, object: "model", owned_by: "altair", altair: { kind: "sim", local: true } },
    ];
    if (UPSTREAM_BASE && UPSTREAM_KEY) {
      try {
        const r = await fetch(`${UPSTREAM_BASE.replace(/\/$/, "")}/models`, {
          headers: { Authorization: `Bearer ${UPSTREAM_KEY}` },
        });
        if (r.ok) {
          const data: any = await r.json();
          for (const m of data?.data ?? []) {
            models.push({ id: m.id, object: "model", owned_by: m.owned_by ?? "upstream", altair: { kind: "cloud" } });
          }
        }
      } catch {
        /* upstream catalogue unavailable — sim model still listed */
      }
    }
    return json(res, 200, { object: "list", data: models });
  }

  if (route === "/v1/chat/completions" && req.method === "POST") {
    let raw = "";
    for await (const chunk of req) raw += chunk;
    let body: any;
    try {
      body = JSON.parse(raw || "{}");
    } catch {
      return json(res, 400, { error: { message: "Invalid JSON body" } });
    }
    const model: string = body?.model ?? SIM_MODEL;
    if (model === SIM_MODEL || model.startsWith("altair-sim")) return simulate(req, res, body);
    if (UPSTREAM_BASE && UPSTREAM_KEY) return proxyChat(req, res, body);
    return json(res, 404, {
      error: { message: `Unknown model "${model}". No upstream provider configured (set UPSTREAM_BASE_URL + UPSTREAM_API_KEY).` },
    });
  }

  // Static web UI
  let file = route === "/" ? "/index.html" : route;
  const candidate = path.join(WEB_DIST, path.normalize(file).replace(/^(\.\.[/\\])+/, ""));
  if (candidate.startsWith(WEB_DIST) && fs.existsSync(candidate) && fs.statSync(candidate).isFile()) {
    const ext = path.extname(candidate).toLowerCase();
    res.writeHead(200, { "Content-Type": MIME[ext] ?? "application/octet-stream" });
    return fs.createReadStream(candidate).pipe(res);
  }
  // SPA fallback
  const index = path.join(WEB_DIST, "index.html");
  if (fs.existsSync(index)) {
    res.writeHead(200, { "Content-Type": "text/html; charset=utf-8" });
    return fs.createReadStream(index).pipe(res);
  }
  json(res, 200, { ok: true, name: "altair-engine", version: VERSION, hint: "web UI not bundled in this build" });
});

server.listen(PORT, HOST, () => {
  console.log(`[altair-engine] v${VERSION} listening on http://${HOST}:${PORT}`);
  console.log(`[altair-engine] upstream provider: ${UPSTREAM_BASE ? UPSTREAM_BASE : "not configured (sim model only)"}`);
});
