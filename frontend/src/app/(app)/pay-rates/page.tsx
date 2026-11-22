"use client";
import { useState } from "react";
import { CrudPage } from "@/components/CrudPage";
import { Tabs } from "@/components/ui";
import { opts, useClients, useEmployees, useServiceTypes, useSites, useBranches } from "@/lib/lookups";
import { fmtMoney } from "@/lib/format";

export default function PayRatesPage() {
  const [tab, setTab] = useState("rates");
  const st = useServiceTypes(), clients = useClients(), sites = useSites(), emps = useEmployees(), branches = useBranches();
  const scope = (r: any) => [
    r.employeeId && "Employee: " + (emps.map[r.employeeId] ?? "…"), r.siteId && "Site: " + (sites.map[r.siteId] ?? "…"),
    r.clientId && "Client: " + (clients.map[r.clientId] ?? "…"), r.branchId && "Branch: " + (branches.map[r.branchId] ?? "…"),
  ].filter(Boolean).join(" · ") || "Default";
  return (
    <div>
      <Tabs active={tab} onChange={setTab} tabs={[
        { key: "rates", label: "Pay rates" }, { key: "components", label: "Salary components" }, { key: "adj", label: "Advances / bonus / deductions" },
      ]} />
      {tab === "rates" && (
        <CrudPage title="Pay rates" search={false} writePerm="PAYRATE_WRITE" endpoint="/v1/pay-rates"
          subtitle="Each duty is paid at the most specific matching rate on that date: employee › site › client › service type › branch. This is how one guard earns different rates at different sites."
          filters={[{ name: "serviceTypeId", label: "Service", options: st.options }, { name: "siteId", label: "Site", options: sites.options }]}
          defaults={{ wageBasis: "MONTHLY", dutyAllowance: 0 }}
          columns={[
            { key: "serviceTypeId", label: "Service", render: (r) => st.map[r.serviceTypeId] ?? "Any" },
            { key: "scope", label: "Applies to", render: scope },
            { key: "wageBasis", label: "Basis" }, { key: "rate", label: "Rate", render: (r) => fmtMoney(r.rate) },
            { key: "dutyAllowance", label: "Allowance/duty", render: (r) => fmtMoney(r.dutyAllowance) },
            { key: "extraDutyRate", label: "Double-duty rate" }, { key: "otHourlyRate", label: "OT/hr" },
            { key: "effectiveFrom", label: "From" }, { key: "effectiveTo", label: "To" },
          ]}
          fields={[
            { name: "serviceTypeId", label: "Service type", type: "select", options: st.options, half: true },
            { name: "wageBasis", label: "Wage basis", type: "select", options: opts("MONTHLY", "DAILY", "HOURLY"), required: true, half: true },
            { name: "rate", label: "Rate", type: "number", required: true, half: true, help: "Monthly salary, per-day or per-hour amount" },
            { name: "dutyAllowance", label: "Allowance per duty", type: "number", half: true },
            { name: "extraDutyRate", label: "Double-duty rate (per duty)", type: "number", half: true },
            { name: "otHourlyRate", label: "OT hourly rate", type: "number", half: true, help: "Blank = derived from per-duty value × OT multiplier" },
            { name: "siteId", label: "Only at site", type: "select", options: sites.options, half: true },
            { name: "clientId", label: "Only for client", type: "select", options: clients.options, half: true },
            { name: "employeeId", label: "Only for employee", type: "select", options: emps.options, half: true },
            { name: "branchId", label: "Only in branch", type: "select", options: branches.options, half: true },
            { name: "effectiveFrom", label: "Effective from", type: "date", required: true, half: true },
            { name: "effectiveTo", label: "Effective to", type: "date", half: true },
            { name: "notes", label: "Notes" },
          ]} />
      )}
      {tab === "components" && (
        <CrudPage title="Salary components" search={false} writePerm="PAYROLL_APPROVE" endpoint="/v1/salary-components"
          subtitle="Statutory and other components. Sample PF/ESI/PT values are illustrative — validate with your payroll advisor."
          defaults={{ kind: "DEDUCTION", calcType: "PERCENT", base: "DUTY_WAGE", sequence: 100, active: true, effectiveFrom: "2000-01-01" }}
          columns={[{ key: "sequence", label: "#" }, { key: "code", label: "Code" }, { key: "name", label: "Name" }, { key: "kind", label: "Kind" },
            { key: "calcType", label: "Calc" }, { key: "base", label: "Base" }, { key: "percent", label: "%" }, { key: "fixedAmount", label: "Fixed" },
            { key: "baseCeiling", label: "Ceiling" }, { key: "appliesIfGrossUpto", label: "If gross ≤" }, { key: "stateCode", label: "State" }, { key: "active", label: "Active" }]}
          fields={[
            { name: "code", label: "Code", required: true, half: true }, { name: "name", label: "Name", required: true, half: true },
            { name: "kind", label: "Kind", type: "select", options: opts("EARNING", "DEDUCTION", "EMPLOYER_CONTRIBUTION"), required: true, half: true },
            { name: "calcType", label: "Calculation", type: "select", options: opts("PERCENT", "FIXED", "SLAB"), required: true, half: true },
            { name: "base", label: "Base", type: "select", options: opts("DUTY_WAGE", "OT_PAY", "GROSS"), half: true },
            { name: "percent", label: "Percent", type: "number", half: true }, { name: "fixedAmount", label: "Fixed amount", type: "number", half: true },
            { name: "baseCeiling", label: "Base ceiling (e.g. PF 15000)", type: "number", half: true },
            { name: "appliesIfGrossUpto", label: "Applies only if gross ≤", type: "number", half: true },
            { name: "stateCode", label: "State code (blank = all)", half: true },
            { name: "slabs", label: "Slabs JSON", type: "json", placeholder: '[{"upto":7500,"amount":0},{"upto":null,"amount":200}]' },
            { name: "sequence", label: "Sequence", type: "number", half: true }, { name: "effectiveFrom", label: "Effective from", type: "date", half: true },
            { name: "effectiveTo", label: "Effective to", type: "date", half: true }, { name: "active", label: "Active", type: "checkbox" },
          ]} />
      )}
      {tab === "adj" && (
        <CrudPage title="Employee adjustments" search={false} writePerm="PAYROLL_RUN" endpoint="/v1/employee-adjustments"
          subtitle="One-off payroll inputs for a month: advance recovery, loan EMI, uniform, fines, bonus, allowances."
          filters={[{ name: "employeeId", label: "Employee", options: emps.options }]}
          columns={[{ key: "periodMonth", label: "Month" }, { key: "employeeId", label: "Employee", render: (r) => emps.map[r.employeeId] },
            { key: "kind", label: "Kind" }, { key: "amount", label: "Amount", render: (r) => fmtMoney(r.amount) }, { key: "notes", label: "Notes" },
            { key: "payrollRunId", label: "Consumed", render: (r) => (r.payrollRunId ? "Yes" : "No") }]}
          fields={[
            { name: "employeeId", label: "Employee", type: "select", options: emps.options, required: true },
            { name: "periodMonth", label: "Payroll month (any date in it)", type: "date", required: true, half: true },
            { name: "kind", label: "Kind", type: "select", required: true, half: true,
              options: opts("ADVANCE_RECOVERY", "LOAN_EMI", "UNIFORM", "FINE", "BONUS", "ALLOWANCE", "OTHER_EARNING", "OTHER_DEDUCTION") },
            { name: "amount", label: "Amount", type: "number", required: true, half: true }, { name: "notes", label: "Notes", half: true },
          ]} />
      )}
    </div>
  );
}
