"use client";
import { useState } from "react";
import { api, qs } from "@/lib/api";
import { useApi } from "@/lib/hooks";
import { useSession } from "@/lib/session";
import { ActionButton, Badge, Loading, Modal, PageHeader } from "@/components/ui";
import { DataTable } from "@/components/DataTable";
import { Form } from "@/components/Form";
import { useToast } from "@/components/Toast";
import { opts, useEmployees } from "@/lib/lookups";

export default function LeavesPage() {
  const { can } = useSession();
  const toast = useToast();
  const emps = useEmployees();
  const [status, setStatus] = useState("PENDING");
  const { data, loading, reload } = useApi<any[]>("/v1/leaves" + qs({ status }));
  const [open, setOpen] = useState(false);
  return (
    <div>
      <PageHeader title="Leaves" subtitle="Approving leave releases the guard's rostered duties so the shifts show as open for replacement."
        actions={<>
          <select className="input w-auto" value={status} onChange={(e) => setStatus(e.target.value)} aria-label="Status">
            <option value="">All</option>{["PENDING", "APPROVED", "REJECTED"].map((s) => <option key={s}>{s}</option>)}
          </select>
          <button className="btn-primary" onClick={() => setOpen(true)}>Record leave</button>
        </>} />
      <div className="card p-0">
        {loading ? <Loading /> : (
          <DataTable rows={data ?? []} empty="No leave requests." columns={[
            { key: "employeeId", label: "Employee", render: (r) => emps.map[r.employeeId] },
            { key: "leaveType", label: "Type" }, { key: "fromDate", label: "From" }, { key: "toDate", label: "To" }, { key: "reason", label: "Reason" },
            { key: "status", label: "Status", render: (r) => <Badge value={r.status} /> },
            {
              key: "a", label: "", render: (r) => r.status === "PENDING" && can("LEAVE_APPROVE") && (
                <div className="flex gap-1">
                  <ActionButton className="btn-primary" onClick={async () => {
                    const x = await api.post(`/v1/leaves/${r.id}/decision`, { approve: true });
                    toast.ok(`Approved; ${x.releasedDuties} duty(ies) released`); reload();
                  }}>Approve</ActionButton>
                  <ActionButton success="Rejected" onClick={async () => { await api.post(`/v1/leaves/${r.id}/decision`, { approve: false }); reload(); }}>Reject</ActionButton>
                </div>
              ),
            },
          ]} />
        )}
      </div>
      <Modal open={open} title="Record leave" onClose={() => setOpen(false)}>
        <Form initial={{ leaveType: "CASUAL" }} fields={[
          { name: "employeeId", label: "Employee", type: "select", options: emps.options, required: true },
          { name: "leaveType", label: "Type", type: "select", options: opts("CASUAL", "SICK", "EARNED", "UNPAID"), required: true, half: true },
          { name: "fromDate", label: "From", type: "date", required: true, half: true }, { name: "toDate", label: "To", type: "date", required: true, half: true },
          { name: "reason", label: "Reason" },
        ]} onCancel={() => setOpen(false)}
          onSubmit={async (v) => { await api.post("/v1/leaves", v); toast.ok("Leave recorded"); setOpen(false); reload(); }} />
      </Modal>
    </div>
  );
}
