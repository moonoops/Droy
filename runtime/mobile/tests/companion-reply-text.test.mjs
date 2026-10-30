import test from 'node:test';
import assert from 'node:assert/strict';
import { companionReplyText, companionVisibleMessage } from '../companions/reply-text.mjs';
const header='[msg_id=12345678-1234-1234-1234-123456789abc 2026-10-30T02:21:58.247Z reply_target=87654321-4321-4321-4321-cba987654321]\n';
test('only the legacy leading protocol envelope is removed',()=>{
  assert.equal(companionReplyText(header+'Hello\n\nWorld'),'Hello\n\nWorld');
  for(const text of ['[Hello]','Example:\n'+header+'Body','```\n'+header+'```','[msg_id=example] explanation']) assert.equal(companionReplyText(text),text);
  assert.equal(companionVisibleMessage({role:'user',text:header+'Original'}).text,header+'Original');
});
