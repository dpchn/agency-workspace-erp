"use client";
import { useState } from "react";
import { api, qs } from "@/lib/api";
import { useApi } from "@/lib/hooks";
import { useSession } from "@/lib/session";
import { ActionButton, Badge, ErrorBox, Loading, Modal, PageHeader, Tabs } from "@/components/ui";
import { DataTable } from "@/components/DataTable";
import { Form } from "@/components/Form";
import { useToast } from "@/components/Toast";
import { opts, useSites } from "@/lib/lookups";
import { addDays, fmtDateTime, fmtTime, thisMonth, today } from "@/lib/format";
import { AuthImage, SourceBadges } from "@/components/AuthImage";
import { RegisterEntry, RollCall, SignOffs } from "./team";

export default function AttendancePage() {
  const { can } = useSession();
  const [tab, setTab] = useState("records");
  return (
    <div>
      <Tabs active={tab} onChange={setTab} tabs={[
        { key: "records", label: "Records & approval" },
        ...(can("ATTENDANCE_MARK") ? [{ key: "roll", label: "Team roll-call" }] : []),
        ...(can("ATTENDANCE_ADJUST") ? [{ key: "register", label: "Register entry" }] : []),
        { key: "adj", label: "Corrections" }, { key: "signoff", label: "Client sign-off" }, { key: "muster", label: "Muster roll" },
      ]} />
      {tab === "records" && <Records />}
      {tab === "roll" && <RollCall />}
      {tab === "register" && <RegisterEntry />}
      {tab === "adj" && <Adjustments />}
      {tab === "signoff" && <SignOffs />}
      {tab === "muster" && <Muster />}
    </div>
  );
}

function Records() {
  const { can } = useSession();
  const toast = useToast();
  const sites = useSites();
  const [f, setF] = useState({ from: addDays(today(), -6), to: today(), siteId: "", approvalStatus: "PENDING", flagged: "" });
  const { data, error, loading, reload } = useApi<any[]>("/v1/attendance/records" + qs(f));
  const [sel, setSel] = useState<Set<string>>(new Set());
  const [adj, setAdj] = useState<any | null>(null);

  const decide = async (decision: string) => {
    const r = await api.post("/v1/attendance/approvals", { recordIds: [...sel], decision }, { idem: true });
    toast.ok(`${r.updated} record(s) ${decision.toLowerCase()}`);
    if (r.skipped?.length) toast.err(`${r.skipped.length} skipped: ${r.skipped.map((x: string) => x.replace(/^[0-9a-f-]{36}: /, "")).filter((x: string, i: number, a: string[]) => a.indexOf(x) === i).join("; ")}`);
    setSel(new Set());
    reload();
  };

  return (
    <div>
      <PageHeader title="Attendance" subtitle="Raw check-ins are kept as evidence; these records feed payroll and billing. Flags are for review, not automatic penalties."
        actions={can("ATTENDANCE_APPROVE") && (
          <ActionButton success="Day close done" onClick={async () => {
            const r = await api.post("/v1/attendance/finalize", {});
            toast.ok(`${r.absentMarked} marked absent, ${r.missingCheckout} missing check-out flagged`);
            reload();
          }}>Close ended duties</ActionButton>
        )} />
      <div className="card p-0">
        <div className="flex flex-wrap items-center gap-2 border-b p-3">
          <input type="date" className="input w-auto" value={f.from} onChange={(e) => setF({ ...f, from: e.target.value })} aria-label="From" />
          <input type="date" className="input w-auto" value={f.to} onChange={(e) => setF({ ...f, to: e.target.value })} aria-label="To" />
          <select className="input w-auto" value={f.siteId} onChange={(e) => setF({ ...f, siteId: e.target.value })} aria-label="Site">
            <option value="">All sites</option>{sites.options.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
          </select>
          <select className="input w-auto" value={f.approvalStatus} onChange={(e) => setF({ ...f, approvalStatus: e.target.value })} aria-label="Approval">
            <option value="">Any approval</option>{opts("PENDING", "APPROVED", "REJECTED").map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
          </select>
          <label className="flex items-center gap-1 text-sm"><input type="checkbox" checked={f.flagged === "true"} onChange={(e) => setF({ ...f, flagged: e.target.checked ? "true" : "" })} /> Flagged only</label>
          {can("ATTENDANCE_APPROVE") && sel.size > 0 && (
            <div className="ml-auto flex gap-2">
              <span className="self-center text-sm text-slate-500">{sel.size} selected</span>
              <ActionButton className="btn-primary" onClick={() => decide("APPROVED")}>Approve</ActionButton>
              <ActionButton className="btn-danger" onClick={() => decide("REJECTED")}>Reject</ActionButton>
            </div>
          )}
        </div>
        {error && <div className="p-3"><ErrorBox error={error} /></div>}
        {loading && !data ? <Loading /> : (
          <DataTable rows={data ?? []} selectable={can("ATTENDANCE_APPROVE")} selected={sel} onSelect={setSel} empty="No records for these filters." columns={[
            { key: "duty_date", label: "Date" },
            { key: "employee", label: "Employee", render: (r) => <><div>{r.employee_code}</div><div className="text-xs text-slate-500">{r.employee_name}</div></> },
            { key: "site", label: "Site / post", render: (r) => <><div>{r.site_name}</div><div className="text-xs text-slate-500">{r.post_name}</div></> },
            { key: "in", label: "In / out", render: (r) => `${fmtTime(r.check_in_at)} – ${fmtTime(r.check_out_at)}` },
            { key: "worked", label: "Worked", render: (r) => `${Math.floor(r.worked_minutes / 60)}h${r.worked_minutes % 60}m` + (r.late_minutes ? ` · late ${r.late_minutes}m` : "") + (r.ot_minutes ? ` · OT ${r.ot_minutes}m` : "") },
            { key: "status", label: "Status", render: (r) => <Badge value={r.status} /> },
            { key: "units", label: "Pay / bill", render: (r) => `${r.pay_units} / ${r.bill_units}` },
            { key: "role", label: "Role", render: (r) => <>{r.assignment_role !== "REGULAR" && <Badge value={r.assignment_role} />} {r.shift_kind === "EXTRA" && <Badge value="EXTRA" />}</> },
            { key: "sources", label: "Marked by", render: (r) => <SourceBadges sources={r.sources} /> },
            { key: "flags", label: "Flags", render: (r) => <div className="flex flex-wrap gap-1">{(r.flags ?? []).map((x: string) => <Badge key={x} value={x} />)}</div> },
            { key: "approval_status", label: "Approval", render: (r) => <>{<Badge value={r.approval_status} />}{(r.payroll_locked || r.invoice_locked) && <span className="ml-1 text-xs text-slate-400">locked</span>}</> },
            { key: "a", label: "", render: (r) => can("ATTENDANCE_ADJUST") && !r.payroll_locked && !r.invoice_locked && <button className="btn-ghost" onClick={(e) => { e.stopPropagation(); setAdj(r); }}>Adjust</button> },
          ]} />
        )}
      </div>
      <Modal open={!!adj} title={adj ? `Adjust ${adj.employee_code} on ${adj.duty_date}` : ""} onClose={() => setAdj(null)}>
        {adj && (
          <>
            <p className="mb-3 text-sm text-slate-600">Creates a correction request; another person must approve it. Original check-in events are never changed.</p>
            <Form initial={{ checkInAt: adj.check_in_at, checkOutAt: adj.check_out_at }} fields={[
              { name: "checkInAt", label: "Check-in", type: "datetime", half: true }, { name: "checkOutAt", label: "Check-out", type: "datetime", half: true },
              { name: "status", label: "Override status (optional)", type: "select", options: opts("PRESENT", "HALF_DAY", "ABSENT", "ON_LEAVE") },
              { name: "reason", label: "Reason", type: "textarea", required: true }, { name: "evidenceKey", label: "Evidence reference (register page, photo URL)" },
            ]} onCancel={() => setAdj(null)}
              onSubmit={async (v) => { await api.post("/v1/attendance/adjustments", { ...v, recordId: adj.id }); toast.ok("Adjustment requested"); setAdj(null); reload(); }} />
          </>
        )}
      </Modal>
    </div>
  );
}

/** beforeState stores missing times as the string "null". */
const nn = (v: unknown) => (v === "null" ? null : v);

function Adjustments() {
  const { can } = useSession();
  const toast = useToast();
  const [status, setStatus] = useState("PENDING");
  const { data, loading, error, reload } = useApi<any[]>(`/v1/attendance/adjustments?status=${status}`);
  const [sel, setSel] = useState<Set<string>>(new Set());
  const decideMany = async (approve: boolean) => {
    const r = await api.post("/v1/attendance/adjustments/decisions", { ids: [...sel], approve }, { idem: true });
    toast.ok(`${r.updated} correction(s) ${approve ? "approved" : "rejected"}`);
    if (r.skipped?.length) toast.err(`${r.skipped.length} skipped: ${r.skipped.map((x: string) => x.replace(/^[0-9a-f-]{36}: /, "")).filter((x: string, i: number, a: string[]) => a.indexOf(x) === i).join("; ")}`);
    setSel(new Set());
    reload();
  };
  const canDecide = status === "PENDING" && can("ATTENDANCE_APPROVE");
  return (
    <div>
      <PageHeader title="Attendance corrections" subtitle="Adjustments and paper-register entries. Maker-checker: the requester cannot approve their own correction."
        actions={<>
          {canDecide && sel.size > 0 && <>
            <span className="self-center text-sm text-slate-500">{sel.size} selected</span>
            <ActionButton className="btn-primary" onClick={() => decideMany(true)}>Approve</ActionButton>
            <ActionButton className="btn-danger" onClick={() => decideMany(false)}>Reject</ActionButton>
          </>}
          <select className="input w-auto" value={status} onChange={(e) => { setStatus(e.target.value); setSel(new Set()); }} aria-label="Status">
            {["PENDING", "APPROVED", "REJECTED"].map((s) => <option key={s}>{s}</option>)}</select>
        </>} />
      {error && <ErrorBox error={error} />}
      <div className="card p-0">
        {loading && !data ? <Loading /> : (
          <DataTable rows={data ?? []} selectable={canDecide} selected={sel} onSelect={setSel} empty="No corrections." columns={[
            { key: "who", label: "Employee / duty", render: (r) => <><div>{r.employeeCode} <span className="text-slate-500">{r.employeeName}</span></div><div className="text-xs text-slate-500">{r.dutyDate} · {r.siteName}</div></> },
            { key: "before", label: "Before", render: (r) => <span className="text-xs">{r.beforeState ? `${fmtTime(nn(r.beforeState.checkInAt))}–${fmtTime(nn(r.beforeState.checkOutAt))} ${r.beforeState.status}` : "-"}</span> },
            { key: "after", label: "Proposed", render: (r) => <span className="text-xs">{fmtTime(r.proposedCheckInAt)}–{fmtTime(r.proposedCheckOutAt)} {r.proposedStatus ? <Badge value={r.proposedStatus} /> : ""}</span> },
            { key: "reason", label: "Reason", render: (r) => <><div className="max-w-xs text-xs">{r.reason}</div>{r.batchId && <span className="text-[10px] text-violet-700">register batch</span>}</> },
            { key: "evidence", label: "Evidence", render: (r) => r.photoId ? <AuthImage id={r.photoId} className="h-10 w-10" alt="Evidence" /> : <span className="text-xs">{r.evidenceKey ?? "-"}</span> },
            { key: "requested", label: "Requested", render: (r) => <span className="text-xs">{r.requestedByName ?? "-"}<br />{fmtDateTime(r.createdAt)}</span> },
            { key: "status", label: "Status", render: (r) => <Badge value={r.status} /> },
            {
              key: "a", label: "", render: (r) => canDecide && r.status === "PENDING" && (
                <div className="flex gap-1">
                  <ActionButton className="btn-primary" success="Approved" onClick={async () => { await api.post(`/v1/attendance/adjustments/${r.id}/decision`, { approve: true }, { idem: true }); reload(); }}>Approve</ActionButton>
                  <ActionButton success="Rejected" onClick={async () => { await api.post(`/v1/attendance/adjustments/${r.id}/decision`, { approve: false }, { idem: true }); reload(); }}>Reject</ActionButton>
                </div>
              ),
            },
          ]} />
        )}
      </div>
    </div>
  );
}

function Muster() {
  const sites = useSites();
  const [month, setMonth] = useState(thisMonth());
  const [siteId, setSiteId] = useState("");
  const { data, loading, error } = useApi<any>("/v1/attendance/muster" + qs({ month, siteId }));
  const days = data ? Array.from({ length: data.daysInMonth }, (_, i) => i + 1) : [];
  const color = (c: string) => c.startsWith("P") ? "bg-emerald-100" : c.startsWith("A") ? "bg-red-100" : c.startsWith("H") ? "bg-amber-100" : c.startsWith("L") ? "bg-violet-100" : "bg-slate-100";
  return (
    <div>
      <PageHeader title="Muster roll" subtitle="P present · H half · A absent · L leave · ? pending. Hover a cell for the site."
        actions={<>
          <input type="month" className="input w-auto" value={month} onChange={(e) => setMonth(e.target.value)} aria-label="Month" />
          <select className="input w-auto" value={siteId} onChange={(e) => setSiteId(e.target.value)} aria-label="Site">
            <option value="">All sites</option>{sites.options.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
          </select>
        </>} />
      {error && <ErrorBox error={error} />}
      {loading ? <Loading /> : (
        <div className="card overflow-x-auto p-0">
          <table className="w-full border-collapse text-[11px]">
            <thead><tr><th className="sticky left-0 bg-slate-50 p-1 text-left">Employee</th>{days.map((d) => <th key={d} className="bg-slate-50 p-1">{d}</th>)}<th className="bg-slate-50 p-1">Duties</th></tr></thead>
            <tbody>
              {(data?.rows ?? []).map((r: any) => (
                <tr key={r.employeeId} className="border-b">
                  <td className="sticky left-0 whitespace-nowrap bg-white p-1">{r.code} <span className="text-slate-500">{r.name}</span></td>
                  {days.map((d) => {
                    const v: string[] = r.days[d] ?? [];
                    return <td key={d} className="p-0.5 text-center">{v.map((c, i) => <div key={i} title={c} className={`rounded ${color(c)}`}>{c.split("@")[0]}</div>)}</td>;
                  })}
                  <td className="p-1 text-center font-medium">{r.units}</td>
                </tr>
              ))}
            </tbody>
          </table>
          {!data?.rows?.length && <div className="p-6 text-center text-sm text-slate-500">No attendance this month.</div>}
        </div>
      )}
    </div>
  );
}
