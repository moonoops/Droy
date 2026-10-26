import { requireMobileSession } from './auth.mjs';
import { resolveMobileLocale } from './locale.mjs';
import { translateBackendText } from './backend-i18n.mjs';

const moduleName = '@droyagent/mobile/notifications';

/** Keep the enablement control on the host so a disabled notification plugin can be enabled here. */
export function installNotificationControl(ctx) {
  ctx.webServer.register({ kind: 'exact', path: '/droy/notify/plugin', async handler(req, res) {
    if (!requireMobileSession(req, res)) return;
    const respond = (status, value) => {
      res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' });
      res.end(JSON.stringify(value));
    };
    if (!['GET', 'POST'].includes(req.method)) return respond(405, { error: 'METHOD_NOT_ALLOWED' });
    const manager = ctx.get('pluginManager');
    if (!manager) return respond(503, { error: 'PLUGIN_MANAGEMENT_UNAVAILABLE' });
    try {
      const rows = (await manager.listPlugins()).filter(row => row.moduleName === moduleName);
      if (rows.length !== 1) return respond(409, { error: 'NOTIFICATION_PLUGIN_NOT_UNIQUE' });
      let change;
      if (req.method === 'POST') {
        let body = '';
        for await (const chunk of req) {
          body += chunk.toString();
          if (body.length > 1024) return respond(413, { error: 'REQUEST_TOO_LARGE' });
        }
        let args;
        try { args = JSON.parse(body); } catch { return respond(400, { error: 'INVALID_JSON' }); }
        if (typeof args.enabled !== 'boolean') return respond(400, { error: 'ENABLED_REQUIRED' });
        change = await manager.setPluginEnabled(rows[0].entryId, args.enabled);
        if (['failed', 'overridden', 'cancelled'].includes(change.application)) return respond(409, {
          error: 'NOTIFICATION_PLUGIN_CHANGE_FAILED', message: translateBackendText('主动通知设置未能生效，请重试或查看插件设置。', resolveMobileLocale(ctx)), application: change.application,
        });
      }
      const row = (await manager.listPlugins()).find(value => value.entryId === rows[0].entryId);
      return respond(200, { enabled: row?.enabled === true, active: ctx.get('droyNotifications')?.active === true,
        ...(change ? { application: change.application, error: change.error, restartRequired: change.application === 'restart-required' } : {}) });
    } catch (error) {
      ctx.logger.warn('DROY_NOTIFICATION_CONTROL_FAILED', error.code);
      return respond(500, { error: 'NOTIFICATION_PLUGIN_CHANGE_FAILED' });
    }
  } });
}
