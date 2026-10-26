import test from 'node:test';
import assert from 'node:assert/strict';
import { capabilityCatalog, toolDefinitions, allowedToolNames } from '../catalog.mjs';
import { defineTool } from '../../dsh/node_modules/@deepseek-ai/dsh-tools/lib/index.js';

test('Android Use catalogue compiles against the pinned engine and scopes only its own tools', () => {
  for (const tool of toolDefinitions) {
    assert.ok(defineTool({ name:tool.name,description:tool.description,parameters:tool.parameters,
      output:{schema:{type:'json'},render:()=>[]},execute:async()=>({}) }));
    assert.ok(tool.permissions.every(id => capabilityCatalog.groups.some(group => group.id===id)));
    assert.equal(allowedToolNames.has(tool.name),true);
  }
  assert.equal(allowedToolNames.size,toolDefinitions.length);
  for (const name of ['shell','skill','mcp__outside__execute','android_arbitrary','droy_compute_shell']) assert.equal(allowedToolNames.has(name),false);
});

test('direct queries never require screen access and every write has distinct authorization', () => {
  const actions = Object.fromEntries(toolDefinitions.map(tool => [tool.action,tool]));
  for (const action of ['system_info','find_apps','calendar_query','contacts_query','notifications_query','usage_query','file_read','compute_run',
    'app_info','app_exit_info','network_usage','privacy_usage','battery_usage']) {
    assert.equal(actions[action].screen,false);
    assert.ok(!actions[action].permissions.includes('observe'));
    assert.ok(actions[action].permissions.length>0);
  }
  assert.deepEqual(actions.settings_set.permissions,['settingsRead','settingsWrite']);
  assert.deepEqual(actions.file_export.permissions,['files','fileExport']);
  assert.deepEqual(actions.browser_click.permissions,['browser','browserControl']);
});

test('process memory tools separate target read, write and local cleanup permissions', () => {
  const actions = Object.fromEntries(toolDefinitions.map(tool => [tool.action, tool]));
  assert.deepEqual(actions.process_memory.operationPermissions.open_read_only, ['apps', 'memoryRead']);
  assert.deepEqual(actions.process_memory.operationPermissions.open_read_write, ['apps', 'memoryRead', 'memoryWrite']);
  assert.deepEqual(actions.process_memory.operationPermissions.close, []);
  assert.deepEqual(actions.process_memory.operationPermissions.status, []);
  for (const action of ['memory_scan', 'memory_read']) {
    assert.deepEqual(actions[action].permissions, ['apps', 'memoryRead']);
    assert.equal(actions[action].screen, false);
  }
  assert.deepEqual(actions.memory_write.permissions, ['apps', 'memoryRead', 'memoryWrite']);
  assert.equal(actions.memory_write.parameters.readId.required, true);
  assert.equal(actions.memory_write.parameters.value.type, 'string');
  for (const action of ['process_memory', 'memory_scan', 'memory_read', 'memory_write']) {
    for (const unsupported of ['command', 'shell', 'taskId', 'callId', 'uid'])
      assert.equal(actions[action].parameters[unsupported], undefined);
  }
});

test('diagnostics and semantic controls expose targeted inputs and distinct product permissions', () => {
  const actions = Object.fromEntries(toolDefinitions.map(tool => [tool.action, tool]));
  assert.deepEqual(actions.privacy_usage.permissions, ['apps', 'privacy']);
  assert.deepEqual(actions.battery_usage.permissions, ['apps', 'diagnostics']);
  assert.deepEqual(actions.network_usage.permissions, ['apps', 'usage']);
  assert.deepEqual(actions.usage_query.parameters.mode.enum, ['summary', 'events']);
  assert.equal(actions.node_action.parameters.snapshotId.required, true);
  for (const action of ['node_action', 'global_action', 'open_settings']) {
    assert.equal(actions[action].screen, true);
    assert.deepEqual(actions[action].permissions, ['observe', 'control']);
  }
  for (const action of ['privacy_usage', 'battery_usage']) {
    for (const unsupported of ['command', 'shell', 'reset', 'channel'])
      assert.equal(actions[action].parameters[unsupported], undefined);
  }
});

test('tool schemas expose automatic selection and preserve action evidence', () => {
  const actions=Object.fromEntries(toolDefinitions.map(tool=>[tool.action,tool]));
  for(const action of ['observe','input_text'])assert.deepEqual(actions[action].parameters.channel.enum,['auto','accessibility']);
  for(const action of ['tap','swipe','gesture','input_text'])assert.equal(actions[action].parameters.snapshotId.required,true);
  assert.deepEqual(actions.gesture.parameters.channel.enum,['auto','accessibility']);
  assert.deepEqual(actions.swipe.parameters.motion.enum,['precise','natural']);
  assert.equal(actions.tap.parameters.navigation.type,'boolean');
  assert.equal(actions.tap.parameters.navigation.required,undefined);
  assert.equal(actions.input_text.parameters.navigation,undefined);
  assert.deepEqual(actions.gesture.permissions,['observe','control']);
  assert.deepEqual(actions.verification.permissions,['observe','control']);
  assert.deepEqual(actions.verification.parameters.operation.enum,['assess','complete','handoff']);
  assert.equal(actions.verification.parameters.imageSupported,undefined,'model must not choose its image capability');
  for(const action of ['screenshot','tap','swipe','back'])assert.ok(actions[action].parameters.channel.enum.includes('root'));
  assert.equal(actions.wait_for.parameters.condition.required,undefined);
  assert.equal(actions.wait_for.parameters.conditions.type,'array');
  assert.deepEqual(actions.wait_for.parameters.match.enum,['any','all']);
});

test('short sequences expose only bounded device actions and current-page evidence', () => {
  const sequence = toolDefinitions.find(tool => tool.action === 'sequence');
  assert.equal(sequence.name, 'android_sequence');
  assert.deepEqual(sequence.permissions, ['observe', 'control']);
  assert.equal(sequence.parameters.snapshotId.required, true);
  assert.equal(sequence.parameters.packageName.required, true);
  const step = sequence.parameters.steps.items.properties;
  assert.deepEqual(step.action.enum, ['tap', 'input_text', 'set_selected', 'back', 'wait_for']);
  for (const unsupported of ['snapshotId', 'x', 'y', 'command', 'script', 'channel', 'taskId'])
    assert.equal(step[unsupported], undefined);
  assert.equal(step.target.additionalProperties, false);
  for (const action of ['tap', 'input_text', 'node_action', 'swipe', 'gesture', 'back', 'global_action']) {
    const parameters = toolDefinitions.find(tool => tool.action === action).parameters;
    assert.deepEqual(parameters.afterCondition.properties, toolDefinitions.find(tool => tool.action === 'wait_for').parameters.condition.properties);
  }
});
