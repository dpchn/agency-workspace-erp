# 06 · Employees: hire, verify, activate, PIN, app login, exit

**Who / where:** HR (owner for app logins) · web app → **Employees**

▶ Video: [06-employees.webm](videos/06-employees.webm)

## What it's for
Every guard, supervisor and housekeeper, from joining to exit, with the checks that keep unverified people off duty.

## Before you start
* Service types list the documents each role needs (Masters → Service types).

## Steps
1. **Employees → New**: code, name, mobile, branch, **service type** (role), other roles they're qualified for,
   joined on, weekly off, UAN/ESIC/PAN, **bank account and IFSC**. **Save**. The employee starts as **DRAFT**.

   ![New employee](img/06-new-employee.png)
2. Open the employee. **Activate** is refused until the required documents are verified; the banner says what's
   missing (e.g. POLICE_VERIFICATION).

   ![Activation blocked](img/06-activation-blocked.png)
3. **Add document**: type, number, issued and **expiry** date, file reference. Then **Verify** it.
4. **Activate**. The banner turns green: *Ready for deployment*.
5. **Attendance** card:
   * **Mark as no smartphone** for workers who will use the site kiosk or be marked by their supervisor.
   * **Set kiosk PIN** (4–6 digits; easy ones like 1111 or 1234 are refused). Setting a new PIN also unlocks a locked one.
   * **Add photo**: shown on the kiosk and roll-call so the supervisor can match the face.
6. **Create app login** (needs user-management rights, e.g. owner): mobile number (login id) and initial password.
   The guard uses it in the Android app.

   ![Employee ready](img/06-employee-detail.png)
7. Lifecycle: **Suspend** (with reason) → **Reinstate**; **Exit** (last working date + reason).

## What the system does for you
* Only **ACTIVE** employees with verified, unexpired documents can be deployed, rostered or used as replacements.
* **Employees → Expiring documents** lists documents expiring soon.
* **Exit** ends their deployments, cancels future duties and disables their login; the final month is paid in the
  next payroll.
* Bank account numbers are masked on screen.

## Common situations
* **Police verification expired.** The person can't be assigned new duties until a new verified document is added.
* **Guard bought a smartphone.** Click **Has a smartphone now** and create an app login.
* **Worker forgot the kiosk PIN, or it's locked after 5 wrong tries.** **Reset kiosk PIN** (HR or a supervisor).
* **Guard forgot their app password.** Guards normally sign in with their **mobile number + SMS code**, so no
  password is needed. For password users: Settings & users → Users & roles → **Reset password** gives a one-time
  password (needs user-management rights), or they use **Forgot password?** in the app.
* **Guard left / was suspended.** **Exit** (or **Suspend**) turns off their app access at once: open sessions end, no
  sign-in code is sent, and they can't reset a password. **Reinstate** turns it back on.
* **Gunman needs an arms licence.** It's in the Gunman service type's required documents; activation checks it.
* **Plan limit reached.** "Your plan allows N employees": ask your provider to upgrade (guide 01).

## Related
[07-deployments-transfers](07-deployments-transfers.md) · [10-no-phone-attendance](10-no-phone-attendance.md) ·
[18-guard-app](18-guard-app.md)
