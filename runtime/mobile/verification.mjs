import { randomUUID } from 'node:crypto';
import { BridgeError } from './bridge.mjs';
import { resolveMobileLocale } from './locale.mjs';
const KINDS = new Set(['slider', 'puzzle', 'text', 'ordered_points', 'otp', 'password',
  'identity', 'biometric', 'blocked', 'unknown']);

/** Coordinates the existing human question surface; native code owns the action gate. */
export function createVerificationWorkflow(ctx, bridge) {
  const text = (zh, en) => resolveMobileLocale(ctx) === 'zh' ? zh : en;
  return async function verification(args, exec) {
    if (!['assess', 'complete', 'handoff'].includes(args.operation))
      throw new BridgeError('INVALID_VERIFICATION', text('验证操作必须是 assess、complete 或 handoff', 'Verification operation must be assess, complete or handoff'));
    const call = (action, values) => bridge.call(action, values, { ...exec, callId: randomUUID() });
    let result;
    if (args.operation === 'assess') {
      if (!KINDS.has(args.kind) || typeof args.solvable !== 'boolean' || typeof args.snapshotId !== 'string')
        throw new BridgeError('INVALID_VERIFICATION', text('评估需要验证类型、可完成判断和当前快照', 'Assessment requires a verification kind, solvability decision and current snapshot'));
      const { provider, model } = exec.agent.options;
      const info = provider && model ? await ctx.llm.resolveModelInfo(provider, model, exec.signal) : null;
      result = await call('verification', { operation: 'assess', kind: args.kind, solvable: args.solvable,
        snapshotId: args.snapshotId, imageSupported: info?.inputModalities?.includes('image') === true });
    } else if (args.operation === 'complete') {
      if (typeof args.passed !== 'boolean' || typeof args.snapshotId !== 'string')
        throw new BridgeError('INVALID_VERIFICATION', text('结果判断需要当前快照和明确的 passed 布尔值', 'The result requires a current snapshot and an explicit passed boolean'));
      result = await call('verification', { operation: 'complete', passed: args.passed, snapshotId: args.snapshotId });
    } else {
      result = await call('verification', { operation: 'handoff' });
    }
    if (result.state !== 'manual_required') return result;

    // The native gate is established before exposing the question. Waiting adds
    // no polling/model calls and the existing cancellation signal owns its life.
    if (args.operation !== 'handoff') result = await call('verification', { operation: 'handoff' });
    const completed = text('已完成，继续', 'Completed, continue');
    const deferred = text('暂不处理', 'Not now');
    const answer = await ctx.userQuestions.ask({ agent: exec.agent, signal: exec.signal,
      questions: [{ id: 'verification', question: text('请在原应用完成当前验证，完成后选择继续。', 'Complete the current verification in the original app, then select Continue.'),
        options: [{ label: completed }, { label: deferred }] }] });
    exec.signal.throwIfAborted();
    const choice = answer.answers?.find(item => item.id === 'verification');
    // Free text is not interpreted as authorization to resume, and is not
    // echoed into tool results (it could contain a password or verification code).
    if (choice?.custom || choice?.selected?.length !== 1 || choice.selected[0] !== completed)
      return { ...result, userDeferred: true, nextAction: text('等待用户后续处理；不要重复提问或继续操作验证页面。', 'Wait for the user. Do not repeat the question or continue operating the verification page.') };
    // Answering in the chat brings Droy to the foreground. Only native-stored
    // target identity may restore the original app, while the gate stays closed.
    await call('verification', { operation: 'return_to_app' });
    const observation = await call('observe', {});
    exec.signal.throwIfAborted();
    if (typeof observation.snapshotId !== 'string' || !observation.snapshotId)
      throw new BridgeError('INVALID_OBSERVATION', text('手动验证后的页面观察没有有效快照，请重新观察', 'The observation after manual verification has no valid snapshot. Observe again.'));
    const resumed = await call('verification', { operation: 'resume', snapshotId: observation.snapshotId });
    return { ...resumed, observation,
      nextAction: text('先检查当前页面；用户选择继续不代表验证成功。确认验证通过后用 complete 和当前快照报告结果。', 'Inspect the current page first. Selecting Continue does not prove verification succeeded. After confirming success, report complete with the current snapshot.') };
  };
}
