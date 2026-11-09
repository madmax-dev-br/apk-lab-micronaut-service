#!/usr/bin/env node
// Downloads the pinned APK Web Lab runtime (sidecar/runtime.lock.json) into vendor/apk-lab, verifying every file's size and SHA-256.
//
//   node sidecar/fetch-runtime.mjs                 # into vendor/apk-lab (or ./gradlew fetchRuntime)
//   node sidecar/fetch-runtime.mjs --dir /opt/apk-lab-runtime
//   node sidecar/fetch-runtime.mjs --base https://mirror.example.test/apk-lab/   # any mirror: the hashes still decide
//   node sidecar/fetch-runtime.mjs --check          # only verify an existing directory
//
// Files come from the site repository at the pinned commit (raw.githubusercontent.com/<repo>/<commit>/<path>), so a newer publication of the site never
// changes what this repository runs. Files already present with the right hash are kept. Nothing is executed during the download.
import { mkdir, readFile, rename, writeFile, rm } from 'node:fs/promises';
import { createReadStream, existsSync } from 'node:fs';
import { createHash } from 'node:crypto';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const args = process.argv.slice(2), option = name => { const i = args.indexOf(`--${name}`); return i >= 0 ? (args[i + 1] && !args[i + 1].startsWith('--') ? args[i + 1] : true) : undefined; };
const lock = JSON.parse(await readFile(path.join(here, 'runtime.lock.json'), 'utf8'));
const target = path.resolve(typeof option('dir') === 'string' ? option('dir') : path.join(here, '..', 'vendor', 'apk-lab'));
const base = typeof option('base') === 'string' ? option('base').replace(/\/?$/, '/') : `https://raw.githubusercontent.com/${lock.site.repo}/${lock.site.commit}/`;
const checkOnly = option('check') === true, concurrency = Math.max(1, Number(option('jobs')) || 8);

const sha256OfFile = file => new Promise((resolve, reject) => { const hash = createHash('sha256'); createReadStream(file).on('data', chunk => hash.update(chunk)).on('end', () => resolve(hash.digest('hex'))).on('error', reject); });
const inside = relative => { const file = path.resolve(target, relative); if (!file.startsWith(target + path.sep)) throw new Error(`unsafe path in the lock: ${relative}`); return file; };

async function download(entry) {
  const file = inside(entry.path);
  if (existsSync(file) && await sha256OfFile(file) === entry.sha256) return 'kept';
  if (checkOnly) throw new Error(`${entry.path}: missing or different`);
  const url = new URL(entry.path.split('/').map(encodeURIComponent).join('/'), base);
  let lastError;
  for (let attempt = 1; attempt <= 3; attempt++) {
    try {
      const response = await fetch(url, { redirect: 'follow', signal: AbortSignal.timeout(120000) });
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      const bytes = Buffer.from(await response.arrayBuffer());
      if (bytes.length !== entry.size) throw new Error(`size ${bytes.length}, expected ${entry.size}`);
      const digest = createHash('sha256').update(bytes).digest('hex');
      if (digest !== entry.sha256) throw new Error(`SHA-256 ${digest}, expected ${entry.sha256}`);
      await mkdir(path.dirname(file), { recursive: true });
      const temp = `${file}.${process.pid}.part`;
      await writeFile(temp, bytes); await rename(temp, file);
      return 'downloaded';
    } catch (error) { lastError = error; }
  }
  throw new Error(`${entry.path}: ${lastError?.message ?? lastError} (${url})`);
}

const started = Date.now(), counts = { kept: 0, downloaded: 0 }, failures = [];
let next = 0, doneBytes = 0;
console.log(`APK Web Lab runtime ${lock.revision} (${lock.site.repo}@${lock.site.commit.slice(0, 12)}): ${lock.files} files, ${(lock.totalBytes / 1048576).toFixed(1)} MiB -> ${target}`);
await mkdir(target, { recursive: true });
await Promise.all(Array.from({ length: concurrency }, async () => {
  while (next < lock.entries.length) {
    const entry = lock.entries[next++];
    try { counts[await download(entry)]++; doneBytes += entry.size; }
    catch (error) { failures.push(error.message); }
    if ((counts.kept + counts.downloaded) % 100 === 0) process.stdout.write(`  ${counts.kept + counts.downloaded}/${lock.entries.length} (${(doneBytes / 1048576).toFixed(0)} MiB)\n`);
  }
}));
if (failures.length) {
  console.error(`${failures.length} files failed:\n  ${failures.slice(0, 20).join('\n  ')}`);
  process.exit(1);
}
const info = JSON.parse(await readFile(path.join(target, 'build-info.json'), 'utf8'));
if (info.revision !== lock.revision) { console.error(`build-info.json says ${info.revision}, the lock pins ${lock.revision}`); process.exit(1); }
await rm(path.join(target, '.partial'), { force: true });
console.log(`ok: ${counts.downloaded} downloaded, ${counts.kept} already present, in ${((Date.now() - started) / 1000).toFixed(1)} s. Licenses of the runtime: ${path.join(target, 'THIRD_PARTY.md')}`);
