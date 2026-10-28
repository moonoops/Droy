import { readFile, mkdir } from 'node:fs/promises';
import { join } from 'node:path';
import { randomUUID } from 'node:crypto';
import { CompanionStore } from './store.mjs';
import { CompanionRuntime } from './runtime.mjs';
import { localizedProfile } from './profiles.mjs';
import { dialoguePrompts } from './dialogue-prompts.mjs';
import { requireMobileSession } from '../auth.mjs';
import { resolveMobileLocale } from '../locale.mjs';
import { isQuietTime } from './dialogue.mjs';
import { stickerCatalog } from './media.mjs';

const base='/droy/companions';
export const stickerIds=['test-sticker-ok','test-sticker-happy','test-sticker-curious'];
const assetNames=new Set(['kaka.png','maimai.png','user-default.png',...['01','02','03','04','05'].flatMap(id=>['wallpaper-'+id+'.webp','wallpaper-'+id+'-thumb.webp']),...stickerIds.map(id=>id+'.png')]);
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
function attachmentUrl(id,attachmentId) {
  return base+'/'+encodeURIComponent(id)+'/attachments/'+encodeURIComponent(attachmentId);
}
async function saveUpload(req,ctx,owner,{imageOnly=false}={}) {
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
    url:attachmentUrl(owner,attachmentId)};
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
    const url=new URL(req.url,'http://127.0.0.1'),path=url.pathname.slice(base.length).split('/').filter(Boolean).map(decodeURIComponent);
    try {
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
      if(!path.length && req.method==='GET') {
        const profiles=await store.profiles(),catalog=await ctx.sessionController.modelCatalog();
        const locale=resolveMobileLocale(ctx);
        return send(res,200,{
          defaultWorkspaceId:ctx.droyInitialization?.defaultWorkspaceId??null,
          companions:profiles.map(profile=>({...localizedProfile(profile,locale),model:profile.model??ctx.agentDefaultModel?.currentSelection(),avatarUrl:profile.avatar,unread:profile.unreadCount,
            status:{phase:runtime.state(profile.id).phase}})),
          settings:await store.getSettings(),userProfile:await store.getUserProfile(),models:catalog.groups.flatMap(group=>group.models.map(model=>({...model,provider:group.id}))),
          stickers:stickerCatalog(locale),
        });
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
      if(path[1]==='conversation' && path.length===2 && req.method==='POST') {
        const conversation=await store.updateConversation(id,await body(req));
        runtime.emit(id,'profile');return send(res,200,conversation);
      }
      if(path.length===1 && req.method==='PATCH') {
        const input=await body(req);
        if(input.model) await ctx.llm.resolveCallConfig(input.model);
        if(input.avatarUrl!==undefined) {
          const upload=await store.getUpload(id,String(input.avatarUrl).split('/').at(-1));
          const valid=upload?.url===input.avatarUrl&&upload.mediaType?.startsWith('image/');
          if(!valid && ![base+'/assets/kaka.png',base+'/assets/maimai.png'].includes(input.avatarUrl)) fail('COMPANION_ATTACHMENT_SCOPE');
          input.avatar=input.avatarUrl;delete input.avatarUrl;
        }
        if(Object.keys(input).some(key=>!['name','avatar','model','settings','personalityOverride','chatBackground'].includes(key))) fail('COMPANION_INVALID_INPUT');
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
          const state=runtime.state(id);
          return send(res,200,{...page,status:{phase:state.phase,error:state.error?runtime.copy().generationFailed:undefined}});
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
        const input=await body(req);await store.markRead(id,input.messageId);runtime.emit(id,'read');return send(res,200,{ok:true});
      }
      if(path[1]==='cancel' && req.method==='POST') return send(res,200,await runtime.cancel(id,await body(req)));
      if(path[1]==='retry' && req.method==='POST') {await runtime.retry(id);return send(res,202,{accepted:true});}
      if(path[1]==='memories') {
        const memory=await runtime.memory(id);
        if(req.method==='GET') {
          const result=await memory.query(url.searchParams.get('q')?{text:url.searchParams.get('q')}:{});
          return send(res,200,{...result,records:result.items});
        }
        const input=await body(req);
        if(req.method==='POST') return send(res,200,await memory.remember(input));
        if(req.method==='PATCH') return send(res,200,await runtime.reviseMemory(id,input));
        if(req.method==='DELETE') {
          if(input.all===true)return send(res,200,await runtime.resetMemories(id));
          const record=await memory.get(input.recordId);if(!record) fail('COMPANION_NOT_FOUND');
          return send(res,200,await runtime.forget(id,{recordId:input.recordId,expectedRevisionId:input.expectedRevisionId??record.revisionId}));
        }
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
      if(res.headersSent) {res.destroy(error);return;}
      const locale=resolveMobileLocale(ctx),code=error.code??error.message;
      const copy=dialoguePrompts[locale];
      const generation=req.method==='POST'&&(path[1]==='messages'&&path.length===2||path[1]==='retry');
      const message=code==='COMPANION_MODEL_REQUIRED'?copy.missingModel
        :['COMPANION_IMAGE_REQUIRED','INVALID_IMAGE','UNSUPPORTED_IMAGE_TYPE'].includes(code)?copy.invalidImage
        :['IMAGE_TOO_LARGE','IMAGE_TOO_MANY_PIXELS','IMAGE_DIMENSION_TOO_LARGE'].includes(code)?copy.imageTooLarge
        :generation?copy.generationFailed:copy.operationFailed;
      ctx.logger.warn('COMPANION_HTTP_FAILED: '+code);
      return send(res,/NOT_FOUND/.test(code)?404:/CONFLICT|MANAGED/.test(code)?409:400,{
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
      runtime=new CompanionRuntime({ctx:scope,store,directory,options:{getClock:signal=>bridge.clock(signal)},notify:async value=>{
        const profile=await store.getProfile(value.companionId);
        const clock=await runtime.clock();
        if(isQuietTime(profile.settings.quietHours,new Date(clock.currentTime),clock.timeZone))return {status:'suppressed'};
        return bridge.notify({operation:'companion',companionId:value.companionId,name:value.name,
          messageId:value.messageId,text:[...value.text].slice(0,180).join('')});
      }});
      startup.phase='initializing';await runtime.initialize();
      const handler=createCompanionHandler({ctx:scope,runtime,store});
      scope.webServer.register({kind:'exact',path:base,handler});
      scope.webServer.register({kind:'prefix',path:base,handler});
      scope.provide('droyCompanions',runtime);
      await runtime.start();
      scope.effect(()=>async()=>{await runtime.close();await store.close();});
      startup.phase='ready';
    } catch(error) {
      startup.phase='failed';startup.errorCode=/^[A-Z][A-Z0-9_]{2,80}$/.test(error.code??'')?error.code:'COMPANION_INITIALIZATION_FAILED';
      startup.errorType=error.name;
      if(runtime)await runtime.close();if(store)await store.close();throw error;
    }
  });
}
