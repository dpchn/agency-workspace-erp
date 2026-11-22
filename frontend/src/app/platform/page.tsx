"use client";
import { useEffect } from "react";
import { useRouter } from "next/navigation";
import { LogOut } from "lucide-react";
import { api } from "@/lib/api";
import { useApi } from "@/lib/hooks";
import { useSession } from "@/lib/session";
import { ActionButton, Badge, Loading, PageHeader } from "@/components/ui";
import { DataTable } from "@/components/DataTable";
import { Brand } from "@/components/Shell";
import { fmtDate } from "@/lib/format";

/** Platform operator: tenant health and plans only — no access to agency HR/finance data. */
export default function PlatformPage() {
  const { me, loading, logout } = useSession();
  const router = useRouter();
  useEffect(() => { if (!loading && !me?.platformAdmin) router.replace("/"); }, [me, loading, router]);
  const { data, reload } = useApi<any[]>(me?.platformAdmin ? "/v1/platform/tenants" : null);
  if (loading || !me?.platformAdmin) return <Loading />;
  const set = (id: string, status: string) => async () => {
    const reason = prompt(`Reason for ${status}?`) ?? "";
    await api.patch(`/v1/platform/tenants/${id}`, { status, reason });
    reload();
  };
  return (
    <div className="min-h-screen">
      <header className="flex items-center justify-between border-b bg-white px-4 py-2"><Brand /><button className="btn-ghost" onClick={logout}><LogOut size={16} /></button></header>
      <main className="mx-auto max-w-5xl p-4">
        <PageHeader title="Agencies (tenants)" subtitle="Plans and status. Agency data is not visible to platform operators." />
        <div className="card p-0">
          <DataTable rows={data ?? []} columns={[
            { key: "name", label: "Agency" }, { key: "slug", label: "Workspace" }, { key: "plan", label: "Plan" },
            { key: "status", label: "Status", render: (r) => <Badge value={r.status} /> }, { key: "activeUsers", label: "Users" },
            { key: "trialEndsAt", label: "Trial ends", render: (r) => fmtDate(r.trialEndsAt) },
            {
              key: "a", label: "", render: (r) => (
                <div className="flex gap-1">
                  {r.status !== "ACTIVE" && <ActionButton success="Activated" onClick={set(r.id, "ACTIVE")}>Activate</ActionButton>}
                  {r.status !== "SUSPENDED" && <ActionButton className="btn-danger" success="Suspended" onClick={set(r.id, "SUSPENDED")}>Suspend</ActionButton>}
                </div>
              ),
            },
          ]} />
        </div>
      </main>
    </div>
  );
}
