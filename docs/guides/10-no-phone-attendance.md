# 10 · Attendance for workers without a phone

**Who / where:** gate tablet (**Site kiosk**), supervisors and operations (**Attendance → Team roll-call / Register
entry**), a second approver (**Corrections**) · also in the Android app (guide 19)

▶ Video: [10-no-phone-attendance.webm](videos/10-no-phone-attendance.webm) (1 min 45 s)

## What it's for
Many guards and housekeeping staff have no smartphone. They still need trustworthy attendance. There are three
ways, from most to least trusted:

| Way | Evidence | Approves itself? |
|---|---|---|
| **Site kiosk**: tablet at the gate, or a supervisor's phone in kiosk mode | Employee code + **PIN**, **photo**, device location | Yes, if clean |
| **Team roll-call**: supervisor marks the team at once | **Team photo**, supervisor's location, who marked | No: someone else approves |
| **Register entry**: office types a signed paper register | **Photo of the page**, who entered it | No: someone else approves |

## Before you start
* **Employees → (person)**: switch **Has smartphone** off, **Set kiosk PIN** (4–6 digits; easy ones like 1111 or
  1234 are refused) and add a profile photo (guide 06).
* For a gate tablet: **Settings & users → Add user**, role **Site device (kiosk)**, limited to that site. Sign in on
  the tablet at `/kiosk` (or in the Android app). It can't see anything else.

## Steps
**A. Gate kiosk (tablet)**
1. The tablet lists who is on duty at its site now. The worker taps their name (or **Enter my code** if they're not
   listed).

   ![Kiosk list](img/10-kiosk-list.png)
2. They enter their PIN on the keypad and the tablet takes their photo. A wrong PIN records nothing; **5 wrong tries
   lock the PIN for 15 minutes** (the office unlocks it by setting a new PIN).

   ![Wrong PIN](img/10-kiosk-wrong-pin.png)
3. Correct PIN → green **Recorded** with the time. The duty shows **Kiosk+PIN** under "Marked by".

   ![Recorded](img/10-kiosk-recorded.png)
4. Someone punching at a site where they have **no duty right now** gets amber "supervisor will review". The punch is
   kept as evidence but **not counted** there (flag WRONG_SITE). It stops a worker punching at the wrong gate,
   or a friend punching for them.

   ![Wrong site](img/10-kiosk-wrong-site.png)

**B. Team roll-call (supervisor / operations)**
5. **Attendance → Team roll-call** → site and date. Tick who is present, **Team photo** (required) → **Mark in**
   (later **Mark out**). Results show per person. A duty at another site is rejected.

   ![Roll-call](img/10-rollcall.png)
   ![Roll-call result](img/10-rollcall-result.png)

**C. Paper register**
6. **Attendance → Register entry** → site and date. Set each line to P / H / A (times optional; blank = the planned
   shift), attach **Photo of register page** and a note → **Submit for approval**.

   ![Register entry](img/10-register-entry.png)
7. **Another person** opens **Corrections**: each line shows before → proposed, the reason, who entered it and the
   register photo (click to enlarge). Tick the page → **Approve** (or **Reject**).

   ![Corrections](img/10-corrections.png)
   ![Register photo](img/10-register-photo.png)

## What the system does for you
* Every duty records **how** it was marked and **who** marked it; photos are stored with the punch.
* Whoever marks for someone else can never approve it (maker-checker), so one person can't invent attendance.
* A site-limited supervisor or kiosk can only mark people at its own sites.
* Kiosk and app punches work offline and sync later; replays are never counted twice.
* Policies (Settings → Business policies): `kioskPhotoRequired`, `rollCallPhotoRequired`, `pinMaxAttempts`,
  `pinLockMinutes`, `autoApproveSources`.

## Common situations
* **"No PIN set — ask office".** Set a PIN on the employee page.
* **"PIN locked".** Wait 15 minutes, or the office sets a new PIN (it unlocks immediately).
* **Tablet has no camera / camera blocked.** It warns; if your agency requires a photo, the punch is refused. Use roll-call instead.
* **Supervisor marked the whole team and wants to approve it.** Not allowed; operations or the owner approves.
* **Register shows someone the roster doesn't.** Register entry only lists rostered duties; first add or assign the duty on the roster (guide 08).

## Related
[06-employees](06-employees.md) · [08-roster](08-roster.md) · [09-attendance-approval](09-attendance-approval.md) ·
[19-supervisor-app](19-supervisor-app.md)
