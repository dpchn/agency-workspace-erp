# DutyGrid: workforce ERP for security & facility agencies

> "DutyGrid", the logo and the colours are placeholders. You can change each in one place (see [Rebranding](#rebranding)).

A multi-tenant SaaS for security agencies, working the way MyGate serves many housing societies: every agency
signs up and runs its own branches, clients, sites, guards, attendance, payroll and billing, fully isolated from
the others. It also works for facility-management agencies (housekeeping, drivers…).

**The core problem it solves:** an agency charges each client under that client's contract and pays guards
under separate wage rules, while guards keep moving between sites (transfers, relief, replacements, double duties,
extra shifts). DutyGrid prices **every duty individually**, both what is charged and what is paid, so payroll and
invoices always reconcile however often people move.

## Documentation

| Guide | For | What's inside |
|---|---|---|
| **[Feature guides with videos](docs/guides/README.md)** | Everyone | 19 step-by-step guides (platform onboarding to payroll, guard and supervisor app), each with screenshots and a captioned video |
| **[Operations manual](docs/OPERATIONS_MANUAL.md)** | Agency staff | How to use every feature, step by step, per role (web + Android) |
| **[User guide & screenshots](docs/USER_GUIDE.md)** | Everyone | Which app each person uses, a typical month, screenshots |
| **[Deployment guide](docs/DEPLOYMENT.md)** | Whoever runs the server | Local run, production with HTTPS, backups, upgrades, Android releases, troubleshooting |
| **[Configuration reference](docs/CONFIGURATION.md)** | Admins | Every environment variable, agency policy setting, salary rule and role permission |
| **[Business scenarios](docs/DOMAIN.md)** | Owners, developers | The data model and how 30 real situations are handled (transfers, no-shows, rate changes…) |
| **[API reference](docs/API.md)** | Integrators | Conventions + every endpoint (live docs at `/swagger-ui`) |
| **[Architecture](docs/ARCHITECTURE.md)** | Developers | Modules, multi-tenancy, data integrity, reliability |
| [Mobile app](mobile/README.md) · [Web app](frontend/README.md) | Developers | App-specific build and code notes |
| `docs/specs/*.pdf` | – | The original product and architecture specifications |

## Quick start (Docker)

Needs only Docker (Docker Desktop, or Docker Engine 24+ with Compose v2.24+).

```bash
git clone https://github.com/dpchn/agency-workspace-erp.git
cd agency-workspace-erp
docker compose up -d --build                    # first build takes 5-10 minutes
docker compose --profile demo up demo-seed      # optional: a demo agency with one month of data
```

Open **http://localhost:3000** (API docs: http://localhost:8080/swagger-ui).
For a real server with your domain and HTTPS, see **[docs/DEPLOYMENT.md](docs/DEPLOYMENT.md#2-production-server-with-https)**:
`cp .env.example .env`, edit it, then `docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d --build`.

**Demo logins** (after the seed step):

| Role | Login | Password | Lands on |
|---|---|---|---|
| Agency owner | `owner.demo@shield.test` | `Passw0rd!` | Dashboard (everything) |
| Finance | `fin.demo@shield.test` | `Passw0rd!` | Payroll, invoices |
| Operations | `ops.demo@shield.test` | `Passw0rd!` | Roster, attendance |
| Guard | `8dem333333` | `guard123` | Android app / `/guard` on a phone |
| Client | `client.demo@phoenix.test` | `Client@123` | Client portal |

Or click **Create your account** on the login page to register your own agency.

## Who uses what

| User | App |
|---|---|
| Owner, HR, operations, finance | **Web app** (laptop; also works on phones) |
| Guards | **Android app**: GPS check-in/out that works offline, duties, attendance, payslips, leave, incidents |
| Supervisors in the field | **Android app**: today's posts and gaps, find/replace a guard, mark attendance, approvals, incidents |
| Workers without a phone | **Site kiosk**: a tablet at the gate (`/kiosk` or the Android app) or the supervisor's phone. Tap your name, enter your PIN, a photo is taken |
| Clients (society / mall managers) | **Web portal**: guards on duty today, monthly attendance to confirm or dispute, invoices, statement, incidents |
| You (SaaS owner) | **Platform console** in the web app: agencies, plans, suspend/restore |

The Android APK is built automatically by GitHub Actions: **Releases → "Android app (latest)" → `app-release.apk`**.

## Modules

| Area | What's included |
|---|---|
| Platform | Agency self-signup with ready-made defaults, trial/suspend, plan limits, platform operator console (no access to agency data) |
| Identity | Login by e-mail or mobile, session revocation, 9 roles + permission matrix, branch/site/client scoping, maker-checker approvals |
| HR | Employee lifecycle (draft → verification → active → suspended → exited), documents with expiry, deployment readiness check, guard app logins |
| Clients | Clients, sites with geofence (enforce / flag / off), posts, shift requirements |
| Contracts | Versioned, effective-dated rate cards; 6 billing models; minimum guarantee, extra-shift and OT rates, absence penalties; approval |
| Deployment & roster | Permanent / temporary / reliever postings, transfers with history, auto-roster, replacement finder, mid-shift handover, double duty, extra shifts, no double-booking, rest-interval rule |
| Attendance | GPS check-in/out, geofence, fraud flags, offline sync, automatic no-show marking, corrections with approval, muster roll. **Staff without a phone**: site kiosk (code + PIN + photo), supervisor team roll-call with photo, paper-register entry with photo. Proxy marking always needs a second approver. Client confirms or disputes monthly attendance, and a dispute holds the invoice |
| Leave | Apply / approve; releases rostered duties for replacement |
| Payroll | Per-duty pay across sites, configurable PF/ESI/PT, advances/bonus, versioned runs, lock, arrears, bank CSV, payslips |
| Billing | Contract-driven invoices, uncovered-duty deductions, GST (CGST/SGST vs IGST), approval, financial-year numbering, credit notes, supplementary invoices, receipts, aging, statements, printable invoice |
| Operations | Incidents with response deadlines and automatic escalation |
| Reports | Dashboard, site/client margin, guard deployment trail, audit log |

## Repository layout

```
backend/    Java 21 + Spring Boot 3.3 API, PostgreSQL (row-level security), Flyway migrations
frontend/   Next.js 14 web app (all roles, client portal, platform console)
mobile/     Flutter Android app (guards and supervisors)
deploy/     Database init scripts, Caddy (HTTPS) config
scripts/    Demo/end-to-end scenario, backup/restore, local start/stop
docs/       Guides (see table above), screenshots, original PDF specs
.github/    CI: backend tests + web build; Android APK build and release
docker-compose.yml        local stack     docker-compose.prod.yml   HTTPS override     .env.example   all settings
```

## Tests

```bash
cd backend && mvn test                         # payroll, billing and pay-rate unit tests (golden scenarios)
python3 scripts/e2e_scenario.py                # full month of business against a running backend (80+ checks)
cd mobile && flutter test                      # app unit/widget tests
flutter test test/live_backend_test.dart --dart-define=SERVER=http://localhost:8080   # app against demo data
```

The end-to-end script covers two agencies, two clients in different states, a guard transferred mid-month, a
replacement, an uncovered no-show, a client-requested extra shift, a contract rate revision, maker-checker
approvals, payroll, invoices, a payment and isolation checks. It also covers staff without a phone: a gate kiosk with
PIN lockout and wrong-site punches, a supervisor roll-call with photo, a paper-register entry, the proxy-marking
maker-checker, photo privacy, and a client dispute that holds the invoice until resolved.

## Rebranding

| What | Where |
|---|---|
| Product name, tagline, colour (server + web) | `.env` → `BRAND_NAME`, `BRAND_TAGLINE`, `BRAND_PRIMARY` (then rebuild) |
| Web defaults / logo / favicon | `frontend/src/config/brand.ts`, `frontend/public/brand/logo.svg` |
| Android app name, colour, default server | `mobile/lib/config/brand.dart` (or GitHub Actions variables), launcher label in `mobile/android/app/src/main/res/values/strings.xml`, icons in `mipmap-*` |
| Each agency's own logo and colour | In the app: **Settings & users → Branding** |

The code package (`app.erp`) and database names are deliberately brand-neutral.

## Roadmap (from the PRD phases, not yet built)

* Mobile: background GPS patrol tracking, QR/NFC checkpoints, push notifications (Firebase), iOS build, Play Store signing
* Patrol routes and checkpoints, inventory and uniform ledger, supervisor site-visit checklists
* MFA, OIDC single sign-on, break-glass support access
* SMS / WhatsApp / e-mail / push delivery (notifications are already queued in the `notifications` table)
* File storage for documents and selfies (`object_key` / `selfie_hash` fields are ready)
* Platform subscription billing, statutory return files, accounting exports
* AI: document extraction with human review, natural-language reports

> Legal and payroll rules (PF, ESI, PT, minimum wages, GST) are agency configuration with illustrative defaults.
> They must be validated by qualified professionals before production use.
