#!/usr/bin/env python3
"""
End-to-end business scenario against a running backend (default http://localhost:8080).

Story (October 2026, agency "Shield Force"):
  * Client 1 "Phoenix Mall" (same state -> CGST+SGST), Site A, 2 posts-months (day + night), monthly rate,
    attendance-linked, client-requested extras billed at 900.
  * Client 2 "Green Valley Society" (other state -> IGST), Site B, billed per shift (850, revised to 900 from 16 Oct).
  * Guards are paid 15000/month by default, 16500/month at Site A (site override), 600/day at Site B.
  * G1 works Site A days 1-15, then is TRANSFERRED to Site B; G3 moves the other way.
    -> G1's payslip has two duty lines with two different rates; each client is billed only for its duties.
  * 10 Oct night: G2 absent, reliever G4 REPLACES him (paid to G4, billed normally).
  * 12 Oct night: G2 no-show, nobody covers -> marked ABSENT, client 1 invoice gets a deduction line.
  * 20 Oct: client 1 asks for an EXTRA shift -> billed as additional shift.
  * Maker-checker on contracts, attendance adjustments, payroll and invoices.
  * November: workers without a phone. G1 punches on the Site B gate kiosk with code + PIN + photo; a supervisor
    marks Site A by team roll-call (photo); the office enters a paper register page; proxy-marked duties need a
    second person to approve. The client disputes Site A's attendance, which blocks the invoice until resolved.
  * A second agency cannot see any of it.

Usage: python3 scripts/e2e_scenario.py [base_url]
"""
import json
import sys
import time
import urllib.error
import urllib.request
import uuid
from datetime import date, datetime, timedelta, timezone

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8080"
IST = timezone(timedelta(hours=5, minutes=30))
import os
RUN = os.environ.get("E2E_RUN") or uuid.uuid4().hex[:6]  # E2E_RUN=demo gives stable demo logins
FAILS = []


def call(method, path, body=None, token=None, idem=False, expect=None, raw=False):
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    if idem:
        req.add_header("Idempotency-Key", idem if isinstance(idem, str) else str(uuid.uuid4()))
    try:
        with urllib.request.urlopen(req) as r:
            status, text = r.status, r.read().decode(errors="replace")
    except urllib.error.HTTPError as e:
        status, text = e.code, e.read().decode()
    if expect is not None and status != expect:
        raise AssertionError(f"{method} {path} -> {status} (expected {expect}): {text[:400]}")
    if expect is None and status >= 400:
        raise AssertionError(f"{method} {path} -> {status}: {text[:400]}")
    if raw:
        return status, text
    return json.loads(text) if text and text.strip().startswith(("{", "[")) else text


def check(cond, msg):
    print(("  PASS  " if cond else "  FAIL  ") + msg)
    if not cond:
        FAILS.append(msg)


def step(t):
    print("\n== " + t)


def at(d, hh, mm=0):
    return datetime(d.year, d.month, d.day, hh, mm, tzinfo=IST).astimezone(timezone.utc).isoformat().replace("+00:00", "Z")


# ------------------------------------------------------------------ wait for backend
for _ in range(90):
    try:
        urllib.request.urlopen(BASE + "/actuator/health", timeout=3)
        break
    except Exception:
        try:
            urllib.request.urlopen(BASE + "/v1/public/brand", timeout=3)
            break
        except Exception:
            time.sleep(2)

if os.environ.get("E2E_RUN"):
    try:
        call("POST", "/v1/auth/login", {"login": f"owner.{RUN}@shield.test", "password": "Passw0rd!"})
        print(f"Demo agency '{RUN}' already loaded. Log in as owner.{RUN}@shield.test / Passw0rd!")
        sys.exit(0)
    except AssertionError:
        pass

# ------------------------------------------------------------------ tenants
step("Agency signup (two independent tenants)")
pw = "Passw0rd!"
call("POST", "/v1/tenants", {"agencyName": f"Shield Force {RUN}", "stateCode": "27", "gstin": "27ABCDE1234F1Z5",
                             "ownerName": "Asha Owner", "ownerEmail": f"owner.{RUN}@shield.test", "password": pw}, expect=201)
call("POST", "/v1/tenants", {"agencyName": f"Other Agency {RUN}", "stateCode": "29",
                             "ownerName": "Bob Other", "ownerEmail": f"owner.{RUN}@other.test", "password": pw}, expect=201)
owner = call("POST", "/v1/auth/login", {"login": f"owner.{RUN}@shield.test", "password": pw})["accessToken"]
other = call("POST", "/v1/auth/login", {"login": f"owner.{RUN}@other.test", "password": pw})["accessToken"]
me = call("GET", "/v1/me", token=owner)
check(me["role"] == "OWNER" and "PAYROLL_APPROVE" in me["permissions"], "owner has full permissions")

# team for maker-checker
fin = call("POST", "/v1/users", {"fullName": "Farah Finance", "email": f"fin.{RUN}@shield.test", "role": "FINANCE", "password": pw}, owner)
ops = call("POST", "/v1/users", {"fullName": "Omar Ops", "email": f"ops.{RUN}@shield.test", "role": "OPERATIONS", "password": pw}, owner)
fin_t = call("POST", "/v1/auth/login", {"login": f"fin.{RUN}@shield.test", "password": pw})["accessToken"]
ops_t = call("POST", "/v1/auth/login", {"login": f"ops.{RUN}@shield.test", "password": pw})["accessToken"]
call("GET", "/v1/employees", token=fin_t)
code, _ = call("POST", "/v1/branches", {"code": "X", "name": "X"}, fin_t, expect=403, raw=True)
check(code == 403, "finance cannot create branches (RBAC)")

# ------------------------------------------------------------------ masters
step("Master data: branch, service types, shifts, clients, sites, posts")
branch = call("GET", "/v1/branches", token=owner)["items"][0]
st = {s["code"]: s for s in call("GET", "/v1/service-types", token=owner)["items"]}
tpl = {t["code"]: t for t in call("GET", "/v1/shift-templates", token=owner)["items"]}
check("GUARD" in st and "D12" in tpl, "defaults seeded at signup")
c1 = call("POST", "/v1/clients", {"code": "PHX", "legalName": "Phoenix Mall Pvt Ltd", "gstin": "27AAACP1234Q1Z2",
                                  "billingAddress": "LBS Marg, Mumbai", "paymentTermsDays": 30}, owner)
c2 = call("POST", "/v1/clients", {"code": "GVS", "legalName": "Green Valley CHS", "stateCode": "29", "billingAddress": "Bengaluru"}, owner)
siteA = call("POST", "/v1/sites", {"clientId": c1["id"], "branchId": branch["id"], "code": "PHX-1", "name": "Phoenix Mall Kurla",
                                   "latitude": 19.0863, "longitude": 72.8888, "geofenceRadiusM": 150, "geofencePolicy": "ENFORCE"}, owner)
siteB = call("POST", "/v1/sites", {"clientId": c2["id"], "branchId": branch["id"], "code": "GVS-1", "name": "Green Valley Tower",
                                   "latitude": 12.9716, "longitude": 77.5946, "geofencePolicy": "FLAG"}, owner)
postA = call("POST", "/v1/posts", {"siteId": siteA["id"], "serviceTypeId": st["GUARD"]["id"], "code": "GATE", "name": "Main Gate"}, owner)
postB = call("POST", "/v1/posts", {"siteId": siteB["id"], "serviceTypeId": st["GUARD"]["id"], "code": "MAIN", "name": "Tower Lobby"}, owner)
for post, t in [(postA, "D12"), (postA, "N12"), (postB, "D12")]:
    call("POST", "/v1/post-requirements", {"postId": post["id"], "shiftTemplateId": tpl[t]["id"], "headcount": 1,
                                           "effectiveFrom": "2026-01-01"}, owner)

# ------------------------------------------------------------------ contracts (what we charge)
step("Contracts with versioned rates and maker-checker approval")
k1 = call("POST", "/v1/contracts", {"clientId": c1["id"], "contractNo": f"PHX-{RUN}", "startDate": "2026-01-01"}, fin_t)
v1 = call("GET", f"/v1/contracts/{k1['id']}/versions", token=fin_t)[0]["version"]
call("POST", f"/v1/contracts/versions/{v1['id']}/rates", {"siteId": siteA["id"], "serviceTypeId": st["GUARD"]["id"],
     "billingModel": "MONTHLY_PER_POST", "rate": 22000, "quantity": 2, "shiftHours": 12, "attendanceLinked": True,
     "extraShiftRate": 900, "description": "Security Guard 12h"}, fin_t)
call("POST", f"/v1/contracts/versions/{v1['id']}/submit", {}, fin_t)
code, body = call("POST", f"/v1/contracts/versions/{v1['id']}/decision", {"approve": True}, fin_t, idem=True, expect=403, raw=True)
check(code == 403, "finance (no CONTRACT_APPROVE) cannot approve")
call("POST", f"/v1/contracts/versions/{v1['id']}/decision", {"approve": True}, owner, idem=True)

k2 = call("POST", "/v1/contracts", {"clientId": c2["id"], "contractNo": f"GVS-{RUN}", "startDate": "2026-01-01"}, fin_t)
v2 = call("GET", f"/v1/contracts/{k2['id']}/versions", token=fin_t)[0]["version"]
call("POST", f"/v1/contracts/versions/{v2['id']}/rates", {"siteId": siteB["id"], "serviceTypeId": st["GUARD"]["id"],
     "billingModel": "PER_SHIFT", "rate": 850, "shiftHours": 12}, fin_t)
call("POST", f"/v1/contracts/versions/{v2['id']}/submit", {}, fin_t)
call("POST", f"/v1/contracts/versions/{v2['id']}/decision", {"approve": True}, owner, idem=True)
# mid-month rate revision for client 2
v2b = call("POST", f"/v1/contracts/{k2['id']}/versions", {"effectiveFrom": "2026-10-16", "notes": "Annual revision"}, fin_t, expect=201)
rate_b = call("GET", f"/v1/contracts/{k2['id']}/versions", token=fin_t)[1]["rates"][0]
call("PATCH", f"/v1/contracts/rates/{rate_b['id']}", {"rate": 900}, fin_t)
call("POST", f"/v1/contracts/versions/{v2b['id']}/submit", {}, fin_t)
call("POST", f"/v1/contracts/versions/{v2b['id']}/decision", {"approve": True}, owner, idem=True)
vs = call("GET", f"/v1/contracts/{k2['id']}/versions", token=owner)
check(vs[0]["version"]["effectiveTo"] == "2026-10-15", "old version end-dated at amendment")

# ------------------------------------------------------------------ pay rates (what we pay)
step("Pay rates: default, site override, daily site")
call("POST", "/v1/pay-rates", {"serviceTypeId": st["GUARD"]["id"], "wageBasis": "MONTHLY", "rate": 15000, "effectiveFrom": "2026-01-01"}, fin_t)
call("POST", "/v1/pay-rates", {"serviceTypeId": st["GUARD"]["id"], "siteId": siteA["id"], "wageBasis": "MONTHLY", "rate": 16500,
                               "effectiveFrom": "2026-01-01", "notes": "Mall allowance"}, fin_t)
call("POST", "/v1/pay-rates", {"serviceTypeId": st["GUARD"]["id"], "siteId": siteB["id"], "wageBasis": "DAILY", "rate": 600,
                               "effectiveFrom": "2026-01-01"}, fin_t)

# ------------------------------------------------------------------ employees
step("Employees: onboarding, verification gate, activation")
emps = {}
for i in range(1, 5):
    e = call("POST", "/v1/employees", {"employeeCode": f"G{i}", "firstName": f"Guard{i}", "phone": f"9{RUN[:3]}00{i}{i}{i}{i}"[:10],
                                       "branchId": branch["id"], "serviceTypeId": st["GUARD"]["id"], "joinedOn": "2026-01-01",
                                       "bankAccountNo": f"00012345{i}", "bankIfsc": "HDFC0001234"}, owner)
    emps[f"G{i}"] = e
code, body = call("POST", f"/v1/employees/{emps['G1']['id']}/activate", {}, owner, expect=422, raw=True)
check(code == 422 and "POLICE_VERIFICATION" in body, "cannot activate without verified police verification")
for k, e in emps.items():
    d = call("POST", "/v1/employee-documents", {"employeeId": e["id"], "docType": "POLICE_VERIFICATION", "docNumber": "PV-" + k,
                                                "expiresOn": "2028-12-31"}, owner)
    call("POST", f"/v1/employee-documents/{d['id']}/verify", {"status": "VERIFIED"}, owner)
    call("POST", f"/v1/employees/{e['id']}/activate", {}, owner)
check(call("GET", f"/v1/employees/{emps['G1']['id']}", token=owner)["status"] == "ACTIVE", "employee active after verification")

# ------------------------------------------------------------------ deployment & roster
step("Deployments, roster generation, transfer mid-month")
G = {k: v["id"] for k, v in emps.items()}
call("POST", "/v1/deployments", {"employeeId": G["G1"], "siteId": siteA["id"], "postId": postA["id"], "shiftTemplateId": tpl["D12"]["id"],
                                 "deploymentType": "PERMANENT", "startDate": "2026-10-01"}, ops_t, expect=201)
call("POST", "/v1/deployments", {"employeeId": G["G2"], "siteId": siteA["id"], "postId": postA["id"], "shiftTemplateId": tpl["N12"]["id"],
                                 "deploymentType": "PERMANENT", "startDate": "2026-10-01"}, ops_t, expect=201)
call("POST", "/v1/deployments", {"employeeId": G["G3"], "siteId": siteB["id"], "postId": postB["id"],
                                 "deploymentType": "PERMANENT", "startDate": "2026-10-01"}, ops_t, expect=201)
call("POST", "/v1/deployments", {"employeeId": G["G4"], "siteId": siteA["id"], "deploymentType": "RELIEVER", "startDate": "2026-10-01"}, ops_t, expect=201)
code, body = call("POST", "/v1/deployments", {"employeeId": G["G1"], "siteId": siteB["id"], "deploymentType": "PERMANENT",
                                              "startDate": "2026-10-05"}, ops_t, expect=409, raw=True)
check(code == 409, "second overlapping permanent deployment rejected (use transfer)")
gen = call("POST", "/v1/roster/generate", {"from": "2026-10-01", "to": "2026-10-31"}, ops_t)
check(gen["shiftsCreated"] == 93 and gen["openSlots"] == 0, f"roster: 93 shifts, all filled ({gen})")
gen2 = call("POST", "/v1/roster/generate", {"from": "2026-10-01", "to": "2026-10-31"}, ops_t)
check(gen2["shiftsCreated"] == 0 and gen2["assignmentsCreated"] == 0, "roster generation is idempotent")

call("POST", "/v1/deployments/transfer", {"employeeId": G["G1"], "toSiteId": siteB["id"], "toPostId": postB["id"],
                                          "effectiveDate": "2026-10-16", "reason": "Client B requested experienced guard"}, ops_t, expect=201)
call("POST", "/v1/deployments/transfer", {"employeeId": G["G3"], "toSiteId": siteA["id"], "toPostId": postA["id"],
                                          "shiftTemplateId": tpl["D12"]["id"], "effectiveDate": "2026-10-16", "reason": "Swap"}, ops_t, expect=201)
gen3 = call("POST", "/v1/roster/generate", {"from": "2026-10-16", "to": "2026-10-31"}, ops_t)
check(gen3["assignmentsCreated"] == 32 and gen3["openSlots"] == 0, f"after transfer, 32 duties re-rostered ({gen3})")
trail = {(a["employeeName"], v["siteName"]) for v in call("GET", "/v1/roster?from=2026-10-01&to=2026-10-31", token=ops_t)
         for a in v["assignments"] if a["status"] == "PLANNED"}
check(("Guard1", "Phoenix Mall Kurla") in trail and ("Guard1", "Green Valley Tower") in trail, "G1 rostered at both sites in October")

# ------------------------------------------------------------------ exceptions: replacement, no-show, extra shift
step("Replacement, uncovered no-show, client-requested extra shift")
board = call("GET", "/v1/roster?from=2026-10-01&to=2026-10-31", token=ops_t)


def find(day, site, tcode=None, kind="REGULAR"):
    for v in board:
        s = v["shift"]
        if s["dutyDate"] == day and s["siteId"] == site and s["kind"] == kind and (tcode is None or s["shiftTemplateId"] == tpl[tcode]["id"]):
            return v
    raise KeyError(day)


night10 = find("2026-10-10", siteA["id"], "N12")
cands = call("GET", f"/v1/shifts/{night10['shift']['id']}/eligible-employees", token=ops_t)
check(cands and cands[0]["code"] == "G4" and cands[0]["source"] == "RELIEVER", "replacement finder ranks reliever first")
call("POST", f"/v1/assignments/{night10['assignments'][0]['id']}/replace", {"employeeId": G["G4"], "reason": "G2 sick"}, ops_t, expect=201)
extra = call("POST", "/v1/shifts", {"postId": postA["id"], "dutyDate": "2026-10-20", "shiftTemplateId": tpl["D12"]["id"],
                                    "clientRequested": True, "notes": "Mall sale event"}, ops_t, expect=201)
call("POST", "/v1/assignments", {"shiftId": extra["id"], "employeeId": G["G4"], "role": "RELIEF", "reason": "Extra guard for sale"}, ops_t, expect=201)
code, _ = call("POST", "/v1/assignments", {"shiftId": extra["id"], "employeeId": G["G3"]}, ops_t, expect=409, raw=True)
check(code == 409, "double-booking a guard into an overlapping shift is blocked")

# ------------------------------------------------------------------ attendance
step("Attendance: supervisor marks the month (offline batch sync), no-show left unmarked")
board = call("GET", "/v1/roster?from=2026-10-01&to=2026-10-31", token=ops_t)
batch = []
for v in board:
    for a in v["assignments"]:
        if a["status"] != "PLANNED":
            continue
        if v["shift"]["dutyDate"] == "2026-10-12" and v["shift"]["shiftTemplateId"] == tpl["N12"]["id"]:
            continue  # G2 no-show, nobody covers
        site = siteA if v["shift"]["siteId"] == siteA["id"] else siteB
        start = datetime.fromisoformat(a["startAt"].replace("Z", "+00:00"))
        end = datetime.fromisoformat(a["endAt"].replace("Z", "+00:00"))
        late = timedelta(minutes=40) if (a["employeeCode"] == "G1" and v["shift"]["dutyDate"] == "2026-10-03") else timedelta(0)
        for kind, t in (("CHECK_IN", start + late), ("CHECK_OUT", end)):
            batch.append({"clientEventId": str(uuid.uuid4()), "employeeId": a["employeeId"], "assignmentId": a["id"], "kind": kind,
                          "occurredAt": t.isoformat().replace("+00:00", "Z"), "latitude": site["latitude"],
                          "longitude": site["longitude"], "accuracyM": 12, "deviceId": "SUP-TAB-1", "offline": True})
results = []
for i in range(0, len(batch), 150):
    results += call("POST", "/v1/attendance/events/batch", batch[i:i + 150], ops_t)
check(all(r["status"] in ("ACCEPTED", "FLAGGED") for r in results), f"{len(results)} offline events ingested")
replay = call("POST", "/v1/attendance/events/batch", batch[:4], ops_t)
check(all(r["duplicate"] for r in replay), "re-sync of same events is deduplicated")

fin_res = call("POST", "/v1/attendance/finalize", {"cutoff": "2026-11-01T00:00:00Z"}, owner)
check(fin_res["absentMarked"] == 1, f"no-show marked absent by day close ({fin_res})")
recs = call("GET", "/v1/attendance/records?from=2026-10-01&to=2026-10-31", token=owner)
late_rec = [r for r in recs if r["employee_code"] == "G1" and str(r["duty_date"]) == "2026-10-03"][0]
check(late_rec["late_minutes"] == 40, "late arrival recorded (40 min)")

step("Adjustment with maker-checker, then bulk approval")
adj = call("POST", "/v1/attendance/adjustments", {"recordId": late_rec["id"], "checkInAt": at(date(2026, 10, 3), 8, 0),
                                                  "reason": "Gate register shows on time; phone battery dead"}, ops_t)
code, _ = call("POST", f"/v1/attendance/adjustments/{adj['id']}/decision", {"approve": True}, ops_t, idem=True, expect=403, raw=True)
check(code == 403, "requester cannot approve own adjustment")
call("POST", f"/v1/attendance/adjustments/{adj['id']}/decision", {"approve": True, "note": "verified"}, owner, idem=True)
pending = [r["id"] for r in call("GET", "/v1/attendance/records?from=2026-10-01&to=2026-10-31&approvalStatus=PENDING", token=owner)]
ap = call("POST", "/v1/attendance/approvals", {"recordIds": pending, "decision": "APPROVED"}, owner, idem=True)
check(ap["updated"] == len(pending) and not ap["skipped"], f"approved {ap['updated']} records incl. the confirmed absence")
muster = call("GET", "/v1/attendance/muster?month=2026-10", token=owner)
g1 = [r for r in muster["rows"] if r["code"] == "G1"][0]
check(float(g1["units"]) == 31, "G1 muster: 31 duties across two sites")

# ------------------------------------------------------------------ live geofence
step("Live guard check-in with geofence ENFORCE")
call("POST", "/v1/roster/generate", {"from": date.today().isoformat(), "to": date.today().isoformat(), "siteId": siteA["id"]}, ops_t)
call("POST", f"/v1/employees/{G['G3']}/create-login", {"phone": f"8{RUN[:3]}333333"[:10], "password": "guard123"}, owner)
g3_t = call("POST", "/v1/auth/login", {"login": f"8{RUN[:3]}333333"[:10], "password": "guard123"})["accessToken"]
duties = call("GET", "/v1/me/duties", token=g3_t)
now_dt = datetime.now(timezone.utc)
now = now_dt.isoformat().replace("+00:00", "Z")


def ts(v):
    return datetime.fromisoformat(str(v).replace("Z", "+00:00").replace(" ", "T"))


today_duty = [d for d in duties if ts(d["start_at"]) - timedelta(hours=2) <= now_dt <= ts(d["end_at"])]
if today_duty:
    far = call("POST", "/v1/attendance/events", {"clientEventId": str(uuid.uuid4()), "kind": "CHECK_IN", "occurredAt": now,
                                                 "latitude": 19.20, "longitude": 72.95, "accuracyM": 10, "deviceId": "G3-PHONE"}, g3_t, idem=True)
    check(far["status"] == "REJECTED" and "OUTSIDE_GEOFENCE" in far["flags"], "check-in 14 km away rejected at ENFORCE site")
    near = call("POST", "/v1/attendance/events", {"clientEventId": str(uuid.uuid4()), "kind": "CHECK_IN", "occurredAt": now,
                                                  "latitude": 19.0865, "longitude": 72.8890, "accuracyM": 10, "deviceId": "G3-PHONE"}, g3_t, idem=True)
    check(near["status"] in ("ACCEPTED", "FLAGGED") and near.get("recordId"), f"check-in at the gate accepted ({near['status']} {near['flags']})")
else:
    print("  SKIP  G3 has no duty in progress right now (time-of-day dependent)")
code, _ = call("GET", "/v1/employees", token=g3_t, expect=403, raw=True)
check(code == 403, "guard cannot list employees")
check([x["name"] for x in call("GET", "/v1/sites", token=g3_t)["items"]] == ["Phoenix Mall Kurla"], "guard sees only the site he works at")

# ------------------------------------------------------------------ payroll
step("Payroll October: per-duty valuation across sites, maker-checker, lock")
run = call("POST", "/v1/payroll/runs", {"month": "2026-10"}, fin_t, idem=True)
slips = {s["employee_code"]: s for s in call("GET", f"/v1/payroll/runs/{run['id']}/payslips", token=fin_t)}
g1slip = call("GET", f"/v1/payroll/payslips/{slips['G1']['id']}", token=fin_t)
duty_lines = [l for l in g1slip["lines"] if l["component_code"] == "DUTY_WAGE"]
amounts = sorted(float(l["amount"]) for l in duty_lines)
exp_a = round(16500 / 31 * 15, 2)
exp_b = round(600 * 16, 2)
check(len(duty_lines) == 2 and abs(amounts[0] - min(exp_a, exp_b)) < 0.02 and abs(amounts[1] - max(exp_a, exp_b)) < 0.02,
      f"G1 paid {exp_a} @ Site A (15 duties @16500/31) + {exp_b} @ Site B (16 x 600): got {amounts}")
check(float(slips["G4"]["duties"]) == 2, "reliever G4 paid for replacement + extra shift (2 duties)")
check(float(slips["G2"]["duties"]) == 29, "G2 paid 29 duties (1 replaced, 1 no-show)")
pf = [l for l in g1slip["lines"] if l["component_code"] == "PF_EE"]
check(pf and float(pf[0]["amount"]) > 0, "statutory PF deducted")
call("POST", f"/v1/payroll/runs/{run['id']}/review", {}, fin_t, idem=True)
code, _ = call("POST", f"/v1/payroll/runs/{run['id']}/approve", {}, fin_t, idem=True, expect=403, raw=True)
check(code == 403, "preparer cannot approve own payroll")
call("POST", f"/v1/payroll/runs/{run['id']}/approve", {}, owner, idem=True)
locked = call("POST", f"/v1/payroll/runs/{run['id']}/lock", {}, owner, idem=True)
check(locked["status"] == "LOCKED", "payroll locked")
code, _ = call("POST", "/v1/attendance/adjustments", {"recordId": late_rec["id"], "status": "ABSENT", "reason": "test"}, ops_t, expect=422, raw=True)
check(code == 422, "locked period attendance cannot be adjusted")
csv_code, csv = call("GET", f"/v1/payroll/runs/{run['id']}/bank-export", token=owner, raw=True)
check(csv_code == 200 and csv.count("\n") == 5, "bank export has 4 employees")

# ------------------------------------------------------------------ billing
step("Invoices: contract-driven billing, GST, issue, payment")
inv1 = call("POST", "/v1/invoices/generate", {"clientId": c1["id"], "month": "2026-10"}, fin_t, idem=True)["invoice"]
d1 = call("GET", f"/v1/invoices/{inv1['id']}", token=fin_t)
types = {l["line_type"]: l for l in d1["lines"]}
check(float(types["SERVICE"]["amount"]) == 44000.0, "base: 2 post-months x 22000")
check("DEDUCTION" in types and float(types["DEDUCTION"]["quantity"]) == 1, "deduction for 1 uncovered duty (12 Oct night)")
check("EXTRA_SHIFT" in types and float(types["EXTRA_SHIFT"]["amount"]) == 900.0, "client-requested extra shift billed at 900")
inv = d1["invoice"]
check(float(inv["cgst"]) > 0 and float(inv["igst"]) == 0, "intra-state -> CGST+SGST")
inv2 = call("POST", "/v1/invoices/generate", {"clientId": c2["id"], "month": "2026-10"}, fin_t, idem=True)["invoice"]
d2 = call("GET", f"/v1/invoices/{inv2['id']}", token=fin_t)
svc = sorted((float(l["rate"]), float(l["quantity"])) for l in d2["lines"])
check(svc == [(850.0, 15.0), (900.0, 16.0)], f"rate revision splits client 2 billing 15 @850 + 16 @900: {svc}")
check(float(d2["invoice"]["igst"]) > 0, "inter-state -> IGST")
for i in (inv1, inv2):
    call("POST", f"/v1/invoices/{i['id']}/review", {}, fin_t, idem=True)
    call("POST", f"/v1/invoices/{i['id']}/approve", {}, owner, idem=True)
    issued = call("POST", f"/v1/invoices/{i['id']}/issue", {}, owner, idem=True)
    check(issued["invoiceNo"].startswith("INV/"), f"issued {issued['invoiceNo']}")
    pc, html = call("GET", f"/v1/invoices/{i['id']}/print", token=owner, raw=True)
    check(pc == 200 and issued["invoiceNo"] in html, "printable tax invoice renders")
again = call("POST", "/v1/invoices/generate", {"clientId": c2["id"], "month": "2026-10"}, fin_t, idem=True)
check(float(again["invoice"]["total"]) == 0, "re-generation after issue bills nothing twice (supplementary empty)")
call("POST", f"/v1/invoices/{again['invoice']['id']}/cancel", {}, fin_t, idem=True)

key = str(uuid.uuid4())
r1 = call("POST", "/v1/receipts", {"clientId": c1["id"], "amount": 20000, "externalRef": f"UTR{RUN}", "receivedOn": "2026-11-10"}, fin_t, idem=key)
r2 = call("POST", "/v1/receipts", {"clientId": c1["id"], "amount": 20000, "externalRef": f"UTR{RUN}", "receivedOn": "2026-11-10"}, fin_t, idem=key)
check(r1["id"] == r2["id"], "payment posting is idempotent")
check(call("GET", f"/v1/invoices/{inv1['id']}", token=fin_t)["invoice"]["status"] == "PARTIALLY_PAID", "invoice partially paid")

# ------------------------------------------------------------------ reports
step("Reports: site margin, deployment trail, dashboard")
margin = call("GET", "/v1/reports/site-margin?month=2026-10", token=owner)
rows = {r["site_name"]: r for r in margin["rows"]}
check(float(rows["Phoenix Mall Kurla"]["revenue"]) > 0 and float(rows["Phoenix Mall Kurla"]["wages"]) > 0, "site margin has revenue and cost")
for name, r in rows.items():
    print(f"        {name}: revenue {r['revenue']}  wages {r['wages']}  employer {r['employer_cost']}  contribution {r['contribution']}  ({r['margin_pct']}%)")
tr = call("GET", f"/v1/reports/employee-deployment-trail?month=2026-10&employeeId={G['G1']}", token=owner)
check(len(tr) == 2, "G1 deployment trail shows two sites")
dash = call("GET", "/v1/reports/dashboard?date=2026-10-12", token=owner)
check(dash["attendance"]["absent"] == 1, "dashboard shows the 12 Oct absence")

# ------------------------------------------------------------------ workers without a phone
step("Workers without a phone: kiosk PIN, supervisor roll-call, paper register, maker-checker")
JPEG = "/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAAEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQH/wAALCAABAAEBAREA/8QAFAABAAAAAAAAAAAAAAAAAAAACf/EABQQAQAAAAAAAAAAAAAAAAAAAAD/2gAIAQEAAD8AKp//2Q=="
call("PATCH", f"/v1/employees/{G['G1']}", {"hasSmartphone": False}, owner)
check(call("GET", f"/v1/employees/{G['G1']}", token=owner)["hasSmartphone"] is False, "G1 marked as having no smartphone")
gen_nov = call("POST", "/v1/roster/generate", {"from": "2026-11-01", "to": "2026-11-05"}, ops_t)
check(gen_nov["shiftsCreated"] == 15 and gen_nov["openSlots"] == 0, f"November roster: 15 duties ({gen_nov})")
d12 = [v["shift"] for v in call("GET", "/v1/roster?from=2026-11-02&to=2026-11-02", token=ops_t) if v["shift"]["siteId"] == siteB["id"]][0]
check(d12["startAt"] == at(date(2026, 11, 2), 8, 0), f"day shift starts 08:00 site time whatever the server time zone ({d12['startAt']})")

code, _ = call("POST", f"/v1/employees/{G['G1']}/kiosk-pin", {"pin": "1111"}, ops_t, expect=400, raw=True)
check(code == 400, "easy-to-guess PIN refused")
call("POST", f"/v1/employees/{G['G1']}/kiosk-pin", {"pin": "4826"}, ops_t)
call("POST", f"/v1/employees/{G['G3']}/kiosk-pin", {"pin": "7391"}, ops_t)
check(call("GET", f"/v1/employees/{G['G1']}", token=owner)["kioskPinSet"] is True, "kiosk PIN set for G1")

code, _ = call("POST", "/v1/users", {"fullName": "Gate tablet", "email": f"kiosk.{RUN}@shield.test", "role": "SITE_KIOSK",
                                     "password": pw}, owner, expect=400, raw=True)
check(code == 400, "site device must be limited to a site")
call("POST", "/v1/users", {"fullName": "Green Valley gate tablet", "email": f"kiosk.{RUN}@shield.test", "role": "SITE_KIOSK",
                           "password": pw, "siteIds": [siteB["id"]]}, owner)
kiosk_t = call("POST", "/v1/auth/login", {"login": f"kiosk.{RUN}@shield.test", "password": pw})["accessToken"]
code, _ = call("GET", "/v1/employees", token=kiosk_t, expect=403, raw=True)
check(code == 403, "kiosk device cannot read employee records")


def photo(token, kind, site=None, emp=None):
    return call("POST", "/v1/attendance/photos", {"kind": kind, "contentType": "image/jpeg", "dataBase64": JPEG,
                                                  "siteId": site, "employeeId": emp}, token)["id"]


def kiosk(code_, pin, kind, when, photo_id=None, expect=None, cid=None):
    body = {"clientEventId": cid or str(uuid.uuid4()), "employeeCode": code_, "pin": pin, "kind": kind, "occurredAt": when,
            "latitude": siteB["latitude"], "longitude": siteB["longitude"], "accuracyM": 8, "deviceId": "GVS-GATE-TAB",
            "photoId": photo_id}
    return call("POST", "/v1/attendance/kiosk/punch", body, kiosk_t, idem=True, expect=expect, raw=expect is not None)


nov2 = date(2026, 11, 2)
code, _ = kiosk("G1", "4826", "CHECK_IN", at(nov2, 8, 2), expect=400)
check(code == 400, "kiosk punch without a photo refused")
p1 = photo(kiosk_t, "SELFIE", siteB["id"], G["G1"])
k_in = kiosk("g1", "4826", "CHECK_IN", at(nov2, 8, 2), p1)
check(k_in.get("recordId") and k_in["status"] in ("ACCEPTED", "FLAGGED"), f"G1 checks in on the gate kiosk with code + PIN ({k_in['status']} {k_in['flags']})")
k_wrong_site = kiosk("G3", "7391", "CHECK_IN", at(nov2, 8, 5), p1)
check(k_wrong_site.get("recordId") is None, f"punch for a guard rostered elsewhere is not counted here ({k_wrong_site['flags']})")
for i in range(5):
    code, body = kiosk("G1", "0000", "CHECK_OUT", at(nov2, 20, 0), p1, expect=400)
code, body = kiosk("G1", "4826", "CHECK_OUT", at(nov2, 20, 0), p1, expect=422)
check(code == 422 and "locked" in body, "PIN locks after 5 wrong attempts, even for the right PIN")
call("POST", f"/v1/employees/{G['G1']}/kiosk-pin", {"pin": "5937"}, ops_t)
out_ev = {"clientEventId": str(uuid.uuid4()), "employeeCode": "G1", "pin": "5937", "kind": "CHECK_OUT", "occurredAt": at(nov2, 20, 1),
          "latitude": siteB["latitude"], "longitude": siteB["longitude"], "deviceId": "GVS-GATE-TAB", "photoId": p1, "offline": True}
sync = call("POST", "/v1/attendance/kiosk/punch/batch", [out_ev], kiosk_t)
resync = call("POST", "/v1/attendance/kiosk/punch/batch", [out_ev], kiosk_t)
check(sync[0]["recordStatus"] == "PRESENT" and resync[0]["duplicate"], "offline kiosk check-out synced once (PIN reset by office)")
rec_k = [r for r in call("GET", "/v1/attendance/records?from=2026-11-02&to=2026-11-02", token=owner) if r["employee_code"] == "G1"][0]
check(rec_k["sources"] == ["KIOSK"], f"duty evidenced by KIOSK ({rec_k['sources']})")

nov3 = date(2026, 11, 3)
sheetA = call("GET", f"/v1/attendance/sheet?siteId={siteA['id']}&date=2026-11-03", token=ops_t)
dayA = [r for r in sheetA if r["employeeCode"] == "G3"][0]
sheetB = call("GET", f"/v1/attendance/sheet?siteId={siteB['id']}&date=2026-11-03", token=ops_t)
rc = {"batchId": str(uuid.uuid4()), "siteId": siteA["id"], "kind": "CHECK_IN", "occurredAt": at(nov3, 8, 5),
      "assignmentIds": [dayA["assignmentId"], sheetB[0]["assignmentId"]], "latitude": siteA["latitude"], "longitude": siteA["longitude"],
      "accuracyM": 15, "deviceId": "OPS-PHONE"}
code, _ = call("POST", "/v1/attendance/roll-call", rc, ops_t, idem=True, expect=400, raw=True)
check(code == 400, "roll-call without a team photo refused")
rc["photoId"] = photo(ops_t, "TEAM", siteA["id"])
res = call("POST", "/v1/attendance/roll-call", rc, ops_t, idem=True)
check(res["marked"] == 1 and res["rejected"] == 1, "roll-call marks the Site A team; a Site B duty in the list is rejected")
again_rc = call("POST", "/v1/attendance/roll-call", rc, ops_t, idem=True)
check(all(r["duplicate"] for r in again_rc["results"] if r["status"] != "REJECTED"), "roll-call replay is deduplicated")
call("POST", "/v1/attendance/roll-call", {**rc, "batchId": str(uuid.uuid4()), "kind": "CHECK_OUT", "occurredAt": at(nov3, 20, 0),
                                          "assignmentIds": [dayA["assignmentId"]]}, ops_t, idem=True)
rec_rc = [r for r in call("GET", f"/v1/attendance/records?from=2026-11-03&to=2026-11-03&siteId={siteA['id']}", token=owner)
          if r["employee_code"] == "G3"][0]
check(rec_rc["status"] == "PRESENT" and rec_rc["approval_status"] == "PENDING" and rec_rc["sources"] == ["SUPERVISOR"],
      "supervisor-marked duty is never auto-approved")
ap_self = call("POST", "/v1/attendance/approvals", {"recordIds": [rec_rc["id"]], "decision": "APPROVED"}, ops_t, idem=True)
check(ap_self["updated"] == 0 and "another person" in ap_self["skipped"][0], "the person who marked it cannot approve it")
check(call("POST", "/v1/attendance/approvals", {"recordIds": [rec_rc["id"]], "decision": "APPROVED"}, owner, idem=True)["updated"] == 1,
      "a second person approves the roll-call duty")

call("POST", "/v1/users", {"fullName": "Sunil Supervisor", "email": f"sup.{RUN}@shield.test", "role": "SUPERVISOR", "password": pw,
                           "siteIds": [siteA["id"]]}, owner)
sup_t = call("POST", "/v1/auth/login", {"login": f"sup.{RUN}@shield.test", "password": pw})["accessToken"]
code, _ = call("POST", "/v1/attendance/roll-call", {**rc, "batchId": str(uuid.uuid4()), "siteId": siteB["id"],
                                                    "assignmentIds": [sheetB[0]["assignmentId"]]}, sup_t, idem=True, expect=404, raw=True)
check(code == 404, "site supervisor cannot mark attendance at another site")

nov4 = call("GET", f"/v1/attendance/sheet?siteId={siteB['id']}&date=2026-11-04", token=ops_t)[0]
reg = {"siteId": siteB["id"], "date": "2026-11-04", "note": "Gate register page 14",
       "entries": [{"assignmentId": nov4["assignmentId"], "status": "PRESENT"}]}
code, _ = call("POST", "/v1/attendance/register-entry", reg, ops_t, idem=True, expect=400, raw=True)
check(code == 400, "register entry needs a photo of the signed register")
reg["photoId"] = photo(ops_t, "REGISTER", siteB["id"])
re_ = call("POST", "/v1/attendance/register-entry", reg, ops_t, idem=True)
own = call("POST", "/v1/attendance/adjustments/decisions", {"ids": re_["adjustmentIds"], "approve": True}, ops_t, idem=True)
check(own["updated"] == 0, "office clerk cannot approve their own register entry")
listed = call("GET", f"/v1/attendance/adjustments?batchId={re_['batchId']}", token=owner)
check(len(listed) == 1 and listed[0]["photoId"] == reg["photoId"] and listed[0]["employeeCode"] == "G1", "reviewer sees who/where/when + register photo")
call("POST", "/v1/attendance/adjustments/decisions", {"ids": re_["adjustmentIds"], "approve": True, "note": "matches register"}, owner, idem=True)
rec_m = [r for r in call("GET", f"/v1/attendance/records?from=2026-11-04&to=2026-11-04&siteId={siteB['id']}", token=owner)][0]
check(rec_m["status"] == "PRESENT" and rec_m["approval_status"] == "APPROVED" and "MANUAL" in rec_m["sources"], "register duty approved as MANUAL")

code, _ = call("GET", f"/v1/attendance/photos/{p1}", token=owner, raw=True)
check(code == 200, "reviewer can open the kiosk photo")
code, _ = call("GET", f"/v1/attendance/photos/{p1}", token=g3_t, expect=404, raw=True)
check(code == 404, "another guard cannot open it")
code, _ = call("GET", f"/v1/attendance/photos/{p1}", token=other, expect=404, raw=True)
check(code == 404, "other agency cannot open it")

step("Client confirms / disputes attendance; a dispute blocks the invoice until resolved")
call("POST", "/v1/users", {"fullName": "Phoenix Facility Manager", "email": f"client.{RUN}@phoenix.test", "role": "CLIENT_ADMIN",
                           "clientId": c1["id"], "password": "Client@123"}, owner)
cl_t = call("POST", "/v1/auth/login", {"login": f"client.{RUN}@phoenix.test", "password": "Client@123"})["accessToken"]
sheet_c = call("GET", f"/v1/portal/attendance?month=2026-11&siteId={siteA['id']}", token=cl_t)
d3 = [d for d in sheet_c["days"] if d["day"] == 3][0]
check(d3["required"] == 2 and float(d3["reported"]) == 1, f"client sees 3 Nov: 2 required, 1 reported ({d3})")
check(all("pay" not in str(k).lower() for p in sheet_c["people"] for k in p), "client sheet has no pay data")
code, _ = call("POST", "/v1/portal/attendance/signoff", {"siteId": siteB["id"], "month": "2026-11", "status": "CONFIRMED"}, cl_t,
               expect=404, raw=True)
check(code == 404, "client cannot sign off another client's site")
call("POST", "/v1/portal/attendance/signoff", {"siteId": siteA["id"], "month": "2026-11", "status": "DISPUTED", "note": "Night guard missing",
                                               "disputes": [{"date": "2026-11-03", "note": "No night guard seen at 2 am"}]}, cl_t)
so = [r for r in call("GET", "/v1/attendance/signoffs?month=2026-11", token=fin_t) if r["siteId"] == siteA["id"]][0]
check(so["status"] == "DISPUTED" and so["disputes"][0]["date"] == "2026-11-03", "agency sees the dispute")
gen_n = call("POST", "/v1/invoices/generate", {"clientId": c1["id"], "month": "2026-11"}, fin_t, idem=True)
check(any("DISPUTED" in w for w in gen_n["warnings"]), f"invoice generation warns about the dispute")
inv_n = gen_n["invoice"]
call("POST", f"/v1/invoices/{inv_n['id']}/review", {}, fin_t, idem=True)
call("POST", f"/v1/invoices/{inv_n['id']}/approve", {}, owner, idem=True)
code, body = call("POST", f"/v1/invoices/{inv_n['id']}/issue", {}, owner, idem=True, expect=422, raw=True)
check(code == 422 and "disputed" in body, "invoice cannot be issued while the dispute is open")
call("POST", f"/v1/attendance/signoffs/{so['id']}/resolve", {"note": "Night post was vacant on 3 Nov; not billed"}, ops_t)
issued_n = call("POST", f"/v1/invoices/{inv_n['id']}/issue", {}, owner, idem=True)
check(issued_n["status"] == "ISSUED", "issued after the dispute was resolved")

# ------------------------------------------------------------------ isolation
step("Tenant isolation")
code, _ = call("GET", f"/v1/employees/{G['G1']}", token=other, expect=404, raw=True)
check(code == 404, "other agency gets 404 for our employee (non-disclosing)")
check(call("GET", "/v1/employees", token=other)["total"] == 0, "other agency sees zero employees")
code, _ = call("GET", f"/v1/invoices/{inv1['id']}", token=other, expect=404, raw=True)
check(code == 404, "other agency cannot read our invoice")

# ------------------------------------------------------------------ platform operator (needs PLATFORM_ADMIN_* on the server)
PA_EMAIL, PA_PW = os.environ.get("E2E_PLATFORM_EMAIL"), os.environ.get("E2E_PLATFORM_PASSWORD")
if PA_EMAIL and PA_PW:
    step("Platform operator: onboard an agency, plans and limits, trial expiry, suspension")
    pa = call("POST", "/v1/auth/login", {"login": PA_EMAIL, "password": PA_PW})["accessToken"]
    code, _ = call("GET", "/v1/platform/tenants", token=owner, expect=404, raw=True)
    check(code == 404, "agency owner cannot see the platform console")
    plans = {p["code"]: p for p in call("GET", "/v1/platform/plans", token=pa)}
    check(plans["STARTER"]["maxSites"] == 5 and plans["ENTERPRISE"].get("maxEmployees") is None, "plan catalogue")
    nt = call("POST", "/v1/platform/tenants", {"agencyName": f"Raksha Services {RUN}", "stateCode": "29", "ownerName": "Ravi Raksha",
                                               "ownerEmail": f"ravi.{RUN}@raksha.test", "plan": "STARTER", "maxSites": 2, "trialDays": 14,
                                               "notes": "Referred by Shield"}, pa, expect=201)
    check(nt["temporaryPassword"] and nt["status"] == "TRIAL" and nt["limits"]["sites"] == 2, "operator onboards an agency with a temporary password")
    rv = call("POST", "/v1/auth/login", {"login": nt["ownerLogin"], "password": nt["temporaryPassword"]})["accessToken"]
    call("POST", "/v1/auth/change-password", {"currentPassword": nt["temporaryPassword"], "newPassword": "Raksha@2026"}, rv)
    rv = call("POST", "/v1/auth/login", {"login": nt["ownerLogin"], "password": "Raksha@2026"})["accessToken"]
    check(call("GET", "/v1/settings/plan", token=rv)["limits"]["sites"] == 2, "owner changed the temporary password and sees the plan")
    check(len(call("GET", "/v1/service-types", token=rv)["items"]) >= 6, "new agency starts with default worker types")
    rb = call("GET", "/v1/branches", token=rv)["items"][0]
    rc = call("POST", "/v1/clients", {"code": "TP", "legalName": "Tech Park LLP"}, rv)
    for i in (1, 2):
        call("POST", "/v1/sites", {"clientId": rc["id"], "branchId": rb["id"], "code": f"TP{i}", "name": f"Tech Park Tower {i}"}, rv)
    code, body = call("POST", "/v1/sites", {"clientId": rc["id"], "branchId": rb["id"], "code": "TP3", "name": "Tower 3"}, rv, expect=422, raw=True)
    check(code == 422 and "plan allows 2 sites" in body, "site limit of the plan is enforced")
    call("PATCH", f"/v1/platform/tenants/{nt['id']}", {"plan": "GROWTH"}, pa)
    call("POST", "/v1/sites", {"clientId": rc["id"], "branchId": rb["id"], "code": "TP3", "name": "Tower 3"}, rv)
    check(True, "after upgrading to GROWTH the third site is allowed")
    pv = call("GET", f"/v1/platform/tenants/{nt['id']}", token=pa)
    check(pv["usage"]["sites"] == 3 and pv["owner"]["email"] == nt["ownerLogin"], "operator sees usage counts and owner")
    check("employees" in pv["usage"] and "items" not in pv, "operator sees counts only, no agency records")
    code, _ = call("GET", "/v1/employees", token=pa, expect=403, raw=True)
    check(code in (403,), "operator cannot read agency employees")
    call("PATCH", f"/v1/platform/tenants/{nt['id']}", {"status": "SUSPENDED", "reason": "Payment overdue"}, pa)
    code, _ = call("GET", "/v1/me", token=rv, expect=401, raw=True)
    check(code == 401, "suspension signs the agency's users out immediately")
    code, body = call("POST", "/v1/auth/login", {"login": nt["ownerLogin"], "password": "Raksha@2026"}, expect=403, raw=True)
    check(code == 403 and "suspended" in body, "suspended agency cannot sign in")
    call("PATCH", f"/v1/platform/tenants/{nt['id']}", {"extendTrialDays": 1}, pa)
    check(call("POST", "/v1/auth/login", {"login": nt["ownerLogin"], "password": "Raksha@2026"})["accessToken"], "restored by extending the trial")
    rs = call("POST", f"/v1/platform/tenants/{nt['id']}/reset-owner-password", {}, pa)
    code, _ = call("POST", "/v1/auth/login", {"login": nt["ownerLogin"], "password": "Raksha@2026"}, expect=401, raw=True)
    rv = call("POST", "/v1/auth/login", {"login": rs["login"], "password": rs["temporaryPassword"]})["accessToken"]
    check(code == 401 and rv, "operator resets a locked-out owner's password (old one stops working)")
    call("POST", "/v1/users", {"fullName": "Raksha Ops", "email": f"ops.{RUN}@raksha.test", "role": "OPERATIONS", "password": "Initial@123"}, rv)
    m = [x for x in call("GET", "/v1/users", token=rv) if x.get("email") == f"ops.{RUN}@raksha.test"][0]
    ur = call("POST", f"/v1/users/{m['membershipId']}/reset-password", {}, rv)
    check(call("POST", "/v1/auth/login", {"login": ur["login"], "password": ur["temporaryPassword"]})["accessToken"], "owner resets a staff member's password")
    call("PATCH", f"/v1/platform/tenants/{nt['id']}", {"status": "ACTIVE"}, pa)
    check(call("GET", f"/v1/platform/tenants/{nt['id']}", token=pa).get("trialEndsAt") is None, "activated: paid agency has no trial end")
else:
    print("  SKIP  platform operator checks (set E2E_PLATFORM_EMAIL / E2E_PLATFORM_PASSWORD)")

print(f"""
Logins created (password in brackets):
  Owner       owner.{RUN}@shield.test  (Passw0rd!)
  Finance     fin.{RUN}@shield.test    (Passw0rd!)
  Operations  ops.{RUN}@shield.test    (Passw0rd!)
  Guard G3    8{RUN[:3]}333333           (guard123)   -> phone view /guard
  Client      client.{RUN}@phoenix.test (Client@123) -> client portal /portal
  Supervisor  sup.{RUN}@shield.test    (Passw0rd!)   -> Site A only
  Gate kiosk  kiosk.{RUN}@shield.test  (Passw0rd!)   -> /kiosk at Site B; G1 punches with code G1 + PIN 5937
""")
print("\n" + ("ALL CHECKS PASSED" if not FAILS else f"{len(FAILS)} CHECK(S) FAILED:\n  - " + "\n  - ".join(FAILS)))
sys.exit(1 if FAILS else 0)
