"use client";
import { useParams } from "next/navigation";
import { useState } from "react";
import { Pencil, Trash2 } from "lucide-react";
import { api } from "@/lib/api";
import { useApi } from "@/lib/hooks";
import { useSession } from "@/lib/session";
import { ActionButton, Badge, ErrorBox, Loading, Modal, PageHeader } from "@/components/ui";
import { DataTable } from "@/components/DataTable";
import { Form, Field } from "@/components/Form";
import { useToast } from "@/components/Toast";
import { opts, useClients, useServiceTypes, useSites } from "@/lib/lookups";
import { fmtMoney } from "@/lib/format";

const MODELS = ["MONTHLY_PER_POST", "PER_SHIFT", "PER_DAY", "PER_HOUR", "PER_HEADCOUNT_MONTH", "LUMPSUM_MONTHLY"];

export default function ContractDetail() {
  const { id } = useParams<{ id: string }>();
  const { can } = useSession();
  const toast = useToast();
  const c = useApi<any>(`/v1/contracts/${id}`);
  const vs = useApi<any[]>(`/v1/contracts/${id}/versions`);
  const clients = useClients(), st = useServiceTypes(), sites = useSites();
  const [rateModal, setRateModal] = useState<{ versionId: string; rate?: any } | null>(null);
  const [verModal, setVerModal] = useState<any | null>(null);
  const [amend, setAmend] = useState(false);

  if (c.loading && !c.data) return <Loading />;
  if (c.error) return <ErrorBox error={c.error} />;
  const k = c.data;
  const reload = () => { c.reload(); vs.reload(); };
  const clientSites = sites.items.filter((s: any) => s.clientId === k.clientId).map((s: any) => ({ value: s.id, label: s.name }));
  const rateFields: Field[] = [
    { name: "siteId", label: "Site", type: "select", options: clientSites, required: true, half: true },
    { name: "serviceTypeId", label: "Service type", type: "select", options: st.options, required: true, half: true },
    { name: "billingModel", label: "Billing model", type: "select", options: opts(...MODELS), required: true, half: true },
    { name: "rate", label: "Rate", type: "number", required: true, half: true, help: "Per post-month, shift, day, hour or person-month" },
    { name: "quantity", label: "Quantity (posts / headcount)", type: "number", half: true },
    { name: "shiftHours", label: "Shift hours", type: "number", half: true },
    { name: "attendanceLinked", label: "Reduce monthly bill for uncovered duties", type: "checkbox" },
    { name: "extraShiftRate", label: "Extra shift rate (client-requested)", type: "number", half: true },
    { name: "otHourlyRate", label: "OT hourly rate (blank = OT not billed)", type: "number", half: true },
    { name: "holidayMultiplier", label: "Holiday multiplier", type: "number", half: true },
    { name: "minimumUnits", label: "Minimum billable units (guarantee)", type: "number", half: true },
    { name: "description", label: "Invoice description" },
  ];

  return (
    <div className="space-y-4">
      <PageHeader title={`${k.contractNo}${k.title ? " · " + k.title : ""}`} subtitle={`${clients.map[k.clientId] ?? ""} · ${k.startDate} → ${k.endDate ?? "open"}`}
        actions={<>
          <Badge value={k.status} />
          {can("CONTRACT_WRITE") && <button className="btn-secondary" onClick={() => setAmend(true)}>Amend (new version)</button>}
          {can("CONTRACT_APPROVE") && k.status === "ACTIVE" && (
            <ActionButton confirm="Suspend this contract?" success="Suspended" onClick={async () => { await api.post(`/v1/contracts/${id}/status`, { status: "SUSPENDED" }); reload(); }}>Suspend</ActionButton>
          )}
          {can("CONTRACT_APPROVE") && ["ACTIVE", "SUSPENDED"].includes(k.status) && (
            <ActionButton className="btn-danger" confirm="Terminate this contract?" success="Terminated"
              onClick={async () => { await api.post(`/v1/contracts/${id}/status`, { status: "TERMINATED", reason: "Terminated from UI" }); reload(); }}>Terminate</ActionButton>
          )}
        </>} />
      {vs.loading && !vs.data && <Loading />}
      {(vs.data ?? []).slice().reverse().map(({ version: v, rates }: any) => (
        <div key={v.id} className="card p-0">
          <div className="flex flex-wrap items-center justify-between gap-2 border-b p-3">
            <div>
              <span className="font-medium">Version {v.versionNo}</span> <Badge value={v.status} />
              <span className="ml-2 text-sm text-slate-500">{v.effectiveFrom} → {v.effectiveTo ?? "open"} · GST {v.gstRatePct}%
                {v.absencePenaltyPerDuty ? ` · penalty ${fmtMoney(v.absencePenaltyPerDuty)}/uncovered duty` : ""}</span>
              {v.notes && <div className="text-xs text-slate-500">{v.notes}</div>}
            </div>
            <div className="flex flex-wrap gap-2">
              {v.status === "DRAFT" && can("CONTRACT_WRITE") && <>
                <button className="btn-secondary" onClick={() => setVerModal(v)}>Edit version</button>
                <button className="btn-secondary" onClick={() => setRateModal({ versionId: v.id })}>Add rate</button>
                <ActionButton className="btn-primary" success="Submitted for approval" onClick={async () => { await api.post(`/v1/contracts/versions/${v.id}/submit`); reload(); }}>Submit</ActionButton>
              </>}
              {v.status === "PENDING_APPROVAL" && can("CONTRACT_APPROVE") && <>
                <ActionButton className="btn-primary" success="Approved"
                  onClick={async () => { await api.post(`/v1/contracts/versions/${v.id}/decision`, { approve: true }, { idem: true }); reload(); }}>Approve</ActionButton>
                <ActionButton success="Rejected"
                  onClick={async () => { await api.post(`/v1/contracts/versions/${v.id}/decision`, { approve: false, note: "Rejected" }, { idem: true }); reload(); }}>Reject</ActionButton>
              </>}
            </div>
          </div>
          <DataTable rows={rates} empty="No rate lines yet." columns={[
            { key: "siteId", label: "Site", render: (r) => sites.map[r.siteId] },
            { key: "serviceTypeId", label: "Service", render: (r) => st.map[r.serviceTypeId] },
            { key: "billingModel", label: "Model" }, { key: "rate", label: "Rate", render: (r) => fmtMoney(r.rate) },
            { key: "quantity", label: "Qty" }, { key: "attendanceLinked", label: "Attendance-linked" },
            { key: "extraShiftRate", label: "Extra shift" }, { key: "otHourlyRate", label: "OT/hr" }, { key: "minimumUnits", label: "Minimum" },
            {
              key: "a", label: "", render: (r) => v.status === "DRAFT" && can("CONTRACT_WRITE") && (
                <div className="flex gap-1">
                  <button className="btn-ghost p-1" aria-label="Edit rate" onClick={() => setRateModal({ versionId: v.id, rate: r })}><Pencil size={14} /></button>
                  <ActionButton className="btn-ghost p-1" confirm="Delete this rate line?" success="Deleted"
                    onClick={async () => { await api.del(`/v1/contracts/rates/${r.id}`); reload(); }}><Trash2 size={14} /></ActionButton>
                </div>
              ),
            },
          ]} />
        </div>
      ))}

      <Modal open={!!rateModal} title={rateModal?.rate ? "Edit rate" : "Add rate"} onClose={() => setRateModal(null)} wide>
        {rateModal && (
          <Form fields={rateFields} initial={rateModal.rate ?? { billingModel: "MONTHLY_PER_POST", quantity: 1, shiftHours: 8, attendanceLinked: true, holidayMultiplier: 1 }}
            onCancel={() => setRateModal(null)}
            onSubmit={async (v) => {
              if (rateModal.rate) await api.patch(`/v1/contracts/rates/${rateModal.rate.id}`, v);
              else await api.post(`/v1/contracts/versions/${rateModal.versionId}/rates`, v);
              toast.ok("Rate saved"); setRateModal(null); reload();
            }} />
        )}
      </Modal>
      <Modal open={!!verModal} title="Edit draft version" onClose={() => setVerModal(null)}>
        {verModal && (
          <Form initial={verModal} fields={[
            { name: "effectiveFrom", label: "Effective from", type: "date", required: true, half: true },
            { name: "effectiveTo", label: "Effective to", type: "date", half: true },
            { name: "gstRatePct", label: "GST %", type: "number", half: true },
            { name: "absencePenaltyPerDuty", label: "Penalty per uncovered duty", type: "number", half: true },
            { name: "notes", label: "Notes", type: "textarea" },
          ]} onCancel={() => setVerModal(null)}
            onSubmit={async (v) => { await api.patch(`/v1/contracts/versions/${verModal.id}`, v); toast.ok("Saved"); setVerModal(null); reload(); }} />
        )}
      </Modal>
      <Modal open={amend} title="Amend contract" onClose={() => setAmend(false)}>
        <p className="mb-3 text-sm text-slate-600">Creates a new draft version copying the latest rates. Once approved, the previous version is end-dated the day before — closed periods keep their old rates.</p>
        <Form fields={[{ name: "effectiveFrom", label: "Effective from", type: "date", required: true }, { name: "notes", label: "Reason / notes" }]}
          onCancel={() => setAmend(false)}
          onSubmit={async (v) => { await api.post(`/v1/contracts/${id}/versions`, v); toast.ok("Draft version created"); setAmend(false); reload(); }} />
      </Modal>
    </div>
  );
}
