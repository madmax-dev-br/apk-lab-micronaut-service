# APK Lab Micronaut Service

**Documentação: https://madmax-dev-br.github.io/apk-lab-micronaut-service/** · [API](https://madmax-dev-br.github.io/apk-lab-micronaut-service/api.html) · [Receitas](https://madmax-dev-br.github.io/apk-lab-micronaut-service/cookbook.html) · [Segurança e limites](https://madmax-dev-br.github.io/apk-lab-micronaut-service/seguranca.html)

Rode APKs Android reais num servidor Java com o runtime do [APK Web Lab](https://madmax-dev-br.github.io/apk-browser-lab-site/). Dois serviços Micronaut 4 (Java 21):

- **runner**: cria sessões a partir de um APK e expõe tudo o que os Controles do site fazem: aparelho, rede (hosts permitidos, redirecionamento de host, offline, proxy), permissões e localização, overrides de método, Activities ignoradas, mocks de endpoint, limites de evento e de memória, modo tolerante/estrito; toque, digitação, rolagem, intents e deep links; recarregar a Activity atual (`POST /sessions/{id}/reload` com `recreate` ou `reopen`); a "Tela pronta" (`GET /sessions/{id}/ready`, `wait` com `ready`, `?waitReady=1` nas capturas, evento SSE `ready`); snapshot, limites de layout, árvore Compose, ViewModels; screenshots PNG da tela e de elementos; Logcat e log de rede por SSE; plugins no servidor; diagnóstico.
- **queue**: jobs declarativos (passos, inclusive `reload`, `waitReady` e `screenshot` com `waitReady`) e leases interativos com proxy da API do runner; prioridades, justiça entre clientes, capacidade lida do health dos runners, retentativas, cancelamento, resultados e artefatos, progresso por SSE, retenção, store em memória ou em arquivo.

A VM do lab é JavaScript + WebAssembly e não roda dentro da JVM: cada sessão do runner dirige um **sidecar Node** (`sidecar/vm-host.mjs`, Node 22+) que roda o Worker do runtime público num `worker_threads` Worker e fala JSON lines pelo stdin/stdout. O runner cuida de HTTP, autenticação, CORS, validação, limites, SSE e do ciclo de vida do processo (timeouts, limite de linha, crash com erro claro e restart). Detalhes em [Visão geral](https://madmax-dev-br.github.io/apk-lab-micronaut-service/#arquitetura).

A API (caminhos, corpos JSON, erros, eventos SSE, passos de job) é a mesma do [apk-lab-node-service](https://github.com/madmax-dev-br/apk-lab-node-service).

## Início rápido

Requisitos: JDK 21 e Node.js 22+.

```sh
./gradlew fetchRuntime                     # runtime fixado (sidecar/runtime.lock.json) em vendor/apk-lab, SHA-256 por arquivo
export RUNNER_TOKEN=$(openssl rand -hex 24)
./gradlew :runner:run                      # http://127.0.0.1:8081

curl -s -H "Authorization: Bearer $RUNNER_TOKEN" -F apk=@app.apk \
  -F 'config={"endpointMocks":[{"method":"GET","url":"https://api.exemplo.com/v1/perfil","status":200,"body":"{\"nome\":\"Ana\"}"}],"launch":true}' \
  http://127.0.0.1:8081/sessions
```

Fila: `QUEUE_CLIENTS="eu:<token>" QUEUE_RUNNERS=http://127.0.0.1:8081 QUEUE_RUNNER_TOKEN=$RUNNER_TOKEN ./gradlew :queue:run` (porta 8080). Com Docker: `docker compose up --build` (veja `docker-compose.yml`).

## Recarregar a tela e "Tela pronta"

- `POST /sessions/{id}/reload` com `{"mode": "recreate"}` (padrão): `Activity.recreate()`, estado salvo e ViewModels retidos ficam, como numa rotação. Com `{"mode": "reopen"}`: uma instância nova com o mesmo Intent, sem estado salvo e com o `ViewModelStore` limpo; o conteúdo carrega de novo. Responde o resumo da tela como as outras entradas.
- Todo snapshot traz `screenReady = {ready, activity, readySince, settledForMs, quietMs, waitingFor, fragments}`: pronta quando a Activity está em `onResume` com o primeiro quadro desenhado, os fragments androidx `RESUMED` e nada pendente (quadro, layout, mensagem da thread principal, rede) por 300 ms. `waitingFor` traz os motivos em português.
- `GET /sessions/{id}/ready`; `POST /sessions/{id}/wait` com `{"ready": true}` (sozinho ou com um seletor); `?waitReady=1&readyTimeoutMs=15000` em `screenshot.png` e na captura de elemento (cabeçalho `X-Screen-Ready`); o evento SSE `ready` em `/events` a cada virada; passos `reload`, `waitReady`, `wait` com `ready` e `screenshot` com `waitReady`. Tempo esgotado: 408 com os motivos.
- Plugins (o runtime de plugins do bundle): `screen.ready`, `lab.on('ready', fn)`, `await lab.waitReady({timeoutMs})`, e o modelo "Print na tela pronta" em `GET /plugin-templates`.

Receitas 16 ("Recarregar a tela") e 17 ("Esperar a tela ficar pronta e tirar o print") em [Receitas](https://madmax-dev-br.github.io/apk-lab-micronaut-service/cookbook.html#r16).

## Estrutura

```
api/        records compartilhados (config da sessão, seletores, corpos, jobs e passos, erros)
runner/     serviço runner (Micronaut): sessões, sidecar, SSE, auth, CORS
queue/      serviço fila (Micronaut): agendamento, jobs, leases, proxy, store
sidecar/    vm-host.mjs (protocolo), lib/ (sessão, shim do Worker, screenshots, plugins, validação),
            fetch-runtime.mjs, make-runtime-lock.mjs, runtime.lock.json
docker/     Dockerfiles (runner: JDK -> JRE + Node; fila: JDK -> JRE)
docs/       site de documentação (GitHub Pages)
```

## Testes

```sh
./gradlew test
```

Unitários (sem rede, sem APKs): fila (prioridade, passos de recarregar e de tela pronta, justiça, capacidade, retentativas, cancelamento, timeout, leases, retenção, store em arquivo), validação, autenticação, CORS, plugins desligados por padrão e o protocolo do sidecar com um sidecar falso. Os de ponta a ponta rodam quando `APK_LAB_RUNTIME_DIR` (um runtime) e `APK_LAB_TEST_APK_DIR` (APKs sintéticos de teste) estão definidos; o CI roda só os unitários.

## Licença

MIT para o código deste repositório. O runtime baixado em `vendor/apk-lab` não faz parte do repositório e mantém as próprias licenças (`vendor/apk-lab/THIRD_PARTY.md`).

---

## English summary

Run real Android APKs on a Java server with the [APK Web Lab](https://madmax-dev-br.github.io/apk-browser-lab-site/) runtime. Two Micronaut 4 services (Java 21): a **runner** that loads an APK into a session and exposes every feature of the lab's controls (device settings, network allow list / host redirects / offline / proxy, permissions and location, method return overrides, ignored Activities, endpoint mocks, event and memory limits, tolerant/strict mode, input, intents, reloading the current Activity (`POST /sessions/{id}/reload` with mode `recreate` — saved state and retained ViewModels kept — or `reopen` — a fresh instance with the same Intent), "screen ready" (`snapshot.screenReady`: the Activity resumed with its first frame drawn, its fragments RESUMED, nothing pending for 300 ms; `GET /sessions/{id}/ready`, `wait` with `ready: true` alone or with a selector, `?waitReady=1` on screenshots, an SSE `ready` event on each flip, queue steps `reload` / `waitReady` / `screenshot` with `waitReady`, and `screen.ready`, `lab.on('ready')`, `lab.waitReady()` in plugins plus the "Print na tela pronta" template), snapshots, layout bounds, Compose tree, ViewModels, PNG screenshots, Logcat and network log over SSE, server-side plugins, diagnostics), and a **queue** with declarative jobs and interactive leases (priorities, per-client fairness, runner capacity from health checks, retries, cancel, results and artifacts, SSE progress, retention, memory or file store).

The lab's VM is JavaScript + WebAssembly with Worker semantics and cannot run inside the JVM as is, so each runner session drives a **Node.js sidecar** process (`sidecar/vm-host.mjs`, Node 22+) that runs the public runtime's Worker in `worker_threads` and talks JSON lines over stdin/stdout, with timeouts, line-size limits and crash handling. GraalJS/GraalWasm is a possible future direction, not implemented. The runtime is downloaded from the public site at a pinned commit with per-file SHA-256 verification (`./gradlew fetchRuntime`). The REST API matches [apk-lab-node-service](https://github.com/madmax-dev-br/apk-lab-node-service). Plugins are disabled by default (`RUNNER_ALLOW_PLUGINS=true` for trusted users only: worker_threads are not a security sandbox). Unimplemented Android APIs fail with an explicit error; protected native code is never faked or bypassed. Full documentation (Portuguese): https://madmax-dev-br.github.io/apk-lab-micronaut-service/
