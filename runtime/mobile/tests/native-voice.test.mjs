import test from 'node:test';
import assert from 'node:assert/strict';
import { Readable } from 'node:stream';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createNativeVoice } from '../native-voice.mjs';
import { createConfigStore } from '../../voice/config.mjs';
import { createVoiceService } from '../../voice/http.mjs';

async function fixture(t, locale = 'zh') {
  const home = await mkdtemp(join(tmpdir(), 'droy-native-voice-')); t.after(() => rm(home, { recursive: true, force: true }));
  const keys = new Map(), calls = [];
  const store = createConfigStore({ home, env: {}, credentials: { resolve: async key => keys.has(key) ? { value: keys.get(key) } : undefined,
    set: async (key, value) => keys.set(key, value), unset: async key => keys.delete(key) } });
  let failKey = false, failSave = false, failStatus = false;
  const saved = store.save.bind(store);
  store.save = patch => {
    calls.push({ operation: 'save_config', patch });
    if (patch.tts?.key && failKey || patch.wake && failSave) throw Error('fictional-private-error-token');
    return saved(patch);
  };
  const preparation = { state: 'ready', ready: true, downloadedBytes: 12, totalBytes: 12, error: null };
  let wake = { enabled: false, chineseKeyword: '你好测试', englishKeyword: 'Hey Test', timing: 'screen_on', useCompanionName: false,
    screenAvailable: true, suspended: false, pausedForVoice: false, voiceClaimed: false, companionName: 'Fixture', state: 'off',
    preparation, error: null, captureId: 'private-fixture-capture', keyword: 'private-fixture-keyword' };
  const native = async input => {
    calls.push(input);
    if (input.operation === 'status') { if (failStatus) throw Error('fictional-private-error-token'); return { state: 'recording', speaking: false, error: null, captureId: 'private-fixture-capture' }; }
    if (input.operation === 'wake.status') return { ...wake };
    if (input.operation === 'wake.configure') {
      wake = { ...wake, ...input, chineseKeyword: input.chinese.trim().replace(/\s+/gu, ' '), englishKeyword: input.english.trim().replace(/\s+/gu, ' '),
        state: input.enabled ? 'listening' : 'off' }; return { ...wake };
    }
    if (input.operation === 'wake.prepare') { wake.preparation = { ...preparation, ready: false, state: 'preparing' }; return { ...wake }; }
    if (input.operation === 'voices') return { ready: true, systemReady: false, systemError: 'synthetic unavailable engine',
      voices: [{ id: 'fixture-voice', name: 'Fixture Voice', provider: 'system', language: 'en-US', networkRequired: true }] };
    if (input.operation === 'tts.models') return { models: [{ provider: 'melo', ...preparation }, { provider: 'qwen3', ...preparation }] };
    if (input.operation === 'tts.download' || input.operation === 'tts.cancel_download') return { models: [{ provider: input.provider,
      ...preparation, ready: false, state: input.operation === 'tts.download' ? 'downloading' : 'not_downloaded' }] };
    return {};
  };
  const service = createVoiceService({ store, native, realtime: { active: false }, getLocale: () => locale,
    fetchImpl: () => assert.fail('Settings tests must never call a speech provider') });
  const ctx = { get: name => ({ droyVoice: service, droyAudio: { refreshPreparation: async () => {}, snapshot: () => ({
    preparation: { phase: 'downloading', completedBytes: 4, totalBytes: 16 } }) }, settings: { describe: () => [{ ns: 'locale', value: { preference: locale } }] } })[name] };
  const api = createNativeVoice(ctx);
  const request = async (path = '', input, signal) => {
    const data = input === undefined ? Buffer.alloc(0) : Buffer.from(JSON.stringify(input));
    const req = Readable.from(data.length ? [data] : []); req.method = input === undefined ? 'GET' : 'POST';
    req.url = `/droy/native/voice${path}`; req.headers = { host: '127.0.0.1', 'content-type': 'application/json', 'content-length': String(data.length) };
    let status, response;
    const res = { writeHead: value => { status = value; }, end: data => { response = JSON.parse(data); } };
    await api.handle(req, res, signal); return { status, ...response };
  };
  return { request, service, store, calls, keys, failKey: value => { failKey = value; }, failSave: value => { failSave = value; }, failStatus: value => { failStatus = value; }, setWake: value => { wake = { ...wake, ...value }; } };
}
const tts = { provider: 'openai', voiceId: 'fixture', rate: 1.15, url: 'https://speech.invalid/v1', model: 'fixture-tts' };
const wake = { enabled: false, timing: 'foreground', useCompanionName: false, chinese: '你好测试', english: 'Hey Test' };

test('snapshot projects live state, ASR progress and actual system readiness without private audio fields', async t => {
  const f = await fixture(t); const response = await f.request();
  assert.equal(response.status, 200); assert.equal(response.result.runtime.state, 'recording');
  assert.equal(response.result.voices.items[0].language, 'en-US');
  assert.equal(response.result.voices.systemReady, false); assert.equal(response.result.voices.items[0].networkRequired, true);
  assert.equal(response.result.asr.downloadedBytes, 4); assert.equal(response.result.asr.totalBytes, 16);
  assert.doesNotMatch(JSON.stringify(response), /private-fixture|synthetic unavailable engine|realtime|hasVoiceprint/);
  assert.equal(f.calls.some(row => ['start', 'speak', 'wake.configure'].includes(row.operation)), false);
});

test('wake status remains readable after removing the legacy companion-name binding', async t => {
  const f = await fixture(t); f.setWake({ companionName: undefined });
  const response = await f.request();
  assert.equal(response.status, 200);
  assert.equal(response.result.wake.available, true);
  assert.equal(response.result.wake.companionName, '');
  assert.equal(response.result.wake.useCompanionName, false);
});

test('missing runtime facts remain unavailable instead of becoming idle or ready', async t => {
  const f = await fixture(t); f.failStatus(true); const response = await f.request();
  assert.equal(response.result.runtime.available, false); assert.equal(response.result.preview.available, false);
  assert.doesNotMatch(JSON.stringify(response), /fictional-private-error/);
});

test('older service-valid values remain readable so users can correct them in the native editor', async t => {
  const f = await fixture(t);
  await f.store.save({ tts: { url: 'wss://speech.invalid/v1' }, wake: { english: 'a'.repeat(60) } });
  const response = await f.request(); assert.equal(response.status, 200); assert.equal(response.result.config.wake.english.length, 60);
  assert.equal(response.result.config.tts.url, 'wss://speech.invalid/v1');
  assert.equal((await f.request('/settings', { patch: { tts: { ...tts, url: 'wss://speech.invalid/v1' } } })).status, 400);
});

test('TTS settings and credentials use separate explicit writes and blank key never clears an existing credential', async t => {
  const f = await fixture(t);
  assert.equal((await f.request('/settings', { patch: { tts } })).ok, true);
  assert.equal(f.keys.size, 0);
  assert.equal((await f.request('/credentials', { operation: 'set', value: 'fictional-tts-credential' })).result.hasKey, true);
  const saved = await f.request(); assert.equal(saved.result.config.tts.hasKey, true);
  assert.doesNotMatch(JSON.stringify(saved), /fictional-tts-credential/);
  assert.equal((await f.request('/credentials', { operation: 'set', value: '' })).status, 400); assert.equal(f.keys.size, 1);
  assert.equal((await f.request('/credentials', { operation: 'clear' })).result.hasKey, false);
});

test('credential failure preserves the committed provider and is safe to retry by itself', async t => {
  const f = await fixture(t, 'en'); await f.request('/settings', { patch: { tts } }); f.failKey(true);
  const response = await f.request('/credentials', { operation: 'set', value: 'fictional-tts-credential' });
  assert.equal(response.error.code, 'NATIVE_VOICE_CREDENTIAL_NOT_CONFIRMED'); assert.match(response.error.message, /Credential save/);
  assert.doesNotMatch(JSON.stringify(response), /fictional-/); assert.equal((await f.store.read()).tts.provider, 'openai');
  f.failKey(false); await f.request('/credentials', { operation: 'set', value: 'fictional-tts-credential' });
  assert.equal(f.calls.filter(row => row.operation === 'save_config' && row.patch.tts?.provider).length, 1);
});

test('wake second-step failure reports native application and retries only the matching configuration', async t => {
  const f = await fixture(t); f.failSave(true);
  const response = await f.request('/wake', { patch: wake }); assert.equal(response.error.code, 'NATIVE_VOICE_WAKE_NOT_SAVED');
  assert.equal(f.calls.filter(row => row.operation === 'wake.configure').length, 1);
  const snapshot = await f.request(); assert.equal(snapshot.result.wake.timing, 'foreground'); assert.equal(snapshot.result.config.wake.timing, 'screen_on');
  f.failSave(false); assert.equal((await f.request('/wake', { patch: wake, commitOnly: true })).ok, true);
  assert.equal(f.calls.filter(row => row.operation === 'wake.configure').length, 1);
  f.setWake({ timing: 'screen_on' }); assert.equal((await f.request('/wake', { patch: wake, commitOnly: true })).error.code, 'NATIVE_VOICE_WAKE_CONFLICT');
});

test('native wake normalization is what is committed, and no permission is silently enabled', async t => {
  const f = await fixture(t); const response = await f.request('/wake', { patch: { ...wake, english: 'Hey  Test' } });
  assert.equal(response.result.config.wake.english, 'Hey Test');
  assert.equal(f.calls.some(row => row.operation.includes('permission') || row.operation === 'start'), false);
  assert.equal((await f.request('/wake/prepare', {})).result.wake.preparation.state, 'preparing');
});

test('resource download and cancellation return actual service receipts', async t => {
  const f = await fixture(t);
  assert.equal((await f.request('/resources', { provider: 'melo', operation: 'download' })).result.items[0].state, 'downloading');
  assert.equal((await f.request('/resources', { provider: 'melo', operation: 'cancel' })).result.items[0].state, 'not_downloaded');
  assert.equal((await f.request('/resources', { provider: 'unknown', operation: 'download' })).status, 400);
});

test('only actual permission denial is presented as a permission failure', async t => {
  const f = await fixture(t);
  f.service.run = async () => { throw Object.assign(Error('fictional-private-error-token'), { code: 'REJECTED' }); };
  assert.equal((await f.request('/resources', { provider: 'melo', operation: 'download' })).error.code, 'NATIVE_VOICE_FAILED');
  f.service.run = async () => { throw Object.assign(Error('fictional-private-error-token'), { code: 'PERMISSION_DENIED' }); };
  assert.equal((await f.request('/resources', { provider: 'melo', operation: 'download' })).error.code, 'NATIVE_VOICE_PERMISSION');
});

test('voice adapter only accepts bounded declared settings and never opens dormant features', async t => {
  const f = await fixture(t);
  for (const patch of [{ realtime: { provider: 'gemini' } }, { tts: { ...tts, key: 'fictional-secret' } }, { tts: { ...tts, provider: 'qwen3', rate: 1.15 } },
    { tts: { ...tts, url: 'https://speech.invalid/v1?api_key=fictional-secret' } }]) assert.equal((await f.request('/settings', { patch })).status, 400);
  assert.equal((await f.request('/wake', { patch: { ...wake, chinese: '测'.repeat(49) } })).status, 400);
  assert.equal((await f.request('/preview', { operation: 'start', owner: 'fixture', provider: 'system', voiceId: '', rate: 1, text: 'private input must not leave' })).status, 400);
  assert.equal((await f.request('/preview', { operation: 'start', owner: 'fixture', provider: 'openai', voiceId: '', rate: 1 })).status, 400);
});

test('request cancellation is explicit and keeps raw upstream failures out of both languages', async t => {
  for (const language of ['zh', 'en']) {
    const f = await fixture(t, language), abort = new AbortController(); abort.abort(new Error('fictional-private-error-token'));
    const response = await f.request('', undefined, abort.signal); assert.equal(response.error.code, 'NATIVE_VOICE_CANCELLED');
    assert.doesNotMatch(JSON.stringify(response), /fictional-private-error-token/);
    assert.match(response.error.message, language === 'zh' ? /请求已取消/ : /Request cancelled/);
  }
});
