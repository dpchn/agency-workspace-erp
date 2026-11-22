"use client";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { api } from "@/lib/api";
import { useApi } from "@/lib/hooks";
import { useSession } from "@/lib/session";
import { Badge, ErrorBox, Loading, Modal, PageHeader } from "@/components/ui";
import { DataTable } from "@/components/DataTable";
import { Form } from "@/components/Form";
import { useToast } from "@/components/Toast";
import { useBranches } from "@/lib/lookups";
import { fmtMoney, thisMonth } from "@/lib/format";

export default function PayrollPage() {
  const { can } = useSession();
  const router = useRouter();
  const toast = useToast();
  const branches = useBranches();
  const [all, setAll] = useState(false);
  const { data, loading, error, reload } = useApi<any[]>(`/v1/payroll/runs?includeSuperseded=${all}`);
  const [open, setOpen] = useState(false);
  return (
    <div>
      <PageHeader title="Payroll" subtitle="Each approved duty is valued at the pay rate of that site on that date. Draft → Reviewed → Approved (by someone else) → Locked → Paid."
        actions={<>
          <label className="flex items-center gap-1 text-sm"><input type="checkbox" checked={all} onChange={(e) => setAll(e.target.checked)} /> Show superseded</label>
          {can("PAYROLL_RUN") && <button className="btn-primary" onClick={() => setOpen(true)}>Run payroll</button>}
        </>} />
      {error && <ErrorBox error={error} />}
      <div className="card p-0">
        {loading && !data ? <Loading /> : (
          <DataTable rows={data ?? []} onRowClick={(r) => router.push(`/payroll/${r.id}`)} empty="No payroll runs yet." columns={[
            { key: "periodStart", label: "Month", render: (r) => String(r.periodStart).slice(0, 7) },
            { key: "branchId", label: "Branch", render: (r) => (r.branchId ? branches.map[r.branchId] : "All") },
            { key: "versionNo", label: "Ver" }, { key: "status", label: "Status", render: (r) => <Badge value={r.status} /> },
            { key: "employeeCount", label: "Employees" }, { key: "totalGross", label: "Gross", render: (r) => fmtMoney(r.totalGross) },
            { key: "totalNet", label: "Net", render: (r) => fmtMoney(r.totalNet) }, { key: "totalEmployerCost", label: "Employer cost", render: (r) => fmtMoney(r.totalEmployerCost) },
            { key: "exceptions", label: "Exceptions", render: (r) => (r.exceptions?.length ? <Badge value={`${r.exceptions.length}_ISSUES`} /> : "-") },
          ]} />
        )}
      </div>
      <Modal open={open} title="Run payroll" onClose={() => setOpen(false)}>
        <p className="mb-3 text-sm text-slate-600">Uses approved attendance, paid leave and adjustments. Late approvals for already-locked months are included as arrears.</p>
        <Form initial={{ month: thisMonth() }} fields={[
          { name: "month", label: "Month (YYYY-MM)", required: true, placeholder: "2026-10" },
          { name: "branchId", label: "Branch (blank = all)", type: "select", options: branches.options },
        ]} onCancel={() => setOpen(false)} submitLabel="Calculate"
          onSubmit={async (v) => { const r = await api.post("/v1/payroll/runs", v, { idem: true }); toast.ok("Payroll calculated"); setOpen(false); router.push(`/payroll/${r.id}`); }} />
      </Modal>
    </div>
  );
}
