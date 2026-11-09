# APK Lab Micronaut Service

**Documentação: https://madmax-dev-br.github.io/apk-lab-micronaut-service/** · [API](https://madmax-dev-br.github.io/apk-lab-micronaut-service/api.html) · [Receitas](https://madmax-dev-br.github.io/apk-lab-micronaut-service/cookbook.html) · [Segurança e limites](https://madmax-dev-br.github.io/apk-lab-micronaut-service/seguranca.html)

Rode APKs Android reais num servidor Java com o runtime do [APK Web Lab](https://madmax-dev-br.github.io/apk-browser-lab-site/). Dois serviços Micronaut 4 (Java 21):

- **runner**: cria sessões a partir de um APK e expõe tudo o que os Controles do site fazem: aparelho, rede (hosts permitidos, redirecionamento de host, offline, proxy), permissões e localização, overrides de método, Activities ignoradas, mocks de endpoint, limites de evento e de memória, modo tolerante/estrito; toque, digitação, rolagem, intents e deep links; recarregar a Activity atual (`POST /sessions/{id}/reload` com `recreate` ou `reopen`); a "Tela pronta" (`GET /sessions/{id}/ready`, `wait` com `ready`, `?waitReady=1` nas capturas, evento SSE `ready`); snapshot, limites de layout, árvore Compose, ViewModels; estado fora da tela (campos estáticos de classes já inicializadas, classes pelo nome, objetos vivos de um tipo e quem os segura); screenshots PNG da tela e de elementos; Logcat e log de rede por SSE; plugins no servidor; diagnóstico.
- **queue**: jobs declarativos (passos, inclusive `reload`, `waitReady` e `screenshot` com `waitReady`) e leases interativos com proxy da API do runner; prioridades, justiça entre clientes, capacidade lida do health dos runners, retentativas, cancelamento, resultados e artefatos, progresso por SSE, retenção, store em memória ou em arquivo.

A VM do lab é JavaScript + WebAssembly e não roda dentro da JVM: cada sessão do runner dirige um **sidecar Node** (`sidecar/vm-host.mjs`, Node 22+) que roda o Worker do runtime público num `worker_threads` Worker e fala JSON lines pelo stdin/stdout. O runner cuida de HTTP, autenticação, CORS, validação, limites, SSE e do ciclo de vida do processo (timeouts, limite de linha, crash com erro claro e restart). Detalhes em [Visão geral](https://madmax-dev-br.github.io/apk-lab-micronaut-service/#arquitetura).

A API (caminhos, corpos JSON, erros, eventos SSE, passos de job) é a mesma do [apk-lab-node-service](https://github.com/madmax-dev-br/apk-lab-node-service).

## Testar no GitHub Codespaces

[![Abrir no GitHub Codespaces](https://github.com/codespaces/badge.svg)](https://codespaces.new/madmax-dev-br/apk-lab-micronaut-service?quickstart=1)

O codespace já vem com Java 21 e Node 22, baixa o runtime fixado (SHA-256 conferido) e compila o runner ao ser criado (`.devcontainer/`). Depois, no terminal:

```sh
bash .devcontainer/runner.sh               # runner na porta 8081; token em ~/.apk-lab-runner-token (ou o secret RUNNER_TOKEN)
bash .devcontainer/demo.sh app.apk         # sessão + "Tela pronta" + print em tela.png (arraste o APK para o explorador antes)
```

A porta 8081 é encaminhada como `https://<codespace>-8081.app.github.dev`, **privada** por padrão: abre só com a sua conta do GitHub. Para chamar da sua máquina, use `gh codespace ports forward 8081:8081` (vira `http://127.0.0.1:8081`) ou torne a porta pública (o Bearer token continua obrigatório). Máquina recomendada: 4 núcleos / 16 GB. O codespace é seu: o APK e os prints ficam nele (`*.apk` é ignorado pelo git) e somem quando você o apaga. O workflow `Devcontainer (Codespaces)` monta o mesmo contêiner no CI e confere que o runner sobe.

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

## Padrões iguais ao site

Runtime fixado: revisão `5eb77725df72` (commit `6c5fe6b2` do site). Uma sessão criada sem config se comporta como o site para um visitante novo; `GET /defaults` mostra a config padrão e a resposta de `POST /sessions` traz a config com os padrões preenchidos.

| | Site (visitante novo) | Runner | |
|---|---|---|---|
| Dispositivo | phone, retrato, fonte 1, `en-US`, tema claro, 24 h, fuso do host, animações 1×, bateria 80 % sem carregar, online, gestos | igual (`sanitizeSettings` do bundle) | |
| Rede | `allowedHosts ["*"]`, sem relay, sem redirecionamentos | igual | alvos privados recusados (abaixo) |
| Memória da VM | automática: `navigator.deviceMemory` | igual: o shim do Worker define `navigator.deviceMemory` como o Chrome nesta máquina | 8 GiB ou mais → 1024 MiB = 671.088 objetos, heap do Worker 3072 MB |
| APIs ausentes | `primitives` | `primitives` | antes era `strict` |
| Encerrar no crash / tempo por evento / Logcat | não / 25 s / sob demanda | `strictCrash: false` / `eventSeconds: 0` / `on-demand` | |
| Dados do app | mantidos | `appData.keep: true` em `data/app-data`, por `X-Client-Id` | `RUNNER_APP_DATA_DIR=off` desliga |
| Resolução no movimento | 0,5, tela nova reduzida | `rasterScale: 0.5`, `rasterNavigation: true` | valem com `raster: "progressive"`, na carga e ao vivo |
| WebView | ligado | **desligado** | exceção: não há navegador no servidor |
| Raster | progressivo | **`full`** | exceção: uma captura nunca pega um quadro reduzido |
| Diálogos de permissão | o usuário toca | **`dismiss`** | exceção: não há quem toque (`permissions.dialogs`) |

- **Memória:** o `heapLimitFor` do lab usa `navigator.deviceMemory`, que o Node não tem; sem ele a VM caía em 262.144 objetos (tela branca, 81 % do limite ocupado, coletas de segundos num app real). Agora o shim define o valor como o Chrome (RAM em GiB arredondada para baixo a uma potência de 2, entre 0,25 e 8). `GET /health` e cada sessão trazem `memory: { budgetMiB, maxHeapObjects, source, deviceMemory, workerHeapMb }`.
- **Rede aberta, sem a rede interna:** toda troca HTTP da VM resolve o nome antes e é recusada quando algum endereço é loopback, privado (RFC 1918, fc00::/7), link-local (169.254.0.0/16, inclusive metadados da nuvem, fe80::/10) ou não especificado, inclusive em cada salto de redirecionamento e em `hostMap`, a menos que `RUNNER_ALLOW_PRIVATE_NETWORK=true`. O app recebe uma `IOException` que nomeia a política. Mocks respondem antes de qualquer rede. `RUNNER_ALLOWED_HOSTS` (padrão `*`; `none` = só mocks) limita o que uma sessão pode liberar.
- Variáveis novas ou com padrão novo: `RUNNER_ALLOWED_HOSTS=*`, `RUNNER_ALLOW_PRIVATE_NETWORK=false`, `RUNNER_APP_DATA_DIR=data/app-data`. Tabela completa em [Configuração](https://madmax-dev-br.github.io/apk-lab-micronaut-service/#configuracao).

## Recarregar a tela e "Tela pronta"

- `POST /sessions/{id}/reload` com `{"mode": "recreate"}` (padrão): `Activity.recreate()`, estado salvo e ViewModels retidos ficam, como numa rotação. Com `{"mode": "reopen"}`: uma instância nova com o mesmo Intent, sem estado salvo e com o `ViewModelStore` limpo; o conteúdo carrega de novo. Responde o resumo da tela como as outras entradas.
- Todo snapshot traz `screenReady = {ready, activity, readySince, settledForMs, quietMs, waitingFor, fragments}`: pronta quando a Activity está em `onResume` com o primeiro quadro desenhado, os fragments androidx `RESUMED` e nada pendente (quadro, layout, mensagem da thread principal, rede) por 300 ms. `waitingFor` traz os motivos em português.
- `GET /sessions/{id}/ready`; `POST /sessions/{id}/wait` com `{"ready": true}` (sozinho ou com um seletor); `?waitReady=1&readyTimeoutMs=15000` em `screenshot.png` e na captura de elemento (cabeçalho `X-Screen-Ready`); o evento SSE `ready` em `/events` a cada virada; passos `reload`, `waitReady`, `wait` com `ready` e `screenshot` com `waitReady`. Tempo esgotado: 408 com os motivos.
- Plugins (o runtime de plugins do bundle): `screen.ready`, `lab.on('ready', fn)`, `await lab.waitReady({timeoutMs})`, e o modelo "Print na tela pronta" em `GET /plugin-templates`.

Receitas 16 ("Recarregar a tela") e 17 ("Esperar a tela ficar pronta e tirar o print") em [Receitas](https://madmax-dev-br.github.io/apk-lab-micronaut-service/cookbook.html#r16).

## Estado fora da tela

As mesmas consultas somente leitura que os plugins do site têm (`lab.classes`, `lab.statics`, `lab.staticField`, `lab.instances`, `lab.referrers`), para o estado que não está num ViewModel da tela: um `object` Kotlin, um companion, um singleton, um contêiner de injeção de dependências.

| | |
|---|---|
| `GET /sessions/{id}/classes?match=&limit=&initialized=` | classes do APK pelo nome ou `/regex/` (`match` obrigatório: 400 sem ele) |
| `POST /sessions/{id}/statics` `{className, options?}` | campos estáticos de uma classe que o app já inicializou; uma classe ainda não inicializada responde `status: "not-initialized"` (a consulta nunca roda o `<clinit>`) |
| `POST /sessions/{id}/static-field` `{className, path, options?}` | um valor pelo caminho a partir de um campo estático (409 numa classe não inicializada) |
| `POST /sessions/{id}/instances` `{className, subclasses?, limit?, after?, maxPages?}` | objetos vivos de um tipo |
| `POST /sessions/{id}/referrers` `{handle, limit?, after?, maxPages?}` | campos estáticos e objetos que seguram um objeto (`via`: o campo) |

`path` em `/field` e `/static-field` é um texto com pontos ou um array de partes (para uma chave de mapa com pontos). `instances` e `referrers` varrem o heap nas páginas curtas do runtime e juntam páginas até `limit` (50, no máximo 500) ou `maxPages` (100, no máximo 500): `{items, scanned, done, next, pages}`, e `next` vai em `after` para continuar. Erros: 400 `bad-class` / `bad-args` / `bad-path`, 404 `no-class`, 409 `not-initialized`, 410 `collected`. Passos da fila: `classes`, `statics`, `staticField`, `instances`, `referrers`. Receita 18 em [Receitas](https://madmax-dev-br.github.io/apk-lab-micronaut-service/cookbook.html#r18).

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

Unitários (sem rede, sem APKs; inclui `node --test sidecar/test/`, os testes do sidecar): padrões iguais ao site (memória, rede, dados do app, raster), fila (prioridade, passos de recarregar, de tela pronta e de estado fora da tela, justiça, capacidade, retentativas, cancelamento, timeout, leases, retenção, store em arquivo), validação, autenticação, CORS, plugins desligados por padrão e o protocolo do sidecar com um sidecar falso. Os de ponta a ponta rodam quando `APK_LAB_RUNTIME_DIR` (um runtime) e `APK_LAB_TEST_APK_DIR` (APKs sintéticos de teste) estão definidos; o CI roda só os unitários.

## Licença

MIT para o código deste repositório. O runtime baixado em `vendor/apk-lab` não faz parte do repositório e mantém as próprias licenças (`vendor/apk-lab/THIRD_PARTY.md`).

---

## English summary

Run real Android APKs on a Java server with the [APK Web Lab](https://madmax-dev-br.github.io/apk-browser-lab-site/) runtime. Two Micronaut 4 services (Java 21): a **runner** that loads an APK into a session and exposes every feature of the lab's controls (device settings, network allow list / host redirects / offline / proxy, permissions and location, method return overrides, ignored Activities, endpoint mocks, event and memory limits, tolerant/strict mode, input, intents, reloading the current Activity (`POST /sessions/{id}/reload` with mode `recreate` — saved state and retained ViewModels kept — or `reopen` — a fresh instance with the same Intent), "screen ready" (`snapshot.screenReady`: the Activity resumed with its first frame drawn, its fragments RESUMED, nothing pending for 300 ms; `GET /sessions/{id}/ready`, `wait` with `ready: true` alone or with a selector, `?waitReady=1` on screenshots, an SSE `ready` event on each flip, queue steps `reload` / `waitReady` / `screenshot` with `waitReady`, and `screen.ready`, `lab.on('ready')`, `lab.waitReady()` in plugins plus the "Print na tela pronta" template), snapshots, layout bounds, Compose tree, ViewModels, state outside the screen (`GET /sessions/{id}/classes`, `POST /sessions/{id}/statics|static-field|instances|referrers`: static fields of initialized classes, classes by name, live instances of a type and who holds an object, the heap scanned in the runtime's short pages; queue steps `classes`, `statics`, `staticField`, `instances`, `referrers`), PNG screenshots, Logcat and network log over SSE, server-side plugins, diagnostics), and a **queue** with declarative jobs and interactive leases (priorities, per-client fairness, runner capacity from health checks, retries, cancel, results and artifacts, SSE progress, retention, memory or file store).

The lab's VM is JavaScript + WebAssembly with Worker semantics and cannot run inside the JVM as is, so each runner session drives a **Node.js sidecar** process (`sidecar/vm-host.mjs`, Node 22+) that runs the public runtime's Worker in `worker_threads` and talks JSON lines over stdin/stdout, with timeouts, line-size limits and crash handling. GraalJS/GraalWasm is a possible future direction, not implemented. The runtime is downloaded from the public site at a pinned commit with per-file SHA-256 verification (`./gradlew fetchRuntime`). The REST API matches [apk-lab-node-service](https://github.com/madmax-dev-br/apk-lab-node-service). Plugins are disabled by default (`RUNNER_ALLOW_PLUGINS=true` for trusted users only: worker_threads are not a security sandbox). Unimplemented Android APIs fail with an explicit error; protected native code is never faked or bypassed. Defaults equal the site's for a new visitor (`GET /defaults`): every public host allowed, missing APIs `primitives`, app data kept in `data/app-data` per `X-Client-Id` (`RUNNER_APP_DATA_DIR=off` disables it), memory "automatic" through `navigator.deviceMemory`, which the sidecar's Worker shim defines as Chrome would (RAM in GiB rounded down to a power of two, clamped to [0.25, 8]; 8 GiB or more gives 1024 MiB = 671,088 objects and a 3072 MB Worker heap; `memory` in `/health` and each session). Server exceptions: WebView off, `raster: "full"` (with `progressive`, `rasterScale` and `rasterNavigation` apply at load and live), permission dialogs dismissed. Every HTTP exchange of the app resolves its host first and is refused when an address is loopback, private, link-local (cloud metadata included) or unspecified unless `RUNNER_ALLOW_PRIVATE_NETWORK=true`. Runtime revision `5eb77725df72`. Rendering is CPU-only on the server (the site's opt-in GPU renderer needs WebGL in a browser). Full documentation (Portuguese): https://madmax-dev-br.github.io/apk-lab-micronaut-service/
