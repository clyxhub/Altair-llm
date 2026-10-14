import { useCallback, useEffect, useRef, useState } from "react";
import { listModels, streamChat, type ModelEntry } from "../api";
import { loadChats, saveChats, setActiveId, getActiveId, newChat, sorted, type Chat, type ChatMsg } from "../store";
import type { Section } from "../App";

type Props = { onMenu: () => void; go: (s: Section) => void };

// built via concat so tooling never strips the literal tags
const THINK_OPEN = "<" + "think>";
const THINK_CLOSE = "<" + "/think>";
const THINK_RE = new RegExp(THINK_OPEN + "([\\s\\S]*?)" + THINK_CLOSE, "g");

/** Split reasoning traces (Qwen3-style blocks) from the answer. */
function splitThink(content: string, keep: boolean): { think: string | null; answer: string } {
  if (!content.includes(THINK_OPEN)) return { think: null, answer: content };
  let think = "";
  let rest = content;
  THINK_RE.lastIndex = 0;
  let m: RegExpExecArray | null;
  while ((m = THINK_RE.exec(content))) {
    think += m[1];
  }
  if (keep) {
    rest = content.replace(new RegExp(THINK_OPEN + "\\s*" + THINK_CLOSE, "g"), "");
    // keep the trace inline but visually separable — handled by renderer below
  } else {
    rest = content.replace(THINK_RE, "").trimStart();
  }
  return { think: think.trim() || null, answer: rest };
}

export default function Chat({ onMenu, go }: Props) {
  const [chats, setChats] = useState<Chat[]>(() => loadChats());
  const [activeId, setActive] = useState<string | null>(() => getActiveId());
  const [input, setInput] = useState("");
  const [busy, setBusy] = useState(false);
  const [thinking, setThinking] = useState(false);
  const [models, setModels] = useState<ModelEntry[]>([]);
  const [model, setModel] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [showTrace, setShowTrace] = useState<Record<number, boolean>>({});
  const listRef = useRef<HTMLDivElement>(null);
  const abortRef = useRef<AbortController | null>(null);

  const active = chats.find((c) => c.id === activeId) ?? null;
  const messages: ChatMsg[] = active?.messages ?? [];

  const persist = useCallback((updated: Chat[]) => {
    setChats(updated);
    saveChats(updated);
  }, []);

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

  const updateActive = useCallback(
    (fn: (c: Chat) => Chat) => {
      const cur = loadChats();
      const idx = cur.findIndex((c) => c.id === activeId);
      if (idx === -1) return;
      cur[idx] = fn(cur[idx]);
      persist(cur);
    },
    [activeId, persist]
  );

  const startNewChat = useCallback(() => {
    const c = newChat();
    const all = loadChats();
    persist([c, ...all]);
    setActive(c.id);
    setActiveId(c.id);
  }, [persist]);

  const send = useCallback(
    async (retryMessages?: ChatMsg[]) => {
      const base = retryMessages ?? [...messages, { role: "user" as const, content: input.trim() }];
      if (busy) return;
      if (!retryMessages && !input.trim()) return;
      if (!model) {
        setError("No model loaded — open Models and start one.");
        return;
      }

      // /no_think enforcement when thinking mode is off (Hermes rule)
      const outbound = base.map((m, i) => {
        const isLastUser = i === base.length - 1 && m.role === "user";
        return isLastUser && !thinking ? { role: m.role, content: m.content + " /no_think" } : m;
      });

      let id = activeId;
      if (!id) {
        const c = newChat();
        c.messages = base;
        c.title = base[0]?.content.slice(0, 40) || "New chat";
        persist([c, ...loadChats()]);
        id = c.id;
        setActive(c.id);
        setActiveId(c.id);
      } else {
        updateActive((c) => ({
          ...c,
          messages: base,
          title: c.title === "New chat" && base[0] ? base[0].content.slice(0, 40) : c.title,
        }));
      }

      setInput("");
      setBusy(true);
      setError(null);
      setShowTrace({});

      const ctrl = new AbortController();
      abortRef.current = ctrl;

      try {
        let acc = "";
        await streamChat(
          model,
          outbound.map((m) => ({ role: m.role, content: m.content })),
          (t) => {
            acc = t;
            // live-tick into the store copy for the current render
            updateActive((c) => ({ ...c, messages: [...base, { role: "assistant", content: t }] }));
          },
          undefined,
          ctrl.signal
        );
        if (!acc) {
          updateActive((c) => ({ ...c, messages: [...base, { role: "assistant", content: "*(empty response)*" }] }));
        }
      } catch (e: any) {
        if (e?.name === "AbortError") {
          // stopped — keep what streamed so far
        } else {
          setError(e?.message ?? String(e));
        }
      } finally {
        setBusy(false);
        abortRef.current = null;
      }
    },
    [input, busy, messages, model, thinking, activeId, persist, updateActive]
  );

  const stop = useCallback(() => {
    abortRef.current?.abort();
  }, []);

  const deleteFrom = useCallback(
    (i: number) => {
      updateActive((c) => ({ ...c, messages: c.messages.slice(0, i) }));
    },
    [updateActive]
  );

  const retryAt = useCallback(
    (i: number) => {
      // find the user message before this assistant reply and regenerate
      let u = i - 1;
      while (u >= 0 && messages[u].role !== "user") u--;
      if (u < 0) return;
      void send(messages.slice(0, u + 1));
    },
    [messages, send]
  );

  const onKey = (e: React.KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === "Enter" && !e.shiftKey) {
      e.preventDefault();
      void send();
    }
  };

  const hasModels = models.length > 0;

  return (
    <div className="section-body">
      <header className="appbar">
        <button className="iconbtn" onClick={onMenu} title="Menu">
          ☰
        </button>
        <div className="appbar-title">Chat</div>
        {busy ? (
          <button className="iconbtn stop" onClick={stop} title="Stop generating">
            ⏹
          </button>
        ) : (
          <button className="iconbtn" onClick={startNewChat} title="New chat">
            ✎
          </button>
        )}
      </header>

      {!hasModels && !error && (
        <div className="empty-state">
          <h2>No Models Available</h2>
          <p>Download a model to start chatting with Altair.</p>
          <button className="primary" onClick={() => go("models")}>
            Open Models
          </button>
        </div>
      )}

      {error && <div className="errorbar">⚠ {error}</div>}

      <main className="chat" ref={listRef}>
        {hasModels && messages.length === 0 && !error && (
          <div className="empty">
            <h1>✦ Altair</h1>
            <p>Local-first AI hub — light, efficient, powerful.</p>
            <p className="hint">Model: {model || "none"}</p>
          </div>
        )}
        {messages.map((m, i) => {
          const isAssistant = m.role === "assistant";
          const { think, answer } = isAssistant ? splitThink(m.content, thinking) : { think: null, answer: m.content };
          return (
            <div key={i} className={`msg ${m.role}`}>
              <div className="bubble">
                {think && thinking && (
                  <details className="trace" open={false}>
                    <summary>Reasoning</summary>
                    <div className="trace-body">{think}</div>
                  </details>
                )}
                {answer}
              </div>
              {isAssistant && !busy && (
                <div className="msgactions">
                  <button
                    onClick={() => {
                      navigator.clipboard?.writeText(m.content).catch(() => {});
                    }}
                    title="Copy"
                  >
                    ⧉
                  </button>
                  <button onClick={() => retryAt(i)} title="Regenerate">
                    ↺
                  </button>
                  <button onClick={() => deleteFrom(i)} title="Delete from here">
                    🗑
                  </button>
                </div>
              )}
            </div>
          );
        })}
        {busy && <div className="typing">● ● ●</div>}
      </main>

      <footer className="composer">
        <textarea
          value={input}
          placeholder={hasModels ? "Message input" : "No models available"}
          onChange={(e) => setInput(e.target.value)}
          onKeyDown={onKey}
          rows={Math.min(6, input.split("\n").length)}
          disabled={!hasModels}
        />
        <div className="toolrow">
          <button
            className={`chip ${thinking ? "on" : ""}`}
            onClick={() => setThinking(!thinking)}
            title="Show the model's reasoning trace (auto-appends nothing when off; /no_think enforced)"
          >
            🧠 Thinking {thinking ? "on" : "off"}
          </button>
          <div className="spacer" />
          <button
            className={`send ${busy || !input.trim() || !hasModels ? "disabled" : ""}`}
            onClick={() => void send()}
            disabled={busy || !input.trim() || !hasModels}
            title="Send"
          >
            ➤
          </button>
        </div>
      </footer>
    </div>
  );
}
