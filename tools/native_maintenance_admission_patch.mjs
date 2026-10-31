import { createHash } from 'node:crypto';

export const NATIVE_MAINTENANCE_ADMISSION_PATCH_VERSION = 1;

const webExport = 'export { WebServer, WebServer as default,';
const gatewayExport = 'export { TypertGatewayError, TypertGatewayService,';
const webChanges = [
  ['\tupgradedSockets = /* @__PURE__ */ new Set();',
    '\tupgradedSockets = /* @__PURE__ */ new Set();\n\t_nativeMaintenanceRequests = new Set();\n\t_nativeMaintenanceUpgrades = new Map();'],
  ['\t\tconst handle = async (req, res) => {', '\t\tconst dispatch = async (req, res) => {'],
  ['\t\tconst listener = (req, res) => {', String.raw`
		const handle = async (req, res) => {
			const completion = Promise.withResolvers();
			let path;
			try { path = new URL(req.url ?? "/", "http://x").pathname; } catch {}
			const row = Object.freeze({ req, res, method: req.method, path, done: completion.promise });
			this._nativeMaintenanceRequests.add(row);
			try {
				return await this.ctx.waterfall("webserver/request", req, res, row, () => dispatch(req, res));
			} finally {
				this._nativeMaintenanceRequests.delete(row);
				completion.resolve();
			}
		};
		const listener = (req, res) => {`.slice(1)],
  ['\t\t\t\t\tthis.upgradedSockets.delete(socket);',
    '\t\t\t\t\tthis.upgradedSockets.delete(socket);\n\t\t\t\t\tthis._nativeMaintenanceUpgrades.get(socket)?.socketClosed();'],
  ['\t\t\t\tthis.upgradedSockets.add(socket);\n\t\t\t\ttry {\n\t\t\t\t\tPromise.resolve(route.handler(req, socket, head)).catch((error) => {', String.raw`
				this.upgradedSockets.add(socket);
				const completion = Promise.withResolvers();
				const row = Object.freeze({ req, socket, route, path: route.path, done: completion.promise });
				let closed = false, handled = false;
				const finish = () => {
					if (!closed || !handled) return;
					this._nativeMaintenanceUpgrades.delete(socket);
					completion.resolve();
				};
				const record = { row, socketClosed: () => { closed = true; finish(); },
					handlerDone: () => { handled = true; finish(); } };
				this._nativeMaintenanceUpgrades.set(socket, record);
				try {
					Promise.resolve(this.ctx.waterfall("webserver/upgrade", req, socket, head, row,
						() => route.handler(req, socket, head))).finally(record.handlerDone).catch((error) => {`.slice(1)],
  ['\t\t\t\t} catch (error) {\n\t\t\t\t\tthis.ctx.logger.warn(error instanceof Error ? error : new Error(String(error)));\n\t\t\t\t\tsocket.destroy();\n\t\t\t\t}\n\t\t\t});\n\t\t\tawait new Promise',
    '\t\t\t\t} catch (error) {\n\t\t\t\t\trecord.handlerDone();\n\t\t\t\t\tthis.ctx.logger.warn(error instanceof Error ? error : new Error(String(error)));\n\t\t\t\t\tsocket.destroy();\n\t\t\t\t}\n\t\t\t});\n\t\t\tawait new Promise'],
];
const webAddition = String.raw`
/** Inventory belongs to the carrier, including requests/sockets begun before a Droy gate was installed. */
function inspectMaintenanceWebIngress(server) {
  if (!(server instanceof WebServer) || !(server._nativeMaintenanceRequests instanceof Set)
    || !(server._nativeMaintenanceUpgrades instanceof Map) || !(server.upgradedSockets instanceof Set)
    || [...server.upgradedSockets].some(socket => !server._nativeMaintenanceUpgrades.has(socket))
    || [...server._nativeMaintenanceUpgrades].some(([socket, record]) => record.row?.socket !== socket))
    throw new Error("DSH_MAINTENANCE_WEB_CONTRACT");
  return Object.freeze({ version: 1,
    requests: Object.freeze([...server._nativeMaintenanceRequests]),
    upgrades: Object.freeze([...server._nativeMaintenanceUpgrades.values()].map(value => value.row)) });
}
`;

const wireOpening = '\tasync openWireStream(endpoint, payload, uplink, peer, signal, control, uplinkWindow) {';
const gatewayChanges = [
  ['\tremoteEventClients = /* @__PURE__ */ new Map();',
    '\tremoteEventClients = /* @__PURE__ */ new Map();\n\t_nativeMaintenanceWire = new Set();\n\t_nativeMaintenanceMuxRoutes = new Set();'],
  ['\t\t\t\tconst releases = /* @__PURE__ */ new Set();',
    '\t\t\t\tconst releases = /* @__PURE__ */ new Set();\n\t\t\t\tconst maintenanceMuxRoutes = this._nativeMaintenanceMuxRoutes;'],
  ['\t\t\t\t\tyield webCtx.webServer.registerUpgrade(route);',
    '\t\t\t\t\tmaintenanceMuxRoutes.add(route);\n\t\t\t\t\tyield () => { maintenanceMuxRoutes.delete(route); };\n\t\t\t\t\tyield webCtx.webServer.registerUpgrade(route);'],
  [wireOpening, String.raw`
	async openWireStream(endpoint, payload, uplink, peer, signal, control, uplinkWindow) {
		const completion = Promise.withResolvers();
		const descriptor = this.ctx.typert.local.get(endpoint);
		const input = endpoint === "$events" ? "none" : descriptor === void 0 ? "unknown"
			: descriptor.uplink === void 0 ? "none" : "duplex";
		const row = Object.freeze({ endpoint, input, done: completion.promise,
			failure: (code, message) => new RemoteError("gateway/maintenance", message, { endpoint, reason: code }) });
		this._nativeMaintenanceWire.add(row);
		let completed = false;
		const finish = () => {
			if (completed) return;
			completed = true;
			this._nativeMaintenanceWire.delete(row);
			completion.resolve();
		};
		try {
			const source = await this.ctx.waterfall("gateway/wire-stream-open", row,
				() => this.openUnfencedWireStream(endpoint, payload, uplink, peer, signal, control, uplinkWindow));
			const iterator = source[Symbol.asyncIterator]?.() ?? source[Symbol.iterator]();
			const advance = async (method, args) => {
				try {
					let result;
					if (typeof iterator[method] === "function") result = await iterator[method](...args);
					else if (method === "throw") {
						await iterator.return?.();
						throw args[0];
					} else result = { done: true, value: args[0] };
					if (result.done) finish();
					return result;
				} catch (error) { finish(); throw error; }
			};
			return { next: (...args) => advance("next", args), return: (...args) => advance("return", args),
				throw: (...args) => advance("throw", args), [Symbol.asyncIterator]() { return this; } };
		} catch (error) { finish(); throw error; }
	}
	async openUnfencedWireStream(endpoint, payload, uplink, peer, signal, control, uplinkWindow) {`.slice(1)],
];
const gatewayAddition = String.raw`
/** No receiver lookup, Session resume, input cancellation or service call occurs during inventory. */
function inspectMaintenanceRemoteIngress(gateway) {
  if (!(gateway instanceof TypertGatewayService) || !(gateway._nativeMaintenanceWire instanceof Set)
    || !(gateway._nativeMaintenanceMuxRoutes instanceof Set))
    throw new Error("DSH_MAINTENANCE_GATEWAY_CONTRACT");
  return Object.freeze({ version: 1, streams: Object.freeze([...gateway._nativeMaintenanceWire]),
    muxRoutes: Object.freeze([...gateway._nativeMaintenanceMuxRoutes]) });
}
`;

function patch(source, changes, exportAnchor, addition, exported, anchors) {
  if (typeof source !== 'string' || source.includes('_nativeMaintenance')
    || [exportAnchor, ...anchors, ...changes.map(([before]) => before)].some(anchor => source.split(anchor).length !== 2))
    throw new Error('DSH maintenance admission contract changed');
  for (const [before, after] of changes) source = source.replace(before, after);
  return source.replace(exportAnchor, addition + '\n' + exported);
}

export function patchNativeMaintenanceWebServer(source) {
  return patch(source, webChanges, webExport, webAddition,
    'export { inspectMaintenanceWebIngress, WebServer, WebServer as default,', [
      'var WebServer = class extends Service {',
      '\t\t\tconst route = this.match(rawPath);',
      '\t\t\tawait fallback(req, res);',
      '\t\t\t\tthis.server.closeAllConnections();',
      '\t\t\t\tconst socketsClosed = [...this.upgradedSockets, ...this.tlsConnections].map',
    ]);
}

export function patchNativeMaintenanceGateway(source) {
  return patch(source, gatewayChanges, gatewayExport, gatewayAddition,
    'export { inspectMaintenanceRemoteIngress, TypertGatewayError, TypertGatewayService,', [
      'var TypertGatewayService = class extends Service {',
      '\t\t\t\tconst mux = new RemoteStreamMuxServer((endpoint, payload, uplink, peer, control, window) => this.openWireStream(endpoint, payload, uplink, peer, control.signal, control, window),',
      '\t\t\t\t\t\t\tconst admission = webCtx.connection.admitConnection(req);',
      '\t\t\t...remoteRequest(endpoint, payload, signal, peer),',
      '\t\tconst prepared = await this.prepareInvocation(request, control);',
    ]);
}

export function nativeMaintenanceAdmissionPatchFingerprint({ webServer, gateway }) {
  const hash = value => createHash('sha256').update(value).digest('hex');
  return { version: NATIVE_MAINTENANCE_ADMISSION_PATCH_VERSION,
    webServer: { before: hash(webServer), after: hash(patchNativeMaintenanceWebServer(webServer)) },
    gateway: { before: hash(gateway), after: hash(patchNativeMaintenanceGateway(gateway)) } };
}
