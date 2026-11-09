#!/usr/bin/env bash
# Runs once when the codespace is created: downloads the pinned APK Web Lab runtime (every file SHA-256 checked against sidecar/runtime.lock.json)
# and compiles the runner, so runner.sh starts in seconds.
set -euo pipefail
cd "$(dirname "$0")/.."
node --version && java -version 2>&1 | head -1
node sidecar/fetch-runtime.mjs
./gradlew --no-daemon :runner:classes -x test
echo "Pronto: bash .devcontainer/runner.sh inicia o runner na porta 8081."
