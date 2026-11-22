"use client";
import { useState } from "react";
import { CrudPage } from "@/components/CrudPage";
import { Badge, Tabs } from "@/components/ui";
import { opts, useBranches, useClients, usePosts, useServiceTypes, useShiftTemplates, useSites } from "@/lib/lookups";

const DAYN = ["", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"];

export default function SitesPage() {
  const [tab, setTab] = useState("sites");
  const clients = useClients(), branches = useBranches(), st = useServiceTypes(), sites = useSites(), posts = usePosts(), tpl = useShiftTemplates();
  return (
    <div>
      <Tabs active={tab} onChange={setTab} tabs={[{ key: "sites", label: "Sites" }, { key: "posts", label: "Posts" }, { key: "req", label: "Shift requirements" }]} />
      {tab === "sites" && (
        <CrudPage title="Sites" subtitle="Physical locations. Geofence: ENFORCE rejects check-ins outside the radius, FLAG accepts but flags for review."
          endpoint="/v1/sites" writePerm="CLIENT_WRITE"
          filters={[{ name: "clientId", label: "Client", options: clients.options }, { name: "branchId", label: "Branch", options: branches.options }]}
          defaults={{ geofenceRadiusM: 200, geofencePolicy: "FLAG", status: "ACTIVE" }}
          columns={[
            { key: "code", label: "Code" }, { key: "name", label: "Site" }, { key: "clientId", label: "Client", render: (r) => clients.map[r.clientId] },
            { key: "branchId", label: "Branch", render: (r) => branches.map[r.branchId] },
            { key: "geo", label: "Location", render: (r) => r.latitude != null ? `${r.latitude.toFixed(4)}, ${r.longitude.toFixed(4)} · ${r.geofenceRadiusM}m` : "-" },
            { key: "geofencePolicy", label: "Geofence", render: (r) => <Badge value={r.geofencePolicy} /> },
            { key: "status", label: "Status", render: (r) => <Badge value={r.status} /> },
          ]}
          fields={[
            { name: "clientId", label: "Client", type: "select", options: clients.options, required: true, half: true },
            { name: "branchId", label: "Servicing branch", type: "select", options: branches.options, required: true, half: true },
            { name: "code", label: "Code", required: true, half: true }, { name: "name", label: "Name", required: true, half: true },
            { name: "address", label: "Address", type: "textarea" },
            { name: "stateCode", label: "State code", half: true }, { name: "timezone", label: "Timezone (blank = agency)", half: true, placeholder: "Asia/Kolkata" },
            { name: "latitude", label: "Latitude", type: "number", half: true }, { name: "longitude", label: "Longitude", type: "number", half: true },
            { name: "geofenceRadiusM", label: "Geofence radius (m)", type: "number", half: true },
            { name: "geofencePolicy", label: "Geofence policy", type: "select", options: opts("ENFORCE", "FLAG", "OFF"), half: true },
            { name: "status", label: "Status", type: "select", options: opts("ACTIVE", "INACTIVE"), half: true },
          ]} />
      )}
      {tab === "posts" && (
        <CrudPage title="Posts" subtitle="A post is a position to be covered (Main gate, Lobby…), independent of who covers it." endpoint="/v1/posts" writePerm="CLIENT_WRITE"
          filters={[{ name: "siteId", label: "Site", options: sites.options }]} defaults={{ active: true }}
          columns={[{ key: "code", label: "Code" }, { key: "name", label: "Post" }, { key: "siteId", label: "Site", render: (r) => sites.map[r.siteId] },
            { key: "serviceTypeId", label: "Service", render: (r) => st.map[r.serviceTypeId] }, { key: "active", label: "Active" }]}
          fields={[{ name: "siteId", label: "Site", type: "select", options: sites.options, required: true, half: true },
            { name: "serviceTypeId", label: "Service type", type: "select", options: st.options, required: true, half: true },
            { name: "code", label: "Code", required: true, half: true }, { name: "name", label: "Name", required: true, half: true },
            { name: "instructions", label: "Post instructions", type: "textarea" }, { name: "active", label: "Active", type: "checkbox" }]} />
      )}
      {tab === "req" && (
        <CrudPage title="Shift requirements" subtitle="Which shifts each post needs and how many people. Roster generation builds shifts from these." search={false}
          endpoint="/v1/post-requirements" writePerm="CLIENT_WRITE"
          filters={[{ name: "postId", label: "Post", options: posts.options }]}
          defaults={{ headcount: 1, daysOfWeek: [1, 2, 3, 4, 5, 6, 7] }}
          columns={[{ key: "postId", label: "Post", render: (r) => posts.map[r.postId] }, { key: "shiftTemplateId", label: "Shift", render: (r) => tpl.map[r.shiftTemplateId] },
            { key: "headcount", label: "Headcount" }, { key: "daysOfWeek", label: "Days", render: (r) => (r.daysOfWeek ?? []).map((d: number) => DAYN[d]).join(" ") },
            { key: "effectiveFrom", label: "From" }, { key: "effectiveTo", label: "To" }]}
          fields={[{ name: "postId", label: "Post", type: "select", options: posts.options, required: true, half: true },
            { name: "shiftTemplateId", label: "Shift", type: "select", options: tpl.options, required: true, half: true },
            { name: "headcount", label: "Headcount", type: "number", required: true, half: true },
            { name: "daysOfWeek", label: "Days of week", type: "days", required: true },
            { name: "effectiveFrom", label: "Effective from", type: "date", required: true, half: true },
            { name: "effectiveTo", label: "Effective to", type: "date", half: true }]} />
      )}
    </div>
  );
}
