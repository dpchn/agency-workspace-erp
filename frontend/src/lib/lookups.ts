"use client";
import { useLookup } from "./hooks";

export const useBranches = () => useLookup("/v1/branches?size=500", (b) => `${b.name} (${b.code})`);
export const useServiceTypes = () => useLookup("/v1/service-types?size=500", (s) => s.name);
export const useShiftTemplates = () =>
  useLookup("/v1/shift-templates?size=500", (t) => `${t.code} · ${String(t.startTime).slice(0, 5)}-${String(t.endTime).slice(0, 5)}`);
export const useClients = () => useLookup("/v1/clients?size=500", (c) => c.legalName);
export const useSites = () => useLookup("/v1/sites?size=500", (s) => s.name);
/** Posts labelled "Site · Post (CODE)": many sites have a "Main Gate", so the site must be in the label. */
export function usePosts(siteId?: string) {
  const sites = useSites();
  const posts = useLookup(siteId ? `/v1/posts?size=500&siteId=${siteId}` : "/v1/posts?size=500", (p) => `${p.name} (${p.code})`);
  const label = (p: any) => (sites.map[p.siteId] ? `${sites.map[p.siteId]} · ` : "") + `${p.name} (${p.code})`;
  const map: Record<string, string> = {};
  posts.items.forEach((p) => { map[p.id] = label(p); });
  const options = posts.items.map((p) => ({ value: p.id as string, label: label(p) })).sort((x, y) => x.label.localeCompare(y.label));
  return { items: posts.items, map, options };
}
export const useEmployees = (status?: string) =>
  useLookup(`/v1/employees?size=500${status ? "&status=" + status : ""}`, (e) => `${e.employeeCode} · ${e.firstName}${e.lastName ? " " + e.lastName : ""}`);

export const opts = (...v: string[]) => v.map((x) => ({ value: x, label: x.replace(/_/g, " ") }));
