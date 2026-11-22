"use client";
import Link from "next/link";
import { useState } from "react";
import { api } from "@/lib/api";
import { useSession } from "@/lib/session";
import { Form } from "@/components/Form";

const STATES = [["27", "Maharashtra"], ["29", "Karnataka"], ["07", "Delhi"], ["33", "Tamil Nadu"], ["36", "Telangana"], ["24", "Gujarat"],
  ["09", "Uttar Pradesh"], ["19", "West Bengal"], ["06", "Haryana"], ["32", "Kerala"], ["08", "Rajasthan"], ["23", "Madhya Pradesh"]];

export default function SignupPage() {
  const { login } = useSession();
  const [done, setDone] = useState(false);
  return (
    <div className="space-y-3">
      <h2 className="text-lg font-semibold">Register your agency</h2>
      <p className="text-sm text-slate-500">Free trial. Your agency gets its own private workspace; default service types, shifts and payroll rules are created for you and can be edited.</p>
      {!done && (
        <Form submitLabel="Create agency" fields={[
          { name: "agencyName", label: "Agency name", required: true },
          { name: "stateCode", label: "State (for GST)", type: "select", options: STATES.map(([v, l]) => ({ value: v, label: `${l} (${v})` })), half: true },
          { name: "gstin", label: "GSTIN (optional)", half: true },
          { name: "ownerName", label: "Your name", required: true },
          { name: "ownerEmail", label: "E-mail", type: "email", required: true, half: true },
          { name: "ownerPhone", label: "Mobile", half: true },
          { name: "password", label: "Password (min 8)", type: "password", required: true },
        ]} onSubmit={async (v) => {
          await api.post("/v1/tenants", v);
          setDone(true);
          await login(v.ownerEmail, v.password);
          location.href = "/";
        }} />
      )}
      {done && <p className="text-sm text-emerald-700">Agency created. Signing you in…</p>}
      <p className="text-center text-sm text-slate-500">Already registered? <Link href="/login" className="text-brand underline">Sign in</Link></p>
    </div>
  );
}
