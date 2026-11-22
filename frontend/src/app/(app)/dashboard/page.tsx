"use client";
import Link from "next/link";
import { useState } from "react";
import { useApi } from "@/lib/hooks";
import { ErrorBox, Loading, PageHeader, Stat } from "@/components/ui";
import { fmtMoney, fmtDateTime, today } from "@/lib/format";

export default function Dashboard() {
  const [date, setDate] = useState(today());
  const { data: d, error, loading, reload } = useApi<any>(`/v1/reports/dashboard?date=${date}`);
  return (
    <div>
      <PageHeader title="Dashboard" subtitle={d ? `Data as of ${fmtDateTime(d.generatedAt)}` : undefined}
        actions={<input type="date" className="input w-auto" value={date} onChange={(e) => setDate(e.target.value)} aria-label="Date" />} />
      {error && <ErrorBox error={error} onRetry={reload} />}
      {loading && !d ? <Loading /> : d && (
        <div className="space-y-4">
          <div className="grid grid-cols-2 gap-3 md:grid-cols-4">
            <Stat label="Fill rate" value={d.fillRatePct == null ? "-" : `${d.fillRatePct}%`} hint={`${d.roster.required_slots} slots required`}
              tone={d.fillRatePct != null && d.fillRatePct < 95 ? "red" : "green"} />
            <Stat label="Uncovered shifts" value={d.roster.uncovered} tone={d.roster.uncovered > 0 ? "red" : undefined} hint={`${d.roster.shifts} shifts`} />
            <Stat label="Checked in" value={d.attendance.checked_in} hint={`${d.attendance.late} late · ${d.attendance.absent} absent`} />
            <Stat label="Flagged attendance" value={d.attendance.flagged} tone={d.attendance.flagged > 0 ? "amber" : undefined}
              hint={`${d.attendance.pending_approval} pending approval`} />
          </div>
          <div className="grid grid-cols-2 gap-3 md:grid-cols-4">
            <Stat label="Active employees" value={d.activeEmployees} />
            <Stat label="Open incidents" value={d.openIncidents} tone={d.openIncidents > 0 ? "red" : undefined} />
            <Stat label="Pending adjustments / leaves" value={`${d.pendingAdjustments} / ${d.pendingLeaves}`} />
            <Stat label="Docs expiring (30d)" value={d.expiringDocuments30d} tone={d.expiringDocuments30d > 0 ? "amber" : undefined} />
          </div>
          {d.receivables && (
            <div className="grid grid-cols-2 gap-3 md:grid-cols-4">
              <Stat label="Receivables" value={fmtMoney(d.receivables.outstanding)} />
              <Stat label="Overdue" value={fmtMoney(d.receivables.overdue)} tone={Number(d.receivables.overdue) > 0 ? "red" : undefined} />
            </div>
          )}
          <div className="card text-sm">
            <div className="mb-2 font-medium">Quick links</div>
            <div className="flex flex-wrap gap-2">
              <Link className="btn-secondary" href="/roster">Roster board</Link>
              <Link className="btn-secondary" href="/attendance">Approve attendance</Link>
              <Link className="btn-secondary" href="/incidents">Incidents</Link>
              <Link className="btn-secondary" href="/employees?tab=expiring">Expiring documents</Link>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
