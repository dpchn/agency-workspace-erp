# 02 · Agency first-time setup

**Who / where:** agency owner · web app → key icon, **Settings & users**, **Masters**

▶ Video: [02-agency-first-setup.webm](videos/02-agency-first-setup.webm)

## What it's for
Turning a newly onboarded agency into a working one: secure the owner's login, set the organisation and
business rules, add office staff and site devices, and check the ready-made masters.

## Before you start
* You received a login and temporary password from your provider (guide 01), or you signed up yourself with
  **Create your account** on the login page.

## Steps
1. Sign in with the temporary password. Click the **key icon** (top right) → **Change password**: current
   (temporary) and new password (min 8 characters). You are signed out everywhere; sign in again.

   ![Change password](img/02-change-password.png)
2. **Settings & users → Organisation**: name, legal name, **GSTIN**, **state code** (your supplier state:
   same state as the client → CGST+SGST, other state → IGST) and time zone.

   ![Organisation](img/02-organisation.png)
3. **Business policies**: rules that differ between agencies. The important ones:
   * **payDivisor** (monthly wage ÷ days = one duty's pay) and **payRatePolicy** (site rate, home rate or higher of).
   * **fullDayMinPercent / halfDayMinPercent**, **lateGraceMinutes**, **otThresholdMinutes**.
   * **autoApproveCleanAttendance** and **autoApproveSources**: only own-phone and kiosk punches approve themselves;
     supervisor-marked and register entries always need a second person.
   * **kioskPhotoRequired**, **rollCallPhotoRequired**, **pinMaxAttempts / pinLockMinutes**.
   * **requireApprovalSeparation** (maker-checker) and **blockInvoiceOnDispute**.

   All are explained in [CONFIGURATION.md](../CONFIGURATION.md#business-policies-tab).

   ![Business policies](img/02-policies.png)
4. **Branding**: name, colour and logo shown in the web app and the guard app.
5. **Users & roles → Add user** for each office person: name, e-mail or mobile, **role** (HR, Operations,
   Finance, Supervisor, Auditor…), initial password, and optionally **limit to branches / sites** (a supervisor
   who sees only their sites).
6. For a gate tablet used by workers without a phone, add a user with role **Site device (kiosk)** and tick its
   **site**. It can only punch people in and out at that site.

   ![Add a kiosk device](img/02-add-kiosk.png)

   ![Users & roles](img/02-users.png)
7. **Plan & usage**: your plan, trial end, and staff / sites / office users against the limits.

   ![Plan & usage](img/02-plan.png)
8. **Masters**: check what was created for you and adjust:
   * **Branches**: your offices (a default *Head Office* exists).
   * **Service types**: the worker roles: Security Guard, Lady Guard, Head Guard, Gunman, Field Supervisor,
     Housekeeping. Each lists the documents it needs before deployment (e.g. police verification, arms licence).
   * **Shift templates**: D12 08:00–20:00, N12 20:00–08:00, A/B/C eight-hour shifts.
   * **Holidays**: paid at `holidayPayMultiplier`, billed at the contract's holiday multiplier.

   ![Service types](img/02-service-types.png)

## What the system does for you
* Changing a password signs that user out of every device.
* Each user has one role per agency; access is never guessed from the e-mail domain.
* Guard app logins are made from the employee record (guide 06), not here.
* Guards, client users and site devices don't count towards your plan's user limit.

## Common situations
* **A phone with a login is lost.** Users & roles → **Sign out everywhere** for that user.
* **A supervisor should only see two sites.** Edit the user → tick those sites.
* **You also do housekeeping and driving.** Service types are generic; add *Driver* with its documents.
* **Your night shift is 19:00–07:00.** Masters → Shift templates → New.
* **What are the policy defaults?** Every field shows its current value; the defaults are listed in
  [CONFIGURATION.md](../CONFIGURATION.md#business-policies-tab). Change only what differs for your agency.
* **A guard got a new phone (self-SMS sign-in).** **Users & roles → Allow new phone** on their row; their old phone
  is signed out.
* **A staff member or guard forgot their password.** **Users & roles → Reset password** on their row shows a
  one-time password and signs them out everywhere. Only an owner can reset another owner.

## Related
[01-platform-onboarding](01-platform-onboarding.md) · [03-clients-sites-posts](03-clients-sites-posts.md) ·
[06-employees](06-employees.md) · [10-no-phone-attendance](10-no-phone-attendance.md)
