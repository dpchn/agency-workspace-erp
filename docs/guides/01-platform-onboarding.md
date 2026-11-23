# 01 · Onboard a manpower agency (platform console)

**Who / where:** you, the ERP company (platform admin) · web app → **/platform**

▶ Video: [01-platform-onboarding.webm](videos/01-platform-onboarding.webm)

## What it's for
Each security or facility agency is a separate, private workspace (a "tenant"). From the platform console you
create agencies, give them a plan, extend or end trials, and suspend or restore them. You see each agency's size
and activity, but **never** their staff, attendance, salaries or invoices.

## Before you start
* Your platform login is created from `PLATFORM_ADMIN_EMAIL` / `PLATFORM_ADMIN_PASSWORD` in `.env`
  (see [DEPLOYMENT.md](../DEPLOYMENT.md#3-after-the-first-start)). Sign in with it; you land on **/platform**.
* Have the agency's name, state, GSTIN and the owner's name, e-mail and mobile.

## Steps
1. Sign in. The console lists every agency with **plan**, **status**, **staff / sites / office users** against
   the plan limits, **guard app logins**, **duties in the last 30 days** and **last activity**.

   ![Platform console](img/01-console.png)
2. Click **Onboard agency**. Fill in the agency (name, optional workspace id, **state** for GST, GSTIN, office
   phone) and the **owner** (name, e-mail = their login, mobile).
3. Choose the **plan**, **Start as** *Free trial* (with **trial days**) or *Paid / active*. Leave the limits blank
   to use the plan's defaults, or type a limit to override it. **Internal notes** are only for you.

   ![Onboard form](img/01-onboard-form.png)
4. Click **Create agency**. The agency is created with a default branch, worker types (guard, lady guard,
   gunman, supervisor, housekeeping…), shift timings and salary rules. You see the owner's **login** and a
   **one-time temporary password**. Send both to the owner. It is shown only once.

   ![Agency created](img/01-created.png)
5. **Plan & limits** on a row: change the plan (limits reset to the plan's defaults) or override a single limit,
   or tick **Remove all limits**.
6. **+15 days** extends a trial. **Activate** makes the agency paid/active (no trial end).
7. **Suspend** asks for a reason. Every user of that agency is signed out at once and cannot sign in until you
   **Activate** again.

   ![Suspended agency](img/01-suspended.png)

## What the system does for you
* **Plans:** Starter (50 staff, 5 sites, 5 office users), Growth (500 / 50 / 25), Enterprise (no limits).
  Guard, client and site-device logins never count, so every guard can use the app.
* **Limits are enforced:** when an agency hits one, it sees "Your plan allows N … Ask us to upgrade". The
  owner sees usage in **Settings → Plan & usage**.
* **Trials end by themselves:** after the trial end date the agency cannot sign in ("The free trial has
  ended…") until you activate it or add days.
* **Suspension is immediate:** open sessions stop working right away, not at the next sign-in.
* Every change you make is written to the agency's audit log.

## Common situations
* **The owner lost the (temporary) password.** It is stored only as a hash and can't be shown again. Click
  **Reset owner password** on the agency's row: a new one-time password is shown, and the owner is signed out
  everywhere. (An owner who also runs another agency must reset it themselves.)
* **The owner already has a login for another agency.** Use the same e-mail. No new password is made; they
  sign in with their current one and switch agency.
* **An agency outgrew its plan.** **Plan & limits** → choose the bigger plan. It applies immediately.
* **You don't want strangers signing up.** Set `SIGNUP_ENABLED=false`; agencies are then created only here.
* **Payment is late.** **Suspend** with a reason such as "Payment overdue for November"; **Activate** when paid.

## Related
[02-agency-first-setup](02-agency-first-setup.md) · [16-reports-audit](16-reports-audit.md)
