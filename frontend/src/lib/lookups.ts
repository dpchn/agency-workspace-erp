"use client";
import { useLookup } from "./hooks";

export const useBranches = () => useLookup("/v1/branches?size=500", (b) => `${b.name} (${b.code})`);
export const useServiceTypes = () => useLookup("/v1/service-types?size=500", (s) => s.name);
export const useShiftTemplates = () =>
  useLookup("/v1/shift-templates?size=500", (t) => `${t.code} · ${String(t.startTime).slice(0, 5)}-${String(t.endTime).slice(0, 5)}`);
export const useClients = () => useLookup("/v1/clients?size=500", (c) => c.legalName);
export const useSites = () => useLookup("/v1/sites?size=500", (s) => s.name);
export const usePosts = (siteId?: string) => useLookup(siteId ? `/v1/posts?size=500&siteId=${siteId}` : "/v1/posts?size=500", (p) => `${p.name} (${p.code})`);
export const useEmployees = (status?: string) =>
  useLookup(`/v1/employees?size=500${status ? "&status=" + status : ""}`, (e) => `${e.employeeCode} · ${e.firstName}${e.lastName ? " " + e.lastName : ""}`);

export const opts = (...v: string[]) => v.map((x) => ({ value: x, label: x.replace(/_/g, " ") }));
