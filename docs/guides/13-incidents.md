# 13 · Incidents

**Who / where:** guards (app / `/guard` → **Incident**), supervisors and operations (**Incidents**), clients (portal → **Incidents**)

▶ Video: [13-incidents.webm](videos/13-incidents.webm) (56 s)

## What it's for
Anything that happens on site (trespass, theft, fire, medical, a broken camera) is recorded, answered within a
deadline, and kept with its full history. Clients can report too and see what you choose to share.

## Before you start
* Response deadlines per severity: `incidentResponseMinutes` (default **critical 15 min, high 60 min, medium 4 h,
  low 24 h**).

## Steps
1. **Report incident**: site, title, severity, category (theft, trespass, fire, medical, fight, equipment, other) and
   what happened. Managers can also tick **Visible to client**.

   ![Report](img/13-report.png)
2. **Incidents** lists them with severity, status, escalation level and **Respond by**.

   ![List](img/13-list.png)
3. Open one → **Acknowledge** (stops escalation) → **Start work** → **Resolve** (with notes) → **Close**.
   **Reopen** if it comes back. Use the visibility button to share it with the client, or stop sharing.

   ![Detail](img/13-detail.png)
4. **Client:** portal → **Incidents** shows incidents at their sites that are shared with them (including the ones
   they reported), and lets a client admin report a new one. Guards see only the incidents they reported.

   ![Client view](img/13-client.png)

## What the system does for you
* Sets the respond-by time from the severity.
* **Escalates** automatically: an incident still OPEN after its deadline goes up one level (L1, L2…) and the
  deadline restarts; a notification is sent each time.
* Sends a notification when an incident is raised.
* Keeps who did what and when in the audit log.

## Common situations
* **Low-priority request reported as critical.** Acknowledge it quickly; the deadline is what matters.
* **Client shouldn't see an internal matter.** Leave "Visible to client" off. Incidents a client reports are shared with them automatically.
* **Problem returned.** **Reopen** the resolved incident instead of creating a new one.
* **Escalation level keeps rising.** Nobody acknowledged it; acknowledge to stop it.

## Related
[17-client-portal](17-client-portal.md) · [18-guard-app](18-guard-app.md) · [19-supervisor-app](19-supervisor-app.md)
