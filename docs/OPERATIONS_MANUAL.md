# Operations manual: how to use every feature

Written for agency staff. Menu names are as they appear in the web app (left sidebar). What you see depends on
your role; see the roles table in [CONFIGURATION.md](CONFIGURATION.md#3-roles-and-permissions).

**Contents**
1. [First-time setup (owner)](#1-first-time-setup-owner)
2. [People: hiring to exit (HR)](#2-people-hiring-to-exit-hr)
3. [Clients, sites and contracts](#3-clients-sites-and-contracts)
4. [Pay rates and salary rules (finance)](#4-pay-rates-and-salary-rules-finance)
5. [Deployment and transfers (operations)](#5-deployment-and-transfers-operations)
6. [Roster: shifts, gaps, replacements, extra shifts](#6-roster)
7. [Attendance: check-in, approvals, corrections](#7-attendance)
8. [Leave](#8-leave)
9. [Incidents](#9-incidents)
10. [Payroll (month end)](#10-payroll-month-end)
11. [Invoicing and collections (month end)](#11-invoicing-and-collections)
12. [Reports and audit](#12-reports-and-audit)
13. [Users, roles and security](#13-users-roles-and-security)
14. [Guard: Android app / phone](#14-guard-android-app)
15. [Supervisor in the field: Android app](#15-supervisor-android-app)
16. [Client portal](#16-client-portal)
17. [Platform admin (SaaS owner)](#17-platform-admin)
18. [How the numbers are calculated](#18-how-the-numbers-are-calculated)

---

## 1. First-time setup (owner)

Sign up on the login page with **Create your account**. Your agency starts with: a "Head Office" branch, worker
types (Security Guard, Lady Guard, Head Guard, Gunman, Supervisor, Housekeeping), shift timings (A/B/C 8-hour,
D12/N12 12-hour, General 9-6) and sample PF/ESI/PT rules. Then do these once, in this order:

| # | Where | What |
|---|---|---|
| 1 | **Settings & users → Organisation** | Legal name, GSTIN, **state code** (e.g. 27 Maharashtra; decides CGST+SGST vs IGST), time zone |
| 2 | **Settings & users → Business policies** | Review defaults: monthly-to-daily pay, late grace, overtime, auto-approval, maker-checker ([meanings](CONFIGURATION.md#business-policies-tab)) |
| 3 | **Settings & users → Branding** | Your colour and logo URL; staff, guards and clients will see them |
| 4 | **Masters** | **Branches** (one per office/state), **Service types** (add Bouncer, Driver… and the documents each requires before deployment, e.g. `POLICE_VERIFICATION`, `ARMS_LICENSE`), **Shift templates**, **Holidays** |
| 5 | **Pay rates → Salary components** | Have your payroll advisor confirm PF/ESI/PT for your state |
| 6 | **Settings & users → Users & roles** | Add HR, operations, supervisors, finance (each gets a login; a temporary password is shown once) |
| 7 | Sections 2–4 | Employees, clients/sites/contracts, pay rates |

---

## 2. People: hiring to exit (HR)

**Employees → New**: code (unique, e.g. G0001), name, mobile, branch, main service type, other service types they
can cover, joining date, weekly off, bank account + IFSC, UAN/ESIC.

Lifecycle (buttons on the employee page):

```
DRAFT --Submit for verification--> PENDING_VERIFICATION --Activate--> ACTIVE <--Suspend/Reinstate--> SUSPENDED
                                                                      \--Exit--> EXITED
```

* **Add document** (police verification, ID, training, arms licence…) with number and expiry date, then **Verify**
  (or reject). Editing a document puts it back to pending.
* **Activate** only works when every document the service type requires is **verified and not expired**. The same
  check blocks deployment, rostering and replacement later, so a guard with an expired licence can't be posted.
* **Create app login**: gives the guard their phone login (mobile number + password) for the Android app.
* **Suspend** (reason required) cancels their future duties. **Exit** (date + reason) ends deployments,
  cancels future duties and disables the login; the final month is paid by the next payroll.
* **Employees → Expiring documents**: the compliance calendar of what expires in the next 30 days.

---

## 3. Clients, sites and contracts

**Clients → New**: code, legal name, GSTIN (the state is taken from it), billing address, payment terms (days).

**Sites & posts → New site**: client, branch, address, **latitude/longitude** (from Google Maps), geofence radius
(metres) and **geofence policy**:
* **ENFORCE**: check-ins outside the radius are rejected (the guard sees how far away they are).
* **FLAG**: accepted but marked for review (good when GPS is weak indoors).
* **OFF**: location ignored.

**Posts** (on the site page): each position to cover, e.g. "Main Gate – Security Guard". Then
**Shift requirements**: which shift template, how many people, which weekdays, from which date. For example, Main Gate
needs 1 guard on D12 and 1 on N12, all 7 days. The roster is generated from these.

**Contracts → New**: client, contract number, start/end. Version 1 opens as a draft. **Add rate** for each
site + service type:

| Billing model | Charged as |
|---|---|
| Monthly per post | rate × posts × (days active ÷ days in month). If *attendance-linked*, uncovered duties are deducted |
| Per shift / per day | rate × duties actually worked (holiday duties × holiday multiplier) |
| Per hour | rate × hours worked |
| Per headcount per month | rate × distinct people deployed |
| Lump sum per month | fixed amount |

Optional per rate: extra-shift rate (for shifts the client asks for), OT hourly rate, minimum guarantee
(units billed even if fewer worked), penalty per uncovered duty, GST %.

**Submit** → someone with approval rights (owner) **Approves**. Approved versions can't be edited.
**Rate change:** use **Amend contract** with the date the new rates start. A new draft version is created with
the old rates copied; edit them, submit and approve. The old version automatically ends the day before, and that
month's invoice is split at the change date.

---

## 4. Pay rates and salary rules (finance)

**Pay rates → Pay rates → New.** A rate applies to a scope; leave fields blank to mean "any":

| Scope set | Example | Use |
|---|---|---|
| Service type only | Guard: 15,000/month | Agency default |
| + Branch | Guards in Pune: 14,500 | Regional minimum wage |
| + Client | All Phoenix sites: 16,000 | Client-specific wage |
| + Site | Phoenix Kurla: 16,500 + 50/duty allowance | Site allowance / difficult site |
| + Employee | Ramesh: 18,000 | Individual arrangement |

The **most specific matching rate in force on the duty's date wins**. Wage basis: monthly, daily or hourly.
Optional: allowance per duty, double-duty rate, OT hourly rate. Minimum-wage revisions: add a new rate with the new
**effective from** date. Duties before and after that date are paid at their own rates.

**Salary components**: PF, ESI, PT and others (see [CONFIGURATION.md](CONFIGURATION.md#salary-components-pay-rates--salary-components)).

**Advances / bonus / deductions**: per employee per month (advance recovery, loan EMI, uniform, fine, bonus,
allowance…). They're picked up by that month's payroll and locked with it.

---

## 5. Deployment and transfers (operations)

**Deployments → Deploy**: employee, site, optional post and shift, type:
* **Permanent**: their home posting. Only one at a time.
* **Temporary**: covers a few days elsewhere (end date required). It overrides the permanent posting for those dates.
* **Reliever**: joins the floating pool used first for replacements.

**Transfer** (the guard moves for good): employee, new site/post, **effective date**, reason. The old posting
ends the day before, their future planned duties at the old site are released (those shifts show as open), and the
next **Generate roster** puts them at the new site. History is kept. **Reports → Employee deployment trail** shows
where each person worked each month.

**End**: closes a deployment on a date (future duties are released).

> Pay follows the duty: whatever site a guard worked on a given day, that day is paid at the rate for that site
> (or per the `payRatePolicy` setting) and billed to that site's client.

---

## 6. Roster

**Roster** shows posts × days. Green = filled, amber = partly filled, red = open. Use the date picker, 7/14-day
view and site filter.

* **Generate roster** (date range, optionally one site): creates shifts from the post requirements and assigns
  deployed guards, skipping their weekly off, approved leave, overlapping duties and rest-time breaches.
  Running it again only fills gaps; nothing is duplicated.
* Click a shift to:
  * **Assign guard** to an open slot. The picker lists available, qualified people, best first (relievers, then
    guards off duty at the same site, then the branch pool), with warnings such as "would breach rest interval".
  * **Replace** an assigned guard (absent, sick…). The original is marked no-show and the replacement is paid.
    For a **mid-shift handover** (e.g. relieve at 2 a.m.), give the handover time: each person is paid for their own part.
    Tick **double duty** when someone works back-to-back shifts; this is the only way to break the minimum rest gap.
  * **Cancel** an assignment (before check-in) or **Cancel shift**.
* **Extra shift**: post, date, shift and **client requested?** Client-requested extras are billed (at the
  contract's extra-shift rate). Extras the agency adds for its own reasons are paid but never billed.

The system never lets one person hold two overlapping duties; the database itself refuses it.

---

## 7. Attendance

**How punches arrive.** There are four ways, so that everyone can be counted, including staff without a phone:

| Way | Who | Evidence kept | Approves itself? |
|---|---|---|---|
| **Own phone** (`MOBILE`) | Guards with a smartphone: Android app or web `/guard` | GPS + geofence, device, optional selfie | Yes, if clean |
| **Site kiosk** (`KIOSK`) | Workers without a phone: a shared tablet at the gate, or the supervisor's phone in kiosk mode | Employee code + **PIN**, **photo** at the device, device location | Yes, if clean |
| **Supervisor roll-call** (`SUPERVISOR`) | Supervisor marks the team on duty in one go | **Team photo**, supervisor's GPS, who marked | **No**: someone else approves |
| **Paper register** (`MANUAL`) | Office types in a signed register page (no phone, no kiosk at that site) | **Photo of the register page**, who entered it | **No**: someone else approves |

* Each punch is matched to the rostered duty, and lateness, overtime, half/full day are worked out automatically.
  Whichever way was used, the duty is still priced at **that site's** contract rate and pay rate.
* The **Sources** column on the attendance page shows how each duty was evidenced. Which ways may approve
  themselves is a policy (`autoApproveSources`).
* **Maker-checker for proxy marking:** whoever marked a duty for someone else (roll-call, single mark, register entry)
  can't approve it. A second person must approve it, which stops one person inventing attendance.
* With no network, phones and kiosks save punches on the device and sync later. Late uploads are flagged.

**Workers without a phone: setup**
1. **Employees → (person) → Has smartphone: off**, then **Set kiosk PIN** (4–6 digits; easy PINs like 1111 or 1234
   are refused). Add a **profile photo** so the supervisor and kiosk can match faces.
2. Either put a **site kiosk** at the gate: **Settings & users → Add user → role *Site device (kiosk)***, limited
   to that site. Sign in on a tablet at **/kiosk** (or in the Android app). It can only punch people in and out at
   its own site and can't see anything else. Or the **supervisor** uses kiosk mode or roll-call on their own phone.
3. Five wrong PINs lock that person's PIN for 15 minutes (`pinMaxAttempts`, `pinLockMinutes`). Setting a new PIN
   unlocks it.
4. A person who punches at a kiosk at a site where they have no duty is **not** counted there (flag `WRONG_SITE`). A
   supervisor limited to some sites can't mark anyone at other sites.

**Team roll-call (Attendance → Team roll-call, or the supervisor app):** choose the site and date, tick the people
present, take one photo of the team, then **Mark in** (and later **Mark out**). Everyone is marked with your location
and the photo. Each result is shown per person. A duty at another site is rejected.

**Paper register entry (Attendance → Register entry):** choose the site and date, set each person to P/H/A
(optionally the times), attach a photo of the signed register page, and submit. This creates correction
requests. **Another person** approves them under *Corrections*, where they see the register photo next to each
line and can approve a whole page at once.

**Automatic checks** (never automatic penalties; flagged for review): outside geofence, poor GPS accuracy,
impossible travel speed, the same phone used by two guards, phone clock wrong, uploaded very late, no rostered duty.

**Attendance page**
* Filter by dates, site, status, **Flagged only**.
* Select rows → **Approve** / **Reject**. Clean duties approve themselves (if the policy is on).
* **Adjust**: request a correction (e.g. "forgot phone, gate register shows 08:00"), giving the corrected times or
  status and a reason. **Another person** approves it under *Attendance adjustments*. The original punches are
  never altered.
* **Close ended duties**: marks duties that ended without check-in as ABSENT now. The server also does this every 30 minutes.
* **Muster roll** (month): employee × day grid (P = present, H = half, A = absent, L = leave, with site code).

Once payroll for a month is **locked**, that month's attendance can't be changed. Late approvals are paid in the
next payroll as arrears.

**Client sign-off (Attendance → Client sign-off):** for each site and month, whether the client has **confirmed**
the attendance, **disputed** it (with the days and what was wrong), or not responded yet. Correct the attendance if
the client is right, then **Resolve** with a note. While a dispute is open, that client's invoice for the month
can't be issued (`blockInvoiceOnDispute`). Invoice generation also warns about sites the client hasn't confirmed.

---

## 8. Leave

Guards apply in the app (**Leave → Apply leave**); HR/operations can **Record leave** for anyone on the web.
**Approve** releases the guard's rostered duties in those dates, so the shifts show as open and need a replacement.
Paid types (casual, sick, earned by default) are paid at the home rate.

---

## 9. Incidents

Anyone (guard, supervisor, client) can **Report incident**: site, severity (low/medium/high/critical), what
happened. Each severity has a response deadline (15 min for critical by default). If nobody **acknowledges** in
time, it escalates automatically, and keeps escalating every deadline window.

Supervisor flow: **Acknowledge → Start work → Resolve** (notes required) → **Close**. **Reopen** if needed.
Choose whether the client can see it (client-reported incidents are always visible to them).

---

## 10. Payroll (month end)

1. Make sure attendance is approved (dashboard shows pending approvals).
2. **Payroll → Run payroll** (month, optional branch). The draft shows each employee's duties, gross, deductions
   and net, plus **exceptions** (missing pay rate, no bank account, pending attendance, deductions exceeding gross).
3. Fix exceptions, then **Recalculate**. Each recalculation is a new version; the old one is kept as superseded.
4. **Mark reviewed** (preparer) → **Approve** (a different person) → **Lock**. Locking freezes the month's attendance and adjustments.
5. **Bank file (CSV)**: upload it to your bank's bulk payment. Then **Mark paid**.
6. Payslips (click an employee) show **one duty line per site with the rate used**, so a guard who worked at two
   sites sees both. Guards see their payslip in the app after the run is locked.

---

## 11. Invoicing and collections

1. **Invoices → Generate invoice** (client, month). It is built from the approved contract version(s) and approved,
   billable duties:
   * base charge per contract model, prorated for partial months and split at rate changes
   * **less: uncovered duties** (attendance-linked monthly contracts) and any penalty
   * **additional shifts on request**, **overtime** (if priced), **minimum guarantee top-up**
   * GST: CGST + SGST if the client is in your state, otherwise IGST
2. Check it; add a **manual line** if needed (draft only). **Mark reviewed → Approve** (different person) → **Issue**.
   Issuing assigns the number (e.g. `INV/2026-27/00001`) and locks the billed duties so they can never be billed twice.
3. **Print / PDF**: the printable tax invoice.
4. Mistake after issue? Use **Credit note** (amount + reason, then the same approval flow). Issued invoices are never edited.
   Duties approved after the invoice was issued: generate again to get a **supplementary** invoice for just those.
5. **Receipts & aging → Record receipt**: client, amount, TDS, bank reference (UTR), date. It is applied to the oldest
   due invoices automatically (or choose which). Entering the same reference twice is ignored.
6. **Receivables aging** (not due / 1-30 / 31-60 / 61-90 / 90+ days) and **Client statement**.
   Unpaid invoices past the due date turn **OVERDUE** automatically. **Mark disputed / Resolve dispute** when a client
   disputes one.

---

## 12. Reports and audit

| Report | Shows |
|---|---|
| **Dashboard** | Today: shifts, fill rate, uncovered, checked in, late, absent, flagged, pending approvals/leave/adjustments, expiring documents, open incidents, receivables |
| **Reports → Site / client margin** | Per site per month: revenue (issued invoices) − wages − employer statutory cost = contribution and margin %. Find the sites that lose money. |
| **Reports → Employee deployment trail** | Per guard per month: each site worked, first/last day, duties, as replacement, double duties |
| **Reports → Audit log** | Who did what and when (approvals, rate changes, transfers, settings…) |

---

## 13. Users, roles and security

**Settings & users → Users & roles → Add user**: name, e-mail or mobile, **role**, optional **branch/site scope**
(e.g. a supervisor sees only their three sites), client (for client users). Change a role or scope at any time.
**Sign out everywhere** ends all of that user's sessions immediately (lost phone, staff leaving).

**Site device (kiosk)** is a role for a shared tablet or phone fixed at a site. It must be limited to its site(s). It
can only show who is on duty there today and punch them in and out with code + PIN + photo. If a kiosk is lost,
**Sign out everywhere** for its user.

---

## 14. Guard: Android app

Install the APK (your office sends the link), open it, enter the **server address** once (your office gives it),
then your **mobile number and password**.

| Tab | Use |
|---|---|
| **Duty** | Today's and upcoming duties. At the site tap **Check in**; at the end tap **Check out**. Without network you'll see "Saved on phone"; it syncs by itself (or tap **Sync now**). At strict sites you must be inside the boundary. |
| **Attendance** | Your month: each day's site, in/out time, status and approval |
| **Payslips** | Month, net pay; tap for details (duties per site, deductions) |
| **Leave** | **Apply leave** (type, dates, reason) and see the decision |
| **Incidents** | **Report incident** (red button): site, severity, what happened |

Allow location permission when asked. Keep GPS on during duty.

---

## 15. Supervisor: Android app

Sign in with your own login. Supervisor, operations and owner roles get:

| Tab | Use |
|---|---|
| **Today** | Fill rate, uncovered, checked-in, late and absent at the top. Every post's shift, gaps first. **Find guard** fills an open slot from the ranked list. **⋮ → Replace guard** for no-shows. **⋮ → Mark checked in/out** for a guard without a phone (your location is recorded). Arrows change the day. |
| **Roll-call** | Pick a site → tick the team on duty → take a team photo → **Mark in** / **Mark out**. People without a phone are marked "no phone". Works offline (synced later). |
| **Kiosk mode** | Hand your phone to workers without a phone: they tap their name, enter their PIN and a photo is taken. Leaving kiosk mode needs your confirmation. |
| **Approve** | Flagged or late duties: tick and **Approve** / **Reject** (needs approval rights). Duties you marked yourself must be approved by someone else. |
| **My duty** | Your own check-in, if you are also an employee |
| **Incidents** | Acknowledge, start, resolve, close; report new ones |

---

## 16. Client portal

Client users (created by the agency with role *Client admin* or *Client viewer*) sign in on the same website and
see only their own data: guards required vs reported today per site, outstanding amount, **invoices** (issued
only, printable), **statement**, and **incidents** shared with them. Client admins can also report incidents.

**Attendance (client admins):** per site and month, a day strip of guards *required vs reported* (short days are
highlighted) and a person × day grid (names and P/H/A only, no pay data). The client admin either **confirms the
month** or **disputes** it, choosing the days and saying what was wrong. A dispute reaches the agency
immediately and holds that month's invoice until the agency resolves it.

---

## 17. Platform admin

For you as the SaaS owner (sign-in set up through `PLATFORM_ADMIN_EMAIL`, see
[DEPLOYMENT.md](DEPLOYMENT.md#3-after-the-first-start)). The **/platform** console:

* **Onboard agency**: creates the agency with defaults and its owner login, and shows a one-time temporary password.
* **Plans**: Starter (50 staff, 5 sites, 5 office users), Growth (500 / 50 / 25), Enterprise (no limits). **Plan &
  limits** changes the plan or overrides any limit for one agency. Guard, client and site-device logins never
  count. When an agency reaches a limit, it sees "Your plan allows N … Ask us to upgrade", and its owner sees
  usage under **Settings → Plan & usage**.
* **Trials**: a trial that has ended can't sign in ("The free trial has ended…") until you **Activate** it (paid)
  or give it **+15 days**.
* **Suspend** (with a reason): every user of that agency is signed out immediately and can't sign in. **Activate**
  restores it.
* Per agency you see the owner and their last sign-in, staff/sites/office users against the plan limits, guard app
  logins, duties in the last 30 days and the last activity. You never see an agency's HR, attendance or financial
  records.

---

## 18. How the numbers are calculated

* **Every duty is valued on its own.** Pay = the pay rate in force for that guard at that site on that date. Bill =
  the contract rate for that site and service type on that date. Payroll and invoices are sums of approved duties, so
  transfers, replacements and rate changes never need manual adjustments.
* **Monthly wage → one duty** = monthly ÷ divisor (days in month / 26 / 30). Half day = 0.5 duty. Holiday duty ×
  holiday multiplier. Double duty paid at the double-duty rate if set.
* **Overtime** = minutes beyond the shift (after the threshold) × OT rate (or per-duty pay ÷ shift hours × multiplier).
* **Gross** = duty wages + allowances + OT + paid leave + bonus/allowance adjustments.
  **Net** = gross − statutory deductions − other deductions.
* **Invoice** = contract charges − uncovered-duty deductions + extras/OT/minimum top-up, then GST.
* **Site contribution** = invoiced revenue − wages paid for duties at that site − that site's share of employer contributions.

More detail and 30 worked scenarios: [DOMAIN.md](DOMAIN.md).
