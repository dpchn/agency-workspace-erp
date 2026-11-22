"use client";
import { useRouter } from "next/navigation";
import { CrudPage } from "@/components/CrudPage";
import { Badge } from "@/components/ui";
import { opts, useClients } from "@/lib/lookups";

export default function ContractsPage() {
  const router = useRouter();
  const clients = useClients();
  return (
    <CrudPage title="Contracts" subtitle="What you charge each client. Rates are versioned and effective-dated; click a contract to manage versions."
      endpoint="/v1/contracts" writePerm="CONTRACT_WRITE" onRowClick={(r) => router.push(`/contracts/${r.id}`)}
      filters={[{ name: "clientId", label: "Client", options: clients.options }, { name: "status", label: "Status", options: opts("DRAFT", "ACTIVE", "SUSPENDED", "TERMINATED", "EXPIRED") }]}
      columns={[
        { key: "contractNo", label: "Contract no" }, { key: "title", label: "Title" },
        { key: "clientId", label: "Client", render: (r) => clients.map[r.clientId] },
        { key: "startDate", label: "Start" }, { key: "endDate", label: "End" },
        { key: "status", label: "Status", render: (r) => <Badge value={r.status} /> },
      ]}
      defaults={{ autoRenew: false }}
      fields={[
        { name: "clientId", label: "Client", type: "select", options: clients.options, required: true, hideOnEdit: true },
        { name: "contractNo", label: "Contract no", required: true, half: true }, { name: "title", label: "Title", half: true },
        { name: "startDate", label: "Start date", type: "date", required: true, half: true }, { name: "endDate", label: "End date", type: "date", half: true },
        { name: "paymentTermsDays", label: "Payment terms (days)", type: "number", half: true }, { name: "autoRenew", label: "Auto renew", type: "checkbox" },
      ]} />
  );
}
