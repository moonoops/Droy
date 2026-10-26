import { defineTool } from '@deepseek-ai/dsh-tools';
import { estimateMessage } from '@deepseek-ai/dsh-token-meter/estimate';
import { createMemoryService, createMemoryHandler, createMemoryArchiveHandler, memoryToolDefinitions, resolveMemoryLocale, confirmMemoryForget, installMemoryToolLocale, MEMORY_ROUTE, MEMORY_ARCHIVE_ROUTE } from './adapter.mjs';

export const name = 'droy-personal-memory';
export const inject = ['tools', 'webServer', 'droyMemoryHost', 'droyPrivacy', 'userQuestions', 'settings'];

export function apply(ctx) {
  const host = ctx.droyMemoryHost;
  const service = createMemoryService({ host, privacy: ctx.droyPrivacy,
    estimateTokens: text => estimateMessage({ role: 'user', content: [{ type: 'text', text }] }),
    confirmForget: (record, exec) => confirmMemoryForget(ctx, record, exec) });
  ctx.provide('droyMemory', service);
  ctx.on('agent/pre-step', (payload, next) => { service.beginTurn(payload.agent, payload.turn); return next(); });
  for (const definition of memoryToolDefinitions(service, resolveMemoryLocale(ctx))) ctx.tools.register(defineTool(definition));
  installMemoryToolLocale(ctx);
  ctx.webServer.register({ kind: 'exact', path: MEMORY_ROUTE,
    handler: createMemoryHandler({ host, service, reportError: code => ctx.logger.warn(code) }) });
  ctx.webServer.register({ kind: 'prefix', path: `${MEMORY_ARCHIVE_ROUTE}/`,
    handler: createMemoryArchiveHandler({ host, service, reportError: code => ctx.logger.warn(code) }) });
  ctx.effect(() => () => service.close());
}
