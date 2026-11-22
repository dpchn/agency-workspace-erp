"use client";
import { useState } from "react";
import { api, qs } from "@/lib/api";
import { useApi } from "@/lib/hooks";
import { useSession } from "@/lib/session";
import { ActionButton, Badge, Loading, Modal, PageHeader } from "./ui";
import { DataTable } from "./DataTable";
import { Form } from "./Form";
import { useToast } from "./Toast";
import { fmtDateTime } from "@/lib/format";
import { opts } from "@/lib/lookups";

/** Incident list + report form; used by staff, client portal and guard app. */
export function IncidentList({ siteOptions, compact }: { siteOptions: { value: string; label: string }[]; compact?: boolean }) {
  const { can } = useSession();
  const toast = useToast();
  const [status, setStatus] = useState("");
  const { data, loading, reload } = useApi<any[]>("/v1/incidents" + qs({ status }));
  const [open, setOpen] = useState(false);
  const [sel, setSel] = useState<any | null>(null);
  const siteName = (id: string) => siteOptions.find((o) => o.value === id)?.label ?? "";
  const act = (a: string, body: any = {}) => async () => {
    const r = await api.post(`/v1/incidents/${sel.id}/${a}`, body, { idem: true });
    setSel(r); reload();
  };
  return (
    <div>
      <PageHeader title="Incidents" subtitle={compact ? undefined : "Unacknowledged incidents escalate automatically when the response deadline passes."}
        actions={<>
          <select className="input w-auto" value={status} onChange={(e) => setStatus(e.target.value)} aria-label="Status">
            <option value="">All</option>{opts("OPEN", "ACKNOWLEDGED", "IN_PROGRESS", "RESOLVED", "CLOSED").map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
          </select>
          {can("INCIDENT_REPORT") && <button className="btn-primary" onClick={() => setOpen(true)}>Report incident</button>}
        </>} />
      <div className="card p-0">
        {loading ? <Loading /> : (
          <DataTable rows={data ?? []} onRowClick={setSel} empty="No incidents." columns={[
            { key: "occurredAt", label: "When", render: (r) => fmtDateTime(r.occurredAt) },
            { key: "severity", label: "Severity", render: (r) => <Badge value={r.severity} /> },
            { key: "title", label: "Title" }, { key: "siteId", label: "Site", render: (r) => siteName(r.siteId) },
            { key: "status", label: "Status", render: (r) => <Badge value={r.status} /> },
            { key: "escalationLevel", label: "Escalation", render: (r) => (r.escalationLevel ? `L${r.escalationLevel}` : "-") },
            { key: "responseDueAt", label: "Respond by", render: (r) => fmtDateTime(r.responseDueAt) },
          ]} />
        )}
      </div>
      <Modal open={open} title="Report incident" onClose={() => setOpen(false)}>
        <Form initial={{ severity: "MEDIUM" }} fields={[
          { name: "siteId", label: "Site", type: "select", options: siteOptions, required: true },
          { name: "title", label: "Title", required: true },
          { name: "severity", label: "Severity", type: "select", options: opts("LOW", "MEDIUM", "HIGH", "CRITICAL"), required: true, half: true },
          { name: "category", label: "Category", type: "select", options: opts("THEFT", "TRESPASS", "FIRE", "MEDICAL", "FIGHT", "EQUIPMENT", "OTHER"), half: true },
          { name: "description", label: "What happened", type: "textarea" },
          ...(can("INCIDENT_MANAGE") ? [{ name: "clientVisible", label: "Visible to client", type: "checkbox" as const }] : []),
        ]} onCancel={() => setOpen(false)}
          onSubmit={async (v) => { await api.post("/v1/incidents", v); toast.ok("Incident reported — supervisor notified"); setOpen(false); reload(); }} />
      </Modal>
      <Modal open={!!sel} title={sel?.title ?? ""} onClose={() => setSel(null)}>
        {sel && (
          <div className="space-y-3 text-sm">
            <div className="flex flex-wrap gap-2"><Badge value={sel.severity} /><Badge value={sel.status} />{sel.category && <Badge value={sel.category} />}</div>
            <div className="text-slate-600">{siteName(sel.siteId)} · {fmtDateTime(sel.occurredAt)}</div>
            <p className="whitespace-pre-wrap">{sel.description || "—"}</p>
            {sel.closureNotes && <p className="rounded bg-slate-50 p-2"><b>Resolution:</b> {sel.closureNotes}</p>}
            {can("INCIDENT_MANAGE") && (
              <div className="flex flex-wrap gap-2">
                {sel.status === "OPEN" && <ActionButton className="btn-primary" success="Acknowledged" onClick={act("acknowledge")}>Acknowledge</ActionButton>}
                {sel.status === "ACKNOWLEDGED" && <ActionButton success="Started" onClick={act("start")}>Start work</ActionButton>}
                {["ACKNOWLEDGED", "IN_PROGRESS"].includes(sel.status) && (
                  <ActionButton className="btn-primary" success="Resolved" onClick={async () => { const n = prompt("Resolution notes"); if (n) await act("resolve", { notes: n })(); }}>Resolve</ActionButton>
                )}
                {sel.status === "RESOLVED" && <ActionButton success="Closed" onClick={act("close")}>Close</ActionButton>}
                {["RESOLVED", "CLOSED"].includes(sel.status) && <ActionButton onClick={act("reopen")}>Reopen</ActionButton>}
                <ActionButton success="Updated" onClick={act("visibility", { clientVisible: !sel.clientVisible })}>
                  {sel.clientVisible ? "Hide from client" : "Share with client"}
                </ActionButton>
              </div>
            )}
          </div>
        )}
      </Modal>
    </div>
  );
}
