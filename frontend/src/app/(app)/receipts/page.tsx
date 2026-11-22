"use client";
import { useState } from "react";
import { api, uuid } from "@/lib/api";
import { useApi } from "@/lib/hooks";
import { useSession } from "@/lib/session";
import { Loading, Modal, PageHeader, Tabs } from "@/components/ui";
import { DataTable } from "@/components/DataTable";
import { Form } from "@/components/Form";
import { useToast } from "@/components/Toast";
import { opts, useClients } from "@/lib/lookups";
import { fmtMoney, today } from "@/lib/format";
import { StatementTable } from "@/components/StatementTable";

export default function ReceiptsPage() {
  const [tab, setTab] = useState("receipts");
  return (
    <div>
      <Tabs active={tab} onChange={setTab} tabs={[{ key: "receipts", label: "Receipts" }, { key: "aging", label: "Receivables aging" }, { key: "statement", label: "Client statement" }]} />
      {tab === "receipts" && <Receipts />}
      {tab === "aging" && <Aging />}
      {tab === "statement" && <Statement />}
    </div>
  );
}

function Receipts() {
  const { can } = useSession();
  const toast = useToast();
  const clients = useClients();
  const { data, loading, reload } = useApi<any[]>("/v1/receipts");
  const [open, setOpen] = useState(false);
  const [key, setKey] = useState(uuid());
  return (
    <div>
      <PageHeader title="Receipts" subtitle="Payments are idempotent on the bank reference; without explicit allocation they settle the oldest due invoices first."
        actions={can("RECEIPT_WRITE") && <button className="btn-primary" onClick={() => { setKey(uuid()); setOpen(true); }}>Record receipt</button>} />
      <div className="card p-0">
        {loading ? <Loading /> : (
          <DataTable rows={data ?? []} empty="No receipts." columns={[
            { key: "receivedOn", label: "Date" }, { key: "clientId", label: "Client", render: (r) => clients.map[r.clientId] },
            { key: "externalRef", label: "Reference" }, { key: "mode", label: "Mode" },
            { key: "amount", label: "Amount", render: (r) => fmtMoney(r.amount) }, { key: "tdsAmount", label: "TDS", render: (r) => fmtMoney(r.tdsAmount) },
            { key: "unallocated", label: "Unallocated", render: (r) => fmtMoney(r.unallocated) }, { key: "notes", label: "Notes" },
          ]} />
        )}
      </div>
      <Modal open={open} title="Record receipt" onClose={() => setOpen(false)}>
        <Form initial={{ receivedOn: today(), mode: "BANK_TRANSFER", tdsAmount: 0 }} fields={[
          { name: "clientId", label: "Client", type: "select", options: clients.options, required: true },
          { name: "amount", label: "Amount received", type: "number", required: true, half: true },
          { name: "tdsAmount", label: "TDS deducted by client", type: "number", half: true },
          { name: "receivedOn", label: "Received on", type: "date", required: true, half: true },
          { name: "mode", label: "Mode", type: "select", options: opts("BANK_TRANSFER", "CHEQUE", "UPI", "CASH"), half: true },
          { name: "externalRef", label: "UTR / cheque no", required: true }, { name: "notes", label: "Notes" },
        ]} onCancel={() => setOpen(false)}
          onSubmit={async (v) => { await api.post("/v1/receipts", v, { idem: key }); toast.ok("Receipt recorded"); setOpen(false); reload(); }} />
      </Modal>
    </div>
  );
}

function Aging() {
  const { data, loading } = useApi<any[]>("/v1/reports/aging");
  return (
    <div>
      <PageHeader title="Receivables aging" />
      <div className="card p-0">
        {loading ? <Loading /> : (
          <DataTable rowKey="client_id" rows={data ?? []} empty="Nothing outstanding." columns={[
            { key: "client_name", label: "Client" }, { key: "not_due", label: "Not due", render: (r) => fmtMoney(r.not_due) },
            { key: "d1_30", label: "1–30", render: (r) => fmtMoney(r.d1_30) }, { key: "d31_60", label: "31–60", render: (r) => fmtMoney(r.d31_60) },
            { key: "d61_90", label: "61–90", render: (r) => fmtMoney(r.d61_90) }, { key: "d90_plus", label: "90+", render: (r) => fmtMoney(r.d90_plus) },
            { key: "total_outstanding", label: "Total", render: (r) => <b>{fmtMoney(r.total_outstanding)}</b> },
          ]} />
        )}
      </div>
    </div>
  );
}

function Statement() {
  const clients = useClients();
  const [clientId, setClientId] = useState("");
  return (
    <div>
      <PageHeader title="Client statement" actions={
        <select className="input w-auto" value={clientId} onChange={(e) => setClientId(e.target.value)} aria-label="Client">
          <option value="">Select client</option>{clients.options.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
        </select>} />
      <div className="card p-0"><StatementTable clientId={clientId} /></div>
    </div>
  );
}
