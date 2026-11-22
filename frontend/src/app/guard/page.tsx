"use client";
import { useCallback, useEffect, useState } from "react";
import { CloudOff, LogIn, LogOut as LogOutIcon, MapPin, RefreshCw } from "lucide-react";
import { MiniShell } from "@/components/MiniShell";
import { api, errMsg, uuid } from "@/lib/api";
import { useApi } from "@/lib/hooks";
import { Badge, Empty, Loading, Modal, Tabs } from "@/components/ui";
import { DataTable } from "@/components/DataTable";
import { Form } from "@/components/Form";
import { PayslipView } from "@/components/PayslipView";
import { IncidentList } from "@/components/IncidentList";
import { useToast } from "@/components/Toast";
import { deviceId, loadQueue, QueuedEvent, saveQueue, submitEvent, syncPending } from "@/lib/offlineQueue";
import { fmtDateTime, fmtMoney, fmtTime, thisMonth } from "@/lib/format";
import { opts } from "@/lib/lookups";

export default function GuardPage() {
  return <MiniShell roles={["GUARD", "SUPERVISOR"]}><GuardApp /></MiniShell>;
}

function GuardApp() {
  const [tab, setTab] = useState("duty");
  return (
    <div>
      <Tabs active={tab} onChange={setTab} tabs={[
        { key: "duty", label: "Duty" }, { key: "att", label: "Attendance" }, { key: "pay", label: "Payslips" },
        { key: "leave", label: "Leave" }, { key: "inc", label: "Incident" },
      ]} />
      {tab === "duty" && <Duty />}
      {tab === "att" && <MyAttendance />}
      {tab === "pay" && <MyPayslips />}
      {tab === "leave" && <MyLeave />}
      {tab === "inc" && <GuardIncidents />}
    </div>
  );
}

function getPosition(): Promise<GeolocationPosition | null> {
  return new Promise((resolve) => {
    if (typeof navigator === "undefined" || !navigator.geolocation) return resolve(null);
    navigator.geolocation.getCurrentPosition((p) => resolve(p), () => resolve(null), { enableHighAccuracy: true, timeout: 15000, maximumAge: 0 });
  });
}

function Duty() {
  const toast = useToast();
  const duties = useApi<any[]>("/v1/me/duties");
  const [queue, setQueue] = useState<QueuedEvent[]>([]);
  const [busy, setBusy] = useState<string | null>(null);
  const [online, setOnline] = useState(true);

  useEffect(() => { setQueue(loadQueue()); setOnline(navigator.onLine); }, []);
  const update = (q: QueuedEvent[]) => { setQueue(q); saveQueue(q); };

  const sync = useCallback(async () => {
    const q = loadQueue();
    if (!q.some((e) => e.state === "pending")) return;
    try {
      const n = await syncPending(q);
      update(n);
      toast.ok("Offline punches synced");
      duties.reload();
    } catch (e) {
      toast.err("Sync failed: " + errMsg(e));
    }
  }, [duties, toast]);

  useEffect(() => {
    const on = () => { setOnline(true); sync(); };
    const off = () => setOnline(false);
    window.addEventListener("online", on);
    window.addEventListener("offline", off);
    return () => { window.removeEventListener("online", on); window.removeEventListener("offline", off); };
  }, [sync]);

  const punch = async (kind: "CHECK_IN" | "CHECK_OUT", d: any) => {
    setBusy(d.assignment_id + kind);
    const pos = await getPosition();
    if (!pos) toast.err("Location unavailable — punch recorded without GPS and may need supervisor review");
    const ev: QueuedEvent = {
      clientEventId: uuid(), kind, occurredAt: new Date().toISOString(), assignmentId: d.assignment_id,
      latitude: pos?.coords.latitude, longitude: pos?.coords.longitude, accuracyM: pos?.coords.accuracy,
      deviceId: deviceId(), state: "pending", label: `${kind === "CHECK_IN" ? "In" : "Out"} · ${d.site_name}`,
    };
    const res = await submitEvent(ev);
    update([...loadQueue(), res]);
    if (res.state === "rejected") toast.err(res.message ?? "Rejected");
    else if (res.state === "pending") toast.err("No network — saved on phone, will sync automatically");
    else toast.ok(kind === "CHECK_IN" ? "Checked in" : "Checked out");
    setBusy(null);
    duties.reload();
  };

  const pending = queue.filter((e) => e.state === "pending").length;
  const list = duties.data ?? [];
  const now = Date.now();
  const current = list.filter((d) => new Date(d.end_at).getTime() > now - 6 * 3600e3);

  return (
    <div className="space-y-4">
      {(!online || pending > 0) && (
        <div className="card flex items-center justify-between border-amber-200 bg-amber-50 text-sm">
          <span className="flex items-center gap-2"><CloudOff size={16} /> {online ? "" : "Offline. "}{pending} punch(es) waiting to sync</span>
          <button className="btn-secondary" onClick={sync} disabled={!online}><RefreshCw size={14} /> Sync now</button>
        </div>
      )}
      {duties.loading && !duties.data ? <Loading /> : !current.length ? <div className="card"><Empty text="No upcoming duties. Contact your supervisor." /></div> : current.map((d) => {
        const inDone = !!d.check_in_at, outDone = !!d.check_out_at;
        return (
          <div key={d.assignment_id} className="card space-y-3">
            <div className="flex items-start justify-between gap-2">
              <div>
                <div className="text-lg font-semibold">{d.site_name}</div>
                <div className="text-sm text-slate-600">{d.post_name} · {d.duty_date}</div>
                <div className="text-sm">{fmtTime(d.start_at)} – {fmtTime(d.end_at)} {d.role !== "REGULAR" && <Badge value={d.role} />}</div>
              </div>
              <Badge value={d.attendance_status ?? d.status} />
            </div>
            {d.instructions && <p className="rounded bg-slate-50 p-2 text-xs text-slate-600">{d.instructions}</p>}
            {d.latitude != null && (
              <a className="flex items-center gap-1 text-xs text-brand underline" target="_blank" rel="noreferrer"
                href={`https://maps.google.com/?q=${d.latitude},${d.longitude}`}><MapPin size={12} /> {d.address || "Open site in maps"} · {d.geofence_policy} {d.geofence_radius_m}m</a>
            )}
            <div className="grid grid-cols-2 gap-3">
              <button className="btn-primary py-4 text-base" disabled={inDone || busy !== null} onClick={() => punch("CHECK_IN", d)}>
                <LogIn size={20} /> {inDone ? `In ${fmtTime(d.check_in_at)}` : busy === d.assignment_id + "CHECK_IN" ? "Locating…" : "Check in"}
              </button>
              <button className="btn-secondary py-4 text-base" disabled={!inDone || outDone || busy !== null} onClick={() => punch("CHECK_OUT", d)}>
                <LogOutIcon size={20} /> {outDone ? `Out ${fmtTime(d.check_out_at)}` : busy === d.assignment_id + "CHECK_OUT" ? "Locating…" : "Check out"}
              </button>
            </div>
          </div>
        );
      })}
      {queue.length > 0 && (
        <div className="card">
          <div className="mb-2 flex items-center justify-between text-sm font-medium">Recent punches on this phone
            <button className="btn-ghost text-xs" onClick={() => update(queue.filter((e) => e.state === "pending"))}>Clear synced</button></div>
          {queue.slice().reverse().slice(0, 10).map((e) => (
            <div key={e.clientEventId} className="flex items-center justify-between border-b py-1 text-xs">
              <span>{e.label} · {fmtDateTime(e.occurredAt)}</span>
              <span className="flex items-center gap-1">
                <Badge value={e.state === "synced" ? "ACCEPTED" : e.state === "pending" ? "PENDING" : e.state === "flagged" ? "FLAGGED" : "REJECTED"} />
                {e.message && <span className="max-w-[160px] truncate text-slate-500" title={e.message}>{e.message}</span>}
              </span>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

function MyAttendance() {
  const [month, setMonth] = useState(thisMonth());
  const { data, loading } = useApi<any[]>(`/v1/me/attendance?month=${month}`);
  const total = (data ?? []).reduce((a, r) => a + Number(r.pay_units), 0);
  return (
    <div className="card p-0">
      <div className="flex items-center justify-between border-b p-3">
        <input type="month" className="input w-auto" value={month} onChange={(e) => setMonth(e.target.value)} aria-label="Month" />
        <span className="text-sm">Duties: <b>{total}</b></span>
      </div>
      {loading ? <Loading /> : (
        <DataTable rows={(data ?? []).map((r, i) => ({ ...r, id: i }))} empty="No attendance this month." columns={[
          { key: "duty_date", label: "Date" }, { key: "site_name", label: "Site" },
          { key: "t", label: "In/Out", render: (r) => `${fmtTime(r.check_in_at)}–${fmtTime(r.check_out_at)}` },
          { key: "status", label: "Status", render: (r) => <Badge value={r.status} /> },
          { key: "approval_status", label: "Approved", render: (r) => <Badge value={r.approval_status} /> },
        ]} />
      )}
    </div>
  );
}

function MyPayslips() {
  const { data, loading } = useApi<any[]>("/v1/me/payslips");
  const [sel, setSel] = useState<string | null>(null);
  return (
    <div className="card p-0">
      {loading ? <Loading /> : (
        <DataTable rows={data ?? []} onRowClick={(r) => setSel(r.id)} empty="No payslips yet." columns={[
          { key: "period_start", label: "Month", render: (r) => String(r.period_start).slice(0, 7) }, { key: "duties", label: "Duties" },
          { key: "gross", label: "Gross", render: (r) => fmtMoney(r.gross) }, { key: "net", label: "Net", render: (r) => <b>{fmtMoney(r.net)}</b> },
          { key: "payment_status", label: "Status", render: (r) => <Badge value={r.payment_status} /> },
        ]} />
      )}
      <Modal open={!!sel} title="Payslip" onClose={() => setSel(null)} wide>{sel && <PayslipView id={sel} />}</Modal>
    </div>
  );
}

function MyLeave() {
  const toast = useToast();
  const { data, loading, reload } = useApi<any[]>("/v1/leaves");
  return (
    <div className="space-y-4">
      <div className="card">
        <div className="mb-2 font-medium">Apply for leave</div>
        <Form initial={{ leaveType: "CASUAL" }} submitLabel="Apply" fields={[
          { name: "leaveType", label: "Type", type: "select", options: opts("CASUAL", "SICK", "EARNED", "UNPAID"), required: true },
          { name: "fromDate", label: "From", type: "date", required: true, half: true }, { name: "toDate", label: "To", type: "date", required: true, half: true },
          { name: "reason", label: "Reason" },
        ]} onSubmit={async (v) => { await api.post("/v1/leaves", v); toast.ok("Leave applied"); reload(); }} />
      </div>
      <div className="card p-0">
        {loading ? <Loading /> : (
          <DataTable rows={data ?? []} empty="No leave requests." columns={[
            { key: "leaveType", label: "Type" }, { key: "fromDate", label: "From" }, { key: "toDate", label: "To" },
            { key: "status", label: "Status", render: (r) => <Badge value={r.status} /> },
          ]} />
        )}
      </div>
    </div>
  );
}

function GuardIncidents() {
  const sites = useApi<any>("/v1/sites?size=200");
  const options = (sites.data?.items ?? []).map((s: any) => ({ value: s.id, label: s.name }));
  return <IncidentList siteOptions={options} compact />;
}
