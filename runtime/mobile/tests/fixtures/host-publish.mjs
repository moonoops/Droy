// Host-only stand-in for renameat2(RENAME_NOREPLACE). The production publication
// path still performs its staging, fsync, cleanup, and source preservation.
import { linkSync, unlinkSync } from 'node:fs';
import { publishExclusive as publish } from '../../publish.mjs';

export function publishExclusive(source, destination) {
  return publish(source, destination, (staged, target) => {
    try { linkSync(staged, target); unlinkSync(staged); return 0; }
    catch (error) { return Math.abs(error.errno); }
  });
}
