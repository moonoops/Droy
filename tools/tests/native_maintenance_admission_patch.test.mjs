import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { patchNativeMaintenanceWebServer, patchNativeMaintenanceGateway,
  nativeMaintenanceAdmissionPatchFingerprint } from '../native_maintenance_admission_patch.mjs';

const [webServer, gateway] = await Promise.all(['dsh-host-webserver', 'dsh-api-gateway'].map(name =>
  readFile(new URL(`../../runtime/dsh/node_modules/@deepseek-ai/${name}/lib/index.js`, import.meta.url), 'utf8')));
test('web hook inventories actual handler completions and every upgraded socket without a response-finish proxy', () => {
  const output = patchNativeMaintenanceWebServer(webServer);
  assert.match(output, /export \{ inspectMaintenanceWebIngress, WebServer,/u);
  assert.match(output, /finally \{\s*this\._nativeMaintenanceRequests\.delete\(row\);\s*completion\.resolve\(\);/u);
  assert.match(output, /waterfall\("webserver\/request", req, res, row, \(\) => dispatch/u);
  assert.match(output, /waterfall\("webserver\/upgrade", req, socket, head, row,/u);
  assert.equal(output.includes("once('finish'"), false);
  assert.equal(output.slice(output.indexOf('\t/** Longest-prefix-wins'), output.indexOf('/** Inventory belongs')),
    webServer.slice(webServer.indexOf('\t/** Longest-prefix-wins'), webServer.indexOf('export { WebServer')) + '\n');
});
test('gateway hook surrounds only wire opens before receiver lookup and leaves all in-process invocation code unchanged', () => {
  const output = patchNativeMaintenanceGateway(gateway);
  const begin = '\tasync openWireStream(', end = '\tasync *openRemoteEvents(';
  const originalBody = gateway.slice(gateway.indexOf(begin), gateway.indexOf(end)).replace('openWireStream(', 'openUnfencedWireStream(');
  assert.equal(output.slice(output.indexOf('\tasync openUnfencedWireStream('), output.indexOf(end)), originalBody);
  assert.equal(output.slice(output.indexOf('\tasync invoke(request)'), output.indexOf(begin)),
    gateway.slice(gateway.indexOf('\tasync invoke(request)'), gateway.indexOf(begin)));
  assert.equal(output.slice(output.indexOf(end), output.indexOf('/** No receiver lookup')),
    gateway.slice(gateway.indexOf(end), gateway.indexOf('export { TypertGatewayError')) + '\n');
  assert.match(output, /gateway\/wire-stream-open/u);
  assert.match(output, /descriptor\.uplink === void 0 \? "none" : "duplex"/u);
});
test('fingerprints bind both locked sources and deterministic outputs', () => {
  const input = { webServer, gateway }, value = nativeMaintenanceAdmissionPatchFingerprint(input);
  assert.equal(value.version, 1); assert.deepEqual(nativeMaintenanceAdmissionPatchFingerprint(input), value);
  for (const row of [value.webServer, value.gateway]) {
    assert.match(row.before, /^[a-f0-9]{64}$/u); assert.match(row.after, /^[a-f0-9]{64}$/u); assert.notEqual(row.before, row.after);
  }
});
test('web patch rejects drift, ambiguity and reapplication at carrier lifetime boundaries', () => {
  for (const source of [webServer + webServer, patchNativeMaintenanceWebServer(webServer),
    webServer.replace('this.upgradedSockets.delete(socket);', 'this.upgradedSockets.clear();'),
    webServer.replace('await fallback(req, res);', 'fallback(req, res);'),
    webServer.replace('Promise.resolve(route.handler(req, socket, head))', 'Promise.resolve(other(req, socket, head))'),
    webServer.replace('this.server.closeAllConnections();', 'void 0;')])
    assert.throws(() => patchNativeMaintenanceWebServer(source), /contract changed/u);
});
test('gateway patch rejects drift at the physical mux, authentication, lookup and original stream boundaries', () => {
  for (const source of [gateway + gateway, patchNativeMaintenanceGateway(gateway),
    gateway.replace('this.openWireStream(endpoint, payload, uplink, peer, control.signal, control, window)', 'this.other(endpoint, payload, uplink, peer, control.signal, control, window)'),
    gateway.replace('webCtx.connection.admitConnection(req)', 'webCtx.connection.other(req)'),
    gateway.replace('yield webCtx.webServer.registerUpgrade(route);', 'yield webCtx.webServer.registerUpgrade(other);'),
    gateway.replace('...remoteRequest(endpoint, payload, signal, peer),', '...otherRequest(endpoint, payload, signal, peer),'),
    gateway.replace('const prepared = await this.prepareInvocation(request, control);', 'const prepared = await this.other(request, control);')])
    assert.throws(() => patchNativeMaintenanceGateway(source), /contract changed/u);
});
