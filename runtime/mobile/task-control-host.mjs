import { join } from 'node:path';
import { TaskControlStore } from './task-control-store.mjs';
import { createTaskControl } from './task-control.mjs';
import { createTaskControlEngine } from './task-control-engine.mjs';
import { resolveMobileLocale } from './locale.mjs';

export function installTaskControl(ctx, bridge, progress) {
  // Production has its private home before profile startup. Restore its fences
  // before optional companion initialization or schedules can materialize agents.
  const home = process.env.DSH_HOME;
  const required = ['sessionPersistence', 'sessions', ...(home ? [] : ['droyInitialization'])];
  return ctx.inject(required, scope => {
    const store = new TaskControlStore(join(home ?? process.env.DSH_HOME, 'droy-task-control'));
    let control;
    const engine = createTaskControlEngine(scope, { held: id => control?.held(id) ?? false,
      retainInbox: id => control?.preserveInbox(id) ?? false,
      onWake: (agent, message) => control?.onWake(agent, message), onCreated: agent => control?.onCreated(agent) });
    control = createTaskControl({ store, engine, native: (input, signal) => bridge.taskControl(input, signal),
      isManaged: id => {
        const companions = scope.get('droyCompanions');
        if (!companions) throw Object.assign(new Error('TASK_CONTROL_UNAVAILABLE'), { code: 'TASK_CONTROL_UNAVAILABLE' });
        return companions.ownsSession(id);
      }, getLocale: () => resolveMobileLocale(scope),
      onChange: (taskId, state) => progress.control(taskId, state), onBind: (taskId, state) => progress.bind(taskId, state),
      reportError: code => scope.logger.warn(code) });
    // Recovery publishes the fence before the service is made available to callers.
    control.recover();
    scope.provide('droyTaskControl', control);
    for (const agent of scope.get('agents').list()) control.onCreated(agent);
    scope.effect(() => async () => { await control.dispose(); store.close(); });
  });
}
