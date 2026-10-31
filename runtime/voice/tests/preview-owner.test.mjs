import test from 'node:test';
import assert from 'node:assert/strict';
import { createSpeechService } from '../http.mjs';

const deferred = () => { let resolve, reject; const promise = new Promise((yes, no) => { resolve = yes; reject = no; }); return { promise, resolve, reject }; };
function fixture() {
  const calls = [], state = { speaking: false };
  let intercept;
  const service = createSpeechService({ getLocale: () => 'en',
    store: { read: async () => ({ tts: { provider: 'system', voiceId: '', rate: 1 } }), key: async () => '' },
    fetchImpl: () => assert.fail('Owned local previews never call an external speech provider'),
    native: async input => {
      calls.push(input);
      if (intercept) { const result = await intercept(input); if (result !== undefined) return result; }
      if (input.operation === 'status') return { ...state };
      if (input.operation === 'speak') { state.speaking = true; return { queued: true }; }
      if (input.operation === 'stop_speaking') state.speaking = false;
      return {};
    } });
  return { service, calls, state, intercept: value => { intercept = value; }, stops: () => calls.filter(row => row.operation === 'stop_speaking').length };
}
const draft = { provider: 'system', voiceId: '', rate: 1 };

test('a preview token cannot stop another owner or a later conversation', async () => {
  const f = fixture(); await f.service.previewOwned('preview-a', undefined, draft);
  const count = f.stops(); assert.deepEqual(await f.service.stopOwned('preview-b'), { stopped: false });
  await f.service.speak({ text: 'Synthetic conversation text.', sessionId: 'conversation' });
  assert.deepEqual(await f.service.stopOwned('preview-a'), { stopped: false });
  assert.equal(f.stops(), count); assert.equal(f.state.speaking, true);
});

test('completion retires the old preview token before a later preview begins', async () => {
  const f = fixture(); await f.service.previewOwned('preview-a', undefined, draft);
  f.state.speaking = false; assert.deepEqual(f.service.previewOwnership(f.state), { active: false });
  await f.service.previewOwned('preview-b', undefined, draft);
  const count = f.stops(); assert.deepEqual(await f.service.stopOwned('preview-a'), { stopped: false });
  assert.equal(f.stops(), count); assert.equal(f.service.previewOwnership(f.state).owner, 'preview-b');
});

test('failed preview does not keep authority over the next preview', async () => {
  const f = fixture(); f.intercept(input => { if (input.operation === 'speak') throw new Error('synthetic failure'); });
  await assert.rejects(f.service.previewOwned('preview-a', undefined, draft), /synthetic failure/);
  f.intercept(undefined); await f.service.previewOwned('preview-b', undefined, draft);
  const count = f.stops(); assert.deepEqual(await f.service.stopOwned('preview-a'), { stopped: false }); assert.equal(f.stops(), count);
});

test('a conversation queued while preview preparation waits takes ownership before any preview playback', async () => {
  const f = fixture(), gate = deferred(); let first = true;
  f.intercept(input => { if (input.operation === 'stop_speaking' && first) { first = false; return gate.promise; } });
  const preview = f.service.previewOwned('preview-a', undefined, draft);
  const conversation = f.service.speak({ text: 'Synthetic next reply.', sessionId: 'conversation' });
  gate.resolve({}); assert.deepEqual(await preview, { cancelled: true }); await conversation;
  const spoken = f.calls.filter(row => row.operation === 'speak'); assert.equal(spoken.length, 1); assert.equal(spoken[0].text, 'Synthetic next reply.');
  const count = f.stops(); assert.deepEqual(await f.service.stopOwned('preview-a'), { stopped: false }); assert.equal(f.stops(), count);
});

test('stop receipt waits for native acknowledgement and a failed stop remains retryable', async () => {
  const f = fixture(); await f.service.previewOwned('preview-a', undefined, draft);
  const gate = deferred(); f.intercept(input => input.operation === 'stop_speaking' ? gate.promise : undefined);
  let settled = false; const stopping = f.service.stopOwned('preview-a'); stopping.finally(() => { settled = true; }).catch(() => {});
  await new Promise(resolve => setImmediate(resolve)); assert.equal(settled, false);
  gate.reject(new Error('synthetic unconfirmed stop')); await assert.rejects(stopping, /unconfirmed stop/);
  assert.equal(f.service.previewOwnership(f.state).owner, 'preview-a');
  f.intercept(undefined); assert.deepEqual(await f.service.stopOwned('preview-a'), { stopped: true }); assert.equal(f.state.speaking, false);
});

test('an old failed stop cannot regain ownership over a newly queued conversation', async () => {
  const f = fixture(); await f.service.previewOwned('preview-a', undefined, draft);
  const gate = deferred(); f.intercept(input => input.operation === 'stop_speaking' ? gate.promise : undefined);
  const stopping = f.service.stopOwned('preview-a'); await new Promise(resolve => setImmediate(resolve));
  const conversation = f.service.speak({ text: 'Synthetic later reply.', sessionId: 'conversation' });
  gate.reject(new Error('synthetic stop failure')); await assert.rejects(stopping, /stop failure/); await conversation;
  const count = f.stops(); assert.deepEqual(await f.service.stopOwned('preview-a'), { stopped: false }); assert.equal(f.stops(), count);
});

test('unknown playback state cannot be reported as a successful stop', async () => {
  const f = fixture(); await f.service.previewOwned('preview-a', undefined, draft);
  f.intercept(input => input.operation === 'status' ? {} : undefined);
  const count = f.stops(); await assert.rejects(f.service.stopOwned('preview-a'), error => error.code === 'VOICE_STATUS_UNAVAILABLE');
  assert.equal(f.stops(), count);
});
