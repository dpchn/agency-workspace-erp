# 05 · Pay rates and salary rules

**Who / where:** finance · web app → **Pay rates**

▶ Video: [05-pay-rates-salary.webm](videos/05-pay-rates-salary.webm)

## What it's for
What you **pay** each worker. Pay rates are independent of what the client is charged (guide 04), and they can
differ by site, so a guard who works at two sites in a month is paid each site's rate for the days worked there.

## Before you start
* Service types (worker roles) and sites exist.
* Know the wage per role, and any site, client or individual exceptions.

## Steps
1. **Pay rates → Pay rates** lists every rate with its scope, basis and dates.

   ![Pay rates](img/05-pay-rates.png)
2. **New**: **service type**, **wage basis** (MONTHLY, DAILY or HOURLY), **rate**, optional **allowance per duty**,
   **double-duty rate** and **OT hourly rate**. Narrow it with **Only at site / client / employee / branch**, and set
   **effective from**.

   ![New pay rate](img/05-pay-rate-form.png)

   | Scope set | Example | Use |
   |---|---|---|
   | Service type only | Guard: ₹15,000/month | Agency default |
   | + Branch | Guards in Pune: ₹14,500 | Regional minimum wage |
   | + Client | All Prestige sites: ₹620/day | Client-specific wage |
   | + Site | Manyata Block A: ₹700/day | Site allowance / difficult site |
   | + Employee | Ramesh: ₹18,000/month | Individual arrangement |
3. **Salary components**: PF, ESI, professional tax and others, as percent, fixed or slabs, with ceilings and
   "applies only if gross ≤". The seeded values are illustrative; have a payroll professional check them for
   your state.

   ![Salary components](img/05-components.png)
4. **Advances / bonus / deductions**: per employee per month (advance recovery, loan EMI, uniform, fine, bonus,
   allowance). The month's payroll picks them up.

## What the system does for you
* For every duty, the **most specific rate in force on that date at that site** is used.
* A monthly wage becomes a per-duty value with `payDivisor` (calendar days, 26 or 30).
* Rate rise from a date: add a new rate with that **effective from**. Earlier duties keep the old rate.
* **Pay by site, worked example (October, Mahesh Naik G03):** Manyata 1–15 Oct at ₹700/day and Prestige
  16–31 Oct at ₹600/day after a transfer:
  15 × ₹700 + 16 × ₹600 = **₹20,100** gross. The payslip shows one line per site.

  ![Payslip with two sites](img/05-payslip-two-sites.png)

## Common situations
* **Guard works away from home site: which rate?** Policy `payRatePolicy`: SITE_RATE (default), HOME_RATE or
  HIGHER_OF.
* **Minimum wage revised mid-month.** New rate with the new effective date; payroll splits automatically.
* **Double duty paid differently.** Fill **double-duty rate**; otherwise a double duty is paid at the normal duty value.
* **One special guard.** A rate with **Only for employee** beats every other scope.
* **Holiday work.** Paid at `holidayPayMultiplier` (Masters → Holidays).

## Related
[04-contracts-rates](04-contracts-rates.md) · [07-deployments-transfers](07-deployments-transfers.md) ·
[14-payroll](14-payroll.md)
