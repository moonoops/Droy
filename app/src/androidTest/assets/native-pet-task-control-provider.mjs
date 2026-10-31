// Test APK only. Calls real DSH tools; every model reply stays inside this owned local fixture.
import { appendFile, readFile, writeFile } from 'node:fs/promises';
import { dirname, join } from 'node:path';
import { pathToFileURL } from 'node:url';

export const name = 'droy-native-pet-task-control-fixture';
export const inject = ['llm', 'tools', 'userQuestions'];

export async function apply(ctx) {
  const home = process.env.DSH_HOME;
  const module = path => pathToFileURL(join(process.env.DROY_RUNTIME_DIR, 'node_modules', path)).href;
  const { LlmAdapter } = await import(module('@deepseek-ai/dsh-llm/lib/index.js'));
  const { defineTool } = await import(module('@deepseek-ai/dsh-tools/lib/index.js'));
  const { requireMobileSession } = await import(module('@droyagent/mobile/auth.mjs'));
  const { resumePrompt } = await import(module('@droyagent/mobile/task-control.mjs'));
  const sessions = new Set(), stages = new Map();
  const journal = join(home, 'native-pet-control-events.jsonl');
  let sequence = 0, writes = Promise.resolve();
  const record = event => {
    const row = { sequence: ++sequence, time: Date.now(), ...event };
    writes = writes.then(() => appendFile(journal, JSON.stringify(row) + '\n'));
    return writes.then(() => row);
  };
  const textOf = message => (message?.content ?? []).filter(part => part.type === 'text').map(part => part.text).join('\n');
  const chunks = (id, name, args) => [
    { type: 'block-start', index: 0, blockType: 'tool-call' },
    { type: 'tool-call-delta', index: 0, id, name, argumentsDelta: JSON.stringify(args) },
    { type: 'block-end', index: 0, block: { type: 'tool-call', id, name, arguments: JSON.stringify(args) } },
    { type: 'finish', reason: { kind: 'tool-calls' } },
  ];
  const reply = text => [
    { type: 'block-start', index: 0, blockType: 'text' },
    { type: 'text-delta', index: 0, text },
    { type: 'block-end', index: 0, block: { type: 'text', text } },
    { type: 'finish', reason: { kind: 'stop' } },
  ];

  // This waterfall records actual SDK body completion, including the controller's
  // direct android_observe call. It delegates every tool without changing its result.
  ctx.on('tools/execute', async (exec, next) => {
    const sessionId = String(exec.agent?.id ?? '');
    if (!sessions.has(sessionId)) return next();
    await record({ kind: 'tool-enter', sessionId, name: exec.name, callId: exec.callId });
    try {
      const result = await next();
      await record({ kind: 'tool-exit', sessionId, name: exec.name, callId: exec.callId, isError: result.isError === true });
      return result;
    } catch (error) {
      await record({ kind: 'tool-threw', sessionId, name: exec.name, callId: exec.callId });
      throw error;
    }
  });

  ctx.tools.register(defineTool({
    name: 'native_pet_fixture_question', description: 'Ask an owned local multi-choice test question',
    parameters: { mode: { type: 'string', enum: ['mixed', 'skip', 'cancel'], required: true } },
    output: { schema: { type: 'json' }, render: (_args, value) => [{ type: 'text', text: JSON.stringify(value) }] },
    async execute({ mode }, exec) {
      const sessionId = String(exec.agent?.id ?? '');
      if (!sessions.has(sessionId)) throw new Error('Pet question requires its owned fixture session');
      await record({ kind: 'question-open', sessionId, mode, callId: exec.callId });
      try {
        const value = await ctx.userQuestions.ask({ agent: exec.agent, signal: exec.signal, wait: { callId: exec.callId },
          questions: [{ id: 'preferences', question: 'Choose fictional preferences', multiSelect: true,
            options: [{ label: 'Alpha' }, { label: 'Beta' }, { label: 'Gamma' }] }] });
        exec.signal.throwIfAborted();
        await record({ kind: 'question-answer', sessionId, mode, value });
        return value;
      } catch (error) {
        await record({ kind: 'question-cancelled', sessionId, mode });
        throw error;
      }
    },
  }));

  class PetControlFixture extends LlmAdapter {
    async resolveModel(provider, id) {
      return { provider, id, name: 'Local pet control fixture', inputModalities: ['text'],
        context: { contextWindow: 32768 }, defaultMaxTokens: 1024 };
    }
    async listModels(provider) { return [await this.resolveModel(provider, 'native-local')]; }
    async *stream(options) {
      if (options.provider !== 'droy-native-fixture') throw new Error('Unexpected pet fixture provider');
      const all = options.messages.map(textOf).join('\n');
      const title = options.purpose === 'session-title' || String(options.system).includes('Create a concise title')
        || all.includes('Generate the session title from this JSON array');
      const sessionId = String(options.sessionId ?? '');
      const lifecycle = all.includes('PET_TASK_CONTROL_LIFECYCLE');
      const questionMode = ['mixed', 'skip', 'cancel'].find(mode => all.includes('PET_QUESTION_' + mode.toUpperCase()));
      if (!title && !lifecycle && !questionMode) throw new Error('Unexpected non-fixture pet prompt');
      if (!title) sessions.add(sessionId);
      const resumed = all.includes(resumePrompt.en) || all.includes(resumePrompt.zh);
      await record({ kind: 'model', sessionId, title, lifecycle, questionMode, resumed,
        originalContext: lifecycle, parkedContext: all.includes('PET_PARKED_USER_MESSAGE') });
      let output;
      if (title) output = reply('Local pet fixture conversation');
      else if (lifecycle) {
        const stage = stages.get(sessionId);
        let callId;
        if (!stage && !resumed) {
          stages.set(sessionId, 'before-pause'); callId = 'pet-wait-before-pause';
        } else if (stage === 'before-pause' && resumed) {
          stages.set(sessionId, 'after-resume'); callId = 'pet-wait-after-resume';
        } else throw new Error('Old wait was replayed, or a held task reached the model');
        if (!(options.tools ?? []).some(tool => tool.name === 'android_wait_for')) throw new Error('Real Android wait tool is unavailable');
        output = chunks(callId, 'android_wait_for', {
          condition: { packageName: 'io.droyagent.fixture.absent', state: 'present' }, timeoutMs: 60000,
        });
      } else {
        const id = 'pet-question-' + questionMode;
        const prior = options.messages.findLast(message => message.role === 'tool' && message.toolCallId === id);
        if (prior) {
          if (prior.isError) throw new Error('Real pet question tool failed');
          output = reply('PET_QUESTION_ACCEPTED_' + questionMode.toUpperCase());
        } else if (!(options.tools ?? []).some(tool => tool.name === 'native_pet_fixture_question')) {
          const loaded = options.messages.some(message => message.role === 'tool' && message.toolCallId === id + '-load');
          if (loaded) throw new Error('Fixture extension was not exposed after loading');
          output = chunks(id + '-load', 'droy_load_tools', { group: 'extensions' });
        } else output = chunks(id, 'native_pet_fixture_question', { mode: questionMode });
      }
      for (const item of output) { options.signal?.throwIfAborted(); yield item; }
    }
  }
  ctx.llm.registerAdapter(['droy-native-fixture'], new PetControlFixture());

  ctx.inject(['droyTaskControl', 'droyCompanions', 'webServer', 'agents', 'sessions'], async scope => {
    async function isolated() {
      const marker = JSON.parse(await readFile(join(dirname(home), 'native-workbench-fixture.json'), 'utf8'));
      if (marker.fixture !== 'native-isolated-runtime-v1' || marker.phase !== 'running'
        || !/^native-workbench-home-[0-9]+$/u.test(marker.backup)
        || marker.preferences !== marker.backup + '-preferences') throw new Error('Pet control fixture is not isolated');
    }
    await isolated();
    scope.webServer.register({ kind: 'exact', path: '/droy/native/fixture/pet-control', async handler(req, res) {
      if (!requireMobileSession(req, res)) return;
      if (req.method !== 'GET') { req.resume(); res.writeHead(405); res.end(); return; }
      await isolated();
      const url = new URL(req.url, 'http://127.0.0.1');
      const id = url.searchParams.get('sessionId');
      if (!sessions.has(id) || [...url.searchParams.keys()].some(key => key !== 'sessionId')) {
        res.writeHead(400); res.end(); return;
      }
      await writes;
      const agent = scope.agents.get(id), control = scope.droyTaskControl.get(id);
      const events = (await readFile(journal, 'utf8')).trim().split('\n').filter(Boolean).map(JSON.parse)
        .filter(row => row.sessionId === id);
      const content = item => (item.content ?? []).some(part => part.type === 'text' && part.text.includes('PET_PARKED_USER_MESSAGE'));
      const inbox = agent ? [...agent.inbox.nextTurn, ...agent.inbox.nextStep] : [];
      const result = { sessionId: id, agentId: agent?.id, agentStatus: agent?.status,
        control, scope: scope.droyTaskControl.currentScope(id) ?? null, events,
        parked: inbox.some(content), inboxCount: inbox.length,
        sessionHeaderId: agent?.session.header.id };
      res.writeHead(200, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' });
      res.end(JSON.stringify({ ok: true, result }));
    } });
    await writeFile(join(home, 'native-fixture-ready.json'), '{"ready":true}');
  });
}
