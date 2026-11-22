#!/usr/bin/env bash
# Starts Postgres, backend (:8080) and web (:3000) in this machine/container, bound to all interfaces.
# Logs: /tmp/erp-backend.log, /tmp/erp-frontend.log.  Stop: scripts/stop-local.sh
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
[ -f /opt/tools/env.sh ] && . /opt/tools/env.sh

service postgresql start >/dev/null 2>&1 || true
until pg_isready -q -h localhost; do sleep 1; done

echo "Building backend..."
(cd "$ROOT/backend" && mvn -q -B package -DskipTests)
nohup java -jar "$ROOT"/backend/target/erp-backend-*.jar > /tmp/erp-backend.log 2>&1 &
echo $! > /tmp/erp-backend.pid

echo "Building frontend..."
(cd "$ROOT/frontend" && { [ -d node_modules ] || npm ci --no-audit --no-fund; } && BACKEND_URL=http://localhost:8080 npm run build >/tmp/erp-frontend-build.log 2>&1)
(cd "$ROOT/frontend" && nohup npx next start -H 0.0.0.0 -p 3000 > /tmp/erp-frontend.log 2>&1 & echo $! > /tmp/erp-frontend.pid)

for i in $(seq 1 90); do curl -sf localhost:8080/actuator/health >/dev/null && break; sleep 2; done
for i in $(seq 1 30); do curl -sf -o /dev/null localhost:3000/login && break; sleep 1; done
echo "Backend:  $(curl -s localhost:8080/actuator/health)"
echo "Web:      http://$(hostname -I | awk '{print $1}'):3000  (or http://localhost:3000 if the port is published)"
