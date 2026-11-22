# Mobile app (Android): guards & supervisors

A Flutter app using the same backend API as the web app. One app serves every field role; what each
person sees depends on their role:

| Role | Tabs |
|---|---|
| **Guard** | **Duty** (today and next 7 days, big Check in / Check out), Attendance (month), Payslips, Leave, Incidents |
| **Supervisor / Operations / Owner** | **Today** (all posts, gaps first, with fill rate, uncovered, late and absent), **Approve** (flagged/late attendance, bulk approve), My duty (if they are also an employee), Incidents |
| Client users | Directed to the web portal |

## What it does

* **GPS check-in/out.** The location is sent with each punch and the server checks the site's geofence.
  At ENFORCE sites a far-away punch is rejected with the distance shown, so the guard knows to move closer.
* **Works offline.** With no network, the punch is saved on the phone with its real time and shown as
  "waiting to sync". It syncs automatically every minute, or with **Sync now**. The server ignores
  duplicates, so retrying is always safe.
* **Optional selfie at check-in** (`SELFIE_AT_CHECKIN=true`). Only a hash of the photo is sent; the photo stays on the phone.
* **Supervisor on the spot.** See who's missing, tap **Find guard** for a ranked list (relievers first, then
  guards off duty at the same site; people who are on leave, unqualified, double-booked or have expired
  documents are excluded). You can also **replace** a guard, or **mark check-in/out** for a guard without a phone.
* **Approvals.** Clean duties approve themselves. Late or flagged ones appear in **Approve**.
* **Incidents.** One tap to report (severity, site, details). Supervisors acknowledge, resolve and close.
  Unacknowledged incidents escalate automatically on the server.
* **Agency branding.** After sign-in the app uses the agency's name and colour (set on the web under Settings → Branding).

## Install on a phone

1. On GitHub open **Releases → "Android app (latest)"** and download `app-release.apk` on the phone.
   It is built automatically by `.github/workflows/android.yml` on every change.
2. Allow "Install unknown apps" for the browser when asked, then open the file.
3. Sign in with the mobile number or e-mail and password created in the web app
   (Employees → employee → **Create login**). Enter the **server address** once: the web app URL,
   e.g. `https://erp.youragency.com`, or `http://192.168.1.20:3000` for a local test.

> Android requires location permission for check-in. For production use HTTPS; plain HTTP is allowed
> only so you can test against a computer on the same Wi-Fi.

## Rebrand

| What | Where |
|---|---|
| App name, colour, default server | `lib/config/brand.dart`, or build flags `--dart-define=BRAND_NAME=… BRAND_COLOR=0xFF… API_URL=…` |
| Name under the launcher icon | `android/app/src/main/res/values/strings.xml` |
| Launcher icon | `android/app/src/main/res/mipmap-*/ic_launcher.png` |
| Package id (needed for Play Store) | `android/app/build.gradle.kts` → `applicationId` |

In GitHub Actions you can set repository **Variables** `API_URL`, `BRAND_NAME` and `BRAND_COLOR`, so every
APK comes pre-configured for your server.

## Develop / build

```bash
flutter pub get
flutter run                                  # phone connected via USB or an emulator
flutter test                                 # unit + widget tests
flutter test test/live_backend_test.dart --dart-define=SERVER=http://localhost:8080   # against demo data
flutter build apk --release --dart-define=API_URL=https://erp.youragency.com
```

**Play Store:** create an upload keystore and configure `signingConfigs` in `android/app/build.gradle.kts`.
CI builds are signed with a debug key, which is fine for direct installs but not for the Play Store.

## Code map

```
lib/config/brand.dart          branding + default server
lib/core/api.dart              HTTP client, error envelope, Idempotency-Key
lib/core/session.dart          login, /v1/me, role & permissions, tenant branding
lib/core/punch.dart            GPS + optional selfie + online send / offline queue
lib/core/offline_queue.dart    persisted punch queue, batch sync
lib/screens/guard/*            duty, attendance, payslips, leave
lib/screens/supervisor/*       today's posts (fill/replace/mark), approvals
lib/screens/incidents_tab.dart report + manage incidents
```
