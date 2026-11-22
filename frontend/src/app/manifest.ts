import type { MetadataRoute } from "next";
import { brand } from "@/config/brand";

/** Makes the web app installable ("Add to Home screen") for guards, supervisors and clients. */
export default function manifest(): MetadataRoute.Manifest {
  return {
    name: brand.name,
    short_name: brand.name,
    description: brand.tagline,
    start_url: "/",
    display: "standalone",
    orientation: "portrait",
    background_color: "#ffffff",
    theme_color: brand.primary,
    icons: [{ src: brand.logo, sizes: "any", type: "image/svg+xml", purpose: "any" }],
  };
}
