"use client";
import { CrudPage } from "@/components/CrudPage";
import { Badge } from "@/components/ui";
import { opts } from "@/lib/lookups";

export default function ClientsPage() {
  return (
    <CrudPage title="Clients" subtitle="Legal entities you bill. Each client can have many sites." endpoint="/v1/clients" writePerm="CLIENT_WRITE"
      filters={[{ name: "status", label: "Status", options: opts("ACTIVE", "INACTIVE") }]}
      columns={[
        { key: "code", label: "Code" }, { key: "legalName", label: "Legal name" }, { key: "gstin", label: "GSTIN" },
        { key: "stateCode", label: "State" }, { key: "contactName", label: "Contact" }, { key: "paymentTermsDays", label: "Terms (days)" },
        { key: "status", label: "Status", render: (r) => <Badge value={r.status} /> },
      ]}
      defaults={{ paymentTermsDays: 30, status: "ACTIVE" }}
      fields={[
        { name: "code", label: "Code", required: true, half: true }, { name: "legalName", label: "Legal name", required: true, half: true },
        { name: "displayName", label: "Display name", half: true }, { name: "gstin", label: "GSTIN", half: true, help: "State code derived from GSTIN" },
        { name: "pan", label: "PAN", half: true }, { name: "stateCode", label: "State code (place of supply)", half: true },
        { name: "billingAddress", label: "Billing address", type: "textarea" },
        { name: "contactName", label: "Contact name", half: true }, { name: "contactPhone", label: "Contact phone", half: true },
        { name: "contactEmail", label: "Contact e-mail", half: true }, { name: "paymentTermsDays", label: "Payment terms (days)", type: "number", half: true },
        { name: "creditLimit", label: "Credit limit", type: "number", half: true }, { name: "status", label: "Status", type: "select", options: opts("ACTIVE", "INACTIVE"), half: true },
      ]} />
  );
}
