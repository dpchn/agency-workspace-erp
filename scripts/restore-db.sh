#!/usr/bin/env bash
# Restore a backup made by backup-db.sh:  ./scripts/restore-db.sh backups/erp-YYYYMMDD-HHMMSS.dump
# WARNING: replaces ALL current data.
set -euo pipefail
cd "$(dirname "$0")/.."
FILE="${1:?usage: restore-db.sh <file.dump>}"
read -r -p "This will REPLACE all data in the erp database with $FILE. Type 'restore' to continue: " ok
[ "$ok" = "restore" ] || { echo "aborted"; exit 1; }
docker compose stop backend frontend
docker compose exec -T db psql -U postgres -c "DROP DATABASE IF EXISTS erp WITH (FORCE);" -c "CREATE DATABASE erp OWNER erp_owner;"
# Restore as the superuser so original owners (erp_owner) and grants are kept and row-level security,
# which is forced even for the owner, does not block loading the data.
docker compose exec -T db pg_restore -U postgres -d erp < "$FILE"
docker compose start backend frontend
echo "restored from $FILE"
