import { readFile, mkdir } from 'node:fs/promises';
import { join } from 'node:path';
import { randomUUID } from 'node:crypto';
import { CompanionStore } from './store.mjs';
import { CompanionRuntime } from './runtime.mjs';
import { localizedProfile, companionTemplates } from './profiles.mjs';
import { dialoguePrompts } from './dialogue-prompts.mjs';
import { requireMobileSession } from '../auth.mjs';
import { resolveMobileLocale } from '../locale.mjs';
import { isQuietTime } from './dialogue.mjs';
import { stickerCatalog } from './media.mjs';
import { StickerPackLibrary } from './sticker-packs.mjs';
import { defineTool } from '@deepseek-ai/dsh-tools';
import { installCompanionMemoryManagement } from './memory-management.mjs';
import { createCompanionEmbedder } from './embedding.mjs';

const base='/droy/companions';
export const stickerIds=stickerCatalog().map(sticker=>sticker.id);
export function companionNotificationRequest(value,profile,clock) {
  if(value.operation==='companionRead'||value.operation==='companionRemove')return value;
  return {...value,operation:'companion',quiet:isQuietTime(profile.settings.quietHours,new Date(clock.currentTime),clock.timeZone)};
}
const assetNames=new Set(['kaka.png','maimai.png','user-default.png',...companionTemplates().map(template=>template.avatar.split('/').at(-1)),...['01','02','03','04','05','06','07','08'].flatMap(id=>['wallpaper-'+id+'.webp','wallpaper-'+id+'-thumb.webp']),...stickerIds.map(id=>id+'.png')]);
const fail=code=>{throw Object.assign(new Error(code),{code});};
async function body(req,limit=262144) {
  const chunks=[]; let bytes=0;
  for await (const part of req) { bytes+=part.length; if(bytes>limit) fail('COMPANION_REQUEST_TOO_LARGE'); chunks.push(part); }
  try { return JSON.parse(Buffer.concat(chunks).toString('utf8') || '{}'); } catch { fail('COMPANION_INVALID_JSON'); }
}
function send(res,status,data) {
  res.writeHead(status,{'Content-Type':'application/json; charset=utf-8','Cache-Control':'no-store'});
  res.end(JSON.stringify(data));
}
async function whileConnected(req,res,operation) {
  const abort=new AbortController(),cancel=()=>{if(!res.writableEnded)abort.abort();};
  req.once('aborted',cancel);res.once('close',cancel);
  if(req.aborted||res.destroyed)abort.abort();
  try {return await operation(abort.signal);}
  finally {req.off('aborted',cancel);res.off('close',cancel);}
}
function attachmentUrl(id,attachmentId) {
  return base+'/'+encodeURIComponent(id)+'/attachments/'+encodeURIComponent(attachmentId);
}
async function saveUpload(req,ctx,owner,{imageOnly=false,urlForUpload}={}) {
  const input=await body(req,32*1024*1024);
  if(typeof input.base64!=='string'||typeof input.mediaType!=='string'||typeof input.name!=='string') fail('COMPANION_INVALID_INPUT');
  const data=Buffer.from(input.base64,'base64');
  if(!data.length || data.length>20*1024*1024) fail('COMPANION_REQUEST_TOO_LARGE');
  const image=input.mediaType.startsWith('image/');
  if(imageOnly&&!image)fail('COMPANION_IMAGE_REQUIRED');
  const ref=image?await ctx.attachments.saveImage({data,mediaType:input.mediaType,name:input.name})
    :await ctx.attachments.saveFile({data,name:input.name});
  const attachmentId=randomUUID();
  return {id:attachmentId,name:ref.name,mediaType:image?ref.mediaType:input.mediaType,bytes:ref.bytes,ref,
    url:urlForUpload?urlForUpload(attachmentId):attachmentUrl(owner,attachmentId)};
}
async function sendUpload(res,ctx,upload) {
  if(!upload)fail('COMPANION_NOT_FOUND');
  res.writeHead(200,{'Content-Type':upload.mediaType,'Cache-Control':'private, max-age=3600','X-Content-Type-Options':'nosniff',
    ...(!/^(image|audio)\//.test(upload.mediaType)?{'Content-Disposition':'attachment'}:{})});
  if(upload.mediaType.startsWith('image/'))res.end((await ctx.attachments.readImage(upload.ref)).data);
  else {for await(const chunk of ctx.attachments.readFileStream(upload.ref))res.write(chunk);res.end();}
}
export function createCompanionHandler({ctx,runtime,store,assets=new URL('./assets/',import.meta.url)}) {
  return async(req,res)=>{
    if(!requireMobileSession(req,res)) return;
    let path=[];
    try {
      const url=new URL(req.url,'http://127.0.0.1');
      try {path=url.pathname.slice(base.length).split('/').filter(Boolean).map(decodeURIComponent);}
      catch {fail('COMPANION_INVALID_INPUT');}
      if(path[0]==='events' && req.method==='GET') {
        res.writeHead(200,{'Content-Type':'text/event-stream','Cache-Control':'no-store','Connection':'keep-alive','X-Accel-Buffering':'no'});
        res.write(': connected\n\n');
        const stop=runtime.subscribe(value=>res.write('data: '+JSON.stringify(value)+'\n\n'));
        const heartbeat=setInterval(()=>res.write(': heartbeat\n\n'),20000);
        heartbeat.unref?.();
        res.on('close',()=>{stop();clearInterval(heartbeat);}); return;
      }
      if(path[0]==='assets' && path.length===2 && req.method==='GET') {
        if(!assetNames.has(path[1])) fail('COMPANION_NOT_FOUND');
        const data=await readFile(new URL(path[1],assets));
        res.writeHead(200,{'Content-Type':path[1].endsWith('.webp')?'image/webp':'image/png','Cache-Control':'private, max-age=86400','Content-Length':data.length});res.end(data);return;
      }
      if(path[0]==='sticker-media'&&path.length===2&&req.method==='GET') {
        if(!/^[A-Za-z0-9_.:-]{1,160}$/u.test(path[1]))fail('COMPANION_NOT_FOUND');
        const image=await runtime.stickerLibrary?.read(path[1]);
        if(!image)fail('COMPANION_NOT_FOUND');
        res.writeHead(200,{'Content-Type':image.mediaType,'Cache-Control':'private, max-age=86400',
          'X-Content-Type-Options':'nosniff','Content-Length':image.data.length});res.end(image.data);return;
      }
      if(path.length===1&&['sticker-market','sticker-search'].includes(path[0])&&req.method==='GET') {
        if([...url.searchParams.keys()].some(key=>!['query','tag','format'].includes(key)))fail('COMPANION_INVALID_INPUT');
        const result=await whileConnected(req,res,signal=>runtime.searchOnlineStickers(null,{
          query:url.searchParams.get('query')??'',tag:url.searchParams.get('tag')??'',
          format:url.searchParams.get('format')??'any',
          kind:path[0]==='sticker-market'?'packs':'stickers',signal,
        }));
        return send(res,200,result);
      }
      if(!path.length && req.method==='GET') {
        const profiles=await store.profiles(),catalog=await ctx.sessionController.modelCatalog();
        const locale=resolveMobileLocale(ctx);
        return send(res,200,{
          defaultWorkspaceId:ctx.droyInitialization?.defaultWorkspaceId??null,
          companions:profiles.map(profile=>({...localizedProfile(profile,locale),model:profile.model??ctx.agentDefaultModel?.currentSelection(),modelInherited:profile.model==null,avatarUrl:profile.avatar,unread:profile.unreadCount,
            status:runtime.status(profile.id)})),
          settings:await store.getSettings(),userProfile:await store.getUserProfile(),defaultModel:ctx.agentDefaultModel?.currentSelection()??null,models:catalog.groups.flatMap(group=>group.models.map(model=>({...model,provider:group.id,providerName:group.name??group.id}))),
          stickers:runtime.stickerLibrary?.catalog(locale)??stickerCatalog(locale).map(item=>({...item,packId:'droy-default'})),
          stickerPacks:runtime.stickerPacks?.()??[{id:'droy-default',name:dialoguePrompts[locale].defaultStickerPack,count:stickerCatalog().length,builtin:true}],
        });
      }
      if(path[0]==='templates'&&path.length===1&&req.method==='GET') {
        const locale=resolveMobileLocale(ctx),templates=[];
        for(const template of companionTemplates()) {
          const view=localizedProfile(template,locale);
          templates.push({templateId:template.templateId,name:view.name,avatarUrl:template.avatar,personality:view.personality,
            behaviorStyle:view.behaviorStyle,replyStyle:view.replyStyle,customPersonaDefaults:view.customPersonaDefaults,settings:{...structuredClone(template.settings),proactiveEnabled:true},
            chatBackground:structuredClone(template.chatBackground),suggestedName:await store.suggestName(template.templateId,locale)});
        }
        return send(res,200,{templates});
      }
      if(!path.length&&req.method==='POST') {
        const input=await body(req);
        if(!await store.getCreation(input?.requestId)&&input.model)await ctx.llm.resolveCallConfig(input.model);
        const created=await store.createProfile(input);
        await runtime.startInstance(created.profile.id);runtime.emit(null,'profile');
        const profile=created.profile;
        return send(res,created.replayed?200:201,{...created,profile:{...localizedProfile(profile,resolveMobileLocale(ctx)),
          model:profile.model??ctx.agentDefaultModel?.currentSelection(),modelInherited:profile.model==null,avatarUrl:profile.avatar,status:runtime.status(profile.id)}});
      }
      if(path[0]==='creation-drafts') {
        if(path.length===1&&req.method==='POST')return send(res,200,await store.createDraft(await body(req)));
        const draftId=path[1];
        if(path.length===2&&req.method==='GET')return send(res,200,await store.getDraft(draftId));
        if(path.length===3&&path[2]==='cancel'&&req.method==='POST') {
          const input=await body(req);if(!input||typeof input!=='object'||Array.isArray(input)||Object.keys(input).length)fail('COMPANION_INVALID_INPUT');
          return send(res,200,await store.cancelDraft(draftId));
        }
        if(path.length===3&&path[2]==='uploads'&&req.method==='POST') {
          const draft=await store.getDraft(draftId);if(draft.state!=='open')fail('COMPANION_DRAFT_CLOSED');
          const upload=await saveUpload(req,ctx,draftId,{imageOnly:true,
            urlForUpload:uploadId=>`${base}/creation-drafts/${draftId}/attachments/${uploadId}`});
          return send(res,201,await store.putDraftUpload(draftId,upload));
        }
        if(path.length===4&&path[2]==='attachments'&&req.method==='GET')return await sendUpload(res,ctx,await store.getDraftUpload(draftId,path[3]));
        return send(res,405,{error:'METHOD_NOT_ALLOWED'});
      }
      if(path[0]==='settings' && path.length===1 && req.method==='PATCH') {
        const settings=await store.updateSettings(await body(req));runtime.emit(null,'profile');return send(res,200,settings);
      }
      if(path[0]==='user-profile') {
        if(path.length===1&&req.method==='GET')return send(res,200,await store.getUserProfile());
        if(path.length===1&&req.method==='PATCH') {
          const profile=await store.updateUserProfile(await body(req));
          runtime.emit(null,'profile');return send(res,200,profile);
        }
        if(path[1]==='uploads'&&path.length===2&&req.method==='POST') {
          const upload=await saveUpload(req,ctx,'user-profile',{imageOnly:true});
          await store.putUserUpload(upload);return send(res,201,upload);
        }
        if(path[1]==='uploads'&&path.length===3&&req.method==='DELETE')return send(res,200,await store.removeUserUpload(path[2]));
        if(path[1]==='attachments'&&path.length===3&&req.method==='GET')return await sendUpload(res,ctx,await store.getUserUpload(path[2]));
        return send(res,405,{error:'METHOD_NOT_ALLOWED'});
      }
      const id=path[0],profile=await store.getProfile(id);
      if(path.length===2&&['sticker-market','sticker-search'].includes(path[1])&&req.method==='POST') {
        const input=await body(req,4096),key=path[1]==='sticker-market'?'packId':'stickerId';
        if(!input||typeof input!=='object'||Array.isArray(input)||Object.keys(input).length!==1||!Object.hasOwn(input,key))
          fail('COMPANION_INVALID_INPUT');
        const result=await whileConnected(req,res,signal=>runtime.downloadOnlineSticker(id,input,{signal}));
        return send(res,201,result);
      }
      if(path[1]==='sticker-packs'&&path.length===2&&req.method==='POST') {
        const input=await body(req,28*1024*1024+4096);
        if(!input||typeof input!=='object'||Array.isArray(input)||Object.keys(input).some(key=>!['name','base64'].includes(key))
          ||typeof input.base64!=='string'||!input.base64.length||input.base64.length%4
          ||!/^[A-Za-z0-9+/]*={0,2}$/u.test(input.base64)
          ||typeof input.name!=='string'||!input.name.trim()||Buffer.byteLength(input.name)>1024)fail('COMPANION_STICKER_PACK_INVALID');
        if(input.base64.length>Math.ceil(20*1024*1024/3)*4)fail('COMPANION_STICKER_PACK_TOO_LARGE');
        const bytes=Buffer.from(input.base64,'base64');
        if(!bytes.length||bytes.toString('base64')!==input.base64)fail('COMPANION_STICKER_PACK_INVALID');
        if(bytes.length>20*1024*1024)fail('COMPANION_STICKER_PACK_TOO_LARGE');
        const abort=new AbortController(),cancel=()=>{if(!res.writableEnded)abort.abort();};
        req.once('aborted',cancel);res.once('close',cancel);
        try {return send(res,201,{pack:await runtime.importStickerPack(id,bytes,{name:input.name,signal:abort.signal})});}
        finally {req.off('aborted',cancel);res.off('close',cancel);}
      }
      if(path[1]==='conversation' && path.length===2 && req.method==='POST') {
        const input=await body(req),conversation=await store.updateConversation(id,input);
        if(['read','hide'].includes(input.action))await runtime.syncReadNotifications(id);
        runtime.emit(id,'profile');return send(res,200,conversation);
      }
      if(path.length===1 && req.method==='PATCH') {
        const input=await body(req);
        if(input.model) await ctx.llm.resolveCallConfig(input.model);
        if(input.avatarUrl!==undefined) {
          if(input.avatar!==undefined&&input.avatar!==input.avatarUrl)fail('COMPANION_INVALID_INPUT');
          input.avatar=input.avatarUrl;delete input.avatarUrl;
        }
        if(Object.keys(input).some(key=>!['name','avatar','model','settings','personalityOverride','behaviorStyleOverride','replyStyleOverride','chatBackground'].includes(key))) fail('COMPANION_INVALID_INPUT');
        if(input.settings?.stickerPackIds!==undefined) {
          const ids=input.settings.stickerPackIds,known=new Set((runtime.stickerPacks?.()??[{id:'droy-default'}]).map(pack=>pack.id));
          if(!Array.isArray(ids)||ids.some(value=>!known.has(value)))fail('COMPANION_INVALID_INPUT');
        }
        const updated=await store.updateProfile(id,input);
        if(input.name!==undefined)await ctx.workspaceRegistry.get(updated.workspaceId)?.setTitle?.(input.name);
        if(input.settings?.memoryEnabled===false) {
          await runtime.invalidate(id,[],{all:true});
        }
        runtime.emit(id,'profile');return send(res,200,{...localizedProfile(updated,resolveMobileLocale(ctx)),avatarUrl:updated.avatar});
      }
      if(path[1]==='messages' && path.length===2) {
        if(req.method==='GET') {
          const page=await store.listMessages(id,{...(url.searchParams.get('before')?{before:url.searchParams.get('before')}:{})});
          const previewIds=page.messages.filter(message=>message.replyTo&&message.quoteReply!==false).map(message=>message.replyTo);
          const previews=await store.replyPreviews(id,previewIds);
          page.messages=page.messages.map(message=>message.replyTo&&message.quoteReply!==false
            ?{...message,replyPreview:previews[message.replyTo]??null}:message);
          return send(res,200,{...page,status:runtime.status(id)});
        }
        if(req.method==='POST') {
          const input=await body(req);
          if(input.replyTo && typeof input.replyTo==='object') input.replyTo=input.replyTo.id;
          input.attachments=await Promise.all((input.attachments??[]).map(async item=>{
            const upload=await store.getUpload(id,item.id??item.attachmentId);
            if(!upload) fail('COMPANION_ATTACHMENT_SCOPE');
            return upload;
          }));
          if(input.voice) {
            const upload=await store.getUpload(id,input.voice.attachmentId??input.voice.id);
            if(!upload || !upload.mediaType?.startsWith('audio/')) fail('COMPANION_ATTACHMENT_SCOPE');
            input.voice={...input.voice,url:upload.url,attachmentId:upload.id};
          }
          return send(res,202,await runtime.submit(id,input));
        }
        if(req.method==='DELETE')return send(res,200,await runtime.clearHistory(id));
      }
      if(path[1]==='messages'&&path.length===4&&path[3]==='transcript'&&req.method==='PATCH')
        return send(res,200,await runtime.transcribeVoice(id,path[2],(await body(req)).text));
      if(path[1]==='messages' && path.length===3 && req.method==='DELETE') {
        return send(res,200,await runtime.removeMessage(id,path[2]));
      }
      if(path[1]==='read' && req.method==='POST') {
        const input=await body(req);await runtime.markRead(id,input.messageId);return send(res,200,{ok:true});
      }
      if(path[1]==='cancel' && req.method==='POST') return send(res,200,await runtime.cancel(id,await body(req)));
      if(path[1]==='retry' && req.method==='POST') {await runtime.retry(id);return send(res,202,{accepted:true});}
      if(path[1]==='memories') {
        return send(res,403,{error:'COMPANION_MEMORY_WORKBENCH_REQUIRED',message:dialoguePrompts[resolveMobileLocale(ctx)].memoryPrivate});
      }
      if(path[1]==='uploads' && req.method==='POST') {
        const upload=await saveUpload(req,ctx,id);
        await store.putUpload(id,upload);
        return send(res,201,upload);
      }
      if(path[1]==='uploads'&&path.length===3&&req.method==='DELETE') {
        const attachmentId=path[2],current=await store.getProfile(id);
        if(current.avatar===attachmentUrl(id,attachmentId)||current.chatBackground?.type==='upload'&&current.chatBackground.id===attachmentId)fail('COMPANION_ATTACHMENT_IN_USE');
        let after='seq:0',page;
        do {page=await store.listMessages(id,{after,limit:200});after=page.nextAfter;
          if(page.messages.some(item=>item.voice?.attachmentId===attachmentId||item.attachments?.some(upload=>upload.id===attachmentId)))
            fail('COMPANION_ATTACHMENT_IN_USE');
        }while(page.hasMore);
        return send(res,200,await store.removeUpload(id,attachmentId));
      }
      if(path[1]==='attachments' && path.length===3 && req.method==='GET') {
        return await sendUpload(res,ctx,await store.getUpload(id,path[2]));
      }
      return send(res,405,{error:'METHOD_NOT_ALLOWED'});
    } catch(error) {
      if(res.destroyed)return;
      if(res.headersSent) {res.destroy(error);return;}
      const online=path.some(part=>['sticker-market','sticker-search'].includes(part));
      const locale=resolveMobileLocale(ctx),raw=online&&error.name==='TimeoutError'?'COMPANION_STICKER_ONLINE_NETWORK':error.code;
      const code=typeof raw==='string'&&(/^(?:COMPANION_[A-Z_]+)$/.test(raw)
        ||['INVALID_IMAGE','UNSUPPORTED_IMAGE_TYPE','IMAGE_TOO_LARGE','IMAGE_TOO_MANY_PIXELS','IMAGE_DIMENSION_TOO_LARGE'].includes(raw))?raw:'COMPANION_OPERATION_FAILED';
      const copy=dialoguePrompts[locale];
      const generation=req.method==='POST'&&(path[1]==='messages'&&path.length===2||path[1]==='retry');
      const message=code==='COMPANION_MODEL_REQUIRED'?copy.missingModel
        :code==='COMPANION_NAME_CONFLICT'?copy.nameConflict:code==='COMPANION_CREATION_CONFLICT'?copy.creationConflict
        :code==='COMPANION_DRAFT_CLOSED'?copy.draftClosed
        :['COMPANION_IMAGE_REQUIRED','INVALID_IMAGE','UNSUPPORTED_IMAGE_TYPE'].includes(code)?copy.invalidImage
        :['IMAGE_TOO_LARGE','IMAGE_TOO_MANY_PIXELS','IMAGE_DIMENSION_TOO_LARGE'].includes(code)?copy.imageTooLarge
        :code==='COMPANION_STICKER_PACK_INVALID'?copy.stickerPackInvalid
        :code==='COMPANION_STICKER_PACK_TOO_LARGE'?copy.stickerPackTooLarge
        :code==='COMPANION_STICKER_PACK_UNSUPPORTED'?copy.stickerPackUnsupported
        :code==='COMPANION_STICKER_ONLINE_NETWORK'?copy.stickerOnlineNetwork
        :code==='COMPANION_STICKER_ONLINE_INVALID'?copy.stickerOnlineInvalid
        :code==='COMPANION_STICKER_ONLINE_NOT_FOUND'?copy.stickerOnlineNotFound
        :code==='COMPANION_STICKER_ONLINE_CHECKSUM'?copy.stickerOnlineChecksum
        :code==='COMPANION_NOTIFICATION_REMOVE_FAILED'?copy.notificationRemovalFailed
        :code==='COMPANION_NOTIFICATION_REMOVALS_PENDING'?copy.notificationRemovalsPending
        :generation?copy.generationFailed:copy.operationFailed;
      ctx.logger.warn('COMPANION_HTTP_FAILED: '+code);
      return send(res,code==='COMPANION_OPERATION_FAILED'?500:code==='COMPANION_STICKER_ONLINE_NETWORK'?503:/NOT_FOUND/.test(code)?404:/CONFLICT|MANAGED|DRAFT_CLOSED/.test(code)?409:400,{
        error:code,message});
    }
  };
}

export function installCompanions(ctx,bridge) {
  const startup={phase:'waiting'};
  ctx.provide('droyCompanionStartup',startup);
  ctx.inject(['sessionController','workspaceRegistry','workingDirectory','agentDefaultModel','sessionPersistence','attachments','llm','droyInitialization'],async scope=>{
    let store,runtime;
    try {
      startup.phase='opening';
      const directory=join(process.env.DSH_HOME,'droy-companions');
      await mkdir(directory,{recursive:true,mode:0o700});
      store=await CompanionStore.open({directory});
      const stickerLibrary=new StickerPackLibrary(join(directory,'sticker-packs'),{builtinCatalog:stickerCatalog});await stickerLibrary.open();
      runtime=new CompanionRuntime({ctx:scope,store,directory,stickerLibrary,options:{getClock:signal=>bridge.clock(signal),embed:createCompanionEmbedder(bridge),
        requestMemoryPurge:input=>bridge.companionMemory(input)},notify:async(value,signal)=>{
        const noticeSignal=signal?AbortSignal.any([signal,AbortSignal.timeout(5000)]):undefined;
        if(value.operation==='companionRead'||value.operation==='companionRemove')return bridge.notify(value,noticeSignal);
        const profile=await store.getProfile(value.companionId);
        const clock=await runtime.clock();
        return bridge.notify(companionNotificationRequest(value,profile,clock),noticeSignal);
      }});
      startup.phase='initializing';await runtime.initialize();
      const handler=createCompanionHandler({ctx:scope,runtime,store});
      scope.webServer.register({kind:'exact',path:base,handler});
      scope.webServer.register({kind:'prefix',path:base,handler});
      scope.provide('droyCompanions',runtime);
      installCompanionMemoryManagement(scope,{list:()=>runtime.listMemorySpaces(),
        purge:(input,authorization)=>runtime.purgeMemorySpace(input,authorization),
        restore:(input,authorization)=>runtime.restoreMemorySpace(input,authorization)},{defineTool});
      await runtime.start();
      scope.effect(()=>async()=>{await runtime.close();await store.close();});
      startup.phase='ready';
      void runtime.resumeMemoryPurges().catch(error=>scope.logger.warn('COMPANION_MEMORY_PURGE_PENDING: '+(error.code??'COMPANION_OPERATION_FAILED')));
    } catch(error) {
      startup.phase='failed';startup.errorCode=/^[A-Z][A-Z0-9_]{2,80}$/.test(error.code??'')?error.code:'COMPANION_INITIALIZATION_FAILED';
      startup.errorType=error.name;
      if(runtime)await runtime.close();if(store)await store.close();throw error;
    }
  });
}
