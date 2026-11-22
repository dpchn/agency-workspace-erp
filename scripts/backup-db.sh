#!/usr/bin/env bash
# Daily backup: ./scripts/backup-db.sh [backup-dir]    (cron: 30 2 * * * cd /opt/erp && ./scripts/backup-db.sh)
# Writes a compressed pg_dump of the whole ERP database and keeps the last 14.
# Runs as the postgres superuser: tenant isolation (forced RLS) blocks dumps by the table owner.
set -euo pipefail
cd "$(dirname "$0")/.."
DIR="${1:-./backups}"
mkdir -p "$DIR"
FILE="$DIR/erp-$(date +%Y%m%d-%H%M%S).dump"
docker compose exec -T db pg_dump -U postgres -d erp -Fc > "$FILE"
echo "backup written: $FILE ($(du -h "$FILE" | cut -f1))"
ls -1t "$DIR"/erp-*.dump | tail -n +15 | xargs -r rm --
