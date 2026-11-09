// State outside the screen (classes, statics, staticField, instances, referrers): the sidecar's paging over the runtime's heap-scan pages, the HTTP status of each
// failure code of a plugin query and the path forms of /field and /static-field. No VM: a fake client answers the pages. Run by ./gradlew test (SidecarScriptsTest).
import test from 'node:test';
import assert from 'node:assert/strict';
import { Session, pluginQueryStatus } from '../lib/session.mjs';
import { isPath } from '../lib/ops.mjs';

const session = pages => {
  const s = new Session({ bundle: { info: { revision: 'test', dir: '.' } }, painter: null, limits: { vmHeapMb: 2048, vmHeapMaxMb: 6144 }, policy: {}, deviceMemory: 8 });
  const calls = [];
  s.client = { worker: {}, pluginQuery: async (query, args) => { calls.push({ query, ...args }); const page = pages(args); if (page instanceof Error) throw page; return { value: page }; } };
  return { s, calls };
};
const failure = code => Object.assign(new Error(`failed: ${code}`), { code });

test('a heap scan walks the runtime pages until limit items, the end of the heap or maxPages', async () => {
  // a heap of 10 pages of 100 records, one match every 3 records
  const heap = ({ after, limit }) => { const items = []; for (let id = after + 1; id <= Math.min(after + 100, 1000) && items.length < limit; id++) if (id % 3 === 0) items.push({ handle: `h${id}.x` }); const last = items.length >= limit ? Number(items.at(-1).handle.slice(1, -2)) : Math.min(after + 100, 1000); return { items, scanned: last - after, next: last >= 1000 ? null : last }; };
  const all = session(heap);
  const done = await all.s.pagedQuery('instances', { className: 'a.B', subclasses: true }, { limit: 500 });
  assert.equal(done.items.length, 333); assert.equal(done.done, true); assert.equal(done.next, null); assert.equal(done.pages, 10); assert.equal(done.scanned, 1000);
  assert.deepEqual(all.calls[0], { query: 'instances', className: 'a.B', subclasses: true, limit: 500, after: 0, budgetMs: undefined });
  assert.equal(all.calls[1].after, 100); assert.equal(all.calls[1].limit, 500 - 33, 'each page asks only for what is still missing');

  const few = session(heap);
  const three = await few.s.pagedQuery('instances', { className: 'java.lang.Object' }, { limit: 3 });
  assert.equal(three.items.length, 3); assert.equal(three.done, false); assert.equal(three.next, 9); assert.equal(three.pages, 1);
  const more = await few.s.pagedQuery('instances', { className: 'java.lang.Object' }, { limit: 3, after: three.next });
  assert.deepEqual(more.items.map(item => item.handle), ['h12.x', 'h15.x', 'h18.x'], 'next continues the scan');

  const capped = session(heap);
  const two = await capped.s.pagedQuery('referrers', { handle: 'h1.x' }, { maxPages: 2, limit: 1000, budgetMs: 80 });
  assert.equal(two.pages, 2); assert.equal(two.done, false); assert.equal(two.next, 200); assert.equal(capped.calls[0].limit, 500, 'limit is at most 500'); assert.equal(capped.calls[0].budgetMs, 80);
  const defaults = session(() => ({ items: [], scanned: 10, next: 5 }));
  const empty = await defaults.s.pagedQuery('instances', { className: 'a.B' });
  assert.equal(empty.pages, 100, 'maxPages defaults to 100'); assert.equal(defaults.calls[0].limit, 50, 'limit defaults to 50'); assert.equal(empty.done, false);
  assert.equal((await session(() => ({ items: [], scanned: 1, next: 1 })).s.pagedQuery('instances', { className: 'a.B' }, { maxPages: 9999 })).pages, 500, 'maxPages is at most 500');
});

test('failure codes of a plugin query map to HTTP statuses', async () => {
  const expected = { 'bad-query': 400, 'bad-handle': 400, 'bad-path': 400, 'bad-class': 400, 'bad-args': 400, 'no-class': 404, 'not-initialized': 409, collected: 410, busy: 503, failed: 422, 'vm-error': 422 };
  for (const [code, status] of Object.entries(expected)) assert.equal(pluginQueryStatus(code), status, code);
  const { s } = session(() => failure('not-initialized'));
  await assert.rejects(s.pluginQuery('staticField', { className: 'a.B', path: 'x' }), { status: 409, code: 'not-initialized' });
  await assert.rejects(session(() => failure('no-class')).s.pagedQuery('instances', { className: 'a.B' }), { status: 404, code: 'no-class' }, 'a failed page fails the scan');
});

test('a path is a dotted string or an array of parts', () => {
  for (const ok of [undefined, null, '', 'a.b', ['a', 'com.exemplo.Tela::id', 0]]) assert.equal(isPath(ok), true, JSON.stringify(ok));
  for (const bad of [{ x: 1 }, 5, ['a', { b: 1 }], ['a', null], new Array(33).fill('a')]) assert.equal(isPath(bad), false, JSON.stringify(bad));
});
