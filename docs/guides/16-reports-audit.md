# 16 · Reports and audit log

**Who / where:** owner, operations, HR, finance, auditor · web app → **Dashboard**, **Reports** (audit log: owner and auditor)

▶ Video: [16-reports-audit.webm](videos/16-reports-audit.webm) (38 s)

## What it's for
See how today is going, which sites make money, where each person worked, and who changed what.

## Steps
1. **Dashboard** (pick any date): fill rate, uncovered shifts, checked in / late / absent, flagged attendance,
   active employees, open incidents, pending corrections and leave, documents expiring in 30 days, receivables and
   overdue, plus quick links.

   ![Dashboard](img/16-dashboard.png)
2. **Reports → Site / client margin** (month): per site, revenue (issued invoices), paid duties, wages, employer cost
   (PF/ESI), contribution and margin %.

   ![Margin](img/16-margin.png)
3. **Employee deployment trail** (month, optional person): each site a person worked at, first and last day, paid
   duties vs billed units, replacements and double duties. It answers "where was this guard, and was it billed?".

   ![Trail](img/16-trail.png)
4. **Audit log**: who did what and when: approvals, locks, invoice issues, PIN changes, disputes, platform changes.

   ![Audit](img/16-audit.png)

## What the system does for you
* Cost comes from the latest payroll run of the month (a draft run is provisional); revenue only from issued invoices.
* Site-scoped users see only their sites.
* Audit entries can't be edited or deleted.

## Common situations
* **Margin shows no revenue.** That month's invoice isn't issued yet.
* **Paid duties differ from billed units.** Normal for replacements, handovers or extras the client didn't ask for; the trail shows where.
* **Who approved this payroll?** Audit log, filter by action.

## Related
[09-attendance-approval](09-attendance-approval.md) · [14-payroll](14-payroll.md) · [15-invoicing-collections](15-invoicing-collections.md)
