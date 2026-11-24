# 19 · Supervisor app: today's posts, gaps, roll-call, kiosk mode

**Who:** field supervisors (also operations staff and owners on the move). **Where:** the Android app, signed in
with the supervisor's own login.

▶ Video: [19-supervisor-app.mp4](videos/19-supervisor-app.mp4)

## What it's for

Running the sites you look after from your phone: see who should be where right now, fill gaps, replace a no-show,
mark the team present (including people who have **no phone**), and handle incidents. A supervisor limited to some
sites only ever sees and marks those sites.

## Before you start

* The office has created your login with role **Supervisor**, limited to your site(s), and linked to your employee
  record if you also do duties yourself (Settings & users → Users & roles; see [02-agency-first-setup](02-agency-first-setup.md)).
* Workers without a phone have a **kiosk PIN** set (Employees → person → *Set kiosk PIN*; see [06-employees](06-employees.md)).

## Steps

1. **Today** tab: fill rate, uncovered, checked in, late and absent at the top; then every post and shift at your
   sites, gaps first. Arrows change the day.
   * **Find guard** on an open slot shows qualified, available people, best first (relievers, then people off duty
     at the same site, then the branch pool). People on leave, with expired documents or already booked are left out.
   * **⋮ → Replace guard** for a no-show: the original duty becomes NO-SHOW and the replacement is paid and billed.
   * **⋮ → Mark checked in / out** for one person (your location is recorded).

   ![Today](img/19-today.png)

2. **Roll-call** tab: mark the whole team at once.
   1. Pick the **site** (and day) and **Coming in** or **Going out**.
   2. Everyone whose shift is **on now** and who isn't marked yet is **already ticked**; untick anyone who isn't there.
      People already in show how (e.g. *In 07:48 AM · via mobile*). People with a later shift can't be ticked yet.
   3. Tap **Mark all present (N)**: the rear camera takes one team photo by itself (3-2-1). The app sends the photo,
      your GPS and the list.
   4. A full-screen result, then per person (**ACCEPTED**, or why not, e.g. not this duty's time).

   ![Roll-call](img/19-rollcall.png)

3. **Kiosk mode** (tablet icon, top right): hand your phone to a worker without a phone.
   1. The worker taps their **name** (big tile with a green **IN** / orange **OUT** chip), or **Type my employee code**.
   2. Types their **PIN** on the big keypad: it sends itself after the last digit. The front camera then takes their
      photo by itself (3-2-1).
   3. A full-screen result: **green** recorded, **amber** recorded but flagged, **red** not recorded with the reason
      (e.g. **Wrong PIN**). It returns to the list by itself.
   4. The phone's Back button does nothing in kiosk mode. To leave, **hold the lock icon** and type **your own
      password** (if you sign in by SMS code, an SMS code is sent to your number instead). A wrong password keeps
      the phone in kiosk mode.

   ![Kiosk list](img/19-kiosk-list.png) ![PIN](img/19-kiosk-pin.png)
   ![Leave kiosk mode](img/19-kiosk-exit.png)

4. **My duty** tab (if you are also an employee): your own check-in, like the guard app.
5. **Incidents** tab: new incidents at your sites; **Acknowledge**, **Start**, **Resolve**, **Close**, or **Report incident**.
6. **Approve** tab (only for roles with approval rights, e.g. operations): tick flagged or late duties and
   **Approve** / **Reject**.

## What the system does for you

* **Evidence on every proxy punch**: roll-call and kiosk punches store the photo, the device and the location, and
  who marked them. The attendance page shows the source (*Own phone, Kiosk+PIN, Supervisor, Register*).
* **Maker-checker**: duties you marked for someone else (roll-call, single mark) are **never auto-approved**, and you
  can't approve them yourself; a second person must. This stops "ghost guards".
* **Site scope**: you can't mark anyone at a site you don't manage; a duty at another site in a roll-call is rejected.
* **PIN protection**: 5 wrong PINs lock that person's PIN for 15 minutes; a kiosk punch for someone with no duty at
  this site is not counted here (*WRONG_SITE*).
* **Offline**: roll-calls and kiosk punches are kept on the phone with their photos and sent when the network is
  back. Signing out warns you if anything is still unsent.

## Common situations

* **The list doesn't show a shift the office just added.** Pull down to refresh.
* **A worker forgot their PIN / it's locked.** The office (or a supervisor with rights) sets a new PIN; that also
  unlocks it.
* **"Wrong PIN" for the right person.** Check they tapped their own name; after 5 tries wait 15 minutes or reset the PIN.
* **No network at the site.** Do the roll-call / kiosk punches anyway; they show as waiting and sync later.
* **I marked someone by mistake.** Tell the office: they reject that duty or correct it with a reason (another person approves).
* **"Not this duty's time".** A person can only be marked from 2 hours before their shift starts until it ends (and
  marked out until 2 hours after it ends), so nobody is marked present for a later shift by mistake.

## Related

[08-roster](08-roster.md) · [09-attendance-approval](09-attendance-approval.md) · [10-no-phone-attendance](10-no-phone-attendance.md) ·
[13-incidents](13-incidents.md) · [18-guard-app](18-guard-app.md)
