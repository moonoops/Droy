import { appendFile, writeFile } from 'node:fs/promises';
import { existsSync } from 'node:fs';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';
import { setTimeout as delay } from 'node:timers/promises';

export const name = 'droy-companion-isolation-fixture';
export const inject = ['llm'];
export async function apply(ctx) {
  const { LlmAdapter } = await import(pathToFileURL(join(process.env.DROY_RUNTIME_DIR,
    'node_modules/@deepseek-ai/dsh-llm/lib/index.js')).href);
  const file = name => join(process.env.DSH_HOME, 'isolation-' + name);
  const lifetime = new AbortController();
  ctx.effect(() => () => lifetime.abort());
  ctx.on('webserver/request', (req, res, row, next) => {
    if (row.path === '/droy/shutdown' && Number(req.headers['content-length']) > 0 && existsSync(file('hold-idle-response'))) {
      const end = res.end;
      res.end = function(body, ...args) {
        if (String(body).includes('DROY_SHUTDOWN_READY')) {
          void (async () => {
            await writeFile(file('idle-committed'), 'true');
            while (!existsSync(file('release-idle-response'))) await delay(20, undefined, { signal: lifetime.signal });
            end.call(this, body, ...args);
          })().catch(() => this.destroy());
          return this;
        }
        return end.call(this, body, ...args);
      };
    }
    return next();
  }, { prepend: true });
  const textOf = message => (message.content ?? []).filter(block => block.type === 'text').map(block => block.text).join('');
  const tool = (id, name, args) => [
    { type: 'block-start', index: 0, blockType: 'tool-call' },
    { type: 'tool-call-delta', index: 0, id, name, argumentsDelta: JSON.stringify(args) },
    { type: 'block-end', index: 0, block: { type: 'tool-call', id, name, arguments: JSON.stringify(args) } },
    { type: 'finish', reason: { kind: 'tool-calls' } },
  ];
  const text = value => [
    { type: 'block-start', index: 0, blockType: 'text' }, { type: 'text-delta', index: 0, text: value },
    { type: 'block-end', index: 0, block: { type: 'text', text: value } }, { type: 'finish', reason: { kind: 'stop' } },
  ];
  let call = 0;
  const handled = new Map();
  async function hold(kind, id, signal, extra = {}) {
    await writeFile(file('running-' + kind + '-' + id + '.json'), JSON.stringify(extra));
    try { while (!existsSync(file('release-' + kind + '-' + id))) await delay(50, undefined, { signal }); }
    catch (error) { await writeFile(file('aborted-' + kind + '-' + id), 'true'); throw error; }
  }
  class LocalModel extends LlmAdapter {
    async resolveModel(provider, id) {
      return { provider, id, name: 'Companion isolation local fixture', inputModalities: ['text'],
        context: { contextWindow: 32768 }, defaultMaxTokens: 1024 };
    }
    async listModels(provider) { return [await this.resolveModel(provider, 'native-local')]; }
    async *stream(options) {
      const runtime = ctx.get('droyCompanions'), owner = runtime?.owners.get(options.sessionId);
      const planner = options.tools?.some(item => item.name === 'companion_reply');
      const userContent = options.messages.filter(message => message.role === 'user').map(textOf).join('\n');
      const title = String(options.system).includes('Create a concise title')
        || userContent.includes('Generate the session title from this JSON array');
      await appendFile(file('model-calls.jsonl'), JSON.stringify({ sessionId: options.sessionId, title, planner,
        ownerKind: owner?.kind, userHold: userContent.includes('ISOLATION_USER_HOLD') }) + '\n');
      if (title) { for (const chunk of text('Fictional conversation')) yield chunk; return; }
      if (owner && planner) {
        const pending = await runtime.store.pendingMessages(owner.companionId);
        const signature = pending.map(item => item.id).join(':');
        let name, args;
        if (owner.kind === 'work') {
          name = 'companion_reply'; args = { msg_id: '', reply_reference: 'ISOLATION_WORK_RESULT' };
        } else if (signature && handled.get(options.sessionId) !== signature) {
          handled.set(options.sessionId, signature);
          const source = pending.at(-1);
          if (source?.role === 'user' && source.text.startsWith('ISOLATION_WORK')) {
            name = 'companion_work'; args = { title: 'Fictional internal work', goal: source.text };
          } else {
            name = 'companion_reply'; args = { msg_id: source.id,
              reply_reference: source.type === 'task' ? 'ISOLATION_CHAT_RESULT' : 'ISOLATION_CHAT_HOLD' };
          }
        } else { name = 'companion_quiet'; args = {}; }
        for (const chunk of tool('isolation:' + ++call, name, args)) { options.signal.throwIfAborted(); yield chunk; }
        return;
      }
      if (owner) {
        const reference = options.messages.map(textOf).find(value => value.startsWith('[Reply Reference]') || value.startsWith('【回复信息参考】')) ?? '';
        if (owner.kind === 'work') await hold('work', owner.companionId, options.signal, { sessionId: owner.sessionId, taskId: owner.taskId });
        else if (reference.includes('ISOLATION_CHAT_HOLD')) await hold('chat', owner.companionId, options.signal);
        for (const chunk of text(owner.kind === 'work' ? 'Fictional work result.' : 'Companion finished the background work.')) {
          options.signal?.throwIfAborted(); yield chunk;
        }
        return;
      }
      if (userContent.includes('ISOLATION_USER_HOLD')) await hold('user', options.sessionId, options.signal);
      for (const chunk of text('Fictional user task result.')) { options.signal?.throwIfAborted(); yield chunk; }
    }
  }
  ctx.llm.registerAdapter(['droy-native-fixture'], new LocalModel());
  ctx.on('agent/status', ({ agent, status }) => appendFile(file('agents.jsonl'), JSON.stringify({ sessionId: agent.id, status }) + '\n'));
  await writeFile(join(process.env.DSH_HOME, 'native-fixture-ready.json'), JSON.stringify({ ready: true }));
}
