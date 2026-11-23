"use client";
import { useState } from "react";
import { Camera, PhoneOff } from "lucide-react";
import { api, qs, uuid } from "@/lib/api";
import { useApi } from "@/lib/hooks";
import { useSession } from "@/lib/session";
import { ActionButton, Badge, ErrorBox, Loading, Modal, PageHeader } from "@/components/ui";
import { DataTable } from "@/components/DataTable";
import { AuthImage, SourceBadges } from "@/components/AuthImage";
import { useToast } from "@/components/Toast";
import { useSites } from "@/lib/lookups";
import { fileToJpeg, PhotoKind, uploadPhoto } from "@/lib/photo";
import { fmtDateTime, fmtTime, thisMonth, today } from "@/lib/format";

function position(): Promise<GeolocationPosition | null> {
  return new Promise((resolve) => {
    if (typeof navigator === "undefined" || !navigator.geolocation) return resolve(null);
    navigator.geolocation.getCurrentPosition((p) => resolve(p), () => resolve(null), { enableHighAccuracy: true, timeout: 10000, maximumAge: 60000 });
  });
}

/** Site + date picker and the duty sheet behind roll-call and register entry. */
function useSheet() {
  const sites = useSites();
  const [siteId, setSiteId] = useState("");
  const [date, setDate] = useState(today());
  const sheet = useApi<any[]>(siteId ? "/v1/attendance/sheet" + qs({ siteId, date }) : null);
  const picker = (
    <>
      <select className="input w-auto" value={siteId} onChange={(e) => setSiteId(e.target.value)} aria-label="Site">
        <option value="">Choose site…</option>{sites.options.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
      </select>
      <input type="date" className="input w-auto" value={date} onChange={(e) => setDate(e.target.value)} aria-label="Date" />
    </>
  );
  return { siteId, date, sheet, picker };
}

/** Picks (or captures, on phones) a photo, downscales it and uploads it; returns the photo id. */
function PhotoInput({ kind, label, siteId, maxSide, photoId, onChange }: {
  kind: PhotoKind; label: string; siteId?: string; maxSide?: number; photoId: string | null; onChange: (id: string | null) => void;
}) {
  const toast = useToast();
  const [busy, setBusy] = useState(false);
  return (
    <div className="flex items-center gap-3">
      <label className={`btn-secondary cursor-pointer ${busy ? "opacity-50" : ""}`}>
        <Camera size={16} /> {busy ? "Uploading…" : photoId ? "Retake photo" : label}
        <input type="file" accept="image/*" capture="environment" className="hidden" disabled={busy} onChange={async (e) => {
          const f = e.target.files?.[0];
          e.target.value = "";
          if (!f) return;
          setBusy(true);
          try {
            onChange(await uploadPhoto(kind, await fileToJpeg(f, maxSide), { siteId: siteId || undefined }));
          } catch (err: any) {
            toast.err(err?.message ?? "Photo upload failed");
          } finally {
            setBusy(false);
          }
        }} />
      </label>
      {photoId && <AuthImage id={photoId} className="h-14 w-14" alt={label} />}
    </div>
  );
}

const who = (r: any) => (
  <div className="flex items-center gap-2">
    <AuthImage id={r.photoId} className="h-8 w-8" alt={r.employeeName} />
    <div>
      <div className="flex items-center gap-1">{r.employeeName} {r.hasSmartphone === false && <span title="No smartphone"><PhoneOff size={12} className="text-slate-400" /></span>}</div>
      <div className="text-xs text-slate-500">{r.employeeCode} · {r.serviceType}</div>
    </div>
  </div>
);

/** Supervisor marks the whole team present (or out) with one team photo + their location. */
export function RollCall() {
  const toast = useToast();
  const { siteId, date, sheet, picker } = useSheet();
  const [sel, setSel] = useState<Set<string>>(new Set());
  const [photoId, setPhotoId] = useState<string | null>(null);
  const [results, setResults] = useState<any[] | null>(null);
  const rows = (sheet.data ?? []).map((r) => ({ ...r, id: r.assignmentId }));
  const names = new Map(rows.map((r) => [r.assignmentId, r.employeeName]));

  const mark = async (kind: "CHECK_IN" | "CHECK_OUT") => {
    const pos = await position();
    const r = await api.post("/v1/attendance/roll-call", {
      batchId: uuid(), siteId, kind, occurredAt: new Date().toISOString(), assignmentIds: [...sel], photoId,
      ...(pos ? { latitude: pos.coords.latitude, longitude: pos.coords.longitude, accuracyM: pos.coords.accuracy } : {}),
    }, { idem: true });
    toast.ok(`${r.marked} marked${r.rejected ? `, ${r.rejected} not marked` : ""}`);
    setResults(r.results);
    setSel(new Set());
    setPhotoId(null);
    sheet.reload();
  };

  return (
    <div>
      <PageHeader title="Team roll-call" subtitle="For guards without a phone: select who is on duty, take one photo of the team, then mark them in or out. A different person approves these duties."
        actions={picker} />
      {!siteId ? <div className="card text-sm text-slate-500">Choose a site to see its duties.</div> : (
        <div className="card p-0">
          <div className="flex flex-wrap items-center gap-3 border-b p-3">
            <PhotoInput kind="TEAM" label="Team photo" siteId={siteId} photoId={photoId} onChange={setPhotoId} />
            <div className="ml-auto flex items-center gap-2">
              <span className="text-sm text-slate-500">{sel.size} selected</span>
              <ActionButton className="btn-primary" disabled={!sel.size} onClick={() => mark("CHECK_IN")}>Mark in</ActionButton>
              <ActionButton disabled={!sel.size} onClick={() => mark("CHECK_OUT")}>Mark out</ActionButton>
            </div>
          </div>
          {sheet.error && <div className="p-3"><ErrorBox error={sheet.error} /></div>}
          {sheet.loading && !sheet.data ? <Loading /> : (
            <DataTable rows={rows} selectable selected={sel} onSelect={setSel} empty={`No duties at this site on ${date}.`} columns={[
              { key: "who", label: "Person", render: who },
              { key: "post", label: "Post / shift", render: (r) => <><div>{r.postName}</div><div className="text-xs text-slate-500">{fmtTime(r.startAt)}–{fmtTime(r.endAt)} {r.role !== "REGULAR" && r.role}</div></> },
              { key: "io", label: "In / out", render: (r) => `${fmtTime(r.checkInAt)} – ${fmtTime(r.checkOutAt)}` },
              { key: "status", label: "Status", render: (r) => <Badge value={r.status ?? r.assignmentStatus} /> },
              { key: "sources", label: "Marked by", render: (r) => <SourceBadges sources={r.sources} /> },
              { key: "approval", label: "Approval", render: (r) => <Badge value={r.approvalStatus} /> },
            ]} />
          )}
        </div>
      )}
      <Modal open={!!results} title="Roll-call result" onClose={() => setResults(null)}>
        <div className="space-y-1 text-sm">
          {(results ?? []).map((r, i) => (
            <div key={i} className="flex items-center justify-between border-b py-1">
              <span>{names.get(r.assignmentId) ?? r.assignmentId}</span>
              <span className="flex items-center gap-2"><Badge value={r.status} /><span className="max-w-[220px] truncate text-xs text-slate-500" title={r.message ?? r.flags?.join(", ")}>{r.message ?? r.flags?.join(", ")}</span></span>
            </div>
          ))}
        </div>
      </Modal>
    </div>
  );
}

/** Office types in a signed paper register; each line becomes a correction for a second person to approve. */
export function RegisterEntry() {
  const toast = useToast();
  const { siteId, date, sheet, picker } = useSheet();
  const [lines, setLines] = useState<Record<string, { status: string; in?: string; out?: string }>>({});
  const [photoId, setPhotoId] = useState<string | null>(null);
  const [note, setNote] = useState("");
  const rows = (sheet.data ?? []).map((r) => ({ ...r, id: r.assignmentId }));
  const setLine = (id: string, v: Partial<{ status: string; in?: string; out?: string }>) => setLines({ ...lines, [id]: { ...(lines[id] ?? { status: "" }), ...v } });
  const toIso = (t?: string) => (t ? new Date(`${date}T${t}`).toISOString() : undefined);
  const filled = Object.entries(lines).filter(([, v]) => v.status);

  const submit = async () => {
    if (!photoId) throw { message: "Attach a photo of the signed register page" };
    const r = await api.post("/v1/attendance/register-entry", {
      siteId, date, photoId, note,
      entries: filled.map(([assignmentId, v]) => ({ assignmentId, status: v.status, checkInAt: toIso(v.in), checkOutAt: toIso(v.out) })),
    }, { idem: true });
    toast.ok(`${r.count} line(s) sent for approval`);
    setLines({}); setPhotoId(null); setNote("");
    sheet.reload();
  };

  return (
    <div>
      <PageHeader title="Register entry" subtitle="Copy a signed paper attendance register into the system. Attach a photo of the page; another person must approve before it counts for pay or billing."
        actions={picker} />
      {!siteId ? <div className="card text-sm text-slate-500">Choose a site and date.</div> : (
        <div className="card p-0">
          {sheet.error && <div className="p-3"><ErrorBox error={sheet.error} /></div>}
          {sheet.loading && !sheet.data ? <Loading /> : (
            <DataTable rows={rows} empty={`No duties at this site on ${date}.`} columns={[
              { key: "who", label: "Person", render: who },
              { key: "shift", label: "Shift", render: (r) => `${fmtTime(r.startAt)}–${fmtTime(r.endAt)}` },
              { key: "now", label: "Recorded now", render: (r) => <><Badge value={r.status ?? r.assignmentStatus} /> {r.pendingCorrection && <span className="text-xs text-amber-700">correction pending</span>}</> },
              {
                key: "reg", label: "As per register", render: (r) => (
                  <div className="flex flex-wrap items-center gap-1">
                    <select className="input w-auto py-1" value={lines[r.assignmentId]?.status ?? ""} onChange={(e) => setLine(r.assignmentId, { status: e.target.value })}
                      aria-label={`Status for ${r.employeeName}`} disabled={r.pendingCorrection}>
                      <option value="">—</option><option value="PRESENT">P · Present</option><option value="HALF_DAY">H · Half day</option><option value="ABSENT">A · Absent</option>
                    </select>
                    {lines[r.assignmentId]?.status && lines[r.assignmentId].status !== "ABSENT" && <>
                      <input type="time" className="input w-auto py-1" value={lines[r.assignmentId]?.in ?? ""} onChange={(e) => setLine(r.assignmentId, { in: e.target.value })} aria-label="In time" title="In (blank = shift start)" />
                      <input type="time" className="input w-auto py-1" value={lines[r.assignmentId]?.out ?? ""} onChange={(e) => setLine(r.assignmentId, { out: e.target.value })} aria-label="Out time" title="Out (blank = shift end)" />
                    </>}
                  </div>
                ),
              },
            ]} />
          )}
          <div className="flex flex-wrap items-center gap-3 border-t p-3">
            <PhotoInput kind="REGISTER" label="Photo of register page" siteId={siteId} maxSide={1600} photoId={photoId} onChange={setPhotoId} />
            <input className="input max-w-sm" placeholder="Note (e.g. register signed by site in-charge)" value={note} onChange={(e) => setNote(e.target.value)} aria-label="Note" />
            <ActionButton className="btn-primary ml-auto" disabled={!filled.length} onClick={submit}>Submit {filled.length || ""} for approval</ActionButton>
          </div>
        </div>
      )}
    </div>
  );
}

/** Client confirmation / dispute of each site's monthly attendance. Disputes block issuing that invoice. */
export function SignOffs() {
  const { can } = useSession();
  const toast = useToast();
  const [month, setMonth] = useState(thisMonth());
  const { data, loading, error, reload } = useApi<any[]>(`/v1/attendance/signoffs?month=${month}`);
  const [resolve, setResolve] = useState<any | null>(null);
  const [note, setNote] = useState("");
  const canResolve = can("ATTENDANCE_APPROVE") || can("BILLING_WRITE");
  return (
    <div>
      <PageHeader title="Client sign-off" subtitle="Each client confirms (or disputes) the attendance at its sites for the month in the client portal. Resolve disputes before issuing the invoice."
        actions={<input type="month" className="input w-auto" value={month} onChange={(e) => setMonth(e.target.value)} aria-label="Month" />} />
      {error && <ErrorBox error={error} />}
      <div className="card p-0">
        {loading && !data ? <Loading /> : (
          <DataTable rows={(data ?? []).map((r) => ({ ...r, signoffId: r.id, id: r.siteId }))} empty="No attendance this month." columns={[
            { key: "site", label: "Site", render: (r) => <><div>{r.siteName}</div><div className="text-xs text-slate-500">{r.clientName}</div></> },
            { key: "duties", label: "Duties now", render: (r) => <>{r.duties}{r.dutiesAtSignoff != null && Number(r.dutiesAtSignoff) !== Number(r.duties) && <div className="text-xs text-amber-700">{r.dutiesAtSignoff} when signed</div>}</> },
            { key: "status", label: "Client", render: (r) => <>{<Badge value={r.status === "DISPUTED" && r.resolvedAt ? "RESOLVED" : r.status} />}{r.signedBy && <div className="text-xs text-slate-500">{r.signedBy} · {fmtDateTime(r.signedAt)}</div>}</> },
            {
              key: "note", label: "Client note / disputed days", render: (r) => (
                <div className="max-w-md text-xs">
                  {r.note && <div>{r.note}</div>}
                  {(r.disputes ?? []).map((d: any) => <div key={d.date} className="text-red-700">{d.date}: {d.note}</div>)}
                  {r.resolutionNote && <div className="mt-1 text-emerald-700">Resolved by {r.resolvedBy}: {r.resolutionNote}</div>}
                </div>
              ),
            },
            { key: "a", label: "", render: (r) => canResolve && r.status === "DISPUTED" && !r.resolvedAt && <button className="btn-secondary" onClick={() => { setNote(""); setResolve(r); }}>Resolve</button> },
          ]} />
        )}
      </div>
      <Modal open={!!resolve} title={resolve ? `Resolve dispute · ${resolve.siteName}` : ""} onClose={() => setResolve(null)}>
        <p className="mb-2 text-sm text-slate-600">Correct the attendance first (Register entry / Adjust), then explain what was done. The client can sign off again.</p>
        <textarea className="input min-h-[100px]" value={note} onChange={(e) => setNote(e.target.value)} aria-label="Resolution" placeholder="e.g. Night duty on 3rd removed; credit applied" />
        <div className="mt-3 flex justify-end gap-2">
          <button className="btn-secondary" onClick={() => setResolve(null)}>Cancel</button>
          <ActionButton className="btn-primary" disabled={!note.trim()} onClick={async () => {
            await api.post(`/v1/attendance/signoffs/${resolve.signoffId}/resolve`, { note });
            toast.ok("Dispute resolved"); setResolve(null); reload();
          }}>Mark resolved</ActionButton>
        </div>
      </Modal>
    </div>
  );
}
