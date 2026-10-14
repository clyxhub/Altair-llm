/** Typed wrapper for the Android JS bridge (absent on desktop). */

export type DeviceInfo = { totalMem: number; freeMem: number; abi: string; device: string; suggestCtx: number };
export type LocalModel = { id: string; path: string; size: number; mmprojPath?: string; mmprojName?: string };
export type HfRepo = { repoId: string; downloads: number; likes: number };
export type HfFile = { path: string; size: number };
export type Download = { id: string; file: string; url: string; status: string; downloaded: number; total: number; error?: string | null };
export type ServerStatus = { state: string; port: number; model: string | null; alive: boolean; error: string | null; log: string[] };

function raw(): any | null {
  return (window as any).AndroidBridge ?? null;
}

export function hasBridge(): boolean {
  return raw() !== null;
}

function parse<T>(fn: () => string): T {
  return JSON.parse(fn());
}

export const bridge = {
  deviceInfo: (): DeviceInfo | null => tryOrNull(() => parse<DeviceInfo>(() => raw().deviceInfo())),
  importModel: (): void => raw()?.importModel(),
  listLocalModels: (): LocalModel[] => (raw() ? parse<LocalModel[]>(() => raw().listLocalModels()) : []),
  hfSearch: async (query: string): Promise<HfRepo[]> => {
    const r = raw();
    if (!r) return [];
    const data = JSON.parse(r.hfSearch(query) as string);
    if (data?.error) throw new Error(data.error);
    return data;
  },
  hfTree: async (repoId: string): Promise<HfFile[]> => {
    const r = raw();
    if (!r) return [];
    const data = JSON.parse(r.hfTree(repoId) as string);
    if (data?.error) throw new Error(data.error);
    return data;
  },
  startDownload: (repoId: string, path: string, size: number): void => {
    raw()?.startDownload(repoId, path, size);
  },
  downloadsStatus: (): Download[] => (raw() ? parse<Download[]>(() => raw().downloadsStatus()) : []),
  pauseDownload: (id: string) => raw()?.pauseDownload(id),
  resumeDownload: (id: string) => raw()?.resumeDownload(id),
  cancelDownload: (id: string) => raw()?.cancelDownload(id),
  deleteModel: (name: string) => raw()?.deleteModel(name),
  startServer: (cfg: {
    modelPath: string;
    mmprojPath?: string;
    port?: number;
    ctx?: number;
    threads?: number;
    ngl?: number;
  }): { ok: boolean; error?: string } => {
    const r = raw();
    if (!r) return { ok: false, error: "no bridge" };
    return JSON.parse(r.startServer(JSON.stringify(cfg)) as string);
  },
  stopServer: () => raw()?.stopServer(),
  serverStatus: (): ServerStatus | null => tryOrNull(() => parse<ServerStatus>(() => raw().serverStatus())),
};

function tryOrNull<T>(fn: () => T): T | null {
  try {
    return fn();
  } catch {
    return null;
  }
}

/** GREEN/YELLOW/RED hardware-fit grade — advisory only, never blocks. */
export function fitGrade(sizeBytes: number, info: DeviceInfo | null): { grade: "green" | "yellow" | "red"; why: string } {
  if (!info) return { grade: "yellow", why: "device RAM unknown" };
  const est = sizeBytes * 1.15 + 512 * 1024 * 1024; // weights + runtime overhead
  const total = info.totalMem;
  if (est < total * 0.4) return { grade: "green", why: `${fmtBytes(est)} est. vs ${fmtBytes(total)} RAM — comfortable` };
  if (est < total * 0.6) return { grade: "yellow", why: `${fmtBytes(est)} est. vs ${fmtBytes(total)} RAM — tight, use smaller ctx` };
  return { grade: "red", why: `${fmtBytes(est)} est. vs ${fmtBytes(total)} RAM — likely won't fit` };
}

export function fmtBytes(n: number): string {
  if (!n) return "0 B";
  const u = ["B", "KB", "MB", "GB", "TB"];
  const i = Math.min(u.length - 1, Math.floor(Math.log(n) / Math.log(1024)));
  return `${(n / 1024 ** i).toFixed(i ? 1 : 0)} ${u[i]}`;
}
