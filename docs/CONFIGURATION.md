# Configuration reference

There are three layers of configuration:

1. **Environment variables**: per installation (server). Set in `.env` next to `docker-compose.yml`.
2. **Agency settings**: per agency, in the web app under **Settings & users**. Each agency can differ.
3. **Build-time branding**: product name, colour and logo for the web and Android apps.

---

## 1. Environment variables

### Docker Compose (`.env` at the repo root, copy from `.env.example`)

| Variable | Default | Required in production | What it does |
|---|---|---|---|
| `DOMAIN` | – | **yes** (prod override) | Your domain, e.g. `erp.agency.com`. Caddy gets an HTTPS certificate for it. |
| `ACME_EMAIL` | – | **yes** (prod override) | E-mail for Let's Encrypt certificate notices. |
| `JWT_SECRET` | dev value | **yes** | Key that signs sign-in tokens. At least 32 characters: `openssl rand -base64 48`. Changing it signs everyone out. |
| `POSTGRES_PASSWORD` | `postgres` | **yes** | Database superuser password. |
| `DB_OWNER_PASSWORD` | `erp_owner` | **yes** | Password of `erp_owner` (runs migrations, owns tables). |
| `DB_APP_PASSWORD` | `erp_app` | **yes** | Password of `erp_app` (runtime role; row-level security applies to it). |
| `SIGNUP_ENABLED` | `true` | – | `true`: any agency can sign up on the login page. `false`: closed; you onboard agencies from the platform console. |
| `PLATFORM_ADMIN_EMAIL` / `PLATFORM_ADMIN_PASSWORD` | empty | – | Your own platform-console login, created at backend start (password 10+ characters). An existing user is only flagged, their password is not changed. |
| `TRIAL_DAYS` | `30` | – | Trial length for new agencies. |
| `ACCESS_TOKEN_MINUTES` | `720` | – | How long a web sign-in lasts (12 h). |
| `MOBILE_TOKEN_DAYS` | `30` | – | How long an app sign-in lasts, so guards don't need a new code every shift. Leaving the agency, deactivation or a password reset still ends it immediately. |
| `SMS_PROVIDER` | `log` | – | How sign-in codes (OTP) are sent. `log`: only written to the backend log (development). `http`: your SMS gateway (below). |
| `SMS_HTTP_URL` | – | with `http` | Gateway URL; `{phone}` (10 digits), `{otp}` and `{message}` are filled in, e.g. `https://sms.example.com/send?key=XXXX&to=91{phone}&text={message}` |
| `SMS_HTTP_METHOD` / `SMS_HTTP_AUTH_HEADER` | `GET` / – | – | `POST` if your gateway needs it; optional header as `Name: value` (e.g. an API key) |
| `OTP_DEV_ECHO` | `false` | – | Returns the code in the API response. **Local testing only, never in production.** |
| `JOBS_ENABLED` | `true` | – | Background jobs (see below). Keep `true` on exactly one backend instance. |
| `CORS_ORIGINS` | `*` | – | Origins allowed to call the API directly. `*` is safe because auth uses bearer tokens, not cookies. |
| `TZ` | `Asia/Kolkata` | – | Server time zone (each agency also has its own time zone). |
| `BRAND_NAME` | `DutyGrid` | – | Product name on the login/signup pages and in API brand info. |
| `BRAND_TAGLINE` | … | – | Subtitle on the login page. |
| `BRAND_PRIMARY` | `#1d4ed8` | – | Main colour of the web app before login. |
| `BRAND_SUPPORT_EMAIL` | `support@example.com` | – | Shown as the support contact. |

> **Database passwords are applied only when the database volume is first created.** To change them later,
> change them inside Postgres (`ALTER ROLE erp_app PASSWORD '…'`) **and** in `.env`, then restart.

### Backend (Spring Boot) when run without Docker

| Variable | Default | Notes |
|---|---|---|
| `PORT` | `8080` | HTTP port |
| `DB_URL` | `jdbc:postgresql://localhost:5432/erp` | |
| `DB_APP_USER` / `DB_APP_PASSWORD` | `erp_app` / `erp_app` | Runtime role. **Must not own the tables**, otherwise tenant isolation (RLS) is bypassed. |
| `DB_OWNER_USER` / `DB_OWNER_PASSWORD` | `erp_owner` / `erp_owner` | Used only by Flyway migrations at startup |
| `DB_POOL_SIZE` | `20` | Database connections |
| `JWT_SECRET`, `ACCESS_TOKEN_MINUTES`, `CORS_ORIGINS`, `SIGNUP_ENABLED`, `TRIAL_DAYS`, `JOBS_ENABLED` | see above | |
| `BRAND_NAME`, `BRAND_TAGLINE`, `BRAND_SUPPORT_EMAIL`, `BRAND_PRIMARY_COLOR`, `BRAND_LOGO_URL` | see above | All in `backend/src/main/resources/application.yml` under `app.brand` |

**Background jobs** (when `JOBS_ENABLED=true`):

| Job | Every | Does |
|---|---|---|
| Day close | 30 min | Duties that ended 2 h ago without check-in become **ABSENT / no-show**; check-ins without check-out are closed at shift end and flagged **MISSING_CHECKOUT** for review |
| Incident escalation | 1 min | Open incidents past their response deadline get an escalation level and a notification |
| Overdue invoices | daily 01:15 | Issued, unpaid invoices past their due date become **OVERDUE** |
| Outbox relay | 15 s | Turns events (incident raised, shift uncovered, invoice issued, payment recorded…) into notification records |

### Web app (Next.js), build-time

| Variable | Default | Notes |
|---|---|---|
| `BACKEND_URL` | `http://localhost:8080` (`http://backend:8080` in Docker) | The web server forwards `/v1/*` here. Users only need to reach the web app. |
| `NEXT_PUBLIC_API_URL` | empty | Leave empty. Only set it if browsers must call the API on another host directly. |
| `NEXT_PUBLIC_BRAND_NAME` / `_TAGLINE` / `_PRIMARY` / `_LOGO` | DutyGrid / … / `#1d4ed8` / `/brand/logo.svg` | Branding; see `frontend/src/config/brand.ts` |

`NEXT_PUBLIC_*` and `BACKEND_URL` are fixed **at build time**: rebuild the web image after changing them
(`docker compose up -d --build frontend`).

### Android app, build-time (`--dart-define`, or GitHub repository Variables)

| Name | Default | Notes |
|---|---|---|
| `API_URL` | empty | Server pre-filled on the login screen (your web app URL, e.g. `https://erp.agency.com`). Users can change it. |
| `BRAND_NAME` | `DutyGrid` | Name inside the app (the launcher label is in `mobile/android/app/src/main/res/values/strings.xml`) |
| `BRAND_TAGLINE` | `Guard & supervisor app` | |
| `BRAND_COLOR` | `0xFF1D4ED8` | ARGB hex |
| `SELFIE_AT_CHECKIN` | `false` | `true`: camera opens at check-in; only a hash of the photo is sent |

---

## 2. Agency settings (web app → Settings & users)

### Organisation tab
Name, legal name, GSTIN, **state code** (decides CGST+SGST vs IGST on invoices), **time zone**.

### Business policies tab

| Setting | Default | Meaning |
|---|---|---|
| `payDivisor` | `CALENDAR_DAYS` | How a **monthly** wage becomes one duty's pay: days in that month, `26` or `30` |
| `payRatePolicy` | `SITE_RATE` | When a guard works **away from their home site**: `SITE_RATE` (pay that site's rate), `HOME_RATE` (always their home rate), `HIGHER_OF` |
| `otMultiplier` | `2.0` | Overtime hourly = (one duty's pay ÷ shift hours) × this, when the pay rate has no explicit OT rate |
| `otThresholdMinutes` | `30` | Extra minutes beyond the shift before overtime counts |
| `holidayPayMultiplier` | `2.0` | Pay multiplier for duties on holidays (Masters → Holidays) |
| `paidLeaveTypes` | `CASUAL, SICK, EARNED` | Leave types paid at the home rate (`UNPAID` is never paid) |
| `lateGraceMinutes` | `15` | Check-in later than this counts as late |
| `fullDayMinPercent` | `75` | Worked % of the shift needed for a full duty |
| `halfDayMinPercent` | `40` | Worked % for a half duty (below this the duty is absent) |
| `autoApproveCleanAttendance` | `true` | Duties with no flags and no lateness approve themselves at check-out |
| `autoApproveSources` | `MOBILE, KIOSK` | Which ways of marking may approve themselves. `SUPERVISOR` (roll-call / proxy) and `MANUAL` (paper register) are left out by default, so a second person always checks them |
| `kioskPhotoRequired` | `true` | A site kiosk takes a photo with every punch |
| `rollCallPhotoRequired` | `true` | A supervisor roll-call needs a photo of the team |
| `pinMaxAttempts` / `pinLockMinutes` | `5` / `15` | Wrong kiosk PINs before that person's PIN locks, and for how long |
| `clientSignoffEnabled` | `false` | Clients confirm or dispute monthly attendance in their portal. Off: the agency manages attendance and invoices alone; nothing waits for the client |
| `blockInvoiceOnDispute` | `true` | When client sign-off is on: an open dispute for a month blocks issuing that month's invoice |
| `checkInWindowMinutes` | `120` | How early or late a punch may be and still match a rostered duty |
| `lateSubmissionHours` | `6` | Offline punches uploaded later than this are flagged |
| `maxAccuracyMeters` | `100` | GPS readings less accurate than this are flagged |
| `maxTravelKmph` | `120` | Two punches implying faster travel are flagged (impossible travel) |
| `minRestHours` | `8` | Minimum gap between a person's duties (only DOUBLE_DUTY may break it) |
| `maxShiftsPerDay` | `2` | Cap on duties per person per day |
| `requireApprovalSeparation` | `true` | Maker-checker: whoever prepares payroll, invoices, contract changes or attendance corrections can't approve them |
| `invoicePrefix` / `creditNotePrefix` | `INV` / `CN` | Numbering: `INV/2026-27/00001` (resets each financial year) |
| `defaultGstRatePct` | `18` | GST % for new contracts |
| `incidentResponseMinutes` | `CRITICAL 15, HIGH 60, MEDIUM 240, LOW 1440` | Acknowledge deadline before escalation |

### Branding tab
`primaryColor` (e.g. `#0f766e`) and `logoUrl` (an https image URL, or a path served by the web app).
Once a user signs in, the web app and the Android app show the agency's name, logo and colour.

### Salary components (Pay rates → Salary components)
Statutory and other pay components. They are agency data, not code. They are seeded with **illustrative**
values that a payroll professional must check for each state:

| Code | Kind | Rule (seeded) |
|---|---|---|
| `PF_EE` | Deduction | 12 % of duty wage, wage ceiling 15,000 |
| `ESI_EE` | Deduction | 0.75 % of gross, only if gross ≤ 21,000 |
| `PT` | Deduction | Slab on gross: ≤7,500 → 0; ≤10,000 → 175; above → 200 |
| `PF_ER` | Employer | 13 % of duty wage, ceiling 15,000 |
| `ESI_ER` | Employer | 3.25 % of gross, only if gross ≤ 21,000 |

Calculation types: `PERCENT` (of `DUTY_WAGE`, `OT_PAY`, `GROSS` or another component's code, with an optional
ceiling and gross limit), `FIXED`, `SLAB`. A component can be limited to one state and given effective dates.

---

## 3. Roles and permissions

Each user has **one role per agency**. They can optionally be limited to some **branches** or **sites**
(Settings → Users & roles). Client users are tied to one client; guard users are tied to one employee.

| Capability | Owner | HR | Operations | Supervisor | Finance | Auditor | Client admin / viewer | Guard |
|---|:-:|:-:|:-:|:-:|:-:|:-:|:-:|:-:|
| Kiosk punch (code + PIN), kiosk mode | ✔ | | ✔ | ✔ | | | | |
| Set an employee's kiosk PIN | ✔ | ✔ | ✔ | ✔ | | | | |
| Confirm / dispute monthly attendance (only if `clientSignoffEnabled`) | | | | | | | admin only | |
| Organisation settings, branding, policies | ✔ | | | | | | | |
| Users & roles | ✔ | ✔ | | | | | | |
| Masters (branches, service types, shifts, holidays) | edit | view | edit | view | view | view | | |
| Employees, documents | edit | edit + verify | view | view | view | view | | |
| Clients, sites, posts | edit | view | edit | view | view | view | own only | |
| Contracts | edit + **approve** | | view | | edit | view | | |
| Pay rates | edit | view | | | edit | view | | |
| Deployments, roster, replacements | edit | view | edit | edit | view | view | | |
| Attendance: mark for others / request corrections | ✔ | | ✔ | ✔ | | | | |
| Attendance: approve | ✔ | | ✔ | | | | | |
| Own check-in/out, own leave | | | | ✔ | | | | ✔ |
| Approve leave | ✔ | ✔ | ✔ | | | | | |
| Payroll run / approve / lock | ✔ | view | | | ✔ | view | | |
| Invoices, receipts | ✔ | | | | ✔ | view | own issued invoices | |
| Incidents | manage | | manage | manage | | view | report + view shared | report |
| Reports (dashboard, margin, trail) | ✔ | ✔ | ✔ | | ✔ | ✔ | | |
| Audit log | ✔ | | | | | ✔ | | |

**Site device (kiosk)** (`SITE_KIOSK`) is a role with a single permission: punch people at its own site(s) with
code + PIN + photo. It must be limited to at least one site and can read nothing else.

The platform operator (you, running the SaaS) is a separate flag on a user. They see the list of agencies and
their plans, but no agency's HR or finance data.
