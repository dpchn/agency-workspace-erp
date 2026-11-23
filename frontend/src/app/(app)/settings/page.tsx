"use client";
import { useState } from "react";
import { api } from "@/lib/api";
import { useApi } from "@/lib/hooks";
import { useSession } from "@/lib/session";
import { ActionButton, Badge, ErrorBox, Loading, Modal, PageHeader, Tabs } from "@/components/ui";
import { DataTable } from "@/components/DataTable";
import { Form, Field } from "@/components/Form";
import { useToast } from "@/components/Toast";
import { applyBrandColor, brand } from "@/config/brand";
import { opts, useBranches, useClients, useEmployees, useSites } from "@/lib/lookups";

const ROLES = ["OWNER", "HR", "OPERATIONS", "SUPERVISOR", "FINANCE", "CLIENT_ADMIN", "CLIENT_VIEWER", "GUARD", "SITE_KIOSK", "AUDITOR"];
const ROLE_OPTIONS = ROLES.map((r) => ({ value: r, label: r === "SITE_KIOSK" ? "Site device (kiosk)" : r.replace(/_/g, " ") }));

/** Human labels for tenant business policies (keys come from the backend's TenantSettings). */
const POLICY_HELP: Record<string, string> = {
  payDivisor: "Monthly wage ÷ this = value of one duty (CALENDAR_DAYS, 26 or 30)",
  payRatePolicy: "When a guard works away from home site: SITE_RATE, HOME_RATE or HIGHER_OF",
  otMultiplier: "OT hourly = per-duty value ÷ shift hours × this (if no explicit OT rate)",
  otThresholdMinutes: "Minutes beyond shift before OT counts",
  holidayPayMultiplier: "Pay multiplier for duties on holidays",
  paidLeaveTypes: "Comma-separated leave types that are paid",
  lateGraceMinutes: "Late arrival grace period",
  fullDayMinPercent: "Worked % of shift needed for a full duty",
  halfDayMinPercent: "Worked % of shift needed for a half duty",
  autoApproveCleanAttendance: "Auto-approve completed duties with no flags",
  autoApproveSources: "Ways of marking that may auto-approve (comma-separated: MOBILE, KIOSK, SUPERVISOR, MANUAL). Others always need a second person",
  kioskPhotoRequired: "Site kiosk takes a photo with every punch",
  rollCallPhotoRequired: "Supervisor roll-call needs a photo of the team",
  pinMaxAttempts: "Wrong kiosk PIN attempts before the PIN locks",
  pinLockMinutes: "How long a kiosk PIN stays locked",
  blockInvoiceOnDispute: "A client's unresolved attendance dispute blocks issuing that month's invoice",
  checkInWindowMinutes: "How early/late a check-in can match a shift",
  lateSubmissionHours: "Offline sync older than this is flagged",
  maxAccuracyMeters: "GPS accuracy worse than this is flagged",
  maxTravelKmph: "Impossible-travel speed threshold",
  minRestHours: "Minimum rest between shifts (else DOUBLE_DUTY only)",
  maxShiftsPerDay: "Maximum shifts per person per day",
  requireApprovalSeparation: "Maker cannot approve own payroll/invoice/adjustment/contract",
  invoicePrefix: "Invoice number prefix",
  creditNotePrefix: "Credit note number prefix",
  defaultGstRatePct: "Default GST % for new contracts",
  incidentResponseMinutes: "Response SLA per severity (JSON)",
};

export default function SettingsPage() {
  const { can } = useSession();
  const [tab, setTab] = useState(can("SETTINGS_WRITE") ? "org" : "users");
  return (
    <div>
      <Tabs active={tab} onChange={setTab} tabs={[
        ...(can("SETTINGS_WRITE") ? [{ key: "org", label: "Organisation" }, { key: "policy", label: "Business policies" }, { key: "brand", label: "Branding" }] : []),
        ...(can("USER_MANAGE") ? [{ key: "users", label: "Users & roles" }] : []),
        { key: "plan", label: "Plan & usage" },
      ]} />
      {tab === "users" ? <Users /> : tab === "plan" ? <PlanUsage /> : <OrgSettings tab={tab} />}
    </div>
  );
}

function OrgSettings({ tab }: { tab: string }) {
  const toast = useToast();
  const { refresh } = useSession();
  const { data, loading, error, reload } = useApi<any>("/v1/settings");
  if (loading && !data) return <Loading />;
  if (error) return <ErrorBox error={error} />;
  const save = async (body: any) => { await api.put("/v1/settings", body); toast.ok("Settings saved"); reload(); refresh(); };

  if (tab === "org") return (
    <div className="card max-w-2xl">
      <PageHeader title="Organisation" subtitle={`Workspace: ${data.slug} · ${data.status} · plan ${data.plan}`} />
      <Form key="org" initial={data} fields={[
        { name: "name", label: "Display name", required: true, half: true }, { name: "legalName", label: "Legal name", half: true },
        { name: "gstin", label: "GSTIN", half: true }, { name: "stateCode", label: "State code (supplier state for GST)", half: true },
        { name: "timezone", label: "Timezone", half: true },
      ]} onSubmit={save} />
    </div>
  );

  if (tab === "policy") {
    const s = data.settings as Record<string, any>;
    const fields: Field[] = Object.keys(s).map((k) => {
      const v = s[k];
      const type = typeof v === "boolean" ? "checkbox" : typeof v === "number" ? "number" : typeof v === "object" && !Array.isArray(v) ? "json" : "text";
      const sel = k === "payDivisor" ? opts("CALENDAR_DAYS", "26", "30") : k === "payRatePolicy" ? opts("SITE_RATE", "HOME_RATE", "HIGHER_OF") : undefined;
      return { name: k, label: k, type: sel ? "select" : type, options: sel, help: POLICY_HELP[k], half: type !== "json" } as Field;
    });
    const initial: Record<string, any> = {};
    Object.entries(s).forEach(([k, v]) => { initial[k] = Array.isArray(v) ? v.join(",") : v; });
    return (
      <div className="card">
        <PageHeader title="Business policies" subtitle="Rules that differ between agencies. Legal/payroll values must be validated by a qualified professional." />
        {/* keyed per tab: without it React reuses the previous tab's form state and the policies show blank */}
        <Form key="policy" initial={initial} fields={fields} onSubmit={async (v) => {
          const out: Record<string, any> = {};
          Object.entries(v).forEach(([k, val]) => { out[k] = Array.isArray(s[k]) ? String(val ?? "").split(",").map((x) => x.trim()).filter(Boolean) : val; });
          await save({ settings: out });
        }} />
      </div>
    );
  }

  const b = data.branding ?? {};
  return (
    <div className="card max-w-2xl">
      <PageHeader title="Branding" subtitle={`Shown to your staff, guards and clients instead of ${brand.name}.`} />
      <Form key="brand" initial={{ displayName: b.displayName, primaryColor: b.primaryColor ?? brand.primary, logoUrl: b.logoUrl }} fields={[
        { name: "displayName", label: "Name shown in the app (blank = organisation name)" },
        { name: "primaryColor", label: "Primary colour (hex)", half: true, placeholder: "#1d4ed8" },
        { name: "logoUrl", label: "Logo URL (square SVG/PNG)", half: true },
      ]} onSubmit={async (v) => {
        if (v.primaryColor && !/^#[0-9a-fA-F]{6}$/.test(v.primaryColor)) throw { message: "Colour must be like #1d4ed8", fieldErrors: { primaryColor: "Use #RRGGBB" } };
        await save({ branding: v });
        if (v.primaryColor) applyBrandColor(v.primaryColor);
      }} />
    </div>
  );
}

function Users() {
  const toast = useToast();
  const { data, loading, reload } = useApi<any[]>("/v1/users");
  const branches = useBranches(), sites = useSites(), clients = useClients(), emps = useEmployees();
  const [invite, setInvite] = useState(false);
  const [edit, setEdit] = useState<any | null>(null);
  const [created, setCreated] = useState<string | null>(null);
  return (
    <div>
      <PageHeader title="Users & roles" subtitle="Access is explicit per organisation; never inferred from e-mail domain. Scope supervisors to sites/branches. A site device (kiosk) is a shared gate tablet where workers without a phone punch with code + PIN; open /kiosk on it."
        actions={<button className="btn-primary" onClick={() => setInvite(true)}>Add user</button>} />
      <div className="card p-0">
        {loading ? <Loading /> : (
          <DataTable rowKey="membershipId" rows={data ?? []} onRowClick={setEdit} columns={[
            { key: "name", label: "Name" }, { key: "email", label: "E-mail" }, { key: "phone", label: "Phone" },
            { key: "role", label: "Role", render: (r) => <Badge value={r.role} /> },
            { key: "scope", label: "Scope", render: (r) => [r.branchIds?.length && `${r.branchIds.length} branch(es)`, r.siteIds?.length && `${r.siteIds.length} site(s)`,
              r.clientId && "Client: " + (clients.map[r.clientId] ?? ""), r.employeeId && "Employee: " + (emps.map[r.employeeId] ?? "")].filter(Boolean).join(" · ") || "All" },
            { key: "status", label: "Status", render: (r) => <Badge value={r.status} /> },
            {
              key: "a", label: "", render: (r) => (
                <div className="flex gap-1">
                  <ActionButton className="btn-ghost" success="Sessions terminated" confirm="Sign this user out of all devices?"
                    onClick={() => api.post(`/v1/users/${r.membershipId}/terminate-sessions`)}>Sign out everywhere</ActionButton>
                  <ActionButton className="btn-ghost" confirm={`Reset ${r.name}'s password? They are signed out everywhere and get a temporary password.`}
                    onClick={async () => {
                      const x = await api.post(`/v1/users/${r.membershipId}/reset-password`);
                      alert(`New temporary password for ${x.login}:\n\n${x.temporaryPassword}\n\nShown only once. Ask them to change it after signing in.`);
                    }}>Reset password</ActionButton>
                </div>
              ),
            },
          ]} />
        )}
      </div>
      <Modal open={invite} title="Add user" onClose={() => setInvite(false)}>
        <Form initial={{ role: "SUPERVISOR" }} fields={[
          { name: "fullName", label: "Full name", required: true },
          { name: "email", label: "E-mail", type: "email", half: true }, { name: "phone", label: "or mobile", half: true },
          { name: "role", label: "Role", type: "select", options: ROLE_OPTIONS, required: true, half: true },
          { name: "password", label: "Initial password (blank = generate)", type: "password", half: true },
          { name: "branchIds", label: "Limit to branches (none = all)", type: "multiselect", options: branches.options },
          { name: "siteIds", label: "Limit to sites (supervisors; required for a site device)", type: "multiselect", options: sites.options },
          { name: "clientId", label: "Client (client portal roles)", type: "select", options: clients.options, half: true },
          { name: "employeeId", label: "Employee (guard role)", type: "select", options: emps.options, half: true },
        ]} onCancel={() => setInvite(false)}
          onSubmit={async (v) => {
            const body = { ...v, branchIds: v.branchIds?.length ? v.branchIds : null, siteIds: v.siteIds?.length ? v.siteIds : null };
            const r = await api.post("/v1/users", body);
            setInvite(false); reload();
            if (r.temporaryPassword) setCreated(r.temporaryPassword); else toast.ok("User added");
          }} />
      </Modal>
      <Modal open={!!created} title="Temporary password" onClose={() => setCreated(null)}>
        <p className="text-sm">Share this once with the user; it will not be shown again:</p>
        <p className="mt-2 rounded bg-slate-100 p-3 font-mono text-lg">{created}</p>
      </Modal>
      <Modal open={!!edit} title={edit ? `Edit ${edit.name}` : ""} onClose={() => setEdit(null)}>
        {edit && (
          <Form initial={edit} fields={[
            { name: "role", label: "Role", type: "select", options: ROLE_OPTIONS, half: true },
            { name: "status", label: "Status", type: "select", options: opts("ACTIVE", "INACTIVE"), half: true },
            { name: "branchIds", label: "Branches (none = all)", type: "multiselect", options: branches.options },
            { name: "siteIds", label: "Sites (none = all)", type: "multiselect", options: sites.options },
          ]} onCancel={() => setEdit(null)}
            onSubmit={async (v) => {
              const clear = !v.branchIds?.length && !v.siteIds?.length;
              await api.patch(`/v1/users/${edit.membershipId}`, {
                role: v.role, status: v.status, clearScope: clear || undefined,
                branchIds: v.branchIds?.length ? v.branchIds : undefined, siteIds: v.siteIds?.length ? v.siteIds : undefined,
              });
              toast.ok("Updated (user must sign in again)"); setEdit(null); reload();
            }} />
        )}
      </Modal>
    </div>
  );
}

/** The agency's subscription: plan, limits vs usage, trial end. Changed by the platform operator. */
function PlanUsage() {
  const { data } = useApi<any>("/v1/settings/plan");
  if (!data) return <Loading />;
  const row = (label: string, used: number, limit: any) => {
    const unlimited = limit === "UNLIMITED";
    const pct = unlimited ? 0 : Math.min(100, Math.round((used * 100) / Math.max(1, limit)));
    return (
      <div key={label}>
        <div className="flex justify-between text-sm"><span>{label}</span><span>{used} / {unlimited ? "unlimited" : limit}</span></div>
        {!unlimited && <div className="mt-1 h-2 rounded bg-slate-100"><div className={`h-2 rounded ${pct >= 90 ? "bg-red-500" : "bg-blue-600"}`} style={{ width: `${pct}%` }} /></div>}
      </div>
    );
  };
  return (
    <div className="card max-w-xl space-y-4">
      <div className="flex items-center justify-between">
        <div><div className="text-lg font-semibold">{data.planName} plan</div>
          {data.status === "TRIAL" && data.trialEndsAt && <div className="text-sm text-amber-700">Free trial until {String(data.trialEndsAt).slice(0, 10)}</div>}
        </div>
        <Badge value={data.status} />
      </div>
      {row("Staff (employees)", data.usage.employees, data.limits.employees)}
      {row("Active sites", data.usage.sites, data.limits.sites)}
      {row("Office users", data.usage.users, data.limits.users)}
      <p className="text-xs text-slate-500">Guard app logins and client logins are not limited. To change your plan, contact your provider.</p>
    </div>
  );
}
