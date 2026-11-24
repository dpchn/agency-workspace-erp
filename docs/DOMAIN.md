# Domain model & business scenarios

## The core idea

A security agency **charges** clients according to *contracts* and **pays** guards according to
*pay rates*. The two are deliberately independent. They meet in exactly one place: the
**attendance record**, one per duty (one person on one shift).

```
Client ──< Site ──< Post ──< Shift (date + time window, demand)
                               │
                               └──< Assignment (who covers it, when) ──1:1── AttendanceRecord
                                                                            │        │
                     Contract ─< Version ─< Rate (site + service type) ──────┘        │  bill units
                     PayRate (employee > site > client > service type > branch) ──────┘  pay units
```

Every approved duty is valued **individually**:

* **Pay**: the most specific pay rate in force *on that date at that site* (`PayRateResolver`).
* **Bill**: the approved contract version in force *on that date* for that site and service type (`InvoiceEngine`).

So moving a guard between sites, one day or one month at a time, needs no special handling.
Payroll is a sum over that guard's duties, and each client's invoice is a sum over the duties
at that client's sites.

## Entities

| Entity | Purpose |
|---|---|
| Tenant | One agency (like a society on MyGate). Owns all data; isolated by `tenant_id` and PostgreSQL RLS. |
| Branch | Operating region / office; carries the state used for GST and statutory rules. |
| Service type | Generic kind of worker: Guard, Lady Guard, Gunman, Supervisor, Housekeeping, Bouncer and so on. Lists the documents required before deployment. Used for facility-management agencies too. |
| Client / Site / Post | Who we serve, where, and which positions must be covered. A post is independent of the person covering it. |
| Post shift requirement | Post X needs N people on shift template T on these weekdays, effective-dated. |
| Contract → Version → Rate | What we charge. Amendments create a new version. Approved versions are immutable and are end-dated when superseded. |
| Pay rate | What we pay, effective-dated, scoped at any level (service type, branch, client, site, employee). |
| Deployment | Where an employee is posted over time: PERMANENT (home posting, one at a time), TEMPORARY (overrides the permanent one for its dates), RELIEVER (floating pool). |
| Shift / Assignment | Roster. Assignments can never overlap; this is enforced by a database exclusion constraint. |
| Attendance event | Raw, append-only check-in/out evidence (GPS, accuracy, device, offline flag, fraud flags). |
| Attendance record | Projection per assignment: worked/late/OT minutes, status, **pay units**, **bill units**, approval, and locks set by payroll and invoicing. |
| Payroll run / payslip / lines / site costs | Versioned payroll with per-site cost allocation for margin. |
| Invoice / lines / receipts | Versioned billing, credit notes, allocation of payments. |

## Scenario handling

| # | Scenario | How the system handles it |
|---|---|---|
| 1 | Guard **transferred** mid-month from Site A to Site B | `POST /deployments/transfer` ends the old posting, starts the new one, and releases future planned duties at A. The next roster generation fills both sites. Pay is valued per duty at each site's rate, and each client is billed only for its own duties. The trail is visible in the `employee-deployment-trail` report. |
| 2 | **Temporary** posting elsewhere for a few days | A TEMPORARY deployment overrides the permanent one for those dates only. Nothing else changes. |
| 3 | Guard **absent**; a reliever covers | `replace` marks the original assignment NO_SHOW and creates a REPLACEMENT assignment. The reliever is paid and the client is billed normally because the post was covered. The replacement finder ranks relievers, then guards off duty at the same site, then the branch pool. It excludes people on leave, unqualified people, people with expired documents and double-bookings, and warns about rest breaches. |
| 4 | **No-show, nobody covers** | Day close (scheduler, or `POST /attendance/finalize`) marks the duty ABSENT. The guard is not paid. On an attendance-linked monthly contract the invoice gets a "Less: uncovered duties" line, plus an optional penalty per duty. |
| 5 | **Mid-shift handover** (relief at 2 a.m.) | `replace` with `handoverAt` shortens the original assignment and starts the reliever at that time. Each person is paid for their own portion, and the client is billed one slot. |
| 6 | **Double duty** (back-to-back shifts) | Assign with role DOUBLE_DUTY and a reason. This is the only way to break the minimum rest interval. It is paid at `extraDutyRate` if configured, otherwise at the normal duty value. |
| 7 | Client asks for an **extra guard** for an event | An EXTRA shift with `clientRequested=true` is billed at the contract's `extraShiftRate`. If the agency adds cover for its own reasons (`clientRequested=false`), it is paid but never billed. |
| 8 | **Rate revision** mid-month (client) | Amend the contract to create a new version from that date. On approval the old version is end-dated, and the invoice splits into lines per rate window (verified: 15 @ 850 + 16 @ 900). |
| 9 | **Minimum wage revision** mid-month | Add a new pay rate effective from that date. Duties before and after are valued at their own rates. |
| 10 | Different worker types at one site | Rates are keyed by site **and** service type, for both billing and pay. |
| 11 | Client pays a fixed monthly amount per post, guard paid per day | These are independent models. Billing uses MONTHLY_PER_POST and pay uses DAILY. The site margin report shows the spread. |
| 12 | Late arrival / short shift | Late minutes are recorded after a grace period. Worked % below the full-day threshold becomes HALF_DAY (0.5 units), and below the half-day threshold becomes ABSENT. The thresholds are tenant settings. |
| 13 | Overtime | Work beyond the shift length plus a threshold. Paid at the pay rate's OT rate, or per-duty value ÷ hours × multiplier. Billed only if the contract has `otHourlyRate`. |
| 14 | Holidays | The holiday calendar (all branches or one branch) applies the pay multiplier, and the billing multiplier for per-shift and per-day contracts. |
| 15 | Night shift crossing midnight | The shift belongs to the date it starts. Times are stored in UTC and generated in the site's time zone. |
| 16 | Forgot phone / GPS failed | The supervisor raises an adjustment with a reason and evidence, and a **different** person approves it. Original events are never altered. |
| 17 | Offline site (no network) | The device queues events with a device-generated UUID. Batch sync is idempotent (deduplicated by UUID). Submissions more than 6 hours late are flagged, not rejected. |
| 18 | GPS spoofing / proxy attendance | Flags include OUTSIDE_GEOFENCE (rejected at ENFORCE sites), LOW_ACCURACY, IMPOSSIBLE_TRAVEL, DEVICE_SHARED, CLOCK_SKEW and NO_ASSIGNMENT. Flagged records go to review and never get automatic penalties. |
| 19 | Attendance approved **after** payroll was locked | The locked month is immutable. Late-approved duties are paid in the next run as arrears. |
| 20 | Invoice already issued, then late duties approved | Regenerating creates a **supplementary** invoice for per-unit items only. Monthly base charges are never billed twice, and record locks prevent double billing. |
| 21 | Wrong amount on an issued invoice | A credit note goes through the normal approval flow. Issued invoices are never edited. |
| 22 | Partial payment / TDS | A receipt with a UTR reference is idempotent. It can be allocated explicitly or to the oldest-due invoice first, and TDS counts toward settlement. |
| 23 | Advances, uniform deduction, bonus | Employee adjustments for the month are consumed and locked by the payroll run. |
| 24 | Paid leave | Approved leave releases rostered duties (the shift shows as open), and paid leave types are paid at the home rate. |
| 25 | Guard exits | Deployments are ended, future duties cancelled and login disabled. The final month is settled by the next payroll. |
| 26 | Expired police verification / arms licence | Deployment, assignment and replacement are blocked. The compliance calendar lists expiring documents. |
| 27 | Pay rule disagreement (home vs site rate) | Tenant setting `payRatePolicy`: SITE_RATE, HOME_RATE or HIGHER_OF. |
| 28 | Different month-to-day conversion | Tenant setting `payDivisor`: CALENDAR_DAYS, 26 or 30. |
| 29 | Supervisor sees only their sites; client sees only their data | Memberships carry branch and site scope plus client or employee links. Out-of-scope access gets non-disclosing 404s. |
| 30 | Facility management (housekeeping, drivers) | Service types are generic. Everything above applies unchanged. |
| 31 | Worker has **no smartphone** | `hasSmartphone=false` and a kiosk PIN. They punch on a **site kiosk** (gate tablet, role `SITE_KIOSK`) or the supervisor's phone in kiosk mode, with code + PIN + photo. The duty's `sources` = `KIOSK`. |
| 32 | **Buddy punching** (someone else's PIN) | Each kiosk punch stores a photo for review. Five wrong PINs lock the PIN. A punch for someone with no duty at that site is not counted (`WRONG_SITE`). |
| 33 | Supervisor marks the **whole team** | Roll-call: one team photo + the supervisor's GPS marks the selected duties at one site. Source `SUPERVISOR`, never auto-approved, and the supervisor can't approve it. A site-limited supervisor can't mark other sites. |
| 34 | Site with **no phone and no kiosk** (paper register) | Office enters the signed register page with its photo. Each line becomes a correction request, approved by another person. Source `MANUAL`. |
| 35 | **Ghost guards** (inventing attendance) | Proxy and register marking always need a second person (`autoApproveSources`). Who marked each punch is kept (`marked_by`), along with photos, location and device. |
| 36 | Client says **"your guard wasn't there"** | Optional (`clientSignoffEnabled`, off by default). Client portal: required vs reported per day. The client confirms or disputes the month. A dispute holds that month's invoice until the agency corrects the attendance and resolves the dispute. |
| 37 | Mixed team at one site (supervisor + guards + housekeeping) | Each role is its own post with its own service type. Contract and pay rates are keyed by site + service type, so each person's duty is priced correctly, and the client sees each role in the attendance sheet. |
| 38 | Server runs in a non-UTC time zone | Shift templates are wall-clock times in the site's (or agency's) time zone, independent of the server's zone. |
| 39 | Guard can't read or forgets passwords | App sign-in is **mobile number + SMS code**; the app stays signed in for 30 days. No self-registration: codes only go to numbers the agency has added. |
| 40 | Guard **leaves** or is suspended | Exit / suspend turns off their app access and ends open sessions at once; they get no sign-in code and can't reset a password. Reinstating restores access. |

## Configurable policies (Settings → Policies)

`payDivisor`, `payRatePolicy`, `otMultiplier`, `otThresholdMinutes`, `holidayPayMultiplier`,
`paidLeaveTypes`, `lateGraceMinutes`, `fullDayMinPercent`, `halfDayMinPercent`,
`autoApproveCleanAttendance`, `checkInWindowMinutes`, `lateSubmissionHours`, `maxAccuracyMeters`,
`maxTravelKmph`, `minRestHours`, `maxShiftsPerDay`, `requireApprovalSeparation`, `invoicePrefix`,
`creditNotePrefix`, `defaultGstRatePct`, `incidentResponseMinutes`, `autoApproveSources`, `kioskPhotoRequired`,
`rollCallPhotoRequired`, `pinMaxAttempts`, `pinLockMinutes`, `blockInvoiceOnDispute`.

Statutory components (PF, ESI, PT and so on) are tenant data in **Salary components**, not code.
The seeded values are illustrative only and must be validated by a payroll professional for
each state.
