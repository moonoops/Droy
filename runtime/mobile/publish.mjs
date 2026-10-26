import { createRequire } from 'node:module';
import { copyFile, open, rm } from 'node:fs/promises';
import { constants } from 'node:fs';
import { dirname, join } from 'node:path';
import { randomUUID } from 'node:crypto';
import { getSystemErrorName } from 'node:util';
let binding;
export function nativeSystem() {
  if (!binding) {
    if (!process.env.DROY_NATIVE_DIR) throw new Error('DROY_NATIVE_DIR is required');
    const module = { exports: {} };
    process.dlopen(module, join(process.env.DROY_NATIVE_DIR, 'libdroy_system.so'));
    binding = module.exports;
  }
  return binding;
}

// Android SELinux denies hard links for app data. Copy privately beside the target,
// fsync, then publish atomically without replacement. The source stays untouched.
export async function publishExclusive(source, destination, renameNoReplace = (a, b) => nativeSystem().renameNoReplace(a, b)) {
  const temporary = `${destination}.droy-${randomUUID()}.tmp`;
  try {
    await copyFile(source, temporary, constants.COPYFILE_EXCL);
    const handle = await open(temporary, 'r+');
    try { await handle.sync(); } finally { await handle.close(); }
    const errno = renameNoReplace(temporary, destination);
    if (errno !== 0) {
      const code = getSystemErrorName(-errno);
      throw Object.assign(new Error(`${code}: atomic publish failed`), { code, errno, syscall: 'renameat2' });
    }
    const directory = await open(dirname(destination), 'r');
    try { await directory.sync(); } finally { await directory.close(); }
  } finally {
    await rm(temporary, { force: true });
  }
}
