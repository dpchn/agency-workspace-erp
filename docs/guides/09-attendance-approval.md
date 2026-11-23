# 09 · Attendance: review, approve, muster roll

**Who / where:** operations, supervisor or owner · web app → **Attendance** (supervisors can also approve in the app, guide 19)

▶ Video: [09-attendance-approval.webm](videos/09-attendance-approval.webm) (1 min 10 s)

## What it's for
Every duty (one person on one shift) gets one attendance record. A record is only **paid** (payroll) and **billed**
(invoice) once it is approved. This is where you check the doubtful ones and approve the rest.

## Before you start
* Duties are rostered (guide 08) and people have checked in: own phone (guide 18), kiosk, roll-call or register
  (guide 10).

## Steps
1. **Attendance → Records & approval.** Choose the dates, a site, the approval status (default **PENDING**) and
   optionally **Flagged only**. Each row shows in / out, worked time, status (present, half day, absent), pay / bill
   units, flags and **Marked by**:
   **Own phone**, **Kiosk+PIN**, **Supervisor** (roll-call or marked by someone else) or **Register** (paper register).

   ![Records](img/09-records.png)
2. Tick rows (or the header box for all) → **Approve** or **Reject**.
   * If **you** marked a duty for someone else (roll-call, "mark checked in", register entry), you can't approve
     it. Those rows are skipped with *"you marked this duty; another person must approve it"*.

   ![Self-approval blocked](img/09-self-approval-blocked.png)
3. Tick **Flagged only** to see what needs a human look first. Reject what is wrong (here: a check-in 16 km
   from the site), fix what needs correcting with **Adjust**, then approve the rest.

   ![Flagged only](img/09-flagged.png)
4. **Adjust** (pencil on a row): give the corrected in / out times or status and a reason (for example "phone
   battery dead, gate register shows 08:00"). It goes to **Corrections**, where **another person** approves it. The
   original punches are never changed.
5. **Close ended duties** (top right): marks duties that ended without any check-in as **ABSENT** now (the server also
   does this every 30 minutes, 2 hours after the shift ends).
6. **Muster roll** tab: person × day grid for a month: **P** present, **H** half, **A** absent, **L** leave, **?** pending.
   Hover a cell for the site; the last column is the duty count.

   ![Muster roll](img/09-muster.png)

## What the system does for you
* Works out late minutes, worked time, overtime and present / half-day / absent from the shift and your policy
  settings (`lateGraceMinutes`, `fullDayMinPercent`, `halfDayMinPercent`).
* **Flags** suspicious punches for review instead of penalising anyone automatically:

  | Flag | Means |
  |---|---|
  | OUTSIDE GEOFENCE | Punched outside the site radius (refused outright at ENFORCE sites for own-phone punches) |
  | LOW ACCURACY / NO LOCATION | GPS was poor or off |
  | IMPOSSIBLE TRAVEL | Two punches too far apart for the time between them |
  | DEVICE SHARED | The same phone punched for two people |
  | CLOCK SKEW / LATE SUBMISSION | Phone clock wrong / uploaded much later (offline) |
  | NO ASSIGNMENT / WRONG SITE | No duty at that time / duty is at another site; not counted |
  | NO SHOW / MISSING CHECKOUT | Nobody checked in / checked in but never out (closed at planned end) |
* **Auto-approval:** clean duties approve themselves, but only for trusted sources (`autoApproveSources`: own phone
  and kiosk by default). Supervisor and register marking always wait for a second person.
* **Locked periods:** once a month's payroll is locked (or the duty invoiced), its attendance can't be changed. The
  rows show **locked**; late corrections are paid next month as arrears.

  ![Locked month](img/09-locked.png)

## Common situations
* **"53 skipped: you marked this duty".** Expected. Ask another person with approval rights (owner, operations) to approve.
* **Guard forgot his phone.** He punches on the site kiosk or the supervisor marks him (guide 10), or you **Adjust** the duty.
* **A check-in was 16 km away.** Reject it, or ask the supervisor. Rejected duties are not paid or billed.
* **Someone is marked ABSENT but was there.** **Adjust** the status to present with a reason; another person approves.
* **Need last month changed after payroll was locked.** Not possible; correct it in the next month (arrears).

## Related
[08-roster](08-roster.md) · [10-no-phone-attendance](10-no-phone-attendance.md) · [11-client-signoff](11-client-signoff.md) ·
[14-payroll](14-payroll.md) · [18-guard-app](18-guard-app.md) · [19-supervisor-app](19-supervisor-app.md)
