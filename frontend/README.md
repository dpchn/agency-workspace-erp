# Frontend (Next.js 14)

Web app for the workforce ERP: staff back-office, guard mobile view (`/guard`), client portal (`/portal`)
and platform operator console (`/platform`). Talks to the Spring Boot backend in `../backend`.

## Run

```bash
cp .env.example .env.local     # adjust BACKEND_URL if the backend is not on :8080
npm install
npm run dev                    # http://localhost:3000
# production
npm run build && npm run start
```

The backend must allow the frontend origin (`CORS_ORIGINS`, default `http://localhost:3000`).

## Environment variables

| Variable | Default | Purpose |
|---|---|---|
| `BACKEND_URL` | `http://localhost:8080` | Backend the Next.js server proxies `/v1/*` to (build time). Browser only needs port 3000. |
| `NEXT_PUBLIC_API_URL` | empty (same origin) | Optional: call the API directly from the browser instead (requires backend `CORS_ORIGINS`) |
| `NEXT_PUBLIC_BRAND_NAME` | `DutyGrid` | Product name (title, login page, "powered by") |
| `NEXT_PUBLIC_BRAND_TAGLINE` | `Workforce ERP for security & facility agencies` | Login page tagline |
| `NEXT_PUBLIC_BRAND_PRIMARY` | `#1d4ed8` | Primary colour (buttons, active nav, highlights) |
| `NEXT_PUBLIC_BRAND_LOGO` | `/brand/logo.svg` | Logo path or URL |

`NEXT_PUBLIC_*` values are compiled in at build time — rebuild after changing them.

## Rebranding

Everything brand-related lives in **one place**:

1. `src/config/brand.ts` — reads the env vars above (change defaults here if you prefer).
2. `public/brand/logo.svg` and `src/app/icon.svg` (favicon) — replace the files.
3. The primary colour is a CSS variable (`--brand`, see `src/app/globals.css`); Tailwind's `brand` colour
   uses it, so the whole UI re-themes from that single value.

**Per-agency white-label**: each agency (tenant) can set its own display name, logo URL and colour in
*Settings → Branding*. After login, their staff, guards and clients see the agency's brand
("powered by <product>") — like a society's name in MyGate.

## Structure

```
src/
  config/brand.ts        product brand (single source)
  lib/api.ts             fetch wrapper: auth header, Idempotency-Key, error envelope, downloads
  lib/session.tsx        login/me/tenant switch, permission checks, tenant theming
  lib/offlineQueue.ts    guard punches stored on device, synced via /v1/attendance/events/batch
  components/            CrudPage (config-driven list+form), DataTable, Form, Modal, Badge, Shell…
  app/(auth)/            login, agency signup
  app/(app)/             back-office pages (navigation filtered by role permissions)
  app/guard/             mobile guard app: check-in/out with GPS, offline queue, payslips, leave, incidents
  app/portal/            client portal: today's coverage, invoices, statement, incidents
  app/platform/          platform operator: tenant plans/status (no tenant HR/finance data)
```

## Docker

```bash
docker build -t erp-frontend --build-arg BACKEND_URL=https://api.example.com \
  --build-arg NEXT_PUBLIC_BRAND_NAME="YourBrand" .
docker run -p 3000:3000 erp-frontend
```
