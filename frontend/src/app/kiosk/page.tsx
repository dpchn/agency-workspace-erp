"use client";
import { useCallback, useEffect, useRef, useState } from "react";
import { ArrowLeft, Camera, CheckCircle2, CloudOff, Delete, Hash, RefreshCw, XCircle, AlertTriangle } from "lucide-react";
import { MiniShell } from "@/components/MiniShell";
import { AuthImage } from "@/components/AuthImage";
import { api, ApiError, errMsg, uuid } from "@/lib/api";
import { useApi } from "@/lib/hooks";
import { deviceId } from "@/lib/offlineQueue";
import { captureVideo, uploadPhoto } from "@/lib/photo";
import { fmtTime } from "@/lib/format";
import { Loading } from "@/components/ui";

/**
 * Shared site device (gate tablet, or the supervisor's phone) for workers without a smartphone:
 * pick your name (or type your code) → PIN → photo → punch. Works offline; punches sync later.
 */
export default function KioskPage() {
  return <MiniShell roles={["SITE_KIOSK"]} perm="ATTENDANCE_KIOSK" wide><Kiosk /></MiniShell>;
}

type Kind = "CHECK_IN" | "CHECK_OUT";
type Person = { employeeId?: string; employeeCode?: string; employeeName?: string; photoId?: string; siteId?: string; kind: Kind; duty?: any };
type Result = { tone: "ok" | "warn" | "bad"; title: string; message?: string };

/** Punch waiting for network. The PIN is kept only until it is synced (the server verifies it). */
type Queued = { body: any; photoBase64?: string; siteId?: string; label: string; state: "pending" | "done" | "rejected"; message?: string };
const QKEY = "erp.kiosk.queue";
const loadQ = (): Queued[] => { try { return JSON.parse(localStorage.getItem(QKEY) || "[]"); } catch { return []; } };
const saveQ = (q: Queued[]) => { try { localStorage.setItem(QKEY, JSON.stringify(q.slice(-200))); } catch { /* storage full/blocked */ } };

function position(): Promise<GeolocationPosition | null> {
  return new Promise((resolve) => {
    if (typeof navigator === "undefined" || !navigator.geolocation) return resolve(null);
    navigator.geolocation.getCurrentPosition((p) => resolve(p), () => resolve(null), { enableHighAccuracy: true, timeout: 6000, maximumAge: 600000 });
  });
}

function resultOf(r: any): Result {
  if (r.status === "REJECTED") return { tone: "bad", title: "Not recorded", message: r.message ?? r.flags?.join(", ") };
  if (r.status === "FLAGGED") return { tone: "warn", title: "Recorded — supervisor will review", message: r.message ?? r.flags?.join(", ") };
  return { tone: "ok", title: "Recorded", message: r.message };
}

function Kiosk() {
  const duties = useApi<any>("/v1/attendance/kiosk/duties");
  const [step, setStep] = useState<"list" | "code" | "pin" | "photo" | "result">("list");
  const [person, setPerson] = useState<Person | null>(null);
  const [code, setCode] = useState("");
  const [pin, setPin] = useState("");
  const [result, setResult] = useState<Result | null>(null);
  const [queue, setQueue] = useState<Queued[]>([]);
  const [online, setOnline] = useState(true);
  const [clock, setClock] = useState(new Date());

  const reset = useCallback(() => { setStep("list"); setPerson(null); setCode(""); setPin(""); setResult(null); }, []);
  const update = (q: Queued[]) => { setQueue(q); saveQ(q); };

  useEffect(() => { setQueue(loadQ()); setOnline(navigator.onLine); }, []);
  useEffect(() => {
    const t = setInterval(() => { setClock(new Date()); }, 30000);
    const r = setInterval(() => { if (step === "list") duties.reload(); }, 60000);
    return () => { clearInterval(t); clearInterval(r); };
  }, [duties, step]);
  // Return to the start screen automatically so the next person never sees someone else's result.
  useEffect(() => {
    if (step === "result") { const t = setTimeout(reset, 4000); return () => clearTimeout(t); }
    if (step !== "list") { const t = setTimeout(reset, 90000); return () => clearTimeout(t); }
  }, [step, reset]);

  const sync = useCallback(async () => {
    const q = loadQ();
    const pending = q.filter((x) => x.state === "pending");
    if (!pending.length) return;
    try {
      for (const p of pending) {
        if (p.photoBase64 && !p.body.photoId) {
          p.body.photoId = await uploadPhoto("SELFIE", p.photoBase64, { siteId: p.siteId, employeeId: p.body.employeeId, takenAt: p.body.occurredAt });
          p.photoBase64 = undefined;
          saveQ(q);
        }
      }
      const res: any[] = await api.post("/v1/attendance/kiosk/punch/batch", pending.map((p) => ({ ...p.body, offline: true })));
      const byId = new Map(res.map((r) => [r.clientEventId, r]));
      const next = q.map((p) => {
        const r = byId.get(p.body.clientEventId);
        if (p.state !== "pending" || !r) return p;
        // drop the PIN once the server has seen it
        const { pin: _pin, ...body } = p.body;
        return { ...p, body, state: r.status === "REJECTED" ? "rejected" : "done", message: r.message ?? r.flags?.join(", ") } as Queued;
      });
      update(next);
      duties.reload();
    } catch (e) {
      if ((e as ApiError).status !== 0) console.warn("Kiosk sync failed", e);
    }
  }, [duties]);

  useEffect(() => {
    const on = () => { setOnline(true); sync(); };
    const off = () => setOnline(false);
    window.addEventListener("online", on);
    window.addEventListener("offline", off);
    const t = setInterval(() => { if (navigator.onLine) sync(); }, 120000);
    return () => { window.removeEventListener("online", on); window.removeEventListener("offline", off); clearInterval(t); };
  }, [sync]);

  const choose = (d: any) => {
    setPerson({ employeeId: d.employeeId, employeeCode: d.employeeCode, employeeName: d.employeeName, photoId: d.photoId, siteId: d.siteId,
      kind: d.checkInAt && !d.checkOutAt ? "CHECK_OUT" : "CHECK_IN", duty: d });
    setPin("");
    setStep("pin");
  };

  const punch = async (photoBase64: string | null) => {
    if (!person) return;
    const pos = await position();
    const siteId = person.siteId ?? duties.data?.sites?.[0]?.id;
    const body: any = {
      clientEventId: uuid(), kind: person.kind, occurredAt: new Date().toISOString(), pin, deviceId: deviceId(),
      ...(person.employeeId ? { employeeId: person.employeeId } : { employeeCode: person.employeeCode }),
      ...(pos ? { latitude: pos.coords.latitude, longitude: pos.coords.longitude, accuracyM: pos.coords.accuracy } : {}),
    };
    setPin("");
    const label = `${person.employeeName ?? person.employeeCode} · ${person.kind === "CHECK_IN" ? "In" : "Out"}`;
    try {
      if (photoBase64) body.photoId = await uploadPhoto("SELFIE", photoBase64, { siteId, employeeId: person.employeeId, takenAt: body.occurredAt });
      const r = await api.post("/v1/attendance/kiosk/punch", body, { idem: body.clientEventId });
      setResult(resultOf(r));
      duties.reload();
    } catch (e) {
      const a = e as ApiError;
      if (a.status === 0) {
        update([...loadQ(), { body, photoBase64: body.photoId ? undefined : photoBase64 ?? undefined, siteId, label, state: "pending" }]);
        setResult({ tone: "warn", title: "Saved on this device", message: "No network. It will sync automatically." });
      } else {
        const field = a.fieldErrors ? Object.values(a.fieldErrors)[0] : undefined;
        setResult({ tone: "bad", title: a.fieldErrors?.pin ? "Wrong PIN" : "Not recorded", message: a.fieldErrors?.pin ? "Try again, or ask your supervisor." : field ?? errMsg(e) });
      }
    }
    setStep("result");
  };

  const pending = queue.filter((q) => q.state === "pending").length;
  const list: any[] = duties.data?.duties ?? [];
  const siteNames = (duties.data?.sites ?? []).map((s: any) => s.name).join(", ");

  return (
    <div className="select-none space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div>
          <div className="text-2xl font-semibold">{clock.toLocaleTimeString("en-IN", { hour: "2-digit", minute: "2-digit" })}</div>
          <div className="text-sm text-slate-500">{siteNames || "Attendance kiosk"} · {clock.toLocaleDateString("en-IN", { weekday: "long", day: "numeric", month: "short" })}</div>
        </div>
        {(!online || pending > 0) && (
          <button className="btn-secondary border-amber-300 bg-amber-50" onClick={sync} disabled={!online}>
            <CloudOff size={16} /> {online ? "" : "Offline · "}{pending} waiting to sync {online && <RefreshCw size={14} />}
          </button>
        )}
      </div>

      {step === "list" && (
        <>
          <div className="flex items-center justify-between">
            <h1 className="text-lg font-semibold">Tap your name</h1>
            <button className="btn-secondary px-4 py-3 text-base" onClick={() => { setCode(""); setStep("code"); }}><Hash size={18} /> Enter my code</button>
          </div>
          {duties.error && <div className="rounded border border-red-200 bg-red-50 p-3 text-sm text-red-700">{duties.error}</div>}
          {duties.loading && !duties.data ? <Loading /> : !list.length ? (
            <div className="card py-10 text-center text-slate-500">No duties right now at this site. Use “Enter my code”.</div>
          ) : (
            <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-4">
              {list.map((d) => {
                const done = !!d.checkOutAt, inside = !!d.checkInAt && !d.checkOutAt;
                return (
                  <button key={d.assignmentId} disabled={done} onClick={() => choose(d)}
                    className={`card flex flex-col items-center gap-2 p-4 text-center transition active:scale-95 disabled:opacity-50 ${inside ? "border-emerald-300 bg-emerald-50" : ""}`}>
                    <AuthImage id={d.photoId} className="h-16 w-16" zoom={false} alt={d.employeeName} />
                    <div className="text-base font-semibold leading-tight">{d.employeeName}</div>
                    <div className="text-xs text-slate-500">{d.employeeCode} · {fmtTime(d.startAt)}–{fmtTime(d.endAt)}</div>
                    <div className={`text-xs font-medium ${inside ? "text-emerald-700" : done ? "text-slate-500" : "text-brand"}`}>
                      {done ? `Done ${fmtTime(d.checkOutAt)}` : inside ? `In since ${fmtTime(d.checkInAt)} · tap to go out` : "Tap to check in"}
                    </div>
                    {!d.pinSet && <div className="text-[10px] text-red-600">No PIN set — ask office</div>}
                  </button>
                );
              })}
            </div>
          )}
          {queue.some((q) => q.state !== "pending") && (
            <div className="card text-xs">
              <div className="mb-1 flex justify-between font-medium">Synced from this device
                <button className="btn-ghost text-xs" onClick={() => update(queue.filter((q) => q.state === "pending"))}>Clear</button></div>
              {queue.filter((q) => q.state !== "pending").slice(-8).reverse().map((q) => (
                <div key={q.body.clientEventId} className="flex justify-between border-b py-1">
                  <span>{q.label}</span><span className={q.state === "rejected" ? "text-red-600" : "text-emerald-700"}>{q.state === "rejected" ? q.message ?? "Rejected" : "Synced"}</span>
                </div>
              ))}
            </div>
          )}
        </>
      )}

      {step === "code" && (
        <Panel title="Enter your employee code" onBack={reset}>
          <input autoFocus className="input py-4 text-center text-2xl uppercase tracking-widest" value={code} onChange={(e) => setCode(e.target.value.toUpperCase())}
            aria-label="Employee code" autoComplete="off" />
          <div className="grid grid-cols-2 gap-3">
            {(["CHECK_IN", "CHECK_OUT"] as Kind[]).map((k) => (
              <button key={k} className={k === "CHECK_IN" ? "btn-primary py-5 text-lg" : "btn-secondary py-5 text-lg"} disabled={code.trim().length < 2}
                onClick={() => { setPerson({ employeeCode: code.trim(), kind: k }); setPin(""); setStep("pin"); }}>
                {k === "CHECK_IN" ? "Check in" : "Check out"}
              </button>
            ))}
          </div>
        </Panel>
      )}

      {step === "pin" && person && (
        <Panel title={`${person.employeeName ?? person.employeeCode} · ${person.kind === "CHECK_IN" ? "Check in" : "Check out"}`} onBack={reset}>
          <div className="text-center text-sm text-slate-500">Enter your PIN</div>
          <div className="flex justify-center gap-3 py-2" aria-label={`${pin.length} digits entered`}>
            {Array.from({ length: Math.max(4, pin.length) }, (_, i) => <span key={i} className={`h-4 w-4 rounded-full ${i < pin.length ? "bg-slate-800" : "bg-slate-200"}`} />)}
          </div>
          <div className="mx-auto grid max-w-xs grid-cols-3 gap-3">
            {["1", "2", "3", "4", "5", "6", "7", "8", "9"].map((n) => <PadKey key={n} onClick={() => pin.length < 6 && setPin(pin + n)}>{n}</PadKey>)}
            <PadKey onClick={() => setPin(pin.slice(0, -1))} label="Delete"><Delete /></PadKey>
            <PadKey onClick={() => pin.length < 6 && setPin(pin + "0")}>0</PadKey>
            <PadKey onClick={() => setStep("photo")} disabled={pin.length < 4} primary label="OK">OK</PadKey>
          </div>
          {person.duty && (
            <button className="mx-auto block text-sm text-brand underline" onClick={() => setPerson({ ...person, kind: person.kind === "CHECK_IN" ? "CHECK_OUT" : "CHECK_IN" })}>
              Switch to {person.kind === "CHECK_IN" ? "check out" : "check in"}
            </button>
          )}
        </Panel>
      )}

      {step === "photo" && person && <PhotoStep person={person} onBack={reset} onShot={punch} />}

      {step === "result" && result && (
        <button onClick={reset} className={`flex w-full flex-col items-center gap-3 rounded-xl p-10 text-center text-white ${result.tone === "ok" ? "bg-emerald-600" : result.tone === "warn" ? "bg-amber-500" : "bg-red-600"}`}>
          {result.tone === "ok" ? <CheckCircle2 size={72} /> : result.tone === "warn" ? <AlertTriangle size={72} /> : <XCircle size={72} />}
          <div className="text-3xl font-semibold">{result.title}</div>
          {person && <div className="text-xl">{person.employeeName ?? person.employeeCode} · {person.kind === "CHECK_IN" ? "In" : "Out"} {fmtTime(new Date().toISOString())}</div>}
          {result.message && <div className="max-w-xl text-base opacity-90">{result.message}</div>}
        </button>
      )}
    </div>
  );
}

function Panel({ title, onBack, children }: { title: string; onBack: () => void; children: React.ReactNode }) {
  return (
    <div className="card mx-auto max-w-lg space-y-4 p-6">
      <div className="flex items-center gap-2">
        <button className="btn-ghost p-2" onClick={onBack} aria-label="Back"><ArrowLeft /></button>
        <h1 className="text-lg font-semibold">{title}</h1>
      </div>
      {children}
    </div>
  );
}

function PadKey({ children, onClick, disabled, primary, label }: { children: React.ReactNode; onClick: () => void; disabled?: boolean; primary?: boolean; label?: string }) {
  return (
    <button aria-label={label} disabled={disabled} onClick={onClick}
      className={`flex h-16 items-center justify-center rounded-xl text-2xl font-semibold active:scale-95 disabled:opacity-40 ${primary ? "bg-brand text-white" : "bg-slate-100 text-slate-800"}`}>
      {children}
    </button>
  );
}

/** Front camera preview; the photo is taken when the worker presses the button. */
function PhotoStep({ person, onBack, onShot }: { person: Person; onBack: () => void; onShot: (b64: string | null) => Promise<void> }) {
  const video = useRef<HTMLVideoElement>(null);
  const [err, setErr] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  useEffect(() => {
    let stream: MediaStream | null = null, live = true;
    if (!navigator.mediaDevices?.getUserMedia) { setErr("This device has no camera access (the page must be opened over https or on localhost)."); return; }
    navigator.mediaDevices.getUserMedia({ video: { facingMode: "user", width: { ideal: 640 } }, audio: false })
      .then((s) => { if (!live) { s.getTracks().forEach((t) => t.stop()); return; } stream = s; if (video.current) { video.current.srcObject = s; video.current.play().catch(() => {}); } })
      .catch(() => setErr("Camera not available or permission denied."));
    return () => { live = false; stream?.getTracks().forEach((t) => t.stop()); };
  }, []);
  const go = async (withPhoto: boolean) => {
    setBusy(true);
    let b64: string | null = null;
    if (withPhoto && video.current && video.current.videoWidth) b64 = captureVideo(video.current);
    await onShot(b64);
  };
  return (
    <Panel title={`${person.employeeName ?? person.employeeCode} · look at the camera`} onBack={onBack}>
      {err ? (
        <div className="space-y-3">
          <div className="rounded border border-amber-200 bg-amber-50 p-3 text-sm text-amber-800">{err} Your agency may require a photo; if so the punch will be refused and your supervisor must mark you.</div>
          <button className="btn-secondary w-full py-4 text-lg" disabled={busy} onClick={() => go(false)}>Punch without photo</button>
        </div>
      ) : (
        <>
          <video ref={video} playsInline muted className="mx-auto aspect-[4/3] w-full max-w-md -scale-x-100 rounded-lg bg-slate-900 object-cover" />
          <button className="btn-primary w-full py-5 text-xl" disabled={busy} onClick={() => go(true)}>
            <Camera size={24} /> {busy ? "Recording…" : person.kind === "CHECK_IN" ? "Take photo & check in" : "Take photo & check out"}
          </button>
        </>
      )}
    </Panel>
  );
}
