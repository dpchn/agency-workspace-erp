-- For a manually installed PostgreSQL (no Docker): psql -U postgres -f deploy/postgres-init.sql
-- Change the passwords and pass the same values to the backend (DB_OWNER_PASSWORD / DB_APP_PASSWORD).
CREATE ROLE erp_owner LOGIN PASSWORD 'erp_owner';
CREATE ROLE erp_app LOGIN PASSWORD 'erp_app';
CREATE DATABASE erp OWNER erp_owner;
