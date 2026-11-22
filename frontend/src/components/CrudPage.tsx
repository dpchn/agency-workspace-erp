"use client";
import { useState } from "react";
import { Plus, Pencil } from "lucide-react";
import { api, qs, Page } from "@/lib/api";
import { useApi } from "@/lib/hooks";
import { DataTable, Column } from "./DataTable";
import { Form, Field } from "./Form";
import { ErrorBox, Loading, Modal, PageHeader } from "./ui";
import { useToast } from "./Toast";
import { useSession } from "@/lib/session";

export type Filter = { name: string; label: string; options?: { value: string; label: string }[]; type?: "text" | "date" };

/**
 * Config-driven list + create/edit for master data endpoints that follow the
 * CrudSupport contract: GET list (paged), POST create, PATCH update.
 */
export function CrudPage({ title, subtitle, endpoint, columns, fields, filters = [], search = true, writePerm, defaults = {},
  rowActions, onRowClick, embedded, fixedParams = {} }: {
  title: string;
  subtitle?: string;
  endpoint: string;
  columns: Column<any>[];
  fields: Field[];
  filters?: Filter[];
  search?: boolean;
  writePerm?: string;
  defaults?: Record<string, any>;
  rowActions?: (row: any, reload: () => void) => React.ReactNode;
  onRowClick?: (row: any) => void;
  embedded?: boolean;
  fixedParams?: Record<string, string>;
}) {
  const { can } = useSession();
  const toast = useToast();
  const [q, setQ] = useState("");
  const [f, setF] = useState<Record<string, string>>({});
  const [page, setPage] = useState(0);
  const [edit, setEdit] = useState<any | null>(null);
  const size = 50;
  const { data, error, loading, reload } = useApi<Page<any>>(endpoint + qs({ ...fixedParams, ...f, q: q || undefined, page, size }));
  const writable = !writePerm || can(writePerm);

  const cols: Column<any>[] = [...columns];
  if (writable || rowActions) {
    cols.push({
      key: "_actions", label: "", className: "text-right",
      render: (r) => (
        <div className="flex justify-end gap-1" onClick={(e) => e.stopPropagation()}>
          {rowActions?.(r, reload)}
          {writable && <button className="btn-ghost p-1" aria-label="Edit" onClick={() => setEdit(r)}><Pencil size={14} /></button>}
        </div>
      ),
    });
  }

  return (
    <div className={embedded ? "" : ""}>
      <PageHeader title={title} subtitle={subtitle} actions={writable && (
        <button className="btn-primary" onClick={() => setEdit({ ...defaults, ...fixedParams })}><Plus size={16} /> New</button>
      )} />
      <div className="card p-0">
        {(search || filters.length > 0) && (
          <div className="flex flex-wrap gap-2 border-b p-3">
            {search && <input className="input max-w-xs" placeholder="Search…" value={q} onChange={(e) => { setQ(e.target.value); setPage(0); }} aria-label="Search" />}
            {filters.map((fl) => fl.options ? (
              <select key={fl.name} className="input w-auto" value={f[fl.name] ?? ""} aria-label={fl.label}
                onChange={(e) => { setF({ ...f, [fl.name]: e.target.value }); setPage(0); }}>
                <option value="">{fl.label}: all</option>
                {fl.options.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
              </select>
            ) : (
              <input key={fl.name} type={fl.type ?? "text"} className="input w-auto" aria-label={fl.label} placeholder={fl.label}
                value={f[fl.name] ?? ""} onChange={(e) => setF({ ...f, [fl.name]: e.target.value })} />
            ))}
          </div>
        )}
        {error && <div className="p-3"><ErrorBox error={error} onRetry={reload} /></div>}
        {loading && !data ? <Loading /> : <DataTable columns={cols} rows={data?.items ?? []} onRowClick={onRowClick} />}
        {data && data.total > size && (
          <div className="flex items-center justify-between border-t p-3 text-sm text-slate-500">
            <span>{data.page * size + 1}–{Math.min((data.page + 1) * size, data.total)} of {data.total}</span>
            <div className="flex gap-2">
              <button className="btn-secondary" disabled={page === 0} onClick={() => setPage(page - 1)}>Prev</button>
              <button className="btn-secondary" disabled={(page + 1) * size >= data.total} onClick={() => setPage(page + 1)}>Next</button>
            </div>
          </div>
        )}
      </div>
      <Modal open={!!edit} title={(edit?.id ? "Edit " : "New ") + title.replace(/s$/, "").toLowerCase()} onClose={() => setEdit(null)} wide={fields.length > 8}>
        {edit && (
          <Form fields={fields} initial={edit} isEdit={!!edit.id} onCancel={() => setEdit(null)} onSubmit={async (v) => {
            if (edit.id) await api.patch(`${endpoint}/${edit.id}`, { ...v, version: edit.version });
            else await api.post(endpoint, v);
            toast.ok("Saved");
            setEdit(null);
            reload();
          }} />
        )}
      </Modal>
    </div>
  );
}
