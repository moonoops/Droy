// Host-only fixture: genuine DSH loop + JSONL + host flock; scripted model and
// HTTP executor are MOCKS. Never connect this fixture to a real provider.
import { createRequire, registerHooks } from 'node:module';
import { pathToFileURL } from 'node:url';
import { createServer } from 'node:http';
import { setTimeout as delay } from 'node:timers/promises';
import { requireMobileSession } from '../../auth.mjs';
const installed = createRequire(new URL('../../../dsh/package.json', import.meta.url));
const localMemory = createRequire(new URL('../../../memory/package.json', import.meta.url));
const hooks = registerHooks({ resolve(specifier, context, next) {
  if (specifier === '@droyagent/memory' || specifier.startsWith('@droyagent/memory/'))
    return { url: pathToFileURL(localMemory.resolve(specifier)).href, shortCircuit: true };
  if (specifier === 'zod' || specifier.startsWith('@deepseek-ai/')) return { url: pathToFileURL(installed.resolve(specifier)).href, shortCircuit: true };
  return next(specifier, context);
} });
const { Context } = await import('@deepseek-ai/cordis');
const { default: LlmRuntime, LlmAdapter, createUserMessage } = await import('@deepseek-ai/dsh-llm');
const { default: SessionStore } = await import('@deepseek-ai/dsh-session');
const { default: Projections } = await import('@deepseek-ai/dsh-session-projection');
const { default: SystemPrompt } = await import('@deepseek-ai/dsh-system-prompt');
const { default: Tools } = await import('@deepseek-ai/dsh-tools');
const { default: Agents } = await import('@deepseek-ai/dsh-agent');
const pluginPolicy = await import('../../plugin-policy.mjs');
const { default: Loop } = await import('@deepseek-ai/dsh-agent-loop');
const { default: UserQuestions } = await import('@deepseek-ai/dsh-user-questions');
const Jsonl = await import('@deepseek-ai/dsh-session-persistence-jsonl');
const Checkpoints = await import('@deepseek-ai/dsh-session-checkpoint-policy');
const mobile = await import('../../plugin.mjs');
const lifecycle = await import('../../lifecycle.mjs');
hooks.deregister();
const [mode, root] = process.argv.slice(2);
let modelCalls = 0;
class MockModel extends LlmAdapter {
  async resolveModel(provider, id) { return { provider, id, name: 'MOCK persisted model', inputModalities: ['text'] }; }
  async *stream() {
    modelCalls++;
    if (mode === 'resume') throw new Error('Unexpected automatic model request');
    if (modelCalls === 1) {
      const args = JSON.stringify({snapshotId:'mock-snapshot',nodeId:'mock-input',text:'中断测试内容'});
      yield {type:'block-start',index:0,blockType:'tool-call'};
      yield {type:'tool-call-delta',index:0,id:'possibly-applied',name:'android_input_text',argumentsDelta:args};
      yield {type:'block-end',index:0,block:{type:'tool-call',id:'possibly-applied',name:'android_input_text',arguments:args}};
      yield {type:'finish',reason:{kind:'tool-calls'}};
    } else {
      yield {type:'block-start',index:0,blockType:'text'};
      yield {type:'text-delta',index:0,text:'MOCK final answer retained'};
      yield {type:'block-end',index:0,block:{type:'text',text:'MOCK final answer retained'}};
      yield {type:'finish',reason:{kind:'stop'}};
    }
  }
}
const ctx = new Context();
await ctx.plugin(LlmRuntime); await ctx.plugin(SessionStore); await ctx.plugin(Projections);
await ctx.plugin(SystemPrompt); await ctx.plugin(Tools, {mode:'native'}); await ctx.plugin(Agents);
await ctx.plugin(Jsonl, {root,compression:'none'}); await ctx.plugin(Loop,{agents:[]}); await ctx.plugin(Checkpoints);
await ctx.plugin(UserQuestions);
const routes = new Map();
const server = createServer((req,res) => {
  if (!requireMobileSession(req,res)) return;
  const route = routes.get(req.url);
  if (!route) {res.writeHead(404);res.end();return;}
  Promise.resolve(route.handler(req,res)).catch(()=>{res.writeHead(500);res.end('fixture failed');});
});
ctx.provide('webServer',{register(route){routes.set(route.path,route);return()=>routes.delete(route.path);}});
ctx.provide('settings',{async mutate(){throw new Error('Outside fixture');}});
ctx.provide('attachments',{async saveImage(){throw new Error('Outside fixture');}});
ctx.provide('appExit', code => {
  void (async()=>{await ctx.fiber.dispose();server.closeAllConnections();await new Promise(r=>server.close(r));process.disconnect();process.exitCode=code;})();
});
await ctx.plugin(pluginPolicy); await ctx.plugin(mobile); await ctx.plugin(lifecycle);
if (!ctx.tools.get('android_input_text') || !routes.has('/droy/shutdown'))
  throw new Error('Persistence fixture requires the mobile tools and shutdown service to be registered');
ctx.on('agent/error', event => process.send({type:'fixture-error',message:String(event.error)}));
ctx.llm.registerAdapter(['mock-persistence'],new MockModel());
const options = {provider:'mock-persistence',model:'scripted'};
const handle = mode==='resume'
  ? await ctx.agents.resume({resumeSessionId:'persistent-contract',agentOptions:options})
  : await ctx.agents.create({sessionId:'persistent-contract',agentOptions:options});
await new Promise(r=>server.listen(0,'127.0.0.1',r));
process.send({type:'ready',port:server.address().port});
if (mode==='resume') {
  await delay(300);
  await ctx.sessions.flush(handle.agent.session);
  const reader=await ctx.sessionPersistence.open('persistent-contract','read');
  const events=(await reader.read()).events;await reader.close();
  process.send({type:'recovered',modelCalls,events});
  ctx.appExit(0);
} else {
  handle.agent.followup(createUserMessage({content:[{type:'text',text:'MOCK apply text once'}],source:{kind:'user'}}));
  await handle.agent.whenIdle();
  process.send({type:'idle',modelCalls});
}
