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
    let current;
    const prepare = operation => {
      if (operation.pending || operation.error || operation.phase === 'exiting') return;
      operation.pending = operation.window.prepare().then(receipt => { operation.receipt = receipt; }, error => {
        operation.error = /^[A-Z_]+$/.test(error.code ?? '') ? error.code : 'COMPANION_MAINTENANCE_FAILED';
      }).finally(() => { operation.pending = null; });
    };
    const view = operation => ({ operationId: operation.operationId, companionId: operation.companionId,
      admissionEpoch: operation.admissionEpoch, hostPid: process.pid, phase: operation.error ? 'failed' : operation.phase === 'exiting' ? 'exiting'
        : operation.window.status().quiet ? 'ready' : 'waiting', ...(operation.error ? { error: operation.error } : {}),
      busy: operation.window.status().admission.busy, unfenced: operation.window.status().admission.unfenced });
    scope.webServer.register({ kind: 'exact', path: companionMaintenancePath, async handler(req, res) {
      if (!requireMobileSession(req, res)) return;
      const send = (status, value) => { res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' }); res.end(JSON.stringify(value)); };
      try {
        if (req.method === 'GET') {
          if (!current) return send(200, { phase: 'idle' });
          prepare(current); return send(200, view(current));
        }
        if (!['POST', 'DELETE'].includes(req.method)) return send(405, { error: 'METHOD_NOT_ALLOWED' });
        let raw = ''; for await (const chunk of req) { raw += chunk; if (Buffer.byteLength(raw) > 1024) fail('COMPANION_INVALID_INPUT'); }
        let input; try { input = JSON.parse(raw); } catch { fail('COMPANION_INVALID_INPUT'); }
        if (!input || typeof input !== 'object' || Array.isArray(input) || Object.keys(input).some(key => !['operationId', 'companionId', 'action'].includes(key))
          || !identity(input.operationId) || !identity(input.companionId)) fail('COMPANION_INVALID_INPUT');
        if (current && (current.operationId !== input.operationId || current.companionId !== input.companionId)) fail('COMPANION_MAINTENANCE_CONFLICT');
        if (req.method === 'DELETE') {
          if (current) { await current.window.release(); current = undefined; }
          return send(200, { phase: 'cancelled' });
        }
        if (input.action === 'shutdown') {
          if (!current || !current.window.status().quiet) fail('COMPANION_MAINTENANCE_BUSY');
          await scope.droyLifecycle.prepareMaintenanceExit(() => current.window.status().quiet);
          current.phase = 'exiting';
          res.once('finish', () => scope.appExit(0)); return send(200, view(current));
        }
        if (input.action !== 'prepare') fail('COMPANION_INVALID_INPUT');
        if (!current) {
          await scope.droyCompanions.store.getProfile(input.companionId);
          const admissionEpoch = randomUUID();
          current = { operationId: input.operationId, companionId: input.companionId, admissionEpoch,
            window: windows.open({ operationId: input.operationId, admissionEpoch }), phase: 'waiting' };
        }
        prepare(current); return send(202, view(current));
      } catch (error) {
        const code = /^[A-Z_]+$/.test(error.code ?? '') ? error.code : 'COMPANION_MAINTENANCE_FAILED';
        send(code.includes('CONFLICT') || code.includes('BUSY') ? 409 : 400, { error: code });
      }
    } });
    scope.provide('droyCompanionMaintenance', { gate, pending: () => !!current });
    scope.effect(() => () => execution.dispose());
  });
}
