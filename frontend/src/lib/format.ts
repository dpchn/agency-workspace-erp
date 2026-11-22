/** Agency timezone used for displaying times (set after login from tenant settings). */
let displayTz: string | undefined;
export const setDisplayTz = (tz?: string) => { displayTz = tz; };

export const fmtMoney = (v: unknown, currency = "INR") => {
  const n = Number(v ?? 0);
  if (Number.isNaN(n)) return "-";
  return n.toLocaleString("en-IN", { style: "currency", currency, maximumFractionDigits: 2 });
};
export const fmtNum = (v: unknown, d = 2) => {
  if (v === null || v === undefined || v === "") return "-";
  const n = Number(v);
  return Number.isNaN(n) ? String(v) : n.toLocaleString("en-IN", { maximumFractionDigits: d });
};
export const fmtDate = (v: unknown) => (v ? String(v).slice(0, 10) : "-");
export const fmtDateTime = (v: unknown) => {
  if (!v) return "-";
  const d = new Date(String(v));
  return Number.isNaN(d.getTime()) ? String(v) : d.toLocaleString("en-IN", { dateStyle: "medium", timeStyle: "short", timeZone: displayTz });
};
export const fmtTime = (v: unknown) => {
  if (!v) return "-";
  const d = new Date(String(v));
  return Number.isNaN(d.getTime()) ? String(v) : d.toLocaleTimeString("en-IN", { hour: "2-digit", minute: "2-digit", timeZone: displayTz });
};
const pad = (n: number) => String(n).padStart(2, "0");
export const today = () => {
  const d = new Date();
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
};
export const thisMonth = () => today().slice(0, 7);
export const addDays = (iso: string, n: number) => {
  const d = new Date(iso + "T00:00:00Z");
  d.setUTCDate(d.getUTCDate() + n);
  return d.toISOString().slice(0, 10);
};
