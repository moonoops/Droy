import test from 'node:test';
import assert from 'node:assert/strict';
import { createExecutionProgress } from '../execution-progress.mjs';

const settle = () => new Promise(resolve => setImmediate(resolve));

test('durable controls retain their revision over SDK idle/cancel, with child question identity separate from control owner', async () => {
  const sent = [], progress = createExecutionProgress(async row => sent.push(row), () => assert.fail());
  const child = { id: 'child-task' };
  progress.control(child.id, { taskId: 'parent-task', phase: 'pausing', active: true, summary: 'Pausing.', controlRevision: 1 });
  progress.sessionEvent(child, { type: 'turn/end', data: { reason: { kind: 'aborted' } } });
  progress.status(child, 'idle'); await settle();
  assert.equal(sent.at(-1).phase, 'pausing'); assert.equal(sent.at(-1).active, true);
  assert.equal(sent.at(-1).taskId, child.id); assert.equal(sent.at(-1).controlTaskId, 'parent-task');
  progress.control(child.id, { taskId: 'parent-task', phase: 'paused', active: true, summary: 'Paused.', controlRevision: 2 });
  progress.sessionEvent(child, { type: 'step/start', data: {} }); await settle();
  assert.equal(sent.at(-1).phase, 'paused'); assert.equal(sent.at(-1).controlRevision, 2);
  progress.control(child.id, { taskId: 'parent-task', phase: 'pausing', active: true, summary: 'Old.', controlRevision: 1 }); await settle();
  assert.equal(sent.at(-1).phase, 'paused');
  progress.control(child.id, { taskId: 'parent-task', phase: 'resuming', active: true, summary: 'Checking.', controlRevision: 3 });
  progress.waiting(child.id, { kind: 'approval', requestId: 'resume-approval', message: 'Confirm read.', options: [], inline: false }); await settle();
  assert.equal(sent.at(-1).phase, 'resuming');
  assert.equal(sent.at(-1).pending.kind, 'approval'); assert.equal(sent.at(-1).pending.requestId, 'resume-approval');
  progress.control(child.id, { taskId: 'parent-task', phase: 'running', active: true, controlRevision: 4 });
  progress.waiting(child.id, null); await settle();
  assert.equal(sent.at(-1).phase, 'thinking'); assert.equal(sent.at(-1).controlRevision, 4);
  assert.equal(sent.at(-1).controlTaskId, 'parent-task');
  progress.control(child.id, { taskId: 'parent-task', phase: 'stopped', active: false, summary: 'Stopped.', controlRevision: 5 });
  progress.sessionEvent(child, { type: 'tool/result', data: {} }); await settle();
  assert.equal(sent.at(-1).phase, 'stopped'); assert.equal(sent.at(-1).active, false);
  progress.dispose();
});

test('child history cannot discard a held parent revision and a completed tombstone remains inactive until a new run', async () => {
  const sent = [], progress = createExecutionProgress(async row => sent.push(row), () => assert.fail());
  progress.control('child', { taskId: 'child', phase: 'running', active: true, controlRevision: 8 }); await settle();
  progress.control('child', { taskId: 'parent', phase: 'paused', active: true, summary: 'Paused.', controlRevision: 1 }); await settle();
  assert.equal(sent.at(-1).phase, 'paused'); assert.equal(sent.at(-1).controlRevision, 1); assert.equal(sent.at(-1).controlTaskId, 'parent');
  progress.control('child', { taskId: 'parent', phase: 'completed', active: false, summary: 'Ended.', controlRevision: 2 });
  progress.status({ id: 'child' }, 'idle'); await settle();
  assert.equal(sent.at(-1).active, false); assert.equal(sent.at(-1).phase, 'completed');
  progress.control('child', { taskId: 'parent', phase: 'running', active: true, controlRevision: 3 }); await settle();
  assert.equal(sent.at(-1).phase, 'thinking'); assert.equal(sent.at(-1).controlRevision, 3);
  progress.dispose();
});

test('companion activity reflects actual task events without leaking tool arguments', async () => {
  const sent = [], session = { id: 'companion-stage' };
  const progress = createExecutionProgress(async row => sent.push(row), () => assert.fail());
  progress.sessionEvent(session, { type: 'turn/start', data: {} }); await settle();
  assert.equal(sent.at(-1).activityKind, 'started');
  progress.sessionEvent(session, { type: 'step/start', data: {} }); await settle();
  assert.equal(sent.at(-1).activityKind, 'started');
  for (const name of ['android_observe', 'android_screenshot']) {
    progress.sessionEvent(session, { type: 'tool/call', data: { name, arguments: 'private screen' } }); await settle();
    assert.equal(sent.at(-1).activityKind, 'observing');
  }
  for (const name of ['android_tap', 'android_swipe', 'android_input_text', 'android_sequence']) {
    progress.sessionEvent(session, { type: 'tool/call', data: { name, arguments: 'private action' } }); await settle();
    assert.equal(sent.at(-1).activityKind, 'controlling');
  }
  progress.publicSummary(session.id, '等待页面加载', 'waiting'); await settle();
  assert.equal(sent.at(-1).activityKind, undefined);
  progress.waiting(session.id, { requestId: 'q', message: '请选择', options: [], inline: false }); await settle();
  assert.equal(sent.at(-1).activityKind, undefined);
  progress.sessionEvent(session, { type: 'turn/end', data: { reason: { kind: 'completed' } } }); await settle();
  assert.equal(sent.at(-1).activityKind, undefined);
  assert.equal(JSON.stringify(sent).includes('private'), false);
  progress.dispose();
});

test('a ten-second thought is not an error and a real retry cancels that announcement', async () => {
  const sent = [], callbacks = [];
  const timers = { setTimeout(fn, ms) { assert.equal(ms, 10000); callbacks.push(fn); return callbacks.length; }, clearTimeout() {} };
  let locale = 'en';
  const progress = createExecutionProgress(async row => sent.push(row), () => assert.fail(), timers, () => locale);
  const session = { id: 'companion-thinking' };
  progress.sessionEvent(session, { type: 'step/start', data: {} }); await settle();
  callbacks.at(-1)(); await settle();
  assert.equal(sent.at(-1).activityKind, 'thinking');
  assert.equal(sent.at(-1).summaryKind, 'slow');
  assert.equal(sent.at(-1).summary, 'Thinking it through. You can stop the task at any time.');
  progress.sessionEvent(session, { type: 'step/start', data: {} });
  progress.sessionEvent(session, { type: 'llm/retry', data: {} }); await settle();
  const retry = sent.at(-1);
  callbacks.at(-1)(); await settle();
  assert.equal(sent.at(-1), retry);
  assert.equal(retry.activityKind, undefined);
  assert.equal(retry.summaryKind, 'error');
  assert.match(retry.summary, /request failed/);
  locale = 'zh';
  progress.sessionEvent(session, { type: 'tool/call', data: { name: 'android_observe' } });
  progress.sessionEvent(session, { type: 'step/start', data: {} }); await settle();
  callbacks.at(-1)(); await settle();
  assert.equal(sent.at(-1).summary, '正在思考中，可随时停止任务');
  progress.sessionEvent(session, { type: 'tool/call', data: { name: 'droy_pet_progress' } });
  locale = 'en';
  progress.publicSummary(session.id, '用户自己的描述', 'executing'); await settle();
  assert.equal(sent.at(-1).summary, '用户自己的描述');
  progress.dispose();
});

test('each real wait completion clears its announced waiting copy before another model response', async () => {
  const sent = [], session = { id: 'fixture-timed-wait' };
  const progress = createExecutionProgress(async row => sent.push(row), () => assert.fail('report failed'));
  for (const name of ['bash', 'job_output', 'android_wait_for', 'droy_browser_wait']) {
    progress.publicSummary(session.id, '等待页面加载完成', 'waiting'); await settle();
    progress.sessionEvent(session, { type: 'tool/call', data: { name, callId: name } }); await settle();
    assert.equal(sent.at(-1).phase, 'waiting');
    progress.sessionEvent(session, { type: 'tool/result', data: { message: { toolCallId: name } } }); await settle();
    assert.equal(sent.at(-1).phase, 'verifying');
    assert.notEqual(sent.at(-1).summary, '等待页面加载完成');
  }
  progress.dispose();
});

test('wait failures, explicit progress, cancellation and a new turn do not retain stale waiting copy', async () => {
  const sent = [], session = { id: 'fixture-wait-cleanup' };
  const progress = createExecutionProgress(async row => sent.push(row), () => {});
  const announce = async () => { progress.publicSummary(session.id, '等待页面加载完成', 'waiting'); await settle(); };
  await announce();
  progress.sessionEvent(session, { type: 'tool/result', data: { message: { isError: true, content: 'private error' } } }); await settle();
  assert.equal(sent.at(-1).phase, 'thinking');
  assert.notEqual(sent.at(-1).summary, '等待页面加载完成');
  await announce();
  progress.publicSummary(session.id, '已到时间，正在领取优惠券', 'executing'); await settle();
  assert.equal(sent.at(-1).phase, 'executing');
  await announce();
  progress.sessionEvent(session, { type: 'turn/end', data: { reason: { kind: 'aborted' } } }); await settle();
  assert.equal(sent.at(-1).phase, 'interrupted');
  assert.equal(sent.at(-1).active, false);
  assert.notEqual(sent.at(-1).summary, '等待页面加载完成');
  await announce();
  progress.sessionEvent(session, { type: 'turn/start', data: {} });
  progress.sessionEvent(session, { type: 'step/start', data: {} }); await settle();
  assert.equal(sent.at(-1).phase, 'thinking');
  assert.equal(sent.at(-1).summaryKind, 'working');
});

test('waiting is distinct from asking the user and is never guessed from shell text', async () => {
  const sent = [], session = { id: 'fixture-wait-contract' };
  const progress = createExecutionProgress(async row => sent.push(row), () => {});
  progress.sessionEvent(session, { type: 'tool/call', data: { name: 'bash', arguments: 'sleep 600' } }); await settle();
  assert.equal(sent.at(-1).phase, 'executing');
  progress.publicSummary(session.id, '等待10:00开始', 'waiting'); await settle();
  progress.waiting(session.id, { requestId: 'question', message: '请选择目标', inline: false, options: [] }); await settle();
  assert.equal(sent.at(-1).phase, 'waiting_input');
  progress.waiting(session.id, null); await settle();
  assert.equal(sent.at(-1).phase, 'waiting');
  progress.removed(session); await settle();
  assert.equal(sent.at(-1).phase, 'interrupted');
  assert.equal(sent.at(-1).pending, null);
});

test('existing session events report execution stages and retain cancellation as interrupted', async () => {
  const sent = [];
  const progress = createExecutionProgress(async row => { sent.push(row); }, () => assert.fail('unexpected reporting failure'));
  const session = { id: 'fixture-session' };
  for (const [type, data] of [
    ['step/start', {}], ['tool/call', { arguments: 'private input must never be copied' }],
    ['tool/result', { message: { content: 'private screen must never be copied' } }],
    ['step/start', {}], ['turn/end', { reason: { kind: 'aborted', reason: { kind: 'user' } } }],
  ]) { progress.sessionEvent(session, { type, data }); await settle(); }
  progress.status(session, 'idle');
  progress.removed(session);
  await settle();
  assert.deepEqual(sent.map(row => row.phase), ['thinking', 'executing', 'verifying', 'thinking', 'interrupted']);
  assert.deepEqual(sent.at(-1), { taskId: session.id, phase: 'interrupted', active: false, summary: '本轮执行已停止，点我查看任务', pending: null, controlTaskId: session.id, controlRevision: 0 });
  assert.equal(JSON.stringify(sent).includes('private'), false);
});

test('a slow reporting transport keeps only the latest pending stage and never blocks execution', async () => {
  let release;
  const sent = [];
  const progress = createExecutionProgress(row => {
    sent.push(row);
    return sent.length === 1 ? new Promise(resolve => { release = resolve; }) : Promise.resolve();
  }, () => assert.fail('unexpected reporting failure'));
  const session = { id: 'fixture-session' };
  progress.status(session, 'running');
  progress.sessionEvent(session, { type: 'tool/call', data: {} });
  progress.sessionEvent(session, { type: 'tool/result', data: {} });
  progress.sessionEvent(session, { type: 'turn/end', data: { reason: { kind: 'completed' } } });
  assert.equal(sent.length, 1);
  release(); await settle();
  assert.deepEqual(sent.map(row => row.phase), ['thinking', 'completed']);
});

test('duplicate events are coalesced and a progress failure remains separate from task results', async () => {
  let errors = 0;
  const sent = [];
  const progress = createExecutionProgress(async row => { sent.push(row); if (sent.length === 1) throw Error('offline'); }, () => { errors++; });
  const session = { id: 'fixture-session' };
  progress.status(session, 'running');
  progress.sessionEvent(session, { type: 'step/start', data: {} });
  await settle();
  progress.removed(session); await settle();
  assert.equal(errors, 1);
  assert.deepEqual(sent.map(row => row.phase), ['thinking', 'interrupted']);
  progress.dispose();
  progress.status(session, 'running'); await settle();
  assert.equal(sent.length, 2);
});

test('idle without a committed turn end does not invent task completion', async () => {
  const sent = [];
  const progress = createExecutionProgress(async row => sent.push(row), () => {});
  const session = { id: 'fixture-session' };
  progress.status(session, 'running'); await settle();
  progress.status(session, 'idle'); await settle();
  assert.equal(sent.at(-1).phase, 'interrupted');
});

test('intentional public progress survives mechanical steps; raw thinking is never projected', async () => {
  const sent = [];
  const progress = createExecutionProgress(async row => sent.push(row), () => {});
  const session = { id: 'fixture-public-progress' };
  progress.sessionEvent(session, { type: 'assistant/attempt', data: { stream: [{ type: 'thinking', text: 'private reasoning' }] } });
  assert.equal(sent.length, 0);
  progress.publicSummary(session.id, '正在筛选符合预算的商品', 'executing'); await settle();
  progress.sessionEvent(session, { type: 'tool/call', data: { name: 'android_observe', arguments: 'private screen text' } }); await settle();
  progress.sessionEvent(session, { type: 'tool/result', data: { message: { content: 'private results' } } }); await settle();
  assert.ok(sent.every(row => row.summary === '正在筛选符合预算的商品'));
  assert.equal(JSON.stringify(sent).includes('private'), false);
  assert.throws(() => progress.publicSummary(session.id, 'password=fictional-secret', 'executing'));
  assert.throws(() => progress.publicSummary(session.id, '长'.repeat(121), 'executing'));
  assert.throws(() => progress.publicSummary(session.id, '假审批', 'waiting_input'));
});

test('real pending requests stay visible over tools and clear with the originating request', async () => {
  const sent = [];
  const progress = createExecutionProgress(async row => sent.push(row), () => {});
  const session = { id: 'fixture-pending' };
  const pending = { requestId: 'question-1', title: '需要你的选择', message: '使用哪一个应用？', inline: true,
    options: [{ id: '0', label: '应用甲' }, { id: '1', label: '应用乙' }] };
  progress.waiting(session.id, pending); await settle();
  progress.sessionEvent(session, { type: 'tool/call', data: { name: 'fixture_parallel_tool' } }); await settle();
  assert.equal(sent.length, 1);
  assert.deepEqual(sent[0].pending, pending);
  assert.equal(sent[0].phase, 'waiting_input');
  progress.waiting(session.id, null); await settle();
  assert.equal(sent.at(-1).pending, null);
  progress.sessionEvent(session, { type: 'turn/end', data: { reason: { kind: 'completed' } } }); await settle();
  progress.waiting(session.id, null); await settle();
  assert.equal(sent.at(-1).active, false);
});

test('a tool failure does not claim the whole task is blocked or expose the error body', async () => {
  const sent = [];
  const progress = createExecutionProgress(async row => sent.push(row), () => {});
  progress.publicSummary('fixture-failure', '正在筛选符合预算的商品', 'executing'); await settle();
  progress.sessionEvent({id:'fixture-failure'}, {type:'tool/result', data:{message:{isError:true,content:'private device failure details'}}});
  await settle();
  assert.equal(sent.at(-1).phase, 'thinking');
  assert.equal(sent.at(-1).summary, '正在努力执行');
  assert.equal(sent.at(-1).summaryKind, 'working');
  assert.equal(sent.at(-1).pending, null);
  assert.equal(JSON.stringify(sent).includes('private'), false);
});

test('structured stale snapshot and unknown outcomes get distinct recovery summaries, then clear on new work', async () => {
  const sent = [], session = { id: 'fixture-recovery' };
  const progress = createExecutionProgress(async row => sent.push(row), () => {});
  progress.toolFailure(session.id, 'stale', { code: 'stale_snapshot', message: 'private screen', details: { dispatched: false } });
  progress.sessionEvent(session, { type: 'tool/result', data: { message: { toolCallId: 'stale', isError: true } } });
  await settle();
  assert.equal(sent.at(-1).summary, '正在努力执行');
  assert.equal(sent.at(-1).summaryKind, 'working');
  assert.equal(sent.at(-1).phase, 'thinking');
  progress.sessionEvent(session, { type: 'tool/call', data: { name: 'android_observe', callId: 'observe' } });
  await settle();
  assert.equal(sent.at(-1).summary, '正在努力执行');
  assert.equal(sent.at(-1).summaryKind, 'working');
  progress.toolFailure(session.id, 'unknown', { code: 'action_timeout', details: { outcomeUnknown: true } });
  progress.sessionEvent(session, { type: 'tool/result', data: { message: { toolCallId: 'unknown', isError: true } } });
  await settle();
  assert.equal(sent.at(-1).phase, 'verifying');
  assert.equal(sent.at(-1).summary, '正在确认刚才的操作是否生效');
  assert.equal(sent.at(-1).summaryKind, undefined);
  assert.equal(JSON.stringify(sent).includes('private'), false);
});

test('intentional blocked copy clears when the agent resumes tools, but real questions remain', async () => {
  const sent = [], session = { id: 'fixture-blocked' };
  const progress = createExecutionProgress(async row => sent.push(row), () => {});
  progress.publicSummary(session.id, '当前页面需要登录，请查看任务', 'blocked'); await settle();
  assert.equal(sent.at(-1).phase, 'blocked');
  progress.sessionEvent(session, { type: 'tool/call', data: { name: 'android_observe', callId: 'observe' } });
  await settle();
  assert.equal(sent.at(-1).summary, '正在努力执行');
  assert.equal(sent.at(-1).summaryKind, 'working');
  progress.waiting(session.id, { requestId: 'question', message: '请选择后续方式', options: [], inline: false }); await settle();
  progress.toolFailure(session.id, 'stale', { code: 'stale_snapshot' });
  progress.sessionEvent(session, { type: 'tool/result', data: { message: { toolCallId: 'stale', isError: true } } });
  await settle();
  assert.equal(sent.at(-1).phase, 'waiting_input');
  assert.equal(sent.at(-1).summary, '请选择后续方式');
  assert.equal(sent.at(-1).summaryKind, undefined);
});

test('ordinary work keeps one semantic kind across repeated video-page changes without generating random transport text', async () => {
  const sent = [], session = { id: 'fixture-video' };
  const progress = createExecutionProgress(async row => sent.push(row), () => {});
  progress.status(session, 'running'); await settle();
  for (let index = 0; index < 6; index++) {
    progress.sessionEvent(session, { type: 'tool/call', data: { name: 'android_swipe', callId: `swipe-${index}` } });
    await settle();
    progress.toolFailure(session.id, `swipe-${index}`, { code: 'stale_snapshot', details: { dispatched: false } });
    progress.sessionEvent(session, { type: 'tool/result', data: { message: { toolCallId: `swipe-${index}`, isError: true } } });
    await settle();
    progress.sessionEvent(session, { type: 'step/start', data: {} }); await settle();
  }
  assert.ok(sent.every(row => row.summary === '正在努力执行' && row.summaryKind === 'working'));
  progress.publicSummary(session.id, '正在按你的要求浏览视频', 'executing'); await settle();
  assert.equal(sent.at(-1).summary, '正在按你的要求浏览视频');
  assert.equal(sent.at(-1).summaryKind, undefined);
  progress.publicSummary(session.id, '当前页面需要登录，请回到任务处理', 'blocked'); await settle();
  assert.equal(sent.at(-1).summaryKind, undefined);
  progress.sessionEvent(session, { type: 'turn/end', data: { reason: { kind: 'completed' } } }); await settle();
  assert.equal(sent.at(-1).summaryKind, undefined);
  assert.equal(sent.at(-1).phase, 'completed');
});


test('slow request uses only a local clock and clears on calls, pending input, cancellation and disposal', async () => {
  const sent = [], session = { id: 'slow-request' };
  let callback, cancelled = 0;
  const timers = { setTimeout(fn, ms) { assert.equal(ms, 10000); callback = fn; return 1; }, clearTimeout() { cancelled++; } };
  const progress = createExecutionProgress(async row => sent.push(row), () => assert.fail(), timers);
  progress.sessionEvent(session, { type: 'step/start', data: {} }); await settle();
  callback(); await settle();
  assert.equal(sent.at(-1).summaryKind, 'slow');
  assert.equal(sent.at(-1).active, true);
  progress.sessionEvent(session, { type: 'tool/call', data: { name: 'android_open_app' } }); await settle();
  assert.equal(sent.at(-1).phase, 'executing');
  assert.notEqual(sent.at(-1).summaryKind, 'slow');
  progress.sessionEvent(session, { type: 'step/start', data: {} });
  progress.waiting(session.id, { requestId: 'q', message: '选择目标', inline: false, options: [] });
  callback(); await settle();
  assert.equal(sent.at(-1).phase, 'waiting_input');
  progress.sessionEvent(session, { type: 'turn/end', data: { reason: { kind: 'aborted' } } });
  callback(); await settle();
  assert.equal(sent.at(-1).phase, 'interrupted');
  progress.sessionEvent(session, { type: 'step/start', data: {} });
  progress.dispose(); callback(); await settle();
  assert.ok(cancelled >= 2);
  assert.notEqual(sent.at(-1).summaryKind, 'slow');
});


test('a real request retry clears stale action copy without a model or inferred failure count', async () => {
  const sent = [], session = { id: 'retry-request' };
  const progress = createExecutionProgress(async row => sent.push(row), () => assert.fail());
  progress.publicSummary(session.id, '正在打开应用', 'executing');
  progress.sessionEvent(session, { type: 'llm/retry', data: { failure: { message: 'private provider detail' } } }); await settle();
  assert.equal(sent.at(-1).summary, '模型请求失败，正在等待重试，可停止任务');
  assert.equal(sent.at(-1).summaryKind, 'error');
  assert.equal(sent.at(-1).active, true);
  assert.equal(JSON.stringify(sent).includes('private'), false);
  progress.sessionEvent(session, { type: 'tool/call', data: { name: 'android_observe' } }); await settle();
  assert.equal(sent.at(-1).phase, 'executing');
  assert.equal(sent.at(-1).summaryKind, 'working');
  progress.dispose();
});

test('only an actual model retry marks an error and pending input keeps priority in both languages', async () => {
  for (const locale of ['zh', 'en']) {
    const sent = [], session = { id: `retry-kind-${locale}` };
    const progress = createExecutionProgress(async row => sent.push(row), () => assert.fail(), undefined, () => locale);
    progress.publicSummary(session.id, '用户原文包含错误两个字', 'thinking'); await settle();
    assert.equal(sent.at(-1).summary, '用户原文包含错误两个字');
    assert.equal(sent.at(-1).summaryKind, undefined);
    progress.toolFailure(session.id, 'recoverable', { code: 'stale_snapshot' });
    progress.sessionEvent(session, { type: 'tool/result', data: { message: { toolCallId: 'recoverable', isError: true } } });
    await settle();
    assert.equal(sent.at(-1).summaryKind, 'working');
    progress.sessionEvent(session, { type: 'llm/retry', data: { failure: { message: 'private provider detail' } } });
    await settle();
    assert.equal(sent.at(-1).summaryKind, 'error');
    assert.equal(sent.at(-1).activityKind, undefined);
    assert.equal(sent.at(-1).summary, locale === 'zh' ? '模型请求失败，正在等待重试，可停止任务'
      : 'The model request failed. Waiting to retry; you can stop the task.');
    progress.waiting(session.id, { requestId: 'question', message: '用户原始问题', options: [], inline: false }); await settle();
    progress.sessionEvent(session, { type: 'llm/retry', data: {} }); await settle();
    assert.equal(sent.at(-1).phase, 'waiting_input');
    assert.equal(sent.at(-1).summary, '用户原始问题');
    assert.equal(sent.at(-1).summaryKind, undefined);
    assert.equal(sent.at(-1).activityKind, undefined);
    progress.waiting(session.id, null); await settle();
    assert.equal(sent.at(-1).summaryKind, 'error');
    progress.sessionEvent(session, { type: 'turn/end', data: { reason: { kind: 'aborted' } } }); await settle();
    assert.equal(sent.at(-1).active, false);
    assert.equal(sent.at(-1).summaryKind, undefined);
    assert.equal(JSON.stringify(sent).includes('private'), false);
    progress.dispose();
  }
});

test('companion-owned work never publishes task progress through any event or recovery entry', async () => {
  const sent = [], timers = [];
  const managed = new Set(['companion-chat', 'companion-work']);
  const progress = createExecutionProgress(async row => sent.push(row), () => assert.fail(),
    { setTimeout(callback) { timers.push(callback); return timers.length; }, clearTimeout() {} }, () => 'en', id => !managed.has(id));
  for (const id of managed) {
    const session = { id };
    progress.bind(id, { taskId: id, phase: 'paused', active: true, controlRevision: 1 });
    progress.control(id, { taskId: id, phase: 'paused', active: true, controlRevision: 1 });
    progress.status(session, 'running');
    for (const [type, data] of [
      ['turn/start', {}], ['step/start', {}], ['llm/retry', {}],
      ['tool/call', { name: 'companion_reply' }], ['tool/result', { message: { isError: true } }],
      ['turn/end', { reason: { kind: 'completed' } }],
    ]) progress.sessionEvent(session, { type, data });
    progress.publicSummary(id, 'Fictional internal work.', 'thinking');
    progress.waiting(id, { requestId: 'private-question', message: 'Private question', options: [] });
    progress.toolFailure(id, 'private-tool', new Error('private'));
    progress.status(session, 'idle'); progress.removed(session);
  }
  await settle();
  assert.deepEqual(sent, []);
  assert.deepEqual(timers, []);
  progress.status({ id: 'user-task' }, 'running'); await settle();
  assert.equal(sent.length, 1); assert.equal(sent[0].taskId, 'user-task');
  progress.dispose();
});

test('late ownership resolution clears queued progress and slow-response callbacks', async () => {
  const sent = [], callbacks = []; let publicTask = true, release;
  const progress = createExecutionProgress(row => { sent.push(row); return new Promise(resolve => { release = resolve; }); },
    () => assert.fail(), { setTimeout(callback) { callbacks.push(callback); return callbacks.length; }, clearTimeout() {} },
    () => 'en', () => publicTask);
  const session = { id: 'resolved-companion' };
  progress.status(session, 'running');
  progress.sessionEvent(session, { type: 'step/start', data: {} });
  publicTask = false;
  callbacks[0]();
  release(); await settle();
  assert.equal(sent.length, 1, 'Already sent progress cannot be recalled; no queued stage or timer may follow');
  progress.control(session.id, { taskId: session.id, phase: 'completed', active: false, controlRevision: 2 });
  await settle(); assert.equal(sent.length, 1);
  progress.dispose();
});
