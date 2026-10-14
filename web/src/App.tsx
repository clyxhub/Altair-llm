import { useEffect, useState } from "react";
import Chat from "./components/Chat";
import Models from "./components/Models";

type Tab = "chat" | "models";

export default function App() {
  const [tab, setTab] = useState<Tab>("chat");

  return (
    <div className="app">
      <header className="topbar">
        <div className="brand">
          <span className="logo">✦</span> Altair
        </div>
        <nav className="tabs">
          <button className={tab === "chat" ? "active" : ""} onClick={() => setTab("chat")}>
            Chat
          </button>
          <button className={tab === "models" ? "active" : ""} onClick={() => setTab("models")}>
            Models
          </button>
        </nav>
      </header>
      {tab === "chat" ? <Chat /> : <Models />}
    </div>
  );
}
