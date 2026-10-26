import WebSocket from 'ws';
import { defineTool } from '@deepseek-ai/dsh-tools';
import { requireMobileSession } from '@droyagent/mobile/auth';
import { createBridge } from '@droyagent/mobile/bridge';
import { createConfigStore } from './config.mjs';
import { createRealtimeManager } from './realtime.mjs';
import { createVoiceHandler, createVoiceService, createVoiceOutputState, VOICE_ROUTE } from './http.mjs';
import { createVoiceTool } from './voice-tools.mjs';
import { resolveMobileLocale } from '../mobile/locale.mjs';

export const name = 'droy-voice';
export const inject = ['webServer', 'credentials', 'tools'];

export function apply(ctx) {
  const bridge = createBridge();
  const native = (payload, signal) => bridge.voice(payload, signal);
  const store = createConfigStore({ home: process.env.DSH_HOME, credentials: ctx.credentials });
  const realtime = createRealtimeManager({ native, getLocale: () => resolveMobileLocale(ctx), connect: (url, headers) => new WebSocket(url, { headers, maxPayload: 4 * 1024 * 1024 }) });
  const outputState = createVoiceOutputState({ getPrivacy: () => ctx.get('droyPrivacy'),
    nativeStatus: () => bridge.memory({ operation: 'status' }),
    protectionRequired: process.env.DROY_MEMORY_PROTECTION_REQUIRED === '1' });
  const service = createVoiceService({ store, native, realtime, getLocale: () => resolveMobileLocale(ctx), outputState });
  ctx.inject(['droyPrivacy'], child => {
    const unsubscribe = child.droyPrivacy.onInvalidated(notice => {
      void service.speech.invalidate(notice).catch(error => ctx.logger.warn('DROY_VOICE_PRIVACY_STOP_FAILED', error.code));
    });
    child.effect(() => () => {
      unsubscribe();
      void service.speech.invalidate().catch(error => ctx.logger.warn('DROY_VOICE_PRIVACY_STOP_FAILED', error.code));
    });
  });
  ctx.provide('droyVoice', service);
  ctx.webServer.register({ kind: 'exact', path: VOICE_ROUTE,
    handler: createVoiceHandler({ authorize: requireMobileSession, service,
      reportError: code => ctx.logger.warn('DROY_VOICE_OPERATION_FAILED', code) }) });
  ctx.tools.register(defineTool(createVoiceTool(service)));
  void native({ operation: 'wake.resume' }).catch(error => ctx.logger.warn('DROY_VOICE_WAKE_RESUME_FAILED', error.code));
  ctx.effect(() => () => service.dispose());
}
