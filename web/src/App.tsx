import { useCallback, useEffect, useRef, useState } from "react";

type Message = { role: "user" | "assistant"; content: string };

type Health = { ok: boolean; name: string; version: string; upstreamConfigured: boolean };

const SIM_MODEL = "altair-sim";

export default function App() {
  const [messages, setMessages] = useState<Message[]>([]);
  const [input, setInput] = useState("");
  const [busy, setBusy] = useState(false);
  const [health, setHealth] = useState<Health | null>(null);
  const listRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    fetch("/health")
      .then((r) => r.json())
      .then(setHealth)
      .catch(() => setHealth(null));
  }, []);

  useEffect(() => {
    listRef.current?.scrollTo({ top: listRef.current.scrollHeight });
  }, [messages]);

  const send = useCallback(async () => {
    const text = input.trim();
    if (!text || busy) return;
    const next: Message[] = [...messages, { role: "user", content: text }];
    setMessages(next);
    setInput("");
    setBusy(true);

    try {
      const res = await fetch("/v1/chat/completions", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          model: SIM_MODEL,
          stream: true,
          messages: next.map((m) => ({ role: m.role, content: m.content })),
        }),
      });
      if (!res.ok || !res.body) {
        const detail = await res.text().catch(() => "");
        throw new Error(detail.slice(0, 300) || `HTTP ${res.status}`);
      }

      setMessages([...next, { role: "assistant", content: "" }]);
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
            const delta: string | undefined = chunk?.choices?.[0]?.delta?.content;
            if (delta) {
              acc += delta;
              setMessages([...next, { role: "assistant", content: acc }]);
            }
          } catch {
            /* partial line — keep buffering */
          }
        }
      }
      if (!acc) setMessages([...next, { role: "assistant", content: "*(empty response)*" }]);
    } catch (err: any) {
      setMessages([...next, { role: "assistant", content: `⚠️ ${err?.message ?? String(err)}` }]);
    } finally {
      setBusy(false);
    }
  }, [input, busy, messages]);

  const onKey = (e: React.KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === "Enter" && !e.shiftKey) {
      e.preventDefault();
      void send();
    }
  };

  return (
    <div className="app">
      <header className="topbar">
        <div className="brand">
          <span className="logo">✦</span> Altair
          <span className="ver">{health ? `engine v${health.version}` : "engine offline"}</span>
        </div>
        <div className={`badge ${health ? (health.upstreamConfigured ? "ok" : "sim") : "off"}`}>
          {health ? (health.upstreamConfigured ? "sim + cloud" : "sim model") : "disconnected"}
        </div>
      </header>

      <main className="chat" ref={listRef}>
        {messages.length === 0 && (
          <div className="empty">
            <h1>✦ Altair</h1>
            <p>Local-first AI hub — light, efficient, powerful.</p>
            <p className="hint">Phase 0 skeleton: this chat runs on the built-in sim model over the real SSE pipeline.</p>
          </div>
        )}
        {messages.map((m, i) => (
          <div key={i} className={`msg ${m.role}`}>
            <div className="bubble">{m.content}</div>
          </div>
        ))}
        {busy && <div className="typing">● ● ●</div>}
      </main>

      <footer className="composer">
        <textarea
          value={input}
          placeholder="Message Altair…  (Enter to send, Shift+Enter for newline)"
          onChange={(e) => setInput(e.target.value)}
          onKeyDown={onKey}
          rows={Math.min(6, input.split("\n").length)}
        />
        <button onClick={() => void send()} disabled={busy || !input.trim()}>
          {busy ? "…" : "➤"}
        </button>
      </footer>
    </div>
  );
}
