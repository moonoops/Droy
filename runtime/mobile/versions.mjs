import { readFileSync, existsSync } from 'node:fs';
import { createRequire } from 'node:module';

// Source tests use the locked installation next to mobile/. Packaged modules
// resolve from the APK's runtime, never from the user plugin dependency tree.
const developmentManifest = new URL('../dsh/package.json', import.meta.url);
const require = createRequire(existsSync(developmentManifest) ? developmentManifest : import.meta.url);
export const resolveBuiltinPackage = name => require.resolve(`${name}/package.json`);
const dsh = JSON.parse(readFileSync(resolveBuiltinPackage('@deepseek-ai/dsh'), 'utf8'));
const mobile = JSON.parse(readFileSync(new URL('./package.json', import.meta.url), 'utf8'));

export const runtimeVersions = Object.freeze({ dshVersion: dsh.version, mobileVersion: mobile.version });
