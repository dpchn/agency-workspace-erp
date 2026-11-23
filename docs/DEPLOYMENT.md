# Deployment guide

| You want to… | Section |
|---|---|
| Try it on your laptop | [1. Local evaluation](#1-local-evaluation) |
| Run it for real on a server with your domain | [2. Production](#2-production-server-with-https) |
| Do first-time setup after deploying | [3. After the first start](#3-after-the-first-start) |
| Back up, restore or upgrade | [4. Operations](#4-day-2-operations) |
| Give guards the Android app | [5. Android app release](#5-android-app-release) |
| Run without Docker | [6. Without Docker](#6-without-docker) |
| Fix a problem | [7. Troubleshooting](#7-troubleshooting) |

All settings are explained in **[CONFIGURATION.md](CONFIGURATION.md)**.

---

## 1. Local evaluation

**Needs:** Docker Desktop (Windows/Mac) or Docker Engine 24+ with Compose v2.24+ (Linux). About 4 GB free RAM.

```bash
git clone https://github.com/dpchn/agency-workspace-erp.git
cd agency-workspace-erp
docker compose up -d --build                  # first build: 5-10 minutes
docker compose --profile demo up demo-seed    # optional: demo agency with one month of data
```

* Web app: **http://localhost:3000**
* API documentation (Swagger): http://localhost:8080/swagger-ui
* Demo logins: see the README (owner, finance, operations, guard, client).

To try it from a phone on the same Wi-Fi, open `http://<your-computer-IP>:3000`. Login works, but **GPS check-in
needs HTTPS** on anything other than `localhost`, so use section 2 for real guard use.

Stop: `docker compose down` (data is kept). Wipe everything: `docker compose down -v`.

---

## 2. Production (server with HTTPS)

### What you need
* A Linux server (Ubuntu 22.04/24.04): **2 vCPU, 4 GB RAM, 40 GB disk** is enough for a few agencies with
  hundreds of guards. Use 4 vCPU / 8 GB for ~5,000 guards.
* A domain, e.g. `erp.youragency.com`, with an **A record pointing to the server's IP**.
* Ports **80 and 443** open to the internet (80 is used for the certificate challenge).

### Steps

```bash
# 1. Install Docker (Ubuntu)
curl -fsSL https://get.docker.com | sh

# 2. Get the code
sudo mkdir -p /opt/erp && sudo chown $USER /opt/erp
git clone https://github.com/dpchn/agency-workspace-erp.git /opt/erp
cd /opt/erp

# 3. Configure
cp .env.example .env
nano .env       # set DOMAIN, ACME_EMAIL, JWT_SECRET, all three DB passwords (openssl rand -base64 48)

# 4. Start with HTTPS (Caddy fetches the certificate automatically)
docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d --build

# 5. Check
docker compose ps                                   # all "running"/"healthy"
curl -s https://erp.youragency.com/v1/public/brand   # returns JSON
```

Open `https://erp.youragency.com`. To avoid typing both `-f` flags every time, put
`COMPOSE_FILE=docker-compose.yml:docker-compose.prod.yml` in `.env`. After that, plain `docker compose …` works.

In production only Caddy (80/443) is exposed. The backend, web server and database are reachable only inside Docker.

### Security checklist before going live
- [ ] `JWT_SECRET`, `POSTGRES_PASSWORD`, `DB_OWNER_PASSWORD`, `DB_APP_PASSWORD` changed (long random values)
- [ ] `.env` is not committed (it is in `.gitignore`) and is readable only by you (`chmod 600 .env`)
- [ ] `SIGNUP_ENABLED=false` if you don't want anyone on the internet creating agencies
- [ ] Daily backups scheduled and copied off the server (section 4)
- [ ] Firewall allows only 22, 80 and 443 (`ufw allow OpenSSH && ufw allow 80 && ufw allow 443 && ufw enable`)
- [ ] Statutory salary components checked by a payroll professional for each state you operate in

### Using an existing reverse proxy instead of Caddy
Skip `docker-compose.prod.yml`. Run the plain `docker-compose.yml` and point your proxy (Nginx, Traefik,
Cloudflare Tunnel) at `http://<server>:3000`. Only port 3000 needs proxying: the web server already forwards
`/v1/*` to the backend. Optionally also proxy `/swagger-ui` and `/v3/api-docs` to port 8080.

---

## 3. After the first start

1. **Your platform admin login.** Set `PLATFORM_ADMIN_EMAIL` and `PLATFORM_ADMIN_PASSWORD` (10+ characters) in
   `.env` and restart the backend. The account is created, or an existing user is made platform admin. Sign in:
   you land on **/platform**, the platform console.
2. **Onboard agencies** from the console: **Onboard agency** (name, state, GSTIN, owner name/e-mail/mobile, plan,
   trial or paid, optional limits). You get the owner's login and a **one-time temporary password** to send them;
   they change it after signing in (key icon, top right). Each agency starts with a default branch, worker types,
   shift timings and payroll components. Or leave `SIGNUP_ENABLED=true` so agencies can sign up themselves (30-day
   trial on the Starter plan).
3. Follow the **setup checklist** in [OPERATIONS_MANUAL.md](OPERATIONS_MANUAL.md#1-first-time-setup-owner).

---

## 4. Day-2 operations

### Backups
```bash
./scripts/backup-db.sh                 # writes backups/erp-YYYYMMDD-HHMMSS.dump, keeps last 14
crontab -e                             # daily at 02:30:
30 2 * * * cd /opt/erp && ./scripts/backup-db.sh >> backups/backup.log 2>&1
```
Copy the `backups/` folder off the server (rclone/S3/Google Drive). A backup that only lives on the same server isn't a backup.

### Restore
```bash
./scripts/restore-db.sh backups/erp-20261122-023000.dump    # asks for confirmation; replaces all data
```
Backups and restores run as the database superuser on purpose: tenant isolation (row-level security) is forced
even for the table owner.

### Upgrade to a new version
```bash
cd /opt/erp
./scripts/backup-db.sh
git pull
docker compose up -d --build           # database migrations run automatically on backend start
docker compose logs -f backend         # watch for "Started ErpApplication"
```

**Upgrading from a version before the shift-time fix:** if the backend ran with a non-UTC `TZ` (the default
`Asia/Kolkata`), rosters it generated have shift times off by the time-zone offset (an 08:00 shift stored as 13:30).
After upgrading, check and repair duties that haven't started yet. Past, attended and locked duties are never touched:
```bash
docker compose exec -T db psql -U postgres -d erp -v dry_run=1 < scripts/fix-shift-times.sql   # report only
docker compose exec -T db psql -U postgres -d erp -v dry_run=0 < scripts/fix-shift-times.sql   # apply
```

### Logs and health
```bash
docker compose ps
docker compose logs -f backend          # every API error response carries a correlationId; search the log for it
docker compose logs -f frontend
curl -s localhost:8080/actuator/health  # {"status":"UP"} (inside the server)
```

### Change branding
* Product (before login): `BRAND_*` in `.env`, then `docker compose up -d --build` (the web app bakes it in at build time).
* Per agency: in the app, **Settings & users → Branding** (no restart needed).

### Scaling notes
* One backend instance handles a lot (Spring Boot + PostgreSQL). Scale vertically first.
* If you run several backend instances, set `JOBS_ENABLED=true` on **one** of them only.
* Use a managed PostgreSQL (with point-in-time recovery) once you have paying agencies: set `DB_URL` and the
  two role passwords, and run `deploy/postgres-init.sql` once (as the admin user) to create the roles and database.

---

## 5. Android app release

The Android app (`mobile/`) is built **automatically by GitHub Actions** on every change to `mobile/`:

1. GitHub → repo → **Actions → "Android app"** shows the build (about 10 minutes). You can also click
   **Run workflow** to build on demand.
2. When it finishes, the APK is at **Releases → "Android app (latest)" → `app-release.apk`**.
3. **Pre-configure your server** so guards don't have to type it: repo → **Settings → Secrets and variables →
   Actions → Variables** → add `API_URL` = `https://erp.youragency.com`. Optional: `BRAND_NAME`, `BRAND_COLOR`.
   Then re-run the workflow.
4. **Distribute:** send the APK link on WhatsApp, or install it on each phone. On the phone: open the file →
   allow "install unknown apps" → Install. Guards sign in with the mobile number and password created in
   **Employees → (guard) → Create login**.

**Play Store (optional, later):** create an upload key (`keytool -genkey -v -keystore upload.jks -keyalg RSA
-keysize 2048 -validity 10000 -alias upload`), configure `signingConfigs` in
`mobile/android/app/build.gradle.kts`, change `applicationId` to your own (e.g. `com.youragency.guard`), and build an
app bundle (`flutter build appbundle`). The CI APK is signed with a debug key, which is fine for direct installs
but not for the Play Store.

**Rename the app:** `mobile/android/app/src/main/res/values/strings.xml` (name under the icon),
`BRAND_NAME` variable (name inside the app), and the icons in `mobile/android/app/src/main/res/mipmap-*`.

---

## 6. Without Docker

```bash
# PostgreSQL 15+ ; create roles + database once (edit passwords inside first)
sudo -u postgres psql -f deploy/postgres-init.sql

# Backend: Java 21 + Maven
cd backend && mvn -B package -DskipTests
DB_APP_PASSWORD=… DB_OWNER_PASSWORD=… JWT_SECRET=… java -jar target/erp-backend-*.jar      # :8080

# Web: Node 20
cd frontend && npm ci && BACKEND_URL=http://localhost:8080 npm run build && npx next start -p 3000
```
`scripts/start-local.sh` / `scripts/stop-local.sh` do all of this on a single machine for development.

---

## 7. Troubleshooting

| Symptom | Cause / fix |
|---|---|
| `docker compose up` fails with `!reset` / unknown tag | Docker Compose is older than v2.24. Update Docker. |
| Backend restarts with `password authentication failed` | DB passwords in `.env` changed after the database was created. Change them inside Postgres too (`ALTER ROLE …`) or wipe with `docker compose down -v` (deletes data). |
| Backend log: `Schema-validation` or Flyway error after an upgrade | Restore the last backup, then report the error. Never edit applied migration files. |
| HTTPS certificate not issued | DNS A record not pointing to the server yet, or ports 80/443 closed. See `docker compose logs caddy`. |
| Guards: "location permission" / check-in rejected | Phone GPS off, or the app/site is not on HTTPS. At **ENFORCE** sites the guard must be inside the radius; the error shows the distance. Change the site to **FLAG** to accept and review instead. |
| Punches stuck "waiting to sync" on the phone | No internet. They sync automatically every minute, or tap **Sync now**. Duplicates are ignored safely. |
| Payroll shows "No pay rate for …" | Add a pay rate covering that service type/site/date (Pay rates). |
| Invoice is empty | No **approved** contract version for that month, or attendance not yet approved. |
| "The person who prepared … cannot approve it" | Maker-checker. Another user with approve rights must approve, or turn off `requireApprovalSeparation` in Business policies (not recommended). |
| User gets 403 / menu item missing | Their role doesn't include that permission (see the roles table in CONFIGURATION.md). |
| Everyone was signed out | `JWT_SECRET` changed, or **Sign out everywhere** was used. Sign in again. |
