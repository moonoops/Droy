// Test APK asset only. This provider never contacts an external model service.
import { appendFile, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';
import { setTimeout as delay } from 'node:timers/promises';

export const name = 'droy-voice-conversation-fixture';
export const inject = ['llm', 'sessionController'];
export const REPLY = 'Public voice fixture received!';

export async function apply(ctx) {
  const { LlmAdapter } = await import(pathToFileURL(join(process.env.DROY_RUNTIME_DIR,
    'node_modules/@deepseek-ai/dsh-llm/lib/index.js')).href);
  const log = join(process.env.DSH_HOME, 'voice-conversation-events.jsonl');
  const record = value => appendFile(log, JSON.stringify(value) + '\n');
  const textOf = content => (content || []).filter(part => part.type === 'text').map(part => part.text).join('');
  class PublicFixtureModel extends LlmAdapter {
    async resolveModel(provider, id) {
      return { provider, id, name: 'Offline Voice acceptance fixture', inputModalities: ['text'],
        context: { contextWindow: 262144 }, defaultMaxTokens: 1024 };
    }
    async listModels(provider) { return [await this.resolveModel(provider, 'public-fixture')]; }
    async *stream(options) {
      if (options.provider !== 'droy-voice-fixture') throw new Error('Unexpected provider in the local Voice fixture');
      const input = options.messages.filter(message => message.role === 'user').map(message => textOf(message.content)).at(-1) || '';
      await record({ event: 'model', purpose: options.purpose, input, reply: REPLY });
      yield { type: 'block-start', index: 0, blockType: 'text' };
      yield { type: 'text-delta', index: 0, text: REPLY };
      await delay(300);
      yield { type: 'block-end', index: 0, block: { type: 'text', text: REPLY } };
      yield { type: 'finish', reason: { kind: 'stop' } };
    }
  }
  ctx.llm.registerAdapter(['droy-voice-fixture'], new PublicFixtureModel());
  const controller = ctx.sessionController;
  const original = controller.prompt;
  const observed = async function(request, ...rest) {
    await record({ event: 'submission', mode: request.mode, text: textOf(request.content), sessionId: request.sessionId });
    return original.call(this, request, ...rest);
  };
  controller.prompt = observed;
  ctx.effect(() => () => { if (controller.prompt === observed) controller.prompt = original; });
  await writeFile(join(process.env.DSH_HOME, 'voice-conversation-ready.json'), JSON.stringify({ provider: 'droy-voice-fixture' }));
}
