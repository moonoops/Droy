import { appendFile, readFile, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';

export const name = 'droy-notification-organizer-fixture';
export const inject = ['llm'];

export async function apply(ctx) {
  const { LlmAdapter, resolveRetryPolicy } = await import(pathToFileURL(join(process.env.DROY_RUNTIME_DIR,
    'node_modules/@deepseek-ai/dsh-llm/lib/index.js')).href);
  const home = process.env.DSH_HOME;
  class OrganizerModel extends LlmAdapter {
    providerRetryPolicy() { return resolveRetryPolicy({ mode: 'normal', maxRetries: 0 }, 'notification-organizer-fixture'); }
    async resolveModel(provider, id) {
      return { provider, id, name: 'Local notification fixture', inputModalities: ['text'],
        context: { contextWindow: 32768 }, defaultMaxTokens: 1024 };
    }
    async listModels(provider) { return [await this.resolveModel(provider, 'native-local')]; }
    async *stream(options) {
      const raw = options.messages.filter(message => message.role === 'user').at(-1)?.content
        ?.filter(part => part.type === 'text').map(part => part.text).join('') ?? '';
      let input;
      try { input = JSON.parse(raw); } catch { /* Initial title calls contain ordinary text. */ }
      let text = 'Local notification fixture is ready.';
      if (input?.notification) {
        const title = input.notification.title ?? '';
        let control = {};
        try { control = JSON.parse(await readFile(join(home, 'notification-organizer-control.json'), 'utf8')); }
        catch (error) { if (error.code !== 'ENOENT') throw error; }
        const fail = control.failNext === true || title.includes('Organizer fail');
        if (control.failNext) await writeFile(join(home, 'notification-organizer-control.json'), '{"failNext":false}');
        await appendFile(join(home, 'notification-organizer-calls.jsonl'), JSON.stringify({
          title, key: input.notification.key, userRule: input.userRule, system: options.system,
          failed: fail, atMs: Date.now(),
        }) + '\n');
        if (fail) throw new Error('Controlled local notification failure');
        let decision = { action: 'retain' };
        if (/^(?:Organizer|DroyFixture) clear(?:\b|_)/u.test(title)) decision = { action: 'clear', advertisement: true };
        if (/^(?:Organizer|DroyFixture) collect(?:\b|_)/u.test(title)) decision = { action: 'collect', groupKey: title };
        if (/^(?:Organizer|DroyFixture) notify(?:\b|_)/u.test(title)) decision = { action: 'notify', groupKey: title };
        if (/^Organizer important(?:\b|_)/u.test(title)) decision = { action: 'retain', important: true };
        text = JSON.stringify(decision);
      }
      for (const chunk of [
        { type: 'block-start', index: 0, blockType: 'text' },
        { type: 'text-delta', index: 0, text },
        { type: 'block-end', index: 0, block: { type: 'text', text } },
        { type: 'finish', reason: { kind: 'stop' } },
      ]) { options.signal?.throwIfAborted(); yield chunk; }
    }
  }
  ctx.llm.registerAdapter(['droy-native-fixture'], new OrganizerModel());
  await writeFile(join(home, 'native-fixture-ready.json'), '{"ready":true}');
}
