import test from 'node:test';
import assert from 'node:assert/strict';
import { toolDefinitions, capabilityCatalog } from '../catalog.mjs';
import { protectHardwareDisclosure } from '../hardware-disclosure.mjs';
import { createBridge } from '../bridge.mjs';

test('device communication permissions distinguish raw operations and local cleanup', () => {
  const tools = Object.fromEntries(toolDefinitions.map(tool => [tool.action, tool]));
  assert.ok(tools.nfc.operationPermissions.transceive.includes('nfcWrite'));
  assert.ok(tools.nfc.operationPermissions.format.includes('nfcWrite'));
  assert.deepEqual(tools.bluetooth.operationPermissions.scan, ['bluetoothRead']);
  for (const [action, operation] of [['nfc', 'close'], ['bluetooth', 'disconnect'], ['wifi', 'disconnect'], ['nfc_emulation', 'disable']])
    assert.deepEqual(tools[action].operationPermissions[operation], []);
  assert.equal(tools.wifi.parameters.passphrase, undefined);
  assert.equal(tools.nfc.parameters.key, undefined);
  assert.equal(tools.wifi.parameters.credentialRef.type, 'string');
  assert.ok(capabilityCatalog.groups.some(group => group.preference === 'allowBodySensors'));
});

test('protected device observations cannot escape a denied processing route', async () => {
  let request;
  const route = {};
  const privacy = { status: async () => ({active: true}), routeForAgent: async () => route,
    beforeDisclosure: async value => { request = value; throw new Error('PRIVACY_ENDPOINT_NOT_AUTHORIZED'); } };
  const exec = { agent: {session: {id: 'synthetic-session'}}, callId: 'synthetic-call', signal: new AbortController().signal };
  await assert.rejects(protectHardwareDisclosure({get: () => privacy}, 'location', {operation: 'current'}, exec), /PRIVACY_ENDPOINT_NOT_AUTHORIZED/);
  assert.equal(request.route, route);
  assert.equal(request.sessionId, 'synthetic-session');
  assert.ok(request.categories.includes('location'));
  assert.equal(request.sourceRefs.some(ref => ref.includes('latitude')), false);
});

test('ordinary device queries do not activate the private memory host', async () => {
  const privacy = { status: async () => ({active: false}), routeForAgent: () => assert.fail('inactive host must not be activated') };
  await protectHardwareDisclosure({get: () => privacy}, 'sensor', {operation: 'sample'}, {});
});

test('hardware status observations require the same protected disclosure route', async () => {
  for (const action of ['wifi', 'bluetooth', 'cellular']) {
    let disclosures = 0;
    const route = {};
    const privacy = { status: async () => ({active: true}), routeForAgent: async () => route,
      beforeDisclosure: async request => {
        disclosures++;
        assert.equal(request.route, route);
        assert.equal(request.sessionId, 'synthetic-status-session');
        assert.ok(request.categories.includes('location'));
        throw new Error('PRIVACY_ENDPOINT_NOT_AUTHORIZED');
      } };
    const exec = { agent: {session: {id: 'synthetic-status-session'}}, callId: 'synthetic-status-call',
      signal: new AbortController().signal };
    await assert.rejects(protectHardwareDisclosure({get: () => privacy}, action, {operation: 'status'}, exec),
      /PRIVACY_ENDPOINT_NOT_AUTHORIZED/);
    assert.equal(disclosures, 1);
  }
});

test('waiting for a tag has enough native budget and preserves trusted call identity', async () => {
  let body;
  const bridge = createBridge({port: 12345, token: 'x'.repeat(32), fetchImpl: async (_url, init) => {
    body = JSON.parse(init.body);
    return {ok: true, json: async () => ({ok: true, result: {status: 'tag_detected'}})};
  }});
  await bridge.call('nfc', {operation: 'scan', timeoutMs: 30000}, {agent: {id: 'trusted-agent'}, callId: 'trusted-call', signal: new AbortController().signal});
  assert.equal(body.budgetMs, 35000);
  assert.equal(body.taskId, 'trusted-agent');
});
