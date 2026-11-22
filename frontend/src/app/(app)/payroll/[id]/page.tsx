"use client";
import { useParams, useRouter } from "next/navigation";
import { useState } from "react";
import { api, download } from "@/lib/api";
import { useApi } from "@/lib/hooks";
import { useSession } from "@/lib/session";
import { ActionButton, Badge, ErrorBox, Loading, Modal, PageHeader, Stat } from "@/components/ui";
import { DataTable } from "@/components/DataTable";
import { PayslipView } from "@/components/PayslipView";
import { fmtMoney, fmtNum } from "@/lib/format";

export default function PayrollRunPage() {
  const { id } = useParams<{ id: string }>();
  const router = useRouter();
  const { can } = useSession();
  const run = useApi<any>(`/v1/payroll/runs/${id}`);
  const slips = useApi<any[]>(`/v1/payroll/runs/${id}/payslips`);
  const [slip, setSlip] = useState<string | null>(null);
  if (run.loading && !run.data) return <Loading />;
  if (run.error) return <ErrorBox error={run.error} />;
  const r = run.data;
  const act = (a: string) => async () => { await api.post(`/v1/payroll/runs/${id}/${a}`, {}, { idem: true }); run.reload(); slips.reload(); };

  return (
    <div className="space-y-4">
      <PageHeader title={`Payroll ${String(r.periodStart).slice(0, 7)} · v${r.versionNo}`} subtitle={`${r.periodStart} to ${r.periodEnd}`}
        actions={<>
          <Badge value={r.status} />
          {["DRAFT", "REVIEWED"].includes(r.status) && can("PAYROLL_RUN") && (
            <ActionButton success="Recalculated as new version" onClick={async () => {
              const n = await api.post(`/v1/payroll/runs/${id}/recalculate`, {}, { idem: true }); router.push(`/payroll/${n.id}`);
            }}>Recalculate</ActionButton>
          )}
          {r.status === "DRAFT" && can("PAYROLL_RUN") && <ActionButton className="btn-primary" success="Marked reviewed" onClick={act("review")}>Mark reviewed</ActionButton>}
          {r.status === "REVIEWED" && can("PAYROLL_APPROVE") && <ActionButton className="btn-primary" success="Approved" onClick={act("approve")}>Approve</ActionButton>}
          {["REVIEWED", "APPROVED"].includes(r.status) && can("PAYROLL_APPROVE") && <ActionButton success="Sent back to draft" onClick={act("reject")}>Reject</ActionButton>}
          {r.status === "APPROVED" && can("PAYROLL_APPROVE") && (
            <ActionButton className="btn-primary" confirm="Lock payroll? Attendance for these duties becomes immutable." success="Locked" onClick={act("lock")}>Lock</ActionButton>
          )}
          {["APPROVED", "LOCKED", "PAID"].includes(r.status) && can("PAYROLL_APPROVE") && (
            <ActionButton onClick={() => download(`/v1/payroll/runs/${id}/bank-export`, `bank-${String(r.periodStart).slice(0, 7)}.csv`)}>Bank file (CSV)</ActionButton>
          )}
          {r.status === "LOCKED" && can("PAYROLL_RUN") && <ActionButton success="Marked paid" onClick={act("mark-paid")}>Mark paid</ActionButton>}
        </>} />
      <div className="grid grid-cols-2 gap-3 md:grid-cols-4">
        <Stat label="Employees" value={r.employeeCount} />
        <Stat label="Gross" value={fmtMoney(r.totalGross)} />
        <Stat label="Net payable" value={fmtMoney(r.totalNet)} />
        <Stat label="Employer contributions" value={fmtMoney(r.totalEmployerCost)} />
      </div>
      {r.exceptions?.length > 0 && (
        <div className="card border-amber-200 bg-amber-50 text-sm">
          <div className="mb-1 font-medium">Exceptions ({r.exceptions.length})</div>
          <ul className="list-disc pl-5">{r.exceptions.map((x: any, i: number) => <li key={i}>{x.message}</li>)}</ul>
        </div>
      )}
      <div className="card p-0">
        <div className="p-3 font-medium">Payroll register</div>
        {slips.loading && !slips.data ? <Loading /> : (
          <DataTable rows={slips.data ?? []} onRowClick={(s) => setSlip(s.id)} empty="No employees in this run." columns={[
            { key: "employee_code", label: "Code" }, { key: "employee_name", label: "Name" },
            { key: "duties", label: "Duties", render: (s) => fmtNum(s.duties) }, { key: "ot_hours", label: "OT h", render: (s) => fmtNum(s.ot_hours) },
            { key: "leave_days", label: "Leave" }, { key: "gross", label: "Gross", render: (s) => fmtMoney(s.gross) },
            { key: "total_deductions", label: "Deductions", render: (s) => fmtMoney(s.total_deductions) }, { key: "net", label: "Net", render: (s) => fmtMoney(s.net) },
            { key: "employer_contributions", label: "Employer", render: (s) => fmtMoney(s.employer_contributions) },
            { key: "payment_status", label: "Payment", render: (s) => <Badge value={s.payment_status} /> },
          ]} />
        )}
      </div>
      <Modal open={!!slip} title="Payslip" onClose={() => setSlip(null)} wide>{slip && <PayslipView id={slip} />}</Modal>
    </div>
  );
}
