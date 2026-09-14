#!/usr/bin/env bash
set -euo pipefail

smoke_log="$(mktemp)"
PORT=5174 HOSTNAME=127.0.0.1 BACKEND_URL=http://127.0.0.1:9 \
  node .next/standalone/server.js >"${smoke_log}" 2>&1 &
server_pid=$!
trap 'kill "${server_pid}" 2>/dev/null || true; rm -f "${smoke_log}"' EXIT

for _ in {1..40}; do
  if curl --fail --silent http://127.0.0.1:5174/ >/tmp/devpilot-root.html; then
    break
  fi
  if ! kill -0 "${server_pid}" 2>/dev/null; then
    cat "${smoke_log}"
    exit 1
  fi
  sleep 0.1
done

curl --fail --silent http://127.0.0.1:5174/login >/tmp/devpilot-login.html
curl --fail --silent http://127.0.0.1:5174/workspace/dashboard >/tmp/devpilot-workspace.html
proxy_status="$(curl --silent --output /tmp/devpilot-proxy.json --write-out '%{http_code}' http://127.0.0.1:5174/api/v1/repositories)"

test "${proxy_status}" = "502"
grep -q 'Backend is unavailable' /tmp/devpilot-proxy.json
grep -q 'DevPilot' /tmp/devpilot-root.html
grep -q 'DevPilot' /tmp/devpilot-login.html
grep -q 'DevPilot' /tmp/devpilot-workspace.html

echo "Frontend route and proxy smoke checks passed"
