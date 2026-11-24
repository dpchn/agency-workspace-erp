# 11 · Client sign-off of attendance (optional: confirm or dispute)

> **Optional, off by default.** Most sites (empty plots, malls, schools) never log in, so nothing waits for the
> client: the agency approves attendance and issues invoices by itself. Turn this on per agency only if a client asks
> for it: **Settings & users → Business policies → `clientSignoffEnabled`**.

**Who / where:** client admin → **client portal → Attendance** · agency → **Attendance → Client sign-off** and **Invoices**

▶ Video: [11-client-signoff.webm](videos/11-client-signoff.webm) (1 min 18 s)

## What it's for
Before you bill a month, the client sees exactly which guards were on duty each day and either **confirms** it or
**disputes** specific days. Billing disagreements are settled on attendance, before the invoice, not after.

## Before you start
* **Business policies → `clientSignoffEnabled`** is ticked (it is off by default).
* The client has a portal login with role **Client admin** (Settings & users → Add user, linked to the client).
  **Client viewer** can see but not sign off.
* Attendance for the month is recorded and mostly approved (guide 09).

## Steps
1. **Client:** portal → **Attendance**, pick the month and site. The top shows duties required vs reported; the grid
   shows each day (short days in red) and who worked which day (P / H / A only, no pay data).

   ![Client sheet](img/11-client-sheet.png)
2. All good → **Confirm month**. Something wrong → **Dispute**: tap the wrong days, say what happened for each, add
   an overall comment → **Send dispute**.

   ![Dispute](img/11-dispute.png)
3. **Agency, finance:** **Invoices → Generate invoice** for that client and month. The draft shows a warning box:
   *"Client DISPUTED attendance for …; resolve before issuing"* (or *"has not confirmed"*).

   ![Invoice warning](img/11-invoice-warning.png)
4. Approving is allowed, but **Issue** is refused while the dispute is open.

   ![Issue blocked](img/11-issue-blocked.png)
5. **Attendance → Client sign-off** (pick the month): every site with status **NOT SUBMITTED**, **CONFIRMED** or
   **DISPUTED**, the client's note and disputed days. Check the days and correct attendance if the client is right
   (Adjust / Register entry, guide 09/10), then **Resolve** with a note of what was done.

   ![Agency sign-off list](img/11-agency-signoff.png)
6. Now **Issue** works and the invoice gets its number. The client sees the resolution in the portal and can confirm again.

   ![Issued](img/11-issued.png)

## What the system does for you
* Records who confirmed or disputed, when, and how many duties there were at that moment.
* Sends an "attendance disputed" notification to the agency.
* Blocks issuing that month's invoice for that client while a dispute is open (`blockInvoiceOnDispute`, on by default).
* A new sign-off by the client replaces the previous one and clears the resolution.

## Common situations
* **Client never signs off.** Invoice generation warns "has not confirmed", but you can still issue.
* **Dispute is wrong.** Resolve it with your explanation (for example "gate register and photos show the guard present"); you can issue then.
* **Client is right.** Reject or adjust the duty first so it isn't billed, then resolve. If already invoiced, use a credit note (guide 15).
* **Client has several sites.** Each site is signed off separately; pick the site in the portal.

## Related
[09-attendance-approval](09-attendance-approval.md) · [15-invoicing-collections](15-invoicing-collections.md) ·
[17-client-portal](17-client-portal.md)
