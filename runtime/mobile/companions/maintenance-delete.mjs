import { createHash } from 'node:crypto';
import { lstat, open, readFile, realpath, rename, unlink } from 'node:fs/promises';
import { dirname, isAbsolute, join, relative, resolve } from 'node:path';
import { createCompanionStoreMaintenance } from './maintenance-store.mjs';
import { createSessionMaintenance, createOfflineJsonlProvider } from './maintenance-sessions.mjs';
import { createCompanionOwnedDataMaintenance, auditCompanionStoreAttachmentReferences } from './maintenance-owned-data.mjs';
import { createTrustedRootAlias } from './maintenance-root-alias.mjs';

const object = value => value !== null && typeof value === 'object' && !Array.isArray(value);
const id = value => typeof value === 'string' && /^[A-Za-z0-9_-]{1,80}$/u.test(value);
const canonical = value => Array.isArray(value) ? '[' + value.map(canonical).join(',') + ']' : object(value)
  ? '{' + Object.keys(value).sort().map(key => JSON.stringify(key) + ':' + canonical(value[key])).join(',') + '}' : JSON.stringify(value);
const digest = value => createHash('sha256').update(canonical(value)).digest('hex');
const hex = value => typeof value === 'string' && /^[a-f0-9]{64}$/u.test(value);
const fail = (code, cause) => { throw Object.assign(new Error('MAINTENANCE_DELETE_' + code, cause ? { cause } : undefined), { code: 'MAINTENANCE_DELETE_' + code }); };
const inside = (root,path) => path === root || !relative(root,path).startsWith('..') && !isAbsolute(relative(root,path));
async function stat(path) { try { return await lstat(path); } catch (error) { if (error.code === 'ENOENT') return null; throw error; } }
async function regular(path, optional = false) {
  const file = await stat(path); if (!file && optional) return null;
  if (!file?.isFile() || file.isSymbolicLink() || file.nlink !== 1) fail('JOURNAL_INVALID'); return file;
}
async function publish(path,value) {
  const temporary=path+'.writing'; if(await regular(temporary,true))await unlink(temporary);
  const file=await open(temporary,'wx',0o600);
  try { await file.writeFile(JSON.stringify({...value,checksum:digest(value)})+'\n');await file.sync(); } finally {await file.close();}
  await rename(temporary,path);const parent=await open(dirname(path),'r');try{await parent.sync();}finally{await parent.close();}
}
function validateAndroid(snapshot, identity) {
  if (!object(snapshot) || snapshot.version !== 1 || snapshot.complete !== true || !hex(snapshot.retirementDigest)
    || Object.keys(identity).some(key=>snapshot[key]!==identity[key]) || !Array.isArray(snapshot.retained) || !Array.isArray(snapshot.target)) fail('ANDROID_SNAPSHOT_REQUIRED');
  const sources=new Set();
  for(const row of [...snapshot.retained,...snapshot.target]) {
    if(!object(row)||typeof row.source!=='string'||!row.source||sources.has(row.source)||!object(row.value)&&!Array.isArray(row.value))fail('ANDROID_SNAPSHOT_INVALID');
    sources.add(row.source);
  }
}
function androidReferences(rows,uploads) {
  const result=new Set(), urls=new Map(uploads.map(row=>[row.url,row.attachmentId]));
  function walk(value) {
    if(typeof value==='string') {
      if(/^sha256:[a-f0-9]{64}$/u.test(value))result.add(value);
      else if(value.startsWith('/droy/companions/')&&value.includes('/attachments/')) { const ref=urls.get(value);if(!ref)fail('ANDROID_UPLOAD_UNPROVEN');result.add(ref); }
      return;
    }
    if(Array.isArray(value)){for(const child of value)walk(child);return;}
    if(!object(value))return;
    if(typeof value.attachmentId==='string'&&!value.attachmentId.startsWith('sha256:')) {
      if(/^[a-f0-9]{64}$/u.test(value.attachmentId))result.add('sha256:'+value.attachmentId);
      else { const matches=uploads.filter(row=>row.uploadId===value.attachmentId);if(!matches.length)fail('ANDROID_UPLOAD_UNPROVEN');for(const row of matches)result.add(row.attachmentId); }
    }
    // NativeLocalAttachment has importId and names a private Android copy, not
    // a shared blob. Hosted companion attachments carry ref and/or URL.
    for(const child of Object.values(value))walk(child);
  }
  for(const row of rows)walk(row.value);return [...result].sort();
}

/** Full offline coordinator. Android snapshot values are trusted parsed JSON
 * trees after Java drains UI writers; raw trees/events are never journalled.
 * Local completion still requires acknowledgeAndroid() after Java retires its
 * prepared metadata/import set under the same epoch. No Host is started here. */
export async function createCompanionDeletionMaintenance({ home, operationId, companionId, admissionEpoch, authority,
  androidSnapshot, trustedRootAlias, provider=createOfflineJsonlProvider(), compression='none', onCheckpoint=()=>{} }={}) {
  if(typeof home!=='string'||!isAbsolute(home)||!id(operationId)||!id(admissionEpoch)||typeof companionId!=='string'
    ||!/^[A-Za-z0-9_-][A-Za-z0-9_.:-]{0,159}$/u.test(companionId)||!['none','zstd'].includes(compression)
    ||typeof authority?.assertHeld!=='function'||typeof provider!=='function'||typeof onCheckpoint!=='function')fail('INVALID_INPUT');
  home=resolve(home);if(await realpath(home)!==home||(await lstat(home)).isSymbolicLink())fail('LINKED_PATH');
  const storedPaths=await createTrustedRootAlias(home,trustedRootAlias,fail);
  const roots={store:join(home,'droy-companions'),sessions:join(home,'sessions'),storage:join(home,'storages'),
    attachments:join(home,'attachments','v1'),cache:join(home,'cache','attachments'),deepSeek:join(home,'llm-deepseek','files-v3.json')};
  const journalPath=join(home,'.companion-delete-'+operationId+'.json'), identity={operationId,companionId,admissionEpoch};
  let journal, sourceSnapshot;
  async function guard() {
    let proof;try{proof=await authority.assertHeld({root:home,scope:'companion-deletion',...identity});}catch(error){fail('AUTHORITY_LOST',error);}
    if(!object(proof)||proof.root!==home||Object.keys(identity).some(key=>proof[key]!==identity[key])||proof.hostStopped!==true||proof.admissionsClosed!==true)fail('AUTHORITY_LOST');
    return proof;
  }
  const subauthority={async assertHeld(request){
    await guard();const root=request.scope==='sessions'?roots.sessions:roots.store;
    if(request.root!==root||request.operationId!==operationId||!['sessions','companion-store','companion-owned-data'].includes(request.scope))fail('SCOPE_INVALID');
    const expected=request.scope==='companion-store'?journal?.manifestDigest:journal?.ownedManifestDigest;
    if(request.manifestDigest!==undefined&&request.manifestDigest!==expected)fail('MANIFEST_CONFLICT');
    return {root,operationId,admissionEpoch,hostStopped:true,admissionsClosed:true,...(expected?{manifestDigest:expected}:{})};
  }};
  async function load() {
    if(!await regular(journalPath,true))return false;
    let value;try{value=JSON.parse(await readFile(journalPath,'utf8'));}catch(error){fail('JOURNAL_INVALID',error);}
    const {checksum,...record}=value;
    if(checksum!==digest(record)||record.version!==1||record.home!==home||record.compression!==compression
      ||Object.keys(identity).some(key=>record[key]!==identity[key])||!['planning','prepared','sessions','owned','local-complete','complete'].includes(record.phase)
      ||digest(record.manifest)!==record.manifestDigest||digest(record.ownedManifest)!==record.ownedManifestDigest
      ||!Array.isArray(record.sessionIds)||new Set(record.sessionIds).size!==record.sessionIds.length
      ||record.manifest.companionId!==companionId||record.ownedManifest.companionId!==companionId||!hex(record.androidSnapshotDigest)||!hex(record.retirementDigest))fail('JOURNAL_INVALID');
    if(digest(record.trustedRootAlias??null)!==digest(storedPaths.value))fail('ROOT_ALIAS_CHANGED');
    if(androidSnapshot&&digest(androidSnapshot)!==record.androidSnapshotDigest)fail('ANDROID_SNAPSHOT_CHANGED');
    journal=record;return true;
  }
  async function checkpoint(phase,extra={}){await guard();journal={...journal,...extra,phase};await publish(journalPath,journal);await onCheckpoint(phase);}
  async function sessionsSnapshot() {
    await guard();const opened=await provider({root:roots.sessions,compression});
    try {
      const audit=await opened.audit();if(audit.complete!==true||!Array.isArray(audit.entries))fail('SESSION_AUDIT_REQUIRED');
      const sessions=[];
      for(const row of audit.entries){
        await guard();const reader=await opened.store.open(row.id,'read');
        try {
          const {events}=await reader.read();const value={header:reader.header,inheritedEventCount:reader.inheritedEventCount,events};
          if(digest(value)!==row.logicalDigest)fail('SESSION_CHANGED');
          sessions.push({id:row.id,...value,generations:row.generations});
        }finally{await reader.close();}
      }
      return {complete:true,sessions,fingerprint:audit.fingerprint};
    }finally{await opened.close();}
  }
  async function storeJob(){return createCompanionStoreMaintenance({root:roots.store,operationId,companionId,authority:subauthority,onCheckpoint:phase=>onCheckpoint('store:'+phase)});}
  async function sessionJob(){return journal.sessionIds.length?createSessionMaintenance({root:roots.sessions,operationId,deleteSessionIds:journal.sessionIds,authority:subauthority,provider,compression,onCheckpoint:phase=>onCheckpoint('session:'+phase)}):null;}
  async function ownedJob(){return createCompanionOwnedDataMaintenance({root:roots.store,storageRoot:roots.storage,attachmentRoot:roots.attachments,
    attachmentCacheRoot:roots.cache,deepSeekIndexPath:roots.deepSeek,operationId,manifest:journal.ownedManifest,manifestDigest:journal.ownedManifestDigest,
    trustedRootAlias,authority:subauthority,inspectSessions:async()=>{
      const snapshot=sourceSnapshot??await sessionsSnapshot();
      if(snapshot.fingerprint!==journal.sessionFingerprint)fail('SESSION_CHANGED');
      return {...snapshot,attachmentReferencesComplete:true,retainedAttachmentIds:journal.retainedAttachmentIds,targetAttachmentIds:journal.targetAttachmentIds};
    },onCheckpoint:phase=>onCheckpoint('owned:'+phase)});}
  const receipt=()=>({version:1,scope:'companion-deletion',...identity,phase:journal.phase,manifestDigest:journal.manifestDigest,
    androidSnapshotDigest:journal.androidSnapshotDigest,retirementDigest:journal.retirementDigest,
    removedSessionIds:journal.sessionIds,workspaceIds:journal.manifest.workspaceClaims.map(row=>row.workspaceId),
    companionDeletion:journal.phase==='complete'?'complete':'incomplete',pending:journal.phase==='complete'?[]:journal.phase==='local-complete'?['android']:['sessions','owned-data','companion-store','android']});
  async function prepare() {
    await guard();if(await load()) {
      if(journal.phase!=='planning'){await verifyLocal();return receipt();}
    }else{
      validateAndroid(androidSnapshot,identity);
      sourceSnapshot=await sessionsSnapshot();
      const store=await storeJob(), prepared=await store.prepare();if(prepared.phase!=='prepared'||!prepared.manifest)fail('STORE_ALREADY_MUTATED');
      // Bind the source manifest before the SQLite reference helper asks for it.
      journal={manifestDigest:prepared.manifestDigest,ownedManifestDigest:prepared.manifestDigest};
      const references=await auditCompanionStoreAttachmentReferences({root:roots.store,operationId,manifest:prepared.manifest,manifestDigest:prepared.manifestDigest,authority:subauthority});
      const manifest=prepared.manifest, entries=new Map(sourceSnapshot.sessions.map(row=>[row.id,row]));
      const target=join(roots.store,companionId), selected=new Set();
      for(const claim of manifest.sessionClaims){
        const row=entries.get(claim.sessionId);if(!row){if(claim.bound)fail('BOUND_SESSION_MISSING');continue;}
        if(!isAbsolute(row.header.cwd)||!inside(target,await storedPaths.path(row.header.cwd)))fail('SESSION_OWNER_UNPROVEN');selected.add(row.id);
      }
      let changed=true;while(changed){changed=false;for(const row of entries.values())if(row.header.origin==='subagent'&&selected.has(row.header.parentSession)&&!selected.has(row.id)){
        if(references.sessionOwners.some(owner=>owner.sessionId===row.id&&owner.companionId!==companionId))fail('SESSION_OWNER_CONFLICT');
        if(!isAbsolute(row.header.cwd)||!inside(target,await storedPaths.path(row.header.cwd)))fail('SUBAGENT_EXTERNAL_WORKSPACE');selected.add(row.id);changed=true;
      }}
      const ownedManifest=structuredClone(manifest);ownedManifest.sessionClaims=[...selected].sort().map(sessionId=>({sessionId,bound:true,source:'offline-lineage'}));
      const retained=new Set([...references.retainedAttachmentIds,...androidReferences(androidSnapshot.retained,references.uploads)]);
      const removed=new Set([...references.targetAttachmentIds,...androidReferences(androidSnapshot.target,references.uploads)]);
      for(const row of entries.values())for(const generation of row.generations??[]){
        if(!Array.isArray(generation.attachmentIds)){if((row.generations?.length??0)>1)fail('HISTORICAL_REFERENCES_UNPROVEN');continue;}
        for(const ref of generation.attachmentIds){if(!/^sha256:[a-f0-9]{64}$/u.test(ref))fail('REFERENCE_INVALID');(selected.has(row.id)?removed:retained).add(ref);}
      }
      journal={version:1,home,compression,...identity,...(storedPaths.value?{trustedRootAlias:storedPaths.value}:{}),phase:'planning',manifest,manifestDigest:prepared.manifestDigest,
        ownedManifest,ownedManifestDigest:digest(ownedManifest),sessionIds:[...selected].sort(),sessionFingerprint:sourceSnapshot.fingerprint,
        androidSnapshotDigest:digest(androidSnapshot),retirementDigest:androidSnapshot.retirementDigest,
        retainedAttachmentIds:[...retained].sort(),targetAttachmentIds:[...removed].sort()};
      await checkpoint('planning');
    }
    const owned=await ownedJob(), prepared=await owned.prepare();if(prepared.pending.length)fail('ATTACHMENT_SCOPE_INCOMPLETE');
    const sessions=await sessionJob();if(sessions){const value=await sessions.prepare();if(value.sessionPhase!=='validated')fail('SESSION_PREPARE_INCOMPLETE');}
    await checkpoint('prepared');sourceSnapshot=undefined;return receipt();
  }
  async function commit() {
    await guard();if(!await load())fail('PREPARE_REQUIRED');if(journal.phase==='planning')await prepare();
    if(journal.phase==='prepared'){
      const currentStore=await(await storeJob()).prepare();if(currentStore.manifestDigest!==journal.manifestDigest)fail('STORE_CHANGED');
      // A complete owned plan is durable before the first irreversible removal.
      const value=await(await ownedJob()).status();if(value?.pending.length)fail('ATTACHMENT_SCOPE_INCOMPLETE');
      // Persist memory identity, personality and referenced evidence before
      // deleting their source Sessions or product message rows.
      await (await ownedJob()).retainMemory();
      const sessions=await sessionJob();if(sessions){const result=await sessions.commit();if(result.sessionPhase!=='complete')fail('SESSION_INCOMPLETE');}
      await checkpoint('sessions');
    }
    if(journal.phase==='sessions'){
      const result=await(await ownedJob()).commit();if(result.phase!=='complete'||result.pending.length)fail('OWNED_INCOMPLETE');await checkpoint('owned');
    }
    if(journal.phase==='owned'){
      const store=await storeJob(), prepared=await store.prepare();if(prepared.manifestDigest!==journal.manifestDigest)fail('STORE_CHANGED');
      const result=await store.commit();if(result.phase!=='complete'||result.hygiene?.complete!==true)fail('STORE_INCOMPLETE');await checkpoint('local-complete');
    }
    await verifyLocal();return receipt();
  }
  async function verifyLocal(){
    if(!['local-complete','complete'].includes(journal.phase))return;
    const sessions=await sessionJob();if(sessions&&(await sessions.status())?.sessionPhase!=='complete')fail('SESSION_INCOMPLETE');
    const owned=await(await ownedJob()).status();if(owned?.phase!=='complete'||owned.pending.length)fail('OWNED_INCOMPLETE');
    const store=await(await storeJob()).status();if(store?.phase!=='complete'||store.hygiene?.complete!==true)fail('STORE_INCOMPLETE');
  }
  async function acknowledgeAndroid(value){
    await guard();if(!await load()||!['local-complete','complete'].includes(journal.phase))fail('LOCAL_INCOMPLETE');await verifyLocal();
    if(!object(value)||value.version!==1||value.scope!=='android-companion-deletion'||value.complete!==true
      ||Object.keys(identity).some(key=>value[key]!==identity[key])||value.snapshotDigest!==journal.androidSnapshotDigest||value.retirementDigest!==journal.retirementDigest)fail('ANDROID_RECEIPT_INVALID');
    if(journal.phase!=='complete')await checkpoint('complete',{androidReceiptDigest:digest(value)});return receipt();
  }
  let chain=Promise.resolve();const serial=action=>{const result=chain.then(action);chain=result.catch(()=>{});return result;};
  return Object.freeze({prepare:()=>serial(prepare),commit:()=>serial(commit),recover:()=>serial(commit),acknowledgeAndroid:value=>serial(()=>acknowledgeAndroid(value)),
    status:()=>serial(async()=>{await guard();if(!await load())return null;await verifyLocal();return receipt();})});
}
