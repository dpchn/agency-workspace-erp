# 12 · Leave

**Who / where:** guard (app or web `/guard` → **Leave**) · office (**Leaves → Record leave**) · approvers (HR,
operations, owner) → **Leaves**

▶ Video: [12-leave.webm](videos/12-leave.webm) (54 s)

## What it's for
Guards ask for leave from their phone, or the office records it for those without one. Approving it frees their
rostered duties so somebody else can cover them, and pays (or doesn't pay) the days correctly.

## Before you start
* The guard has a login (guide 06) or the office records the leave for them.
* Which leave types are paid is a policy: `paidLeaveTypes` (default casual, sick, earned; **unpaid** is never paid).

## Steps
1. **Guard:** **Leave** tab → type, from, to, reason → **Apply**. It shows as **PENDING**.

   ![Apply](img/12-apply.png)
   ![Pending](img/12-pending.png)
2. **Office:** **Leaves** lists requests (filter by status). For someone without a phone use **Record leave**.

   ![Leaves](img/12-leaves-list.png)
3. **Approve** (or **Reject**). On approval the message says how many duties were released.
4. **Roster:** those days now show **needs 1** (open). Click one → **Assign guard** → the ranked list (relievers
   first) → **Choose**.

   ![Roster open](img/12-roster-open.png)
   ![Assign cover](img/12-assign.png)

## What the system does for you
* Approval cancels the person's **planned** duties in the leave dates ("On approved leave") and marks those shifts open.
* Roster generation skips people on approved leave; they are not offered as replacements.
* Payroll pays approved paid-leave days at the person's home rate (shown as "Paid leave" on the payslip); unpaid leave is not paid.
* The cover is paid for the duty and the client is billed normally, because the post was covered.

## Common situations
* **Guard applied for the wrong dates.** Reject it and ask them to apply again.
* **Leave approved after the duty already started.** Only planned duties are released; fix the started one in attendance.
* **Emergency, no time to apply.** Replace the guard on the roster (guide 08); record the leave afterwards.
* **Which days count as paid?** Only approved leave of a paid type, within the payroll month.

## Related
[08-roster](08-roster.md) · [14-payroll](14-payroll.md) · [18-guard-app](18-guard-app.md)
