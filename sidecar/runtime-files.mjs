// Which files of the public APK Web Lab site make up the runtime the sidecar needs. The site also hosts the browser UI, demo APKs and documentation;
// those are left out of the download. Shared by make-runtime-lock.mjs (writes runtime.lock.json) and fetch-runtime.mjs (downloads it).
export const SITE_REPO = 'madmax-dev-br/apk-browser-lab-site';
export const SITE_URL = 'https://madmax-dev-br.github.io/apk-browser-lab-site/';

const EXCLUDED_DIRS = ['fixtures/', 'hermes-proof/', 'net-probe/', 'player/', 'compose/', 'comparison/', 'docs/', 'wasm/', '.git/', '.github/'];   // wasm/: a second copy of the whole site kept under that path
const EXCLUDED_FILES = /(^|\/)(\.nojekyll|styles\.css)$|\.(html|map|apk)$/i;

/** true when `relative` (a site path with "/" separators) belongs to the runtime. */
export function isRuntimeFile(relative) {
  if (EXCLUDED_DIRS.some(dir => relative.startsWith(dir))) return false;
  return !EXCLUDED_FILES.test(relative);
}
