"use client";
import { useState } from "react";
import { api, qs } from "@/lib/api";
import { useApi } from "@/lib/hooks";
import { useSession } from "@/lib/session";
import { Badge, ErrorBox, Loading, Modal, PageHeader } from "@/components/ui";
import { DataTable } from "@/components/DataTable";
import { Form } from "@/components/Form";
import { InvoiceDetail } from "@/components/InvoiceDetail";
import { useToast } from "@/components/Toast";
import { opts, useClients } from "@/lib/lookups";
import { fmtMoney } from "@/lib/format";

export default function InvoicesPage() {
  const { can } = useSession();
  const toast = useToast();
  const clients = useClients();
  const [f, setF] = useState({ clientId: "", status: "", month: "" });
  const { data, loading, error, reload } = useApi<any[]>("/v1/invoices" + qs(f));
  const [gen, setGen] = useState(false);
  const [sel, setSel] = useState<string | null>(null);
  return (
    <div>
      <PageHeader title="Invoices" subtitle="Generated from contracts + approved attendance. Billable units come from the contract, not from payroll days."
        actions={can("BILLING_WRITE") && <button className="btn-primary" onClick={() => setGen(true)}>Generate invoice</button>} />
      <div className="card p-0">
        <div className="flex flex-wrap gap-2 border-b p-3">
          <select className="input w-auto" value={f.clientId} onChange={(e) => setF({ ...f, clientId: e.target.value })} aria-label="Client">
            <option value="">All clients</option>{clients.options.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
          </select>
          <select className="input w-auto" value={f.status} onChange={(e) => setF({ ...f, status: e.target.value })} aria-label="Status">
            <option value="">Any status</option>
            {opts("DRAFT", "REVIEWED", "APPROVED", "ISSUED", "PARTIALLY_PAID", "PAID", "OVERDUE", "DISPUTED", "CANCELLED").map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
          </select>
          <input type="month" className="input w-auto" value={f.month} onChange={(e) => setF({ ...f, month: e.target.value })} aria-label="Month" />
        </div>
        {error && <div className="p-3"><ErrorBox error={error} /></div>}
        {loading && !data ? <Loading /> : (
          <DataTable rows={data ?? []} onRowClick={(r) => setSel(r.id)} empty="No invoices." columns={[
            { key: "invoice_no", label: "Number", render: (r) => r.invoice_no ?? <span className="text-slate-400">draft v{r.version_no}</span> },
            { key: "kind", label: "Kind", render: (r) => r.kind !== "INVOICE" ? <Badge value={r.kind} /> : "Invoice" },
            { key: "client_name", label: "Client" }, { key: "period_start", label: "Period", render: (r) => String(r.period_start ?? "").slice(0, 7) },
            { key: "issue_date", label: "Issued" }, { key: "due_date", label: "Due" },
            { key: "total", label: "Total", render: (r) => fmtMoney(r.total) }, { key: "balance", label: "Balance", render: (r) => fmtMoney(r.balance) },
            { key: "status", label: "Status", render: (r) => <Badge value={r.status} /> },
          ]} />
        )}
      </div>
      <Modal open={gen} title="Generate invoice" onClose={() => setGen(false)}>
        <p className="mb-3 text-sm text-slate-600">Re-generating supersedes an unissued draft. If already issued, a supplementary invoice for late-approved duties is created.</p>
        <Form fields={[
          { name: "clientId", label: "Client", type: "select", options: clients.options, required: true },
          { name: "month", label: "Month (YYYY-MM)", required: true, placeholder: "2026-10" },
        ]} onCancel={() => setGen(false)} submitLabel="Generate"
          onSubmit={async (v) => {
            const r = await api.post("/v1/invoices/generate", v, { idem: true });
            toast.ok("Draft invoice generated");
            r.warnings?.forEach((w: string) => toast.err(w));
            setGen(false); reload(); setSel(r.invoice.id);
          }} />
      </Modal>
      <Modal open={!!sel} title="Invoice" onClose={() => setSel(null)} wide>{sel && <InvoiceDetail id={sel} onChanged={reload} />}</Modal>
    </div>
  );
}
