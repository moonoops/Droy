import { TYPERT } from '@deepseek-ai/dsh-api-session-controller/typert';

const descriptor = TYPERT.invocations.find(method => method.namespace === 'session' && method.method === 'selectModel');
if (!descriptor) throw new Error('DSH Session model selection contract changed');
const parameter = descriptor.parameters[0].wire;
const input = descriptor.input({ strict: true }).create();

function invalid() {
  return Object.assign(new Error('Invalid Session model selection'), { code: 'gateway/bad-request' });
}

/** The authenticated native API owns authorization; this seam never changes its deployment default. */
export function selectNativeSessionModel(ctx, request) {
  let parsed;
  try { parsed = input.parse({ [parameter]: request })[parameter]; }
  catch (error) {
    if (error?.name === 'ZodError') throw invalid();
    throw error;
  }
  if (!parsed || !parsed.sessionId.trim() || parsed.sessionId.length > 1024) throw invalid();
  const companions = ctx.get?.('droyCompanions') ?? ctx.droyCompanions;
  if (companions?.ownsSession?.(parsed.sessionId))
    throw Object.assign(new Error('Companion-owned Session'), { code: 'COMPANION_SESSION_MANAGED' });
  const controller = ctx.get?.('sessionController') ?? ctx.sessionController;
  if (!controller) throw Object.assign(new Error('Session service is not ready'), { code: 'session/not-ready' });
  // Added only after strict public input parsing: callers cannot turn this into
  // a global preference write or pass this internal flag through the HTTP API.
  return controller.selectModel({ ...parsed, persistDefault: false });
}
