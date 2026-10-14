import { useEffect, useState } from "react";
import Chat from "./components/Chat";
import Chats from "./components/Chats";
import Models from "./components/Models";
import Hf from "./components/Hf";
import Providers from "./components/Providers";
import Settings from "./components/Settings";

export type Section = "chat" | "chats" | "models" | "hf" | "providers" | "settings";

const SECTIONS: Array<{ id: Section; label: string; icon: string }> = [
  { id: "chat", label: "Chat", icon: "💬" },
  { id: "chats", label: "Chats", icon: "🕘" },
  { id: "models", label: "Models", icon: "📦" },
  { id: "hf", label: "HuggingFace", icon: "⬇️" },
  { id: "providers", label: "Providers", icon: "🔑" },
  { id: "settings", label: "Settings", icon: "⚙️" },
];

const TITLES: Record<Section, string> = {
  chat: "Chat",
  chats: "Chats",
  models: "Models",
  hf: "HuggingFace",
  providers: "Providers",
  settings: "Settings",
};

export default function App() {
  const [section, setSection] = useState<Section>("chat");
  const [drawer, setDrawer] = useState(false);
  const go = (s: Section) => {
    setSection(s);
    setDrawer(false);
  };

  // close the drawer on system back-ish gesture space / escape
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === "Escape" && setDrawer(false);
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, []);

  const openMenu = () => setDrawer(true);

  return (
    <div className="app">
      {drawer && <div className="drawer-overlay" onClick={() => setDrawer(false)} />}
      <aside className={`drawer ${drawer ? "open" : ""}`}>
        <div className="drawer-head">
          <span className="logo">✦</span> Altair
        </div>
        {SECTIONS.map((s) => (
          <button
            key={s.id}
            className={`drawer-item ${section === s.id ? "active" : ""}`}
            onClick={() => go(s.id)}
          >
            <span className="dicon">{s.icon}</span>
            {s.label}
          </button>
        ))}
        <div className="drawer-foot">light · efficient · powerful</div>
      </aside>

      <div className="section">
        {section === "chat" && <Chat onMenu={openMenu} go={go} />}
        {section === "chats" && <Chats onMenu={openMenu} go={go} />}
        {section === "models" && <Models onMenu={openMenu} go={go} />}
        {section === "hf" && <Hf onMenu={openMenu} go={go} />}
        {section === "providers" && <Providers onMenu={openMenu} />}
        {section === "settings" && <Settings onMenu={openMenu} go={go} />}
      </div>
      <span className="hidden-title" aria-hidden>
        {TITLES[section]}
      </span>
    </div>
  );
}
