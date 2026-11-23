"use client";
import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import { LogOut, Menu, X } from "lucide-react";
import { ChangePasswordButton } from "@/components/ChangePassword";
import { useSession } from "@/lib/session";
import { NAV, allowed, homeFor } from "./nav";
import { Loading } from "./ui";
import { brand } from "@/config/brand";

export function Brand({ compact }: { compact?: boolean }) {
  const { displayName, logo } = useSession();
  return (
    <div className="flex items-center gap-2">
      {/* eslint-disable-next-line @next/next/no-img-element */}
      <img src={logo} alt="" className="h-8 w-8" />
      {!compact && (
        <div className="leading-tight">
          <div className="font-semibold">{displayName}</div>
          {displayName !== brand.name && <div className="text-[10px] uppercase tracking-wide text-slate-400">powered by {brand.name}</div>}
        </div>
      )}
    </div>
  );
}

export function TenantSwitcher() {
  const { me, switchTenant } = useSession();
  if (!me || me.memberships.length < 2) return null;
  return (
    <select className="input w-auto py-1 text-xs" value={me.tenant?.id} aria-label="Switch organisation"
      onChange={async (e) => { await switchTenant(e.target.value); location.href = "/"; }}>
      {me.memberships.map((m) => <option key={m.tenantId} value={m.tenantId}>{m.tenantName} ({m.role})</option>)}
    </select>
  );
}

/** Authenticated staff layout: sidebar filtered by permissions. */
export function Shell({ children }: { children: React.ReactNode }) {
  const { me, loading, logout } = useSession();
  const router = useRouter();
  const path = usePathname();
  const [open, setOpen] = useState(false);

  useEffect(() => {
    if (loading) return;
    if (!me) router.replace("/login");
    else if (me.role === "GUARD") router.replace("/guard");
    else if (me.role === "SITE_KIOSK") router.replace("/kiosk");
    else if (me.role === "CLIENT_ADMIN" || me.role === "CLIENT_VIEWER") router.replace("/portal");
  }, [me, loading, router]);

  useEffect(() => setOpen(false), [path]);

  if (loading || !me) return <Loading />;
  const perms = me.permissions ?? [];

  const nav = (
    <nav className="flex flex-col gap-4 p-3">
      {NAV.map((s) => {
        const items = s.items.filter((i) => allowed(i, perms));
        if (!items.length) return null;
        return (
          <div key={s.section}>
            <div className="mb-1 px-2 text-[11px] font-semibold uppercase tracking-wide text-slate-400">{s.section}</div>
            {items.map((i) => {
              const active = path === i.href || path.startsWith(i.href + "/");
              const Icon = i.icon;
              return (
                <Link key={i.href} href={i.href}
                  className={`flex items-center gap-2 rounded-md px-2 py-1.5 text-sm ${active ? "bg-brand/10 font-medium text-brand" : "text-slate-600 hover:bg-slate-100"}`}>
                  <Icon size={16} /> {i.label}
                </Link>
              );
            })}
          </div>
        );
      })}
    </nav>
  );

  return (
    <div className="min-h-screen lg:flex">
      <aside className="hidden w-60 shrink-0 border-r bg-white lg:block">
        <div className="border-b p-3"><Link href={homeFor(me)}><Brand /></Link></div>
        {nav}
      </aside>
      {open && (
        <div className="fixed inset-0 z-40 bg-black/40 lg:hidden" onClick={() => setOpen(false)}>
          <aside className="h-full w-64 overflow-y-auto bg-white" onClick={(e) => e.stopPropagation()}>
            <div className="flex items-center justify-between border-b p-3"><Brand /><button onClick={() => setOpen(false)} aria-label="Close menu"><X size={18} /></button></div>
            {nav}
          </aside>
        </div>
      )}
      <div className="min-w-0 flex-1">
        <header className="sticky top-0 z-30 flex items-center justify-between gap-2 border-b bg-white/90 px-4 py-2 backdrop-blur">
          <div className="flex items-center gap-2">
            <button className="btn-ghost p-1 lg:hidden" onClick={() => setOpen(true)} aria-label="Open menu"><Menu size={20} /></button>
            <span className="lg:hidden"><Brand compact /></span>
          </div>
          <div className="flex items-center gap-3 text-sm">
            <TenantSwitcher />
            <span className="hidden text-slate-600 sm:inline">{me.name} · <span className="text-xs text-slate-400">{me.role}</span></span>
            <ChangePasswordButton />
            <button className="btn-ghost" onClick={logout}><LogOut size={16} /> <span className="hidden sm:inline">Logout</span></button>
          </div>
        </header>
        <main className="mx-auto max-w-7xl p-4">{children}</main>
      </div>
    </div>
  );
}
