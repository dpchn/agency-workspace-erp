"use client";
import { useState } from "react";
import { qs } from "@/lib/api";
import { useApi } from "@/lib/hooks";
import { useSession } from "@/lib/session";
import { ErrorBox, Loading, PageHeader, Stat, Tabs } from "@/components/ui";
import { DataTable } from "@/components/DataTable";
import { useEmployees } from "@/lib/lookups";
import { fmtDateTime, fmtMoney, fmtNum, thisMonth } from "@/lib/format";

export default function ReportsPage() {
  const { can } = useSession();
  const [tab, setTab] = useState("margin");
  return (
    <div>
      <Tabs active={tab} onChange={setTab} tabs={[
        { key: "margin", label: "Site / client margin" }, { key: "trail", label: "Employee deployment trail" },
        ...(can("AUDIT_READ") ? [{ key: "audit", label: "Audit log" }] : []),
      ]} />
      {tab === "margin" && <Margin />}
      {tab === "trail" && <Trail />}
      {tab === "audit" && <Audit />}
    </div>
  );
}

function Margin() {
  const [month, setMonth] = useState(thisMonth());
  const { data, loading, error } = useApi<any>(`/v1/reports/site-margin?month=${month}`);
  const rows: any[] = data?.rows ?? [];
  const tot = rows.reduce((a, r) => ({ rev: a.rev + Number(r.revenue), cost: a.cost + Number(r.wages) + Number(r.employer_cost), con: a.con + Number(r.contribution) }), { rev: 0, cost: 0, con: 0 });
  return (
    <div>
      <PageHeader title="Site margin" subtitle={data ? `${data.note} Generated ${fmtDateTime(data.generatedAt)}` : undefined}
        actions={<input type="month" className="input w-auto" value={month} onChange={(e) => setMonth(e.target.value)} aria-label="Month" />} />
      {error && <ErrorBox error={error} />}
      <div className="mb-3 grid grid-cols-3 gap-3">
        <Stat label="Revenue" value={fmtMoney(tot.rev)} /><Stat label="Direct cost" value={fmtMoney(tot.cost)} />
        <Stat label="Contribution" value={fmtMoney(tot.con)} hint={tot.rev ? `${((tot.con / tot.rev) * 100).toFixed(1)}% margin` : undefined} tone={tot.con < 0 ? "red" : "green"} />
      </div>
      <div className="card p-0">
        {loading ? <Loading /> : (
          <DataTable rowKey="site_id" rows={rows} empty="No invoices or payroll for this month." columns={[
            { key: "site_name", label: "Site" }, { key: "client_name", label: "Client" },
            { key: "revenue", label: "Revenue", render: (r) => fmtMoney(r.revenue) }, { key: "paid_duties", label: "Paid duties", render: (r) => fmtNum(r.paid_duties) },
            { key: "wages", label: "Wages", render: (r) => fmtMoney(r.wages) }, { key: "employer_cost", label: "Employer cost", render: (r) => fmtMoney(r.employer_cost) },
            { key: "contribution", label: "Contribution", render: (r) => <span className={Number(r.contribution) < 0 ? "text-red-600" : ""}>{fmtMoney(r.contribution)}</span> },
            { key: "margin_pct", label: "Margin %", render: (r) => (r.margin_pct == null ? "-" : `${r.margin_pct}%`) },
          ]} />
        )}
      </div>
    </div>
  );
}

function Trail() {
  const emps = useEmployees();
  const [month, setMonth] = useState(thisMonth());
  const [employeeId, setEmployeeId] = useState("");
  const { data, loading } = useApi<any[]>("/v1/reports/employee-deployment-trail" + qs({ month, employeeId }));
  return (
    <div>
      <PageHeader title="Deployment trail" subtitle="Where each person worked in the month — paid duties vs billed units per site."
        actions={<>
          <input type="month" className="input w-auto" value={month} onChange={(e) => setMonth(e.target.value)} aria-label="Month" />
          <select className="input w-auto" value={employeeId} onChange={(e) => setEmployeeId(e.target.value)} aria-label="Employee">
            <option value="">All employees</option>{emps.options.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
          </select>
        </>} />
      <div className="card p-0">
        {loading ? <Loading /> : (
          <DataTable rows={(data ?? []).map((r, i) => ({ ...r, id: i }))} empty="No approved duties." columns={[
            { key: "employee_code", label: "Code" }, { key: "employee_name", label: "Name" }, { key: "site_name", label: "Site" }, { key: "client_name", label: "Client" },
            { key: "service_type", label: "Service" }, { key: "first_day", label: "First" }, { key: "last_day", label: "Last" },
            { key: "paid_duties", label: "Paid duties", render: (r) => fmtNum(r.paid_duties) }, { key: "billed_units", label: "Billed units", render: (r) => fmtNum(r.billed_units) },
            { key: "as_replacement", label: "Replacements" }, { key: "double_duties", label: "Double duties" },
          ]} />
        )}
      </div>
    </div>
  );
}

function Audit() {
  const { data, loading } = useApi<any[]>("/v1/reports/audit?limit=300");
  return (
    <div>
      <PageHeader title="Audit log" subtitle="Append-only trail of who changed what." />
      <div className="card p-0">
        {loading ? <Loading /> : (
          <DataTable rows={(data ?? []).map((r, i) => ({ ...r, id: i }))} columns={[
            { key: "created_at", label: "When", render: (r) => fmtDateTime(r.created_at) }, { key: "actor", label: "Actor" },
            { key: "action", label: "Action" }, { key: "entity_type", label: "Entity" },
            { key: "details", label: "Details", render: (r) => <code className="text-[11px]">{r.details ? JSON.stringify(r.details).slice(0, 140) : ""}</code> },
          ]} />
        )}
      </div>
    </div>
  );
}
