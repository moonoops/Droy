import { readFile } from 'node:fs/promises';
import { join } from 'node:path';
import yaml from '../runtime/dsh/node_modules/js-yaml/index.js';
import { applyEntryPatches, entryListSchema } from '../runtime/dsh/node_modules/@deepseek-ai/cordis-plugin-include/lib/index.js';

export { applyEntryPatches, entryListSchema };
export const parsePatch = text => yaml.load(text, { schema: entryListSchema });
const failPatch = (message, id = '') => { throw new Error(`${message}: ${id}`); };

const PRUNER = '@deepseek-ai/dsh-compaction-tool-result-pruner';

function adaptHostPlugins(rows) {
  for (const row of rows) {
    // Head/tail slicing can remove the middle of a live Android node result.
    if (row.name === PRUNER) row.disabled = true;
    if (row.name === '@deepseek-ai/dsh-terminal-bash' && row.config?.shellDialect !== 'pwsh') {
      row.config = { ...row.config, shellPath: { __jsExpr: 'process.env.DROY_BASH_PATH' } };
    }
    if (row.group && Array.isArray(row.config)) adaptHostPlugins(row.config);
    if (row.name === '@deepseek-ai/dsh-agent-preset') adaptHostPlugins(row.config.plugins);
  }
}

export function assertNoToolResultPruner(rows) {
  for (const row of rows) {
    // Disabled presets never load their children; validate the active graph.
    if (row.disabled === true) continue;
    if (row.name === PRUNER) throw new Error(`DSH head/tail pruning is prohibited in Droy: ${row.id}`);
    if (row.group && Array.isArray(row.config)) assertNoToolResultPruner(row.config);
    if (row.name === '@deepseek-ai/dsh-agent-preset') assertNoToolResultPruner(row.config.plugins);
  }
}

// Read the upstream bundles before choosing Droy's built-in presets.
export async function readUpstreamProfile(nodeModules) {
  let entries = [];
  for (const name of ['@deepseek-ai/dsh-base', '@deepseek-ai/dsh-web-app']) {
    const directory = join(nodeModules, name);
    const manifest = JSON.parse(await readFile(join(directory, 'package.json'), 'utf8'));
    const files = manifest.dsh?.bundle?.patch;
    if (!files) throw new Error(`DSH bundle has no patch files: ${name}`);
    for (const file of Array.isArray(files) ? files : [files]) {
      entries = applyEntryPatches(entries, parsePatch(await readFile(join(directory, file), 'utf8')), failPatch);
    }
  }
  return entries;
}

export function mobileProfilePatches(upstream) {
  const byId = new Map(upstream.map(row => [row.id, row]));
  const config = (id, additions) => {
    const row = byId.get(id);
    if (!row) throw new Error(`DSH Android adaptation target missing: ${id}`);
    return { id, config: { ...structuredClone(row.config ?? {}), ...additions } };
  };
  const patches = [
    config('locale', { preference: { __jsExpr: "process.env.DROY_LOCALE === 'zh' ? 'zh' : 'en'" } }),
    // Android normalizes images natively; the replacement below implements the full service.
    { id: 'attachment-local', disabled: true },
    // The replacement preserves upstream behavior before memory activation.
    { id: 'subprocess', disabled: true },
    { id: 'fs-sandbox', disabled: true },
    // Droy supplies the public brand slots; keep all model/provider features.
    { id: 'ui-brand-official', disabled: true },
    config('session-persistence-jsonl', { compression: 'none' }),
    config('session-log-deepseek', { enabled: false }),
    config('web-runtime', { openBrowser: false, printUrl: false }),
    config('webserver', { host: '127.0.0.1', port: { __jsExpr: 'Number(process.env.DROY_WEB_PORT)' } }),
    config('agent-preset-registry', { default: 'standard' }),
    // Upstream gates this ordinary browser tab to the desktop profile by name.
    { id: 'ui-sidebar-browser', disabled: false },
  ];
  for (const row of upstream) {
    if (row.name === '@deepseek-ai/dsh-agent-preset') continue;
    const updated = structuredClone(row);
    adaptHostPlugins([updated]);
    if (JSON.stringify(updated) !== JSON.stringify(row)) patches.push({ id: row.id,
      ...(updated.config === undefined ? {} : { config: updated.config }),
      ...(updated.disabled === row.disabled ? {} : { disabled: updated.disabled }) });
  }
  const presets = upstream.filter(row => row.name === '@deepseek-ai/dsh-agent-preset');
  for (const preset of presets) {
    if (!['standard', 'cordis'].includes(preset.config.id)) {
      patches.push({ id: preset.id, disabled: true });
      continue;
    }
    const updated = structuredClone(preset.config);
    const personas = updated.plugins?.filter(row => row.name === '@deepseek-ai/dsh-persona') ?? [];
    if (personas.length !== 1 || typeof personas[0].config?.prefix !== 'string') {
      throw new Error(`DSH preset persona needs Android adaptation review: ${preset.id}`);
    }
    if (personas[0].config.suffix !== undefined && typeof personas[0].config.suffix !== 'string') {
      throw new Error(`DSH preset persona suffix needs Android adaptation review: ${preset.id}`);
    }
    // Replace only reviewed upstream defaults. User-authored personas are not
    // rewritten; the assembly adapter contributes the product rules separately.
    const identity = personas[0].config.prefix;
    if (identity === 'You are a coding agent powered by the {{model}} model.') {
      personas[0].config.prefix = 'The model configured for this session is {{model}}.';
    } else if (identity === 'You are a helpful software engineer assistant.') {
      personas[0].config.prefix = '';
    }
    adaptHostPlugins(updated.plugins);
    patches.push({ id: preset.id, config: updated });
  }
  patches.push({ insert: [
    { id: 'droy-plugin-policy', name: '@droyagent/mobile/plugin-policy' },
    { id: 'droy-memory-host', name: '@droyagent/mobile/memory-host' },
    { id: 'droy-privacy', name: '@droyagent/privacy' },
    { id: 'droy-execution', name: '@droyagent/execution' },
    { id: 'droy-memory', name: '@droyagent/memory' },
    { id: 'speech-to-text', name: '@deepseek-ai/dsh-experimental-speech-to-text', config: {
      defaultProvider: 'droy-sensevoice-local',
    } },
    { id: 'api-speech-to-text', name: '@deepseek-ai/dsh-experimental-api-speech-to-text' },
    { id: 'ui-voice-input', name: '@deepseek-ai/dsh-experimental-client-ui-voice-input' },
    { id: 'droy-initialize', name: '@droyagent/mobile/initialize' },
    { id: 'droy-skills', name: '@deepseek-ai/dsh-skill-filesystem', config: {
      providerName: 'droy-android',
      includeDefaultRoots: false,
      customSkillDirs: [{ __jsExpr: "process.env.DSH_HOME + '/../droy-skills'" }],
      bundledSkillDir: { __jsExpr: "process.env.DROY_RUNTIME_DIR + '/mobile/skills'" },
    } },
    { id: 'droy-lifecycle', name: '@droyagent/mobile/lifecycle' },
    { id: 'droy-attachments', name: '@droyagent/mobile/attachments',
      config: { ...structuredClone(byId.get('attachment-local')?.config ?? {}), maxImagesPerMessage: 10 } },
    { id: 'droy-android-use', name: '@droyagent/mobile' },
    { id: 'droy-media-input', name: '@droyagent/media-input' },
    { id: 'droy-voice', name: '@droyagent/voice' },
    { id: 'droy-notifications', name: '@droyagent/mobile/notifications' },
  ] });
  assertNoToolResultPruner(applyEntryPatches(upstream, patches, failPatch));
  return patches;
}

export function serializeMobileProfile(upstream) {
  return '# Droy base/web profile with standard and creator presets and Android adaptations.\n'
    + yaml.dump(mobileProfilePatches(upstream), { schema: entryListSchema, noRefs: true, lineWidth: -1 });
}
