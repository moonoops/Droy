import test from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { PrivacyStore } from '../../privacy/store.mjs';
import { createDshPrivacyService } from '../../privacy/dsh-plugin.mjs';
import { createSpeechService, createVoiceOutputState } from '../http.mjs';

const defer = () => { let resolve; const promise = new Promise(done => { resolve = done; }); return { promise, resolve }; };
const denied = error => error.code === 'TTS_PRIVATE_SESSION';

async function fixture(t) {
  const root = await mkdtemp(join(tmpdir(), 'droy-tts-privacy-'));
  const authority = await PrivacyStore.open({ directory: join(root, 'store'), markerPath: join(root, 'marker'), initialize: true });
  let active = true, locale = 'zh', receiver;
  const privacy = createDshPrivacyService({ ctx: { get: () => undefined }, store: authority,
    host: { status: async () => ({ active, ready: true }) } });
  await authority.registerCleanSession('clean'); await authority.registerCleanSession('other-clean');
  await authority.protectSession('protected', { categories: ['memory'], sourceRefs: ['synthetic-source'] });
  await authority.registerCleanSession('forgotten'); await authority.forgetSessions(['forgotten']);
  const requests = [], nativeCalls = [];
  const server = createServer(async (req, res) => {
    const chunks = []; for await (const chunk of req) chunks.push(chunk);
    requests.push(JSON.parse(Buffer.concat(chunks).toString()));
    if (receiver) return receiver(req, res);
    res.end(Buffer.from([0, 0]));
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const tts = { provider: 'openai', url: `http://127.0.0.1:${server.address().port}/v1`, voiceId: 'synthetic', rate: 1 };
  const store = { read: async () => ({ tts: { ...tts } }), key: async () => '', findVoice: async () => ({ ...tts }) };
  const native = async input => { nativeCalls.push(input); return input.operation === 'tts.models'
    ? { models: [{ provider: 'melo', ready: true }, { provider: 'qwen3', ready: true }] } : { queued: true }; };
  const speech = createSpeechService({ store, native, getLocale: () => locale, outputState: id => privacy.outputState(id) });
  t.after(async () => {
    await speech.stop(); privacy.dispose(); server.closeAllConnections();
    await new Promise(resolve => server.close(resolve)); await authority.close(); await rm(root, { recursive: true, force: true });
  });
  return { authority, privacy, speech, store, native, tts, requests, nativeCalls,
    setActive: value => { active = value; }, setLocale: value => { locale = value; }, setReceiver: value => { receiver = value; } };
}

test('protected, forgotten, unknown and absent sessions send zero text to real HTTP or system TTS', async t => {
  const f = await fixture(t);
  for (const sessionId of ['protected', 'forgotten', 'unknown', undefined]) {
    for (const provider of ['openai', 'system']) await assert.rejects(f.speech.speak({
      text: 'SYNTHETIC_PRIVATE_TTS_CANARY', sessionId, provider, public: true, isPublic: true, publicPreview: true,
    }), denied);
  }
  assert.equal(f.requests.length, 0);
  assert.equal(f.nativeCalls.some(call => call.operation === 'speak'), false);
});

test('before activation and known-clean speech keep their ordinary HTTP and system paths', async t => {
  const f = await fixture(t); f.setActive(false);
  await f.speech.speak({ text: 'SYNTHETIC_BEFORE_ACTIVATION' });
  await f.speech.speak({ text: 'SYNTHETIC_BEFORE_SYSTEM', provider: 'system' });
  f.setActive(true);
  await f.speech.speak({ text: 'SYNTHETIC_CLEAN', sessionId: 'clean' });
  await f.speech.speak({ text: 'SYNTHETIC_CLEAN_SYSTEM', sessionId: 'clean', provider: 'system' });
  assert.equal(f.requests.length, 2);
  assert.equal(f.nativeCalls.filter(call => call.operation === 'speak').length, 2);
});

test('explicit native Melo and Qwen remain usable without disclosing protected or unidentified text', async t => {
  const f = await fixture(t);
  for (const provider of ['melo', 'qwen3']) for (const sessionId of ['protected', undefined])
    await f.speech.speak({ text: 'SYNTHETIC_LOCAL_ONLY', sessionId, provider });
  assert.equal(f.requests.length, 0);
  assert.deepEqual(f.nativeCalls.filter(call => call.operation === 'speak').map(call => call.provider), ['melo', 'melo', 'qwen3', 'qwen3']);
  await assert.rejects(f.speech.speak({ text: 'SYNTHETIC_PRIVATE', sessionId: 'protected' }), error => denied(error) && /本地 Melo/.test(error.message));
  f.setLocale('en');
  await assert.rejects(f.speech.speak({ text: 'SYNTHETIC_PRIVATE', sessionId: 'protected' }), error => denied(error) && /local Melo/.test(error.message));
});

test('only a server-created fixed preview bypasses session policy, ignoring caller preview text', async t => {
  const f = await fixture(t);
  await f.speech.preview('synthetic-profile', { text: 'DO_NOT_SEND_THIS', public: true, sessionId: 'protected' });
  assert.equal(f.requests.length, 1);
  assert.equal(f.requests[0].input, '你好，我是 Droy。我们可以用这个声音聊天。');
  await assert.rejects(f.speech.speak({ text: 'DO_NOT_SEND_THIS', utteranceId: 'preview', sessionId: 'protected', public: true }), denied);
  assert.equal(f.requests.length, 1);
});

test('every queued reply chunk rechecks the actual durable session before another HTTP dispatch', async t => {
  const f = await fixture(t), entered = defer(), release = defer();
  f.setReceiver(async (_req, res) => { entered.resolve(); await release.promise; res.end(Buffer.from([0, 0])); });
  const first = f.speech.speak({ text: 'SYNTHETIC_CLEAN_FIRST', sessionId: 'clean', replyId: 'clean:1' });
  const input = { text: 'SYNTHETIC_QUEUED_SECOND', sessionId: 'clean', replyId: 'clean:1' };
  const second = f.speech.speak(input); second.catch(() => {});
  await entered.promise;
  await f.authority.protectSession('clean', { categories: ['memory'] });
  input.sessionId = 'other-clean'; input.text = 'MUTATED_CALLER_INPUT';
  release.resolve(); await first;
  await assert.rejects(second, denied);
  assert.equal(f.requests.length, 1);
});

test('privacy invalidation aborts real streaming HTTP and cancels queued chunks', async t => {
  const f = await fixture(t), entered = defer();
  f.setReceiver((_req, res) => { res.writeHead(200); res.write(Buffer.from([0, 0])); entered.resolve(); });
  const stops = [];
  const unsubscribe = f.privacy.onInvalidated(notice => { const stopped = f.speech.invalidate(notice); stops.push(stopped); });
  t.after(unsubscribe);
  const first = f.speech.speak({ text: 'SYNTHETIC_CLEAN_STREAM', sessionId: 'clean' });
  const second = f.speech.speak({ text: 'SYNTHETIC_QUEUED', sessionId: 'clean' });
  await entered.promise; f.privacy.invalidate(); await Promise.all(stops);
  assert.deepEqual(await first, { cancelled: true }); assert.deepEqual(await second, { cancelled: true });
  assert.equal(f.requests.length, 1);
  assert.ok(f.nativeCalls.some(call => call.operation === 'stop_speaking'));
  assert.ok(f.nativeCalls.some(call => call.operation === 'clear_playback'));
});

test('native queued system speech is stopped after its dispatch promise has completed', async t => {
  const f = await fixture(t);
  await f.speech.speak({ text: 'SYNTHETIC_CLEAN_SYSTEM_QUEUE', sessionId: 'clean', provider: 'system' });
  await f.speech.invalidate({ sessionId: 'other-clean' });
  assert.equal(f.nativeCalls.some(call => call.operation === 'stop_speaking'), false);
  await f.authority.protectSession('clean', { categories: ['memory'] });
  await f.speech.invalidate({ sessionId: 'clean' });
  assert.ok(f.nativeCalls.some(call => call.operation === 'stop_speaking'));
  await assert.rejects(f.speech.speak({ text: 'SYNTHETIC_PRIVATE_NEXT', sessionId: 'clean', provider: 'system' }), denied);
});

test('a state lookup crossing invalidation cannot release a stale clean answer', async () => {
  const entered = defer(), release = defer(), calls = [];
  const speech = createSpeechService({ store: { read: async () => ({ tts: { provider: 'system' } }) },
    native: async input => { calls.push(input); return {}; },
    outputState: async () => { entered.resolve(); await release.promise; return { active: true, state: 'known-clean' }; } });
  const pending = speech.speak({ text: 'SYNTHETIC_STALE', sessionId: 'clean' }); pending.catch(() => {});
  await entered.promise; await speech.invalidate({ sessionId: 'clean' }); release.resolve();
  await assert.rejects(pending, denied); assert.equal(calls.length, 0);
});

test('the same active reply cannot be moved to another session', async t => {
  const f = await fixture(t);
  await f.speech.speak({ text: 'SYNTHETIC_FIRST', sessionId: 'clean', replyId: 'source:turn' });
  await assert.rejects(f.speech.speak({ text: 'SYNTHETIC_REASSIGNED', sessionId: 'other-clean', replyId: 'source:turn' }),
    { code: 'TTS_REPLY_SESSION_MISMATCH' });
  assert.equal(f.requests.length, 1);
});

test('missing privacy service consults the persisted activation latch and never downgrades', async () => {
  let service, nativeActive = false, checks = 0;
  const outputState = createVoiceOutputState({ getPrivacy: () => service,
    nativeStatus: async () => { checks++; return { settings: { privacyEverActivated: nativeActive } }; } });
  assert.deepEqual(await outputState('clean'), { active: false, state: 'inactive' });
  nativeActive = true;
  assert.deepEqual(await outputState('clean'), { active: true, state: 'unknown' });
  service = { outputState: async () => ({ active: false, state: 'inactive' }) };
  assert.deepEqual(await outputState('clean'), { active: true, state: 'unknown' });
  assert.equal(checks, 2);
  service = { outputState: async () => ({ active: true, state: 'known-clean' }) };
  assert.deepEqual(await outputState('clean'), { active: true, state: 'known-clean' });
  const unavailable = createVoiceOutputState({ getPrivacy: () => undefined, nativeStatus: async () => ({}) });
  await assert.rejects(unavailable('clean'), /TTS_PRIVACY_STATE_UNAVAILABLE/);
});
