"use client";
import { useState } from "react";
import { CrudPage } from "@/components/CrudPage";
import { Badge, Tabs } from "@/components/ui";
import { opts, useBranches } from "@/lib/lookups";

const DOCS = ["POLICE_VERIFICATION", "ID_PROOF", "TRAINING", "ARMS_LICENSE", "MEDICAL", "ADDRESS_PROOF"];

export default function MastersPage() {
  const [tab, setTab] = useState("branches");
  const branches = useBranches();
  return (
    <div>
      <Tabs active={tab} onChange={setTab} tabs={[
        { key: "branches", label: "Branches" }, { key: "service", label: "Service types" },
        { key: "shifts", label: "Shift templates" }, { key: "holidays", label: "Holidays" },
      ]} />
      {tab === "branches" && (
        <CrudPage title="Branches" endpoint="/v1/branches" writePerm="MASTER_WRITE" defaults={{ active: true }}
          columns={[{ key: "code", label: "Code" }, { key: "name", label: "Name" }, { key: "stateCode", label: "State" }, { key: "gstin", label: "GSTIN" },
            { key: "active", label: "Active" }]}
          fields={[{ name: "code", label: "Code", required: true, half: true }, { name: "name", label: "Name", required: true, half: true },
            { name: "stateCode", label: "State code", half: true }, { name: "gstin", label: "GSTIN", half: true },
            { name: "address", label: "Address", type: "textarea" }, { name: "active", label: "Active", type: "checkbox" }]} />
      )}
      {tab === "service" && (
        <CrudPage title="Service types" subtitle="Kinds of worker on a post: guard, gunman, supervisor, housekeeping…" endpoint="/v1/service-types" writePerm="MASTER_WRITE"
          defaults={{ active: true, category: "SECURITY", defaultShiftHours: 8 }}
          columns={[{ key: "code", label: "Code" }, { key: "name", label: "Name" }, { key: "category", label: "Category" },
            { key: "defaultShiftHours", label: "Shift hrs" }, { key: "requiredDocuments", label: "Required documents" }, { key: "active", label: "Active" }]}
          fields={[{ name: "code", label: "Code", required: true, half: true }, { name: "name", label: "Name", required: true, half: true },
            { name: "category", label: "Category", type: "select", options: opts("SECURITY", "FACILITY", "OTHER"), half: true },
            { name: "defaultShiftHours", label: "Default shift hours", type: "number", half: true },
            { name: "requiredDocuments", label: "Documents required before deployment", type: "multiselect", options: opts(...DOCS) },
            { name: "active", label: "Active", type: "checkbox" }]} />
      )}
      {tab === "shifts" && (
        <CrudPage title="Shift templates" subtitle="End before start means the shift crosses midnight." endpoint="/v1/shift-templates" writePerm="MASTER_WRITE" search={false}
          defaults={{ active: true, breakMinutes: 0 }}
          columns={[{ key: "code", label: "Code" }, { key: "name", label: "Name" }, { key: "startTime", label: "Start" }, { key: "endTime", label: "End" },
            { key: "breakMinutes", label: "Break (min)" }, { key: "active", label: "Active" }]}
          fields={[{ name: "code", label: "Code", required: true, half: true }, { name: "name", label: "Name", required: true, half: true },
            { name: "startTime", label: "Start", type: "time", required: true, half: true }, { name: "endTime", label: "End", type: "time", required: true, half: true },
            { name: "breakMinutes", label: "Break minutes", type: "number", half: true }, { name: "active", label: "Active", type: "checkbox" }]} />
      )}
      {tab === "holidays" && (
        <CrudPage title="Holidays" subtitle="Duties on holidays get the holiday pay/bill multiplier." endpoint="/v1/holidays" writePerm="MASTER_WRITE" search={false}
          columns={[{ key: "holidayDate", label: "Date" }, { key: "name", label: "Name" },
            { key: "branchId", label: "Branch", render: (r) => r.branchId ? branches.map[r.branchId] : <Badge value="ALL" /> }]}
          fields={[{ name: "holidayDate", label: "Date", type: "date", required: true, half: true }, { name: "name", label: "Name", required: true, half: true },
            { name: "branchId", label: "Branch (blank = all)", type: "select", options: branches.options }]} />
      )}
    </div>
  );
}
