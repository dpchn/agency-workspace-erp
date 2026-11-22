"use client";
import { createContext, useCallback, useContext, useEffect, useState } from "react";
import { api, tokenStore } from "./api";
import { applyBrandColor, brand } from "@/config/brand";
import { setDisplayTz } from "./format";

export type Me = {
  userId: string;
  name: string;
  email?: string;
  phone?: string;
  platformAdmin: boolean;
  memberships: { membershipId: string; tenantId: string; tenantName: string; tenantSlug: string; role: string }[];
  tenant?: { id: string; name: string; slug: string; status: string; timezone: string; currency: string; branding: Record<string, any> };
  role?: string;
  permissions?: string[];
  employeeId?: string;
  clientId?: string;
  siteIds?: string[];
};

type Ctx = {
  me: Me | null;
  loading: boolean;
  can: (perm: string) => boolean;
  refresh: () => Promise<void>;
  login: (login: string, password: string, tenantId?: string) => Promise<void>;
  switchTenant: (tenantId: string) => Promise<void>;
  logout: () => void;
  displayName: string;
  logo: string;
};

const SessionCtx = createContext<Ctx | null>(null);

export function SessionProvider({ children }: { children: React.ReactNode }) {
  const [me, setMe] = useState<Me | null>(null);
  const [loading, setLoading] = useState(true);

  const refresh = useCallback(async () => {
    if (!tokenStore.get()) { setMe(null); setLoading(false); applyBrandColor(brand.primary); return; }
    try {
      const m = await api.get<Me>("/v1/me");
      setDisplayTz(m.tenant?.timezone);
      setMe(m);
      applyBrandColor(m.tenant?.branding?.primaryColor || brand.primary);
    } catch {
      setMe(null);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { refresh(); }, [refresh]);

  const login = async (l: string, p: string, tenantId?: string) => {
    const r = await api.post<{ accessToken: string }>("/v1/auth/login", { login: l, password: p, tenantId });
    tokenStore.set(r.accessToken);
    setLoading(true);
    await refresh();
  };

  const switchTenant = async (tenantId: string) => {
    const r = await api.post<{ accessToken: string }>("/v1/auth/switch-tenant", { tenantId });
    tokenStore.set(r.accessToken);
    await refresh();
  };

  const logout = () => {
    tokenStore.clear();
    setMe(null);
    applyBrandColor(brand.primary);
    location.href = "/login";
  };

  const can = (perm: string) => !!me?.permissions?.includes(perm);
  const displayName = me?.tenant?.branding?.displayName || me?.tenant?.name || brand.name;
  const logo = me?.tenant?.branding?.logoUrl || brand.logo;

  return (
    <SessionCtx.Provider value={{ me, loading, can, refresh, login, switchTenant, logout, displayName, logo }}>
      {children}
    </SessionCtx.Provider>
  );
}

export function useSession() {
  const c = useContext(SessionCtx);
  if (!c) throw new Error("useSession outside provider");
  return c;
}
