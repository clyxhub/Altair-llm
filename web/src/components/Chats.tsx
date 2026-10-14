import { useCallback, useState } from "react";
import { loadChats, saveChats, sorted, newChat, setActiveId, importChats, exportChats, type Chat } from "../store";
import type { Section } from "../App";

type Props = { onMenu: () => void; go: (s: Section) => void };

export default function Chats({ onMenu, go }: Props) {
  const [chats, setChats] = useState<Chat[]>(() => sorted(loadChats()));

  const commit = (list: Chat[]) => {
    saveChats(list);
    setChats(sorted(list));
  };

  const open = (id: string) => {
    setActiveId(id);
    go("chat");
  };

  const del = (id: string) => {
    if (!confirm("Delete this chat?")) return;
    commit(loadChats().filter((c) => c.id !== id));
  };

  const rename = (id: string) => {
    const c = loadChats().find((x) => x.id === id);
    const t = prompt("Rename chat", c?.title ?? "");
    if (t === null || !t.trim()) return;
    commit(loadChats().map((x) => (x.id === id ? { ...x, title: t.trim() } : x)));
  };

  const pin = (id: string) => commit(loadChats().map((x) => (x.id === id ? { ...x, pinned: !x.pinned } : x)));

  const doExport = () => {
    const blob = new Blob([exportChats()], { type: "application/json" });
    const a = document.createElement("a");
    a.href = URL.createObjectURL(blob);
    a.download = `altair-chats-${new Date().toISOString().slice(0, 10)}.json`;
    a.click();
    URL.revokeObjectURL(a.href);
  };

  const doImport = () => {
    const inp = document.createElement("input");
    inp.type = "file";
    inp.accept = "application/json";
    inp.onchange = async () => {
      const f = inp.files?.[0];
      if (!f) return;
      try {
        const n = importChats(await f.text());
        setChats(sorted(loadChats()));
        alert(`Imported ${n} chat(s)`);
      } catch (e: any) {
        alert(`Import failed: ${e?.message ?? e}`);
      }
    };
    inp.click();
  };

  return (
    <div className="section-body">
      <header className="appbar">
        <button className="iconbtn" onClick={onMenu} title="Menu">
          ☰
        </button>
        <div className="appbar-title">Chats</div>
        <button className="iconbtn" onClick={() => commit([newChat(), ...loadChats()])} title="New chat">
          ✎
        </button>
      </header>
      <div className="list">
        {chats.length === 0 && <p className="hint pad">No chats yet — start one from the Chat section.</p>}
        {chats.map((c) => (
          <div className="mrow" key={c.id}>
            <div className="minfo clickable" onClick={() => open(c.id)}>
              <div className="mname">
                {c.pinned ? "📌 " : ""}
                {c.title}
              </div>
              <div className="msub">
                {c.messages.length} messages · {new Date(c.created).toLocaleDateString()}
              </div>
            </div>
            <button className="ghost" onClick={() => pin(c.id)} title="Pin/unpin">
              📌
            </button>
            <button className="ghost" onClick={() => rename(c.id)} title="Rename">
              ✎
            </button>
            <button className="ghost danger" onClick={() => del(c.id)} title="Delete">
              🗑
            </button>
          </div>
        ))}
        {chats.length > 0 && (
          <div className="row pad">
            <button className="ghost" onClick={doExport}>
              Export all
            </button>
            <button className="ghost" onClick={doImport}>
              Import
            </button>
          </div>
        )}
      </div>
    </div>
  );
}
