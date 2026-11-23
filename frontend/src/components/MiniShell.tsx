"use client";
import { useEffect } from "react";
import { useRouter } from "next/navigation";
import { LogOut } from "lucide-react";
import { useSession } from "@/lib/session";
import { Brand, TenantSwitcher } from "./Shell";
import { Loading } from "./ui";

/** Minimal layout for the guard app and client portal. */
export function MiniShell({ roles, perm, wide, children }: { roles: string[]; perm?: string; wide?: boolean; children: React.ReactNode }) {
  const { me, loading, logout } = useSession();
  const router = useRouter();
  const ok = !!me && (roles.includes(me.role ?? "") || (!!perm && !!me.permissions?.includes(perm)));
  useEffect(() => {
    if (loading) return;
    if (!me) router.replace("/login");
    else if (!ok) router.replace("/");
  }, [me, loading, ok, router]);
  if (loading || !me || !ok) return <Loading />;
  return (
    <div className="min-h-screen">
      <header className="sticky top-0 z-30 flex items-center justify-between border-b bg-white px-4 py-2">
        <Brand />
        <div className="flex items-center gap-2 text-sm">
          <TenantSwitcher />
          <span className="hidden text-slate-600 sm:inline">{me.name}</span>
          <button className="btn-ghost" onClick={logout} aria-label="Logout"><LogOut size={16} /></button>
        </div>
      </header>
      <main className={`mx-auto ${wide ? "max-w-5xl" : "max-w-3xl"} p-4`}>{children}</main>
    </div>
  );
}
