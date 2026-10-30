import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, mkdir, writeFile, readFile, rm, lstat, symlink, link, realpath } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createHash } from 'node:crypto';
import { createRequire, registerHooks } from 'node:module';
import { pathToFileURL } from 'node:url';
const installed = createRequire(new URL('../../dsh/package.json', import.meta.url));
const hooks = registerHooks({ resolve(specifier, context, next) { if (specifier.startsWith('@deepseek-ai/')) return { url: pathToFileURL(installed.resolve(specifier)).href, shortCircuit: true }; return next(specifier, context); } });
const { createCompanionOwnedDataMaintenance, auditCompanionStoreAttachmentReferences } = await import('../companions/maintenance-owned-data.mjs');
const { createAfterScheduleRecord } = await import('@deepseek-ai/dsh-schedule');
const { requestImageVariantId } = await import('@deepseek-ai/dsh-attachment-local');
const { CompanionStore } = await import('../companions/store.mjs');
hooks.deregister();
const hash = value => createHash('sha256').update(value).digest('hex');
const canonical = value => Array.isArray(value) ? '[' + value.map(canonical).join(',') + ']' : value && typeof value === 'object' ? '{' + Object.keys(value).sort().map(key => JSON.stringify(key) + ':' + canonical(value[key])).join(',') + '}' : JSON.stringify(value);
const absent = path => assert.rejects(lstat(path), { code: 'ENOENT' });
async function put(path, content) { await mkdir(join(path, '..'), { recursive: true }); await writeFile(path, content); }
async function fixture(t, options = {}) {
  const base = await realpath(await mkdtemp(join(tmpdir(), 'droy-owned-test-')));
  t.after(() => rm(base, { recursive: true, force: true }));
  const root = join(base, 'companions'), storageRoot = join(base, 'storage'), attachmentRoot = join(base, 'attachments'), attachmentCacheRoot = join(base, 'cache');
  const target = join(root, 'companion-remove'), keep = join(root, 'companion-keep');
  await put(join(target, 'memory', 'memory.sqlite'), 'synthetic-memory'); await put(join(target, 'memory.identity.json'), '{}');
  await put(join(target, 'work', 'file.txt'), 'synthetic-work'); await mkdir(join(target, 'chat')); await put(join(keep, 'work', 'keep.txt'), 'keep');
  const ws = path => ({ path, title: 'Synthetic', sessionIds: [], createdAt: '2030-01-01', updatedAt: '2030-01-01' });
  const workspace = { unit: { name: 'workspace', version: 2 }, global: { initialized: true, workspaceIds: ['remove-work','keep-work'], archivedSessionIds: ['remove-session','keep-session'], pinnedSessionIds: [] }, tables: { workspaces: { 'remove-work': { ...ws(join(target,'work')), sessionIds: ['remove-session'] }, 'keep-work': { ...ws(join(keep,'work')), sessionIds: ['keep-session'] } } } };
  await put(join(storageRoot,'workspace.json'), JSON.stringify(workspace));
  const privateBlob = hash('private-blob'), sharedBlob = hash('shared-blob');
  await put(join(attachmentRoot,'objects',privateBlob.slice(0,2),privateBlob), 'private-blob');
  await put(join(attachmentRoot,'objects',sharedBlob.slice(0,2),sharedBlob), 'shared-blob');
  const manifest = { version: 1, scope: 'companion-store', companionId: 'companion-remove', sessionClaims: [{ sessionId: 'remove-session' }], workspaceClaims: [{ workspaceId: 'remove-work', retainedProfileIds: [], retainedSessionIds: [] }], blobs: [privateBlob,sharedBlob].map(key => ({ ref: { attachmentId: 'sha256:'+key }, retainedInStore: key === sharedBlob ? ['other'] : [] })) };
  const manifestDigest = hash(canonical(manifest)), operationId = 'operation'; let held = true, epoch = 'epoch';
  const authority = { async assertHeld() { return { root, operationId, manifestDigest, admissionEpoch: epoch, hostStopped: held, admissionsClosed: held }; } };
  const snapshots = ['remove','keep'].map(prefix => ({ id: prefix+'-session', header: { id: prefix+'-session', cwd: join(prefix==='remove'?target:keep,'work') }, events: [] }));
  const config = { root, storageRoot, attachmentRoot, attachmentCacheRoot, deepSeekIndexPath: join(base,'llm-deepseek','files-v3.json'), operationId, manifest, manifestDigest, authority, inspectSessions: async () => ({ complete: true, sessions: snapshots, attachmentReferencesComplete: true, retainedAttachmentIds: [] }), ...options };
  return { base, root, target, keep, storageRoot, attachmentRoot, attachmentCacheRoot, workspace, manifest, snapshots, privateBlob, sharedBlob, config,
    create: more => createCompanionOwnedDataMaintenance({ ...config, ...more }), lose: () => { held = false; }, epoch: () => { epoch = 'changed'; } };
}
test('removes only proven target directories/registry/blob and preserves shared data, idempotently', async t => {
  const f = await fixture(t), job = await f.create(); assert.equal((await job.prepare()).phase, 'prepared');
  const receipt = await job.commit(); assert.equal(receipt.phase, 'complete'); assert.deepEqual(receipt.pending, []);
  await absent(f.target); assert.equal(await readFile(join(f.keep,'work','keep.txt'),'utf8'), 'keep');
  const ws = JSON.parse(await readFile(join(f.storageRoot,'workspace.json'),'utf8'));
  assert.deepEqual(ws.tables.workspaces, { 'keep-work': f.workspace.tables.workspaces['keep-work'] }); assert.deepEqual(ws.global.archivedSessionIds,['keep-session']);
  await absent(join(f.attachmentRoot,'objects',f.privateBlob.slice(0,2),f.privateBlob));
  assert.equal(await readFile(join(f.attachmentRoot,'objects',f.sharedBlob.slice(0,2),f.sharedBlob),'utf8'),'shared-blob');
  assert.equal((await (await f.create()).recover()).phase,'complete');
});
test('refuses shared workspace, symlinks, changed sources, lost authority and changed epoch', async t => {
  for (const kind of ['shared-session','shared-workspace','symlink','changed','authority','epoch']) await t.test(kind, async child => {
    const f = await fixture(child);
    if (kind==='shared-session') f.snapshots[1].header.cwd=join(f.target,'chat');
    if (kind==='shared-workspace') { f.workspace.tables.workspaces['keep-work'].path=join(f.target,'work'); await writeFile(join(f.storageRoot,'workspace.json'),JSON.stringify(f.workspace)); }
    if (kind==='symlink') await symlink(join(f.keep,'work'),join(f.target,'work','link'));
    const job=await f.create();
    if (kind.startsWith('shared') || kind==='symlink') await assert.rejects(job.prepare());
    else { await job.prepare(); if(kind==='changed') await put(join(f.target,'work','new.txt'),'new'); if(kind==='authority')f.lose(); if(kind==='epoch')f.epoch(); await assert.rejects(job.commit()); }
    assert.equal(await readFile(join(f.keep,'work','keep.txt'),'utf8'),'keep'); assert.equal(await readFile(join(f.target,'work','file.txt'),'utf8'),'synthetic-work');
  });
});
test('durable exact plan resumes after a replacement and a partially removed tree', async t => {
  for (const prefix of ['applying','replaced:','removed:']) await t.test(prefix, async child => {
    let stopped=false; const f=await fixture(child,{onCheckpoint(phase){if(!stopped&&phase.startsWith(prefix)){stopped=true;throw Error('interrupt');}}});
    const job=await f.create(); await job.prepare(); await assert.rejects(job.commit(), /interrupt/);
    const recovered=await f.create({onCheckpoint(){}}); assert.equal((await recovered.recover()).phase,'complete'); await absent(f.target);
  });
});
test('session references retain blobs and unindexed image cache remains an explicit incomplete scope', async t => {
  const f=await fixture(t); f.snapshots[1].events.push({ attachment: { attachmentId: 'sha256:'+f.privateBlob } });
  await put(join(f.attachmentCacheRoot,'request-images','unindexed'),'cache'); const job=await f.create(); await job.prepare(); const receipt=await job.commit();
  assert.deepEqual(receipt.pending,['attachment-request-cache-unproven']); assert.equal(receipt.resources.attachments,'incomplete');
  assert.equal(await readFile(join(f.attachmentRoot,'objects',f.privateBlob.slice(0,2),f.privateBlob),'utf8'),'private-blob');
});
test('canonical file objects with in-store hardlink aliases delete together; outside hardlinks block', async t => {
  for(const external of [false,true]) await t.test(String(external),async child=>{
    const f=await fixture(child), key=f.privateBlob, object=join(f.attachmentRoot,'objects',key.slice(0,2),key), alias=join(f.attachmentRoot,'files',key.slice(0,2),key,'file.txt');
    await mkdir(join(alias,'..'),{recursive:true}); await link(object,alias); if(external)await link(object,join(f.base,'outside'));
    const job=await f.create(); if(external)await assert.rejects(job.prepare(),{code:'MAINTENANCE_OWNED_ATTACHMENT_LINK_UNPROVEN'}); else {await job.prepare();await job.commit();await absent(alias);}
  });
});
test('schedule tasks are validated against installed SDK and removed by original session only', async t => {
  const f=await fixture(t);
  const record=createAfterScheduleRecord('task', 'Synthetic', 60, Date.UTC(2030,0,1), 'Synthetic reminder');
  const schedule={unit:{name:'schedule',version:1},global:null,tables:{tasks:{'remove-task':{sessionId:'remove-session',record},'keep-task':{sessionId:'keep-session',record}}}};
  await put(join(f.storageRoot,'schedule.json'),JSON.stringify(schedule)); const job=await f.create();await job.prepare();await job.commit();
  assert.deepEqual(JSON.parse(await readFile(join(f.storageRoot,'schedule.json'),'utf8')).tables.tasks,{'keep-task':schedule.tables.tasks['keep-task']});
});

test('missing external reference proof preserves target blobs and reports pending', async t => {
  const f=await fixture(t), job=await f.create({inspectSessions:async()=>({complete:true,sessions:f.snapshots})});
  await job.prepare();const receipt=await job.commit();assert.deepEqual(receipt.pending,['attachment-reference-coverage']);
  assert.equal(await readFile(join(f.attachmentRoot,'objects',f.privateBlob.slice(0,2),f.privateBlob),'utf8'),'private-blob');
});
test('completed receipt rejects recreated residue and post-checkpoint changes cannot be deleted', async t => {
  const f=await fixture(t), job=await f.create();await job.prepare();await job.commit();
  await put(join(f.target,'work','file.txt'),'recreated');await assert.rejects(job.recover(),{code:'MAINTENANCE_OWNED_RESIDUE_PRESENT'});
  const g=await fixture(t), changed=await g.create({onCheckpoint:async phase=>{if(phase==='applying')await writeFile(join(g.target,'work','file.txt'),'changed');}});
  await changed.prepare();await assert.rejects(changed.commit(),{code:'MAINTENANCE_OWNED_SOURCE_CHANGED'});
  assert.equal(await readFile(join(g.target,'work','file.txt'),'utf8'),'changed');
});

test('DeepSeek v3 index proves target cache variants while retaining other mappings and bytes', async t => {
  const f=await fixture(t), targetId='sha256:'+f.privateBlob, keptId='sha256:'+f.sharedBlob;
  const targetVariant='sha256:'+hash('target-variant'), keptVariant='sha256:'+hash('kept-variant');
  const record=(attachmentId,variantId)=>({scope:'c'.repeat(64),attachmentId,variantId,fileId:'synthetic-file',bytes:12,createdAt:1,expiresAt:99});
  const kept=record(keptId,keptVariant);
  await put(f.config.deepSeekIndexPath,JSON.stringify({formatVersion:3,records:[record(targetId,targetVariant),kept]}));
  const cache=id=>join(f.attachmentCacheRoot,'request-images',id.slice(7,9),id.slice(7));
  await put(cache(targetVariant),'target-cache');await put(cache(keptVariant),'keep-cache');
  const job=await f.create();await job.prepare();const receipt=await job.commit();assert.deepEqual(receipt.pending,[]);
  await absent(cache(targetVariant));assert.equal(await readFile(cache(keptVariant),'utf8'),'keep-cache');
  assert.deepEqual(JSON.parse(await readFile(f.config.deepSeekIndexPath,'utf8')).records,[kept]);
  assert.equal((await (await f.create()).recover()).phase,'complete');
});
test('official transform descriptors prove cache keys, legacy PNG ownership, and corrupt index blocks all deletion', async t => {
  const f=await fixture(t), target={width:32,height:24,maxBytes:1024}, attachmentId='sha256:'+f.privateBlob;
  const variant=requestImageVariantId({attachmentId},target), cache=join(f.attachmentCacheRoot,'request-images',variant.slice(7,9),variant.slice(7));
  await put(cache,'synthetic-cache');const legacy=join(f.base,'droy-png-v1',f.privateBlob+'.png');await put(legacy,'private-blob');
  const job=await f.create({inspectSessions:async()=>({complete:true,sessions:f.snapshots,attachmentReferencesComplete:true,retainedAttachmentIds:[],requestImageVariants:[{attachmentId,target}]})});
  await job.prepare();assert.deepEqual((await job.commit()).pending,[]);await absent(cache);await absent(legacy);
  const g=await fixture(t);await put(g.config.deepSeekIndexPath,JSON.stringify({formatVersion:99,records:[]}));
  await assert.rejects((await g.create()).prepare(),{code:'MAINTENANCE_OWNED_UPLOAD_INDEX_INVALID'});assert.equal(await readFile(join(g.target,'work','file.txt'),'utf8'),'synthetic-work');
});
test('full SQLite reference audit includes other users, creation drafts and uploaded URLs before deletion',async t=>{
  const root=await realpath(await mkdtemp(join(tmpdir(),'droy-owned-reference-')));t.after(()=>rm(root,{recursive:true,force:true}));
  const store=await CompanionStore.open({directory:root}), profiles=await store.profiles(), keep=profiles[0].id, target=(await store.createProfile({requestId:'synthetic-target-create',templateId:'kaka',name:'Synthetic target',model:null})).profile.id;
  const upload=(owner,key,digit)=>({id:key,name:'synthetic.png',mediaType:'image/png',bytes:9,ref:{attachmentId:'sha256:'+digit.repeat(64),bytes:9,mediaType:'image/png',width:2,height:2},url:'/droy/companions/'+owner+'/attachments/'+key});
  await store.putUpload(target,upload(target,'target-upload','a'));await store.putUpload(keep,upload(keep,'keep-upload','b'));
  await store.putUserUpload(upload('user-profile','user-upload','c'));
  const draft=await store.createDraft({requestId:'synthetic-request'});await store.putDraftUpload(draft.draftId,upload('creation-drafts/'+draft.draftId,'draft-upload','d'));
  await store.updateUserProfile({avatarUrl:'/droy/companions/user-profile/attachments/user-upload'});await store.close();
  const manifest={companionId:target},manifestDigest=hash(canonical(manifest)),operationId='reference-op';
  const authority={async assertHeld(){return {root,operationId,manifestDigest,admissionEpoch:'reference-epoch',hostStopped:true,admissionsClosed:true};}};
  const audit=await auditCompanionStoreAttachmentReferences({root,operationId,manifest,manifestDigest,authority});
  assert.deepEqual(audit.targetAttachmentIds,['sha256:'+'a'.repeat(64)]);assert.deepEqual(audit.retainedAttachmentIds,['b','c','d'].map(value=>'sha256:'+value.repeat(64)));
  assert.equal(audit.uploads.find(row=>row.uploadId==='user-upload').ownerKind,'user');assert.equal(audit.uploads.find(row=>row.uploadId==='target-upload').removed,true);
});
