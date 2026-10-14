import { useCallback, useEffect, useRef, useState } from "react";
import { listModels, streamChat, type ModelEntry } from "../api";

type Message = { role: "user" | "assistant"; content: string };

export default function Chat() {
  const [messages, setMessages] = useState<Message[]>([]);
  const [input, setInput] = useState("");
  const [busy, setBusy] = useState(false);
  const [models, setModels] = useState<ModelEntry[]>([]);
  const [model, setModel] = useState("");
  const [error, setError] = useState<string | null>(null);
  const listRef = useRef<HTMLDivElement>(null);

  const refreshModels = useCallback(async () => {
    try {
      const list = await listModels();
      setModels(list);
      setError(null);
      setModel((cur) => (cur && list.some((m) => m.id === cur) ? cur : (list[0]?.id ?? "")));
    } catch (e: any) {
      setError(e?.message ?? "cannot reach endpoint");
    }
  }, []);

  useEffect(() => {
    void refreshModels();
  }, [refreshModels]);

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
    setError(null);

    try {
      let acc = "";
      await streamChat(
        model,
        next.map((m) => ({ role: m.role, content: m.content })),
        (t) => {
          acc = t;
          setMessages([...next, { role: "assistant", content: t }]);
        }
      );
      if (!acc) setMessages([...next, { role: "assistant", content: "*(empty response)*" }]);
    } catch (e: any) {
      setError(e?.message ?? String(e));
      setMessages(next);
    } finally {
      setBusy(false);
    }
  }, [input, busy, messages, model]);

  const onKey = (e: React.KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === "Enter" && !e.shiftKey) {
      e.preventDefault();
      void send();
    }
  };

  return (
    <div className="chatwrap">
      <div className="chatbar">
        <select value={model} onChange={(e) => setModel(e.target.value)} title="Model">
          {models.length === 0 && <option value="">(no models — add one in Models)</option>}
          {models.map((m) => (
            <option key={m.id} value={m.id}>
              {m.id}
            </option>
          ))}
        </select>
        <button className="ghost" onClick={() => void refreshModels()} title="Refresh models">
          ↻
        </button>
        <button className="ghost" onClick={() => setMessages([])} title="Clear chat">
          ⌫
        </button>
      </div>
      {error && <div className="errorbar">⚠ {error}</div>}

      <main className="chat" ref={listRef}>
        {messages.length === 0 && !error && (
          <div className="empty">
            <h1>✦ Altair</h1>
            <p>Local-first AI hub — light, efficient, powerful.</p>
            <p className="hint">Pick a model above. Local GGUF runs entirely on-device via Vulkan.</p>
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
