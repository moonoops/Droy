import { mkdir } from 'node:fs/promises';
import { join, resolve } from 'node:path';
import { resolveMobileLocale } from './locale.mjs';
import { translateBackendText } from './backend-i18n.mjs';

export const name = 'droy-initialize';
export const inject = ['workspaceRegistry'];

export async function apply(ctx) {
  if (!process.env.DSH_HOME) throw new Error('DROY_HOME_REQUIRED');
  const skillDirectory = resolve(process.env.DSH_HOME, '..', 'droy-skills');
  await mkdir(skillDirectory, { recursive: true });
  // Official initialization preserves existing history/workspaces and the
  // user's deletion marker. It never replays or creates a conversation.
  let created = false;
  const workspace = await ctx.workspaceRegistry.initializeDefault(async () => {
    created = true;
    return join(process.env.DSH_HOME, 'workspace');
  });
  if (created && workspace) await workspace.setTitle(translateBackendText('默认空间', resolveMobileLocale(ctx)));
  ctx.provide('droyInitialization', { ready: true, skillDirectory });
}
