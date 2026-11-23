"use client";
import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { LogOut, Plus } from "lucide-react";
import { api } from "@/lib/api";
import { useApi } from "@/lib/hooks";
import { useSession } from "@/lib/session";
import { ActionButton, Badge, Loading, Modal, PageHeader, Stat } from "@/components/ui";
import { DataTable } from "@/components/DataTable";
import { Form } from "@/components/Form";
import { Brand } from "@/components/Shell";
import { useToast } from "@/components/Toast";
import { fmtDate, fmtDateTime } from "@/lib/format";

const STATES = [["27", "Maharashtra"], ["29", "Karnataka"], ["07", "Delhi"], ["33", "Tamil Nadu"], ["36", "Telangana"], ["24", "Gujarat"],
  ["09", "Uttar Pradesh"], ["19", "West Bengal"], ["06", "Haryana"], ["32", "Kerala"]].map(([value, label]) => ({ value, label: `${label} (${value})` }));

const usageCell = (used: number, limit: any) => (
  <span className={limit !== "UNLIMITED" && used >= limit ? "font-semibold text-red-600" : ""}>
    {used} / {limit === "UNLIMITED" ? "∞" : limit}
  </span>
);

/** Platform operator: onboard agencies, plans, limits, trials and health. No access to agency HR/attendance/finance data. */
export default function PlatformPage() {
  const { me, loading, logout } = useSession();
  const router = useRouter();
  const toast = useToast();
  useEffect(() => { if (!loading && !me?.platformAdmin) router.replace("/"); }, [me, loading, router]);
  const ok = !!me?.platformAdmin;
  const { data, reload } = useApi<any[]>(ok ? "/v1/platform/tenants" : null);
  const { data: summary, reload: reloadSummary } = useApi<any>(ok ? "/v1/platform/summary" : null);
  const { data: plans } = useApi<any[]>(ok ? "/v1/platform/plans" : null);
  const [adding, setAdding] = useState(false);
  const [created, setCreated] = useState<any>(null);
  const [editing, setEditing] = useState<any>(null);
  if (loading || !ok) return <Loading />;

  const refresh = () => { reload(); reloadSummary(); };
  const planOptions = (plans ?? []).map((p) => ({ value: p.code, label: `${p.name}: ${p.description}` }));
  const patch = async (id: string, body: any, msg: string) => { await api.patch(`/v1/platform/tenants/${id}`, body); toast.ok(msg); refresh(); };
  const suspend = (r: any) => async () => {
    const reason = prompt(`Why suspend ${r.name}? Its users are signed out at once.`);
    if (!reason) return;
    await patch(r.id, { status: "SUSPENDED", reason }, "Suspended");
  };

  return (
    <div className="min-h-screen">
      <header className="flex items-center justify-between border-b bg-white px-4 py-2">
        <Brand />
        <div className="flex items-center gap-2 text-sm text-slate-600">Platform console<button className="btn-ghost" onClick={logout}><LogOut size={16} /></button></div>
      </header>
      <main className="mx-auto max-w-7xl space-y-4 p-4">
        <PageHeader title="Agencies" subtitle="Onboard manpower agencies, set their plan and limits, extend trials, suspend or restore. Agency data stays private to each agency."
          actions={<button className="btn-primary" onClick={() => setAdding(true)}><Plus size={16} /> Onboard agency</button>} />
        {summary && (
          <div className="grid grid-cols-2 gap-3 md:grid-cols-5">
            <Stat label="Agencies" value={summary.agencies} />
            <Stat label="Active" value={summary.byStatus?.ACTIVE ?? 0} tone="green" />
            <Stat label="On trial" value={summary.byStatus?.TRIAL ?? 0} />
            <Stat label="Trial expired / suspended" value={(summary.byStatus?.TRIAL_EXPIRED ?? 0) + (summary.byStatus?.SUSPENDED ?? 0)} tone="red" />
            <Stat label="Staff managed" value={summary.employees} hint={`${summary.sites} sites · self sign-up ${summary.selfSignupEnabled ? "on" : "off"}`} />
          </div>
        )}
        <div className="card p-0">
          <DataTable rows={data ?? []} columns={[
            { key: "name", label: "Agency", render: (r) => <div><div className="font-medium">{r.name}</div><div className="text-xs text-slate-500">{r.slug} · since {fmtDate(r.createdAt)}</div></div> },
            { key: "owner", label: "Owner", render: (r) => r.owner ? <div className="text-xs"><div>{r.owner.name}</div><div className="text-slate-500">{r.owner.email ?? r.owner.phone}</div><div className="text-slate-400">last sign-in {r.owner.lastLoginAt ? fmtDateTime(r.owner.lastLoginAt) : "never"}</div></div> : "-" },
            { key: "plan", label: "Plan" },
            {
              key: "status", label: "Status", render: (r) => (
                <div>
                  <Badge value={r.trialExpired ? "TRIAL_EXPIRED" : r.status} />
                  {r.status === "TRIAL" && r.trialEndsAt && <div className="text-xs text-slate-500">ends {fmtDate(r.trialEndsAt)}</div>}
                  {r.statusReason && r.status === "SUSPENDED" && <div className="text-xs text-slate-500">{r.statusReason}</div>}
                </div>
              ),
            },
            { key: "emp", label: "Staff", render: (r) => usageCell(r.usage.employees, r.limits.employees) },
            { key: "sites", label: "Sites", render: (r) => usageCell(r.usage.sites, r.limits.sites) },
            { key: "users", label: "Office users", render: (r) => usageCell(r.usage.staffUsers, r.limits.users) },
            { key: "guards", label: "Guard app logins", render: (r) => r.usage.guardLogins },
            { key: "duties", label: "Duties (30 d)", render: (r) => r.usage.duties30d },
            { key: "act", label: "Last activity", render: (r) => r.usage.lastActivityAt ? fmtDateTime(r.usage.lastActivityAt) : "-" },
            {
              key: "a", label: "", render: (r) => (
                <div className="flex flex-wrap gap-1">
                  {r.status !== "ACTIVE" && <ActionButton success="Activated" onClick={() => patch(r.id, { status: "ACTIVE" }, "Activated")}>Activate</ActionButton>}
                  {(r.status === "TRIAL" || r.trialExpired) && <ActionButton onClick={() => patch(r.id, { extendTrialDays: 15 }, "Trial extended 15 days")}>+15 days</ActionButton>}
                  <button className="btn-secondary" onClick={() => setEditing(r)}>Plan & limits</button>
                  <ActionButton confirm={`Reset the password of ${r.owner?.name ?? "the owner"}? They are signed out everywhere.`}
                    onClick={async () => {
                      const x = await api.post(`/v1/platform/tenants/${r.id}/reset-owner-password`);
                      setCreated({ name: r.name, ownerLogin: x.login, temporaryPassword: x.temporaryPassword, reset: true });
                    }}>Reset owner password</ActionButton>
                  {r.status !== "SUSPENDED" && <ActionButton className="btn-danger" onClick={suspend(r)}>Suspend</ActionButton>}
                </div>
              ),
            },
          ]} />
        </div>
      </main>

      <Modal open={adding} title="Onboard a manpower agency" onClose={() => setAdding(false)} wide>
        <Form initial={{ plan: "STARTER", status: "TRIAL", trialDays: 30, stateCode: "27" }} fields={[
          { name: "agencyName", label: "Agency name", required: true },
          { name: "slug", label: "Workspace id (optional)", placeholder: "e.g. shield-force", half: true },
          { name: "stateCode", label: "State (for GST)", type: "select", options: STATES, half: true },
          { name: "gstin", label: "GSTIN", half: true },
          { name: "contactPhone", label: "Office phone", half: true },
          { name: "ownerName", label: "Owner name", required: true, half: true },
          { name: "ownerEmail", label: "Owner e-mail (login)", type: "email", required: true, half: true },
          { name: "ownerPhone", label: "Owner mobile", half: true },
          { name: "plan", label: "Plan", type: "select", options: planOptions, required: true },
          { name: "status", label: "Start as", type: "select", options: [{ value: "TRIAL", label: "Free trial" }, { value: "ACTIVE", label: "Paid / active" }], half: true },
          { name: "trialDays", label: "Trial days", type: "number", half: true },
          { name: "maxEmployees", label: "Staff limit (blank = plan default)", type: "number", half: true },
          { name: "maxSites", label: "Site limit (blank = plan default)", type: "number", half: true },
          { name: "notes", label: "Internal notes", type: "textarea" },
        ]} submitLabel="Create agency" onCancel={() => setAdding(false)}
          onSubmit={async (v) => {
            const r = await api.post("/v1/platform/tenants", v);
            setAdding(false);
            setCreated(r);
            refresh();
          }} />
      </Modal>

      <Modal open={!!created} title={created?.reset ? "Owner password reset" : "Agency created"} onClose={() => setCreated(null)}>
        {created && (
          <div className="space-y-3 text-sm">
            {created.reset ? <p>Send these new sign-in details to the owner of <b>{created.name}</b>:</p> : <p><b>{created.name}</b> is ready with default branch, worker types (guard, lady guard, gunman, supervisor, housekeeping), shifts and salary rules. Send these sign-in details to the owner:</p>}
            <div className="rounded border bg-slate-50 p-3 font-mono text-xs">
              <div>Web: {typeof window !== "undefined" ? window.location.origin : ""}/login</div>
              <div>Login: {created.ownerLogin}</div>
              <div>Password: {created.temporaryPassword ?? "(existing user: their current password)"}</div>
            </div>
            {created.temporaryPassword && <p className="text-amber-700">This password is shown only once. Ask the owner to change it after signing in (key icon, top right).</p>}
            <button className="btn-primary" onClick={() => setCreated(null)}>Done</button>
          </div>
        )}
      </Modal>

      <Modal open={!!editing} title={`Plan & limits: ${editing?.name ?? ""}`} onClose={() => setEditing(null)}>
        {editing && (
          <Form initial={{
            plan: editing.plan, notes: editing.notes,
            maxEmployees: editing.limits.employees === "UNLIMITED" ? null : editing.limits.employees,
            maxSites: editing.limits.sites === "UNLIMITED" ? null : editing.limits.sites,
            maxUsers: editing.limits.users === "UNLIMITED" ? null : editing.limits.users,
          }} fields={[
            { name: "plan", label: "Plan (changing it resets limits to the plan's defaults)", type: "select", options: planOptions },
            { name: "maxEmployees", label: "Staff limit", type: "number", half: true },
            { name: "maxSites", label: "Site limit", type: "number", half: true },
            { name: "maxUsers", label: "Office user limit", type: "number", half: true, help: "Guard and client logins are never limited" },
            { name: "unlimited", label: "Remove all limits", type: "checkbox", half: true },
            { name: "notes", label: "Internal notes", type: "textarea" },
          ]} onCancel={() => setEditing(null)}
            onSubmit={async (v) => {
              const body: any = { notes: v.notes ?? "", unlimited: v.unlimited };
              if (v.plan !== editing.plan) body.plan = v.plan;
              else if (!v.unlimited) Object.assign(body, { maxEmployees: v.maxEmployees, maxSites: v.maxSites, maxUsers: v.maxUsers });
              await patch(editing.id, body, "Saved");
              setEditing(null);
            }} />
        )}
      </Modal>
    </div>
  );
}
