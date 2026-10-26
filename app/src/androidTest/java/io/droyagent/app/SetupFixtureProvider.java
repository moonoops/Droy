package io.droyagent.app;

/** A test-only DSH adapter. Its replies and tool calls never leave the emulator. */
final class SetupFixtureProvider {
    static final String PROVIDER = "droy-setup-fixture";
    static final String MODEL = "setup-local";
    static final String CONTEXT_CANARY = "SETUP_PRIVATE_CONTEXT_MUST_NOT_REACH_THE_MODEL";

    private SetupFixtureProvider() {}

    static String source() {
        return """
            import { appendFile, writeFile } from 'node:fs/promises';
            import { join } from 'node:path';
            import { pathToFileURL } from 'node:url';
            export const name = 'droy-setup-local-fixture';
            export const inject = ['llm', 'systemPrompt'];
            export async function apply(ctx) {
              const { LlmAdapter } = await import(pathToFileURL(join(process.env.DROY_RUNTIME_DIR,
                'node_modules/@deepseek-ai/dsh-llm/lib/index.js')).href);
              const log = join(process.env.DSH_HOME, 'setup-model-events.jsonl');
              class SetupModel extends LlmAdapter {
                async resolveModel(provider, id) {
                  return { provider, id, name: 'Local installation fixture', inputModalities: ['text'],
                    context: { contextWindow: 32768 }, defaultMaxTokens: 1024 };
                }
                async listModels(provider) { return [await this.resolveModel(provider, 'setup-local')]; }
                async *stream(options) {
                  if (options.provider !== 'droy-setup-fixture') throw Error('Unexpected fixture provider');
                  await appendFile(log, JSON.stringify({ system: options.system, messages: options.messages,
                    tools: options.tools?.map(tool => tool.name) || [] }) + '\\n');
                  if (options.tools?.length) {
                    const text = options.messages.at(-1).content.filter(part => part.type === 'text').map(part => part.text).join('');
                    const { value } = JSON.parse(text);
                    yield { type: 'block-start', index: 0, blockType: 'tool-call', id: 'setup-echo-call', name: 'droy_setup_echo' };
                    yield { type: 'tool-call-delta', index: 0, id: 'setup-echo-call', name: 'droy_setup_echo',
                      argumentsDelta: JSON.stringify({ value }) };
                    yield { type: 'block-end', index: 0, block: { type: 'tool-call', id: 'setup-echo-call',
                      name: 'droy_setup_echo', arguments: JSON.stringify({ value }) } };
                    yield { type: 'finish', reason: { kind: 'tool-calls' } };
                  } else {
                    const text = '你好，Droy 已连接。';
                    yield { type: 'block-start', index: 0, blockType: 'text' };
                    yield { type: 'text-delta', index: 0, text };
                    yield { type: 'block-end', index: 0, block: { type: 'text', text } };
                    yield { type: 'finish', reason: { kind: 'stop' } };
                  }
                }
              }
              ctx.llm.registerAdapter(['droy-setup-fixture'], new SetupModel());
              ctx.systemPrompt.context({ name: 'setup-private-context-canary', order: 1000,
                required: true, text: 'SETUP_PRIVATE_CONTEXT_MUST_NOT_REACH_THE_MODEL' });
              await writeFile(join(process.env.DSH_HOME, 'setup-fixture-ready.json'), '{"ready":true}');
            }
            """;
    }
}
