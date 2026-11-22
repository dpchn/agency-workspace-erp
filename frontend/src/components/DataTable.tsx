"use client";
import { Empty } from "./ui";

export type Column<T> = { key: string; label: string; render?: (row: T) => React.ReactNode; className?: string };

export function DataTable<T extends Record<string, any>>({ columns, rows, onRowClick, selectable, selected, onSelect, rowKey = "id", empty }: {
  columns: Column<T>[];
  rows: T[];
  onRowClick?: (row: T) => void;
  selectable?: boolean;
  selected?: Set<string>;
  onSelect?: (s: Set<string>) => void;
  rowKey?: string;
  empty?: string;
}) {
  if (!rows.length) return <Empty text={empty} />;
  const all = selectable && selected && rows.every((r) => selected.has(r[rowKey]));
  return (
    <div className="overflow-x-auto">
      <table className="table">
        <thead>
          <tr>
            {selectable && (
              <th className="w-8">
                <input type="checkbox" aria-label="Select all" checked={!!all}
                  onChange={() => onSelect?.(all ? new Set() : new Set(rows.map((r) => r[rowKey])))} />
              </th>
            )}
            {columns.map((c) => <th key={c.key} className={c.className}>{c.label}</th>)}
          </tr>
        </thead>
        <tbody>
          {rows.map((r, i) => (
            <tr key={r[rowKey] ?? i} className={onRowClick ? "cursor-pointer hover:bg-slate-50" : ""} onClick={() => onRowClick?.(r)}>
              {selectable && (
                <td onClick={(e) => e.stopPropagation()}>
                  <input type="checkbox" aria-label="Select row" checked={!!selected?.has(r[rowKey])}
                    onChange={() => {
                      const s = new Set(selected);
                      s.has(r[rowKey]) ? s.delete(r[rowKey]) : s.add(r[rowKey]);
                      onSelect?.(s);
                    }} />
                </td>
              )}
              {columns.map((c) => (
                <td key={c.key} className={c.className}>{c.render ? c.render(r) : formatCell(r[c.key])}</td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function formatCell(v: unknown): React.ReactNode {
  if (v === null || v === undefined || v === "") return <span className="text-slate-400">-</span>;
  if (typeof v === "boolean") return v ? "Yes" : "No";
  if (Array.isArray(v)) return v.join(", ");
  if (typeof v === "object") return JSON.stringify(v);
  return String(v);
}
