-- Platform operator onboarding: plan limits beyond employees, contact details and notes per agency.
ALTER TABLE tenants
    ADD COLUMN max_sites INT,
    ADD COLUMN max_users INT,
    ADD COLUMN contact_name VARCHAR(200),
    ADD COLUMN contact_email VARCHAR(200),
    ADD COLUMN contact_phone VARCHAR(20),
    ADD COLUMN platform_notes VARCHAR(2000),
    ADD COLUMN status_reason VARCHAR(500),
    ADD COLUMN onboarded_by UUID;
