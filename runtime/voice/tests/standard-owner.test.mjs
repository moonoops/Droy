import test from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { once } from 'node:events';
import { randomUUID } from 'node:crypto';
import { createVoiceService, createVoiceHandler } from '../http.mjs';

const deferred = () => { let resolve; const promise = new Promise(yes => { resolve = yes; }); return { promise, resolve }; };
const rejected = code => Object.assign(new Error(`REJECTED: ${code}\nfictional-native-detail`), { code: 'REJECTED' });
async function fixture(t, options = {}) {
  const calls = [], media = { state: 'idle', voiceOperationId: null, sessionId: '', captureId: '', speaking: false };
  let captures = 0, startEvent = 0, eventOwner = null, wakeOwner = null;
  const retired = new Set();
  const config = { entries: { standard: true }, tts: { provider: options.remote ? 'openai' : 'system', url: 'https://synthetic.invalid/v1', voiceId: '', rate: 1 } };
  const active = () => media.state === 'recording';
  function control(input, output = false) {
    const id = input.voiceOperationId ?? null;
    if (output && !active() && id === null) return;
    if (id !== media.voiceOperationId || output && !active()) throw rejected(id === null ? 'VOICE_OPERATION_REQUIRED' : 'VOICE_OWNER_MISMATCH');
  }
  const native = async input => {
    calls.push(structuredClone(input));
    if (options.intercept) { const result = await options.intercept(input, media); if (result !== undefined) return result; }
    if (input.operation === 'status') return { ...media };
    if (input.operation === 'start') {
      if (retired.has(input.voiceOperationId) && !active()) {
        if (media.voiceOperationId === input.voiceOperationId) return { ...media };
        throw rejected('VOICE_OWNER_MISMATCH');
      }
      if (active()) {
        control(input);
        if (media.sessionId !== input.sessionId) throw rejected('VOICE_OWNER_MISMATCH');
      } else {
        Object.assign(media, { state: 'recording', voiceOperationId: input.voiceOperationId ?? null, sessionId: input.sessionId,
          captureId: `synthetic-capture-${++captures}`, speaking: false });
      }
      return { ...media };
    }
    if (input.operation === 'wake.consume') {
      startEvent = input.eventId; eventOwner = input.voiceOperationId ?? null;
      return { consumed: true, startingEventId: startEvent, startingVoiceOperationId: eventOwner, voiceOperationId: wakeOwner };
    }
    if (input.operation === 'wake.claim') { wakeOwner = input.voiceOperationId ?? null; return { voiceClaimed: true, voiceOperationId: wakeOwner }; }
    if (input.operation === 'wake.release') {
      const id = input.voiceOperationId ?? null;
      if ((wakeOwner !== null && id !== wakeOwner) || (eventOwner !== null && id !== eventOwner)) throw rejected('VOICE_OWNER_MISMATCH');
      startEvent = 0; eventOwner = wakeOwner = null;
      return { voiceClaimed: false, voiceOperationId: null, startingEventId: 0, startingVoiceOperationId: null };
    }
    if (input.operation === 'wake.status') return { voiceClaimed: wakeOwner !== null, voiceOperationId: wakeOwner, startingEventId: startEvent, startingVoiceOperationId: eventOwner };
    if (['speak', 'play', 'stop_speaking', 'clear_playback'].includes(input.operation)) {
      control(input, true);
      if (input.operation === 'speak' || input.operation === 'play') media.speaking = true;
      if (input.operation === 'stop_speaking') media.speaking = false;
      return { queued: true };
    }
    if (['poll', 'mute', 'stop'].includes(input.operation)) {
      if (input.operation === 'stop' && input.voiceOperationId !== undefined) retired.add(input.voiceOperationId);
      control(input);
      if (input.operation === 'stop') Object.assign(media, { state: 'ended', speaking: false });
      return { ...media };
    }
    return {};
  };
  const service = createVoiceService({ store: { read: options.read ?? (async () => structuredClone(config)), publicConfig: async () => ({}), key: async () => '' },
    native, realtime: { active: false, stop: async () => ({}) }, getLocale: () => options.locale ?? 'en',
    fetchImpl: options.fetchImpl ?? (() => assert.fail('Owner protocol tests must not use a network model')) });
  const server = createServer(createVoiceHandler({ service, authorize: (req, res) => {
    if (req.headers.authorization === 'Bearer synthetic-owner-fixture') return true;
    res.writeHead(401); res.end(); return false;
  } }));
  server.listen(0, '127.0.0.1'); await once(server, 'listening');
  t.after(() => { server.closeAllConnections(); server.close(); });
  return { calls, media, config, service, captures: () => captures, async post(operation, id, rest = {}) {
    const response = await fetch(`http://127.0.0.1:${server.address().port}/droy/voice`, { method: 'POST',
      headers: { Authorization: 'Bearer synthetic-owner-fixture', 'Content-Type': 'application/json' },
      body: JSON.stringify({ operation, ...(id === undefined ? {} : { voiceOperationId: id }), ...rest }) });
    const value = await response.json(); assert.ok(!JSON.stringify(value).includes('fictional-native-detail'));
    return { status: response.status, value, result: value.result };
  } };
}
test('real HTTP start receipts distinguish two operations on the same session and every control keeps the UUID', async t => {
  const f = await fixture(t), a = randomUUID(), b = randomUUID();
  const responses = await Promise.all([f.post('start', a, { sessionId: 'same-r' }), f.post('start', b, { sessionId: 'same-r' })]);
  assert.equal(responses.filter(row => row.status === 200).length, 1); assert.equal(f.captures(), 1);
  const winner = f.media.voiceOperationId, other = winner === a ? b : a;
  const repeated = await f.post('start', winner, { sessionId: 'same-r' });
  assert.equal(repeated.result.voiceOperationId, winner); assert.equal(f.captures(), 1);
  for (const [operation, rest] of [['poll', { cursor: 0 }], ['mute', { muted: true }], ['speak', { text: 'Synthetic reply.' }],
    ['play', { audio: 'AAA=', sampleRate: 24000 }], ['clear_playback', { responseId: 'tts:0' }], ['stop_speaking', {}], ['stop', {}]]) {
    const refused = await f.post(operation, other, rest);
    assert.equal(refused.status, 409, operation); assert.equal(refused.value.error.code, 'VOICE_OWNER_MISMATCH', operation);
    assert.equal((await f.post(operation, undefined, rest)).status, 409, `legacy ${operation}`);
  }
  assert.equal(f.media.state, 'recording');
  assert.equal((await f.post('poll', winner)).result.voiceOperationId, winner);
  assert.equal((await f.post('mute', winner, { muted: false })).status, 200);
  const stopped = await f.post('stop', winner);
  assert.equal(stopped.result.state, 'ended'); assert.equal(stopped.result.voiceOperationId, winner);
  assert.deepEqual(f.calls.filter(row => row.operation === 'stop').map(row => row.voiceOperationId), [other, undefined, winner]);
});
test('owner scopes cancel late TTS without allowing an old stop to affect the next operation', async t => {
  const gate = deferred(), entered = deferred();
  const f = await fixture(t, { remote: true, fetchImpl: async () => { entered.resolve(); await gate.promise; return new Response(Buffer.alloc(480)); } });
  const a = randomUUID(), b = randomUUID(); await f.post('start', a, { sessionId: 'same-r' });
  const oldSpeech = f.post('speak', a, { text: 'Synthetic old request.', sessionId: 'same-r' }); await entered.promise;
  await f.post('stop', a); await f.post('start', b, { sessionId: 'same-r' });
  assert.equal((await f.post('speak', b, { text: 'Synthetic new reply.', provider: 'system', sessionId: 'same-r' })).status, 200);
  gate.resolve(); assert.deepEqual((await oldSpeech).result, { cancelled: true });
  const before = f.calls.filter(row => row.operation === 'stop_speaking' || row.operation === 'clear_playback').length;
  assert.equal((await f.post('stop_speaking', a)).value.error.code, 'VOICE_OWNER_MISMATCH');
  assert.equal((await f.post('stop', a)).value.error.code, 'VOICE_OWNER_MISMATCH');
  assert.equal(f.calls.filter(row => row.operation === 'stop_speaking' || row.operation === 'clear_playback').length, before);
  assert.equal(f.media.voiceOperationId, b); assert.equal(f.media.state, 'recording'); assert.equal(f.media.speaking, true);
  assert.equal(f.calls.some(row => row.operation === 'play' && row.voiceOperationId === a), false);
});
test('retired and never-started stopped operations cannot capture after the next operation ends', async t => {
  const f = await fixture(t), a = randomUUID(), b = randomUUID(), neverStarted = randomUUID();
  await f.post('start', a, { sessionId: 'same-r' }); await f.post('stop', a);
  assert.equal((await f.post('speak', a, { text: 'Late retired speech.' })).status, 409);
  assert.equal(f.calls.some(row => row.operation === 'speak'), false);
  await f.post('start', b, { sessionId: 'same-r' });
  assert.equal((await f.post('stop', neverStarted)).status, 409);
  assert.equal(f.media.state, 'recording'); assert.equal(f.media.voiceOperationId, b);
  await f.post('stop', b);
  assert.equal((await f.post('start', a, { sessionId: 'same-r' })).status, 409);
  assert.equal((await f.post('start', neverStarted, { sessionId: 'same-r' })).status, 409);
  assert.equal(f.captures(), 2); assert.equal(f.media.state, 'ended'); assert.equal(f.media.voiceOperationId, b);
});
test('a delayed owner check cannot authorize stale local output after a new operation has started', async t => {
  const entered = deferred(), gate = deferred(); let waitStatus = false;
  const f = await fixture(t, { intercept: async (input, media) => {
    if (waitStatus && input.operation === 'status') { waitStatus = false; const snapshot = { ...media }; entered.resolve(); await gate.promise; return snapshot; }
  } });
  const a = randomUUID(), b = randomUUID(); await f.post('start', a, { sessionId: 'same-r' });
  waitStatus = true; const oldSpeech = f.post('speak', a, { text: 'Synthetic delayed speech.' }); await entered.promise;
  await f.post('stop', a); await f.post('start', b, { sessionId: 'same-r' }); gate.resolve();
  assert.equal((await oldSpeech).value.error.code, 'VOICE_OWNER_MISMATCH');
  assert.equal(f.media.voiceOperationId, b); assert.equal(f.media.speaking, false);
  assert.ok(f.calls.some(row => row.operation === 'speak' && row.voiceOperationId === a));
});
test('a delayed successful start receipt cannot cancel the next owners speech queue', async t => {
  const a = randomUUID(), b = randomUUID(), started = deferred(), oldReceipt = deferred(), speaking = deferred(), audio = deferred();
  let speechSignal;
  const f = await fixture(t, { remote: true, intercept: async (input, media) => {
    if (input.operation === 'start' && input.voiceOperationId === a) {
      Object.assign(media, { state: 'recording', voiceOperationId: a, sessionId: input.sessionId, captureId: 'synthetic-old-capture' });
      const receipt = { ...media }; started.resolve(); await oldReceipt.promise; return receipt;
    }
  }, fetchImpl: async (_url, options) => { speechSignal = options.signal; speaking.resolve(); await audio.promise; return new Response(Buffer.alloc(480)); } });
  const starting = f.post('start', a, { sessionId: 'r' }); await started.promise;
  await f.post('stop', a); await f.post('start', b, { sessionId: 'r' });
  const currentSpeech = f.post('speak', b, { text: 'Synthetic current reply.' }); await speaking.promise;
  oldReceipt.resolve(); assert.equal((await starting).status, 200); assert.equal(speechSignal.aborted, false);
  audio.resolve(); assert.equal((await currentSpeech).status, 200);
  assert.ok(f.calls.some(row => row.operation === 'play' && row.voiceOperationId === b));
  assert.equal(f.media.voiceOperationId, b); assert.equal(f.media.speaking, true);
});
test('one operation stopping during another operation configuration read cannot cancel the latter start', async t => {
  const gate = deferred(), entered = deferred();
  const f = await fixture(t, { read: async () => { entered.resolve(); return gate.promise; } });
  const a = randomUUID(), b = randomUUID(); const start = f.post('start', a, { sessionId: 'r' }); await entered.promise;
  assert.equal((await f.post('stop', b)).status, 409);
  gate.resolve({ entries: { standard: true } }); const receipt = await start;
  assert.equal(receipt.result.voiceOperationId, a); assert.equal(f.captures(), 1);
});
test('stopping the same pending operation cancels its late start without recording', async t => {
  const gate = deferred(), entered = deferred();
  const f = await fixture(t, { read: async () => { entered.resolve(); return gate.promise; } }), a = randomUUID();
  const starting = f.post('start', a, { sessionId: 'r' }); await entered.promise; await f.post('stop', a);
  gate.resolve({ entries: { standard: true } }); const result = (await starting).result;
  assert.equal(result.reason, 'cancelled_start'); assert.equal(result.voiceOperationId, null); assert.equal(f.captures(), 0);
});
test('wake consume, claim and release keep operation and event identities through real HTTP', async t => {
  const f = await fixture(t), a = randomUUID(), b = randomUUID();
  const consumed = (await f.post('wake.consume', a, { eventId: 17 })).result;
  assert.equal(consumed.startingEventId, 17); assert.equal(consumed.startingVoiceOperationId, a); assert.equal(consumed.voiceOperationId, null);
  assert.equal((await f.post('wake.claim', a)).result.voiceOperationId, a);
  assert.equal((await f.post('wake.release', b)).value.error.code, 'VOICE_OWNER_MISMATCH');
  assert.equal((await f.post('wake.status')).result.startingVoiceOperationId, a);
  const released = (await f.post('wake.release', a)).result;
  assert.equal(released.voiceOperationId, null); assert.equal(released.startingEventId, 0);
  for (const call of f.calls.filter(row => ['wake.consume', 'wake.claim', 'wake.release'].includes(row.operation)))
    assert.ok(call.voiceOperationId === a || call.voiceOperationId === b);
});
test('invalid operation IDs are rejected before native access and owner failures have paired safe copy', async t => {
  for (const locale of ['en', 'zh']) {
    const f = await fixture(t, { locale });
    for (const value of ['', null, 17, '../../capture']) assert.equal((await f.post('start', value, { sessionId: 'r' })).value.error.code, 'VOICE_OPERATION_INVALID');
    assert.equal(f.calls.length, 0);
    const a = randomUUID(); await f.post('start', a, { sessionId: 'r' });
    const failure = await f.post('poll');
    assert.equal(failure.value.error.code, 'VOICE_OPERATION_REQUIRED');
    assert.match(failure.value.error.message, locale === 'zh' ? /语音/u : /voice/u);
  }
});
