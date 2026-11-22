"use client";
import { useState } from "react";
import { api, qs } from "@/lib/api";
import { useApi } from "@/lib/hooks";
import { useSession } from "@/lib/session";
import { Badge, ErrorBox, Loading, Modal, PageHeader } from "@/components/ui";
import { DataTable } from "@/components/DataTable";
import { Form } from "@/components/Form";
import { useToast } from "@/components/Toast";
import { opts, useEmployees, usePosts, useServiceTypes, useShiftTemplates, useSites } from "@/lib/lookups";
import { today } from "@/lib/format";

export default function DeploymentsPage() {
  const { can } = useSession();
  const toast = useToast();
  const [siteId, setSiteId] = useState("");
  const [activeOn, setActiveOn] = useState(today());
  const { data, error, loading, reload } = useApi<any[]>("/v1/deployments" + qs({ siteId, activeOn }));
  const emps = useEmployees(), sites = useSites(), posts = usePosts(), st = useServiceTypes(), tpl = useShiftTemplates();
  const [modal, setModal] = useState<null | "deploy" | "transfer">(null);
  const [ending, setEnding] = useState<any | null>(null);

  return (
    <div>
      <PageHeader title="Deployments" subtitle="Who is posted where, over time. Transfers keep history; the roster is generated from deployments."
        actions={can("ROSTER_WRITE") && <>
          <button className="btn-secondary" onClick={() => setModal("transfer")}>Transfer</button>
          <button className="btn-primary" onClick={() => setModal("deploy")}>Deploy</button>
        </>} />
      <div className="card p-0">
        <div className="flex flex-wrap items-center gap-2 border-b p-3">
          <select className="input w-auto" value={siteId} onChange={(e) => setSiteId(e.target.value)} aria-label="Site">
            <option value="">All sites</option>{sites.options.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
          </select>
          <label className="flex items-center gap-2 text-sm">Active on
            <input type="date" className="input w-auto" value={activeOn} onChange={(e) => setActiveOn(e.target.value)} /></label>
          <button className="btn-ghost" onClick={() => setActiveOn("")}>Show full history</button>
        </div>
        {error && <div className="p-3"><ErrorBox error={error} /></div>}
        {loading && !data ? <Loading /> : (
          <DataTable rows={data ?? []} empty="No deployments." columns={[
            { key: "employeeId", label: "Employee", render: (r) => emps.map[r.employeeId] },
            { key: "siteId", label: "Site", render: (r) => sites.map[r.siteId] },
            { key: "postId", label: "Post", render: (r) => (r.postId ? posts.map[r.postId] : "Any post") },
            { key: "serviceTypeId", label: "Service", render: (r) => st.map[r.serviceTypeId] },
            { key: "shiftTemplateId", label: "Shift", render: (r) => (r.shiftTemplateId ? tpl.map[r.shiftTemplateId] : "Any") },
            { key: "deploymentType", label: "Type", render: (r) => <Badge value={r.deploymentType} /> },
            { key: "startDate", label: "From" }, { key: "endDate", label: "To" },
            { key: "status", label: "Status", render: (r) => <Badge value={r.status} /> },
            { key: "reason", label: "Reason" },
            {
              key: "a", label: "", render: (r) => can("ROSTER_WRITE") && r.status === "ACTIVE" && !r.endDate &&
                <button className="btn-secondary" onClick={() => setEnding(r)}>End</button>,
            },
          ]} />
        )}
      </div>

      <Modal open={modal === "deploy"} title="Deploy employee" onClose={() => setModal(null)}>
        <Form initial={{ deploymentType: "PERMANENT", startDate: today() }} fields={[
          { name: "employeeId", label: "Employee (must be ACTIVE)", type: "select", options: emps.options, required: true },
          { name: "siteId", label: "Site", type: "select", options: sites.options, required: true, half: true },
          { name: "postId", label: "Post (optional)", type: "select", options: posts.options, half: true },
          { name: "serviceTypeId", label: "Service type (default from post)", type: "select", options: st.options, half: true },
          { name: "shiftTemplateId", label: "Fixed shift (optional)", type: "select", options: tpl.options, half: true },
          {
            name: "deploymentType", label: "Type", type: "select", options: opts("PERMANENT", "TEMPORARY", "RELIEVER"), required: true, half: true,
            help: "TEMPORARY overrides the permanent posting on its dates; RELIEVER = floating standby pool",
          },
          { name: "startDate", label: "From", type: "date", required: true, half: true }, { name: "endDate", label: "To", type: "date", half: true },
          { name: "reason", label: "Reason" },
        ]} onCancel={() => setModal(null)}
          onSubmit={async (v) => { await api.post("/v1/deployments", v); toast.ok("Deployed"); setModal(null); reload(); }} />
      </Modal>
      <Modal open={modal === "transfer"} title="Transfer employee" onClose={() => setModal(null)}>
        <p className="mb-3 text-sm text-slate-600">Ends the current permanent posting the day before, releases future planned duties there and starts the new posting. Generate the roster afterwards.</p>
        <Form initial={{ effectiveDate: today() }} fields={[
          { name: "employeeId", label: "Employee", type: "select", options: emps.options, required: true },
          { name: "toSiteId", label: "To site", type: "select", options: sites.options, required: true, half: true },
          { name: "toPostId", label: "To post", type: "select", options: posts.options, half: true },
          { name: "shiftTemplateId", label: "Shift", type: "select", options: tpl.options, half: true },
          { name: "effectiveDate", label: "Effective date", type: "date", required: true, half: true },
          { name: "reason", label: "Reason", required: true },
        ]} onCancel={() => setModal(null)}
          onSubmit={async (v) => { await api.post("/v1/deployments/transfer", v); toast.ok("Transferred"); setModal(null); reload(); }} />
      </Modal>
      <Modal open={!!ending} title="End deployment" onClose={() => setEnding(null)}>
        {ending && (
          <Form initial={{ endDate: today() }} fields={[{ name: "endDate", label: "Last day", type: "date", required: true }, { name: "reason", label: "Reason" }]}
            onCancel={() => setEnding(null)}
            onSubmit={async (v) => { await api.post(`/v1/deployments/${ending.id}/end`, v); toast.ok("Ended"); setEnding(null); reload(); }} />
        )}
      </Modal>
    </div>
  );
}
