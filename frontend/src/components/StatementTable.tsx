"use client";
import { useApi } from "@/lib/hooks";
import { DataTable } from "./DataTable";
import { Loading } from "./ui";
import { fmtMoney } from "@/lib/format";

export function StatementTable({ clientId }: { clientId: string }) {
  const { data, loading } = useApi<any[]>(clientId ? `/v1/clients/${clientId}/statement` : null);
  if (!clientId) return null;
  if (loading) return <Loading />;
  let bal = 0;
  const rows = (data ?? []).map((r, i) => { bal += Number(r.debit) - Number(r.credit); return { ...r, id: i, balance: bal }; });
  return (
    <DataTable rows={rows} empty="No transactions." columns={[
      { key: "date", label: "Date" }, { key: "type", label: "Type" }, { key: "ref", label: "Reference" },
      { key: "debit", label: "Debit", render: (r) => (Number(r.debit) ? fmtMoney(r.debit) : "") },
      { key: "credit", label: "Credit", render: (r) => (Number(r.credit) ? fmtMoney(r.credit) : "") },
      { key: "balance", label: "Balance", render: (r) => fmtMoney(r.balance) },
    ]} />
  );
}

