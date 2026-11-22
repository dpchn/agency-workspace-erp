#!/bin/bash
# Runs once, on first start of an empty Postgres volume (docker-entrypoint-initdb.d).
# Two roles: erp_owner runs migrations and owns the tables; erp_app is the runtime role,
# which does NOT own the tables, so PostgreSQL row-level security (tenant isolation) applies to it.
set -euo pipefail
: "${ERP_OWNER_PASSWORD:?set ERP_OWNER_PASSWORD}"
: "${ERP_APP_PASSWORD:?set ERP_APP_PASSWORD}"
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" \
     -v owner_pw="$ERP_OWNER_PASSWORD" -v app_pw="$ERP_APP_PASSWORD" <<'SQL'
CREATE ROLE erp_owner LOGIN PASSWORD :'owner_pw';
CREATE ROLE erp_app LOGIN PASSWORD :'app_pw';
CREATE DATABASE erp OWNER erp_owner;
SQL
