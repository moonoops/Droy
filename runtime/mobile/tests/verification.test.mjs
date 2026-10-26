import test from 'node:test';
import assert from 'node:assert/strict';
import { createVerificationWorkflow } from '../verification.mjs';

function fixture({ image = false, language = 'zh', answer = { answers: [{ id: 'verification', selected: ['已完成，继续'] }] }, ask, native } = {}) {
  const calls = [], questions = [], abort = new AbortController();
  const exec = { agent: { id: 'fictional-agent', options: { provider: 'fixture', model: 'fixture' } },
    callId: 'fictional-call', signal: abort.signal };
  const ctx = { settings: { describe: () => [{ ns: 'locale', value: { preference: language } }] },
    llm: { async resolveModelInfo() { return { inputModalities: image ? ['text', 'image'] : ['text'] }; } },
    userQuestions: { async ask(request) { questions.push(request); return ask ? ask(request) : answer; } } };
  const bridge = { async call(action, args, actual) {
    actual.signal.throwIfAborted();
    calls.push({ action, args, exec: actual });
    if (native) return native(action, args);
    if (action === 'observe') return { snapshotId: 'after-human', packageName: 'example.fixture' };
    return { state: args.operation === 'resume' ? 'awaiting_review' : 'manual_required' };
  } };
  return { run: args => createVerificationWorkflow(ctx, bridge)(args, exec), calls, questions, abort, exec };
}

test('assessment takes modality from runtime and does not forward forged fields', async () => {
  const f = fixture({ native: () => ({ state: 'automatic_allowed' }) });
  await f.run({ operation: 'assess', kind: 'slider', solvable: true, snapshotId: 'current',
    imageSupported: true, taskId: 'forged', resume: true });
  assert.deepEqual(f.calls[0].args, { operation: 'assess', kind: 'slider', solvable: true,
    snapshotId: 'current', imageSupported: false });
  assert.equal(f.calls[0].exec.agent, f.exec.agent);
  assert.deepEqual(f.questions, []);
});

test('English handoff accepts only its presented completion option', async () => {
  const f = fixture({ language: 'en', answer: { answers: [{ id: 'verification', selected: ['Completed, continue'] }] } });
  assert.equal((await f.run({ operation: 'handoff' })).state, 'awaiting_review');
  assert.match(f.questions[0].questions[0].question, /original app/);
  assert.deepEqual(f.questions[0].questions[0].options.map(option => option.label), ['Completed, continue', 'Not now']);
  const wrong = fixture({ language: 'en' });
  assert.equal((await wrong.run({ operation: 'handoff' })).userDeferred, true);
  assert.equal(wrong.calls.some(call => call.args.operation === 'resume'), false);
});

test('human completion obtains a fresh observation and enters review without asserting success', async () => {
  const f = fixture();
  const result = await f.run({ operation: 'assess', kind: 'otp', solvable: false, snapshotId: 'before' });
  assert.deepEqual(f.calls.map(x => `${x.action}:${x.args.operation ?? ''}`),
    ['verification:assess', 'verification:handoff', 'verification:return_to_app', 'observe:', 'verification:resume']);
  assert.equal(new Set(f.calls.map(x => x.exec.callId)).size, f.calls.length);
  assert.equal(f.questions[0].signal, f.exec.signal);
  assert.equal(result.state, 'awaiting_review');
  assert.equal(result.observation.snapshotId, 'after-human');
  assert.deepEqual(f.calls.at(-1).args, { operation: 'resume', snapshotId: 'after-human' });
  assert.ok(f.calls.every(x => x.args.passed !== true));
});

test('deferral and free text do not resume or echo possible secret values', async () => {
  for (const answer of [
    { answers: [{ id: 'verification', selected: ['暂不处理'] }] },
    { answers: [{ id: 'verification', selected: [], custom: 'fictional-code-123456' }] },
    { answers: [] },
  ]) {
    const f = fixture({ answer });
    const result = await f.run({ operation: 'handoff' });
    assert.equal(result.state, 'manual_required'); assert.equal(result.userDeferred, true);
    assert.equal(f.calls.length, 1);
    assert.equal(JSON.stringify(result).includes('fictional-code'), false);
  }
});

test('cancelled question keeps native manual state without observing or resuming', async () => {
  const f = fixture({ ask: request => new Promise((_resolve, reject) => {
    request.signal.addEventListener('abort', () => reject(request.signal.reason), { once: true });
  }) });
  const run = f.run({ operation: 'handoff' });
  await new Promise(resolve => setImmediate(resolve));
  f.abort.abort(new Error('fixture cancelled'));
  await assert.rejects(run, /fixture cancelled/);
  assert.equal(f.calls.length, 1);
});

test('model cannot directly restore or resume and malformed observations cannot unlock control', async () => {
  const f = fixture({ native: action => action === 'observe' ? {} : { state: 'manual_required' } });
  await assert.rejects(f.run({ operation: 'resume', snapshotId: 'forged' }), /INVALID_VERIFICATION/);
  await assert.rejects(f.run({ operation: 'return_to_app', packageName: 'forged.app' }), /INVALID_VERIFICATION/);
  assert.equal(f.calls.length, 0);
  await assert.rejects(f.run({ operation: 'handoff' }), /INVALID_OBSERVATION/);
  assert.equal(f.calls.some(x => x.args.operation === 'resume'), false);
});

test('failed restoration preserves the manual gate without observing or completing', async () => {
  const f = fixture({ native: (_action, args) => {
    if (args.operation === 'return_to_app') throw new Error('original app cannot be opened');
    return { state: 'manual_required' };
  } });
  await assert.rejects(f.run({ operation: 'handoff' }), /original app cannot be opened/);
  assert.deepEqual(f.calls.map(x => x.args.operation), ['handoff', 'return_to_app']);
});

test('failed automatic attempt hands off while passed result returns only native verdict', async () => {
  const failed = fixture({ answer: { answers: [{ id: 'verification', selected: ['暂不处理'] }] } });
  await failed.run({ operation: 'complete', passed: false, snapshotId: 'after-attempt' });
  assert.deepEqual(failed.calls.map(x => x.args.operation), ['complete', 'handoff']);
  const passed = fixture({ native: () => ({ state: 'verified', goalVerified: false }) });
  assert.deepEqual(await passed.run({ operation: 'complete', passed: true, snapshotId: 'after-attempt' }),
    { state: 'verified', goalVerified: false });
  assert.equal(passed.questions.length, 0);
});
