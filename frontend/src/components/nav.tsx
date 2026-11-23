import {
  LayoutDashboard, Users, Building2, MapPin, FileText, Wallet, CalendarDays, ClipboardCheck, Plane, Banknote,
  Receipt, ShieldAlert, BarChart3, Settings, Truck, IndianRupee, Layers, Smartphone, Tablet,
  type LucideIcon,
} from "lucide-react";
import type { Me } from "@/lib/session";

export type NavItem = { href: string; label: string; icon: LucideIcon; perm?: string; any?: string[] };

export const NAV: { section: string; items: NavItem[] }[] = [
  { section: "Overview", items: [{ href: "/dashboard", label: "Dashboard", icon: LayoutDashboard, any: ["REPORT_READ", "ROSTER_READ"] }] },
  {
    section: "Operations",
    items: [
      { href: "/roster", label: "Roster", icon: CalendarDays, perm: "ROSTER_READ" },
      { href: "/deployments", label: "Deployments", icon: Truck, perm: "ROSTER_READ" },
      { href: "/attendance", label: "Attendance", icon: ClipboardCheck, perm: "ATTENDANCE_READ" },
      { href: "/leaves", label: "Leaves", icon: Plane, perm: "LEAVE_APPROVE" },
      { href: "/incidents", label: "Incidents", icon: ShieldAlert, perm: "INCIDENT_READ" },
      { href: "/guard", label: "My duty (mobile)", icon: Smartphone, perm: "ATTENDANCE_SELF" },
      { href: "/kiosk", label: "Site kiosk", icon: Tablet, perm: "ATTENDANCE_KIOSK" },
    ],
  },
  {
    section: "People & clients",
    items: [
      { href: "/employees", label: "Employees", icon: Users, perm: "HR_READ" },
      { href: "/clients", label: "Clients", icon: Building2, perm: "CLIENT_READ" },
      { href: "/sites", label: "Sites & posts", icon: MapPin, perm: "CLIENT_READ" },
      { href: "/contracts", label: "Contracts", icon: FileText, perm: "CONTRACT_READ" },
    ],
  },
  {
    section: "Finance",
    items: [
      { href: "/pay-rates", label: "Pay rates", icon: IndianRupee, perm: "PAYRATE_READ" },
      { href: "/payroll", label: "Payroll", icon: Wallet, perm: "PAYROLL_READ" },
      { href: "/invoices", label: "Invoices", icon: Receipt, perm: "BILLING_READ" },
      { href: "/receipts", label: "Receipts & aging", icon: Banknote, perm: "BILLING_READ" },
      { href: "/reports", label: "Reports", icon: BarChart3, perm: "REPORT_READ" },
    ],
  },
  {
    section: "Admin",
    items: [
      { href: "/masters", label: "Masters", icon: Layers, perm: "MASTER_READ" },
      { href: "/settings", label: "Settings & users", icon: Settings, any: ["SETTINGS_WRITE", "USER_MANAGE"] },
    ],
  },
];

export function allowed(item: NavItem, perms: string[]) {
  if (item.perm && !perms.includes(item.perm)) return false;
  if (item.any && !item.any.some((p) => perms.includes(p))) return false;
  return true;
}

export function homeFor(me: Me): string {
  if (me.role === "GUARD") return "/guard";
  if (me.role === "SITE_KIOSK") return "/kiosk";
  if (me.role === "CLIENT_ADMIN" || me.role === "CLIENT_VIEWER") return "/portal";
  if (!me.tenant && me.platformAdmin) return "/platform";
  const perms = me.permissions ?? [];
  for (const s of NAV) for (const i of s.items) if (allowed(i, perms)) return i.href;
  return "/dashboard";
}
