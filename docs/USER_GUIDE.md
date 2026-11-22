# User guide: apps and views for every role

## Which app does each person use?

* **Web app** (browser, laptop or phone): office staff and clients.
* **Android app** (`mobile/`, APK from GitHub Releases): guards and supervisors in the field. It has the same
  guard features as the web `/guard` page plus supervisor tools (today's posts, find/replace guard, mark
  attendance for a guard without a phone, approvals, incidents).

After login, each person lands on the screen for their role and sees only what they're allowed to:

| Who | Device | Lands on | What they do |
|---|---|---|---|
| Agency owner / admin | Laptop | Dashboard | Everything: setup, approvals, finance, reports, users |
| Operations / supervisor | Laptop, or **Android app** in the field | Roster / Today tab | Deploy and transfer guards, fill open shifts, replacements, approve attendance, incidents |
| HR | Laptop | Employees | Onboarding, documents and verification, leave, guard logins |
| Finance | Laptop | Payroll / Invoices | Pay rates, payroll runs, invoices, receipts, margin |
| **Guard** | **Android app** or phone browser | Duty tab / `/guard` | Today's duty, **Check in / Check out** (GPS), attendance, payslips, leave, report incident |
| **Client** (society / mall manager) | Phone or laptop | `/portal` | Guards on duty today, invoices, statement, incidents |
| Platform operator (you, the SaaS owner) | Laptop | `/platform` | Agencies, plans, suspend/restore (no access to their HR or finance data) |

### Web app on a phone (no install needed)

The web app can also be added to the home screen (PWA):

* **Android (Chrome):** open the site, then menu ⋮ → **Install app** / **Add to Home screen**.
* **iPhone (Safari):** open the site, then Share → **Add to Home Screen**.

It opens full-screen with the agency's logo. The guard view keeps working offline: punches are
stored on the phone and sync automatically when the network returns.

### Android app

Download `app-release.apk` from GitHub **Releases → "Android app (latest)"**, install it, sign in and enter
the server address once. See [mobile/README.md](../mobile/README.md). Next steps for the app: background GPS
patrol tracking, QR/NFC checkpoints, push notifications, iOS build.

## A typical month

1. **Setup (once):** Settings → Organisation & policies → Masters (branches, shift timings, worker types) → Clients → Sites & posts → Contracts (rates) → Pay rates.
2. **Hire:** Employees → add → upload documents → verify → **Activate** → create guard login.
3. **Deploy:** Deployments → post each guard at a site. Use **Transfer** when a guard moves (history is kept).
4. **Roster:** Roster → **Generate roster** for the month. Open slots show in red. Click a slot to see the best available replacement.
5. **Daily:** guards check in and out on their phones. Supervisors handle late arrivals and absences (**Replace**), and add extra shifts the client requests.
6. **Approvals:** Attendance → clean records are auto-approved. Review the flagged ones, and approve corrections (a different person must approve).
7. **Month end:**
   * Payroll → **New run** → review → approve (by someone else) → lock → bank file → mark paid.
   * Invoices → **Generate** per client → review → approve → issue → print/PDF → record payments in Receipts.
8. **Review:** Reports → **Site margin** shows what each site earned versus what its guards cost.

## Screens

### Web app

| | |
|---|---|
| Dashboard ![](screenshots/01-dashboard.png) | Roster: replacement on 10 Oct, uncovered no-show on 12 Oct ![](screenshots/02-roster.png) |
| Attendance approvals ![](screenshots/03-attendance.png) | Payroll register ![](screenshots/04-payroll.png) |
| Invoice with uncovered-duty deduction and extra shift ![](screenshots/05-invoice.png) | Site margin ![](screenshots/06-site-margin.png) |
| Contracts and versions ![](screenshots/07-contracts.png) | Back office on a phone ![](screenshots/10-backoffice-mobile.png) |
| Guard phone view ![](screenshots/08-guard-mobile.png) | Client portal ![](screenshots/09-client-portal-mobile.png) |

### Android app (guards & supervisors)

| | | |
|---|---|---|
| Sign in ![](screenshots/mobile-login.png) | Guard: today's duty, check in/out ![](screenshots/mobile-guard-duty.png) | Guard: payslips ![](screenshots/mobile-guard-payslip.png) |
| Supervisor: today's posts and gaps ![](screenshots/mobile-supervisor-today.png) | Incidents ![](screenshots/mobile-incident-report.png) | |

How to operate each screen: [OPERATIONS_MANUAL.md](OPERATIONS_MANUAL.md#14-guard-android-app).
