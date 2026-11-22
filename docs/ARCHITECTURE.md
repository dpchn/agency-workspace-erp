# Architecture

Follows `docs/specs/02_Backend_System_Architecture.pdf`: a modular monolith on PostgreSQL.

## Backend packages (`backend/src/main/java/app/erp`)

| Package | Responsibility |
|---|---|
| `common` | Tenancy (TenantContext, Hibernate `@TenantId` resolver, RLS-binding DataSource), error envelope, audit, outbox, idempotency, generic CRUD, sequences |
| `security` | Roles → permissions, JWT issue/verify, token revocation (token epoch), request context |
| `platform` | Tenants, users, memberships, signup and seeding, settings and branding, user admin, platform operator |
| `org` | Branches, service types, shift templates, holidays |
| `hr` | Employees and lifecycle, documents, deployability (`HrService`) |
| `client` | Clients, sites, posts, post shift requirements |
| `contract` | Contracts, versions, rate lines, pay rates, `PayRateResolver` |
| `roster` | Deployments, transfers, roster generation, assignments, replacement finder (`RosterService`) |
| `attendance` | Event ingestion and fraud flags, record projection, day close, adjustments, approvals, leave |
| `payroll` | `PayrollEngine` (pure), `PayrollService` (lifecycle, lock, arrears), payslips, bank export |
| `billing` | `InvoiceEngine` (pure), `BillingService` (generate, issue, credit notes, receipts), aging |
| `ops` | Incidents, background jobs |
| `report` | Dashboard, margin, deployment trail, audit, self-service and client portal |

## Multi-tenancy (defence in depth)

1. The tenant comes only from the verified JWT (`tid` claim), never from request input.
2. Hibernate `@TenantId` adds `tenant_id = ?` to every JPA query and fills it on insert.
3. `TenantAwareDataSourceConfig` runs `set_config('app.tenant_id', …)` on every pooled connection
   and resets it on release.
4. PostgreSQL **FORCE ROW LEVEL SECURITY** on every tenant table. The app connects as a non-owner
   role, so even raw SQL in report endpoints cannot cross tenants. Verified: an unbound connection
   sees 0 rows.
5. Composite `(tenant_id, id)` foreign keys prevent cross-tenant references.
6. Cross-tenant IDs return 404 (non-disclosing).

## Integrity rules enforced by the database

* `ex_assignment_overlap`: GiST exclusion; a person cannot hold two overlapping duties.
* `ex_deployment_permanent`: one active permanent posting per employee at a time.
* `ux_shift_regular`: roster generation is idempotent.
* `ux_payroll_active`: one live payroll run per period and branch.
* `ux_invoice_no`: unique invoice numbers per tenant, gap-free per financial year (`number_sequences` row lock).
* Unique `(tenant_id, client_event_id)` for offline attendance dedup and `(tenant_id, external_ref)` for payments.

## Reliability

* `Idempotency-Key` is required on check-ins, approvals, payroll and invoice actions and payments.
  Replays return the stored response; reusing a key with a different body returns 422.
* A transactional outbox records `AttendanceApproved`, `PayrollApproved`, `InvoiceIssued`,
  `PaymentRecorded`, `IncidentRaised`, `IncidentEscalated` and `ShiftUncovered`. A relay turns them
  into notifications, with at-least-once delivery and `FOR UPDATE SKIP LOCKED`.
* Background jobs run per tenant in separate transactions, so one agency's failure never blocks another.
* Optimistic locking (`version`) on every row. Financial corrections are new documents (credit notes,
  new payroll versions), never in-place edits of closed data.

## API conventions

* Errors: `{code, message, correlationId, fieldErrors}`. 403 for missing permission, 404 for
  out-of-scope or absent records, 409 for conflicts, 422 for invalid state transitions.
* Lists: `?page=&size=` plus allow-listed equality filters and `q` search. Responses are `{items, page, size, total}`.
* OpenAPI: `/v3/api-docs`, Swagger UI: `/swagger-ui`.
