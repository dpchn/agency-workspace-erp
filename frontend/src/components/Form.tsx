"use client";
import { useState } from "react";
import { errMsg, ApiError } from "@/lib/api";

export type FieldType = "text" | "number" | "date" | "time" | "datetime" | "select" | "checkbox" | "textarea" | "email" | "password" | "multiselect" | "days" | "json";

export type Field = {
  name: string;
  label: string;
  type?: FieldType;
  options?: { value: string; label: string }[];
  required?: boolean;
  placeholder?: string;
  help?: string;
  half?: boolean;
  hideOnEdit?: boolean;
};

const DAYS = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"];

function toInput(f: Field, v: any) {
  if (v === null || v === undefined) return f.type === "checkbox" ? false : "";
  if (f.type === "datetime" && typeof v === "string") {
    const d = new Date(v);
    if (!Number.isNaN(d.getTime())) {
      const off = d.getTimezoneOffset() * 60000;
      return new Date(d.getTime() - off).toISOString().slice(0, 16);
    }
  }
  if (f.type === "time" && typeof v === "string") return v.slice(0, 5);
  if (f.type === "json") return typeof v === "string" ? v : JSON.stringify(v, null, 2);
  return v;
}

function fromInput(f: Field, v: any) {
  if (f.type === "checkbox") return !!v;
  if (v === "" || v === undefined) return null;
  if (f.type === "number") return Number(v);
  if (f.type === "datetime") return new Date(v).toISOString();
  if (f.type === "json") return JSON.parse(v);
  return v;
}

/** Generic form. onSubmit receives cleaned values; thrown ApiError field errors are shown inline. */
export function Form({ fields, initial = {}, onSubmit, submitLabel = "Save", onCancel, isEdit }: {
  fields: Field[];
  initial?: Record<string, any>;
  onSubmit: (values: Record<string, any>) => Promise<unknown>;
  submitLabel?: string;
  onCancel?: () => void;
  isEdit?: boolean;
}) {
  const [vals, setVals] = useState<Record<string, any>>(() => {
    const o: Record<string, any> = {};
    fields.forEach((f) => { o[f.name] = toInput(f, initial[f.name]); });
    return o;
  });
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [formErr, setFormErr] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const set = (k: string, v: any) => setVals((s) => ({ ...s, [k]: v }));

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setErrors({});
    setFormErr(null);
    const out: Record<string, any> = {};
    try {
      for (const f of fields) {
        if (isEdit && f.hideOnEdit) continue;
        out[f.name] = fromInput(f, vals[f.name]);
        if (f.required && (out[f.name] === null || (Array.isArray(out[f.name]) && !out[f.name].length))) {
          setErrors({ [f.name]: "Required" });
          return;
        }
      }
    } catch {
      setFormErr("Invalid JSON");
      return;
    }
    setBusy(true);
    try {
      await onSubmit(out);
    } catch (err) {
      const a = err as ApiError;
      if (a?.fieldErrors) setErrors(a.fieldErrors);
      setFormErr(errMsg(err));
    } finally {
      setBusy(false);
    }
  };

  return (
    <form onSubmit={submit} className="grid grid-cols-2 gap-3">
      {fields.filter((f) => !(isEdit && f.hideOnEdit)).map((f) => (
        <div key={f.name} className={f.half ? "col-span-2 sm:col-span-1" : "col-span-2"}>
          {f.type !== "checkbox" && <label className="label" htmlFor={"f-" + f.name}>{f.label}{f.required && " *"}</label>}
          <Input f={f} value={vals[f.name]} onChange={(v) => set(f.name, v)} />
          {f.help && <p className="mt-0.5 text-xs text-slate-500">{f.help}</p>}
          {errors[f.name] && <p className="mt-0.5 text-xs text-red-600">{errors[f.name]}</p>}
        </div>
      ))}
      {formErr && <div className="col-span-2 rounded bg-red-50 p-2 text-sm text-red-700">{formErr}</div>}
      <div className="col-span-2 flex justify-end gap-2 pt-1">
        {onCancel && <button type="button" className="btn-secondary" onClick={onCancel}>Cancel</button>}
        <button className="btn-primary" disabled={busy}>{busy ? "Saving…" : submitLabel}</button>
      </div>
    </form>
  );
}

function Input({ f, value, onChange }: { f: Field; value: any; onChange: (v: any) => void }) {
  const id = "f-" + f.name;
  switch (f.type) {
    case "select":
      return (
        <select id={id} className="input" value={value ?? ""} onChange={(e) => onChange(e.target.value)}>
          <option value="">—</option>
          {f.options?.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
        </select>
      );
    case "multiselect": {
      const arr: string[] = Array.isArray(value) ? value : [];
      return (
        <div className="flex max-h-36 flex-wrap gap-2 overflow-y-auto rounded-md border border-slate-300 p-2">
          {f.options?.map((o) => (
            <label key={o.value} className="flex items-center gap-1 text-sm">
              <input type="checkbox" checked={arr.includes(o.value)}
                onChange={() => onChange(arr.includes(o.value) ? arr.filter((x) => x !== o.value) : [...arr, o.value])} />
              {o.label}
            </label>
          ))}
          {!f.options?.length && <span className="text-xs text-slate-400">No options</span>}
        </div>
      );
    }
    case "days": {
      const arr: number[] = Array.isArray(value) ? value : [];
      return (
        <div className="flex flex-wrap gap-1">
          {DAYS.map((d, i) => {
            const n = i + 1;
            const on = arr.includes(n);
            return (
              <button type="button" key={d} onClick={() => onChange(on ? arr.filter((x) => x !== n) : [...arr, n].sort())}
                className={`rounded px-2 py-1 text-xs ${on ? "bg-brand text-brand-fg" : "bg-slate-100 text-slate-600"}`}>{d}</button>
            );
          })}
        </div>
      );
    }
    case "checkbox":
      return (
        <label className="flex items-center gap-2 text-sm">
          <input id={id} type="checkbox" checked={!!value} onChange={(e) => onChange(e.target.checked)} /> {f.label}
        </label>
      );
    case "textarea":
    case "json":
      return <textarea id={id} className="input min-h-[80px] font-mono text-xs" value={value ?? ""} placeholder={f.placeholder} onChange={(e) => onChange(e.target.value)} />;
    default:
      return (
        <input id={id} className="input" placeholder={f.placeholder} value={value ?? ""}
          type={f.type === "datetime" ? "datetime-local" : f.type === "number" ? "number" : f.type || "text"}
          step={f.type === "number" ? "any" : undefined}
          onChange={(e) => onChange(e.target.value)} />
      );
  }
}
