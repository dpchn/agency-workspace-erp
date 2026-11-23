# 14 · Payroll

**Who / where:** finance prepares, owner (or anyone with payroll approval) approves · web app → **Payroll**,
**Pay rates → Advances / bonus / deductions**

▶ Video: [14-payroll.webm](videos/14-payroll.webm) (1 min 9 s)

## What it's for
Pays every person for the duties they actually did, **each duty at the pay rate of the site where it was worked**.
A guard who spends 15 days at site A (₹700/day) and 16 days at site B (₹600/day) gets 15 × 700 + 16 × 600, with no
manual calculation, however often people move.

## Before you start
* Pay rates (guide 05) and salary components (PF, ESI, PT…) are set.
* The month's attendance is approved (guide 09). Anything still pending is listed as an exception and paid later.
* One-off items for the month are entered: **Pay rates → Advances / bonus / deductions → New** (advance recovery,
  loan EMI, uniform, fine, bonus, allowance…).

  ![Adjustment](img/14-adjustment.png)

## Steps
1. **Payroll → Run payroll** → month (YYYY-MM), optionally a branch. A **DRAFT** run is created.

   ![November draft](img/14-november-draft.png)
2. Check the **Exceptions** box and the **Payroll register** (duties, OT hours, leave, gross, deductions, net,
   employer cost per person). Click a person for the **payslip**: duty wages are split **per site and rate**.

   ![Register](img/14-register.png)
   ![Payslip with two sites](img/14-payslip-two-sites.png)
3. Need changes? Fix attendance or adjustments, then **Recalculate**. It creates a new version (v2…); the old one is kept.
4. **Mark reviewed** (preparer) → **Approve** (a **different** person; the preparer is refused with *"The person who
   prepared payroll cannot approve it"*). **Reject** sends it back to draft.

   ![Maker-checker](img/14-maker-checker.png)
   ![Approved](img/14-approved.png)
5. **Lock**: the month's attendance becomes unchangeable (the adjustments it used show "Consumed: Yes").
6. **Bank file (CSV)** for the salary transfer → after paying, **Mark paid**.

## What the system does for you
* Values each approved duty with the most specific pay rate in force that day (employee › site › client › service
  type › branch), plus allowance, overtime, holiday and double-duty rates.
* Policy `payRatePolicy` decides what happens away from the home site (site rate, home rate or higher of the two);
  `payDivisor` turns monthly wages into per-day value (calendar days, 26 or 30).
* Applies PF / ESI / PT and other components from **Salary components**, by state and ceilings.
* Adds paid-leave days, and **arrears**: duties of an already-locked month approved late are paid in the next run.
* Allocates each person's cost to the sites they worked at, for the site-margin report (guide 16).

## Common situations
* **"N attendance records still pending".** Approve them and Recalculate, or go ahead; they will be paid next month as arrears.
* **Wrong rate used.** Fix the pay rate (with the right effective date), then Recalculate. Never edit a locked month.
* **Advance given mid-month.** Add an ADVANCE_RECOVERY adjustment for the month(s) you'll recover it in.
* **A month is locked and a mistake is found.** Correct attendance in the next month (arrears) or add an adjustment.

## Related
[05-pay-rates-salary](05-pay-rates-salary.md) · [07-deployments-transfers](07-deployments-transfers.md) ·
[09-attendance-approval](09-attendance-approval.md) · [12-leave](12-leave.md) · [16-reports-audit](16-reports-audit.md)
