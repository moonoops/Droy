import test from 'node:test';
import assert from 'node:assert/strict';
import { renderPhoneResult } from '../tool-result.mjs';
import { BridgeError } from '../bridge.mjs';

const observation = () => ({ snapshotId: 'current', packageName: 'com.example.fixture',
  nodes: [{ nodeId: 'n1', text: '练习商品', description: '', resourceId: '', clickable: true, selected: false,
    bounds: { left: 0, top: 20, right: 200, bottom: 80 } },
  { nodeId: 'n2', text: '', description: '搜索', resourceId: 'fixture:id/search', editable: false }],
  text: '练习商品\n搜索\n', truncated: false });

test('new page output omits only exact duplicate labels and empty optional text fields', () => {
  const original = observation(), saved = structuredClone(original), result = renderPhoneResult(original);
  assert.equal(result.text, undefined);
  assert.equal(result.nodes[0].description, undefined);
  assert.equal(result.nodes[0].selected, false);
  assert.equal(result.nodes[0].nodeId, 'n1');
  assert.deepEqual(result.nodes[0].bounds, original.nodes[0].bounds);
  assert.equal(result.nodes[1].resourceId, 'fixture:id/search');
  assert.deepEqual(original, saved);
  assert.ok(JSON.stringify(result).length < JSON.stringify(original).length);
});

test('extra page text and unknown side-effect receipts survive formatting', () => {
  const value = { status: 'sequence_stopped', dispatched: true, outcomeUnknown: true,
    afterObservation: { ...observation(), text: '练习商品\n搜索\n额外商品条件' } };
  const result = renderPhoneResult(value);
  assert.equal(result.afterObservation.text, value.afterObservation.text);
  assert.equal(result.outcomeUnknown, true);
  assert.equal(result.dispatched, true);
});

test('unrelated tool values are not interpreted as Android pages', () => {
  const value = { text: '原始文件内容', nodes: [{ text: '局部内容' }] };
  assert.deepEqual(renderPhoneResult(value), value);
});

test('error output uses the same page formatting while preserving dispatch evidence', () => {
  const details = { currentObservation: observation(), dispatched: true, outcomeUnknown: true };
  const error = new BridgeError('target_changed', '请核实', details);
  const rendered = JSON.parse(error.message.split('\n').slice(1).join('\n'));
  assert.equal(rendered.currentObservation.text, undefined);
  assert.equal(rendered.currentObservation.nodes[0].description, undefined);
  assert.equal(rendered.dispatched, true);
  assert.equal(rendered.outcomeUnknown, true);
  assert.equal(error.details, details);
});
