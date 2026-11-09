// A fake sidecar for the runner's protocol tests: it speaks the JSON-lines protocol of sidecar/vm-host.mjs without the APK Web Lab runtime.
// Behaviour is picked by the request: tap { selector: { text } } with "crash" exits, "hang" never answers, "huge" writes an oversized line,
// "garbage" writes a non-JSON line, "missing" answers 404 element-not-found. FAKE_SIDECAR_FAIL_START=1 exits before "ready".
import { createInterface } from 'node:readline';

const send = message => process.stdout.write(`${JSON.stringify(message)}\n`);
console.log = (...args) => process.stderr.write(`${args.join(' ')}\n`);
if (process.env.FAKE_SIDECAR_FAIL_START === '1') { process.stderr.write('fake: refusing to start\n'); process.exit(2); }

let session = null, config = {}, logTimer = null, logSeq = 0, loads = 0;
const subscriptions = { logcat: false, netlog: false, events: false, plugins: false };
const describe = () => ({ id: session?.sessionId ?? null, state: 'ready', packageName: 'test.fake', activity: 'test.fake.MainActivity', metrics: { loads }, plugins: config.plugins?.length ?? 0 });
const fail = (status, code, message, details) => { const error = new Error(message); Object.assign(error, { status, code, details }); throw error; };

const ops = {
  init: args => { session = args; return { sessionId: args.sessionId, policy: args.policy, limits: args.limits }; },
  load: args => { loads++; config = args.config ?? {}; if (config.device?.screen === 'invalid-preset') fail(400, 'bad-request', 'config.device.screen: unsupported value "invalid-preset"'); return { session: describe(), inventory: { packageName: 'test.fake' }, config, snapshot: null, apkPath: args.apkPath }; },
  describe: () => describe(),
  defaults: args => ({ config: { missingApis: 'primitives', network: { allowedHosts: args.policy?.allowedHosts ?? [] }, raster: 'full' }, policy: args.policy, limits: args.limits }),
  initArgs: () => session,
  getConfig: () => config,
  updateConfig: args => { config = { ...config, ...args.config }; return { applied: Object.keys(args.config ?? {}).map(key => ({ key })), restartRequired: [], running: true, config }; },
  restart: args => { loads++; if (args.config) config = { ...config, ...args.config }; return { session: describe() }; },
  launch: args => ({ launchMs: 1, snapshot: { activity: args.activity ?? 'test.fake.MainActivity' } }),
  tap: args => {
    const text = args.selector?.text;
    if (text === 'crash') { process.stderr.write('fake: crashing on request\n'); process.exit(3); }
    if (text === 'hang') return new Promise(() => {});
    if (text === 'huge') { process.stdout.write(`{"id":${args.__id},"ok":true,"result":"${'x'.repeat(3 * 1024 * 1024)}"}\n`); return undefined; }
    if (text === 'garbage') { process.stdout.write('this is not json\n'); return new Promise(() => {}); }
    if (text === 'missing') fail(404, 'element-not-found', 'No element on the screen matches {"text":"missing"}', { activity: 'test.fake.MainActivity' });
    return { via: 'semantics', element: { key: 'c:1:2' }, args };
  },
  wait: args => args.ready === true && args.timeoutMs === 1 ? fail(408, 'ready-timeout', 'Timed out after 1 ms waiting for the screen to be ready (waiting for: quadro pendente)', { waitingFor: ['quadro pendente'] })
    : ({ element: args.selector ? { key: 'c:1:2' } : null, waitedMs: 0, ready: args.ready === true ? true : null, args }),
  reload: args => {
    if (subscriptions.events) setTimeout(() => send({ event: 'session', name: 'ready', data: { seq: 2, ready: true, activity: 'test.fake.MainActivity', waitingFor: [] } }), 20);
    return { mode: args.mode, snapshot: { activity: 'test.fake.MainActivity', screenReady: { ready: false, waitingFor: ['mensagens da thread principal para processar'] } } };
  },
  ready: () => ({ ready: true, current: true, activity: 'test.fake.MainActivity', screenReady: { ready: true, waitingFor: [], fragments: [] } }),
  layoutBounds: () => ({ activity: 'test.fake.MainActivity', elements: [{ key: 'c:1:2', name: 'Button', bounds: { left: 0, top: 0, right: 10, bottom: 10 } }] }),
  screenshot: args => ({ pngBase64: 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII=', width: 1, height: 1, notes: { approximatedText: 0 },
    ...(args.waitReady ? { ready: { ready: true, waitedMs: args.readyTimeoutMs, activity: 'test.fake.MainActivity' } } : {}) }),
  setPlugins: args => ({ plugins: args.plugins.map(p => ({ id: p.id, name: p.name, enabled: p.enabled !== false })) }),
  subscribe: args => {
    for (const key of Object.keys(subscriptions)) if (typeof args[key] === 'boolean') subscriptions[key] = args[key];
    clearInterval(logTimer); logTimer = null;
    if (subscriptions.logcat) logTimer = setInterval(() => { const seq = logSeq++; send({ event: 'logcat', data: [{ seq, level: 'I', tag: 'Fake', message: `line ${seq}` }] }); }, 50);
    return { ...subscriptions, logcatStreaming: subscriptions.logcat };
  },
  subscriptions: () => subscriptions,
  env: () => Object.keys(process.env).sort(),
  stats: () => ({ memory: process.memoryUsage() }),
  shutdown: () => { setTimeout(() => process.exit(0), 10); return { closed: true }; },
};

send({ event: 'ready', protocol: 1, revision: 'fake-revision', node: process.version, pid: process.pid });
const input = createInterface({ input: process.stdin });
input.on('line', async line => {
  const { id, op, args } = JSON.parse(line);
  try {
    const fn = ops[op]; if (!fn) fail(400, 'bad-request', `unknown operation ${op}`);
    const result = await fn({ ...(args ?? {}), __id: id });
    if (result !== undefined) send({ id, ok: true, result });
  } catch (error) {
    send({ id, ok: false, error: { status: error.status ?? 500, code: error.code ?? 'internal', message: error.message, ...(error.details ? { details: error.details } : {}) } });
  }
});
input.on('close', () => process.exit(0));
