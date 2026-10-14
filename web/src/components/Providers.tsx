import { useState } from "react";

type Props = { onMenu: () => void };

type Provider = { name: string; baseUrl: string; key: string };

const PRESETS: Array<{ name: string; baseUrl: string }> = [
  { name: "OpenAI", baseUrl: "https://api.openai.com/v1" },
  { name: "OpenRouter", baseUrl: "https://openrouter.ai/api/v1" },
  { name: "Groq", baseUrl: "https://api.groq.com/openai/v1" },
  { name: "Mistral", baseUrl: "https://api.mistral.ai/v1" },
  { name: "DeepSeek", baseUrl: "https://api.deepseek.com/v1" },
  { name: "xAI", baseUrl: "https://api.x.ai/v1" },
  { name: "Custom (OpenAI-compatible)", baseUrl: "" },
];

const K = "altair.providers.v1";

function load(): Provider[] {
  try {
    return JSON.parse(localStorage.getItem(K) ?? "[]");
  } catch {
    return [];
  }
}
function save(list: Provider[]) {
  localStorage.setItem(K, JSON.stringify(list));
}

export default function Providers({ onMenu }: Props) {
  const [list, setList] = useState<Provider[]>(load);
  const [preset, setPreset] = useState(0);
  const [key, setKey] = useState("");

  const commit = (l: Provider[]) => {
    setList(l);
    save(l);
  };

  const add = () => {
    const p = PRESETS[preset];
    if (!key.trim() && !p.baseUrl) return;
    commit([...load(), { name: p.name, baseUrl: p.baseUrl, key: key.trim() }]);
    setKey("");
  };

  return (
    <div className="section-body">
      <header className="appbar">
        <button className="iconbtn" onClick={onMenu} title="Menu">
          ☰
        </button>
        <div className="appbar-title">Providers</div>
        <span className="appbar-count">{list.length}</span>
      </header>
      <div className="list">
        <section className="card">
          <div className="label-row">Add a provider key (BYOK)</div>
          <div className="row">
            <select value={preset} onChange={(e) => setPreset(Number(e.target.value))}>
              {PRESETS.map((p, i) => (
                <option key={p.name} value={i}>
                  {p.name}
                </option>
              ))}
            </select>
          </div>
          <div className="row" style={{ marginTop: 8 }}>
            <input
              type="password"
              value={key}
              placeholder="API key"
              onChange={(e) => setKey(e.target.value)}
              spellCheck={false}
              autoComplete="off"
            />
            <button onClick={add} disabled={!key.trim()}>
              Add
            </button>
          </div>
          <p className="hint">Keys are stored locally on this device only. Cloud model routing lands with the capability-router phase — local models work today.</p>
        </section>

        {list.map((p, i) => (
          <section className="card" key={i}>
            <div className="row">
              <div className="minfo">
                <div className="mname">{p.name}</div>
                <div className="msub">
                  {p.baseUrl || "custom base URL"} · key {p.key.slice(0, 6)}…{p.key.slice(-4)}
                </div>
              </div>
              <button className="ghost danger" onClick={() => commit(list.filter((_, j) => j !== i))}>
                🗑
              </button>
            </div>
          </section>
        ))}
      </div>
    </div>
  );
}
