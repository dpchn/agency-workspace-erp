"use client";
import { useState } from "react";
import { api } from "@/lib/api";
import { useToast } from "@/components/Toast";
import { MiniShell } from "@/components/MiniShell";
import { useApi } from "@/lib/hooks";
import { useSession } from "@/lib/session";
import { ActionButton, Badge, ErrorBox, Loading, Modal, Stat, Tabs, PageHeader } from "@/components/ui";
import { DataTable } from "@/components/DataTable";
import { InvoiceDetail } from "@/components/InvoiceDetail";
import { IncidentList } from "@/components/IncidentList";
import { StatementTable } from "@/components/StatementTable";
import { fmtDateTime, fmtMoney, fmtNum, thisMonth, today } from "@/lib/format";

export default function PortalPage() {
  return <MiniShell roles={["CLIENT_ADMIN", "CLIENT_VIEWER"]}><Portal /></MiniShell>;
}

function Portal() {
  const { me } = useSession();
  const [tab, setTab] = useState("summary");
  const sites = useApi<any>("/v1/sites?size=200");
  const siteOptions = (sites.data?.items ?? []).map((s: any) => ({ value: s.id, label: s.name }));
  return (
    <div>
      <Tabs active={tab} onChange={setTab} tabs={[
        { key: "summary", label: "Today" }, { key: "attendance", label: "Attendance" }, { key: "invoices", label: "Invoices" }, { key: "statement", label: "Statement" }, { key: "incidents", label: "Incidents" },
      ]} />
      {tab === "summary" && <Summary />}
      {tab === "attendance" && <Attendance />}
      {tab === "invoices" && <Invoices />}
      {tab === "statement" && <div className="card p-0"><StatementTable clientId={me?.clientId ?? ""} /></div>}
      {tab === "incidents" && <IncidentList siteOptions={siteOptions} compact />}
    </div>
  );
}

function Summary() {
  const [date, setDate] = useState(today());
  const { data, loading } = useApi<any>(`/v1/portal/summary?date=${date}`);
  if (loading && !data) return <Loading />;
  return (
    <div className="space-y-4">
      <PageHeader title="Service summary" actions={<input type="date" className="input w-auto" value={date} onChange={(e) => setDate(e.target.value)} aria-label="Date" />} />
      <div className="grid grid-cols-2 gap-3">
        <Stat label="Outstanding" value={fmtMoney(data?.outstanding)} />
        <Stat label="Open incidents" value={data?.openIncidents ?? 0} tone={data?.openIncidents ? "red" : undefined} />
      </div>
      <div className="card p-0">
        <DataTable rows={data?.sites ?? []} empty="No sites." columns={[
          { key: "name", label: "Site" }, { key: "required_today", label: "Guards required" }, { key: "reported_today", label: "Reported on duty" },
          { key: "s", label: "", render: (r) => <Badge value={Number(r.reported_today) >= Number(r.required_today) ? "FILLED" : "PARTIAL"} /> },
        ]} />
      </div>
    </div>
  );
}

function Invoices() {
  const { data, loading } = useApi<any[]>("/v1/invoices");
  const [sel, setSel] = useState<string | null>(null);
  return (
    <div className="card p-0">
      {loading ? <Loading /> : (
        <DataTable rows={data ?? []} onRowClick={(r) => setSel(r.id)} empty="No invoices." columns={[
          { key: "invoice_no", label: "Number" }, { key: "period_start", label: "Period", render: (r) => String(r.period_start ?? "").slice(0, 7) },
          { key: "due_date", label: "Due" }, { key: "total", label: "Total", render: (r) => fmtMoney(r.total) },
          { key: "balance", label: "Balance", render: (r) => fmtMoney(r.balance) }, { key: "status", label: "Status", render: (r) => <Badge value={r.status} /> },
        ]} />
      )}
      <Modal open={!!sel} title="Invoice" onClose={() => setSel(null)} wide>{sel && <InvoiceDetail id={sel} />}</Modal>
    </div>
  );
}

/** Monthly attendance per site: required vs reported, who worked which day, and confirm / dispute. */
function Attendance() {
  const { can } = useSession();
  const toast = useToast();
  const [month, setMonth] = useState(thisMonth());
  const [siteId, setSiteId] = useState("");
  const { data, loading, error, reload } = useApi<any>(`/v1/portal/attendance?month=${month}${siteId ? `&siteId=${siteId}` : ""}`);
  const [dispute, setDispute] = useState(false);
  const [picked, setPicked] = useState<Record<number, string>>({});
  const [note, setNote] = useState("");
  if (loading && !data) return <Loading />;
  if (error) return <ErrorBox error={error} />;
  const days: any[] = data?.days ?? [];
  const byDay = new Map(days.map((d) => [d.day, d]));
  const all = Array.from({ length: data?.daysInMonth ?? 0 }, (_, i) => i + 1);
  const short = (d: any) => d && Number(d.reported) < Number(d.required);
  const so = data?.signoff;
  const site = data?.siteId as string | undefined;
  const iso = (day: number) => `${month}-${String(day).padStart(2, "0")}`;
  const cell = (c?: string) => c ? (c.startsWith("P") ? "bg-emerald-100" : c.startsWith("A") ? "bg-red-100" : c.startsWith("H") ? "bg-amber-100" : "bg-slate-100") : "";

  const sign = async (status: "CONFIRMED" | "DISPUTED") => {
    const disputes = Object.entries(picked).map(([d, n]) => ({ date: iso(Number(d)), note: n }));
    await api.post("/v1/portal/attendance/signoff", { siteId: site, month, status, note: status === "DISPUTED" ? note : note || undefined, disputes: status === "DISPUTED" ? disputes : [] });
    toast.ok(status === "CONFIRMED" ? "Attendance confirmed" : "Dispute sent to the agency");
    setDispute(false); setPicked({}); setNote(""); reload();
  };

  return (
    <div className="space-y-4">
      <PageHeader title="Attendance" subtitle={"Guards required vs reported at your site each day." + (data?.signoffEnabled ? " Confirm the month, or dispute specific days before it is invoiced." : "")}
        actions={<>
          <input type="month" className="input w-auto" value={month} onChange={(e) => setMonth(e.target.value)} aria-label="Month" />
          <select className="input w-auto" value={site ?? ""} onChange={(e) => setSiteId(e.target.value)} aria-label="Site">
            {(data?.sites ?? []).map((s: any) => <option key={s.id} value={s.id}>{s.name}</option>)}
          </select>
        </>} />
      {!data?.sites?.length ? <div className="card text-sm text-slate-500">No sites.</div> : <>
        <div className="grid grid-cols-3 gap-3">
          <Stat label="Duties required" value={data.totals?.required ?? 0} />
          <Stat label="Duties reported" value={fmtNum(data.totals?.reported)} tone={Number(data.totals?.reported) < Number(data.totals?.required) ? "amber" : "green"} />
          <Stat label="Short days" value={days.filter(short).length} tone={days.some(short) ? "red" : undefined} />
        </div>

        {data?.signoffEnabled && <div className={`card text-sm ${so?.status === "CONFIRMED" ? "border-emerald-200 bg-emerald-50" : so?.status === "DISPUTED" ? "border-red-200 bg-red-50" : ""}`}>
          <div className="flex flex-wrap items-center justify-between gap-2">
            <div>
              {so ? <>
                <Badge value={so.status === "DISPUTED" && so.resolvedAt ? "RESOLVED" : so.status} /> by {so.signedBy} · {fmtDateTime(so.signedAt)} · {fmtNum(so.dutiesAtSignoff)} duties
                {so.note && <div className="mt-1">{so.note}</div>}
                {(so.disputes ?? []).map((d: any) => <div key={d.date} className="text-red-700">{d.date}: {d.note}</div>)}
                {so.resolvedAt && <div className="mt-1 text-emerald-700">Agency response: {so.resolutionNote} ({fmtDateTime(so.resolvedAt)})</div>}
              </> : <span>Not yet confirmed for {month}.</span>}
            </div>
            {can("CLIENT_SIGNOFF") && (
              <div className="flex gap-2">
                <ActionButton className="btn-primary" confirm={`Confirm ${month} attendance at this site?`} onClick={() => sign("CONFIRMED")}>Confirm month</ActionButton>
                <button className="btn-danger" onClick={() => setDispute(true)}>Dispute</button>
              </div>
            )}
          </div>
        </div>}

        <div className="card overflow-x-auto p-0">
          <table className="w-full border-collapse text-[11px]">
            <thead>
              <tr><th className="sticky left-0 bg-slate-50 p-1 text-left">Day</th>{all.map((d) => <th key={d} className={`p-1 ${short(byDay.get(d)) ? "bg-red-100 text-red-700" : "bg-slate-50"}`}>{d}</th>)}<th className="bg-slate-50 p-1">Total</th></tr>
              <tr className="border-b"><td className="sticky left-0 bg-white p-1 text-slate-500">Required</td>{all.map((d) => <td key={d} className="p-1 text-center">{byDay.get(d)?.required ?? ""}</td>)}<td className="p-1 text-center font-medium">{data.totals?.required}</td></tr>
              <tr className="border-b"><td className="sticky left-0 bg-white p-1 text-slate-500">Reported</td>{all.map((d) => <td key={d} className={`p-1 text-center ${short(byDay.get(d)) ? "font-semibold text-red-700" : ""}`}>{byDay.has(d) ? fmtNum(byDay.get(d).reported) : ""}</td>)}<td className="p-1 text-center font-medium">{fmtNum(data.totals?.reported)}</td></tr>
            </thead>
            <tbody>
              {(data.people ?? []).map((p: any) => (
                <tr key={p.code} className="border-b">
                  <td className="sticky left-0 whitespace-nowrap bg-white p-1">{p.name} <span className="text-slate-400">{p.role}</span></td>
                  {all.map((d) => <td key={d} className="p-0.5 text-center"><div className={`rounded ${cell(p.days[d])}`}>{p.days[d] ?? ""}</div></td>)}
                  <td className="p-1 text-center font-medium">{fmtNum(p.duties)}</td>
                </tr>
              ))}
            </tbody>
          </table>
          {!data.people?.length && <div className="p-6 text-center text-sm text-slate-500">No attendance at this site this month.</div>}
        </div>
      </>}

      <Modal open={dispute} title={`Dispute ${month} attendance`} onClose={() => setDispute(false)} wide>
        <p className="mb-2 text-sm text-slate-600">Tap the days that are wrong and say what happened (e.g. “only 1 guard at night”). Red days had fewer guards reported than required.</p>
        <div className="mb-3 flex flex-wrap gap-1">
          {all.map((d) => (
            <button key={d} onClick={() => { const n = { ...picked }; if (d in n) delete n[d]; else n[d] = ""; setPicked(n); }}
              className={`h-9 w-9 rounded text-sm ${d in picked ? "bg-red-600 text-white" : short(byDay.get(d)) ? "bg-red-100 text-red-700" : "bg-slate-100"}`}>{d}</button>
          ))}
        </div>
        <div className="space-y-2">
          {Object.keys(picked).map(Number).sort((a, b) => a - b).map((d) => (
            <div key={d} className="flex items-center gap-2">
              <span className="w-24 text-sm">{iso(d)}</span>
              <input className="input" value={picked[d]} onChange={(e) => setPicked({ ...picked, [d]: e.target.value })} placeholder="What was wrong" aria-label={`Note for ${iso(d)}`} />
            </div>
          ))}
        </div>
        <textarea className="input mt-3 min-h-[70px]" value={note} onChange={(e) => setNote(e.target.value)} placeholder="Overall comment" aria-label="Overall comment" />
        <div className="mt-3 flex justify-end gap-2">
          <button className="btn-secondary" onClick={() => setDispute(false)}>Cancel</button>
          <ActionButton className="btn-danger" disabled={!note.trim() && !Object.keys(picked).length} onClick={() => sign("DISPUTED")}>Send dispute</ActionButton>
        </div>
      </Modal>
    </div>
  );
}
