# 03 · Clients, sites, posts and shift requirements

**Who / where:** owner or operations · web app → **Clients**, **Sites & posts**

▶ Video: [03-clients-sites-posts.webm](videos/03-clients-sites-posts.webm)

## What it's for
Describing who you serve (**client**), where (**site**), which positions must be covered (**posts**) and how many
people each post needs on which shift (**shift requirements**). The roster is built from these.

## Before you start
* Masters are set up (guide 02): branch, service types (worker roles), shift templates.
* For the geofence, get the site's latitude/longitude (Google Maps → right-click → copy coordinates).

## Steps
1. **Clients → New**: code, **legal name**, GSTIN, **state code** (place of supply), billing address, contact,
   **payment terms (days)**, optional credit limit. **Save**.

   ![New client](img/03-client-form.png)
2. **Sites & posts → Sites → New**: client, branch, code, name, address, **latitude / longitude**, **geofence radius**
   (metres) and **geofence policy**:
   * **ENFORCE**: a guard's own-phone check-in outside the radius is rejected (they see how far away they are).
   * **FLAG**: accepted but flagged for review (good where GPS is weak indoors).
   * **OFF**: location ignored.

   ![New site with geofence](img/03-site-form.png)
3. **Posts → New** for each position: site, **service type** (the worker role: guard, supervisor, housekeeping…),
   code, name and duty **instructions**. A post is a position, not a person.

   ![New post](img/03-post-form.png)
4. **Shift requirements → New**: post, **shift template**, **headcount** (e.g. 2 guards at night), **days of week**,
   **effective from / to**.

   ![Shift requirement](img/03-requirement-form.png)
5. Repeat for every post. Example: Main Gate needs 1 guard on D12 and 2 on N12 every day; the supervisor post needs
   1 on D12.

   ![Shift requirements](img/03-requirements.png)

## What the system does for you
* The GST type on invoices follows the client's state vs your branch's state (CGST+SGST or IGST).
* Contract rates and pay rates are keyed by **site + worker type**, so each post is billed and paid correctly.
* Changing a requirement with a new effective date keeps the past intact.
* Supervisors and client users limited to sites only ever see their own sites.

## Common situations
* **Client wants 3 guards instead of 2 from next month.** Add a new requirement with headcount 3 from that date
  and set the old one's *effective to* to the day before.
* **Weekend-only post.** Tick only Sat and Sun in the days of week.
* **Basement post where GPS fails.** Use **FLAG** (or OFF) for that site, or use the site kiosk (guide 10).
* **One client, many locations.** One client, one site per location; each site can have different rates.
* **Same post code at two sites (e.g. GATE).** Fine; posts belong to a site. Pick the site first when you choose
  a post.

## Related
[04-contracts-rates](04-contracts-rates.md) · [05-pay-rates-salary](05-pay-rates-salary.md) ·
[08-roster](08-roster.md) · [10-no-phone-attendance](10-no-phone-attendance.md)
