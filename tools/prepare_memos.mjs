import { readFileSync, writeFileSync, mkdirSync, readdirSync } from 'node:fs';
import { resolve, join, dirname } from 'node:path';
import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { stripTypeScriptTypes } from 'node:module';
import { fileURLToPath } from 'node:url';

const COMMIT = '64388b50e2a15528bcdf032e65a1ac792bcf63ee';
const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const destination = join(root, 'runtime/memory/memos/upstream');
const source = process.argv[2];
if (!source) throw new Error('Usage: node tools/prepare_memos.mjs /path/to/MemOS-checkout');
if (execFileSync('git', ['rev-parse', 'HEAD'], { cwd: source, encoding: 'utf8' }).trim() !== COMMIT) throw new Error('MEMOS_SOURCE_COMMIT_MISMATCH');
const base = join(source, 'apps/memos-local-plugin');
const files = ['core/time.ts', 'core/runtime/namespace.ts', 'core/storage/migrator.ts',
  'core/storage/policy-metadata-backfill.ts', 'core/storage/keyword.ts', 'core/storage/vector.ts',
  'core/storage/tx.ts', 'core/storage/repos/_helpers.ts', 'core/storage/repos/traces.ts'];
files.push(...readdirSync(join(base, 'core/storage/migrations')).filter(name => name.endsWith('.sql')).map(name => 'core/storage/migrations/' + name));
const manifest = { repository: 'https://github.com/MemTensor/MemOS', commit: COMMIT, packageVersion: '2.0.16-beta.1',
  generation: 'Node stripTypeScriptTypes transform; relative .js module extensions changed to .mjs. Android storage and private logger are explicit Droy adapters.', files: [] };
for (const path of files) {
  const sourceText = readFileSync(join(base, path), 'utf8');
  const tracked = execFileSync('git', ['show', COMMIT + ':apps/memos-local-plugin/' + path], { cwd: source, encoding: 'utf8', maxBuffer: 8 * 1024 * 1024 });
  if (tracked !== sourceText) throw new Error('MEMOS_SOURCE_MODIFIED:' + path);
  const target = path.replace(/\.ts$/, '.mjs');
  const output = path.endsWith('.ts')
    ? '// Generated from MemTensor/MemOS ' + COMMIT + ': ' + path + '\n' + stripTypeScriptTypes(sourceText, { mode: 'transform' }).replace(/(from\s+["']\.[^"']*)\.js(["'])/g, '$1.mjs$2')
    : sourceText;
  mkdirSync(dirname(join(destination, target)), { recursive: true });
  writeFileSync(join(destination, target), output);
  manifest.files.push({ path, sha256: createHash('sha256').update(sourceText).digest('hex'), generated: target,
    generatedSha256: createHash('sha256').update(output).digest('hex') });
}
writeFileSync(join(destination, 'LICENSE'), execFileSync('git', ['show', COMMIT + ':LICENSE'], { cwd: source }));
writeFileSync(join(destination, 'manifest.json'), JSON.stringify(manifest, null, 2) + '\n');
console.log('Prepared ' + manifest.files.length + ' pinned MemOS files.');
