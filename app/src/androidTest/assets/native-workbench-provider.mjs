import { appendFile, mkdir, readFile, writeFile } from 'node:fs/promises';
import { existsSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { createHash } from 'node:crypto';
import { pathToFileURL } from 'node:url';
import { setTimeout as delay } from 'node:timers/promises';
import { createServer } from 'node:http';
import { once } from 'node:events';
export const name = 'droy-native-workbench-fixture';
export const inject = ['llm', 'tools', 'approval', 'userQuestions', 'credentialsController'];
export async function apply(ctx) {
  const transcriptFixture = await installNativeTranscriptFixture(ctx);
  const { LlmAdapter } = await import(pathToFileURL(join(process.env.DROY_RUNTIME_DIR,
    'node_modules/@deepseek-ai/dsh-llm/lib/index.js')).href);
  const { defineTool } = await import(pathToFileURL(join(process.env.DROY_RUNTIME_DIR,
    'node_modules/@deepseek-ai/dsh-tools/lib/index.js')).href);
  ctx.on('agent/status', ({ agent, status }) => appendFile(join(process.env.DSH_HOME, 'native-fixture-status.jsonl'), JSON.stringify({sessionId:agent.id,status,time:Date.now()})+'\n'));
  // A provider never receives Droy's private session cookie. Its local fixture
  // therefore uses a separate listener from the authenticated DSH web server.
  const upstream = createServer(async (req,res) => {
    try {
      if (req.method !== 'GET' || req.url !== '/v1/models') { res.writeHead(404); res.end(); return; }
      const authenticated = req.headers.authorization === 'Bearer fictional-settings-key';
      await appendFile(join(process.env.DSH_HOME,'native-fixture-settings.jsonl'),JSON.stringify({kind:'discovery',authenticated})+'\n');
      res.writeHead(authenticated?200:401,{'Content-Type':'application/json'});
      res.end(JSON.stringify(authenticated?{data:[{id:'fixture-upstream',object:'model',owned_by:'local-fixture'}]}:{error:'Fixture authentication required'}));
    } catch { if (!res.headersSent) res.writeHead(500); res.end(); }
  });
  upstream.listen(0, '127.0.0.1');
  await once(upstream, 'listening');
  await writeFile(join(process.env.DSH_HOME, 'native-fixture-api.json'), JSON.stringify({port: upstream.address().port}));
  ctx.effect(() => () => { upstream.closeAllConnections(); upstream.close(); });
  const credentials = ctx.credentialsController, originalSet = credentials.set;
  credentials.set = async function(ref,value) {
    await appendFile(join(process.env.DSH_HOME,'native-fixture-settings.jsonl'),JSON.stringify({kind:'credential-set',ref})+'\n');
    return originalSet.call(this,ref,value);
  };
  ctx.effect(()=>()=>{credentials.set=originalSet;});
  const output = { schema: { type: 'json' }, render: (_args, value) => [{ type: 'text', text: JSON.stringify(value) }] };
  ctx.tools.register(defineTool({ name: 'native_fixture_approval', description: 'Local no-op approval fixture', parameters: {}, output,
    async execute(_args, exec) {
      if (!exec.agent) throw new Error('Fixture requires a live agent');
      await appendFile(join(process.env.DSH_HOME, 'native-fixture-tools.jsonl'), JSON.stringify({kind:'approval',phase:'entered',callId:exec.callId})+'\n');
      const outcome = await ctx.approval.request({ agent: exec.agent, toolName: 'native_fixture_approval',
        callId: exec.callId, reason: 'Confirm a local no-op test.',
        displayReason: { zh: '确认本地测试？', en: 'Confirm local test?' }, signal: exec.signal });
      exec.signal.throwIfAborted();
      await appendFile(join(process.env.DSH_HOME, 'native-fixture-tools.jsonl'), JSON.stringify({kind:'approval',phase:'answered',outcome,callId:exec.callId})+'\n');
      return { outcome };
    } }));
  ctx.tools.register(defineTool({ name: 'native_fixture_question', description: 'Local choice fixture', parameters: {}, output,
    async execute(_args, exec) {
      if (!exec.agent) throw new Error('Fixture requires a live agent');
      await appendFile(join(process.env.DSH_HOME, 'native-fixture-tools.jsonl'), JSON.stringify({kind:'question',phase:'entered',callId:exec.callId})+'\n');
      const value = await ctx.userQuestions.ask({ agent: exec.agent, signal: exec.signal, wait: { callId: exec.callId },
        questions: [{ id: 'choice', question: 'Choose a test option', options: [{ label: 'Alpha' }, { label: 'Beta' }] }] });
      exec.signal.throwIfAborted();
      await appendFile(join(process.env.DSH_HOME, 'native-fixture-tools.jsonl'), JSON.stringify({kind:'question',phase:'answered',value,callId:exec.callId})+'\n');
      return value;
    } }));
  const textOf = message => (message.content ?? []).filter(block => block.type === 'text').map(block => block.text).join('');
  const toolChunks = (id, name, args = {}) => [
    { type: 'block-start', index: 0, blockType: 'tool-call' },
    { type: 'tool-call-delta', index: 0, id, name, argumentsDelta: JSON.stringify(args) },
    { type: 'block-end', index: 0, block: { type: 'tool-call', id, name, arguments: JSON.stringify(args) } },
    { type: 'finish', reason: { kind: 'tool-calls' } },
  ];
  const companionReplies = new Map();
  let companionCall = 0;
  class NativeFixture extends LlmAdapter {
    async resolveModel(provider, id) {
      return { provider, id, name: id === 'native-alternate' ? 'Native alternate fixture' : id === 'native-vision' ? 'Native vision fixture' : 'Native local fixture', inputModalities: id === 'native-vision' ? ['text', 'image'] : ['text'],
        context: { contextWindow: 32768 }, defaultMaxTokens: 1024 };
    }
    async listModels(provider) { return [await this.resolveModel(provider, 'native-local'), await this.resolveModel(provider, 'native-alternate'), await this.resolveModel(provider, 'native-vision')]; }
    async *stream(options) {
      if (transcriptFixture) transcriptFixture.modelCalls++;
      if (options.tools?.some(tool => tool.name === 'droy_setup_echo')) {
        const input = JSON.parse(textOf(options.messages.at(-1)));
        for (const chunk of toolChunks('native-setup-echo','droy_setup_echo',{value:input.value})) {
          options.signal?.throwIfAborted(); yield chunk;
        }
        return;
      }
      if (options.tools?.some(tool => tool.name === 'companion_reply')) {
        const runtime = ctx.get('droyCompanions');
        const owner = runtime?.owners.get(options.sessionId);
        if (!owner) throw new Error('Companion fixture requires real session ownership');
        const pending = await runtime.store.pendingMessages(owner.companionId);
        const signature = pending.map(message => message.id).join(':');
        const latest = pending.at(-1);
        const reply = !!signature && companionReplies.get(options.sessionId) !== signature;
        if (reply) companionReplies.set(options.sessionId, signature);
        const reference = latest?.text?.includes('NATIVE_COMPANION_HOLD')
          ? 'NATIVE_COMPANION_REPLY_HOLD' : 'NATIVE_COMPANION_REPLY';
        const stickerTurn = reply && latest.text === 'NATIVE_COMPANION_STICKER';
        const name = stickerTurn ? 'companion_emoji' : reply ? 'companion_reply' : 'companion_quiet';
        const args = stickerTurn ? { msg_id: latest.id, query: 'Fictional-wave' }
          : reply ? { msg_id: latest.id, reply_reference: reference,
            quoteReply: latest.text?.includes('NATIVE_COMPANION_QUOTE') === true } : {};
        await appendFile(join(process.env.DSH_HOME, 'native-fixture-companion.jsonl'), JSON.stringify({
          kind: 'planner', sessionId: options.sessionId, companionId: owner.companionId, name, signature,
        }) + '\n');
        for (const chunk of toolChunks('native-companion:' + ++companionCall, name, args)) {
          options.signal?.throwIfAborted(); yield chunk;
        }
        return;
      }
      let selectionInput;
      try { selectionInput = JSON.parse(textOf(options.messages.at(-1))); } catch { /* Other fixture calls use ordinary text. */ }
      if (selectionInput?.candidateFormat && selectionInput.items?.[0]?.emoji_index) {
        const text = JSON.stringify({emoji_index: selectionInput.items[0].emoji_index});
        await appendFile(join(process.env.DSH_HOME, 'native-fixture-companion.jsonl'), JSON.stringify({kind:'sticker-selection'})+'\n');
        for (const chunk of [
          {type:'block-start',index:0,blockType:'text'}, {type:'text-delta',index:0,text},
          {type:'block-end',index:0,block:{type:'text',text}}, {type:'finish',reason:{kind:'stop'}},
        ]) { options.signal?.throwIfAborted(); yield chunk; }
        return;
      }
      const reference = options.messages.map(textOf).find(text => text.startsWith('[Reply Reference]') || text.startsWith('【回复信息参考】'));
      if (reference?.includes('NATIVE_COMPANION_REPLY')) {
        const hold = reference.includes('NATIVE_COMPANION_REPLY_HOLD');
        await appendFile(join(process.env.DSH_HOME, 'native-fixture-companion.jsonl'), JSON.stringify({kind:'replyer',hold})+'\n');
        if (hold) {
          try { await delay(120000, undefined, {signal:options.signal}); }
          catch (error) {
            await writeFile(join(process.env.DSH_HOME, 'native-fixture-companion-aborted.json'), JSON.stringify({time:Date.now()}));
            throw error;
          }
        }
        const reply = 'Native companion reply received.';
        for (const chunk of [
          {type:'block-start',index:0,blockType:'text'},
          {type:'text-delta',index:0,text:reply},
          {type:'block-end',index:0,block:{type:'text',text:reply}},
          {type:'finish',reason:{kind:'stop'}},
        ]) { options.signal?.throwIfAborted(); yield chunk; }
        return;
      }
      const text = options.messages.filter(m => m.role === 'user').flatMap(m => m.content ?? [])
        .filter(p => p.type === 'text').map(p => p.text).join('\n');
      const title = text.includes('Generate the session title from this JSON array')
        || String(options.system).includes('Create a concise title');
      const at = options.messages.findLastIndex(message => message.role === 'user'
        && (!message.source || ['user', 'user-rpc'].includes(message.source.kind)));
      const prompt = at < 0 ? '' : textOf(options.messages[at]);
      const kind = prompt.includes('NATIVE_APPROVAL') ? 'approval' : prompt.includes('NATIVE_QUESTION') ? 'question' : null;
      const goalRound = options.messages.findLast(message => message.role === 'user'
        && message.source?.kind === 'goal' && message.source.round > 0
        && textOf(message).includes('NATIVE_GOAL_LIFECYCLE'));
      await appendFile(join(process.env.DSH_HOME, 'native-fixture-calls.jsonl'), JSON.stringify({
        sessionId: options.sessionId, title, model: options.model, hold: prompt.includes('NATIVE_HOLD'),
        hello: prompt === 'NATIVE_HELLO', kind, prompt,
        lastSource: options.messages[at]?.source?.kind,
        goal: goalRound?.source,
        inputKinds: (options.messages[at]?.content ?? []).map(block => block.type),
        tools: (options.tools ?? []).map(tool => tool.name),
        turnTools: options.messages.slice(at + 1).filter(message => message.role === 'tool')
          .map(message => ({id:message.toolCallId,isError:message.isError,text:textOf(message).slice(0,4000)})),
      }) + '\n');
      if (!title && prompt.startsWith('NATIVE_FIRST_USE_APPS_')) {
        const id = 'native-first-use:' + (options.messages[at].id ?? at);
        const result = options.messages.slice(at + 1).find(message => message.role === 'tool' && message.toolCallId === id);
        if (!result) {
          if (!options.tools?.some(tool => tool.name === 'android_find_apps')) throw new Error('Actual apps tool was not exposed');
          for (const chunk of toolChunks(id, 'android_find_apps', {query:'NativeFixtureApp',limit:3})) {
            options.signal?.throwIfAborted(); yield chunk;
          }
          return;
        }
        const refused = result.isError === true;
        const expectedRefusal = prompt.endsWith('_REFUSED');
        if (refused !== expectedRefusal) throw new Error('Unexpected first-use tool outcome: ' + textOf(result));
        await appendFile(join(process.env.DSH_HOME, 'native-fixture-first-use.jsonl'), JSON.stringify({
          sessionId:options.sessionId,callId:id,refused,result:textOf(result).slice(0,4000),
        })+'\n');
        const reply = refused ? 'Native first-use refused.' : 'Native first-use allowed.';
        for (const chunk of [
          {type:'block-start',index:0,blockType:'text'},
          {type:'text-delta',index:0,text:reply},
          {type:'block-end',index:0,block:{type:'text',text:reply}},
          {type:'finish',reason:{kind:'stop'}},
        ]) { options.signal?.throwIfAborted(); yield chunk; }
        return;
      }
      if (!title && kind) {
        const name = 'native_fixture_' + kind;
        const id = 'native-' + kind + ':' + (options.messages[at].id ?? at);
        const turn = options.messages.slice(at + 1);
        const load = turn.find(message => message.role === 'tool' && message.toolCallId === id + ':load');
        if (!(options.tools ?? []).some(tool => tool.name === name)) {
          if (load) throw new Error('Extension loader did not expose fixture tool');
          for (const chunk of toolChunks(id + ':load', 'droy_load_tools', { group: 'extensions' })) {
            options.signal?.throwIfAborted(); yield chunk;
          }
          return;
        }
        const result = turn.find(message => message.role === 'tool' && message.toolCallId === id);
        if (!result) {
          for (const chunk of toolChunks(id, name)) { options.signal?.throwIfAborted(); yield chunk; }
          return;
        }
        if (result.isError) throw new Error('Fixture tool failed: ' + textOf(result));
        const value = JSON.parse(textOf(result));
        let reply;
        if (kind === 'approval') {
          if (!['allowed-once', 'rejected'].includes(value.outcome)) throw new Error('Unexpected approval outcome');
          reply = 'Native approval: ' + value.outcome + '.';
        } else {
          if (JSON.stringify(value) !== JSON.stringify({ answers: [{ id: 'choice', selected: ['Alpha'] }] }))
            throw new Error('Actual question answer was not Alpha');
          reply = 'Native question: Alpha.';
        }
        for (const chunk of [
          { type: 'block-start', index: 0, blockType: 'text' },
          { type: 'text-delta', index: 0, text: reply },
          { type: 'block-end', index: 0, block: { type: 'text', text: reply } },
          { type: 'finish', reason: { kind: 'stop' } },
        ]) { options.signal?.throwIfAborted(); yield chunk; }
        return;
      }
      yield { type: 'block-start', index: 0, blockType: 'text' };
      if (!title && goalRound) {
        yield { type: 'text-delta', index: 0, text: 'Local goal round started.' };
        try {
          if (goalRound.source.round === 3) {
            while (!existsSync(join(process.env.DSH_HOME, 'native-fixture-goal-fail-round-3')))
              await delay(50, undefined, {signal: options.signal});
            throw new Error('Local controlled goal provider failure');
          }
          await delay(120000, undefined, {signal: options.signal});
        } catch (error) {
          const outcome = options.signal?.aborted ? 'aborted' : 'failed';
          await writeFile(join(process.env.DSH_HOME, `native-fixture-goal-${outcome}.json`), JSON.stringify(goalRound.source));
          throw error;
        }
      }
      if (!title && prompt.includes('NATIVE_BACKGROUND_REPLY')) {
        yield { type: 'text-delta', index: 0, text: 'Background reply started.' };
        while (!existsSync(join(process.env.DSH_HOME, 'native-fixture-background-release')))
          await delay(50, undefined, { signal: options.signal });
      }
      if (!title && prompt.includes('NATIVE_HOLD')) {
        yield { type: 'text-delta', index: 0, text: 'Local hold started' };
        try { await delay(120000, undefined, { signal: options.signal }); }
        catch (error) {
          await writeFile(join(process.env.DSH_HOME, 'native-fixture-hold-aborted.json'), JSON.stringify({sessionId:options.sessionId,time:Date.now()}));
          throw error;
        }
      }
      const reply = title ? 'Native fixture conversation' : 'Native DSH reply received.';
      for (const part of [reply.slice(0, 10), reply.slice(10)]) {
        yield { type: 'text-delta', index: 0, text: part };
        await delay(60, undefined, { signal: options.signal });
      }
      yield { type: 'block-end', index: 0, block: { type: 'text', text: reply } };
      yield { type: 'usage', usage: { inputTokens: 20, outputTokens: 8, cacheReadTokens: 10, cacheWriteTokens: 0, totalTokens: 38 } };
      yield { type: 'finish', reason: { kind: 'stop' } };
    }
  }
  ctx.llm.registerAdapter(['droy-native-fixture'], new NativeFixture());
  await writeFile(join(process.env.DSH_HOME, 'native-fixture-ready.json'), '{"ready":true}');
}

/** Only the explicitly owned isolated Android fixture may materialize these cold logs. */
export async function installNativeTranscriptFixture(ctx) {
  const home = process.env.DSH_HOME;
  if (!home) return null;
  let request;
  try { request = JSON.parse(await readFile(join(home, 'native-transcript-fixture-request.json'), 'utf8')); }
  catch (error) { if (error.code === 'ENOENT') return null; throw error; }
  const ownership = JSON.parse(await readFile(join(dirname(home), 'native-workbench-fixture.json'), 'utf8'));
  if (request.fixture !== 'native-transcript-v1' || request.version !== 1 || Object.keys(request).length !== 2
    || ownership.fixture !== 'native-isolated-runtime-v1' || ownership.phase !== 'running'
    || !/^native-workbench-home-[0-9]+$/u.test(ownership.backup) || ownership.preferences !== ownership.backup + '-preferences'
    || typeof ownership.hadHome !== 'boolean' || resolve(home) !== join(resolve(dirname(home)), 'dsh-home'))
    throw new Error('Invalid isolated transcript fixture ownership');
  const audit = { modelCalls: 0, createdSessions: [], agentStatuses: [], cancelCalls: [], follows: [], pages: [], activeFollows: 0, closedFollows: 0 };
  await ctx.inject(['sessions', 'sessionPersistence', 'workspaceRegistry', 'workspaceController', 'sessionController', 'webServer', 'agents'], async scope => {
    const module = name => import(pathToFileURL(join(process.env.DROY_RUNTIME_DIR, 'node_modules', name, 'lib/index.js')).href);
    const [{ createUserMessage, createAssistantMessage }, { requireMobileSession }] = await Promise.all([
      module('@deepseek-ai/dsh-llm'), import(pathToFileURL(join(process.env.DROY_RUNTIME_DIR, 'node_modules/@droyagent/mobile/auth.mjs')).href),
    ]);
    const ids = { parent: 'native-transcript-parent-v1', child: 'native-transcript-child-v1',
      missingDescriptor: 'native-transcript-no-descriptor-v1', ordinaryFork: 'native-transcript-fork-v1' };
    const allIds = Object.values(ids), turns = 90;
    scope.on('session/created', session => { if (allIds.includes(session.id)) audit.createdSessions.push(session.id); });
    scope.on('agent/status', ({ agent, status }) => { if (allIds.includes(agent.id)) audit.agentStatuses.push({ id: agent.id, status }); });
    const workspacePath = join(home, 'native-transcript-workspace');
    await mkdir(workspacePath);
    for (const id of allIds) if (await scope.sessionPersistence.stat(id)) throw new Error('Transcript fixture must start in a fresh isolated home');
    const { workspace } = await scope.workspaceController.create({ path: workspacePath });
    function turn(session, index, userText, assistantText) {
      session.append('turn/start', { turn: index + 1 });
      const user = createUserMessage({ content: [{ type: 'text', text: userText }], source: { kind: 'user' } });
      const assistant = createAssistantMessage({ content: [{ type: 'text', text: assistantText }], source: { provider: 'droy-native-fixture', model: 'native-local' } });
      session.append('user/message', user, { surfaceOp: 'append' });
      session.append('step/start', { turn: index + 1, step: 1 });
      session.append('assistant/message', { turn: index + 1, step: 1, message: assistant, stream: [] }, { surfaceOp: 'append' });
      session.append('step/end', { turn: index + 1, step: 1 });
      session.append('turn/end', { turn: index + 1, reason: { kind: 'completed' } });
      return { userId: user.id, assistantId: assistant.id, userText, assistantText };
    }
    async function persist(session) {
      const handle = await scope.sessionPersistence.create(session.header, { inheritedEventCount: session.inheritedEventCount });
      try { await handle.append(session.snapshotEvents()); await handle.flush(); }
      finally { await handle.close(); }
      await scope.workspaceRegistry.get(workspace.workspaceId).attachSession(session.id);
    }
    const parent = scope.sessions.prepare(ids.parent, { meta: { cwd: workspace.path } });
    const inheritedMessage = turn(parent, 0, 'NATIVE_TRANSCRIPT_INHERITED_USER', 'NATIVE_TRANSCRIPT_INHERITED_ASSISTANT');
    await persist(parent);
    const seed = parent.snapshotEvents(), inheritedEventCount = seed.length;
    const child = scope.sessions.prepare(ids.child, { seed, inheritedEventCount,
      meta: { cwd: workspace.path, isSeeded: true, origin: 'subagent', parentSession: ids.parent, delegationDepth: 1 } });
    const descriptor = child.append('subagent/descriptor', { version: 3, mode: 'one-shot', provider: 'droy-native-fixture', label: 'Synthetic child transcript' });
    const messages = [];
    for (let index = 0; index < turns; index++) messages.push(turn(child, index + 1,
      'NATIVE_TRANSCRIPT_USER_' + String(index).padStart(3, '0'), 'NATIVE_TRANSCRIPT_ASSISTANT_' + String(index).padStart(3, '0')));
    await persist(child);
    const missing = scope.sessions.prepare(ids.missingDescriptor, { seed, inheritedEventCount,
      meta: { cwd: workspace.path, isSeeded: true, origin: 'subagent', parentSession: ids.parent, delegationDepth: 1 } });
    await persist(missing);
    const fork = scope.sessions.prepare(ids.ordinaryFork, { seed, inheritedEventCount,
      meta: { cwd: workspace.path, isSeeded: true, parentSession: ids.parent } });
    turn(fork, 1, 'NATIVE_TRANSCRIPT_ORDINARY_FORK', 'NATIVE_TRANSCRIPT_ORDINARY_FORK_REPLY');
    await persist(fork);
    async function logs() {
      return Promise.all(allIds.map(async id => {
        const stat = await scope.sessionPersistence.stat(id), handle = await scope.sessionPersistence.open(id, 'read');
        try {
          const saved = await handle.read();
          return { id, origin: stat.header.origin ?? null, parentSessionId: stat.header.parentSession ?? null,
            inheritedEventCount: saved.inheritedEventCount, eventCount: saved.events.length,
            ownDescriptorSeqs: saved.events.slice(saved.inheritedEventCount).filter(event => event.type === 'subagent/descriptor').map(event => event.seq),
            digest: createHash('sha256').update(JSON.stringify(saved)).digest('hex'),
            liveSession: !!scope.sessions.get(id), liveAgent: !!scope.agents.get(id) };
        } finally { await handle.close(); }
      }));
    }
    const controller = scope.sessionController, follow = controller.follow, page = controller.page, cancel = controller.cancel;
    const addressed = request => request.address?.kind === 'subagent' ? request.address.childSessionId : request.address?.sessionId;
    const addressCopy = request => ({ ...request.address });
    controller.follow = function(request, signal) {
      const source = follow.call(this, request, signal);
      if (!allIds.includes(addressed(request))) return source;
      return (async function* () {
        audit.activeFollows++; audit.follows.push({ address: addressCopy(request), maxMessages: request.maxMessages });
        try { for await (const frame of source) yield frame; }
        finally { audit.activeFollows--; audit.closedFollows++; }
      })();
    };
    controller.page = async function(request, signal) {
      if (allIds.includes(addressed(request))) audit.pages.push({ address: addressCopy(request), throughSeq: request.throughSeq,
        beforeSeq: request.beforeSeq ?? null, maxMessages: request.maxMessages });
      return page.call(this, request, signal);
    };
    controller.cancel = function(request) {
      if (allIds.includes(request.sessionId)) audit.cancelCalls.push(request.sessionId);
      return cancel.call(this, request);
    };
    scope.effect(() => () => { controller.follow = follow; controller.page = page; controller.cancel = cancel; });
    scope.webServer.register({ kind: 'exact', path: '/droy/native/fixture-transcript/audit', async handler(req, res) {
      if (!requireMobileSession(req, res)) return;
      if (req.method !== 'GET' || req.url !== '/droy/native/fixture-transcript/audit') { res.writeHead(405); res.end(); return; }
      const value = { ...audit, logs: await logs() };
      res.writeHead(200, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' });
      res.end(JSON.stringify({ ok: true, result: value }));
    } });
    await writeFile(join(home, 'native-transcript-fixture-receipt.json'), JSON.stringify({ version: 1, fixture: request.fixture,
      ids, workspaceId: workspace.workspaceId, turns, inheritedEventCount, descriptorSeq: descriptor.seq,
      inheritedMessage, messages, logs: await logs() }));
  });
  return audit;
}
