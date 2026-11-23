"use client";
import { useEffect, useState } from "react";
import { Camera } from "lucide-react";
import { tokenStore } from "@/lib/api";
import { apiUrl } from "@/config/brand";
import { Modal } from "./ui";

/** Protected photo (needs the bearer token, so it is fetched as a blob). Click to enlarge. */
export function AuthImage({ id, className = "h-10 w-10", alt = "Photo", zoom = true }: { id?: string | null; className?: string; alt?: string; zoom?: boolean }) {
  const [url, setUrl] = useState<string | null>(null);
  const [failed, setFailed] = useState(false);
  const [open, setOpen] = useState(false);
  useEffect(() => {
    if (!id) return;
    let u: string | null = null, live = true;
    fetch(`${apiUrl}/v1/attendance/photos/${id}`, { headers: { Authorization: `Bearer ${tokenStore.get()}` } })
      .then((r) => (r.ok ? r.blob() : Promise.reject()))
      .then((b) => { if (live) { u = URL.createObjectURL(b); setUrl(u); } })
      .catch(() => live && setFailed(true));
    return () => { live = false; if (u) URL.revokeObjectURL(u); };
  }, [id]);
  if (!id) return <span className={`${className} inline-flex items-center justify-center rounded bg-slate-100 text-slate-400`}><Camera size={14} /></span>;
  if (failed || !url) return <span className={`${className} inline-block animate-pulse rounded bg-slate-100`} title={failed ? "Photo unavailable" : "Loading"} />;
  return (
    <>
      {/* eslint-disable-next-line @next/next/no-img-element */}
      <img src={url} alt={alt} className={`${className} rounded object-cover ${zoom ? "cursor-zoom-in" : ""}`}
        onClick={(e) => { if (zoom) { e.stopPropagation(); setOpen(true); } }} />
      {zoom && (
        <Modal open={open} title={alt} onClose={() => setOpen(false)} wide>
          {/* eslint-disable-next-line @next/next/no-img-element */}
          <img src={url} alt={alt} className="mx-auto max-h-[75vh] rounded" />
        </Modal>
      )}
    </>
  );
}

/** Small pill for how a duty was marked. */
const SOURCE_LABEL: Record<string, [string, string]> = {
  MOBILE: ["Own phone", "bg-emerald-50 text-emerald-700"], KIOSK: ["Kiosk+PIN", "bg-blue-50 text-blue-700"],
  SUPERVISOR: ["Supervisor", "bg-amber-50 text-amber-800"], MANUAL: ["Register", "bg-violet-50 text-violet-700"],
};
export function SourceBadges({ sources }: { sources?: string[] | null }) {
  if (!sources?.length) return <span className="text-slate-400">-</span>;
  return (
    <span className="flex flex-wrap gap-1">
      {sources.map((s) => {
        const [l, c] = SOURCE_LABEL[s] ?? [s, "bg-slate-100 text-slate-600"];
        return <span key={s} className={`rounded px-1.5 py-0.5 text-[10px] font-medium ${c}`} title={s}>{l}</span>;
      })}
    </span>
  );
}
