"use client";
import { useParams } from "next/navigation";
import { useState } from "react";
import { api } from "@/lib/api";
import { useApi } from "@/lib/hooks";
import { useSession } from "@/lib/session";
import { ActionButton, Badge, ErrorBox, Loading, Modal, PageHeader } from "@/components/ui";
import { DataTable } from "@/components/DataTable";
import { Form } from "@/components/Form";
import { useToast } from "@/components/Toast";
import { opts, useBranches, useServiceTypes, useSites } from "@/lib/lookups";
import { employeeFields } from "../fields";

const DOCS = ["POLICE_VERIFICATION", "ID_PROOF", "ADDRESS_PROOF", "TRAINING", "ARMS_LICENSE", "MEDICAL"];

export default function EmployeeDetail() {
  const { id } = useParams<{ id: string }>();
  const { can } = useSession();
  const toast = useToast();
  const emp = useApi<any>(`/v1/employees/${id}`);
  const docs = useApi<any>(`/v1/employee-documents?employeeId=${id}&size=200`);
  const ready = useApi<any>(`/v1/employees/${id}/readiness`);
  const deps = useApi<any[]>(can("ROSTER_READ") ? `/v1/deployments?employeeId=${id}` : null);
  const branches = useBranches(), st = useServiceTypes(), sites = useSites();
  const [modal, setModal] = useState<null | "edit" | "doc" | "login" | "exit" | "suspend">(null);

  if (emp.loading && !emp.data) return <Loading />;
  if (emp.error) return <ErrorBox error={emp.error} />;
  const e = emp.data;
  const reloadAll = () => { emp.reload(); docs.reload(); ready.reload(); deps.reload(); };
  const act = (path: string, body: any = {}) => async () => { await api.post(`/v1/employees/${id}/${path}`, body); reloadAll(); };

  return (
    <div className="space-y-4">
      <PageHeader title={`${e.firstName} ${e.lastName ?? ""}`}
        subtitle={`${e.employeeCode} · ${st.map[e.serviceTypeId] ?? ""} · ${branches.map[e.branchId] ?? ""}`}
        actions={<>
          <Badge value={e.status} />
          {can("HR_WRITE") && e.status === "DRAFT" && <ActionButton onClick={act("submit")} success="Submitted for verification">Submit for verification</ActionButton>}
          {can("HR_VERIFY") && ["DRAFT", "PENDING_VERIFICATION"].includes(e.status) &&
            <ActionButton className="btn-primary" onClick={act("activate")} success="Activated">Activate</ActionButton>}
          {can("HR_WRITE") && e.status === "ACTIVE" && <button className="btn-secondary" onClick={() => setModal("suspend")}>Suspend</button>}
          {can("HR_WRITE") && e.status === "SUSPENDED" && <ActionButton onClick={act("reinstate")} success="Reinstated">Reinstate</ActionButton>}
          {can("HR_WRITE") && e.status !== "EXITED" && <button className="btn-danger" onClick={() => setModal("exit")}>Exit</button>}
          {!e.userId && can("USER_MANAGE") && <button className="btn-secondary" onClick={() => setModal("login")}>Create app login</button>}
          {can("HR_WRITE") && <button className="btn-secondary" onClick={() => setModal("edit")}>Edit</button>}
        </>} />

      {ready.data && (
        <div className={`card text-sm ${ready.data.deployable ? "border-emerald-200 bg-emerald-50" : "border-amber-200 bg-amber-50"}`}>
          <b>{ready.data.deployable ? "Ready for deployment" : "Not deployable yet"}</b>
          {ready.data.missingDocuments?.length > 0 && <> — missing/unverified: {ready.data.missingDocuments.join(", ")}</>}
          {e.userId && <span className="ml-2 text-slate-500">· has mobile app login</span>}
        </div>
      )}

      <div className="grid gap-4 lg:grid-cols-2">
        <div className="card">
          <div className="mb-2 font-medium">Profile</div>
          <dl className="grid grid-cols-2 gap-x-4 gap-y-1 text-sm">
            {([["Mobile", e.phone], ["E-mail", e.email], ["Joined", e.joinedOn], ["Exited", e.exitedOn], ["UAN", e.uan], ["ESIC", e.esicNo],
              ["Bank", e.bankAccountNo ? `${e.bankName ?? ""} ••${String(e.bankAccountNo).slice(-4)} ${e.bankIfsc ?? ""}` : null],
              ["Weekly off", e.weeklyOffDay], ["Emergency", e.emergencyContact], ["Exit reason", e.exitReason]] as [string, any][]).map(([k, v]) => (
              <div key={k} className="contents"><dt className="text-slate-500">{k}</dt><dd>{v ?? "-"}</dd></div>
            ))}
          </dl>
        </div>
        <div className="card p-0">
          <div className="p-3 font-medium">Deployment history</div>
          <DataTable rows={deps.data ?? []} empty="Not deployed." columns={[
            { key: "siteId", label: "Site", render: (r) => sites.map[r.siteId] }, { key: "deploymentType", label: "Type" },
            { key: "startDate", label: "From" }, { key: "endDate", label: "To" },
            { key: "status", label: "Status", render: (r) => <Badge value={r.status} /> },
          ]} />
        </div>
      </div>

      <div className="card p-0">
        <div className="flex items-center justify-between p-3">
          <span className="font-medium">Documents & verification</span>
          {can("HR_WRITE") && <button className="btn-secondary" onClick={() => setModal("doc")}>Add document</button>}
        </div>
        <DataTable rows={docs.data?.items ?? []} empty="No documents." columns={[
          { key: "docType", label: "Type" }, { key: "docNumber", label: "Number" }, { key: "issuedOn", label: "Issued" }, { key: "expiresOn", label: "Expires" },
          { key: "verificationStatus", label: "Status", render: (r) => <Badge value={r.verificationStatus} /> },
          {
            key: "a", label: "", render: (r) => can("HR_VERIFY") && r.verificationStatus === "PENDING" && (
              <div className="flex gap-1">
                <ActionButton className="btn-primary" success="Verified"
                  onClick={async () => { await api.post(`/v1/employee-documents/${r.id}/verify`, { status: "VERIFIED" }); reloadAll(); }}>Verify</ActionButton>
                <ActionButton success="Rejected"
                  onClick={async () => { await api.post(`/v1/employee-documents/${r.id}/verify`, { status: "REJECTED" }); reloadAll(); }}>Reject</ActionButton>
              </div>
            ),
          },
        ]} />
      </div>

      <Modal open={modal === "edit"} title="Edit employee" onClose={() => setModal(null)} wide>
        <Form fields={employeeFields(branches, st)} initial={e} isEdit onCancel={() => setModal(null)}
          onSubmit={async (v) => { await api.patch(`/v1/employees/${id}`, { ...v, version: e.version }); toast.ok("Saved"); setModal(null); reloadAll(); }} />
      </Modal>
      <Modal open={modal === "doc"} title="Add document" onClose={() => setModal(null)}>
        <Form fields={[
          { name: "docType", label: "Type", type: "select", options: opts(...DOCS), required: true, half: true },
          { name: "docNumber", label: "Number", half: true },
          { name: "issuedOn", label: "Issued on", type: "date", half: true }, { name: "expiresOn", label: "Expires on", type: "date", half: true },
          { name: "objectKey", label: "File reference / URL" }, { name: "notes", label: "Notes", type: "textarea" },
        ]} onCancel={() => setModal(null)}
          onSubmit={async (v) => { await api.post("/v1/employee-documents", { ...v, employeeId: id }); toast.ok("Document added"); setModal(null); reloadAll(); }} />
      </Modal>
      <Modal open={modal === "login"} title="Create guard app login" onClose={() => setModal(null)}>
        <Form initial={{ phone: e.phone }} fields={[
          { name: "phone", label: "Mobile (login id)", half: true }, { name: "email", label: "or e-mail", half: true },
          { name: "password", label: "Initial password (min 6)", type: "password", required: true },
        ]} onCancel={() => setModal(null)}
          onSubmit={async (v) => { const r = await api.post(`/v1/employees/${id}/create-login`, v); toast.ok(`Login created: ${r.login}`); setModal(null); reloadAll(); }} />
      </Modal>
      <Modal open={modal === "exit"} title="Exit employee" onClose={() => setModal(null)}>
        <p className="mb-3 text-sm text-slate-600">Ends deployments, cancels future duties and disables the app login. Final settlement is paid in the next payroll run.</p>
        <Form fields={[{ name: "date", label: "Last working date", type: "date", required: true }, { name: "reason", label: "Reason", type: "textarea", required: true }]}
          onCancel={() => setModal(null)} submitLabel="Confirm exit"
          onSubmit={async (v) => { await act("exit", v)(); toast.ok("Employee exited"); setModal(null); }} />
      </Modal>
      <Modal open={modal === "suspend"} title="Suspend employee" onClose={() => setModal(null)}>
        <Form fields={[{ name: "reason", label: "Reason", type: "textarea", required: true }]} onCancel={() => setModal(null)} submitLabel="Suspend"
          onSubmit={async (v) => { await act("suspend", v)(); toast.ok("Suspended"); setModal(null); }} />
      </Modal>
    </div>
  );
}
