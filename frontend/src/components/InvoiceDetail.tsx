"use client";
import { useState } from "react";
import { Trash2 } from "lucide-react";
import { api, openHtml } from "@/lib/api";
import { useApi } from "@/lib/hooks";
import { useSession } from "@/lib/session";
import { ActionButton, Badge, ErrorBox, Loading, Modal } from "./ui";
import { Form } from "./Form";
import { useToast } from "./Toast";
import { fmtMoney, fmtNum } from "@/lib/format";

export function InvoiceDetail({ id, onChanged }: { id: string; onChanged?: () => void }) {
  const { can } = useSession();
  const toast = useToast();
  const { data, loading, error, reload } = useApi<any>(`/v1/invoices/${id}`);
  const [modal, setModal] = useState<null | "line" | "credit" | "dispute">(null);
  if (loading && !data) return <Loading />;
  if (error) return <ErrorBox error={error} />;
  const inv = data.invoice;
  const refresh = () => { reload(); onChanged?.(); };
  const act = (a: string, body: any = {}) => async () => { await api.post(`/v1/invoices/${id}/${a}`, body, { idem: true }); refresh(); };
  const draft = inv.status === "DRAFT";

  return (
    <div className="space-y-3 text-sm">
      <div className="flex flex-wrap items-start justify-between gap-2">
        <div>
          <div className="text-lg font-semibold">{inv.invoiceNo ?? "Draft"} {inv.kind !== "INVOICE" && <Badge value={inv.kind} />}</div>
          <div className="text-slate-500">{data.client.legal_name} · GSTIN {data.client.gstin ?? "-"} · {inv.periodStart} to {inv.periodEnd} · v{inv.versionNo}</div>
          {inv.notes && <div className="text-xs text-slate-500">{inv.notes}</div>}
          {inv.disputeReason && <div className="text-xs text-red-600">Dispute: {inv.disputeReason}</div>}
        </div>
        <Badge value={inv.status} />
      </div>
      <div className="flex flex-wrap gap-2">
        <ActionButton onClick={() => openHtml(`/v1/invoices/${id}/print`)}>Print / PDF</ActionButton>
        {can("BILLING_WRITE") && draft && <>
          <button className="btn-secondary" onClick={() => setModal("line")}>Add manual line</button>
          <ActionButton className="btn-primary" success="Reviewed" onClick={act("review")}>Mark reviewed</ActionButton>
        </>}
        {can("BILLING_APPROVE") && inv.status === "REVIEWED" && <ActionButton className="btn-primary" success="Approved" onClick={act("approve")}>Approve</ActionButton>}
        {can("BILLING_APPROVE") && ["REVIEWED", "APPROVED"].includes(inv.status) && <ActionButton success="Back to draft" onClick={act("reject")}>Reject</ActionButton>}
        {can("BILLING_APPROVE") && inv.status === "APPROVED" && (
          <ActionButton className="btn-primary" confirm="Issue invoice? It gets a legal number and can only be corrected by credit note." success="Issued" onClick={act("issue")}>Issue</ActionButton>
        )}
        {can("BILLING_WRITE") && ["DRAFT", "REVIEWED", "APPROVED"].includes(inv.status) && <ActionButton className="btn-danger" confirm="Cancel this draft?" success="Cancelled" onClick={act("cancel")}>Cancel</ActionButton>}
        {can("BILLING_WRITE") && inv.kind === "INVOICE" && ["ISSUED", "PARTIALLY_PAID", "PAID", "OVERDUE", "DISPUTED"].includes(inv.status) &&
          <button className="btn-secondary" onClick={() => setModal("credit")}>Credit note</button>}
        {can("BILLING_WRITE") && ["ISSUED", "PARTIALLY_PAID", "OVERDUE"].includes(inv.status) && <button className="btn-secondary" onClick={() => setModal("dispute")}>Mark disputed</button>}
        {can("BILLING_WRITE") && inv.status === "DISPUTED" && <ActionButton success="Dispute resolved" onClick={act("resolve-dispute")}>Resolve dispute</ActionButton>}
      </div>
      <div className="overflow-x-auto">
        <table className="table">
          <thead><tr><th>Site</th><th>Description</th><th className="text-right">Qty</th><th className="text-right">Rate</th><th className="text-right">Amount</th><th /></tr></thead>
          <tbody>
            {data.lines.map((l: any) => (
              <tr key={l.id} className={Number(l.amount) < 0 ? "text-red-700" : ""}>
                <td>{l.site_name ?? "-"}</td><td>{l.description} {l.line_type !== "SERVICE" && <Badge value={l.line_type} />}</td>
                <td className="text-right">{fmtNum(l.quantity, 4)} {l.unit}</td><td className="text-right">{fmtNum(l.rate)}</td>
                <td className="text-right">{fmtMoney(l.amount)}</td>
                <td>{draft && can("BILLING_WRITE") && (
                  <ActionButton className="btn-ghost p-1" confirm="Remove line?" onClick={async () => { await api.del(`/v1/invoices/${id}/lines/${l.id}`); refresh(); }}><Trash2 size={14} /></ActionButton>
                )}</td>
              </tr>
            ))}
            {!data.lines.length && <tr><td colSpan={6} className="text-slate-500">No lines.</td></tr>}
          </tbody>
          <tfoot>
            {[["Taxable value", inv.subtotal], ["CGST", inv.cgst], ["SGST", inv.sgst], ["IGST", inv.igst], ["Total", inv.total], ["Received / credited", inv.amountPaid],
              ["Balance", Number(inv.total) - Number(inv.amountPaid)]].map(([k, v]) => (
              <tr key={k as string}><td colSpan={4} className="text-right font-medium">{k}</td><td className="text-right font-medium">{fmtMoney(v)}</td><td /></tr>
            ))}
          </tfoot>
        </table>
      </div>
      {data.payments?.length > 0 && (
        <div><div className="mb-1 font-medium">Payments</div>
          {data.payments.map((p: any) => <div key={p.id} className="text-xs">{p.received_on} · {p.external_ref} · {fmtMoney(p.amount)}</div>)}
        </div>
      )}
      <Modal open={modal === "line"} title="Manual line" onClose={() => setModal(null)}>
        <Form initial={{ quantity: 1 }} fields={[
          { name: "description", label: "Description", required: true }, { name: "quantity", label: "Quantity", type: "number", required: true, half: true },
          { name: "unit", label: "Unit", half: true }, { name: "rate", label: "Rate (negative for discount)", type: "number", required: true, half: true },
        ]} onCancel={() => setModal(null)}
          onSubmit={async (v) => { await api.post(`/v1/invoices/${id}/lines`, v); toast.ok("Line added"); setModal(null); refresh(); }} />
      </Modal>
      <Modal open={modal === "credit"} title="Credit note" onClose={() => setModal(null)}>
        <p className="mb-3 text-slate-600">Creates a draft credit note (taxable amount; GST is added) that goes through review/approve/issue and reduces this invoice's balance.</p>
        <Form fields={[{ name: "amount", label: "Taxable amount", type: "number", required: true }, { name: "reason", label: "Reason", required: true }]}
          onCancel={() => setModal(null)}
          onSubmit={async (v) => { await api.post(`/v1/invoices/${id}/credit-notes`, v, { idem: true }); toast.ok("Credit note drafted — see invoice list"); setModal(null); refresh(); }} />
      </Modal>
      <Modal open={modal === "dispute"} title="Mark disputed" onClose={() => setModal(null)}>
        <Form fields={[{ name: "reason", label: "Client's dispute reason", type: "textarea", required: true }]} onCancel={() => setModal(null)}
          onSubmit={async (v) => { await act("dispute", v)(); toast.ok("Marked disputed"); setModal(null); }} />
      </Modal>
    </div>
  );
}
