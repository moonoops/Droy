import { readFileSync } from 'node:fs';

// DSH serves one ModuleLoader registration; inline the independent client helpers.
export function composeMobileClient(client, touch, usage = readFileSync(new URL('../runtime/mobile/token-usage-client.js', import.meta.url), 'utf8')) {
  const marker = '/* DROY_TOUCH_MODULE */';
  if (client.split(marker).length !== 2) throw new Error('Mobile client touch module marker must occur exactly once');
  if (!/^function createTouchController\(/m.test(touch)) throw new Error('Mobile touch controller declaration missing');
  const usageMarker = '/* DROY_TOKEN_USAGE_MODULE */';
  if (client.split(usageMarker).length !== 2) throw new Error('Mobile client token usage module marker must occur exactly once');
  if (!/^function installTokenUsageClient\(/m.test(usage)) throw new Error('Mobile token usage declaration missing');
  const setupMarker = '/* DROY_SETUP_MODULE */';
  if (client.split(setupMarker).length !== 2) throw new Error('Mobile setup module marker must occur exactly once');
  const setup = readFileSync(new URL('../runtime/mobile/setup-client.js', import.meta.url), 'utf8');
  if (!/^function installSetupClient\(/m.test(setup)) throw new Error('Mobile setup declaration missing');
  const copyMarker = '/* DROY_UI_COPY_MODULE */';
  if (client.split(copyMarker).length !== 2) throw new Error('Mobile UI copy marker must occur exactly once');
  const copy = readFileSync(new URL('../runtime/mobile/ui-copy.js', import.meta.url), 'utf8');
  const companionMarker = '/* DROY_COMPANION_MODULE */';
  if (client.split(companionMarker).length !== 2) throw new Error('Mobile companion module marker must occur exactly once');
  const companionFiles = ['copy.js','client.js','input-adapters.js'];
  const companion = companionFiles.map(file => readFileSync(new URL('../runtime/mobile/companions/' + file, import.meta.url), 'utf8')).join('\n');
  const companionStyles = readFileSync(new URL('../runtime/mobile/companions/client.css', import.meta.url), 'utf8');
  return client.replace(copyMarker, () => copy).replace(marker, () => touch).replace(usageMarker, () => usage).replace(setupMarker, () => setup)
    .replace(companionMarker, () => 'const DROY_COMPANION_STYLES = ' + JSON.stringify(companionStyles) + ';\n' + companion);
}
