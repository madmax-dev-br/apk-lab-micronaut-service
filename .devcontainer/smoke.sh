#!/usr/bin/env bash
# CI check of the devcontainer (.github/workflows/devcontainer.yml): the setup ran, the runner starts with the pinned runtime and answers /health.
set -euo pipefail
cd "$(dirname "$0")/.."
export RUNNER_TOKEN="ci-$(openssl rand -hex 12)"
bash .devcontainer/runner.sh > /tmp/runner.log 2>&1 &
for i in $(seq 1 90); do curl -sf http://127.0.0.1:8081/health > /tmp/health.json 2>/dev/null && break; sleep 2; done
cat /tmp/health.json || { tail -50 /tmp/runner.log; exit 1; }
expected="$(node -e 'console.log(require("./sidecar/runtime.lock.json").revision)')"
node -e 'const h=require("/tmp/health.json"); if (!h.ok || h.runtimeRevision !== process.argv[1]) { console.error("unexpected health", h); process.exit(1); } console.log("runner ok, runtime", h.runtimeRevision)' "$expected"
[ "$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8081/sessions)" = "401" ] || { echo "the API must ask for the token"; exit 1; }
echo "devcontainer smoke ok"
