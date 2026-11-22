# DutyGrid: workforce ERP for security & facility agencies

> The product name, logo and colours are placeholders and can be changed in one place each (see [Rebranding](#rebranding)).

A multi-tenant SaaS for security agencies, in the same way MyGate serves many housing societies:
every agency signs up and runs its own branches, clients, sites, guards, payroll and billing,
fully isolated from the others.

**The problem it solves:** an agency charges each client under that client's contract and pays
guards under separate wage rules, while guards keep moving between sites (transfers, relief,
replacements, double duties, extra shifts). DutyGrid values **every duty individually**, so
payroll and invoices always reconcile, however often people move.
→ See **[docs/DOMAIN.md](docs/DOMAIN.md)** for the 30 scenarios it handles.

## Modules

| Area | What's included |
|---|---|
| Platform | Agency self-signup with seeded defaults, trial/suspend, plan limits, platform operator view (no access to tenant HR or finance data) |
| Identity | Login, JWT sessions with revocation, 9 roles + permission matrix, branch/site/client scoping, maker-checker approvals |
| HR | Employee lifecycle (draft → verification → active → suspended → exited), documents with expiry, deployability gate, guard logins |
| Clients | Clients, sites (geofence ENFORCE/FLAG/OFF), posts, shift requirements |
| Contracts | Versioned, effective-dated rate cards; 6 billing models; minimum guarantee, extra-shift and OT rates, absence penalties |
| Deployment & roster | Permanent/temporary/reliever postings, transfers, auto-roster generation, replacement finder, extra shifts, no double-booking (DB constraint), rest-interval rule |
| Attendance | Mobile check-in/out, geofence, fraud flags, offline batch sync, day close (no-shows), adjustments, approvals, muster roll |
| Leave | Apply/approve; releases rostered duties |
| Payroll | Per-duty valuation across sites, configurable statutory components, advances/bonus, versioned runs, lock, arrears, bank CSV, payslips |
| Billing | Contract-driven invoices, uncovered-duty deductions, GST (CGST/SGST vs IGST), approval, numbering per financial year, credit notes, supplementary invoices, receipts, aging, statements, printable invoice |
| Operations | Incidents with SLA escalation |
| Reports | Dashboard, site/contract margin, guard deployment trail, audit log |
| Portals | Guard self-service (duties, check-in, payslips, leave) and client portal (deployment today, invoices, incidents) |

## Tech stack

* **backend/**: Java 21, Spring Boot 3.3 modular monolith, PostgreSQL 15+ (row-level security), Flyway, OpenAPI at `/swagger-ui`
* **frontend/**: Next.js 14 (App Router, TypeScript, Tailwind)
* **docs/**: domain scenarios, architecture, original specifications (`docs/specs/*.pdf`)

## Quick start (Docker): clone and run

Needs only Docker Desktop (or Docker Engine with the compose plugin).

```bash
git clone https://github.com/dpchn/agency-workspace-erp.git
cd agency-workspace-erp
docker compose up -d --build                    # first build takes a few minutes

# optional: load a demo agency with one month of data (all roles)
docker compose --profile demo up demo-seed
```

| Open | URL |
|---|---|
| Web app (all users) | http://localhost:3000 |
| API docs (Swagger) | http://localhost:8080/swagger-ui |

**Demo logins** (after the seed step):

| Role | Login | Password |
|---|---|---|
| Agency owner | `owner.demo@shield.test` | `Passw0rd!` |
| Finance | `fin.demo@shield.test` | `Passw0rd!` |
| Operations | `ops.demo@shield.test` | `Passw0rd!` |
| Guard (open on a phone) | `8dem333333` | `guard123` |
| Client portal | `client.demo@phoenix.test` | `Client@123` |

Or click **Create your account** on the login page to register your own agency.

* **From another device** (e.g. a guard's phone on the same Wi-Fi), open `http://<your-computer-IP>:3000`.
  Only port 3000 is needed, because the web server forwards `/v1/*` to the backend.
* GPS check-in on phones needs **HTTPS** when not on `localhost`. Put the stack behind a reverse proxy
  with a certificate (Caddy, Nginx, Cloudflare Tunnel) for real use.
* **Stop:** `docker compose down` (data is kept). **Reset everything:** `docker compose down -v`.
* **Before going live:** set `JWT_SECRET` (and `POSTGRES_PASSWORD`) in a `.env` file next to `docker-compose.yml`.

**Who uses what:** there is one app for everyone, and each role gets its own screens (guards and clients get
phone-friendly views that can be installed to the home screen). See **[docs/USER_GUIDE.md](docs/USER_GUIDE.md)**
for screenshots and a month-in-the-life walkthrough.

## Local development

```bash
# Postgres roles (once): owner runs migrations, app role is subject to RLS
psql -U postgres -f deploy/postgres-init.sql

cd backend && mvn spring-boot:run          # :8080, migrations run automatically
cd frontend && npm install && npm run dev  # :3000
```

### Tests

```bash
cd backend && mvn test                     # golden payroll / billing / rate-resolution unit tests
python3 scripts/e2e_scenario.py            # full business scenario against a running backend (50 checks)
```

The end-to-end script runs one realistic month: two agencies, two clients in different states, a
guard transferred mid-month, a replacement, an uncovered no-show, a client-requested extra shift, a
contract rate revision, maker-checker approvals, payroll, invoices, a payment and isolation checks.

## Rebranding

| What | Where |
|---|---|
| Product name, tagline, colour (backend) | `backend/src/main/resources/application.yml` → `app.brand.*` or env `BRAND_NAME`, `BRAND_TAGLINE`, `BRAND_PRIMARY_COLOR`, `BRAND_LOGO_URL` |
| Product name, tagline, colour (web) | `frontend/src/config/brand.ts` or env `NEXT_PUBLIC_BRAND_NAME`, `NEXT_PUBLIC_BRAND_TAGLINE`, `NEXT_PUBLIC_BRAND_PRIMARY` |
| Logo / favicon | `frontend/public/brand/logo.svg` |
| Per-agency white-label | Each agency sets its own logo and colour in **Settings → Branding** |

The Java package (`app.erp`) and database names are deliberately brand-neutral.

## Configuration (env)

| Variable | Default | Notes |
|---|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:5432/erp` | |
| `DB_APP_USER` / `DB_APP_PASSWORD` | `erp_app` | runtime role (must **not** own tables, so RLS applies) |
| `DB_OWNER_USER` / `DB_OWNER_PASSWORD` | `erp_owner` | migrations |
| `JWT_SECRET` | dev value | **set ≥ 32 random bytes in production** |
| `ACCESS_TOKEN_MINUTES` | 720 | |
| `CORS_ORIGINS` | `*` | comma-separated origin patterns (safe default: auth is bearer-token, no cookies) |
| `SIGNUP_ENABLED` / `TRIAL_DAYS` | true / 30 | |
| `JOBS_ENABLED` | true | day close, incident escalation, overdue marking, outbox relay |

## Roadmap (from the PRD phases, not yet built)

* Flutter guard/supervisor app (the web `/guard` page is a mobile-friendly PWA-style stand-in)
* Patrol routes and checkpoints, inventory and uniform ledger, supervisor site-visit checklists
* MFA, OIDC provider integration, break-glass support access
* Push/SMS/WhatsApp/e-mail providers (notifications are queued in the `notifications` table)
* Object storage for documents and selfies (the `object_key` / `selfie_hash` fields are ready)
* Subscription billing for the platform, statutory return files, accounting exports
* AI: document extraction with human review, natural-language reports over approved metrics

> Legal and payroll rules (PF, ESI, PT, minimum wages, GST) are tenant configuration with illustrative
> defaults. They must be validated by qualified professionals before production use.
