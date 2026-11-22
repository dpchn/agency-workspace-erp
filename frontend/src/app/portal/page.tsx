"use client";
import { useState } from "react";
import { MiniShell } from "@/components/MiniShell";
import { useApi } from "@/lib/hooks";
import { useSession } from "@/lib/session";
import { Badge, Loading, Modal, Stat, Tabs, PageHeader } from "@/components/ui";
import { DataTable } from "@/components/DataTable";
import { InvoiceDetail } from "@/components/InvoiceDetail";
import { IncidentList } from "@/components/IncidentList";
import { StatementTable } from "@/components/StatementTable";
import { fmtMoney, today } from "@/lib/format";

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
        { key: "summary", label: "Today" }, { key: "invoices", label: "Invoices" }, { key: "statement", label: "Statement" }, { key: "incidents", label: "Incidents" },
      ]} />
      {tab === "summary" && <Summary />}
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
