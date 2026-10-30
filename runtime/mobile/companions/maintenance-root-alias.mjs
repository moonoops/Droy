import { lstat, realpath } from 'node:fs/promises';
import { isAbsolute, join, relative, resolve } from 'node:path';

/** The Android owner supplies its pre-canonical home, never an arbitrary cwd alias. */
export async function createTrustedRootAlias(canonicalRoot, trustedRootAlias, fail) {
  if (trustedRootAlias === undefined) return { value: null, path: async path => resolve(path), retainedPath: async path => resolve(path) };
  if (!trustedRootAlias || typeof trustedRootAlias !== 'object' || Array.isArray(trustedRootAlias)
    || Object.keys(trustedRootAlias).sort().join(',') !== 'aliasRoot,canonicalRoot'
    || trustedRootAlias.canonicalRoot !== canonicalRoot || typeof trustedRootAlias.aliasRoot !== 'string'
    || !isAbsolute(trustedRootAlias.aliasRoot) || resolve(trustedRootAlias.aliasRoot) !== trustedRootAlias.aliasRoot) fail('ROOT_ALIAS_INVALID');
  const value = { canonicalRoot, aliasRoot: trustedRootAlias.aliasRoot };
  async function roots() {
    const [canonical, alias] = await Promise.all([lstat(canonicalRoot), lstat(value.aliasRoot)]);
    if (!canonical.isDirectory() || !alias.isDirectory() || canonical.isSymbolicLink() || alias.isSymbolicLink()
      || canonical.dev !== alias.dev || canonical.ino !== alias.ino
      || await realpath(canonicalRoot) !== canonicalRoot || await realpath(value.aliasRoot) !== canonicalRoot) fail('ROOT_ALIAS_INVALID');
  }
  await roots();
  const beneath = (root, path) => path === root || path.startsWith(root + '/');
  async function path(input) {
    if (typeof input !== 'string' || !isAbsolute(input)) fail('ROOT_ALIAS_PATH_INVALID');
    const stored = resolve(input);
    if (stored !== input) fail('ROOT_ALIAS_PATH_INVALID');
    const prefix = beneath(value.aliasRoot, stored) ? value.aliasRoot : beneath(canonicalRoot, stored) ? canonicalRoot : null;
    if (!prefix) return stored;
    await roots();
    const suffix = relative(prefix, stored), parts = suffix ? suffix.split('/') : [];
    let actual = prefix, canonical = canonicalRoot;
    for (const part of parts) {
      actual = join(actual, part); canonical = join(canonical, part);
      let left, right;
      try { [left, right] = await Promise.all([lstat(actual), lstat(canonical)]); }
      catch (error) { fail('ROOT_ALIAS_PATH_INVALID', error); }
      // Only directories are valid session cwd/workspace paths. In particular,
      // an alias below home must not turn an outside reference into ownership.
      if (!left.isDirectory() || !right.isDirectory() || left.isSymbolicLink() || right.isSymbolicLink()
        || left.dev !== right.dev || left.ino !== right.ino) fail('ROOT_ALIAS_PATH_INVALID');
    }
    if (await realpath(actual) !== canonical) fail('ROOT_ALIAS_PATH_INVALID');
    return canonical;
  }
  async function retainedPath(input) {
    const mapped = await path(input);
    if (beneath(canonicalRoot, mapped)) return mapped;
    // An outside symlink never establishes ownership, but a retained cwd using
    // it still prevents destruction of the physical directory it references.
    try { return await realpath(mapped); } catch (error) { if (error.code === 'ENOENT') return mapped; throw error; }
  }
  return { value, path, retainedPath };
}
