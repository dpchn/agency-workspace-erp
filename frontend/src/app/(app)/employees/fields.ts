import type { Field } from "@/components/Form";
import { opts } from "@/lib/lookups";

type Lookup = { options: { value: string; label: string }[] };

export const employeeFields = (branches: Lookup, st: Lookup): Field[] => [
  { name: "employeeCode", label: "Employee code", required: true, half: true },
  { name: "firstName", label: "First name", required: true, half: true },
  { name: "lastName", label: "Last name", half: true },
  { name: "phone", label: "Mobile", half: true },
  { name: "email", label: "E-mail", half: true },
  { name: "gender", label: "Gender", type: "select", options: opts("MALE", "FEMALE", "OTHER"), half: true },
  { name: "dateOfBirth", label: "Date of birth", type: "date", half: true },
  { name: "joinedOn", label: "Joined on", type: "date", half: true },
  { name: "branchId", label: "Branch", type: "select", options: branches.options, required: true, half: true },
  { name: "serviceTypeId", label: "Primary service type", type: "select", options: st.options, required: true, half: true },
  { name: "qualifiedServiceTypeIds", label: "Also qualified for", type: "multiselect", options: st.options },
  { name: "weeklyOffDay", label: "Weekly off (1=Mon…7=Sun)", type: "number", half: true },
  { name: "uan", label: "UAN (PF)", half: true },
  { name: "esicNo", label: "ESIC no", half: true },
  { name: "pan", label: "PAN", half: true },
  { name: "idNumberLast4", label: "ID last 4 digits", half: true },
  { name: "bankAccountNo", label: "Bank account", half: true },
  { name: "bankIfsc", label: "IFSC", half: true },
  { name: "bankName", label: "Bank name", half: true },
  { name: "emergencyContact", label: "Emergency contact", half: true },
  { name: "address", label: "Address", type: "textarea" },
];
