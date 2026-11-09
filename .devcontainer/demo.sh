#!/usr/bin/env bash
# Quick end-to-end check against the running runner: creates a session with the APK, launches it, waits for "Tela pronta" and saves a screenshot.
#   bash .devcontainer/demo.sh app.apk [saida.png]
# (Drag the APK into the codespace's file explorer first; *.apk is git-ignored, so it never gets committed.)
set -euo pipefail
apk="${1:?uso: bash .devcontainer/demo.sh app.apk [saida.png]}"; out="${2:-tela.png}"
base="${RUNNER_URL:-http://127.0.0.1:8081}"; token="${RUNNER_TOKEN:-$(cat "$HOME/.apk-lab-runner-token" 2>/dev/null || true)}"
[ -n "$token" ] || { echo "sem token: inicie o runner com bash .devcontainer/runner.sh"; exit 1; }
auth="Authorization: Bearer $token"
curl -sf "$base/health" > /dev/null || { echo "o runner não responde em $base: bash .devcontainer/runner.sh"; exit 1; }
created="$(mktemp)"; trap 'rm -f "$created"' EXIT
curl -sf -H "$auth" -F "apk=@$apk" -F 'config={"launch":true}' "$base/sessions" -o "$created"
id="$(node -e 'const d=JSON.parse(require("fs").readFileSync(process.argv[1], "utf8")); console.log(d.session.id)' "$created")"
echo "sessão $id: $(node -e 'const d=JSON.parse(require("fs").readFileSync(process.argv[1], "utf8")); console.log(d.snapshot?.activity ?? "-")' "$created")"
curl -sf -X POST -H "$auth" -H 'content-type: application/json' -d '{"ready":true,"timeoutMs":20000}' "$base/sessions/$id/wait" > /dev/null || echo "a tela não ficou pronta em 20 s (seguindo com o print assim mesmo)"
curl -sf -H "$auth" -o "$out" "$base/sessions/$id/screenshot.png"
echo "print salvo em $out"
echo "a sessão continua aberta: $base/sessions/$id  (apague com: curl -X DELETE -H \"Authorization: Bearer …\" $base/sessions/$id)"
