import { useCallback, useEffect, useState } from "react";
import { bridge, fitGrade, fmtBytes, type HfFile, type HfRepo } from "../bridge";
import type { Section } from "../App";

type Props = { onMenu: () => void; go: (s: Section) => void };

// module-level so search results survive navigating away (Hermes lesson #12)
let persistedQuery = "";
let persistedResults: HfRepo[] = [];

export default function Hf({ onMenu, go }: Props) {
  const [device, setDevice] = useState<ReturnType<typeof bridge.deviceInfo>>(null);
  const [query, setQuery] = useState(persistedQuery);
  const [results, setResults] = useState<HfRepo[]>(persistedResults);
  const [openRepo, setOpenRepo] = useState<{ repo: HfRepo; files: HfFile[] } | null>(null);
  const [downloads, setDownloads] = useState<{ id: string; file: string; status: string; downloaded: number; total: number; error?: string | null }[]>([]);
  const [err, setErr] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const refresh = useCallback(() => {
    setDevice(bridge.deviceInfo());
    setDownloads(bridge.downloadsStatus());
  }, []);

  useEffect(() => {
    refresh();
    const t = setInterval(refresh, 1500);
    return () => clearInterval(t);
  }, [refresh]);

  const search = async () => {
    setBusy(true);
    setErr(null);
    try {
      const r = await bridge.hfSearch(query);
      setResults(r);
      persistedQuery = query;
      persistedResults = r;
    } catch (e: any) {
      setErr(e?.message ?? "search failed");
    } finally {
      setBusy(false);
    }
  };

  const openFiles = async (repo: HfRepo) => {
    setBusy(true);
    try {
      setOpenRepo({ repo, files: await bridge.hfTree(repo.repoId) });
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="section-body">
      <header className="appbar">
        <button className="iconbtn" onClick={onMenu} title="Menu">
          ☰
        </button>
        <div className="appbar-title">HuggingFace</div>
        <button className="iconbtn" onClick={() => go("models")} title="Model library">
          📦
        </button>
      </header>
      <div className="list">
        <section className="card">
          <div className="label-row">Search GGUF models</div>
          <div className="row">
            <input
              value={query}
              placeholder='e.g. "qwen3 4b gguf"'
              onChange={(e) => setQuery(e.target.value)}
              onKeyDown={(e) => e.key === "Enter" && void search()}
              spellCheck={false}
            />
            <button disabled={busy || !query.trim()} onClick={() => void search()}>
              Search
            </button>
          </div>
          {err && <p className="hint err">{err}</p>}
          <div className="hflist">
            {results.map((r) => (
              <div className="hrow" key={r.repoId} onClick={() => void openFiles(r)}>
                <div>
                  <div className="mname">{r.repoId}</div>
                  <div className="msub">
                    ↓ {r.downloads.toLocaleString()} · ★ {r.likes}
                  </div>
                </div>
                <span className="chev">›</span>
              </div>
            ))}
          </div>
        </section>

        {openRepo && (
          <section className="card">
            <div className="row">
              <div className="label-row" style={{ flex: 1 }}>
                {openRepo.repo.repoId}
              </div>
              <button className="ghost" onClick={() => setOpenRepo(null)}>
                ✕
              </button>
            </div>
            {openRepo.files.map((f) => {
              const grade = fitGrade(f.size, device);
              const name = f.path.split("/").pop() ?? f.path;
              const isMmproj = /mmproj/i.test(name);
              return (
                <div className="frow" key={f.path}>
                  <div className="minfo">
                    <div className="mname">{name}</div>
                    <div className="msub">
                      {fmtBytes(f.size)}
                      {isMmproj ? " · projector" : ""}
                    </div>
                  </div>
                  <span className={`dot ${grade.grade}`} title={grade.why} />
                  <button
                    onClick={() => {
                      bridge.startDownload(openRepo.repo.repoId, f.path, f.size);
                      setTimeout(refresh, 500);
                    }}
                  >
                    Get
                  </button>
                </div>
              );
            })}
          </section>
        )}

        {downloads.length > 0 && (
          <section className="card">
            <div className="label-row">Downloads</div>
            {downloads.map((d) => {
              const pct = d.total > 0 ? Math.min(100, (d.downloaded / d.total) * 100) : 0;
              return (
                <div className="drow" key={d.id}>
                  <div className="dinfo">
                    <div className="dname">{d.file}</div>
                    <div className="msub">
                      {fmtBytes(d.downloaded)} / {fmtBytes(d.total)} ({pct.toFixed(0)}%)
                    </div>
                    <div className="bar">
                      <div className="fill" style={{ width: `${pct}%` }} />
                    </div>
                  </div>
                  {d.status === "downloading" && (
                    <button className="ghost" onClick={() => bridge.pauseDownload(d.id)}>
                      ⏸
                    </button>
                  )}
                  {d.status === "paused" && (
                    <button className="ghost" onClick={() => bridge.resumeDownload(d.id)}>
                      ▶
                    </button>
                  )}
                </div>
              );
            })}
            <p className="hint">Downloads land in the app's model library — see Models.</p>
          </section>
        )}
      </div>
    </div>
  );
}
