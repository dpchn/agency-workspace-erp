"use client";
import { useApi } from "@/lib/hooks";
import { fmtMoney, fmtNum } from "@/lib/format";
import { ErrorBox, Loading } from "./ui";

/** Payslip with per-site duty lines (shows how a guard who moved sites was paid). */
export function PayslipView({ id }: { id: string }) {
  const { data: p, loading, error } = useApi<any>(`/v1/payroll/payslips/${id}`);
  if (loading) return <Loading />;
  if (error) return <ErrorBox error={error} />;
  if (!p) return null;
  const lines: any[] = p.lines ?? [];
  const sec = (kind: string) => lines.filter((l) => l.kind === kind);
  const Section = ({ title, kind }: { title: string; kind: string }) => (
    <div>
      <div className="mb-1 text-xs font-semibold uppercase text-slate-500">{title}</div>
      <table className="w-full text-sm">
        <tbody>
          {sec(kind).map((l, i) => (
            <tr key={i} className="border-b border-slate-100">
              <td className="py-1">
                {l.component_name}{l.site_name ? ` @ ${l.site_name}` : ""}
                {l.quantity != null && <span className="text-xs text-slate-500"> · {fmtNum(l.quantity)} {l.component_code === "LEAVE_PAY" ? "days" : "duties"}</span>}
                {l.source?.rates?.length ? <div className="text-[11px] text-slate-400">{l.source.rates.join(", ")}</div> : null}
              </td>
              <td className="py-1 text-right">{fmtMoney(l.amount)}</td>
            </tr>
          ))}
          {!sec(kind).length && <tr><td className="py-1 text-slate-400">—</td></tr>}
        </tbody>
      </table>
    </div>
  );
  return (
    <div className="space-y-4 text-sm">
      <div className="flex flex-wrap justify-between gap-2">
        <div><div className="font-semibold">{p.employee_name}</div><div className="text-xs text-slate-500">{p.employee_code} · UAN {p.uan ?? "-"} · ESIC {p.esic_no ?? "-"}</div></div>
        <div className="text-right text-xs text-slate-500">Period {p.period_start} – {p.period_end}<br />Bank {p.bank_account_no ?? "-"} {p.bank_ifsc ?? ""}</div>
      </div>
      <div className="grid grid-cols-3 gap-2 text-center">
        <div className="rounded bg-slate-50 p-2"><div className="text-xs text-slate-500">Duties</div><div className="font-semibold">{fmtNum(p.duties)}</div></div>
        <div className="rounded bg-slate-50 p-2"><div className="text-xs text-slate-500">OT hours</div><div className="font-semibold">{fmtNum(p.ot_hours)}</div></div>
        <div className="rounded bg-slate-50 p-2"><div className="text-xs text-slate-500">Paid leave</div><div className="font-semibold">{fmtNum(p.leave_days)}</div></div>
      </div>
      <div className="grid gap-4 md:grid-cols-2">
        <Section title="Earnings" kind="EARNING" />
        <Section title="Deductions" kind="DEDUCTION" />
      </div>
      <div className="flex justify-between rounded bg-brand/10 p-3 font-semibold"><span>Gross {fmtMoney(p.gross)} − Deductions {fmtMoney(p.total_deductions)}</span><span>Net {fmtMoney(p.net)}</span></div>
      <Section title="Employer contributions (not deducted)" kind="EMPLOYER_CONTRIBUTION" />
    </div>
  );
}
