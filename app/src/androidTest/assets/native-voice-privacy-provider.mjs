// Test APK only: deterministic local replies and an explicitly isolated seeded-history fixture.
import { appendFile, readFile, writeFile } from 'node:fs/promises';
import { randomUUID } from 'node:crypto';
import { dirname, join } from 'node:path';
import { pathToFileURL } from 'node:url';

export const name = 'droy-native-voice-privacy-fixture';
export const inject = ['llm', 'agents', 'sessionPersistence', 'webServer'];

export async function apply(ctx) {
  const home = process.env.DSH_HOME;
  const fromRuntime = path => pathToFileURL(join(process.env.DROY_RUNTIME_DIR, 'node_modules', path)).href;
  const { LlmAdapter } = await import(fromRuntime('@deepseek-ai/dsh-llm/lib/index.js'));
  const { requireMobileSession } = await import(fromRuntime('@droyagent/mobile/auth.mjs'));
  class VoicePrivacyFixture extends LlmAdapter {
    async resolveModel(provider, id) {
      return { provider, id, name: 'Local voice privacy fixture', inputModalities: ['text'],
        context: { contextWindow: 32768 }, defaultMaxTokens: 1024 };
    }
    async listModels(provider) { return [await this.resolveModel(provider, 'native-local')]; }
    async *stream(options) {
      if (options.provider !== 'droy-native-fixture') throw new Error('Unexpected voice privacy fixture provider');
      const title = options.purpose === 'session-title'
        || String(options.system).includes('Create a concise title')
        || options.messages.some(message => message.content?.some(block => block.type === 'text'
          && block.text.includes('Generate the session title from this JSON array')));
      await appendFile(join(home, 'native-fixture-calls.jsonl'), JSON.stringify({ sessionId: options.sessionId, title }) + '\n');
      const text = title ? 'Voice privacy fixture' : 'Native DSH reply received.';
      yield { type: 'block-start', index: 0, blockType: 'text' };
      yield { type: 'text-delta', index: 0, text };
      yield { type: 'block-end', index: 0, block: { type: 'text', text } };
      yield { type: 'finish', reason: { kind: 'stop' } };
    }
  }
  ctx.llm.registerAdapter(['droy-native-fixture'], new VoicePrivacyFixture());
  ctx.webServer.register({ kind: 'exact', path: '/droy/native/fixture/privacy-seed', async handler(req, res) {
    if (!requireMobileSession(req, res)) return;
    if (req.method !== 'POST') { req.resume(); res.writeHead(405); res.end(); return; }
    req.resume();
    const ownership = JSON.parse(await readFile(join(dirname(home), 'native-workbench-fixture.json'), 'utf8'));
    if (ownership.fixture !== 'native-isolated-runtime-v1' || ownership.phase !== 'running'
      || !/^native-workbench-home-[0-9]+$/.test(ownership.backup)
      || ownership.preferences !== ownership.backup + '-preferences') throw new Error('Voice privacy fixture is not isolated');
    const sessionId = 'privacy-imported-' + randomUUID();
    const created = await ctx.agents.create({ sessionId, seed: [], inheritedEventCount: 0,
      meta: { isSeeded: true, cwd: join(home, 'workspace'), agentPreset: 'standard' },
      agentOptions: { provider: 'droy-native-fixture', model: 'native-local' } });
    try {
      await ctx.sessionPersistence.flush();
      res.writeHead(200, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' });
      res.end(JSON.stringify({ ok: true, result: { sessionId, isSeeded: created.agent.session.header.isSeeded } }));
    } finally { await created.dispose(); }
  } });
  await writeFile(join(home, 'native-fixture-ready.json'), '{"ready":true}');
}
