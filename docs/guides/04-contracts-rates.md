# 04 · Contracts and billing rates

**Who / where:** finance prepares, owner approves · web app → **Contracts**

▶ Video: [04-contracts-rates.webm](videos/04-contracts-rates.webm)

## What it's for
What you **charge** each client, per site and worker type. Contracts are versioned: a rate change is a new version
from a date, so past invoices are never affected.

## Before you start
* The client, its sites and posts exist (guide 03).
* Two people: one with contract-write rights (finance) and one with approval rights (owner).

## Steps
1. **Contracts → New**: client, contract number, title, start (and end) date, payment terms, auto renew. **Save**,
   then open the contract. **Version 1** is a draft.
2. **Add rate** for each site + service type: **billing model**, **rate**, quantity, shift hours, and options.

   ![Rate line](img/04-rate-form.png)

   | Billing model | Charged as |
   |---|---|
   | **PER_SHIFT** / **PER_DAY** | rate × duties actually worked (holiday duties × holiday multiplier) |
   | **PER_HOUR** | rate × hours worked |
   | **MONTHLY_PER_POST** | rate × posts × days active ÷ days in month; if *attendance-linked*, uncovered duties are deducted |
   | **PER_HEADCOUNT_MONTH** | rate × distinct people deployed in the month |
   | **LUMPSUM_MONTHLY** | fixed amount per month |

   Options per line: **extra shift rate** (client-requested extra shifts), **OT hourly rate** (blank = OT not
   billed), **holiday multiplier**, **minimum billable units** (guarantee), **invoice description**. On the version:
   **GST %** and **penalty per uncovered duty**.
3. Click **Submit**. The version is now waiting for approval.

   ![Draft version](img/04-draft-version.png)
4. The owner opens the contract and clicks **Approve** (or **Reject**). The contract becomes **ACTIVE**.

   ![Approved](img/04-approved.png)
5. **Rate revision:** **Amend (new version)** with the date the new rates start. The rates are copied; edit only
   what changed (pencil icon), **Submit**, and the owner approves.

   ![Two versions](img/04-versions.png)

## What the system does for you
* **Maker-checker:** the person who prepares a version can't approve it.
* Approved versions can't be edited. When a new version is approved, the old one is end-dated the day before.
* Each duty is billed at the version in force **on that duty's date**, so a mid-month change splits the invoice
  (e.g. 15 shifts @ ₹1,100 + 16 shifts @ ₹1,200).
* A guard moving between sites needs nothing here: each duty is billed under its own site's rate.

## Common situations
* **Client pays a fixed monthly amount per post, but guards are paid per day.** Use MONTHLY_PER_POST here and a
  DAILY pay rate (guide 05). The two are independent.
* **Client asks for an extra guard for an event.** Set an **extra shift rate**; mark the shift "requested by client"
  in the roster (guide 08).
* **Guard absent and nobody covered.** With *attendance-linked* on, the monthly bill is reduced for that duty; add a
  **penalty per uncovered duty** if your contract has one.
* **Contract on hold.** **Suspend**, or **Terminate** to end it.
* **Wrong rate on an already-issued invoice.** Don't edit the contract backwards; issue a credit note (guide 15).

## Related
[03-clients-sites-posts](03-clients-sites-posts.md) · [05-pay-rates-salary](05-pay-rates-salary.md) ·
[15-invoicing-collections](15-invoicing-collections.md) · [11-client-signoff](11-client-signoff.md)
