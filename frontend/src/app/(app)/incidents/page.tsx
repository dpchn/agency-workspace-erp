"use client";
import { IncidentList } from "@/components/IncidentList";
import { useSites } from "@/lib/lookups";

export default function IncidentsPage() {
  const sites = useSites();
  return <IncidentList siteOptions={sites.options} />;
}
