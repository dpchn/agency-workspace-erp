"use client";
import { useEffect } from "react";
import { useRouter } from "next/navigation";
import { LogOut } from "lucide-react";
import { useSession } from "@/lib/session";
import { Brand, TenantSwitcher } from "./Shell";
import { Loading } from "./ui";

/** Minimal layout for the guard app and client portal. */
export function MiniShell({ roles, children }: { roles: string[]; children: React.ReactNode }) {
  const { me, loading, logout } = useSession();
  const router = useRouter();
  useEffect(() => {
    if (loading) return;
    if (!me) router.replace("/login");
    else if (!roles.includes(me.role ?? "")) router.replace("/");
  }, [me, loading, roles, router]);
  if (loading || !me || !roles.includes(me.role ?? "")) return <Loading />;
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
      <main className="mx-auto max-w-3xl p-4">{children}</main>
    </div>
  );
}
