#!/usr/bin/env bash
for f in /tmp/erp-backend.pid /tmp/erp-frontend.pid; do [ -f "$f" ] && kill "$(cat "$f")" 2>/dev/null; rm -f "$f"; done
pkill -f "erp-backend-.*\.jar" 2>/dev/null; pkill -f "next-server" 2>/dev/null; echo stopped
