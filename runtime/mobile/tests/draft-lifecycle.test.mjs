import test from 'node:test';
import assert from 'node:assert/strict';
import {createDroyDraftRetention} from '../../../tools/mobile_draft_bridge.mjs';

function source(value) {
  const listeners=new Set();
  return {getSnapshot:()=>value,subscribe:fn=>{listeners.add(fn);return ()=>listeners.delete(fn);},
    set:next=>{value=next;for(const fn of [...listeners])fn();},get listenerCount(){return listeners.size;}};
}

function fixture({text='',attachments=[],blank=true,mobile=true}={}) {
  const events=[],saved=new Map(),resources=new Map(attachments.map(id=>[id,{bytes:'fixture image'}]));
  const state=source({draft:text,attachmentIds:attachments,phase:'plain'});
  const session=source({blank});
  let main=1,retained=0,retains=0,releases=0,disposed=0,untrack;
  const destroy=()=>{if(main+retained!==0)return;disposed++;untrack?.();resources.clear();};
  const sessions={retain:(id,options)=>{assert.equal(id,'fixture');assert.equal(options.source,'workspaceOperation');retains++;retained++;let done=false;return{release(){assert.equal(done,false);done=true;retained--;releases++;destroy();}};}};
  const target={document:{documentElement:{hasAttribute:()=>mobile}},CustomEvent:class{constructor(type){this.type=type;}},dispatchEvent:event=>events.push(event.type)};
  const tracker=createDroyDraftRetention(target,sessions,id=>({text:saved.get(id)||''}));
  const binding={sessionId:'fixture',session};
  const input={state};
  const track=()=>untrack=tracker.track(binding,input);
  track();
  return {tracker,state,session,binding,input,events,saved,resources,track,
    leave(){assert.equal(main,1);main--;destroy();},open(){assert.equal(disposed,0);main++;},
    get counts(){return{main,retained,retains,releases,disposed};}};
}

test('blank text draft keeps exactly one original input binding across navigation, without a second draft store', () => {
  const f=fixture({text:'unsent fixture text'});
  assert.equal(f.tracker.has('fixture'),true);
  f.leave();
  assert.equal(f.counts.disposed,0);
  assert.equal(f.input.state.getSnapshot().draft,'unsent fixture text');
  f.open();f.track();
  assert.equal(f.counts.retains,1);
  f.state.set({...f.state.getSnapshot(),draft:''});
  assert.equal(f.tracker.has('fixture'),false);
  assert.equal(f.counts.releases,1);
  f.tracker.dispose();
  assert.equal(f.state.listenerCount,0);
  assert.equal(f.session.listenerCount,0);
  assert.equal(f.counts.releases,1);
  assert.ok(f.events.every(type=>type==='droy-mobile-drafts-change'));
});

test('attachment-only drafts retain the existing bytes while away and release them after explicit clear', () => {
  const f=fixture({attachments:['image-fixture']});
  f.leave();
  assert.equal(f.tracker.has('fixture'),true);
  assert.deepEqual(f.state.getSnapshot().attachmentIds,['image-fixture']);
  assert.equal(f.resources.size,1);
  assert.equal(f.counts.disposed,0);
  f.open();f.state.set({draft:'',attachmentIds:[],phase:'plain'});
  assert.equal(f.counts.retained,0);
  assert.equal(f.tracker.has('fixture'),false);
  f.leave();
  assert.equal(f.counts.disposed,1);
  assert.equal(f.resources.size,0);
  assert.equal(f.state.listenerCount,0);
});

test('empty provisional rows and ordinary history drafts do not acquire another retained binding', () => {
  for(const options of [{},{text:'existing conversation draft',blank:false}]) {
    const f=fixture(options);assert.equal(f.tracker.has('fixture'),false);
    assert.equal(f.counts.retains,0);f.leave();assert.equal(f.counts.disposed,1);
    f.tracker.dispose();assert.equal(f.counts.releases,0);
  }
});

test('optimistic submit clear keeps draft ownership until admission, restores on failure, and releases on turn start', () => {
  const f=fixture({text:'before submit',attachments:['image-fixture']});
  f.leave();
  f.state.set({draft:'',attachmentIds:[],phase:'submitting'});
  assert.equal(f.counts.retained,1);
  assert.equal(f.resources.size,1);
  f.state.set({draft:'before submit',attachmentIds:['image-fixture'],phase:'plain'});
  assert.equal(f.tracker.has('fixture'),true);
  assert.equal(f.counts.retains,1);
  f.state.set({draft:'',attachmentIds:[],phase:'submitting'});
  f.session.set({blank:false});
  assert.equal(f.counts.releases,1);
  assert.equal(f.counts.retained,0);
  assert.equal(f.counts.disposed,1);
  assert.equal(f.resources.size,0);
});

test('archive and plugin disposal remove draft guards and subscriptions without duplicate release', () => {
  for(const reason of ['archive','dispose']) {
    const f=fixture({attachments:['image-fixture']});f.leave();
    if(reason==='archive')f.tracker.setArchived(['fixture']);else f.tracker.dispose();
    assert.equal(f.counts.retained,0);assert.equal(f.counts.releases,1);
    assert.equal(f.state.listenerCount,0);assert.equal(f.session.listenerCount,0);
    f.tracker.dispose();assert.equal(f.counts.releases,1);
  }
});

test('saved text is read from DSH only and the desktop path does not retain mobile drafts', () => {
  const f=fixture();f.saved.set('other-blank','text already saved by DSH');
  assert.equal(f.tracker.has('other-blank'),true);
  f.tracker.setArchived(['other-blank']);assert.equal(f.tracker.has('other-blank'),false);
  const desktop=fixture({text:'desktop draft',attachments:['file-fixture'],mobile:false});
  assert.equal(desktop.counts.retains,0);assert.equal(desktop.tracker.has('fixture'),false);
  desktop.tracker.dispose();assert.equal(desktop.state.listenerCount,0);
});
