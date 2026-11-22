import { apiUrl } from "@/config/brand";

export type ApiError = { status: number; code: string; message: string; fieldErrors?: Record<string, string> };
export type Page<T> = { items: T[]; page: number; size: number; total: number };

const TOKEN_KEY = "erp.token";

export const tokenStore = {
  get(): string | null {
    try { return localStorage.getItem(TOKEN_KEY); } catch { return null; }
  },
  set(t: string) { try { localStorage.setItem(TOKEN_KEY, t); } catch { /* ignore */ } },
  clear() { try { localStorage.removeItem(TOKEN_KEY); } catch { /* ignore */ } },
};

export function uuid(): string {
  if (typeof crypto !== "undefined" && "randomUUID" in crypto) return crypto.randomUUID();
  return "xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx".replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0;
    return (c === "x" ? r : (r & 0x3) | 0x8).toString(16);
  });
}

type Opts = { idem?: boolean | string; raw?: boolean; signal?: AbortSignal };

async function request<T>(method: string, path: string, body?: unknown, opts: Opts = {}): Promise<T> {
  const headers: Record<string, string> = { "Content-Type": "application/json" };
  const t = tokenStore.get();
  if (t) headers.Authorization = `Bearer ${t}`;
  if (opts.idem) headers["Idempotency-Key"] = typeof opts.idem === "string" ? opts.idem : uuid();
  let res: Response;
  try {
    res = await fetch(apiUrl + path, {
      method, headers, body: body === undefined ? undefined : JSON.stringify(body), signal: opts.signal,
    });
  } catch {
    throw { status: 0, code: "NETWORK", message: "Cannot reach server. Check your connection." } as ApiError;
  }
  if (res.status === 401) {
    tokenStore.clear();
    if (typeof window !== "undefined" && !location.pathname.startsWith("/login") && !location.pathname.startsWith("/signup")) {
      location.href = "/login";
    }
  }
  if (!res.ok) {
    let err: ApiError = { status: res.status, code: "HTTP_" + res.status, message: res.statusText || "Request failed" };
    try {
      const j = await res.json();
      err = { status: res.status, code: j.code ?? err.code, message: j.message ?? err.message, fieldErrors: j.fieldErrors };
    } catch { /* not json */ }
    throw err;
  }
  if (opts.raw) return (await res.text()) as unknown as T;
  const text = await res.text();
  return (text ? JSON.parse(text) : null) as T;
}

export const api = {
  get: <T = any>(p: string, o?: Opts) => request<T>("GET", p, undefined, o),
  post: <T = any>(p: string, b?: unknown, o?: Opts) => request<T>("POST", p, b ?? {}, o),
  put: <T = any>(p: string, b?: unknown, o?: Opts) => request<T>("PUT", p, b ?? {}, o),
  patch: <T = any>(p: string, b?: unknown, o?: Opts) => request<T>("PATCH", p, b ?? {}, o),
  del: <T = any>(p: string, o?: Opts) => request<T>("DELETE", p, undefined, o),
};

/** Download a protected file (CSV etc.) via fetch + blob. */
export async function download(path: string, filename: string) {
  const res = await fetch(apiUrl + path, { headers: { Authorization: `Bearer ${tokenStore.get()}` } });
  if (!res.ok) throw { status: res.status, code: "DOWNLOAD", message: "Download failed" } as ApiError;
  const blob = await res.blob();
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = filename;
  a.click();
  setTimeout(() => URL.revokeObjectURL(url), 5000);
}

/** Open protected HTML (printable invoice) in a new window. */
export async function openHtml(path: string) {
  const w = window.open("", "_blank");
  const html = await api.get<string>(path, { raw: true });
  const url = URL.createObjectURL(new Blob([html], { type: "text/html" }));
  if (w) w.location.href = url; else window.location.href = url;
}

export function qs(params: Record<string, unknown>): string {
  const p = new URLSearchParams();
  Object.entries(params).forEach(([k, v]) => {
    if (v !== undefined && v !== null && v !== "") p.set(k, String(v));
  });
  const s = p.toString();
  return s ? "?" + s : "";
}

export function errMsg(e: unknown): string {
  const a = e as ApiError;
  if (a && a.message) {
    if (a.fieldErrors && Object.keys(a.fieldErrors).length)
      return a.message + ": " + Object.entries(a.fieldErrors).map(([k, v]) => `${k} ${v}`).join("; ");
    return a.message;
  }
  return String(e);
}
