#!/usr/bin/env node
// Writes sidecar/runtime.lock.json: the pinned APK Web Lab runtime (one commit of the public site repository) as a list of files with size and SHA-256.
//
//   node sidecar/make-runtime-lock.mjs --from <checkout of the site at that commit> --commit <sha> [--verify-remote]
//
// --verify-remote compares every file with the commit's git tree on GitHub (blob SHA-1), so a lock is never made from a modified checkout.
// Maintainers run this when moving to a new runtime build; users only run fetch-runtime.mjs.
import { readdir, readFile, writeFile, stat } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { SITE_REPO, SITE_URL, isRuntimeFile } from './runtime-files.mjs';

const args = Object.fromEntries(process.argv.slice(2).reduce((out, arg, index, all) => { if (arg.startsWith('--')) out.push([arg.slice(2), all[index + 1]?.startsWith('--') || all[index + 1] === undefined ? true : all[index + 1]]); return out; }, []));
if (!args.from || !args.commit || !/^[0-9a-f]{40}$/.test(args.commit)) {
  console.error('usage: node sidecar/make-runtime-lock.mjs --from <site checkout> --commit <40-hex commit> [--repo owner/name] [--verify-remote]');
  process.exit(2);
}
const root = path.resolve(args.from), repo = typeof args.repo === 'string' ? args.repo : SITE_REPO;

async function walk(dir, prefix = '') {
  const out = [];
  for (const entry of await readdir(dir, { withFileTypes: true })) {
    const relative = `${prefix}${entry.name}`;
    if (entry.isDirectory()) { if (isRuntimeFile(`${relative}/`)) out.push(...await walk(path.join(dir, entry.name), `${relative}/`)); }
    else if (entry.isFile() && isRuntimeFile(relative)) out.push(relative);
  }
  return out;
}

const files = (await walk(root)).sort();
const entries = [];
let totalBytes = 0;
for (const relative of files) {
  const bytes = await readFile(path.join(root, relative));
  entries.push({ path: relative, size: bytes.length, sha256: createHash('sha256').update(bytes).digest('hex'), gitSha1: createHash('sha1').update(`blob ${bytes.length}\0`).update(bytes).digest('hex') });
  totalBytes += bytes.length;
}
const revision = JSON.parse(await readFile(path.join(root, 'build-info.json'), 'utf8')).revision;

if (args['verify-remote']) {
  const response = await fetch(`https://api.github.com/repos/${repo}/git/trees/${args.commit}?recursive=1`, { headers: { accept: 'application/vnd.github+json' } });
  if (!response.ok) throw new Error(`GitHub tree of ${args.commit}: HTTP ${response.status}`);
  const tree = await response.json();
  if (tree.truncated) throw new Error('the GitHub tree listing is truncated; verify another way');
  const remote = new Map(tree.tree.filter(item => item.type === 'blob').map(item => [item.path, item.sha]));
  const mismatched = entries.filter(entry => remote.get(entry.path) !== entry.gitSha1).map(entry => entry.path);
  if (mismatched.length) throw new Error(`${mismatched.length} files differ from commit ${args.commit}: ${mismatched.slice(0, 10).join(', ')}`);
  const missing = [...remote.keys()].filter(file => isRuntimeFile(file) && !entries.some(entry => entry.path === file));
  if (missing.length) throw new Error(`${missing.length} runtime files of the commit are missing locally: ${missing.slice(0, 10).join(', ')}`);
  console.log(`verified ${entries.length} files against ${repo}@${args.commit}`);
}

const lock = { format: 1, note: 'Pinned APK Web Lab runtime: files of the public site at one commit, verified by SHA-256 when downloaded (sidecar/fetch-runtime.mjs).',
  site: { repo, commit: args.commit, url: SITE_URL }, revision, files: entries.length, totalBytes,
  entries: entries.map(({ path: p, size, sha256 }) => ({ path: p, size, sha256 })) };
const target = path.join(path.dirname(fileURLToPath(import.meta.url)), 'runtime.lock.json');
await writeFile(target, `${JSON.stringify(lock, null, 1)}\n`);
console.log(`runtime.lock.json: revision ${revision}, ${entries.length} files, ${(totalBytes / 1048576).toFixed(1)} MiB (${(await stat(target)).size} bytes of lock)`);
