import { useCallback, useEffect, useState } from "react";
import { bridge, fitGrade, fmtBytes, hasBridge, type HfFile, type HfRepo, type LocalModel } from "../bridge";
import { getEndpoint, setEndpoint } from "../api";

type Download = { id: string; file: string; status: string; downloaded: number; total: number; error?: string | null };

export default function Models() {
  const onDevice = hasBridge();
  return (
    <div className="models">
      <ConnectionBox />
      {onDevice ? <DeviceModels /> : <DesktopNotice />}
    </div>
  );
}

function ConnectionBox() {
  const [value, setValue] = useState(getEndpoint());
  const saved = value !== getEndpoint();
  return (
    <section className="card">
      <h3>Connection</h3>
      <div className="row">
        <input
          value={value}
          onChange={(e) => setValue(e.target.value)}
          placeholder="OpenAI-compatible endpoint, e.g. http://192.168.1.10:8077/v1"
          spellCheck={false}
        />
        <button disabled={!saved} onClick={() => setEndpoint(value)}>
          Save
        </button>
      </div>
      <p className="hint">
        Chat runs against this endpoint. On-device default is the local engine (127.0.0.1:8077);
        point it at your PC over LAN to use bigger models.
      </p>
    </section>
  );
}

function DesktopNotice() {
  return (
    <section className="card">
      <h3>Models</h3>
      <p className="hint">
        HuggingFace browsing and on-device GGUF management are available in the Android app.
        On desktop, run llama-server / any OpenAI-compatible server and point Connection at it.
      </p>
    </section>
  );
}

function DeviceModels() {
  const [device, setDevice] = useState<ReturnType<typeof bridge.deviceInfo>>(null);
  const [local, setLocal] = useState<LocalModel[]>([]);
  const [query, setQuery] = useState("");
  const [results, setResults] = useState<HfRepo[]>([]);
  const [openRepo, setOpenRepo] = useState<{ repo: HfRepo; files: HfFile[] } | null>(null);
  const [downloads, setDownloads] = useState<Download[]>([]);
  const [status, setStatus] = useState<ReturnType<typeof bridge.serverStatus>>(null);
  const [searchErr, setSearchErr] = useState<string | null>(null);
  const [busyRepo, setBusyRepo] = useState(false);

  const refresh = useCallback(async () => {
    setDevice(bridge.deviceInfo());
    setLocal(bridge.listLocalModels());
    setStatus(bridge.serverStatus());
    setDownloads(bridge.downloadsStatus());
  }, []);

  useEffect(() => {
    void refresh();
    const t = setInterval(refresh, 1500);
    // SAF import callback from the Android file picker
    (window as any).__altair = {
      onImportDone: () => {
        setTimeout(refresh, 400);
      },
    };
    return () => {
      clearInterval(t);
      delete (window as any).__altair;
    };
  }, [refresh]);

  const search = async () => {
    setBusyRepo(true);
    setSearchErr(null);
    try {
      setResults(await bridge.hfSearch(query));
    } catch (e: any) {
      setSearchErr(e?.message ?? "search failed");
    } finally {
      setBusyRepo(false);
    }
  };

  const openFiles = async (repo: HfRepo) => {
    setBusyRepo(true);
    try {
      setOpenRepo({ repo, files: await bridge.hfTree(repo.repoId) });
    } finally {
      setBusyRepo(false);
    }
  };

  const running = status?.state === "running" || status?.state === "starting";

  return (
    <>
      <section className="card">
        <h3>Local engine</h3>
        <div className="row">
          <span className={`badge ${running ? "ok" : "off"}`}>
            {status ? `${status.state} :${status.port}` : "unknown"}
          </span>
          {running ? (
            <button className="danger" onClick={() => bridge.stopServer()}>
              Stop
            </button>
          ) : (
            <span className="hint">pick a model below and press Start</span>
          )}
        </div>
        {status?.error && <p className="hint err">{status.error}</p>}
        {status?.log && status.log.length > 0 && (
          <pre className="logbox">{status.log.slice(-6).join("\n")}</pre>
        )}
      </section>

      <section className="card">
        <h3>Model library</h3>
        <div className="row" style={{ marginBottom: 8 }}>
          <button onClick={() => bridge.importModel()}>+ Add from device</button>
          <span className="hint">pick a .gguf (model or mmproj) already on this phone</span>
        </div>
        {local.length === 0 && <p className="hint">No models yet — add from device or download from HuggingFace below.</p>}
        {local.map((m) => {
          const grade = fitGrade(m.size, device);
          const isLoaded = status?.model === m.path && running;
          return (
            <div className="mrow" key={m.id}>
              <div className="minfo">
                <div className="mname">{m.id}</div>
                <div className="msub">
                  {fmtBytes(m.size)}
                  {m.mmprojName ? ` · vision: ${m.mmprojName}` : ""}
                  {` · ${device ? device.device : ""}`}
                </div>
              </div>
              <span className={`dot ${grade.grade}`} title={grade.why} />
              {isLoaded ? (
                <span className="badge ok">loaded</span>
              ) : (
                <button
                  onClick={() =>
                    bridge.startServer({
                      modelPath: m.path,
                      mmprojPath: m.mmprojPath,
                      ctx: device?.suggestCtx,
                    })
                  }
                >
                  Start
                </button>
              )}
              <button
                className="ghost danger"
                title="Delete model"
                onClick={() => {
                  if (confirm(`Delete ${m.id}?`)) {
                    bridge.deleteModel(m.id + ".gguf");
                    setTimeout(refresh, 300);
                  }
                }}
              >
                🗑
              </button>
            </div>
          );
        })}
      </section>

      {downloads.length > 0 && (
        <section className="card">
          <h3>Downloads</h3>
          {downloads.map((d) => {
            const pct = d.total > 0 ? Math.min(100, (d.downloaded / d.total) * 100) : 0;
            return (
              <div className="drow" key={d.id}>
                <div className="dinfo">
                  <div className="dname">{d.file}</div>
                  <div className="msub">
                    {fmtBytes(d.downloaded)} / {fmtBytes(d.total)} ({pct.toFixed(0)}%)
                    {d.error ? ` — ${d.error}` : ""}
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
                {(d.status === "paused" || d.status === "error") && d.status !== "error" && (
                  <button className="ghost" onClick={() => bridge.resumeDownload(d.id)}>
                    ▶
                  </button>
                )}
                {d.status !== "done" && (
                  <button className="ghost danger" onClick={() => bridge.cancelDownload(d.id)}>
                    ✕
                  </button>
                )}
              </div>
            );
          })}
        </section>
      )}

      <section className="card">
        <h3>HuggingFace</h3>
        <div className="row">
          <input
            value={query}
            placeholder='Search GGUF models, e.g. "qwen3 4b gguf"'
            onChange={(e) => setQuery(e.target.value)}
            onKeyDown={(e) => e.key === "Enter" && void search()}
            spellCheck={false}
          />
          <button disabled={busyRepo || !query.trim()} onClick={() => void search()}>
            Search
          </button>
        </div>
        {searchErr && <p className="hint err">{searchErr}</p>}
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

        {openRepo && (
          <div className="files">
            <div className="row">
              <h4>{openRepo.repo.repoId}</h4>
              <button className="ghost" onClick={() => setOpenRepo(null)}>
                ✕
              </button>
            </div>
            {openRepo.files.map((f) => {
              const grade = fitGrade(f.size, device);
              const name = f.path.split("/").pop() ?? f.path;
              return (
                <div className="frow" key={f.path}>
                  <div className="minfo">
                    <div className="mname">{name}</div>
                    <div className="msub">{fmtBytes(f.size)}</div>
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
          </div>
        )}
      </section>
    </>
  );
}
