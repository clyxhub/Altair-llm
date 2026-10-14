/** Multi-chat persistence (localStorage; engine-side sync lands later). */

export type ChatMsg = { role: "user" | "assistant"; content: string };
export type Chat = {
  id: string;
  title: string;
  messages: ChatMsg[];
  pinned: boolean;
  created: number;
};

const K = "altair.chats.v1";
const AK = "altair.chat.active.v1";

export function loadChats(): Chat[] {
  try {
    const raw = localStorage.getItem(K);
    if (!raw) return [];
    const parsed = JSON.parse(raw);
    return Array.isArray(parsed) ? parsed : [];
  } catch {
    return [];
  }
}

export function saveChats(chats: Chat[]) {
  try {
    localStorage.setItem(K, JSON.stringify(chats));
  } catch {}
}

export function newChat(): Chat {
  return { id: `c${Date.now().toString(36)}`, title: "New chat", messages: [], pinned: false, created: Date.now() };
}

export function getActiveId(): string | null {
  return localStorage.getItem(AK);
}

export function setActiveId(id: string) {
  localStorage.setItem(AK, id);
}

/** All chats sorted: pinned first, then newest. */
export function sorted(chats: Chat[]): Chat[] {
  return [...chats].sort((a, b) => Number(b.pinned) - Number(a.pinned) || b.created - a.created);
}

export function exportChats(): string {
  return JSON.stringify(loadChats(), null, 2);
}

export function importChats(json: string): number {
  const parsed = JSON.parse(json);
  if (!Array.isArray(parsed)) throw new Error("not a chat export");
  const existing = loadChats();
  const ids = new Set(existing.map((c) => c.id));
  let added = 0;
  for (const c of parsed) {
    if (c && typeof c.id === "string" && Array.isArray(c.messages) && !ids.has(c.id)) {
      existing.push({
        id: c.id,
        title: String(c.title ?? "Imported chat"),
        messages: c.messages.filter((m: any) => m && (m.role === "user" || m.role === "assistant") && typeof m.content === "string"),
        pinned: Boolean(c.pinned),
        created: Number(c.created) || Date.now(),
      });
      added++;
    }
  }
  saveChats(existing);
  return added;
}
