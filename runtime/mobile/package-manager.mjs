import { existsSync } from 'node:fs';
import { join, resolve } from 'node:path';

export function mobilePackageManager({ runtime = process.env.DROY_RUNTIME_DIR, executable = process.execPath } = {}) {
  if (!runtime) throw new Error('DROY_RUNTIME_DIR required for the bundled package manager');
  const entry = join(resolve(runtime), 'node_modules', 'pnpm', 'bin', 'pnpm.cjs');
  if (!existsSync(entry)) throw new Error('Bundled pnpm is missing; reinstall this Droy runtime');
  // Android launches Node from its signed native-library directory. A pnpm
  // shell shim in writable app storage is not an executable on that platform.
  return { command: executable, args: [entry], env: { npm_node_execpath: executable } };
}
