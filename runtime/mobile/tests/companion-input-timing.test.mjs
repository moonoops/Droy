import test from 'node:test';
import assert from 'node:assert/strict';
import { inputBatchDelay } from '../companions/input-timing.mjs';

test('private input waits for additions and bounds a continuous batch',()=>{
  assert.equal(inputBatchDelay({now:100000}),2000);
  assert.equal(inputBatchDelay({now:100000,lastReplyAt:99000}),5000);
  assert.equal(inputBatchDelay({now:100000,lastReplyAt:99000,count:2}),2000);
  assert.equal(inputBatchDelay({now:100000,imageOnly:true}),4000);
  assert.equal(inputBatchDelay({now:107500,firstAt:100000,count:8}),500);
  assert.equal(inputBatchDelay({now:109000,firstAt:100000,count:10}),0);
  assert.equal(inputBatchDelay({now:100000,overrideMs:0}),0);
});
