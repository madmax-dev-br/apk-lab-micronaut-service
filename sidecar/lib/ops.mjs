// The operations of the sidecar protocol: one per runner endpoint. Each one validates its arguments, calls the session (lib/session.mjs) and returns plain JSON.
// HTTP concerns (auth, CORS, multipart, SSE, capacity) live in the Micronaut runner; this file only knows sessions.
import { HttpError, badRequest } from './errors.mjs';
import { validateConfig, withDefaults, sessionDefaults } from './config.mjs';
import { hostDeviceMemory, memoryReport, workerHeapMb } from './memory.mjs';
import { Session, publicSnapshot, inventorySummary } from './session.mjs';
import { reloadModeFrom, readyFlagFrom, screenReadyOut, READY_TIMEOUT_MS, MAX_WAIT_MS, SERVICE_PLUGIN_TEMPLATES } from './readiness.mjs';

const SELECTOR_KEYS = ['key', 'testTag', 'text', 'textContains', 'contentDescription', 'androidId', 'viewId', 'kind', 'clickable', 'index'];
const INPUT_TYPES = ['click', 'input', 'check', 'focus', 'press', 'scroll', 'scrollby', 'compose-action', 'pointer'];
const INTENT_KEYS = ['action', 'data', 'category', 'categories', 'extras', 'type', 'component', 'flags'];
const num = (value, fallback, min, max) => { if (value === null || value === undefined || value === '') return fallback; const n = Number(value); if (!Number.isFinite(n)) throw badRequest(`not a number: ${value}`); return Math.min(max, Math.max(min, n)); };
const conflict = (code, message) => new HttpError(409, code, message);
/** A path of /field and /static-field: a dotted string, or an array of parts when a part has dots (a map key). */
export const isPath = path => path === undefined || path === null || typeof path === 'string' || (Array.isArray(path) && path.length <= 32 && path.every(part => typeof part === 'string' || typeof part === 'number'));
/** The runner's policy when init does not say otherwise: every public host (private targets refused unless allowPrivateNetwork), no plugins. */
const DEFAULT_POLICY = Object.freeze({ allowedHosts: ['*'], allowPrivateNetwork: false, pluginsAllowed: false, maxPlugins: 4, maxPluginCodeBytes: 256 * 1024, maxMemoryMiB: 2048 });

function selectorFrom(args) {
  const source = args?.selector ?? (args?.key !== undefined ? { key: args.key } : null);
  if (!source || typeof source !== 'object' || Array.isArray(source)) return null;
  const unknown = Object.keys(source).filter(key => !SELECTOR_KEYS.includes(key)); if (unknown.length) throw badRequest(`selector: unknown keys ${unknown.join(', ')}`);
  for (const [key, value] of Object.entries(source)) if (typeof value === 'string' && value.length > 2000) throw badRequest(`selector.${key} too long`);
  const clean = {}; for (const [key, value] of Object.entries(source)) if (value !== null && value !== undefined) clean[key] = value;
  return Object.keys(clean).length ? clean : null;
}
function intentFrom(body) {
  if (!body || typeof body !== 'object' || Array.isArray(body)) throw badRequest('intent body must be an object');
  const clean = {}; for (const [key, value] of Object.entries(body)) if (value !== null && value !== undefined) clean[key] = value;
  const unknown = Object.keys(clean).filter(key => !INTENT_KEYS.includes(key)); if (unknown.length) throw badRequest(`intent: unknown keys ${unknown.join(', ')}`);
  if (JSON.stringify(clean).length > 64 * 1024) throw badRequest('intent larger than 64 KiB');
  return clean;
}

/** What most clients want after an operation: the screen in a compact form (no rasters, no trace). Same shape as the Node runner's summary. */
export function summary(session) {
  const s = session.snapshot; if (!s) return null;
  const offset = session.contentOffset();
  const views = session.bundle.flattenViews(s).filter(v => v.bounds).map(v => ({ id: v.id, parentId: v.parentId, depth: v.depth, type: v.type, kind: v.kind, text: v.text, contentDescription: v.contentDescription, androidId: v.androidId, visible: v.visible, clickable: v.clickable, compose: v.compose, bounds: v.bounds, window: v.window }));
  return { seq: session.snapshotSeq, activity: s.activityName ?? null, closed: !!s.closed, canGoBack: !!s.canGoBack, navigation: s.navigation ?? null, systemBars: s.systemBars ?? null, contentOffset: offset,
    views, composeSemantics: s.composeSemantics ?? null, deepLink: s.deepLink ?? null, droppedEvent: s.droppedEvent ?? null, droppedComposeAction: s.droppedComposeAction ?? null,
    pendingEventDelay: s.pendingEventDelay ?? null, screenReady: screenReadyOut(s), vmHealth: s.vmHealth ? { liveObjects: s.vmHealth.liveObjects, limit: s.vmHealth.limit, collections: s.vmHealth.collections, oomCount: s.vmHealth.oomCount, lastEventMs: s.vmHealth.lastEventMs } : null,
    debugging: { methodOverrides: s.methodOverrides ?? [], endpointMocks: s.endpointMocks ?? [], hostMap: s.hostMap ?? [], ignoredActivities: s.ignoredActivities ?? [] } };
}
const png = shot => ({ pngBase64: shot.png.toString('base64'), width: shot.width, height: shot.height, notes: shot.notes, ...(shot.element ? { element: shot.element } : {}),
  ...(shot.ready ? { ready: { ready: !!shot.ready.screenReady?.ready, waitedMs: shot.ready.waitedMs, activity: shot.ready.screenReady?.activity ?? null } } : {}) });
/** waitReady / readyTimeoutMs of a screenshot operation. */
const readyArgs = args => ({ waitReady: args.waitReady === true, readyTimeoutMs: num(args.readyTimeoutMs, READY_TIMEOUT_MS, 0, MAX_WAIT_MS) });

export function createOps({ bundle, painter, rasterStats, send, log, readApk }) {
  let session = null, policy = null;
  const subscriptions = { logcat: false, netlog: false, events: false, plugins: false };
  let pluginPending = new Set(), pluginTimer = null;
  const need = () => { if (!session) throw conflict('no-session', 'The sidecar has no session yet (send "init" first)'); session.touch(); return session; };
  const opResult = (extra = {}) => ({ ...extra, snapshot: summary(session) });

  function attach(s) {
    s.on('logcat', lines => { if (subscriptions.logcat) send({ event: 'logcat', data: lines }); });
    s.on('netlog', rows => { if (subscriptions.netlog) send({ event: 'netlog', data: rows }); });
    s.on('event', (name, data) => {
      if (subscriptions.events) send({ event: 'session', name, data });
      if (name === 'plugin' && subscriptions.plugins) { pluginPending.add(data.id); pluginTimer ??= setTimeout(flushPlugins, 200); }
    });
  }
  async function flushPlugins() {
    pluginTimer = null; const ids = [...pluginPending]; pluginPending = new Set();
    for (const id of ids) { const out = await session?.plugins?.get(id).catch(() => null); if (out) send({ event: 'plugin', data: out }); }
  }
  const pluginsOf = s => { if (!s.plugins) throw conflict('vm-not-running', 'No VM in this session'); return s.plugins; };

  const table = {
    ping: () => ({ pong: true, revision: bundle.info.revision }),
    templates: () => [...(bundle.PLUGIN_TEMPLATES ?? []), ...SERVICE_PLUGIN_TEMPLATES].map(t => ({ id: t.id, name: t.name, description: t.description, code: t.code })),
    /** The defaults of a session for this policy (the site's, with the server exceptions) and the memory budget of a default session on this machine. */
    defaults: args => {
      const p = { ...DEFAULT_POLICY, ...(args.policy ?? policy ?? {}) }, limits = { vmHeapMb: 2048, vmHeapMaxMb: 6144, ...(args.limits ?? {}) };
      const memory = memoryReport(bundle.memoryBudget, { memoryMiB: 0, deviceMemory: hostDeviceMemory() });
      const device = bundle.defaultDeviceSettings ? Object.fromEntries(Object.entries(bundle.defaultDeviceSettings()).filter(([key]) => !['network', 'memoryMiB', 'eventSeconds', 'location', 'raster', 'layoutBounds'].includes(key))) : null;
      return { config: sessionDefaults(p), device, memory: { ...memory, workerHeapMb: workerHeapMb(memory.budgetMiB, limits) }, webview: false };
    },
    /** { sessionId, policy: { allowedHosts, allowPrivateNetwork, pluginsAllowed, maxPlugins, maxPluginCodeBytes, maxMemoryMiB }, limits: { vmHeapMb, vmHeapMaxMb, loadTimeoutMs, operationTimeoutMs, plugins: {...} }, appDataDir } */
    init: args => {
      if (session) throw conflict('already-initialized', 'This sidecar already hosts a session');
      if (typeof args.sessionId !== 'string' || !args.sessionId) throw badRequest('init needs sessionId');
      policy = { ...DEFAULT_POLICY, ...(args.policy ?? {}) };
      const limits = { vmHeapMb: 2048, vmHeapMaxMb: 6144, loadTimeoutMs: 120000, operationTimeoutMs: 45000, ...(args.limits ?? {}), plugins: { heapMb: 64, busySeconds: 5, maxRunSeconds: 0, ...(args.limits?.plugins ?? {}) } };
      session = new Session({ id: args.sessionId, owner: args.owner ?? null, bundle, painter, limits, policy, appDataDir: args.appDataDir || null, log });
      attach(session);
      return { sessionId: session.id, revision: bundle.info.revision };
    },
    validateConfig: args => validateConfig(args.config ?? {}, { modules: bundle, policy: policy ?? DEFAULT_POLICY }, { partial: args.partial === true }),
    load: async args => {
      const s = need();
      if (typeof args.apkPath !== 'string') throw badRequest('load needs apkPath');
      const config = withDefaults(validateConfig(args.config ?? {}, { modules: bundle, policy }), policy);   // the site's defaults for every key the client left out
      const apk = await readApk(args.apkPath);
      await s.load(apk, config);
      return { session: s.describe(), inventory: inventorySummary(s.inventory), config: s.config, snapshot: s.snapshot ? summary(s) : null };
    },
    describe: () => need().describe(),
    inventory: () => inventorySummary(need().inventory),
    getConfig: () => need().config,
    updateConfig: args => { const s = need(); return s.updateConfig(validateConfig(args.config ?? {}, { modules: bundle, policy }, { partial: true })); },
    restart: async args => { const s = need(); const partial = args.config ? validateConfig(args.config, { modules: bundle, policy }, { partial: true }) : null; return { session: await s.restart(partial) }; },

    launch: async args => { const s = need(); if (args.activity !== undefined && args.activity !== null && (typeof args.activity !== 'string' || args.activity.length > 500)) throw badRequest('activity must be a class name'); await s.launch(args.activity ?? undefined); return opResult({ launchMs: s.metrics.launchMs }); },
    intent: async args => { const { deepLink } = await need().intent(intentFrom(args)); return opResult({ deepLink }); },
    back: async () => { await need().back(); return opResult(); },
    /** "Recriar" (mode recreate, the default: Activity.recreate()) or "Reabrir" (mode reopen: a fresh instance with the same Intent). */
    reload: async args => { const s = need(), mode = reloadModeFrom({ ...(args.mode !== undefined && args.mode !== null ? { mode: args.mode } : {}) }); await s.reload(mode); return opResult({ mode }); },
    /** "Tela pronta": the latest snapshot.screenReady and whether it is current. */
    ready: () => need().readiness(),
    tap: async args => {
      const s = need(), selector = selectorFrom(args), longPress = args.longPress === true;
      if (selector) { const { snapshot, element, ...rest } = await s.tapElement(selector, { longPress }); void snapshot; return opResult({ ...rest, element }); }
      if (!Number.isFinite(args.x) || !Number.isFinite(args.y)) throw badRequest('tap needs { x, y } (screen dp) or { selector } / { key }');
      const { snapshot, ...rest } = await s.tapAt(args.x, args.y, { longPressMs: longPress ? 700 : 0 }); void snapshot; return opResult(rest);
    },
    type: async args => { const s = need(), selector = selectorFrom(args); if (!selector) throw badRequest('type needs a selector'); const { snapshot, ...rest } = await s.typeText(selector, args.text, { append: args.append === true }); void snapshot; return opResult(rest); },
    scroll: async args => { const s = need(); const { snapshot, ...rest } = await s.scroll({ selector: selectorFrom(args), x: args.x ?? undefined, y: args.y ?? undefined, dx: Number(args.dx ?? 0), dy: Number(args.dy ?? 0) }); void snapshot; return opResult(rest); },
    input: async args => {
      const s = need(); if (!INPUT_TYPES.includes(args.type)) throw badRequest(`type: one of ${INPUT_TYPES.join(', ')}`);
      const { type, ...payload } = args; await s.raw(type, payload); return opResult();
    },
    wait: async args => {
      const s = need(), selector = selectorFrom(args), ready = readyFlagFrom(args.ready);
      if (!selector && !ready) throw badRequest('wait needs a selector, ready: true, or both');
      const timeoutMs = num(args.timeoutMs, ready && !selector ? READY_TIMEOUT_MS : 10000, 0, MAX_WAIT_MS), { element, waitedMs, screenReady } = await s.waitFor(selector, { timeoutMs, gone: args.gone === true, ready });
      return { element, waitedMs, activity: s.snapshot?.activityName ?? null, ready: screenReady?.ready ?? null, screenReady };
    },

    snapshot: args => { const s = need(); return args.full ? publicSnapshot(s.snapshot, { trace: num(args.trace, 50, 0, 512) }) : summary(s); },
    layoutBounds: async args => { const s = need(); return { activity: s.snapshot?.activityName ?? null, elements: await s.layoutBounds({ names: args.names !== false }) }; },
    elements: args => { const s = need(), selector = selectorFrom({ selector: args.selector ?? null }); if (!s.snapshot) return { elements: [] }; return { elements: selector ? s.find(selector) : s.find({ textContains: '' }) }; },
    composeTree: args => need().composeTree({ maxNodes: num(args.maxNodes, 2000, 1, 4000) }),
    screenState: async () => (await need().screenState()) ?? { text: null },
    diagnostic: () => need().diagnostic(),
    screenshot: async args => png(await need().screenshot({ scale: num(args.scale, 1, 0.1, 4), systemBars: args.systemBars !== false, ...readyArgs(args) })),
    elementScreenshot: async args => { if (typeof args.key !== 'string') throw badRequest('elementScreenshot needs key'); return png(await need().elementScreenshot(args.key, { scale: num(args.scale, 1, 0.1, 4), ...readyArgs(args) })); },

    activity: async () => (await need().pluginQuery('activity', {})).value,
    viewModels: async () => (await need().pluginQuery('viewModels', {})).value,
    fragments: async () => (await need().pluginQuery('fragments', {})).value,
    inspect: async args => { if (typeof args.handle !== 'string') throw badRequest('inspect needs { handle, options? }'); return (await need().pluginQuery('inspect', { handle: args.handle, options: args.options ?? {} })).value; },
    field: async args => { if (typeof args.handle !== 'string' || !isPath(args.path)) throw badRequest('field needs { handle, path (string or array of strings), options? }'); return (await need().pluginQuery('field', { handle: args.handle, path: args.path ?? '', options: args.options ?? {} })).value; },
    // state outside the screen: static fields (a Kotlin object, a companion, a singleton, a dependency-injection container), classes by name, instances of a type, who holds an object
    classes: async args => { const match = String(args.match ?? '').slice(0, 200); if (!match) throw badRequest('classes needs ?match=part of the name or /regex/'); return (await need().pluginQuery('classes', { match, limit: num(args.limit, 100, 1, 1000), initialized: args.initialized === true })).value; },
    statics: async args => { if (typeof args.className !== 'string') throw badRequest('statics needs { className, options? }'); return (await need().pluginQuery('statics', { className: args.className, options: args.options ?? {} })).value; },
    staticField: async args => { if (typeof args.className !== 'string' || !isPath(args.path)) throw badRequest('static-field needs { className, path (string or array of strings), options? }'); return (await need().pluginQuery('staticField', { className: args.className, path: args.path ?? '', options: args.options ?? {} })).value; },
    instances: async args => { if (typeof args.className !== 'string') throw badRequest('instances needs { className, subclasses?, limit?, after?, maxPages? }'); return need().pagedQuery('instances', { className: args.className, subclasses: args.subclasses !== false }, args); },
    referrers: async args => { if (typeof args.handle !== 'string') throw badRequest('referrers needs { handle, limit?, after?, maxPages? }'); return need().pagedQuery('referrers', { handle: args.handle }, args); },
    methods: args => need().searchMethods({ query: String(args.query ?? '').slice(0, 200) }),

    logcat: args => need().readLogcat({ since: num(args.since, -1, -1, Number.MAX_SAFE_INTEGER), limit: num(args.limit, 500, 1, 5000), level: args.level ?? null, tag: args.tag ?? null, contains: args.contains ?? null }),
    network: args => need().readNetlog({ since: num(args.since, -1, -1, Number.MAX_SAFE_INTEGER), limit: num(args.limit, 200, 1, 2000) }),
    /** Which event channels the runner forwards to listeners. Logcat lines leave the VM Worker only while the logcat channel is on (or config.logcat = 'always'). */
    subscribe: async args => {
      const s = need();
      for (const key of Object.keys(subscriptions)) if (typeof args[key] === 'boolean') subscriptions[key] = args[key];
      s.logListeners = subscriptions.logcat ? 1 : 0; s.streams = Object.values(subscriptions).filter(Boolean).length;
      await s.syncLogcat();
      return { ...subscriptions, logcatStreaming: s.logStreaming };
    },

    permissionRequests: () => { const s = need(); return { config: s.permissionConfig, pending: s.listPermissionRequests() }; },
    answerPermission: args => need().answerPermission(args.requestId, args.answer),
    clearAppData: async () => ({ appData: await need().clearAppData() }),

    listPlugins: args => pluginsOf(need()).list({ inlineImages: args.inlineImages === true }),
    setPlugins: args => ({ plugins: need().setPlugins(args.plugins ?? []).map(p => ({ id: p.id, name: p.name, enabled: p.enabled })) }),
    getPlugin: async args => { const out = await pluginsOf(need()).get(args.pluginId, { inlineImages: args.inlineImages === true }); if (!out) throw new HttpError(404, 'not-found', `No plugin ${args.pluginId}`); return out; },
    stopPlugin: args => { pluginsOf(need()).stop(args.pluginId); return { stopped: true }; },
    restartPlugin: args => { if (!policy?.pluginsAllowed) throw new HttpError(403, 'plugins-disabled', 'Plugins are disabled on this runner'); pluginsOf(need()).restart(args.pluginId); return { restarted: true }; },
    pluginImage: async args => { const image = await pluginsOf(need()).image(args.pluginId, Number(args.index)); if (!image) throw new HttpError(404, 'not-found', 'No such image block'); return { type: image.type, dataBase64: image.bytes.toString('base64') }; },

    stats: () => ({ rasters: rasterStats(), memory: process.memoryUsage(), session: session?.describe() ?? null }),
    shutdown: () => { if (session) session.close('deleted'); return { closed: true }; },
  };

  return {
    async run(op, args) {
      const fn = Object.hasOwn(table, op) ? table[op] : null;
      if (!fn) throw badRequest(`unknown operation ${op}`);
      if (args === null || typeof args !== 'object' || Array.isArray(args)) throw badRequest('args must be an object');
      return fn(args);
    },
    close(reason) { session?.close(reason); },
  };
}
