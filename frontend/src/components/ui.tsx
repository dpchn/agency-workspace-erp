"use client";
import { X, Loader2 } from "lucide-react";
import { useEffect, useState } from "react";
import { errMsg } from "@/lib/api";
import { useToast } from "./Toast";

export function Modal({ title, open, onClose, children, wide }: { title: string; open: boolean; onClose: () => void; children: React.ReactNode; wide?: boolean }) {
  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => { if (e.key === "Escape") onClose(); };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [open, onClose]);
  if (!open) return null;
  return (
    <div className="fixed inset-0 z-50 flex items-start justify-center overflow-y-auto bg-black/40 p-4" onMouseDown={(e) => e.target === e.currentTarget && onClose()}>
      <div className={`mt-10 w-full ${wide ? "max-w-4xl" : "max-w-lg"} rounded-lg bg-white shadow-xl`} role="dialog" aria-modal="true" aria-label={title}>
        <div className="flex items-center justify-between border-b px-4 py-3">
          <h2 className="font-semibold">{title}</h2>
          <button className="btn-ghost p-1" onClick={onClose} aria-label="Close"><X size={18} /></button>
        </div>
        <div className="p-4">{children}</div>
      </div>
    </div>
  );
}

const STATUS_COLORS: Record<string, string> = {
  ACTIVE: "green", APPROVED: "green", FILLED: "green", PRESENT: "green", PAID: "green", ACCEPTED: "green", VERIFIED: "green",
  COMPLETED: "green", CLOSED: "slate", LOCKED: "indigo", ISSUED: "blue", CONFIRMED: "blue", REVIEWED: "blue", RESOLVED: "blue",
  TRIAL: "blue", ACKNOWLEDGED: "blue", IN_PROGRESS: "blue", PLANNED: "slate", DRAFT: "slate", PENDING: "amber",
  PENDING_APPROVAL: "amber", PENDING_VERIFICATION: "amber", PARTIAL: "amber", HALF_DAY: "amber", PARTIALLY_PAID: "amber",
  FLAGGED: "amber", OPEN: "red", ABSENT: "red", NO_SHOW: "red", REJECTED: "red", OVERDUE: "red", DISPUTED: "red",
  SUSPENDED: "red", CRITICAL: "red", HIGH: "orange", MEDIUM: "amber", LOW: "slate", EXITED: "slate", CANCELLED: "slate",
  SUPERSEDED: "slate", ON_LEAVE: "violet", INACTIVE: "slate", UNPAID: "amber",
};
const COLOR_CLS: Record<string, string> = {
  green: "bg-emerald-50 text-emerald-700 ring-emerald-600/20", blue: "bg-blue-50 text-blue-700 ring-blue-600/20",
  amber: "bg-amber-50 text-amber-800 ring-amber-600/20", red: "bg-red-50 text-red-700 ring-red-600/20",
  slate: "bg-slate-100 text-slate-600 ring-slate-500/20", indigo: "bg-indigo-50 text-indigo-700 ring-indigo-600/20",
  orange: "bg-orange-50 text-orange-700 ring-orange-600/20", violet: "bg-violet-50 text-violet-700 ring-violet-600/20",
};

export function Badge({ value }: { value?: string | null }) {
  if (!value) return <span className="text-slate-400">-</span>;
  const c = COLOR_CLS[STATUS_COLORS[value] ?? "slate"];
  return <span className={`inline-flex whitespace-nowrap rounded px-1.5 py-0.5 text-xs font-medium ring-1 ring-inset ${c}`}>{value.replace(/_/g, " ")}</span>;
}

export function PageHeader({ title, subtitle, actions }: { title: string; subtitle?: string; actions?: React.ReactNode }) {
  return (
    <div className="mb-4 flex flex-wrap items-end justify-between gap-3">
      <div>
        <h1 className="text-xl font-semibold">{title}</h1>
        {subtitle && <p className="text-sm text-slate-500">{subtitle}</p>}
      </div>
      {actions && <div className="flex flex-wrap gap-2">{actions}</div>}
    </div>
  );
}

export function Loading() {
  return <div className="flex items-center gap-2 p-6 text-sm text-slate-500"><Loader2 className="animate-spin" size={16} /> Loading…</div>;
}
export function ErrorBox({ error, onRetry }: { error: string; onRetry?: () => void }) {
  return (
    <div className="rounded-md border border-red-200 bg-red-50 p-3 text-sm text-red-700">
      {error} {onRetry && <button className="ml-2 underline" onClick={onRetry}>Retry</button>}
    </div>
  );
}
export function Empty({ text = "Nothing here yet." }: { text?: string }) {
  return <div className="p-6 text-center text-sm text-slate-500">{text}</div>;
}

/** Button that runs an async action, shows spinner, toasts success/error. */
export function ActionButton({ onClick, children, className = "btn-secondary", success, confirm, disabled, title }: {
  onClick: () => Promise<unknown>; children: React.ReactNode; className?: string; success?: string; confirm?: string; disabled?: boolean; title?: string;
}) {
  const [busy, setBusy] = useState(false);
  const toast = useToast();
  return (
    <button
      className={className}
      disabled={busy || disabled}
      title={title}
      onClick={async () => {
        if (confirm && !window.confirm(confirm)) return;
        setBusy(true);
        try {
          await onClick();
          if (success) toast.ok(success);
        } catch (e) {
          toast.err(errMsg(e));
        } finally {
          setBusy(false);
        }
      }}
    >
      {busy && <Loader2 className="animate-spin" size={14} />} {children}
    </button>
  );
}

export function Stat({ label, value, hint, tone }: { label: string; value: React.ReactNode; hint?: string; tone?: "red" | "green" | "amber" }) {
  const t = tone === "red" ? "text-red-600" : tone === "green" ? "text-emerald-600" : tone === "amber" ? "text-amber-600" : "text-slate-900";
  return (
    <div className="card">
      <div className="text-xs font-medium uppercase tracking-wide text-slate-500">{label}</div>
      <div className={`mt-1 text-2xl font-semibold ${t}`}>{value}</div>
      {hint && <div className="mt-0.5 text-xs text-slate-500">{hint}</div>}
    </div>
  );
}

export function Tabs({ tabs, active, onChange }: { tabs: { key: string; label: string }[]; active: string; onChange: (k: string) => void }) {
  return (
    <div className="mb-4 flex gap-1 overflow-x-auto border-b">
      {tabs.map((t) => (
        <button key={t.key} onClick={() => onChange(t.key)}
          className={`whitespace-nowrap border-b-2 px-3 py-2 text-sm ${active === t.key ? "border-brand font-medium text-brand" : "border-transparent text-slate-500 hover:text-slate-800"}`}>
          {t.label}
        </button>
      ))}
    </div>
  );
}
