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
RUN = uuid.uuid4().hex[:6]
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
            status, text = r.status, r.read().decode()
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
now = datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")
today_duty = [d for d in duties if str(d["duty_date"]) == date.today().isoformat()]
if today_duty:
    far = call("POST", "/v1/attendance/events", {"clientEventId": str(uuid.uuid4()), "kind": "CHECK_IN", "occurredAt": now,
                                                 "latitude": 19.20, "longitude": 72.95, "accuracyM": 10, "deviceId": "G3-PHONE"}, g3_t, idem=True)
    check(far["status"] == "REJECTED" and "OUTSIDE_GEOFENCE" in far["flags"], "check-in 14 km away rejected at ENFORCE site")
    near = call("POST", "/v1/attendance/events", {"clientEventId": str(uuid.uuid4()), "kind": "CHECK_IN", "occurredAt": now,
                                                  "latitude": 19.0865, "longitude": 72.8890, "accuracyM": 10, "deviceId": "G3-PHONE"}, g3_t, idem=True)
    check(near["status"] in ("ACCEPTED", "FLAGGED") and near["recordId"], f"check-in at the gate accepted ({near['status']} {near['flags']})")
else:
    print("  SKIP  no duty today for G3 (time-of-day dependent)")
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

# ------------------------------------------------------------------ isolation
step("Tenant isolation")
code, _ = call("GET", f"/v1/employees/{G['G1']}", token=other, expect=404, raw=True)
check(code == 404, "other agency gets 404 for our employee (non-disclosing)")
check(call("GET", "/v1/employees", token=other)["total"] == 0, "other agency sees zero employees")
code, _ = call("GET", f"/v1/invoices/{inv1['id']}", token=other, expect=404, raw=True)
check(code == 404, "other agency cannot read our invoice")

print("\n" + ("ALL CHECKS PASSED" if not FAILS else f"{len(FAILS)} CHECK(S) FAILED:\n  - " + "\n  - ".join(FAILS)))
sys.exit(1 if FAILS else 0)
