# 15 · Invoicing and collections

**Who / where:** finance prepares, owner (or anyone with billing approval) approves · web app → **Invoices**,
**Receipts & aging**

▶ Video: [15-invoicing-collections.webm](videos/15-invoicing-collections.webm) (1 min 23 s)

## What it's for
Bills each client from its **contract** and the **approved duties at its sites**, at that site's rates, with GST,
then tracks payments, TDS and what is still owed.

## Before you start
* An approved contract with rates per site and worker type (guide 04).
* The month's attendance is approved (guide 09) and, ideally, the client has confirmed it (guide 11).
* Generate after the month ends. Days not yet worked or still pending are not billed, and on attendance-linked
  monthly rates they show as uncovered.

## Steps
1. **Invoices → Generate invoice** → client and month. A **DRAFT** opens with:
   * one line per site × worker type × rate period (a mid-month rate revision gives two lines),
   * **extra shifts** the client asked for, at the extra-shift rate,
   * **Less: uncovered duties** on attendance-linked monthly rates (plus any absence penalty),
   * GST: **CGST + SGST** when the client is in your state, **IGST** otherwise,
   * a **Check before issuing** box: pending attendance not billed, client sign-off missing or disputed.

   ![Draft](img/15-draft.png)
2. Optional **Add manual line** (negative rate = discount). **Mark reviewed** → a **different** person **Approve** →
   **Issue**. Issuing gives the legal number for the financial year (`INV/2026-27/00004`) and locks the billed duties.

   ![Issued](img/15-issued.png)
3. **Print / PDF** opens the printable tax invoice (use the browser's Save as PDF).

   ![Printable tax invoice](img/15-print.png)
4. Wrong amount on an issued invoice? Never edit it. **Credit note** → taxable amount + reason. It is a draft that
   follows the same review → approve → issue and reduces the invoice balance.

   ![Credit note](img/15-credit-note.png)
5. Late-approved duties after an invoice was issued: **Generate** again → a **Supplementary invoice** with only the
   newly approved per-duty items (monthly base charges are never billed twice).

   ![Supplementary](img/15-supplementary.png)
6. **Receipts & aging → Record receipt**: client, amount, **TDS deducted by client**, date, mode, **UTR / cheque no**.

   ![Receipt](img/15-receipt.png)
7. **Receivables aging**: what each client owes by age (not due, 1–30, 31–60, 61–90, 90+). **Client statement**:
   invoices, credit notes and receipts with a running balance.

   ![Aging](img/15-aging.png)
   ![Statement](img/15-statement.png)

## What the system does for you
* Invoice numbers have no gaps and restart each financial year (`invoicePrefix`, `creditNotePrefix`).
* A receipt is applied to the **oldest due invoice first**; TDS counts towards settling it.
* The same UTR can't be posted twice (safe to retry).
* An open client dispute blocks **Issue** (guide 11).
* Overdue invoices are marked automatically every night.

## Common situations
* **"Nothing to bill".** No approved contract version for the period, or no approved billable duties yet.
* **Client paid less because of TDS.** Enter the amount received and the TDS separately.
* **Approved invoice exists for this period.** Issue it or reject it before generating again.
* **Client disputes an issued invoice.** **Mark disputed** with their reason, then settle with a credit note and **Resolve dispute**.

## Related
[04-contracts-rates](04-contracts-rates.md) · [09-attendance-approval](09-attendance-approval.md) ·
[11-client-signoff](11-client-signoff.md) · [16-reports-audit](16-reports-audit.md) · [17-client-portal](17-client-portal.md)
