#!/usr/bin/env node
// The Node sidecar of one runner session. The Micronaut runner starts one of these processes per session and drives it with a JSON-lines protocol:
//
//   stdin  (runner -> sidecar)  {"id": 7, "op": "tap", "args": {"selector": {"testTag": "save"}}}
//   stdout (sidecar -> runner)  {"id": 7, "ok": true, "result": {...}}
//                               {"id": 7, "ok": false, "error": {"status": 404, "code": "element-not-found", "message": "...", "details": {...}}}
//                               {"event": "ready", "protocol": 1, "revision": "...", "node": "v22..."}       once, after the runtime bundle loaded
//                               {"event": "logcat" | "netlog" | "session" | "plugin", ...}                   only for the channels the runner subscribed to
//                               {"event": "fatal", "message": "..."}                                         before exiting on an unrecoverable error
//
// stdout carries nothing else: console output of this process, of the VM Worker and of plugins goes to stderr. Every operation of the lab is the bundle's own code
// (the Worker of the public site run in a worker_threads Worker, its APKWorkerClient, layout-bounds model, plugin host and sanitizers); see lib/session.mjs.
import { createInterface } from 'node:readline';
import { readFile } from 'node:fs/promises';
import { format } from 'node:util';

// ---- stdout is the protocol: everything else goes to stderr ----------------------------------------------------------------------------------------------------------
const toStderr = (...args) => { try { process.stderr.write(`${format(...args)}\n`); } catch { /* ignore */ } };
for (const name of ['log', 'info', 'debug', 'warn', 'error', 'trace']) console[name] = toStderr;

export const PROTOCOL_VERSION = 1;
const MAX_OUT_LINE = Number(process.env.APK_LAB_SIDECAR_MAX_LINE_BYTES) || 48 * 1024 * 1024;
const stdoutWrite = process.stdout.write.bind(process.stdout);
const jsonSafe = (key, value) => typeof value === 'bigint' ? String(value) : value;
function send(message) {
  let line = JSON.stringify(message, jsonSafe);
  if (Buffer.byteLength(line) > MAX_OUT_LINE) {
    if (message.id === undefined) return;   // an oversized event is dropped
    line = JSON.stringify({ id: message.id, ok: false, error: { status: 507, code: 'response-too-large', message: `The response is larger than the sidecar line limit (${MAX_OUT_LINE} bytes)` } });
  }
  stdoutWrite(`${line}\n`);
}
const log = (level, message) => toStderr(`[sidecar ${process.pid}] ${level} ${message}`);
function fatal(message) { try { send({ event: 'fatal', message: String(message).slice(0, 4000) }); } catch { /* ignore */ } log('error', message); setTimeout(() => process.exit(70), 50); }
process.on('uncaughtException', error => fatal(`uncaught exception: ${error?.stack ?? error}`));
process.on('unhandledRejection', reason => log('warn', `unhandled rejection: ${reason?.stack ?? reason}`));

// ---- runtime --------------------------------------------------------------------------------------------------------------------------------------------------------
const { loadBundle } = await import('./lib/bundle.mjs');
const { createScreenPainter } = await import('./lib/screen.mjs');
const { rasterBytes, rasterStats } = await import('./lib/rasters.mjs');
const { createOps } = await import('./lib/ops.mjs');
let bundle;
try { bundle = await loadBundle(process.env.APK_LAB_RUNTIME_DIR || undefined); }
catch (error) { fatal(`could not load the APK Web Lab runtime: ${error?.message ?? error}`); await new Promise(() => {}); }
const painter = createScreenPainter({ runtimeDir: bundle.info.dir, rasterBytes });
const ops = createOps({ bundle, painter, rasterStats, send, log, readApk: file => readFile(file) });

send({ event: 'ready', protocol: PROTOCOL_VERSION, revision: bundle.info.revision, node: process.version, pid: process.pid });

// ---- requests -------------------------------------------------------------------------------------------------------------------------------------------------------
const input = createInterface({ input: process.stdin, crlfDelay: Infinity });
input.on('line', async line => {
  if (!line.trim()) return;
  let request;
  try { request = JSON.parse(line); } catch { send({ id: null, ok: false, error: { status: 400, code: 'bad-protocol', message: 'request line is not JSON' } }); return; }
  const { id, op, args } = request ?? {};
  try {
    const result = await ops.run(op, args ?? {});
    send({ id, ok: true, result: result === undefined ? null : result });
    if (op === 'shutdown') setTimeout(() => process.exit(0), 20);
  } catch (error) {
    const status = Number.isInteger(error?.status) ? error.status : 500;
    if (status >= 500 && !error?.code) log('error', `${op}: ${error?.stack ?? error}`);
    send({ id, ok: false, error: { status, code: error?.code && error?.status ? error.code : 'internal', message: String(error?.message ?? error).slice(0, 8000), ...(error?.details !== undefined ? { details: error.details } : {}), ...(error?.retryAfter ? { retryAfter: error.retryAfter } : {}) } });
  }
});
// The runner closed our stdin (it stopped or died): close the session and leave.
input.on('close', () => { try { ops.close('runner-gone'); } catch { /* ignore */ } setTimeout(() => process.exit(0), 50); });
