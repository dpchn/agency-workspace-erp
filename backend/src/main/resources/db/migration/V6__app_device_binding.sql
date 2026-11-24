-- Self-SMS sign-in (until an SMS gateway is connected): a number's first sign-in binds it to that phone; another
-- phone needs the agency to allow it.
ALTER TABLE users ADD COLUMN app_device_id VARCHAR(120);
ALTER TABLE users ADD COLUMN app_device_bound_at TIMESTAMPTZ;
