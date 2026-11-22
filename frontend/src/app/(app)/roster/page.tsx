"use client";
import { useMemo, useState } from "react";
import { api, qs } from "@/lib/api";
import { useApi } from "@/lib/hooks";
import { useSession } from "@/lib/session";
import { ActionButton, Badge, ErrorBox, Loading, Modal, PageHeader, Empty } from "@/components/ui";
import { Form } from "@/components/Form";
import { useToast } from "@/components/Toast";
import { useShiftTemplates, useSites, usePosts } from "@/lib/lookups";
import { addDays, fmtTime, today } from "@/lib/format";

type ShiftView = { shift: any; siteName: string; postName: string; assignments: any[] };

const CELL: Record<string, string> = {
  FILLED: "border-emerald-300 bg-emerald-50", PARTIAL: "border-amber-300 bg-amber-50", OPEN: "border-red-300 bg-red-50", CANCELLED: "border-slate-200 bg-slate-50 opacity-60",
};
const CHIP: Record<string, string> = {
  PLANNED: "bg-slate-200 text-slate-700", CONFIRMED: "bg-blue-100 text-blue-800", COMPLETED: "bg-emerald-100 text-emerald-800",
  NO_SHOW: "bg-red-100 text-red-700 line-through", CANCELLED: "bg-slate-100 text-slate-400 line-through",
};

export default function RosterPage() {
  const { can } = useSession();
  const toast = useToast();
  const [from, setFrom] = useState(today());
  const [days, setDays] = useState(7);
  const [siteId, setSiteId] = useState("");
  const to = addDays(from, days - 1);
  const { data, error, loading, reload } = useApi<ShiftView[]>("/v1/roster" + qs({ from, to, siteId }));
  const sites = useSites(), tpl = useShiftTemplates(), posts = usePosts();
  const [sel, setSel] = useState<ShiftView | null>(null);
  const [gen, setGen] = useState(false);
  const [extra, setExtra] = useState(false);

  const dates = useMemo(() => Array.from({ length: days }, (_, i) => addDays(from, i)), [from, days]);
  const rows = useMemo(() => {
    const m = new Map<string, { label: string; sub: string; cells: Record<string, ShiftView[]> }>();
    (data ?? []).forEach((v) => {
      const key = `${v.siteName}|${v.postName}|${v.shift.shiftTemplateId ?? "x"}|${v.shift.kind}`;
      if (!m.has(key)) m.set(key, {
        label: `${v.siteName} · ${v.postName}`,
        sub: (v.shift.shiftTemplateId ? tpl.map[v.shift.shiftTemplateId] : `${fmtTime(v.shift.startAt)}-${fmtTime(v.shift.endAt)}`) + (v.shift.kind === "EXTRA" ? " · EXTRA" : ""),
        cells: {},
      });
      const r = m.get(key)!;
      (r.cells[v.shift.dutyDate] ??= []).push(v);
    });
    return [...m.values()].sort((a, b) => a.label.localeCompare(b.label) || a.sub.localeCompare(b.sub));
  }, [data, tpl.map]);

  const summary = useMemo(() => {
    const s = { FILLED: 0, PARTIAL: 0, OPEN: 0 } as Record<string, number>;
    (data ?? []).forEach((v) => { if (s[v.shift.status] !== undefined) s[v.shift.status]++; });
    return s;
  }, [data]);

  const refreshSel = async () => {
    await reload();
    setSel(null);
  };

  return (
    <div>
      <PageHeader title="Roster" subtitle={`${summary.FILLED} filled · ${summary.PARTIAL} partial · ${summary.OPEN} open`}
        actions={can("ROSTER_WRITE") && <>
          <button className="btn-secondary" onClick={() => setExtra(true)}>Extra shift</button>
          <button className="btn-primary" onClick={() => setGen(true)}>Generate roster</button>
        </>} />
      <div className="card mb-3 flex flex-wrap items-center gap-2 p-3">
        <button className="btn-secondary" onClick={() => setFrom(addDays(from, -days))}>‹</button>
        <input type="date" className="input w-auto" value={from} onChange={(e) => setFrom(e.target.value)} aria-label="From" />
        <button className="btn-secondary" onClick={() => setFrom(addDays(from, days))}>›</button>
        <select className="input w-auto" value={days} onChange={(e) => setDays(Number(e.target.value))} aria-label="Days">
          {[1, 3, 7, 14, 31].map((d) => <option key={d} value={d}>{d} day{d > 1 ? "s" : ""}</option>)}
        </select>
        <select className="input w-auto" value={siteId} onChange={(e) => setSiteId(e.target.value)} aria-label="Site">
          <option value="">All sites</option>{sites.options.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
        </select>
        <span className="ml-auto flex gap-2 text-xs">
          <span className="rounded border border-emerald-300 bg-emerald-50 px-1.5">filled</span>
          <span className="rounded border border-amber-300 bg-amber-50 px-1.5">partial</span>
          <span className="rounded border border-red-300 bg-red-50 px-1.5">open</span>
        </span>
      </div>
      {error && <ErrorBox error={error} onRetry={reload} />}
      {loading && !data ? <Loading /> : !rows.length ? <div className="card"><Empty text="No shifts in this range. Set up posts & shift requirements, deploy guards, then Generate roster." /></div> : (
        <div className="card overflow-x-auto p-0">
          <table className="w-full border-collapse text-xs">
            <thead>
              <tr>
                <th className="sticky left-0 z-10 min-w-[180px] border-b bg-slate-50 p-2 text-left">Post / shift</th>
                {dates.map((d) => (
                  <th key={d} className="min-w-[110px] border-b bg-slate-50 p-2 text-left font-medium">
                    {new Date(d + "T00:00:00").toLocaleDateString("en-IN", { weekday: "short", day: "2-digit", month: "short" })}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {rows.map((r) => (
                <tr key={r.label + r.sub}>
                  <td className="sticky left-0 z-10 border-b bg-white p-2 align-top"><div className="font-medium">{r.label}</div><div className="text-slate-500">{r.sub}</div></td>
                  {dates.map((d) => (
                    <td key={d} className="border-b p-1 align-top">
                      {(r.cells[d] ?? []).map((v) => (
                        <button key={v.shift.id} onClick={() => setSel(v)}
                          className={`mb-1 block w-full rounded border p-1 text-left hover:ring-2 hover:ring-brand/30 ${CELL[v.shift.status] ?? ""}`}>
                          {v.assignments.filter((a) => a.status !== "CANCELLED").map((a) => (
                            <div key={a.id} className={`mb-0.5 truncate rounded px-1 ${CHIP[a.status] ?? ""}`} title={`${a.employeeName} (${a.role})`}>
                              {a.employeeCode} {a.role !== "REGULAR" && <span className="opacity-70">· {a.role.replace("_", " ").toLowerCase()}</span>}
                            </div>
                          ))}
                          {v.shift.status !== "FILLED" && v.shift.status !== "CANCELLED" && <div className="text-red-600">needs {v.shift.requiredHeadcount}</div>}
                        </button>
                      ))}
                    </td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      <Modal open={!!sel} title={sel ? `${sel.siteName} · ${sel.postName} · ${sel.shift.dutyDate}` : ""} onClose={() => setSel(null)} wide>
        {sel && <ShiftDetail v={sel} canWrite={can("ROSTER_WRITE")} onChanged={refreshSel} />}
      </Modal>
      <Modal open={gen} title="Generate roster" onClose={() => setGen(false)}>
        <p className="mb-3 text-sm text-slate-600">Creates shifts from post requirements and assigns deployed guards (respecting weekly off, leave, rest hours and overlaps). Safe to re-run — only gaps are filled.</p>
        <Form initial={{ from, to }} fields={[
          { name: "from", label: "From", type: "date", required: true, half: true }, { name: "to", label: "To (max 62 days)", type: "date", required: true, half: true },
          { name: "siteId", label: "Site (blank = all in scope)", type: "select", options: sites.options },
        ]} onCancel={() => setGen(false)} submitLabel="Generate"
          onSubmit={async (v) => {
            const r = await api.post("/v1/roster/generate", v);
            toast.ok(`${r.shiftsCreated} shifts, ${r.assignmentsCreated} assignments created, ${r.openSlots} open`);
            r.warnings?.forEach((w: string) => toast.err(w));
            setGen(false); reload();
          }} />
      </Modal>
      <Modal open={extra} title="Extra shift" onClose={() => setExtra(false)}>
        <p className="mb-3 text-sm text-slate-600">Client-requested extras are billed (extra-shift rate); internal cover is paid but not billed.</p>
        <Form initial={{ dutyDate: from, headcount: 1, clientRequested: true }} fields={[
          { name: "postId", label: "Post", type: "select", options: posts.options, required: true },
          { name: "dutyDate", label: "Date", type: "date", required: true, half: true },
          { name: "shiftTemplateId", label: "Shift template", type: "select", options: tpl.options, half: true },
          { name: "startAt", label: "or custom start", type: "datetime", half: true }, { name: "endAt", label: "custom end", type: "datetime", half: true },
          { name: "headcount", label: "Headcount", type: "number", half: true },
          { name: "clientRequested", label: "Requested by client (billable)", type: "checkbox" },
          { name: "notes", label: "Notes" },
        ]} onCancel={() => setExtra(false)}
          onSubmit={async (v) => { await api.post("/v1/shifts", v); toast.ok("Extra shift created — assign a guard"); setExtra(false); reload(); }} />
      </Modal>
    </div>
  );
}

function ShiftDetail({ v, canWrite, onChanged }: { v: ShiftView; canWrite: boolean; onChanged: () => void }) {
  const toast = useToast();
  const [mode, setMode] = useState<null | { kind: "assign" } | { kind: "replace"; a: any }>(null);
  const [reason, setReason] = useState("");
  const [handover, setHandover] = useState("");
  const [doubleDuty, setDoubleDuty] = useState(false);
  const cands = useApi<any[]>(mode ? `/v1/shifts/${v.shift.id}/eligible-employees` : null);
  const s = v.shift;

  const pick = async (empId: string) => {
    try {
      if (mode?.kind === "assign") {
        await api.post("/v1/assignments", { shiftId: s.id, employeeId: empId, role: doubleDuty ? "DOUBLE_DUTY" : "RELIEF", reason: reason || "Assigned to open slot" });
      } else if (mode?.kind === "replace") {
        if (!reason) { toast.err("Reason is required"); return; }
        await api.post(`/v1/assignments/${mode.a.id}/replace`, {
          employeeId: empId, reason, doubleDuty, handoverAt: handover ? new Date(handover).toISOString() : null,
        });
      }
      toast.ok("Done");
      onChanged();
    } catch (e: any) {
      toast.err(e.message ?? "Failed");
    }
  };

  return (
    <div className="space-y-3 text-sm">
      <div className="flex flex-wrap items-center gap-2">
        <Badge value={s.status} /> <Badge value={s.kind} /> {s.clientRequested && <Badge value="CLIENT_REQUESTED" />}
        <span className="text-slate-500">{fmtTime(s.startAt)} – {fmtTime(s.endAt)} · needs {s.requiredHeadcount}</span>
        {s.notes && <span className="text-slate-500">· {s.notes}</span>}
      </div>
      <table className="table">
        <thead><tr><th>Guard</th><th>Role</th><th>Status</th><th>Window</th><th>Reason</th><th /></tr></thead>
        <tbody>
          {v.assignments.map((a) => (
            <tr key={a.id}>
              <td>{a.employeeCode} · {a.employeeName}</td><td>{a.role}</td><td><Badge value={a.status} /></td>
              <td>{fmtTime(a.startAt)}–{fmtTime(a.endAt)}</td><td className="text-xs text-slate-500">{a.reason}</td>
              <td className="whitespace-nowrap text-right">
                {canWrite && ["PLANNED", "CONFIRMED", "NO_SHOW"].includes(a.status) && (
                  <button className="btn-secondary mr-1" onClick={() => setMode({ kind: "replace", a })}>Replace</button>
                )}
                {canWrite && ["PLANNED", "CONFIRMED"].includes(a.status) && (
                  <ActionButton className="btn-ghost" confirm="Cancel this assignment?" success="Cancelled"
                    onClick={async () => { const r = prompt("Reason for cancelling?"); if (!r) return; await api.post(`/v1/assignments/${a.id}/cancel`, { reason: r }); onChanged(); }}>Cancel</ActionButton>
                )}
              </td>
            </tr>
          ))}
          {!v.assignments.length && <tr><td colSpan={6} className="text-slate-500">Nobody assigned.</td></tr>}
        </tbody>
      </table>
      <div className="flex flex-wrap gap-2">
        {canWrite && s.status !== "FILLED" && s.status !== "CANCELLED" && <button className="btn-primary" onClick={() => setMode({ kind: "assign" })}>Assign guard</button>}
        {canWrite && s.status !== "CANCELLED" && (
          <ActionButton className="btn-danger" confirm="Cancel the whole shift?" success="Shift cancelled"
            onClick={async () => { const r = prompt("Reason?"); if (!r) return; await api.post(`/v1/shifts/${s.id}/cancel`, { reason: r }); onChanged(); }}>Cancel shift</ActionButton>
        )}
      </div>
      {mode && (
        <div className="rounded-md border p-3">
          <div className="mb-2 font-medium">{mode.kind === "assign" ? "Assign to open slot" : `Replace ${mode.a.employeeCode}`}</div>
          <div className="mb-2 grid gap-2 sm:grid-cols-2">
            <input className="input" placeholder="Reason (required for replacement)" value={reason} onChange={(e) => setReason(e.target.value)} />
            {mode.kind === "replace" && (
              <label className="text-xs text-slate-600">Mid-shift handover at (blank = original is a no-show)
                <input type="datetime-local" className="input" value={handover} onChange={(e) => setHandover(e.target.value)} /></label>
            )}
            <label className="flex items-center gap-2 text-xs"><input type="checkbox" checked={doubleDuty} onChange={(e) => setDoubleDuty(e.target.checked)} />
              Double duty (allowed to break rest interval; paid at double-duty rate)</label>
          </div>
          {cands.loading ? <Loading /> : (
            <div className="max-h-64 overflow-y-auto">
              {(cands.data ?? []).map((c) => (
                <div key={c.employeeId} className="flex items-center justify-between border-b py-1.5">
                  <div>
                    <div>{c.code} · {c.name} <span className="text-xs text-slate-500">{c.phone}</span></div>
                    <div className="text-xs text-slate-500">{c.source.replace(/_/g, " ").toLowerCase()} · score {c.score}{c.notes?.length ? " · " + c.notes.join("; ") : ""}</div>
                  </div>
                  <button className="btn-secondary" onClick={() => pick(c.employeeId)}>Choose</button>
                </div>
              ))}
              {!cands.data?.length && <Empty text="No eligible employees (qualified, verified, free, in this branch)." />}
            </div>
          )}
        </div>
      )}
    </div>
  );
}
