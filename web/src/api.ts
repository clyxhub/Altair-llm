/** Connection + OpenAI-compatible API layer. Endpoint is user-configurable. */
import { hasBridge } from "./bridge";

const ENDPOINT_KEY = "altair.endpoint";
export const SIM_MODEL = "altair-sim";

export function defaultEndpoint(): string {
  const saved = localStorage.getItem(ENDPOINT_KEY);
  if (saved) return saved;
  // On-device (bridge present): local llama-server. Desktop: same-origin engine.
  return hasBridge() ? "http://127.0.0.1:8077/v1" : "/v1";
}

export function getEndpoint(): string {
  return localStorage.getItem(ENDPOINT_KEY) ?? defaultEndpoint();
}

export function setEndpoint(url: string) {
  localStorage.setItem(ENDPOINT_KEY, url.trim());
  localStorage.removeItem(ENDPOINT_KEY + ".models");
}

export type ModelEntry = { id: string; kind?: string; owned_by?: string };

export async function listModels(endpoint = getEndpoint()): Promise<ModelEntry[]> {
  const res = await fetch(`${endpoint.replace(/\/$/, "")}/models`);
  if (!res.ok) throw new Error(`models: HTTP ${res.status}`);
  const data = await res.json();
  return (data?.data ?? []).map((m: any) => ({
    id: m.id,
    kind: m?.altair?.kind,
    owned_by: m.owned_by,
  }));
}

export type ChatMessage = { role: string; content: string };

/** Stream a chat completion; returns the full assistant text. */
export async function streamChat(
  model: string,
  messages: ChatMessage[],
  onDelta: (text: string) => void,
  endpoint = getEndpoint()
): Promise<string> {
  const res = await fetch(`${endpoint.replace(/\/$/, "")}/chat/completions`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ model, stream: true, messages }),
  });
  if (!res.ok || !res.body) {
    const detail = await res.text().catch(() => "");
    throw new Error(prettyHttpError(res.status, detail));
  }

  const reader = res.body.getReader();
  const decoder = new TextDecoder();
  let buf = "";
  let acc = "";

  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    buf += decoder.decode(value, { stream: true });
    const lines = buf.split("\n");
    buf = lines.pop() ?? "";
    for (const line of lines) {
      const trimmed = line.trim();
      if (!trimmed.startsWith("data:")) continue;
      const payload = trimmed.slice(5).trim();
      if (payload === "[DONE]") continue;
      try {
        const chunk = JSON.parse(payload);
        if (chunk?.error) throw new Error(chunk.error?.message ?? "server error");
        const delta: string | undefined = chunk?.choices?.[0]?.delta?.content;
        if (delta) {
          acc += delta;
          onDelta(acc);
        }
      } catch (e: any) {
        if (e?.message && !e?.message?.includes("JSON")) throw e;
      }
    }
  }
  return acc;
}

function prettyHttpError(status: number, detail: string): string {
  try {
    const j = JSON.parse(detail);
    if (j?.error?.message) return j.error.message;
  } catch {}
  if (status === 0 || detail === "") return "Cannot reach the engine — is the local server running?";
  return `HTTP ${status}: ${detail.slice(0, 200)}`;
}
