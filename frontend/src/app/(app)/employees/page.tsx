"use client";
import { useRouter, useSearchParams } from "next/navigation";
import { Suspense, useState } from "react";
import { CrudPage } from "@/components/CrudPage";
import { Badge, Tabs, PageHeader, Loading } from "@/components/ui";
import { DataTable } from "@/components/DataTable";
import { opts, useBranches, useServiceTypes } from "@/lib/lookups";
import { useApi } from "@/lib/hooks";
import { employeeFields } from "./fields";

export default function EmployeesPage() {
  return <Suspense fallback={<Loading />}><Employees /></Suspense>;
}

function Employees() {
  const router = useRouter();
  const sp = useSearchParams();
  const [tab, setTab] = useState(sp.get("tab") === "expiring" ? "expiring" : "list");
  const branches = useBranches(), st = useServiceTypes();
  return (
    <div>
      <Tabs active={tab} onChange={setTab} tabs={[{ key: "list", label: "Employees" }, { key: "expiring", label: "Expiring documents" }]} />
      {tab === "list" ? (
        <CrudPage title="Employees" subtitle="Click a row for documents, lifecycle and app login." endpoint="/v1/employees" writePerm="HR_WRITE"
          onRowClick={(r) => router.push(`/employees/${r.id}`)}
          filters={[
            { name: "status", label: "Status", options: opts("DRAFT", "PENDING_VERIFICATION", "ACTIVE", "SUSPENDED", "EXITED") },
            { name: "branchId", label: "Branch", options: branches.options },
            { name: "serviceTypeId", label: "Service", options: st.options },
          ]}
          columns={[
            { key: "employeeCode", label: "Code" },
            { key: "name", label: "Name", render: (r) => `${r.firstName} ${r.lastName ?? ""}` },
            { key: "phone", label: "Mobile" },
            { key: "serviceTypeId", label: "Service", render: (r) => st.map[r.serviceTypeId] },
            { key: "branchId", label: "Branch", render: (r) => branches.map[r.branchId] },
            { key: "status", label: "Status", render: (r) => <Badge value={r.status} /> },
          ]}
          fields={employeeFields(branches, st)} />
      ) : <Expiring />}
    </div>
  );
}

function Expiring() {
  const { data, loading } = useApi<any[]>("/v1/employee-documents/expiring?days=60");
  return (
    <div>
      <PageHeader title="Compliance calendar" subtitle="Documents expired or expiring in the next 60 days." />
      <div className="card p-0">
        {loading ? <Loading /> : (
          <DataTable rows={data ?? []} empty="No documents expiring soon." columns={[
            { key: "employee_code", label: "Code" }, { key: "employee_name", label: "Employee" }, { key: "doc_type", label: "Document" },
            { key: "doc_number", label: "Number" }, { key: "expires_on", label: "Expires" },
            { key: "expired", label: "State", render: (r) => <Badge value={r.expired ? "EXPIRED" : "EXPIRING"} /> },
          ]} />
        )}
      </div>
    </div>
  );
}
