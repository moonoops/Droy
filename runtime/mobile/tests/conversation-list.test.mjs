import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { DatabaseSync } from 'node:sqlite';
import { CompanionStore } from '../companions/store.mjs';

async function fixture(t) {
  const directory = await mkdtemp(join(tmpdir(), 'droy-conversation-list-'));
  let store = await CompanionStore.open({ directory });
  t.after(async () => { await store.close(); await rm(directory, { recursive: true, force: true }); });
  return { directory, get store() { return store; }, async reopen() { await store.close(); store = await CompanionStore.open({ directory }); } };
}
const message = (id, extra = {}) => ({ id, role: 'assistant', text: 'Fictional message ' + id, createdAt: '2026-10-28T01:00:00.000Z', ...extra });
const row = async (store, id = 'kaka') => (await store.profiles()).find(profile => profile.id === id);
const action = (store, value, id = 'kaka') => store.updateConversation(id, { action: value });

test('conversation flags persist independently of profiles and actual message activity', async t => {
  const f = await fixture(t), before = await f.store.getProfile('kaka');
  assert.equal((await row(f.store)).pinned, false);
  assert.equal((await row(f.store)).hidden, false);
  assert.equal((await row(f.store)).lastActivityAt, null);
  await f.store.appendMessage('kaka', message('first'));
  await action(f.store, 'pin');
  await f.store.updateProfile('kaka', { name: 'Fictional Friend' });
  await f.reopen();
  const saved = await row(f.store);
  assert.equal(saved.pinned, true);
  assert.equal(saved.hidden, false);
  assert.equal(saved.lastActivityAt, '2026-10-28T01:00:00.000Z');
  assert.equal(saved.lastMessage.id, 'first');
  assert.equal(saved.createdAt, before.createdAt);
  assert.equal((await row(f.store, 'maimai')).pinned, false);
  await action(f.store, 'unpin');
  assert.equal((await row(f.store)).pinned, false);
});

test('hiding clears old unread atomically, preserves history and new messages return unpinned', async t => {
  const f = await fixture(t);
  await f.store.appendMessage('kaka', message('old-1'));
  await f.store.appendMessage('kaka', message('old-2'));
  await f.store.appendMessage('maimai', message('other-unread'));
  await action(f.store, 'pin');
  assert.deepEqual(await action(f.store, 'hide'), { pinned: false, hidden: true, unreadCount: 0 });
  await f.reopen();
  assert.equal((await row(f.store)).hidden, true);
  assert.equal((await row(f.store)).unreadCount, 0);
  assert.equal((await f.store.listMessages('kaka')).messages.length, 2);
  assert.equal((await row(f.store, 'maimai')).unreadCount, 1);
  await f.store.appendMessage('kaka', message('new', { createdAt: '2026-10-28T02:00:00.000Z' }));
  const returned = await row(f.store);
  assert.equal(returned.hidden, false);
  assert.equal(returned.pinned, false);
  assert.equal(returned.unreadCount, 1);
  assert.equal(returned.lastActivityAt, '2026-10-28T02:00:00.000Z');
  await f.reopen();
  assert.equal((await row(f.store)).unreadCount, 1);
});

test('hidden conversations ignore metadata, system messages, replay and existing message updates', async t => {
  const { store } = await fixture(t), original = message('original');
  await store.appendMessage('kaka', original);
  await action(store, 'hide');
  await store.updateProfile('kaka', { name: 'Fictional renamed friend' });
  await store.appendMessage('kaka', message('system', { role: 'system', createdAt: '2026-10-28T03:00:00.000Z' }));
  await store.appendMessage('kaka', original);
  await store.updateMessage('kaka', 'original', { text: 'Fictional edited message' });
  const hidden = await row(store);
  assert.equal(hidden.hidden, true);
  assert.equal(hidden.unreadCount, 0);
  assert.equal(hidden.lastMessage.id, 'original');
  assert.equal(hidden.lastActivityAt, original.createdAt);
  await store.appendMessage('kaka', message('user', { role: 'user' }));
  assert.equal((await row(store)).hidden, false);
  assert.equal((await row(store)).unreadCount, 0);
});

test('mark all read preserves flags and does not consume subsequently appended messages', async t => {
  const { store } = await fixture(t);
  await action(store, 'pin');
  await store.appendMessage('kaka', message('read-1'));
  assert.deepEqual(await action(store, 'read'), { pinned: true, hidden: false, unreadCount: 0 });
  await store.appendMessage('kaka', message('read-2'));
  assert.equal((await row(store)).unreadCount, 1);
  await store.markRead('kaka', 'read-1');
  assert.equal((await row(store)).unreadCount, 1);
  await action(store, 'hide');
  assert.deepEqual(await action(store, 'read'), { pinned: false, hidden: true, unreadCount: 0 });
  assert.deepEqual(await action(store, 'read', 'maimai'), { pinned: false, hidden: false, unreadCount: 0 });
});

test('invalid actions and failed writes cannot partly clear unread or hide a conversation', async t => {
  const f = await fixture(t);
  await f.store.appendMessage('kaka', message('unread'));
  await action(f.store, 'pin');
  for (const input of [null, {}, { action: 'delete' }, { action: 'hide', hidden: true }, { action: ['hide'] }])
    await assert.rejects(f.store.updateConversation('kaka', input), error => error.code === 'COMPANION_INVALID_INPUT');
  await assert.rejects(action(f.store, 'hide', 'missing'), error => error.code === 'COMPANION_NOT_FOUND');
  const db = new DatabaseSync(join(f.directory, 'companions.sqlite'));
  db.exec("CREATE TRIGGER fail_conversation_write BEFORE INSERT ON conversation_state BEGIN SELECT RAISE(ABORT,'fixture write rejection'); END");
  await assert.rejects(action(f.store, 'hide'), /fixture write rejection/);
  assert.equal((await row(f.store)).pinned, true);
  assert.equal((await row(f.store)).hidden, false);
  assert.equal((await row(f.store)).unreadCount, 1);
  db.exec('DROP TRIGGER fail_conversation_write');
  db.close();
});

test('an existing database gains conversation state without changing its messages or read position', async t => {
  const f = await fixture(t);
  await f.store.appendMessage('kaka', message('read'));
  await f.store.markRead('kaka', 'read');
  await f.store.appendMessage('kaka', message('unread'));
  await f.store.close();
  const db = new DatabaseSync(join(f.directory, 'companions.sqlite'));
  db.exec('DROP TABLE conversation_state'); db.close();
  await f.reopen();
  const saved = await row(f.store);
  assert.equal(saved.unreadCount, 1);
  assert.equal(saved.hidden, false);
  assert.equal(saved.pinned, false);
  assert.deepEqual((await f.store.listMessages('kaka')).messages.map(item => item.id), ['read', 'unread']);
});

test('a failed visibility update rolls back the new message rather than hiding an unread delivery', async t => {
  const f = await fixture(t);
  await action(f.store, 'hide');
  const db = new DatabaseSync(join(f.directory, 'companions.sqlite'));
  db.exec("CREATE TRIGGER fail_conversation_restore BEFORE UPDATE ON conversation_state WHEN new.hidden=0 BEGIN SELECT RAISE(ABORT,'fixture visibility rejection'); END");
  await assert.rejects(f.store.appendMessage('kaka', message('new')), /fixture visibility rejection/);
  assert.equal(await f.store.getMessage('kaka', 'new'), null);
  assert.equal((await row(f.store)).hidden, true);
  assert.equal((await row(f.store)).unreadCount, 0);
  db.exec('DROP TRIGGER fail_conversation_restore'); db.close();
  await f.store.appendMessage('kaka', message('new'));
  assert.equal((await row(f.store)).hidden, false);
  assert.equal((await row(f.store)).unreadCount, 1);
});
