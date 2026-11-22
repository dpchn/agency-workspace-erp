/**
 * Product branding — the ONLY place the product name, tagline, colour and logo are defined.
 * Override with env vars at build time (see .env.example). After login, the agency's own
 * branding (Settings -> Branding) overrides these for its users (white-label).
 */
export const brand = {
  name: process.env.NEXT_PUBLIC_BRAND_NAME || "DutyGrid",
  tagline: process.env.NEXT_PUBLIC_BRAND_TAGLINE || "Workforce ERP for security & facility agencies",
  primary: process.env.NEXT_PUBLIC_BRAND_PRIMARY || "#1d4ed8",
  logo: process.env.NEXT_PUBLIC_BRAND_LOGO || "/brand/logo.svg",
};

export const apiUrl = process.env.NEXT_PUBLIC_API_URL || "http://localhost:8080";

/** "#1d4ed8" -> "29 78 216" for use in rgb(var(--brand) / alpha). */
export function hexToRgbTriplet(hex: string): string {
  const h = hex.replace("#", "").trim();
  const full = h.length === 3 ? h.split("").map((c) => c + c).join("") : h;
  const n = parseInt(full.slice(0, 6), 16);
  if (Number.isNaN(n)) return "29 78 216";
  return `${(n >> 16) & 255} ${(n >> 8) & 255} ${n & 255}`;
}

export function applyBrandColor(hex: string) {
  if (typeof document === "undefined") return;
  document.documentElement.style.setProperty("--brand", hexToRgbTriplet(hex));
}
