# API reference

Interactive, always-current documentation: **`/swagger-ui`** on the backend (OpenAPI JSON at `/v3/api-docs`).
This page explains the conventions and lists every endpoint. The web app and the Android app use exactly this API,
so anything they do can be automated.

## Conventions

**Base URL:** `https://<your-domain>` (through the web server) or `http://<server>:8080` (backend directly).
All paths start with `/v1`.

**Authentication**
```http
POST /v1/auth/login
{"login": "owner@agency.com" | "9876543210", "password": "…", "tenantId": "<optional, for users in several agencies>"}
→ {"accessToken": "eyJ…", "tenantId": "…", "role": "OWNER", "memberships": [...]}

GET /v1/me
Authorization: Bearer eyJ…
```
The agency (tenant) always comes from the token, never from the request. Tokens last `ACCESS_TOKEN_MINUTES`.
`POST /v1/auth/switch-tenant` changes agency, and `POST /v1/auth/logout-all` revokes every session.

**Errors**: always the same shape:
```json
{"code": "VALIDATION_FAILED", "message": "Request validation failed", "correlationId": "…", "fieldErrors": {"gstin": "GSTIN must be 15 characters"}}
```
| Status | Meaning |
|---|---|
| 400 | Bad input (`fieldErrors` says which field) |
| 401 | Not signed in / token expired or revoked |
| 403 | Signed in, but your role lacks the permission |
| 404 | Not found **or** belongs to another agency/scope (deliberately the same answer) |
| 409 | Conflict: duplicate, overlapping assignment, record changed by someone else (`version`) |
| 422 | Not allowed in the current state (e.g. approving an already-locked payroll) |

**Idempotency-Key** header (any unique string, e.g. a UUID) is **required** where marked below: check-ins,
approvals, payroll/invoice actions, payments. Repeating a request with the same key returns the first response
instead of doing it twice; reusing a key with a different body returns 422.

**Lists**: master-data lists take `?page=0&size=50&q=search&<field>=<value>` and return
`{"items": [...], "page": 0, "size": 50, "total": 123}`. Workflow lists (roster, attendance, invoices…) take the
filters shown in Swagger and return arrays.

**Updates**: `PATCH` with only the fields to change. Include `"version"` to guard against overwriting someone
else's change (you get 409 if it moved on).

**Offline attendance sync**: `POST /v1/attendance/events/batch` with up to 200 events, each carrying a
device-generated `clientEventId` (UUID). Events already received come back with `"duplicate": true`, so retries
are always safe.

## Endpoints

### Attendance

| Method | Path | Idempotency-Key |
|---|---|---|
| POST | `/v1/attendance/events` | required |
| POST | `/v1/attendance/events/batch` |  |
| GET | `/v1/attendance/events` |  |
| GET | `/v1/attendance/records` |  |
| POST | `/v1/attendance/approvals` | required |
| POST | `/v1/attendance/adjustments` |  |
| GET | `/v1/attendance/adjustments` |  |
| POST | `/v1/attendance/adjustments/{id}/decision` | required |
| POST | `/v1/attendance/finalize` |  |
| GET | `/v1/attendance/muster` |  |
| GET | `/v1/leaves` |  |
| POST | `/v1/leaves` |  |
| POST | `/v1/leaves/{id}/decision` |  |

### Billing

| Method | Path | Idempotency-Key |
|---|---|---|
| POST | `/v1/invoices/generate` | required |
| GET | `/v1/invoices` |  |
| GET | `/v1/invoices/{id}` |  |
| POST | `/v1/invoices/{id}/{review,approve,reject,issue,cancel,dispute,resolve-dispute}` | required |
| POST | `/v1/invoices/{id}/lines` |  |
| DELETE | `/v1/invoices/{id}/lines/{lineId}` |  |
| POST | `/v1/invoices/{id}/credit-notes` | required |
| GET | `/v1/invoices/{id}/print` |  |
| POST | `/v1/receipts` | required |
| GET | `/v1/receipts` |  |
| GET | `/v1/reports/aging` |  |
| GET | `/v1/clients/{clientId}/statement` |  |

### Clients, sites, posts

| Method | Path | Idempotency-Key |
|---|---|---|
| GET | `/v1/clients` |  |
| GET | `/v1/clients/{id}` |  |
| POST | `/v1/clients` |  |
| PATCH | `/v1/clients/{id}` |  |
| GET | `/v1/posts` |  |
| GET | `/v1/posts/{id}` |  |
| POST | `/v1/posts` |  |
| PATCH | `/v1/posts/{id}` |  |
| GET | `/v1/post-requirements` |  |
| GET | `/v1/post-requirements/{id}` |  |
| POST | `/v1/post-requirements` |  |
| PATCH | `/v1/post-requirements/{id}` |  |
| GET | `/v1/sites` |  |
| GET | `/v1/sites/{id}` |  |
| POST | `/v1/sites` |  |
| PATCH | `/v1/sites/{id}` |  |

### Contracts and pay rates

| Method | Path | Idempotency-Key |
|---|---|---|
| GET | `/v1/contracts` |  |
| GET | `/v1/contracts/{id}` |  |
| POST | `/v1/contracts` |  |
| PATCH | `/v1/contracts/{id}` |  |
| GET | `/v1/contracts/{id}/versions` |  |
| POST | `/v1/contracts/{id}/versions` |  |
| PATCH | `/v1/contracts/versions/{versionId}` |  |
| POST | `/v1/contracts/versions/{versionId}/rates` |  |
| PATCH | `/v1/contracts/rates/{rateId}` |  |
| DELETE | `/v1/contracts/rates/{rateId}` |  |
| POST | `/v1/contracts/versions/{versionId}/submit` |  |
| POST | `/v1/contracts/versions/{versionId}/decision` | required |
| POST | `/v1/contracts/{id}/status` |  |
| GET | `/v1/pay-rates` |  |
| GET | `/v1/pay-rates/{id}` |  |
| POST | `/v1/pay-rates` |  |
| PATCH | `/v1/pay-rates/{id}` |  |

### HR (employees, documents)

| Method | Path | Idempotency-Key |
|---|---|---|
| GET | `/v1/employees` |  |
| GET | `/v1/employees/{id}` |  |
| POST | `/v1/employees` |  |
| PATCH | `/v1/employees/{id}` |  |
| POST | `/v1/employees/{id}/submit` |  |
| POST | `/v1/employees/{id}/activate` |  |
| POST | `/v1/employees/{id}/suspend` |  |
| POST | `/v1/employees/{id}/reinstate` |  |
| POST | `/v1/employees/{id}/exit` |  |
| POST | `/v1/employees/{id}/create-login` |  |
| GET | `/v1/employees/{id}/readiness` |  |
| GET | `/v1/employee-documents` |  |
| GET | `/v1/employee-documents/{id}` |  |
| POST | `/v1/employee-documents` |  |
| PATCH | `/v1/employee-documents/{id}` |  |
| POST | `/v1/employee-documents/{id}/verify` |  |
| GET | `/v1/employee-documents/expiring` |  |

### Operations (incidents)

| Method | Path | Idempotency-Key |
|---|---|---|
| GET | `/v1/incidents` |  |
| POST | `/v1/incidents` |  |
| POST | `/v1/incidents/{id}/{acknowledge,start,resolve,close,reopen,assign,visibility}` | required |

### Masters (branches, service types, shifts, holidays)

| Method | Path | Idempotency-Key |
|---|---|---|
| GET | `/v1/branches` |  |
| GET | `/v1/branches/{id}` |  |
| POST | `/v1/branches` |  |
| PATCH | `/v1/branches/{id}` |  |
| GET | `/v1/holidays` |  |
| GET | `/v1/holidays/{id}` |  |
| POST | `/v1/holidays` |  |
| PATCH | `/v1/holidays/{id}` |  |
| GET | `/v1/service-types` |  |
| GET | `/v1/service-types/{id}` |  |
| POST | `/v1/service-types` |  |
| PATCH | `/v1/service-types/{id}` |  |
| GET | `/v1/shift-templates` |  |
| GET | `/v1/shift-templates/{id}` |  |
| POST | `/v1/shift-templates` |  |
| PATCH | `/v1/shift-templates/{id}` |  |

### Payroll

| Method | Path | Idempotency-Key |
|---|---|---|
| GET | `/v1/employee-adjustments` |  |
| GET | `/v1/employee-adjustments/{id}` |  |
| POST | `/v1/employee-adjustments` |  |
| PATCH | `/v1/employee-adjustments/{id}` |  |
| POST | `/v1/payroll/runs` | required |
| GET | `/v1/payroll/runs` |  |
| GET | `/v1/payroll/runs/{id}` |  |
| POST | `/v1/payroll/runs/{id}/recalculate` | required |
| POST | `/v1/payroll/runs/{id}/{review,approve,reject,lock,mark-paid}` | required |
| GET | `/v1/payroll/runs/{id}/payslips` |  |
| GET | `/v1/payroll/payslips/{payslipId}` |  |
| GET | `/v1/payroll/runs/{id}/bank-export` |  |
| GET | `/v1/salary-components` |  |
| GET | `/v1/salary-components/{id}` |  |
| POST | `/v1/salary-components` |  |
| PATCH | `/v1/salary-components/{id}` |  |

### Platform, auth, settings, users

| Method | Path | Idempotency-Key |
|---|---|---|
| POST | `/v1/auth/login` |  |
| POST | `/v1/auth/switch-tenant` |  |
| POST | `/v1/auth/logout-all` |  |
| POST | `/v1/auth/change-password` |  |
| GET | `/v1/me` |  |
| POST | `/v1/tenants` |  |
| GET | `/v1/public/brand` |  |
| GET | `/v1/public/tenants/{slug}/brand` |  |
| GET | `/v1/platform/tenants` |  |
| PATCH | `/v1/platform/tenants/{id}` |  |
| GET | `/v1/settings` |  |
| PUT | `/v1/settings` |  |
| GET | `/v1/users` |  |
| POST | `/v1/users` |  |
| PATCH | `/v1/users/{membershipId}` |  |
| POST | `/v1/users/{membershipId}/terminate-sessions` |  |

### Reports, self-service, portal

| Method | Path | Idempotency-Key |
|---|---|---|
| GET | `/v1/reports/dashboard` |  |
| GET | `/v1/reports/site-margin` |  |
| GET | `/v1/reports/employee-deployment-trail` |  |
| GET | `/v1/reports/audit` |  |
| GET | `/v1/me/duties` |  |
| GET | `/v1/me/attendance` |  |
| GET | `/v1/me/payslips` |  |
| GET | `/v1/portal/summary` |  |

### Roster

| Method | Path | Idempotency-Key |
|---|---|---|
| GET | `/v1/deployments` |  |
| POST | `/v1/deployments` |  |
| POST | `/v1/deployments/transfer` |  |
| POST | `/v1/deployments/{id}/end` |  |
| POST | `/v1/roster/generate` |  |
| GET | `/v1/roster` |  |
| POST | `/v1/shifts` |  |
| POST | `/v1/shifts/{id}/cancel` |  |
| GET | `/v1/shifts/{id}/eligible-employees` |  |
| POST | `/v1/assignments` |  |
| POST | `/v1/assignments/{id}/replace` |  |
| POST | `/v1/assignments/{id}/cancel` |  |
