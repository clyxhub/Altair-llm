import { useEffect, useState } from "react";
import { bridge, fmtBytes, hasBridge } from "../bridge";
import { getEndpoint } from "../api";
import { loadChats } from "../store";
import type { Section } from "../App";

type Props = { onMenu: () => void; go: (s: Section) => void };

export default function Settings({ onMenu, go }: Props) {
  const [endpoint, setEndpointState] = useState(getEndpoint());
  const [device, setDevice] = useState<ReturnType<typeof bridge.deviceInfo>>(null);
  const [status, setStatus] = useState<ReturnType<typeof bridge.serverStatus>>(null);
  const onDevice = hasBridge();

  useEffect(() => {
    if (!onDevice) return;
    const tick = () => {
      setDevice(bridge.deviceInfo());
      setStatus(bridge.serverStatus());
    };
    tick();
    const t = setInterval(tick, 2000);
    return () => clearInterval(t);
  }, [onDevice]);

  const chats = loadChats();

  return (
    <div className="section-body">
      <header className="appbar">
        <button className="iconbtn" onClick={onMenu} title="Menu">
          ☰
        </button>
        <div className="appbar-title">Settings</div>
      </header>
      <div className="list">
        <section className="card">
          <div className="label-row">Engine</div>
          <SettingsRow
            label="Endpoint"
            subtitle={endpoint}
            onClick={() => go("models")}
          />
          {onDevice && status && (
            <SettingsRow
              label="Local server"
              subtitle={`${status.state} :${status.port}${status.model ? " · " + status.model.split("/").pop() : ""}`}
              onClick={() => go("models")}
            />
          )}
        </section>

        <section className="card">
          <div className="label-row">Inference</div>
          {onDevice && device && (
            <>
              <SettingsRow label="Device" subtitle={device.device} />
              <SettingsRow
                label="Memory"
                subtitle={`${fmtBytes(device.totalMem)} total · ${fmtBytes(device.freeMem)} free · ${device.abi}`}
              />
              <SettingsRow label="Suggested context" subtitle={`${device.suggestCtx} tokens (auto, from RAM)`} />
              <SettingsRow label="GPU offload" subtitle="Auto — all layers via Vulkan, CPU fallback built-in" />
            </>
          )}
          {!onDevice && <p className="hint">Device info is available in the Android app.</p>}
        </section>

        <section className="card">
          <div className="label-row">Data</div>
          <SettingsRow label="Chats" subtitle={`${chats.length} stored locally`} onClick={() => go("chats")} />
          <SettingsRow label="Export / Import" subtitle="JSON backup of all chats" onClick={() => go("chats")} />
        </section>

        <section className="card">
          <div className="label-row">About</div>
          <p className="hint">
            ✦ Altair — local-first AI hub. GGUF on-device (Vulkan/CPU) + cloud BYOK, MCP, multi-agent mesh
            inference and the agentic web land in later phases. Light · efficient · powerful.
          </p>
        </section>
      </div>
    </div>
  );
}

function SettingsRow({ label, subtitle, onClick }: { label: string; subtitle?: string; onClick?: () => void }) {
  return (
    <div className={`setrow ${onClick ? "clickable" : ""}`} onClick={onClick}>
      <div>
        <div className="setlabel">{label}</div>
        {subtitle && <div className="setsub">{subtitle}</div>}
      </div>
      {onClick && <span className="chev">›</span>}
    </div>
  );
}
