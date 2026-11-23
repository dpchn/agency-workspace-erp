# 07 · Deployments and transfers

**Who / where:** operations · web app → **Deployments**

▶ Video: [07-deployments-transfers.webm](videos/07-deployments-transfers.webm)

## What it's for
Recording where each person is posted over time. The roster puts people on shifts from their deployments, and pay
and billing follow the site of each duty.

## Before you start
* The employee is **ACTIVE** (guide 06) and the site/posts exist (guide 03).

## Steps
1. **Deployments** lists current postings: employee, site, post, shift, type, from/to.

   ![Deployments](img/07-deployments.png)
2. **Deploy**: employee (must be ACTIVE), site, optional post and fixed shift, **type**, from (and to), reason.
   * **Permanent**: home posting; one at a time.
   * **Temporary**: a few days elsewhere (end date needed); overrides the home posting for those dates.
   * **Reliever**: floating pool for a site/branch; first choice when filling gaps.

   ![Deploy](img/07-deploy-form.png)
3. **Transfer** (a permanent move): employee, **to site**, to post, shift, **effective date**, reason.

   ![Transfer](img/07-transfer-form.png)
4. Run **Generate roster** for the coming days (guide 08) so the new site is filled.
5. **Show full history** shows every past posting.

   ![History](img/07-history.png)

## What the system does for you
* Transfer ends the old posting the day before the effective date and starts the new one; future planned duties at
  the old site are released and show as open.
* An employee can't have two overlapping permanent postings (use Transfer, not a second Deploy).
* Each duty is paid at its site's pay rate and billed to that site's client. Example: transferred on 16 Dec, the
  guard is paid TechVillage rates for 1–15 Dec and Prestige rates from 16 Dec, with no manual work.
* **Reports → Employee deployment trail** shows where each person worked each month.

## Common situations
* **Guard needed elsewhere for 3 days.** Temporary deployment with an end date; they return automatically.
* **Permanent move.** Transfer with the date.
* **Guard leaves the site but stays with you.** **End** the deployment; future duties are released.
* **Deploy refused.** The employee isn't ACTIVE, or a document is missing/expired, or they already have a
  permanent posting for those dates.

## Related
[06-employees](06-employees.md) · [08-roster](08-roster.md) · [05-pay-rates-salary](05-pay-rates-salary.md) ·
[16-reports-audit](16-reports-audit.md)
