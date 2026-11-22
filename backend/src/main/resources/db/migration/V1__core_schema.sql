-- =====================================================================
-- Core schema. Every tenant-owned table carries tenant_id and is protected
-- by row-level security (see V2). Money is NUMERIC, never float.
-- =====================================================================
CREATE EXTENSION IF NOT EXISTS btree_gist;

-- ---------------------------------------------------------------- platform (global)
CREATE TABLE tenants (
    id              UUID PRIMARY KEY,
    slug            VARCHAR(60)  NOT NULL UNIQUE,
    name            VARCHAR(200) NOT NULL,
    legal_name      VARCHAR(200),
    status          VARCHAR(20)  NOT NULL DEFAULT 'TRIAL',
    plan            VARCHAR(40)  NOT NULL DEFAULT 'STARTER',
    gstin           VARCHAR(20),
    state_code      VARCHAR(5),
    timezone        VARCHAR(60)  NOT NULL DEFAULT 'Asia/Kolkata',
    currency        VARCHAR(3)   NOT NULL DEFAULT 'INR',
    fiscal_year_start_month INT  NOT NULL DEFAULT 4,
    settings        JSONB        NOT NULL DEFAULT '{}'::jsonb,
    branding        JSONB        NOT NULL DEFAULT '{}'::jsonb,
    max_employees   INT,
    trial_ends_at   TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version         BIGINT       NOT NULL DEFAULT 0
);

CREATE TABLE users (
    id              UUID PRIMARY KEY,
    email           VARCHAR(200) UNIQUE,
    phone           VARCHAR(20)  UNIQUE,
    full_name       VARCHAR(200) NOT NULL,
    password_hash   VARCHAR(200) NOT NULL,
    platform_admin  BOOLEAN      NOT NULL DEFAULT FALSE,
    status          VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    token_epoch     INT          NOT NULL DEFAULT 0,  -- bump to revoke all sessions
    last_login_at   TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version         BIGINT       NOT NULL DEFAULT 0,
    CHECK (email IS NOT NULL OR phone IS NOT NULL)
);

-- Membership is explicit; never inferred from e-mail domain.
CREATE TABLE memberships (
    id              UUID PRIMARY KEY,
    tenant_id       UUID NOT NULL REFERENCES tenants(id),
    user_id         UUID NOT NULL REFERENCES users(id),
    role            VARCHAR(30) NOT NULL,
    branch_ids      UUID[],          -- NULL = all branches
    site_ids        UUID[],          -- NULL = all sites in scope
    client_id       UUID,            -- client portal users
    employee_id     UUID,            -- guard self-service
    status          VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    version         BIGINT NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, user_id)
);

-- ---------------------------------------------------------------- organisation
CREATE TABLE branches (
    id UUID PRIMARY KEY, tenant_id UUID NOT NULL REFERENCES tenants(id),
    code VARCHAR(30) NOT NULL, name VARCHAR(200) NOT NULL,
    state_code VARCHAR(5), address TEXT, gstin VARCHAR(20),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(), version BIGINT NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, code), UNIQUE (tenant_id, id)
);

-- Generic "kind of worker on a post": guard, lady guard, gunman, supervisor, housekeeping, bouncer ...
CREATE TABLE service_types (
    id UUID PRIMARY KEY, tenant_id UUID NOT NULL REFERENCES tenants(id),
    code VARCHAR(30) NOT NULL, name VARCHAR(120) NOT NULL,
    category VARCHAR(30) NOT NULL DEFAULT 'SECURITY',
    default_shift_hours NUMERIC(5,2) NOT NULL DEFAULT 8,
    required_documents VARCHAR(40)[],    -- e.g. {POLICE_VERIFICATION,ARMS_LICENSE}
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(), version BIGINT NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, code), UNIQUE (tenant_id, id)
);

CREATE TABLE holidays (
    id UUID PRIMARY KEY, tenant_id UUID NOT NULL REFERENCES tenants(id),
    branch_id UUID, holiday_date DATE NOT NULL, name VARCHAR(120) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(), version BIGINT NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX ux_holiday ON holidays (tenant_id, holiday_date, COALESCE(branch_id, '00000000-0000-0000-0000-000000000000'));

CREATE TABLE shift_templates (
    id UUID PRIMARY KEY, tenant_id UUID NOT NULL REFERENCES tenants(id),
    code VARCHAR(30) NOT NULL, name VARCHAR(120) NOT NULL,
    start_time TIME NOT NULL, end_time TIME NOT NULL,       -- end < start => crosses midnight
    break_minutes INT NOT NULL DEFAULT 0,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(), version BIGINT NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, code), UNIQUE (tenant_id, id)
);

-- ---------------------------------------------------------------- HR
CREATE TABLE employees (
    id UUID PRIMARY KEY, tenant_id UUID NOT NULL REFERENCES tenants(id),
    employee_code VARCHAR(40) NOT NULL,
    first_name VARCHAR(100) NOT NULL, last_name VARCHAR(100),
    phone VARCHAR(20), email VARCHAR(200), gender VARCHAR(10), date_of_birth DATE,
    branch_id UUID NOT NULL, service_type_id UUID NOT NULL,
    qualified_service_type_ids UUID[],   -- additional posts this person may cover
    status VARCHAR(30) NOT NULL DEFAULT 'DRAFT',
    joined_on DATE, exited_on DATE, exit_reason TEXT,
    weekly_off_day INT,             -- 1=Mon..7=Sun, NULL = rotational/none
    uan VARCHAR(20), esic_no VARCHAR(20), pan VARCHAR(12), id_number_last4 VARCHAR(4),
    bank_account_no VARCHAR(30), bank_ifsc VARCHAR(15), bank_name VARCHAR(100),
    address TEXT, emergency_contact VARCHAR(200),
    consent_at TIMESTAMPTZ,
    user_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(), version BIGINT NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, employee_code), UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, branch_id) REFERENCES branches (tenant_id, id),
    FOREIGN KEY (tenant_id, service_type_id) REFERENCES service_types (tenant_id, id)
);

CREATE TABLE employee_documents (
    id UUID PRIMARY KEY, tenant_id UUID NOT NULL REFERENCES tenants(id),
    employee_id UUID NOT NULL,
    doc_type VARCHAR(40) NOT NULL,      -- POLICE_VERIFICATION, ID_PROOF, TRAINING, ARMS_LICENSE, MEDICAL ...
    doc_number VARCHAR(80), issued_on DATE, expires_on DATE, object_key VARCHAR(400),
    verification_status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    verified_by UUID, verified_at TIMESTAMPTZ, notes TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(), version BIGINT NOT NULL DEFAULT 0,
    FOREIGN KEY (tenant_id, employee_id) REFERENCES employees (tenant_id, id)
);
CREATE INDEX ix_empdoc_expiry ON employee_documents (tenant_id, expires_on);

-- ---------------------------------------------------------------- clients, sites, posts
CREATE TABLE clients (
    id UUID PRIMARY KEY, tenant_id UUID NOT NULL REFERENCES tenants(id),
    code VARCHAR(40) NOT NULL, legal_name VARCHAR(200) NOT NULL, display_name VARCHAR(200),
    gstin VARCHAR(20), pan VARCHAR(12), billing_address TEXT, state_code VARCHAR(5),
    contact_name VARCHAR(120), contact_email VARCHAR(200), contact_phone VARCHAR(20),
    payment_terms_days INT NOT NULL DEFAULT 30, credit_limit NUMERIC(14,2),
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(), version BIGINT NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, code), UNIQUE (tenant_id, id)
);

CREATE TABLE sites (
    id UUID PRIMARY KEY, tenant_id UUID NOT NULL REFERENCES tenants(id),
    client_id UUID NOT NULL, branch_id UUID NOT NULL,
    code VARCHAR(40) NOT NULL, name VARCHAR(200) NOT NULL, address TEXT, state_code VARCHAR(5),
    timezone VARCHAR(60),
    latitude DOUBLE PRECISION, longitude DOUBLE PRECISION,
    geofence_radius_m INT NOT NULL DEFAULT 200,
    geofence_policy VARCHAR(10) NOT NULL DEFAULT 'FLAG',   -- ENFORCE | FLAG | OFF
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(), version BIGINT NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, code), UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, client_id) REFERENCES clients (tenant_id, id),
    FOREIGN KEY (tenant_id, branch_id) REFERENCES branches (tenant_id, id)
);

-- A post is a position to be covered, independent of who covers it.
CREATE TABLE posts (
    id UUID PRIMARY KEY, tenant_id UUID NOT NULL REFERENCES tenants(id),
    site_id UUID NOT NULL, service_type_id UUID NOT NULL,
    code VARCHAR(40) NOT NULL, name VARCHAR(200) NOT NULL, instructions TEXT,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(), version BIGINT NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, site_id, code), UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, site_id) REFERENCES sites (tenant_id, id),
    FOREIGN KEY (tenant_id, service_type_id) REFERENCES service_types (tenant_id, id)
);

-- Which shifts a post needs, how many people, on which weekdays.
CREATE TABLE post_shift_requirements (
    id UUID PRIMARY KEY, tenant_id UUID NOT NULL REFERENCES tenants(id),
    post_id UUID NOT NULL, shift_template_id UUID NOT NULL,
    headcount INT NOT NULL DEFAULT 1 CHECK (headcount > 0),
    days_of_week INT[] NOT NULL DEFAULT '{1,2,3,4,5,6,7}',
    effective_from DATE NOT NULL, effective_to DATE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(), version BIGINT NOT NULL DEFAULT 0,
    FOREIGN KEY (tenant_id, post_id) REFERENCES posts (tenant_id, id),
    FOREIGN KEY (tenant_id, shift_template_id) REFERENCES shift_templates (tenant_id, id)
);

-- ---------------------------------------------------------------- contracts (what we CHARGE)
CREATE TABLE contracts (
    id UUID PRIMARY KEY, tenant_id UUID NOT NULL REFERENCES tenants(id),
    client_id UUID NOT NULL, contract_no VARCHAR(60) NOT NULL, title VARCHAR(200),
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    start_date DATE NOT NULL, end_date DATE, auto_renew BOOLEAN NOT NULL DEFAULT FALSE,
    payment_terms_days INT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(), version BIGINT NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, contract_no), UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, client_id) REFERENCES clients (tenant_id, id)
);

-- Amendments create a new version. Approved versions are immutable.
CREATE TABLE contract_versions (
    id UUID PRIMARY KEY, tenant_id UUID NOT NULL REFERENCES tenants(id),
    contract_id UUID NOT NULL, version_no INT NOT NULL,
    effective_from DATE NOT NULL, effective_to DATE,
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',     -- DRAFT | PENDING_APPROVAL | APPROVED | SUPERSEDED | REJECTED
    gst_rate_pct NUMERIC(5,2) NOT NULL DEFAULT 18,
    absence_penalty_per_duty NUMERIC(12,2),
    notes TEXT, created_by UUID, approved_by UUID, approved_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(), version BIGINT NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, contract_id, version_no), UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, contract_id) REFERENCES contracts (tenant_id, id)
);

CREATE TABLE contract_rates (
    id UUID PRIMARY KEY, tenant_id UUID NOT NULL REFERENCES tenants(id),
    contract_version_id UUID NOT NULL, site_id UUID NOT NULL, service_type_id UUID NOT NULL,
    billing_model VARCHAR(30) NOT NULL,   -- MONTHLY_PER_POST | PER_SHIFT | PER_DAY | PER_HOUR | PER_HEADCOUNT_MONTH | LUMPSUM_MONTHLY
    rate NUMERIC(12,2) NOT NULL CHECK (rate >= 0),
    quantity NUMERIC(8,2) NOT NULL DEFAULT 1,           -- posts / headcount contracted
    shift_hours NUMERIC(5,2) NOT NULL DEFAULT 8,
    attendance_linked BOOLEAN NOT NULL DEFAULT TRUE,    -- monthly rates reduced for uncovered duties
    extra_shift_rate NUMERIC(12,2),                     -- client-requested additional shifts
    ot_hourly_rate NUMERIC(12,2),
    holiday_multiplier NUMERIC(5,2) NOT NULL DEFAULT 1,
    minimum_units NUMERIC(10,2),                        -- minimum guarantee
    description VARCHAR(300),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(), version BIGINT NOT NULL DEFAULT 0,
    FOREIGN KEY (tenant_id, contract_version_id) REFERENCES contract_versions (tenant_id, id),
    FOREIGN KEY (tenant_id, site_id) REFERENCES sites (tenant_id, id),
    UNIQUE (tenant_id, contract_version_id, site_id, service_type_id)
);

-- ---------------------------------------------------------------- pay rates (what we PAY)
-- Resolved most-specific-first: employee+site > employee > site > client > service type.
CREATE TABLE pay_rates (
    id UUID PRIMARY KEY, tenant_id UUID NOT NULL REFERENCES tenants(id),
    service_type_id UUID, client_id UUID, site_id UUID, employee_id UUID, branch_id UUID,
    wage_basis VARCHAR(10) NOT NULL,      -- MONTHLY | DAILY | HOURLY
    rate NUMERIC(12,2) NOT NULL CHECK (rate >= 0),
    duty_allowance NUMERIC(12,2) NOT NULL DEFAULT 0,  -- per duty site allowance
    extra_duty_rate NUMERIC(12,2),                    -- double duty / extra shift
    ot_hourly_rate NUMERIC(12,2),
    effective_from DATE NOT NULL, effective_to DATE,
    notes VARCHAR(300),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(), version BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX ix_payrate_lookup ON pay_rates (tenant_id, effective_from);

CREATE TABLE salary_components (
    id UUID PRIMARY KEY, tenant_id UUID NOT NULL REFERENCES tenants(id),
    code VARCHAR(30) NOT NULL, name VARCHAR(120) NOT NULL,
    kind VARCHAR(30) NOT NULL,        -- EARNING | DEDUCTION | EMPLOYER_CONTRIBUTION
    calc_type VARCHAR(30) NOT NULL,   -- PERCENT | FIXED | SLAB
    base VARCHAR(30),                 -- DUTY_WAGE | OT_PAY | GROSS | <component code>
    percent NUMERIC(7,4), fixed_amount NUMERIC(12,2),
    base_ceiling NUMERIC(12,2),       -- e.g. PF wage ceiling
    applies_if_gross_upto NUMERIC(12,2), -- e.g. ESI threshold
    slabs JSONB,                      -- [{"upto":7500,"amount":0},{"upto":10000,"amount":175},{"upto":null,"amount":200}]
    state_code VARCHAR(5),            -- NULL = all states
    sequence INT NOT NULL DEFAULT 100,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    effective_from DATE NOT NULL DEFAULT DATE '2000-01-01', effective_to DATE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(), version BIGINT NOT NULL DEFAULT 0
);

-- ---------------------------------------------------------------- deployment & roster
-- Where an employee is posted over time. Transfers close one and open another (history kept).
CREATE TABLE deployments (
    id UUID PRIMARY KEY, tenant_id UUID NOT NULL REFERENCES tenants(id),
    employee_id UUID NOT NULL, site_id UUID NOT NULL, post_id UUID, service_type_id UUID NOT NULL,
    shift_template_id UUID,
    deployment_type VARCHAR(20) NOT NULL DEFAULT 'PERMANENT',  -- PERMANENT | TEMPORARY | RELIEVER
    start_date DATE NOT NULL, end_date DATE,
    reason VARCHAR(300), status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_by UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(), version BIGINT NOT NULL DEFAULT 0,
    FOREIGN KEY (tenant_id, employee_id) REFERENCES employees (tenant_id, id),
    FOREIGN KEY (tenant_id, site_id) REFERENCES sites (tenant_id, id),
    CHECK (end_date IS NULL OR end_date >= start_date)
);
-- one permanent posting at a time
ALTER TABLE deployments ADD CONSTRAINT ex_deployment_permanent EXCLUDE USING gist (
    tenant_id WITH =, employee_id WITH =,
    daterange(start_date, COALESCE(end_date, 'infinity'::date), '[]') WITH &&
) WHERE (deployment_type = 'PERMANENT' AND status = 'ACTIVE');

CREATE TABLE shifts (
    id UUID PRIMARY KEY, tenant_id UUID NOT NULL REFERENCES tenants(id),
    site_id UUID NOT NULL, post_id UUID NOT NULL, service_type_id UUID NOT NULL, shift_template_id UUID,
    duty_date DATE NOT NULL, start_at TIMESTAMPTZ NOT NULL, end_at TIMESTAMPTZ NOT NULL,
    required_headcount INT NOT NULL DEFAULT 1,
    kind VARCHAR(20) NOT NULL DEFAULT 'REGULAR',     -- REGULAR | EXTRA
    client_requested BOOLEAN NOT NULL DEFAULT FALSE, -- billable extra
    status VARCHAR(20) NOT NULL DEFAULT 'OPEN',      -- OPEN | PARTIAL | FILLED | CANCELLED
    notes VARCHAR(300),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(), version BIGINT NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, post_id) REFERENCES posts (tenant_id, id),
    CHECK (end_at > start_at)
);
CREATE UNIQUE INDEX ux_shift_regular ON shifts (tenant_id, post_id, shift_template_id, duty_date) WHERE kind = 'REGULAR';
CREATE INDEX ix_shift_date ON shifts (tenant_id, duty_date, site_id);

CREATE TABLE assignments (
    id UUID PRIMARY KEY, tenant_id UUID NOT NULL REFERENCES tenants(id),
    shift_id UUID NOT NULL, employee_id UUID NOT NULL,
    start_at TIMESTAMPTZ NOT NULL, end_at TIMESTAMPTZ NOT NULL,
    role VARCHAR(20) NOT NULL DEFAULT 'REGULAR',     -- REGULAR | RELIEF | REPLACEMENT | DOUBLE_DUTY
    replaces_assignment_id UUID,
    status VARCHAR(20) NOT NULL DEFAULT 'PLANNED',   -- PLANNED | CONFIRMED | COMPLETED | NO_SHOW | CANCELLED
    reason VARCHAR(300), created_by UUID, approved_by UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(), version BIGINT NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, shift_id) REFERENCES shifts (tenant_id, id),
    FOREIGN KEY (tenant_id, employee_id) REFERENCES employees (tenant_id, id),
    CHECK (end_at > start_at)
);
-- A person can never be in two places at once.
ALTER TABLE assignments ADD CONSTRAINT ex_assignment_overlap EXCLUDE USING gist (
    tenant_id WITH =, employee_id WITH =, tstzrange(start_at, end_at, '[)') WITH &&
) WHERE (status IN ('PLANNED', 'CONFIRMED', 'COMPLETED'));
CREATE INDEX ix_assignment_shift ON assignments (tenant_id, shift_id);

-- ---------------------------------------------------------------- attendance
CREATE TABLE attendance_events (
    id UUID PRIMARY KEY, tenant_id UUID NOT NULL REFERENCES tenants(id),
    client_event_id UUID NOT NULL,           -- generated on device; dedup key
    employee_id UUID NOT NULL, assignment_id UUID,
    kind VARCHAR(20) NOT NULL,               -- CHECK_IN | CHECK_OUT
    occurred_at TIMESTAMPTZ NOT NULL, received_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    latitude DOUBLE PRECISION, longitude DOUBLE PRECISION, accuracy_m DOUBLE PRECISION, distance_m DOUBLE PRECISION,
    device_id VARCHAR(120), selfie_hash VARCHAR(128),
    source VARCHAR(20) NOT NULL DEFAULT 'MOBILE',
    offline BOOLEAN NOT NULL DEFAULT FALSE,
    flags VARCHAR(40)[] NOT NULL DEFAULT '{}',
    status VARCHAR(20) NOT NULL DEFAULT 'ACCEPTED',  -- ACCEPTED | FLAGGED | REJECTED
    recorded_by UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(), version BIGINT NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, client_event_id)
);
CREATE INDEX ix_attevent_emp ON attendance_events (tenant_id, employee_id, occurred_at);

-- One record per assignment: the projection used by payroll AND billing.
CREATE TABLE attendance_records (
    id UUID PRIMARY KEY, tenant_id UUID NOT NULL REFERENCES tenants(id),
    assignment_id UUID NOT NULL, employee_id UUID NOT NULL, shift_id UUID NOT NULL,
    site_id UUID NOT NULL, post_id UUID NOT NULL, client_id UUID NOT NULL, service_type_id UUID NOT NULL,
    duty_date DATE NOT NULL,
    check_in_at TIMESTAMPTZ, check_out_at TIMESTAMPTZ,
    worked_minutes INT NOT NULL DEFAULT 0, late_minutes INT NOT NULL DEFAULT 0, ot_minutes INT NOT NULL DEFAULT 0,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',          -- PENDING | PRESENT | HALF_DAY | ABSENT | ON_LEAVE
    approval_status VARCHAR(20) NOT NULL DEFAULT 'PENDING', -- PENDING | APPROVED | REJECTED
    pay_units NUMERIC(5,2) NOT NULL DEFAULT 0,
    bill_units NUMERIC(5,2) NOT NULL DEFAULT 0,
    billable BOOLEAN NOT NULL DEFAULT TRUE,
    is_holiday BOOLEAN NOT NULL DEFAULT FALSE,
    assignment_role VARCHAR(20) NOT NULL DEFAULT 'REGULAR',
    shift_kind VARCHAR(20) NOT NULL DEFAULT 'REGULAR',
    flags VARCHAR(40)[] NOT NULL DEFAULT '{}',
    approved_by UUID, approved_at TIMESTAMPTZ,
    payroll_run_id UUID,     -- set when payroll is locked: record becomes immutable
    invoice_id UUID,         -- set when invoice is issued
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(), version BIGINT NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, assignment_id), UNIQUE (tenant_id, id)
);
CREATE INDEX ix_attrec_period ON attendance_records (tenant_id, duty_date, employee_id);
CREATE INDEX ix_attrec_client ON attendance_records (tenant_id, client_id, duty_date);

CREATE TABLE attendance_adjustments (
    id UUID PRIMARY KEY, tenant_id UUID NOT NULL REFERENCES tenants(id),
    record_id UUID NOT NULL,
    proposed_check_in_at TIMESTAMPTZ, proposed_check_out_at TIMESTAMPTZ, proposed_status VARCHAR(20),
    reason VARCHAR(500) NOT NULL, evidence_key VARCHAR(400),
    before_state JSONB,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    requested_by UUID NOT NULL, decided_by UUID, decided_at TIMESTAMPTZ, decision_note VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(), version BIGINT NOT NULL DEFAULT 0,
    FOREIGN KEY (tenant_id, record_id) REFERENCES attendance_records (tenant_id, id)
);

CREATE TABLE leave_requests (
    id UUID PRIMARY KEY, tenant_id UUID NOT NULL REFERENCES tenants(id),
    employee_id UUID NOT NULL, leave_type VARCHAR(20) NOT NULL,  -- CASUAL | SICK | EARNED | UNPAID
    from_date DATE NOT NULL, to_date DATE NOT NULL, reason VARCHAR(500),
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING', decided_by UUID, decided_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(), version BIGINT NOT NULL DEFAULT 0,
    FOREIGN KEY (tenant_id, employee_id) REFERENCES employees (tenant_id, id),
    CHECK (to_date >= from_date)
);

-- ---------------------------------------------------------------- payroll
CREATE TABLE employee_adjustments (
    id UUID PRIMARY KEY, tenant_id UUID NOT NULL REFERENCES tenants(id),
    employee_id UUID NOT NULL, period_month DATE NOT NULL,   -- first day of month
    kind VARCHAR(30) NOT NULL,  -- ADVANCE_RECOVERY | LOAN_EMI | UNIFORM | FINE | BONUS | ALLOWANCE | OTHER_EARNING | OTHER_DEDUCTION
    amount NUMERIC(12,2) NOT NULL CHECK (amount >= 0),
    notes VARCHAR(300), payroll_run_id UUID,
    created_by UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(), version BIGINT NOT NULL DEFAULT 0,
    FOREIGN KEY (tenant_id, employee_id) REFERENCES employees (tenant_id, id)
);

CREATE TABLE payroll_runs (
    id UUID PRIMARY KEY, tenant_id UUID NOT NULL REFERENCES tenants(id),
    period_start DATE NOT NULL, period_end DATE NOT NULL, branch_id UUID,
    version_no INT NOT NULL DEFAULT 1,
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',  -- DRAFT | REVIEWED | APPROVED | LOCKED | PAID | SUPERSEDED
    employee_count INT NOT NULL DEFAULT 0,
    total_gross NUMERIC(14,2) NOT NULL DEFAULT 0, total_deductions NUMERIC(14,2) NOT NULL DEFAULT 0,
    total_net NUMERIC(14,2) NOT NULL DEFAULT 0, total_employer_cost NUMERIC(14,2) NOT NULL DEFAULT 0,
    exceptions JSONB NOT NULL DEFAULT '[]'::jsonb,
    rule_snapshot JSONB,
    created_by UUID, reviewed_by UUID, approved_by UUID, locked_at TIMESTAMPTZ, paid_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(), version BIGINT NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, id)
);
CREATE UNIQUE INDEX ux_payroll_active ON payroll_runs (tenant_id, period_start, COALESCE(branch_id, '00000000-0000-0000-0000-000000000000'))
    WHERE status <> 'SUPERSEDED';

CREATE TABLE payslips (
    id UUID PRIMARY KEY, tenant_id UUID NOT NULL REFERENCES tenants(id),
    run_id UUID NOT NULL, employee_id UUID NOT NULL,
    duties NUMERIC(7,2) NOT NULL DEFAULT 0, ot_hours NUMERIC(7,2) NOT NULL DEFAULT 0, leave_days NUMERIC(5,2) NOT NULL DEFAULT 0,
    gross NUMERIC(12,2) NOT NULL, total_deductions NUMERIC(12,2) NOT NULL, net NUMERIC(12,2) NOT NULL,
    employer_contributions NUMERIC(12,2) NOT NULL,
    payment_status VARCHAR(20) NOT NULL DEFAULT 'UNPAID', payment_ref VARCHAR(100),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(), version BIGINT NOT NULL DEFAULT 0,
    FOREIGN KEY (tenant_id, run_id) REFERENCES payroll_runs (tenant_id, id),
    UNIQUE (tenant_id, run_id, employee_id)
);

CREATE TABLE payroll_lines (
    id UUID PRIMARY KEY, tenant_id UUID NOT NULL REFERENCES tenants(id),
    run_id UUID NOT NULL, employee_id UUID NOT NULL,
    component_code VARCHAR(30) NOT NULL, component_name VARCHAR(120) NOT NULL, kind VARCHAR(30) NOT NULL,
    quantity NUMERIC(10,2), rate NUMERIC(12,4), amount NUMERIC(12,2) NOT NULL,
    site_id UUID, source JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(), version BIGINT NOT NULL DEFAULT 0,
    FOREIGN KEY (tenant_id, run_id) REFERENCES payroll_runs (tenant_id, id)
);
CREATE INDEX ix_payline_run ON payroll_lines (tenant_id, run_id, employee_id);

-- Cost of each employee at each site: feeds contract/site margin.
CREATE TABLE payroll_site_costs (
    id UUID PRIMARY KEY, tenant_id UUID NOT NULL REFERENCES tenants(id),
    run_id UUID NOT NULL, employee_id UUID NOT NULL, site_id UUID NOT NULL, client_id UUID NOT NULL,
    duties NUMERIC(7,2) NOT NULL, wage_amount NUMERIC(12,2) NOT NULL, employer_cost NUMERIC(12,2) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(), version BIGINT NOT NULL DEFAULT 0,
    FOREIGN KEY (tenant_id, run_id) REFERENCES payroll_runs (tenant_id, id)
);

-- ---------------------------------------------------------------- billing
CREATE TABLE number_sequences (
    tenant_id UUID NOT NULL REFERENCES tenants(id), seq_key VARCHAR(60) NOT NULL, next_value BIGINT NOT NULL DEFAULT 1,
    PRIMARY KEY (tenant_id, seq_key)
);

CREATE TABLE invoices (
    id UUID PRIMARY KEY, tenant_id UUID NOT NULL REFERENCES tenants(id),
    client_id UUID NOT NULL, contract_id UUID,
    kind VARCHAR(20) NOT NULL DEFAULT 'INVOICE',   -- INVOICE | CREDIT_NOTE | DEBIT_NOTE
    ref_invoice_id UUID,
    period_start DATE, period_end DATE,
    invoice_no VARCHAR(60), version_no INT NOT NULL DEFAULT 1,
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',   -- DRAFT | REVIEWED | APPROVED | ISSUED | PARTIALLY_PAID | PAID | OVERDUE | DISPUTED | CANCELLED | SUPERSEDED
    issue_date DATE, due_date DATE,
    place_of_supply VARCHAR(5), supplier_state VARCHAR(5),
    subtotal NUMERIC(14,2) NOT NULL DEFAULT 0, cgst NUMERIC(14,2) NOT NULL DEFAULT 0, sgst NUMERIC(14,2) NOT NULL DEFAULT 0,
    igst NUMERIC(14,2) NOT NULL DEFAULT 0, total NUMERIC(14,2) NOT NULL DEFAULT 0, amount_paid NUMERIC(14,2) NOT NULL DEFAULT 0,
    gst_rate_pct NUMERIC(5,2) NOT NULL DEFAULT 18,
    notes TEXT, dispute_reason TEXT,
    created_by UUID, approved_by UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(), version BIGINT NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, client_id) REFERENCES clients (tenant_id, id)
);
CREATE UNIQUE INDEX ux_invoice_no ON invoices (tenant_id, invoice_no) WHERE invoice_no IS NOT NULL;

CREATE TABLE invoice_lines (
    id UUID PRIMARY KEY, tenant_id UUID NOT NULL REFERENCES tenants(id),
    invoice_id UUID NOT NULL, site_id UUID, service_type_id UUID, contract_rate_id UUID,
    line_type VARCHAR(30) NOT NULL DEFAULT 'SERVICE',  -- SERVICE | EXTRA_SHIFT | OVERTIME | DEDUCTION | MINIMUM_TOPUP | MANUAL
    description VARCHAR(400) NOT NULL,
    quantity NUMERIC(12,2) NOT NULL, unit VARCHAR(20), rate NUMERIC(12,4) NOT NULL, amount NUMERIC(14,2) NOT NULL,
    source_ref JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(), version BIGINT NOT NULL DEFAULT 0,
    FOREIGN KEY (tenant_id, invoice_id) REFERENCES invoices (tenant_id, id)
);

CREATE TABLE receipts (
    id UUID PRIMARY KEY, tenant_id UUID NOT NULL REFERENCES tenants(id),
    client_id UUID NOT NULL, amount NUMERIC(14,2) NOT NULL CHECK (amount > 0),
    received_on DATE NOT NULL, mode VARCHAR(20) NOT NULL DEFAULT 'BANK_TRANSFER',
    external_ref VARCHAR(100) NOT NULL, tds_amount NUMERIC(14,2) NOT NULL DEFAULT 0,
    unallocated NUMERIC(14,2) NOT NULL DEFAULT 0, notes VARCHAR(300), created_by UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(), version BIGINT NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, external_ref),
    FOREIGN KEY (tenant_id, client_id) REFERENCES clients (tenant_id, id)
);

CREATE TABLE receipt_allocations (
    id UUID PRIMARY KEY, tenant_id UUID NOT NULL REFERENCES tenants(id),
    receipt_id UUID NOT NULL, invoice_id UUID NOT NULL, amount NUMERIC(14,2) NOT NULL CHECK (amount > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(), version BIGINT NOT NULL DEFAULT 0
);

-- ---------------------------------------------------------------- operations
CREATE TABLE incidents (
    id UUID PRIMARY KEY, tenant_id UUID NOT NULL REFERENCES tenants(id),
    site_id UUID NOT NULL, reported_by UUID, reporter_employee_id UUID,
    title VARCHAR(200) NOT NULL, description TEXT, category VARCHAR(40),
    severity VARCHAR(10) NOT NULL,  -- LOW | MEDIUM | HIGH | CRITICAL
    status VARCHAR(20) NOT NULL DEFAULT 'OPEN', -- OPEN | ACKNOWLEDGED | IN_PROGRESS | RESOLVED | CLOSED
    assigned_to UUID, occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    response_due_at TIMESTAMPTZ, acknowledged_at TIMESTAMPTZ, acknowledged_by UUID,
    escalation_level INT NOT NULL DEFAULT 0,
    closed_at TIMESTAMPTZ, closed_by UUID, closure_notes TEXT,
    client_visible BOOLEAN NOT NULL DEFAULT FALSE,
    attachments VARCHAR(400)[] NOT NULL DEFAULT '{}',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(), version BIGINT NOT NULL DEFAULT 0
);

-- ---------------------------------------------------------------- infrastructure
CREATE TABLE audit_events (
    id UUID PRIMARY KEY, tenant_id UUID NOT NULL,
    actor_user_id UUID, action VARCHAR(80) NOT NULL, entity_type VARCHAR(60) NOT NULL, entity_id UUID,
    before_hash VARCHAR(64), after_hash VARCHAR(64), details JSONB,
    correlation_id VARCHAR(64), created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_audit_entity ON audit_events (tenant_id, entity_type, entity_id);

CREATE TABLE outbox_events (
    id UUID PRIMARY KEY, tenant_id UUID NOT NULL,
    event_type VARCHAR(80) NOT NULL, aggregate_id UUID, payload JSONB NOT NULL,
    attempts INT NOT NULL DEFAULT 0, last_error TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(), published_at TIMESTAMPTZ
);
CREATE INDEX ix_outbox_pending ON outbox_events (created_at) WHERE published_at IS NULL;

CREATE TABLE notifications (
    id UUID PRIMARY KEY, tenant_id UUID NOT NULL,
    channel VARCHAR(20) NOT NULL, recipient VARCHAR(200), template VARCHAR(80) NOT NULL,
    payload JSONB, status VARCHAR(20) NOT NULL DEFAULT 'QUEUED', attempts INT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(), sent_at TIMESTAMPTZ
);

CREATE TABLE idempotency_keys (
    tenant_id UUID NOT NULL, idem_key VARCHAR(100) NOT NULL,
    request_hash VARCHAR(64) NOT NULL, status_code INT, response_body TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, idem_key)
);
