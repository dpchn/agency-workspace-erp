# 08 · Roster: shifts, gaps, replacements, extra shifts

**Who / where:** operations or supervisor · web app → **Roster** (supervisors also use the app, guide 19)

▶ Video: [08-roster.webm](videos/08-roster.webm)

## What it's for
Who covers which post on which shift, day by day, and handling the daily changes: open slots, absences, handovers,
client events and double duties.

## Before you start
* Shift requirements (guide 03) and deployments (guide 07) exist.

## Steps
1. **Roster** shows posts × days. Green = filled, amber = partly filled, red = open. Use the date, 1–31 day view
   and site filter.

   ![Roster board](img/08-board.png)
2. **Generate roster**: from / to (max 62 days), optionally one site. It creates shifts from the requirements and
   assigns deployed people, skipping weekly offs, approved leave, overlaps and rest-time breaches.

   ![Generate roster](img/08-generate.png)
3. Click a red or amber shift → **Assign guard**. The list is ranked: relievers first, then people off duty at the
   same site, then the branch pool, with warnings such as "would breach rest interval". Give a reason → **Choose**.

   ![Candidates](img/08-candidates.png)
4. Absent guard: click the shift → **Replace** next to their name, give a reason, then **Choose** the cover.
   * Leave **Mid-shift handover at** blank = the original is a no-show.
   * Fill a time (e.g. 02:00) for a handover; each person is paid for their own part, the client is billed one slot.
   * Tick **Double duty** for back-to-back shifts; it's the only way to break the minimum rest gap.

   ![Replace](img/08-replace.png)
5. **Extra shift**: post, date, shift (or custom times), headcount, **Requested by client (billable)**, notes.
   Then assign people to it.
6. **Cancel** an assignment (before check-in) or **Cancel shift** (with a reason).

## What the system does for you
* Generating again only fills gaps; nothing is duplicated.
* **No double-booking:** one person can never hold two overlapping duties (the database refuses it).
* Rest rule: `minRestHours` between duties; only a DOUBLE_DUTY assignment may break it, paid at the double-duty rate.
* People on leave, unqualified for the post, with expired documents, or already booked are not offered.
* A replacement is paid to the cover; the client is billed normally because the post was covered.
* Client-requested extras are billed at the contract's extra-shift rate; extras you add for your own reasons are paid
  but never billed.
* Approved leave, exits and transfers release duties, which then show as open here.

## Common situations
* **Guard called in sick at 7 a.m.** Replace → pick the top reliever. The original becomes NO_SHOW.
* **Night guard leaves at 2 a.m.** Replace with a handover time of 02:00.
* **Nobody available.** Leave it open; at day close it's marked ABSENT and, on attendance-linked contracts, deducted
  from the bill.
* **Client asks for 2 extra guards on Saturday.** Extra shift, headcount 2, requested by client.
* **"Would breach rest interval".** Choose someone else, or tick Double duty if they really work back-to-back.

## Related
[03-clients-sites-posts](03-clients-sites-posts.md) · [07-deployments-transfers](07-deployments-transfers.md) ·
[09-attendance-approval](09-attendance-approval.md) · [12-leave](12-leave.md) · [19-supervisor-app](19-supervisor-app.md)
