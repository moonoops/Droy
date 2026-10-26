import { mkdir, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { randomUUID } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { dirname } from 'node:path';
import { resolveBuiltinPackage, runtimeVersions } from './versions.mjs';

// This vocabulary only redacts diagnostics; it never selects enabled features.
const components = new Set(['droy-attachments', 'droy-android-use', 'droy-initialize', 'droy-lifecycle', 'droy-media-input']);
for (const name of ['@deepseek-ai/dsh-base', '@deepseek-ai/dsh-web-app']) {
  const path = resolveBuiltinPackage(name);
  const manifest = JSON.parse(readFileSync(path, 'utf8'));
  for (const patch of [manifest.dsh.bundle.patch].flat()) {
    const text = readFileSync(join(dirname(path), patch), 'utf8');
    for (const match of text.matchAll(/^\s*- id: ([\w-]+)\s*$/gm)) components.add(match[1]);
  }
}
const own = (value, key) => value && typeof value === 'object' ? Object.getOwnPropertyDescriptor(value, key)?.value : undefined;

// StartupError retains the entire plugin graph. Only closed vocabulary fields
// may cross this diagnostic boundary; never serialize errors or their messages.
export function startupDiagnostic(error) {
  const entries = [];
  try {
    const source = own(error, 'entries');
    if (Array.isArray(source)) for (let index = 0; index < Math.min(source.length, 128); index++) {
      const entry = own(source, String(index));
      const id = own(entry, 'id');
      const kind = own(own(entry, 'outcome'), 'kind');
      entries.push({ component: components.has(id) ? id : 'unknown-component', state: ['failed', 'pending'].includes(kind) ? kind : 'unknown' });
    }
  } catch { entries.length = 0; }
  return { code: 'DROY_STARTUP_FAILED', ...runtimeVersions, components: entries };
}

export async function reportMobileStartupFailure(error, context, write = text => new Promise(resolve => process.stderr.write(text, resolve))) {
  const report = JSON.stringify(startupDiagnostic(error)) + '\n';
  await write(report);
  try {
    const directory = join(context.home, 'logs');
    await mkdir(directory, { recursive: true, mode: 0o700 });
    await writeFile(join(directory, `startup-${randomUUID()}.log`), report, { flag: 'wx', mode: 0o600 });
  } catch {
    await write('DROY_DIAGNOSTIC_WRITE_FAILED\n');
  }
}
