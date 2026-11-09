// One server-side plugin: a worker_threads Worker (memory-limited, empty environment) that runs the site's plugin runtime (plugin-runtime.js pluginWorkerSource(), passed in
// workerData) inside a node:vm context holding only timers, console and the message bridge. The plugin's code arrives in the 'start' message exactly as on the site and
// sees the same `lab` API. node:vm and worker_threads are NOT a security boundary (see the security model in the documentation): this only removes the obvious capabilities (process, require,
// import(), fetch, the runner's environment) and bounds memory and CPU; plugin submission must stay limited to trusted users.
import { parentPort, workerData } from 'node:worker_threads';
import vm from 'node:vm';

const context = vm.createContext(Object.create(null), { name: 'apk-lab-plugin', codeGeneration: { strings: true, wasm: false }, microtaskMode: undefined });
const bridge = {
  post: message => parentPort.postMessage(message),
  setTimeout: (fn, ms) => setTimeout(fn, Math.max(0, Math.min(2147483647, Number(ms) || 0))),
  setInterval: (fn, ms) => setInterval(fn, Math.max(10, Math.min(2147483647, Number(ms) || 0))),
  clearTimeout: id => clearTimeout(id), clearInterval: id => clearInterval(id),
};
// Inside the context: the Worker-like global the plugin runtime expects (self, postMessage, addEventListener, timers, console). Wrappers are created by code running in the
// context so the plugin only ever holds functions of its own realm.
vm.runInContext(`(bridge) => {
  const g = globalThis; const listeners = { error: [], unhandledrejection: [] };
  g.self = g;
  g.postMessage = message => bridge.post(message);
  g.setTimeout = (fn, ms) => bridge.setTimeout(fn, ms); g.setInterval = (fn, ms) => bridge.setInterval(fn, ms);
  g.clearTimeout = id => bridge.clearTimeout(id); g.clearInterval = id => bridge.clearInterval(id);
  g.addEventListener = (type, fn) => { if (listeners[type]) listeners[type].push(fn); };
  g.__dispatch = (type, event) => { for (const fn of listeners[type] ?? []) fn(event); };
  g.console = { log() {}, info() {}, warn() {}, error() {}, debug() {} };
}`, context)(bridge);
vm.runInContext(workerData.source, context, { filename: 'plugin-runtime.js', timeout: 5000 });
process.on('unhandledRejection', reason => { try { context.__dispatch('unhandledrejection', { reason, preventDefault() {} }); } catch { /* ignore */ } });
process.on('uncaughtException', error => { try { context.__dispatch('error', { error, message: String(error?.message ?? error), preventDefault() {} }); } catch { /* ignore */ } });
parentPort.on('message', data => { try { context.onmessage?.({ data }); } catch (error) { context.__dispatch('error', { error, message: String(error?.message ?? error), preventDefault() {} }); } });
