import { randomUUID } from 'node:crypto';
import { createHostMaintenanceExecution } from './maintenance-host-execution.mjs';
import * as Web from '@deepseek-ai/dsh-host-webserver';
import * as Gateway from '@deepseek-ai/dsh-api-gateway';
import * as Schedule from '@deepseek-ai/dsh-schedule';
import { createMaintenanceAdmission, installMaintenanceAdmission } from './maintenance-admission.mjs';
import { createCompanionMaintenanceLease } from './maintenance-runtime.mjs';
import { createCompanionMaintenanceWindow } from './maintenance-window.mjs';
import { requireMobileSession } from '../auth.mjs';

export const companionMaintenancePath = '/droy/native/maintenance/companion';
const fail = code => { throw Object.assign(new Error(code), { code }); };
const identity = value => typeof value === 'string' && /^[A-Za-z0-9][A-Za-z0-9_-]{0,79}$/.test(value);
const operationIdentity = value => typeof value === 'string' && /^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/.test(value);

/** The native coordinator may only finish a purge already authorized and
 * journaled by the workbench. A live profile is not required for retained data. */
export function createCompanionMaintenanceHandler({ runtime, windows, lifecycle, exit }) {
  let current;
  const purgeAuthorization = async (memorySpaceId, operationId) => {
    const space = await runtime.memorySpaces.get(memorySpaceId);
    if (!space || space.state !== 'purging' || space.purgeOperation !== operationId || !identity(space.companionId))
      fail('COMPANION_MEMORY_PURGE_NOT_AUTHORIZED');
    return space;
  };
  const prepare = operation => {
    if (operation.pending || operation.error || operation.phase === 'exiting') return;
    operation.pending = operation.window.prepare().then(receipt => { operation.receipt = receipt; }, error => {
      operation.error = /^[A-Z_]+$/.test(error.code ?? '') ? error.code : 'COMPANION_MAINTENANCE_FAILED';
    }).finally(() => { operation.pending = null; });
  };
  const view = operation => ({ operationId: operation.operationId, companionId: operation.companionId,
    ...(operation.memorySpaceId ? { memorySpaceId: operation.memorySpaceId } : {}),
    admissionEpoch: operation.admissionEpoch, hostPid: process.pid, phase: operation.error ? 'failed' : operation.phase === 'exiting' ? 'exiting'
      : operation.window.status().quiet ? 'ready' : 'waiting', ...(operation.error ? { error: operation.error } : {}),
    busy: operation.window.status().admission.busy, unfenced: operation.window.status().admission.unfenced });
  const handler = async (req, res) => {
    if (!requireMobileSession(req, res)) return;
    const send = (status, value) => { res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' }); res.end(JSON.stringify(value)); };
    try {
      if (req.method === 'GET') {
        if (!current) return send(200, { phase: 'idle' });
        if (current.memorySpaceId) await purgeAuthorization(current.memorySpaceId, current.operationId);
        prepare(current); return send(200, view(current));
      }
      if (!['POST', 'DELETE'].includes(req.method)) return send(405, { error: 'METHOD_NOT_ALLOWED' });
      let raw = ''; for await (const chunk of req) { raw += chunk; if (Buffer.byteLength(raw) > 1024) fail('COMPANION_INVALID_INPUT'); }
      let input; try { input = JSON.parse(raw); } catch { fail('COMPANION_INVALID_INPUT'); }
      if (!input || typeof input !== 'object' || Array.isArray(input) || Object.keys(input).some(key => !['operationId', 'companionId', 'memorySpaceId', 'action'].includes(key))
        || !operationIdentity(input.operationId) || input.companionId !== undefined && !identity(input.companionId)
        || input.memorySpaceId !== undefined && !identity(input.memorySpaceId)) fail('COMPANION_INVALID_INPUT');
      const preparingPurge = req.method === 'POST' && input.action === 'prepare-memory-purge';
      if (preparingPurge && !input.memorySpaceId || !preparingPurge && !input.companionId) fail('COMPANION_INVALID_INPUT');
      if (current && (current.operationId !== input.operationId || current.memorySpaceId !== input.memorySpaceId
        || input.companionId !== undefined && current.companionId !== input.companionId)) fail('COMPANION_MAINTENANCE_CONFLICT');
      if (req.method === 'DELETE') {
        if (current) { await current.window.release(); current = undefined; }
        return send(200, { phase: 'cancelled' });
      }
      if (input.action === 'shutdown') {
        if (!current || !current.window.status().quiet) fail('COMPANION_MAINTENANCE_BUSY');
        if (current.memorySpaceId) await purgeAuthorization(current.memorySpaceId, current.operationId);
        await lifecycle.prepareMaintenanceExit(() => current.window.status().quiet);
        current.phase = 'exiting';
        res.once('finish', () => exit(0)); return send(200, view(current));
      }
      if (!preparingPurge && (input.action !== 'prepare' || input.memorySpaceId !== undefined)) fail('COMPANION_INVALID_INPUT');
      let companionId = input.companionId;
      if (preparingPurge) {
        const space = await purgeAuthorization(input.memorySpaceId, input.operationId);
        if (companionId !== undefined && companionId !== space.companionId) fail('COMPANION_MAINTENANCE_CONFLICT');
        companionId = space.companionId;
      }
      if (current && (current.operationId !== input.operationId || current.memorySpaceId !== input.memorySpaceId
        || current.companionId !== companionId)) fail('COMPANION_MAINTENANCE_CONFLICT');
      if (!current) {
        if (!preparingPurge) await runtime.store.getProfile(companionId);
        const admissionEpoch = randomUUID();
        current = { operationId: input.operationId, companionId, ...(preparingPurge ? { memorySpaceId: input.memorySpaceId } : {}), admissionEpoch,
          window: windows.open({ operationId: input.operationId, admissionEpoch }), phase: 'waiting' };
      }
      prepare(current); return send(202, view(current));
    } catch (error) {
      const code = /^[A-Z_]+$/.test(error.code ?? '') ? error.code : 'COMPANION_MAINTENANCE_FAILED';
      send(code.includes('CONFLICT') || code.includes('BUSY') ? 409 : 400, { error: code });
    }
  };
  return { handler, pending: () => !!current };
}

export function installCompanionMaintenanceHost(ctx) {
  return ctx.inject(['droyCompanions', 'droyNativeUserTasks', 'droyMemoryHost', 'schedule', 'typertGateway', 'sessions', 'sessionPersistence', 'droyLifecycle', 'appExit'], scope => {
    const execution = createHostMaintenanceExecution(scope);
    const gate = createMaintenanceAdmission({ inspectExecution: () => execution.inspect() });
    installMaintenanceAdmission(scope, { gate, inspectWebIngress: Web.inspectMaintenanceWebIngress, inspectRemoteIngress: Gateway.inspectMaintenanceRemoteIngress });
    const windows = createCompanionMaintenanceWindow({ gate, agents: () => scope.agents.list(),
      producers: operationId => [createCompanionMaintenanceLease(scope.droyCompanions, { operationId }),
        Schedule.createScheduleMaintenanceLease(scope.schedule, { operationId }), scope.droyMemoryHost.maintenanceLease({ operationId })],
      flushSessions: () => Promise.all(scope.sessions.list().map(session => scope.sessions.flush(session))),
      flushPersistence: () => scope.sessionPersistence.flush() });
    const controller = createCompanionMaintenanceHandler({ runtime: scope.droyCompanions, windows,
      lifecycle: scope.droyLifecycle, exit: code => scope.appExit(code) });
    scope.webServer.register({ kind: 'exact', path: companionMaintenancePath, handler: controller.handler });
    scope.provide('droyCompanionMaintenance', { gate, pending: controller.pending });
    scope.effect(() => () => execution.dispose());
  });
}
