import { useCallback, useEffect, useState } from "react";
import { bridge, fitGrade, fmtBytes, hasBridge, type LocalModel } from "../bridge";
import { getEndpoint, setEndpoint } from "../api";
import type { Section } from "../App";

type Props = { onMenu: () => void; go: (s: Section) => void };

type Download = { id: string; file: string; status: string; downloaded: number; total: number; error?: string | null };

export default function Models({ onMenu, go }: Props) {
  const onDevice = hasBridge();
  return (
    <div className="section-body">
      <header className="appbar">
        <button className="iconbtn" onClick={onMenu} title="Menu">
          ☰
        </button>
        <div className="appbar-title">Models</div>
        <button className="iconbtn" onClick={() => go("hf")} title="Find more models">
          ＋
        </button>
      </header>
      <div className="list">
        <ConnectionBox />
        {onDevice ? <DeviceModels /> : <DesktopNotice />}
      </div>
    </div>
  );
}

function ConnectionBox() {
  const [value, setValue] = useState(getEndpoint());
  const saved = value !== getEndpoint();
  return (
    <section className="card">
      <div className="label-row">Connection</div>
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
      <p className="hint">Chat runs against this endpoint. On-device default is the local engine (127.0.0.1:8077).</p>
    </section>
  );
}

function DesktopNotice() {
  return (
    <section className="card">
      <div className="label-row">On-device models</div>
      <p className="hint">
        GGUF management is available in the Android app. On desktop, run llama-server or any OpenAI-compatible
        server and point Connection at it.
      </p>
    </section>
  );
}

function DeviceModels() {
  const [device, setDevice] = useState<ReturnType<typeof bridge.deviceInfo>>(null);
  const [local, setLocal] = useState<LocalModel[]>([]);
  const [downloads, setDownloads] = useState<Download[]>([]);
  const [status, setStatus] = useState<ReturnType<typeof bridge.serverStatus>>(null);

  const refresh = useCallback(async () => {
    setDevice(bridge.deviceInfo());
    setLocal(bridge.listLocalModels());
    setStatus(bridge.serverStatus());
    setDownloads(bridge.downloadsStatus());
  }, []);

  useEffect(() => {
    void refresh();
    const t = setInterval(refresh, 1500);
    (window as any).__altair = { onImportDone: () => setTimeout(refresh, 400) };
    return () => {
      clearInterval(t);
      delete (window as any).__altair;
    };
  }, [refresh]);

  const running = status?.state === "running" || status?.state === "starting";

  return (
    <>
      <section className="card">
        <div className="label-row">Local engine</div>
        <div className="row">
          <span className={`badge ${running ? "ok" : "off"}`}>{status ? `${status.state} :${status.port}` : "unknown"}</span>
          {running ? (
            <button className="danger" onClick={() => bridge.stopServer()}>
              Stop
            </button>
          ) : (
            <span className="hint">pick a model below and press Start</span>
          )}
        </div>
        {status?.error && <p className="hint err">{status.error}</p>}
        {status?.log && status.log.length > 0 && <pre className="logbox">{status.log.slice(-6).join("\n")}</pre>}
      </section>

      <section className="card">
        <div className="label-row">On this device</div>
        <div className="row" style={{ marginBottom: 8 }}>
          <button onClick={() => bridge.importModel()}>+ Add from device</button>
          <span className="hint">pick a .gguf (model or mmproj) already on this phone</span>
        </div>
        {local.length === 0 && <p className="hint">Nothing here yet — import files or open HuggingFace to download.</p>}
        {local.map((m) => {
          const grade = fitGrade(m.size, device);
          const isLoaded = status?.model === m.path && running;
          return (
            <div className="mrow" key={m.id}>
              <div className="minfo clickable" onClick={() => !isLoaded && running === false && bridge.startServer({ modelPath: m.path, mmprojPath: m.mmprojPath, ctx: device?.suggestCtx })}>
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
                <button onClick={() => bridge.startServer({ modelPath: m.path, mmprojPath: m.mmprojPath, ctx: device?.suggestCtx })}>Start</button>
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
          <div className="label-row">Downloads</div>
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
    </>
  );
}
