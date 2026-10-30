#!/usr/bin/env node
import { cp, mkdir, readFile, writeFile, readdir, rm } from 'node:fs/promises';
import { join, resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createHash } from 'node:crypto';
import { spawnSync } from 'node:child_process';
import { readUpstreamProfile, serializeMobileProfile } from './mobile_profile.mjs';
import { patchLayout, patchSettings, patchWorkspace, patchConversation, patchComposerStats, patchInputTrigger, patchSidebar, patchSidebarRight, patchModelSelection, patchImageLightbox, patchThemeSettings } from './mobile_ui_patch.mjs';
import { composeMobileClient } from './mobile_client_bundle.mjs';
import { patchNodePtyLoader } from './mobile_native_patch.mjs';
import { patchSidebarBrowser } from './mobile_browser_patch.mjs';
import { patchSystemPrompt, patchSessionTitleProvider } from './mobile_prompt_patch.mjs';
import { patchLocalePersistence } from './mobile_locale_patch.mjs';
import { patchSessionModelSelection } from './mobile_session_model_patch.mjs';
import { patchNativeCompanionMaintenance } from './native_companion_maintenance_patch.mjs';
import { patchNativeMaintenanceWebServer, patchNativeMaintenanceGateway } from './native_maintenance_admission_patch.mjs';
import { patchNativeScheduleMaintenance } from './native_schedule_maintenance_patch.mjs';
import { patchMediaCommandMenu } from '../runtime/media-input/upstream-patch.mjs';
import { patchVoiceInput } from '../runtime/voice/upstream-patch.mjs';
import { patchPrivacyLlm, patchPrivacyPiAi, patchExecutionMcp, patchExecutionFileMode, patchRequiredLoader, patchRequiredPluginManager, patchRequiredPluginManagerPatch } from '../runtime/privacy/upstream-patch.mjs';
import { patchConversationBranding, patchSidebarBranding, patchModelsBranding, patchAccountBranding, patchProductCopy, patchEntryCustomizationCopy, patchSpaceCopy, patchPresetCopy, patchShortcutBranding, patchFrontendBranding, patchWebManifestBranding } from './mobile_brand_patch.mjs';
const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const generated = join(root, 'runtime/generated');
const staging = join(generated, 'dsh');
const source = join(root, 'runtime/dsh/node_modules');
const version = JSON.parse(await readFile(join(root, 'runtime/dsh/package.json'), 'utf8')).dependencies['@deepseek-ai/dsh'];
if (!/^\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?$/.test(version)) throw new Error('DSH dependency must be an exact version');
const release = JSON.parse(await readFile(join(root, 'runtime/release.json'), 'utf8'));
const dependencyLock = JSON.parse(await readFile(join(root, 'runtime/dsh/package-lock.json'), 'utf8'));
const mobilePackage = JSON.parse(await readFile(join(root, 'runtime/mobile/package.json'), 'utf8'));
if (release.dshVersion !== version || dependencyLock.packages?.['node_modules/@deepseek-ai/dsh']?.version !== version
    || release.mobileVersion !== mobilePackage.version) throw new Error('Droy release, installed dependencies and mobile adapter versions must agree');
const manifest = JSON.parse(await readFile(join(source, '@deepseek-ai/dsh/package.json')));
if (manifest.version !== version) throw new Error(`Expected DSH ${version}`);
await mkdir(generated, { recursive: true });
if (!process.argv.includes('--refresh-mobile')) await rm(staging, { recursive: true, force: true });
await mkdir(staging, { recursive: true });
const excluded = name => /-(?:darwin|win32|linux)-(?:x64|arm64|ia32|arm)(?:-|$)|^@img$/.test(name);
if (!process.argv.includes('--refresh-mobile')) await cp(source, join(staging, 'node_modules'), { recursive: true, dereference: true, filter: path => !excluded(path.split('/').pop()) });
const mobileClient = composeMobileClient(
  await readFile(join(root, 'runtime/mobile/client.js'), 'utf8'),
  await readFile(join(root, 'runtime/mobile/touch.js'), 'utf8'),
  await readFile(join(root, 'runtime/mobile/token-usage-client.js'), 'utf8'),
);
for (const destination of [join(staging, 'mobile'), join(staging, 'node_modules/@droyagent/mobile')]) {
  await rm(destination, { recursive: true, force: true });
  await cp(join(root, 'runtime/mobile'), destination, { recursive: true, filter: path => path.split('/').pop() !== 'tests' });
  await writeFile(join(destination, 'client.js'), mobileClient);
}
const nodeModules = join(staging, 'node_modules');
for (const name of ['privacy', 'execution', 'memory']) {
  const destination = join(nodeModules, '@droyagent', name);
  await rm(destination, { recursive: true, force: true });
  await cp(join(root, 'runtime', name), destination, { recursive: true, filter: path => path.split('/').pop() !== 'tests' });
}
let memoryClient = await readFile(join(root, 'runtime/memory/client.js'), 'utf8');
const memoryStyles = await readFile(join(root, 'runtime/memory/client.css'), 'utf8');
const memoryStyleMarker = "/* DROY_MEMORY_STYLES */ ''";
if (memoryClient.split(memoryStyleMarker).length !== 2) throw new Error('Memory client styles marker must occur exactly once');
memoryClient = memoryClient.replace(memoryStyleMarker, () => JSON.stringify(memoryStyles));
await writeFile(join(nodeModules, '@droyagent/memory/client.js'), memoryClient);
// The media-input plugin uses public DSH services and owns its compatibility adapter.
await rm(join(nodeModules, '@droyagent/media-input'), { recursive: true, force: true });
await cp(join(root, 'runtime/media-input'), join(nodeModules, '@droyagent/media-input'), {
  recursive: true, filter: path => path.split('/').pop() !== 'tests',
});
const mediaClient = await readFile(join(root, 'runtime/media-input/client.js'), 'utf8');
const mediaAdapter = await readFile(join(root, 'runtime/media-input/dsh-adapter.js'), 'utf8');
if (mediaClient.split('/* DROY_MEDIA_ADAPTER */').length !== 2)
  throw new Error('Media-input client compatibility adapter marker must occur exactly once');
await writeFile(join(nodeModules, '@droyagent/media-input/client.js'), mediaClient.replace('/* DROY_MEDIA_ADAPTER */', () => mediaAdapter));
await rm(join(nodeModules, '@droyagent/voice'), { recursive: true, force: true });
await cp(join(root, 'runtime/voice'), join(nodeModules, '@droyagent/voice'), {
  recursive: true, filter: path => path.split('/').pop() !== 'tests',
});
let voiceClient = await readFile(join(root, 'runtime/voice/client.js'), 'utf8');
for (const [marker, file, json] of [
  ['/* DROY_VOICE_ADAPTER */', 'dsh-adapter.js', false],
  ['/* DROY_VOICE_CONTROLLER */', 'client-controller.js', false],
  ['/* DROY_VOICE_STYLES */', 'voice.css', true],
]) {
  if (voiceClient.split(marker).length !== 2) throw new Error(`Voice client marker must occur exactly once: ${marker}`);
  const content = await readFile(join(root, 'runtime/voice', file), 'utf8');
  const placeholder = json ? `${marker} ''` : marker;
  voiceClient = voiceClient.replace(placeholder, () => json ? JSON.stringify(content) : content);
}
await writeFile(join(nodeModules, '@droyagent/voice/client.js'), voiceClient);
const hashes = {};
const patchLock = JSON.parse(await readFile(join(root, 'runtime/mobile/upstream-patches.lock.json'), 'utf8'));
if (patchLock.dshVersion !== version) throw new Error('DSH version changed without reviewing Android patch inputs');
const expectedHashes = patchLock.sha256;
async function patch(path, transform) {
  const file = join(nodeModules, path);
  const text = await readFile(join(source, path), 'utf8');
  hashes[path] = createHash('sha256').update(text).digest('hex');
  if (hashes[path] !== expectedHashes[path]) throw new Error(`Upstream patch input changed: ${path}`);
  const updated = transform(text);
  if (updated === text) throw new Error(`Patch did not change ${path}`);
  if (path.endsWith('.js')) {
    const syntax = spawnSync(process.execPath, ['--check', '--input-type=module'], { input: updated, encoding: 'utf8' });
    if (syntax.status !== 0) throw new Error(`Invalid JavaScript after Android patch ${path}: ${syntax.stderr}`);
  }
  await writeFile(file, updated);
}
function replaceOnce(text, from, to) {
  if (text.split(from).length !== 2) throw new Error(`Patch anchor count: ${from.slice(0, 70)}`);
  return text.replace(from, to);
}
await patch('@deepseek-ai/dsh-system-prompt/lib/index.js', patchSystemPrompt);
await patch('@deepseek-ai/dsh-session-title-first-prompt-llm/lib/index.js', patchSessionTitleProvider);
await patch('@deepseek-ai/dsh-session-title-all-prompts-llm/lib/index.js', patchSessionTitleProvider);
await patch('@deepseek-ai/dsh-api-session-controller/lib/index.js', patchSessionModelSelection);
await patch('@deepseek-ai/dsh-llm/lib/index.js', patchPrivacyLlm);
await patch('@deepseek-ai/dsh-llm-pi-ai/lib/index.js', patchPrivacyPiAi);
await patch('@deepseek-ai/dsh-mcp-client/lib/index.js', patchExecutionMcp);
await patch('@deepseek-ai/dsh-fs-local/lib/index.js', patchExecutionFileMode);
await patch('@deepseek-ai/cordis-plugin-loader/lib/index.js', patchRequiredLoader);
await patch('@deepseek-ai/dsh-plugin-manager/lib/index.js', patchRequiredPluginManager);
await patch('@deepseek-ai/dsh-plugin-manager/lib/types/patch.js', patchRequiredPluginManagerPatch);
await patch('@vscode/ripgrep/lib/index.js', text => replaceOnce(text, 'let resolved;\ntry {', 'let resolved = process.env.DROY_RG_PATH;\nif (!resolved) try {'));
await patch('node-pty/lib/utils.js', patchNodePtyLoader);
await patch('node-addon-require-builtin/lib/index.js', () => `// Android runtime only; launched with --expose-internals.\nexports.requireBuiltin = id => require(id);\nexports.isAllowedInternalId = id => typeof id === 'string' && id.startsWith('internal/');\nexports.getBindingInfo = () => ({ platform: process.platform, backend: 'node-expose-internals' });\nexports.default = exports;\n`);
await patch('@deepseek-ai/node-addon-system/lib/flock.js', text => {
  const start = text.indexOf('    const { platform, arch } = process;');
  const end = text.indexOf('    return binding;', start);
  if (start < 0 || end < 0) throw new Error('flock patch anchor');
  return text.slice(0, start) + `    const module = { exports: {} };\n    if (!process.env.DROY_NATIVE_DIR) throw new Error('DROY_NATIVE_DIR required');\n    process.dlopen(module, join(process.env.DROY_NATIVE_DIR, 'libdroy_system.so'));\n    binding = module.exports;\n` + text.slice(end);
});
await patch('@deepseek-ai/dsh-session-persistence-jsonl/lib/index.js', text => {
  text = `import { publishExclusive as droyPublishExclusive } from '@droyagent/mobile/publish';\n` + text;
  text = replaceOnce(text, 'await internals.fs.link(staged, currentPath);', 'await droyPublishExclusive(staged, currentPath);');
  text = replaceOnce(text, 'await link(tmp, finalPath);', 'await droyPublishExclusive(tmp, finalPath);');
  return patchNativeCompanionMaintenance(text);
});
await patch('@deepseek-ai/dsh-api-gateway/lib/index.js', patchNativeMaintenanceGateway);
await patch('@deepseek-ai/dsh-schedule/lib/index.js', patchNativeScheduleMaintenance);
await patch('@deepseek-ai/dsh-host-webserver/lib/index.js', text => {
  text = patchNativeMaintenanceWebServer(text);
  text = `import { requireMobileSession, requireMobileUpgrade } from '@droyagent/mobile/auth';\n` + text;
  text = replaceOnce(text, 'const listener = (req, res) => {', 'const listener = (req, res) => {\n\t\t\tif (!requireMobileSession(req, res)) return;');
  return replaceOnce(text, 'this.server.on("upgrade", (req, socket, head) => {', 'this.server.on("upgrade", (req, socket, head) => {\n\t\t\t\tif (!requireMobileUpgrade(req, socket)) return;');
});
await patch('@deepseek-ai/dsh-client-connection/lib/index.js', text => replaceOnce(text, 'const created = encodeBase64Url(randomBytes(SECRET_BYTES));', "const created = process.env.DROY_WEB_TOKEN;\n\tif (!created || created.length < 32) throw new Error('DROY_WEB_TOKEN required');"));
await patch('@deepseek-ai/dsh/lib/bin.js', text => {
  text = replaceOnce(text, 'import { inspect } from "node:util";', "import { reportMobileStartupFailure } from '@droyagent/mobile/diagnostics';\nimport { mobilePackageManager } from '@droyagent/mobile/package-manager';");
  const start = text.indexOf('async function reportStartupFailure(');
  const end = text.indexOf('\n//#endregion', start);
  if (start < 0 || end < 0) throw new Error('startup diagnostic patch anchor');
  text = text.slice(0, start) + `async function reportStartupFailure(error, context, write = writeStderr) {\n  await reportMobileStartupFailure(error, context, write);\n}\n` + text.slice(end);
  return replaceOnce(text, 'if (import.meta.main) await runCli();', `if (import.meta.main) {\n  try { await runCli({ packageManager: process.env.DROY_RUNTIME_DIR ? mobilePackageManager() : undefined }); } catch (error) {\n    await reportMobileStartupFailure(error, {home: process.env.DSH_HOME});\n    process.exitCode = 1;\n  }\n}`);
});
await patch('@deepseek-ai/dsh-client-ui-settings-models/lib/client.js', patchModelsBranding);
await patch('@deepseek-ai/dsh-client-ui-layout/lib/client.js', patchLayout);
await patch('@deepseek-ai/dsh-client-ui-settings-general/lib/client.js', text => patchSpaceCopy(patchSettings(text)));
await patch('@deepseek-ai/dsh-client-ui-workspace/lib/client.js', text => patchSpaceCopy(patchWorkspace(text)));
await patch('@deepseek-ai/dsh-client-ui-conversation/lib/client.js', text => patchSpaceCopy(patchConversationBranding(patchConversation(text))));
await patch('@deepseek-ai/dsh-client-ui-chat/lib/client.js', patchComposerStats);
await patch('@deepseek-ai/dsh-client-locale/lib/client.js', text => patchSpaceCopy(patchLocalePersistence(text)));
await patch('@deepseek-ai/dsh-client-ui-directory-picker-browse/lib/client.js', patchSpaceCopy);
await patch('@deepseek-ai/dsh-client-ui-reference/lib/client.js', patchSpaceCopy);
await patch('@deepseek-ai/dsh-client-ui-agent-preset/lib/client.js', patchPresetCopy);
await patch('@deepseek-ai/dsh-experimental-client-ui-voice-input/lib/client.js', patchVoiceInput);
await patch('@deepseek-ai/dsh-client-ui-input-trigger/lib/client.js', patchInputTrigger);
await patch('@deepseek-ai/dsh-client-ui-commands/lib/client.js', patchMediaCommandMenu);
await patch('@deepseek-ai/dsh-client-ui-sidebar/lib/client.js', text => patchSidebarBranding(patchSidebar(text)));
await patch('@deepseek-ai/dsh-client-ui-settings-account/lib/client.js', patchAccountBranding);
await patch('@deepseek-ai/dsh-client-ui-entry-customization/lib/client.js', patchEntryCustomizationCopy);
await patch('@deepseek-ai/dsh-client-ui-plugin-manager/lib/client.js', text => patchProductCopy(text, 2));
await patch('@deepseek-ai/dsh-client-ui-sidebar-documentpreview/lib/client.js', text => patchProductCopy(text, 2));
await patch('@deepseek-ai/dsh-client-ui-shortcuts/lib/client.js', patchShortcutBranding);
await patch('@deepseek-ai/dsh-web-frontend/dist/index.html', patchFrontendBranding);
await patch('@deepseek-ai/dsh-web-frontend/dist/manifest.webmanifest', patchWebManifestBranding);
await patch('@deepseek-ai/dsh-client-ui-sidebar-right/lib/client.js', patchSidebarRight);
await patch('@deepseek-ai/dsh-client-ui-sidebar-browser/lib/client.js', patchSidebarBrowser);
await patch('@deepseek-ai/dsh-client-ui-model-selection/lib/client.js', patchModelSelection);
await patch('@deepseek-ai/dsh-web-frontend/dist/assets/index-BXV-oxvX.js', patchImageLightbox);
await patch('@deepseek-ai/dsh-client-ui-theme/lib/client.js', patchThemeSettings);
const overlay = serializeMobileProfile(await readUpstreamProfile(source));
await writeFile(join(nodeModules, '@droyagent/mobile/cordis.patch.yml'), overlay);
await writeFile(join(staging, 'mobile/cordis.patch.yml'), overlay);
// Android loads the APK's PTY library; host executables are not runtime inputs.
await rm(join(nodeModules, 'node-pty/prebuilds'), { recursive: true, force: true });
// Native packages for other hosts are not loadable on Android and are excluded.
async function prune(dir) {
  for (const entry of await readdir(dir, { withFileTypes: true })) {
    const path = join(dir, entry.name);
    if (entry.isDirectory()) {
      if (excluded(entry.name)) await rm(path, { recursive: true });
      else await prune(path);
    } else if (/\.(?:node|dylib|map)$/.test(entry.name) || /\.d\.(?:ts|mts|cts)$/.test(entry.name) || /^(?:\.DS_Store)$/.test(entry.name)) await rm(path);
  }
}
await prune(nodeModules);
await mkdir(join(generated, 'assets'), { recursive: true });
const zip = join(generated, 'assets/dsh-runtime.zip');
const python = `import os,sys,zipfile\nroot,out=sys.argv[1:]\nwith zipfile.ZipFile(out,'w',zipfile.ZIP_DEFLATED,compresslevel=6) as z:\n for base,dirs,files in os.walk(root):\n  dirs.sort()\n  for name in sorted(files):\n   p=os.path.join(base,name); rel=os.path.relpath(p,root)\n   info=zipfile.ZipInfo(rel,date_time=(2026,1,1,0,0,0)); info.compress_type=zipfile.ZIP_DEFLATED; info.external_attr=0o100600<<16\n   with open(p,'rb') as f: z.writestr(info,f.read())\n`;
const packed = spawnSync('python3', ['-c', python, staging, zip], { stdio: 'inherit' });
if (packed.status !== 0) throw new Error('zip packaging failed');
const digest = createHash('sha256').update(await readFile(zip)).digest('hex');
await writeFile(join(generated, 'assets/dsh-runtime.json'), JSON.stringify({ ...release, runtimeRevision: 1, sha256: digest, patches: hashes }, null, 2) + '\n');
console.log(`Prepared DSH ${version}: ${zip}`);
