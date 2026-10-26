import test from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import { readFile } from 'node:fs/promises';
import { patchLocalePersistence } from '../../../tools/mobile_locale_patch.mjs';
import { readUpstreamProfile, mobileProfilePatches } from '../../../tools/mobile_profile.mjs';

const upstream = await readFile(new URL('../../dsh/node_modules/@deepseek-ai/dsh-client-locale/lib/client.js', import.meta.url), 'utf8');
const patched = patchLocalePersistence(upstream);
let LocaleRuntime;
vm.runInNewContext(patched, { navigator: { languages: ['en'] }, window: { __ModuleLoader__: { load({ factory }) {
  LocaleRuntime = factory(() => ({})).LocaleRuntime;
} } } });

function fixture(set) {
  const changes = [];
  const host = { getSnapshot: () => ({ value: { preference: 'zh' } }), subscribe: () => () => {}, set };
  const locale = new LocaleRuntime({ emit: (_, snapshot) => changes.push(snapshot.active), effect: run => run() }, host);
  return { locale, changes };
}

test('language is published only after Host accepts the selection', async () => {
  let accept;
  const { locale, changes } = fixture((field, value) => {
    assert.equal(field, 'preference'); assert.equal(value, 'en');
    return new Promise(resolve => { accept = resolve; });
  });
  changes.length = 0;
  const saving = locale.setLocale('en');
  assert.equal(locale.getSnapshot().active, 'zh');
  assert.deepEqual(changes, []);
  accept(true); await saving;
  assert.equal(locale.getSnapshot().active, 'en');
  assert.deepEqual(changes, ['en']);
});

test('rejected or failed writes preserve the accepted language and surface failure', async () => {
  const { locale, changes } = fixture(async () => false);
  changes.length = 0;
  await assert.rejects(locale.setLocale('en'), /语言未保存/);
  assert.equal(locale.getSnapshot().active, 'zh');
  assert.deepEqual(changes, []);
  locale.host.set = async () => { throw new Error('fixture disconnected'); };
  await assert.rejects(locale.setLocale('en'), /fixture disconnected/);
  assert.equal(locale.getSnapshot().active, 'zh');
});

test('standalone locale still supports direct switching and rejects unknown ids', async () => {
  const locale = new LocaleRuntime({ emit() {} }, undefined, { languages: ['zh-CN'], preference: null });
  assert.equal(locale.getSnapshot().active, 'zh');
  await locale.setLocale('en');
  assert.equal(locale.getSnapshot().active, 'en');
  await assert.rejects(locale.setLocale('unknown'), /not registered/);
});

test('mobile locale default comes from the Android launch environment', async () => {
  const rows = await readUpstreamProfile(new URL('../../dsh/node_modules/', import.meta.url).pathname);
  const preference = mobileProfilePatches(rows).find(row => row.id === 'locale').config.preference.__jsExpr;
  const evaluate = DROY_LOCALE => vm.runInNewContext(preference, { process: { env: { DROY_LOCALE } } });
  assert.equal(evaluate('zh'), 'zh');
  assert.equal(evaluate('en'), 'en');
  assert.equal(evaluate(undefined), 'en');
});

test('upstream locale anchor drift is rejected', () => {
  assert.throws(() => patchLocalePersistence('changed upstream'), /contract changed/);
});

test('General language row surfaces native failure and retries the same accepted language', async () => {
  let plugin, pending = Promise.resolve(), failNative = false, registration, active;
  const calls = [], events = new Map();
  const bridge = {
    read: async () => ({ languages: ['zh'], preference: 'zh' }),
    onChange(language) {
      calls.push(language);
      pending = failNative ? Promise.reject(new Error('fixture native timeout')) : Promise.resolve();
      pending.catch(() => {});
      return pending;
    },
    whenSynced: () => pending,
  };
  const window = { __ModuleLoader__: { load({ factory }) {
    plugin = factory(name => name === '@deepseek-ai/dsh-client-store' ? { defineStore: value => value } : {});
  } } };
  vm.runInNewContext(patched, { window, __DSH_LOCALE__: bridge });
  const host = { getSnapshot: () => ({ value: { preference: 'zh' } }), subscribe: () => () => {}, set: async () => true };
  await plugin.apply({ fiber: { uid: 'fixture' }, configForms: { get: () => host },
    effect: effect => effect(), provide: (_, locale) => { active = locale; },
    on: (event, callback) => events.set(event, callback), emit: (event, value) => events.get(event)?.(value),
    slots: { installLocale() {}, inject: (_, register) => register(), register: options => { registration = options; } },
  });
  const row = registration.inject({ sync() {} });
  failNative = true;
  await assert.rejects(row.setLocale('en'), /native timeout/);
  assert.equal(active.getSnapshot().active, 'en', 'Host selection was accepted before the native failure');
  failNative = false;
  await row.setLocale('en');
  assert.deepEqual(calls, ['zh', 'en', 'en']);
});
