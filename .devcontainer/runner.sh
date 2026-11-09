#!/usr/bin/env bash
# Starts the runner on port 8081 (Codespaces forwards it). The Bearer token comes from RUNNER_TOKEN (a Codespaces secret, for example) or is created
# once and kept in ~/.apk-lab-runner-token, outside the repository. Extra settings pass through as environment variables, for example
#   RUNNER_ALLOW_PLUGINS=true bash .devcontainer/runner.sh
set -euo pipefail
cd "$(dirname "$0")/.."
token_file="$HOME/.apk-lab-runner-token"
if [ -z "${RUNNER_TOKEN:-}" ]; then
  if [ ! -s "$token_file" ]; then (umask 077; openssl rand -hex 24 > "$token_file"); fi
  RUNNER_TOKEN="$(cat "$token_file")"
fi
export RUNNER_TOKEN APK_LAB_RUNTIME_DIR="${APK_LAB_RUNTIME_DIR:-$PWD/vendor/apk-lab}"
[ -f "$APK_LAB_RUNTIME_DIR/build-info.json" ] || node sidecar/fetch-runtime.mjs
if [ -n "${CODESPACE_NAME:-}" ]; then url="https://${CODESPACE_NAME}-8081.${GITHUB_CODESPACES_PORT_FORWARDING_DOMAIN:-app.github.dev}"; else url="http://127.0.0.1:8081"; fi
cat <<INFO
Runner em http://127.0.0.1:8081 (dentro do codespace)${CODESPACE_NAME:+ e $url (porta encaminhada, privada por padrão)}
Token: ${RUNNER_TOKEN:0:6}… (inteiro em $( [ -s "$token_file" ] && echo "$token_file" || echo 'RUNNER_TOKEN' ))
Teste: bash .devcontainer/demo.sh caminho/do/app.apk
INFO
exec ./gradlew --no-daemon --console=plain :runner:run
