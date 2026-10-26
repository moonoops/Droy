import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import vm from 'node:vm';
import { patchMediaCommandMenu } from '../upstream-patch.mjs';

let api;
const React = { createElement: (type, props, ...children) => ({ type, props, children }) };
const adapter = await readFile(new URL('../dsh-adapter.js', import.meta.url), 'utf8');
const client = (await readFile(new URL('../client.js', import.meta.url), 'utf8')).replace('/* DROY_MEDIA_ADAPTER */', adapter);
vm.runInNewContext(client, { window: { __ModuleLoader__: { load: ({ factory }) => { api = factory(name => name === 'react' ? React : {}); } } } });

test('media rows lead the Add section in album/camera order without moving other commands', () => {
  const original = [
    { name: 'file', section: '添加' }, { name: 'goal', section: '添加' },
    { name: 'model', section: '命令' }, { name: 'droy-camera', section: '命令' },
    { name: 'droy-album', section: '命令' }, { name: 'droy-imports', section: '命令' },
  ];
  const rows = api.orderMediaCommands(original);
  assert.deepEqual(Array.from(rows, row => row.name), ['droy-album', 'droy-camera', 'file', 'goal', 'model', 'droy-imports']);
  assert.equal(rows[0].section, '添加'); assert.equal(rows[1].section, '添加');
  assert.equal(original[3].section, '命令', 'ordering must not mutate the source candidates');
  assert.equal(rows[2], original[0]); assert.equal(rows[5], original[5]);
  assert.deepEqual(Array.from(api.orderMediaCommands(original.slice(0, 3)), row => row.name), ['file', 'goal', 'model']);
});

test('menu entries expose the requested copy, distinct line icons and the existing native action routes', () => {
  const picks = [], session = { sessionId: 'existing' };
  const commands = api.createMediaCommands({ available: id => id === 'existing' },
    { pick: (...args) => picks.push(args), pendingRows: () => [], open() {} }, { DroyMobile: { mediaInput() {} } });
  assert.equal(commands[0].label(), '相册'); assert.equal(commands[0].description(), '从相册选择图片上传');
  assert.equal(commands[1].label(), '拍照'); assert.equal(commands[1].description(), '使用相机拍照上传');
  for (const command of commands.slice(0, 2)) {
    assert.equal(command.available(session), true); assert.equal(command.available({ sessionId: 'gone' }), false);
    const icon = command.icon({ size: 20, className: 'menu-icon' });
    assert.equal(icon.type, 'svg'); assert.equal(icon.props.width, 20); assert.equal(icon.props.height, 20);
    assert.equal(icon.props.className, 'menu-icon'); assert.equal(icon.props.stroke, 'currentColor');
    assert.equal(icon.props['aria-hidden'], true); command.ui.run(session);
  }
  assert.notDeepEqual(commands[0].icon(), commands[1].icon());
  assert.deepEqual(picks, [['album', 'existing'], ['camera', 'existing']]);
  const unavailable = api.createMediaCommands({ available: () => true }, {}, {});
  assert.equal(unavailable[0].available(session), false);
});

const asset = '@deepseek-ai/dsh-client-ui-commands/lib/client.js';
const upstream = await readFile(new URL('../../dsh/node_modules/' + asset, import.meta.url), 'utf8');
const patched = patchMediaCommandMenu(upstream);
function candidates(listener) {
  const start = patched.indexOf('async candidates(session, req) {');
  const end = patched.indexOf('/** Decision table, menu column:', start);
  let hooks = 0, ranks = 0;
  const sectionRows = rows => rows.map(row => ({ ...row, section: 'Add' }));
  const instance = vm.runInNewContext(`({${patched.slice(start, end)}})`, {
    builtinRowFace: () => undefined, sectionRows,
    _deepseek_ai_dsh_client_ui_primitives: { rankByName: rows => { ranks++; return rows.filter(row => row.name === 'file'); } },
  });
  Object.assign(instance, {
    directory: { ensureReady: async () => [] }, t: value => value,
    live: { contributions: new Map(['file', 'droy-camera', 'droy-album'].map(name => [name, { name, available: () => true }])) },
    ctx: { bail(event, rows) { hooks++; assert.equal(event, 'droy/command-menu-rows'); return listener?.(rows); } },
  });
  return { run: query => instance.candidates({ sessionId: 'existing' }, { query, position: 'leading' }),
    get hooks() { return hooks; }, get ranks() { return ranks; } };
}

test('locked upstream seam applies plugin ordering only to the empty-query menu', async () => {
  const f = candidates(api.orderMediaCommands);
  assert.deepEqual(Array.from(await f.run(''), row => row.name), ['droy-album', 'droy-camera', 'file']);
  assert.equal(f.hooks, 1);
  assert.deepEqual(Array.from(await f.run('file'), row => row.name), ['file']);
  assert.equal(f.hooks, 1); assert.equal(f.ranks, 1, 'typed query must keep DSH ranking');
  const withoutPlugin = candidates();
  assert.deepEqual(Array.from(await withoutPlugin.run(''), row => row.name), ['file', 'droy-camera', 'droy-album']);
});

test('the packaging seam rejects upstream drift and duplicate application', async () => {
  const lock = JSON.parse(await readFile(new URL('../../mobile/upstream-patches.lock.json', import.meta.url), 'utf8'));
  assert.equal(lock.sha256[asset], createHash('sha256').update(upstream).digest('hex'));
  assert.throws(() => patchMediaCommandMenu('changed upstream'), /anchor changed/);
  assert.throws(() => patchMediaCommandMenu(patched), /anchor changed/);
});
