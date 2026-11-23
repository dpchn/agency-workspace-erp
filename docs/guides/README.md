# Feature guides (step by step, with videos)

One guide per feature or flow. Each has numbered steps with screenshots, what the system checks for you, common
situations, and a short video with on-screen captions (`videos/`, WebM, opens in any browser; app videos are MP4).

The examples use a demo agency, **Suraksha Manpower Services**: two clients with different rates (Manyata Tech Park,
Prestige Lakeside), guards, a supervisor and housekeeping staff, including one without a phone.

## Start here, by who you are

| You are | Read |
|---|---|
| **The ERP company** (you sell the software) | [01](01-platform-onboarding.md) |
| **Agency owner**, first week | [02](02-agency-first-setup.md) → [03](03-clients-sites-posts.md) → [04](04-contracts-rates.md) → [05](05-pay-rates-salary.md) → [06](06-employees.md) → [07](07-deployments-transfers.md) → [08](08-roster.md) |
| **Operations / supervisor** (every day) | [08](08-roster.md), [09](09-attendance-approval.md), [10](10-no-phone-attendance.md), [12](12-leave.md), [13](13-incidents.md), [19](19-supervisor-app.md) |
| **Finance** (month end) | [11](11-client-signoff.md), [14](14-payroll.md), [15](15-invoicing-collections.md), [16](16-reports-audit.md) |
| **Guard / housekeeping** | [18](18-guard-app.md) (with a phone) or [10](10-no-phone-attendance.md) (without) |
| **Client** (society / mall manager) | [17](17-client-portal.md), [11](11-client-signoff.md) |

## All guides

| # | Guide | Video |
|---|---|---|
| 01 | [Onboard a manpower agency](01-platform-onboarding.md): plans, limits, trials, suspend, reset owner password | [▶](videos/01-platform-onboarding.webm) |
| 02 | [Agency first-time setup](02-agency-first-setup.md): password, organisation, policies, branding, users & roles, masters | [▶](videos/02-agency-first-setup.webm) |
| 03 | [Clients, sites, posts, shift requirements](03-clients-sites-posts.md): geofence, worker types per post | [▶](videos/03-clients-sites-posts.webm) |
| 04 | [Contracts and billing rates](04-contracts-rates.md): rate per site and worker type, six billing models, approval, mid-month revision | [▶](videos/04-contracts-rates.webm) |
| 05 | [Pay rates and salary rules](05-pay-rates-salary.md): pay by site (x × days + y × days), PF/ESI/PT | [▶](videos/05-pay-rates-salary.webm) |
| 06 | [Employees](06-employees.md): hire, documents, verification gate, kiosk PIN, photo, app login, exit | [▶](videos/06-employees.webm) |
| 07 | [Deployments and transfers](07-deployments-transfers.md): permanent, temporary, reliever, mid-month transfer | [▶](videos/07-deployments-transfers.webm) |
| 08 | [Roster](08-roster.md): generate, fill gaps, replace a no-show, handover, extra shift, double duty | [▶](videos/08-roster.webm) |
| 09 | [Attendance review and approval](09-attendance-approval.md): sources, flags, approvals, muster roll, locked months | [▶](videos/09-attendance-approval.webm) |
| 10 | [Workers without a phone](10-no-phone-attendance.md): site kiosk, team roll-call, paper register, second-person approval | [▶](videos/10-no-phone-attendance.webm) |
| 11 | [Client sign-off](11-client-signoff.md): client confirms or disputes; dispute holds the invoice | [▶](videos/11-client-signoff.webm) |
| 12 | [Leave](12-leave.md): apply, approve, duties released, paid vs unpaid | [▶](videos/12-leave.webm) |
| 13 | [Incidents](13-incidents.md): report, deadlines, escalation, close | [▶](videos/13-incidents.webm) |
| 14 | [Payroll](14-payroll.md): per-duty pay across sites, approve, lock, bank file, payslips | [▶](videos/14-payroll.webm) |
| 15 | [Invoicing and collections](15-invoicing-collections.md): GST invoices, credit notes, receipts, aging | [▶](videos/15-invoicing-collections.webm) |
| 16 | [Reports and audit](16-reports-audit.md): dashboard, site margin, deployment trail, audit log | [▶](videos/16-reports-audit.webm) |
| 17 | [Client portal](17-client-portal.md): today, monthly attendance, invoices, statement, incidents | [▶](videos/17-client-portal.webm) |
| 18 | [Guard app](18-guard-app.md): check in with GPS/selfie, attendance, payslips, leave, incidents | [▶](videos/18-guard-app.mp4) |
| 19 | [Supervisor app](19-supervisor-app.md): today's posts, roll-call, kiosk mode for workers without a phone | [▶](videos/19-supervisor-app.mp4) |

## The flow of one month

```
Onboard agency (01) → setup (02-03) → contracts & pay rates (04-05) → people (06) → postings (07)
  → roster (08) → attendance every day: phone (18), kiosk / roll-call / register (10, 19) → approve (09)
  → client confirms (11) → payroll (14) + invoices (15) → collections (15) → reports (16)
```

More reference: [Operations manual](../OPERATIONS_MANUAL.md) · [Configuration](../CONFIGURATION.md) ·
[Business scenarios](../DOMAIN.md) · [Deployment](../DEPLOYMENT.md)
