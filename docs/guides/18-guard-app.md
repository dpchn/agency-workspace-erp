# 18 · Guard app: check in, attendance, payslips, leave, incidents

**Who:** guards and housekeeping staff with a smartphone. **Where:** the Android app (or `/guard` in the phone's browser).

▶ Video: [18-guard-app.mp4](videos/18-guard-app.mp4)

## What it's for

A guard's whole working life on one phone: where to report today, check in and out at the site, see their own
attendance and payslips, apply for leave and report incidents. Workers **without** a phone don't need it; see
[10-no-phone-attendance](10-no-phone-attendance.md) and [19-supervisor-app](19-supervisor-app.md).

## Before you start

* The office has created the guard's **app login** (Employees → person → *Create app login* with their mobile number).
  Only numbers added by the agency can sign in; there is no self-registration. See [06-employees](06-employees.md).
* The guard is **rostered** for today ([08-roster](08-roster.md)).
* The APK is installed (your office sends the link: GitHub **Releases → "Android app (latest)"**). The **server
  address** is filled in already, or the office tells you what to type the first time.
* Allow **location** (and **camera** if your agency asks for a check-in selfie) when the app asks.

## Steps

1. **Sign in**: the app opens in **English** (tap **हिन्दी** for Hindi). Type your **mobile number**, tap **Send code**,
   and type the **6-digit SMS code**. No password needed; the app stays signed in (30 days by default).
   Office staff can use **Sign in with e-mail / password** instead.

   ![Sign in](img/18-login.png) ![Mobile number](img/18-otp-code.png)

2. **Duty** tab: one big card for the duty that is on now (site, post, shift time) with **one big button**:
   green **START DUTY**. Tap it at the site: the app takes your GPS position and, if your agency asks, a photo by
   itself after a 3-2-1 countdown. A full-screen **green ✓** means recorded (amber **!** = recorded, a supervisor
   will check; red **✗** = not recorded, with the reason). Coming duties are listed below.

   ![START DUTY](img/18-start-duty.png) ![Recorded](img/18-result.png)
   * **Accepted / Flagged**: recorded. A flag (e.g. weak GPS) only means a supervisor will look at it.
   * **Rejected**: at a strict site you are outside the boundary; move to the gate and try again.
   * **No network**: "Saved on this phone"; it is sent automatically when the network is back.
3. The button turns red: **END DUTY**, with "On duty since hh:mm". Tap it when the shift ends. After that it shows
   **Done for today ✓** and your next duty.

   ![On duty](img/18-on-duty.png)

4. **Attendance** tab: your month, day by day, with site, in/out times, status (present / half day / absent / leave)
   and whether it is approved.
5. **Pay** tab: each locked month with net pay. Tap one for details: **duties per site** (a guard who worked at
   two sites sees two lines, each at that site's rate), deductions (PF, ESI, professional tax), employer
   contributions and the bank account (masked).

   ![Payslip detail](img/18-payslip-detail.png)

6. **Leave** tab: **Apply leave**, choose the type (**CASUAL**, **SICK**, **EARNED**, **UNPAID**), dates and a reason,
   **Submit**. The decision appears in the list. Approved leave frees your rostered duties so someone else can be put
   in ([12-leave](12-leave.md)).
7. **Problem** tab: **Report incident**, choose the site, severity (**Low**, **Med**, **High**, **Critical**), a
   title and what happened, then **Send**. The supervisor and office see it at once ([13-incidents](13-incidents.md)).

## What the system does for you

* **Finds your duty**: a punch is matched to the rostered duty within the check-in window (2 hours by default).
  You don't choose the shift.
* **Checks the place**: GPS distance to the site is stored. At an **ENFORCE** site a punch from outside the
  boundary is refused; at a **FLAG** site it is accepted and flagged.
* **Catches cheating without punishing honest mistakes**: low GPS accuracy, impossible travel speed, the same phone
  used by two guards, wrong phone clock and late uploads are **flagged for review**, never auto-penalised.
* **Works offline**: punches keep their real time and location and are de-duplicated when they sync.
* **Lateness, overtime, half/full day** are worked out from the times, using your agency's policy.
* **Pay follows the site**: each duty is paid at the rate of the site where you worked that day.

## Common situations

* **"Check-in rejected: you are 300 m from the site."** Walk to the gate and try again. If GPS is poor indoors,
  step outside. Still failing? Tell the supervisor; they can mark you with a team photo.
* **Forgot to check in / phone died.** The supervisor or office raises a correction with a reason; another person
  approves it. Your original punches are never changed.
* **No network at the site.** Check in anyway: "Saved on this phone". It syncs later. Very late uploads are flagged
  for a quick review, not rejected.
* **Two shifts in one day (double duty).** Both appear on the Duty tab; check in and out of each.
* **I don't see my payslip.** Payslips appear when the office **locks** that month's payroll.
* **New phone.** Install the app and sign in with your mobile number and SMS code. For a lost phone the office uses
  **Sign out everywhere**.
* **Forgot password.** Not needed for the code sign-in; staff with a password use **Forgot password?** (SMS code) or
  ask the office to **Reset password**.
* **Left the agency.** The app signs you out at once, and no new code is sent to your number.
* **No code arrives.** Wait 30 seconds and tap **Resend code** (at most 3 codes per 15 minutes). Still nothing? Ask the
  office to check your mobile number on your employee record.

## Related

[06-employees](06-employees.md) · [08-roster](08-roster.md) · [09-attendance-approval](09-attendance-approval.md) ·
[10-no-phone-attendance](10-no-phone-attendance.md) · [12-leave](12-leave.md) · [13-incidents](13-incidents.md) ·
[14-payroll](14-payroll.md) · [19-supervisor-app](19-supervisor-app.md)
