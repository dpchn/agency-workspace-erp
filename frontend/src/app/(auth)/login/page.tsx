"use client";
import Link from "next/link";
import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { errMsg } from "@/lib/api";
import { useSession } from "@/lib/session";
import { homeFor } from "@/components/nav";

export default function LoginPage() {
  const { login, me } = useSession();
  const router = useRouter();
  const [l, setL] = useState("");
  const [p, setP] = useState("");
  const [err, setErr] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => { if (me) router.replace(homeFor(me)); }, [me, router]);

  return (
    <form className="space-y-3" onSubmit={async (e) => {
      e.preventDefault();
      setBusy(true);
      setErr(null);
      try { await login(l.trim(), p); } catch (x) { setErr(errMsg(x)); } finally { setBusy(false); }
    }}>
      <h2 className="text-lg font-semibold">Sign in</h2>
      <div>
        <label className="label" htmlFor="login">E-mail or mobile number</label>
        <input id="login" className="input" autoComplete="username" value={l} onChange={(e) => setL(e.target.value)} required />
      </div>
      <div>
        <label className="label" htmlFor="pw">Password</label>
        <input id="pw" type="password" className="input" autoComplete="current-password" value={p} onChange={(e) => setP(e.target.value)} required />
      </div>
      {err && <div className="rounded bg-red-50 p-2 text-sm text-red-700">{err}</div>}
      <button className="btn-primary w-full py-2" disabled={busy}>{busy ? "Signing in…" : "Sign in"}</button>
      <p className="text-center text-sm text-slate-500">New agency? <Link href="/signup" className="text-brand underline">Create your account</Link></p>
    </form>
  );
}
