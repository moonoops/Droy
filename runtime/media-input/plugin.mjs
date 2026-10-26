import { requireMobileSession } from '@droyagent/mobile/auth';
import { createMediaHandler, MEDIA_ROUTE } from './http.mjs';

export const name = 'droy-media-input';
export const inject = ['webServer'];

export function apply(ctx) {
  ctx.webServer.register({
    kind: 'prefix', path: MEDIA_ROUTE,
    handler: createMediaHandler({ root: process.env.DROY_MEDIA_INPUT_DIR, authorize: requireMobileSession }),
  });
}
