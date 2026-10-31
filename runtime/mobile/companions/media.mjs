import { assertCompanionId } from './profiles.mjs';
import { resolveMobileLocale } from '../locale.mjs';

const stickers = [
  { id:'test-sticker-ok',zh:{label:'收到',description:'白色团子举起拇指，表示收到、认可或支持。',tags:['收到','认可','支持']},
    en:{label:'Understood',description:'A white dumpling raises a thumb to acknowledge, agree or offer support.',tags:['understood','agree','support']} },
  { id:'test-sticker-happy',zh:{label:'开心',description:'白色团子闭眼大笑、张开双臂，表达轻松的开心。',tags:['开心','好笑','庆祝']},
    en:{label:'Happy',description:'A white dumpling laughs with closed eyes and open arms, expressing cheerful delight.',tags:['happy','amused','celebrate']} },
  { id:'test-sticker-curious',zh:{label:'疑惑',description:'白色团子歪头思考，头上有问号，表示疑惑或好奇。',tags:['疑惑','好奇','思考']},
    en:{label:'Curious',description:'A white dumpling tilts its head with a question mark, expressing curiosity or puzzlement.',tags:['puzzled','curious','thinking']} },
  { id:'sticker-support',zh:{label:'支持你',description:'白色团子抱着粉色爱心微笑，表达关心、支持或安慰。',tags:['关心','支持','安慰']},
    en:{label:'Here for you',description:'A white dumpling smiles while holding a pink heart, offering care, support or comfort.',tags:['care','support','comfort']} },
  { id:'sticker-rest',zh:{label:'休息一下',description:'白色团子闭着眼睛，旁边有月亮和星星，表达困倦、休息或晚安。',tags:['困倦','休息','晚安']},
    en:{label:'Take a rest',description:'A white dumpling closes its eyes beside a moon and star, expressing sleepiness, rest or goodnight.',tags:['sleepy','rest','goodnight']} },
  { id:'sticker-surprised',zh:{label:'惊讶',description:'白色团子睁圆眼睛、张着嘴，旁边有感叹号，表达惊讶或意外。',tags:['惊讶','意外']},
    en:{label:'Surprised',description:'A white dumpling opens its eyes and mouth wide beside an exclamation mark, expressing surprise.',tags:['surprised','unexpected']} },
  { id:'sticker-sad',zh:{label:'难过',description:'白色团子低垂眉眼、流下一滴眼泪，表达难过、失落或委屈。',tags:['难过','失落','委屈']},
    en:{label:'Sad',description:'A white dumpling lowers its brows and sheds a tear, expressing sadness, disappointment or hurt.',tags:['sad','disappointed','hurt']} },
  { id:'sticker-wave',zh:{label:'挥挥手',description:'白色团子微笑着挥手，可以表示打招呼或道别。',tags:['招呼','道别','挥手']},
    en:{label:'Wave',description:'A white dumpling smiles and waves, offering a greeting or goodbye.',tags:['hello','goodbye','wave']} },
  { id:'sticker-thinking',zh:{label:'想一想',description:'白色团子托着下巴，头上有三个圆点，表示思考或斟酌。',tags:['思考','斟酌']},
    en:{label:'Thinking',description:'A white dumpling rests a hand on its chin under three dots, expressing thought or consideration.',tags:['thinking','considering']} },
];
const copy={
  zh:{format:'暂不支持解析这个文件格式。可以提供 UTF-8 文本、JSON、CSV 或 Markdown，也可以发送图片。',
    encoding:'已读取并验证文件，但它不是当前支持的 UTF-8 文本，无法可靠解释内容。可以转换为 UTF-8 后重新发送。',
    binary:'已读取并验证文件，但内容包含二进制数据，不能作为文本解释。'},
  en:{format:'This file format is not supported for reading yet. Share UTF-8 text, JSON, CSV or Markdown, or send an image.',
    encoding:'The complete file was read and verified, but it is not supported UTF-8 text, so its content cannot be interpreted reliably. Convert it to UTF-8 and share it again.',
    binary:'The complete file was read and verified, but it contains binary data and cannot be interpreted as text.'},
};
const textTypes = new Set(['application/json','application/ld+json','application/x-ndjson','application/jsonl','application/xml',
  'application/javascript','application/x-javascript','application/yaml','application/x-yaml','application/toml','application/sql']);
const textExtensions = /\.(?:txt|text|md|markdown|json|jsonl|ndjson|csv|tsv|yaml|yml|toml|xml|html?|log|ini|conf|cfg|properties|m?js|cjs|jsx|tsx?|css|scss|py|java|kts?|c|h|cpp|hpp|rs|go|sh|sql|vue|svelte)$/iu;
const binaryTypes = /^(?:audio\/|video\/|application\/(?:pdf|zip|gzip|x-7z-compressed|x-rar-compressed|vnd\.|msword))/iu;
const fail=code=>{throw Object.assign(new Error(code),{code});};
const checkSignal=signal=>signal?.throwIfAborted();
const identifier=value=>{
  if(typeof value!=='string'||!/^[A-Za-z0-9_.:-]{1,160}$/u.test(value))fail('COMPANION_INVALID_ID');
  return value;
};
const uploadId=value=>value?.id ?? value?.attachmentId;
const references=(message,id)=>message?.role==='user'&&!message.contextExcluded&&['sent','received'].includes(message.status)
  &&(message.attachments?.some(attachment=>uploadId(attachment)===id)||message.voice?.attachmentId===id);
const sameRef=(a,b)=>a?.attachmentId===b?.attachmentId&&a?.bytes===b?.bytes&&a?.name===b?.name&&a?.mediaType===b?.mediaType;
const metadata=upload=>({attachmentId:upload.id,name:upload.name,mediaType:upload.ref.mediaType ?? upload.mediaType,bytes:upload.ref.bytes});

export function stickerCatalog(locale='zh') {
  const language=locale==='en'?'en':'zh';
  return stickers.map(sticker=>({id:sticker.id,url:'/droy/companions/assets/'+sticker.id+'.png',...structuredClone(sticker[language])}));
}

/** Resolve meaning from the shipped catalog, never from message-supplied metadata. */
export function stickerContext(message, locale='zh', catalog=stickerCatalog(locale)) {
  if(typeof message?.stickerId!=='string'||!message.stickerId)return {};
  const sticker=catalog.find(item=>item.id===message.stickerId);
  return {stickerId:message.stickerId,...(sticker?{sticker:{id:sticker.id,label:sticker.label,
    description:sticker.description,tags:sticker.tags}}:{})};
}

export function stickerText(message, locale='zh', catalog=stickerCatalog(locale)) {
  const {stickerId,sticker}=stickerContext(message,locale,catalog);
  if(!stickerId)return '';
  const known=sticker?`${sticker.label}: ${sticker.description}`
    :locale==='en'?'Meaning unknown; do not infer an emotion.':'含义未知，不要推测情绪。';
  return locale==='en'?`[Sticker ${JSON.stringify(stickerId)} — ${known}]`:`[表情 ${JSON.stringify(stickerId)} — ${known}]`;
}

async function sharedUpload({store,companionId,attachmentId,signal}) {
  assertCompanionId(companionId);identifier(attachmentId);checkSignal(signal);
  const [upload,state]=await Promise.all([store.getUpload(companionId,attachmentId),store.getState(companionId)]);
  if(!upload||upload.id!==attachmentId||!upload.ref)fail('COMPANION_ATTACHMENT_SCOPE');
  const excluded=new Set(state.forgottenMessageIds ?? []),baseline=state.forgottenThrough ?? 'seq:0';
  let after=baseline;
  while(true) {
    checkSignal(signal);
    const page=await store.listMessages(companionId,{after,limit:200});
    const source=page.messages.find(message=>!excluded.has(message.id)&&references(message,attachmentId));
    if(source)return {upload,sourceId:source.id,baseline};
    if(!page.hasMore)fail('COMPANION_ATTACHMENT_SCOPE');
    after=page.nextAfter;
  }
}
async function stillShared({store,companionId,attachmentId,signal},source) {
  checkSignal(signal);
  const [upload,state,message]=await Promise.all([store.getUpload(companionId,attachmentId),store.getState(companionId),store.getMessage(companionId,source.sourceId)]);
  if(!upload||!sameRef(upload.ref,source.upload.ref)||(state.forgottenThrough ?? 'seq:0')!==source.baseline
    ||state.forgottenMessageIds?.includes(source.sourceId)||!references(message,attachmentId))fail('COMPANION_ATTACHMENT_SCOPE');
  checkSignal(signal);
}
function isImage(upload) { return /^image\/(?:png|jpeg|webp|gif)$/u.test(upload.ref.mediaType ?? ''); }
function isText(upload) {
  const mediaType=upload.mediaType.split(';',1)[0].trim().toLocaleLowerCase('und');
  return !binaryTypes.test(mediaType)&&(mediaType.startsWith('text/')||textTypes.has(mediaType)||/\+(?:json|xml)$/u.test(mediaType)||textExtensions.test(upload.name));
}

/** Reads only opaque references already sent in this companion's IM. Draining
 * a file stream is intentional: its provider verifies the full digest at EOF. */
export async function readSharedAttachment(input) {
  const {ctx,signal,maxBytes=65536}=input;
  if(!Number.isSafeInteger(maxBytes)||maxBytes<1||maxBytes>1048576)fail('COMPANION_INVALID_INPUT');
  const source=await sharedUpload(input),{upload}=source,details=metadata(upload),language=input.locale==='en'?'en':input.locale==='zh'?'zh':resolveMobileLocale(ctx);
  if(isImage(upload)) {
    await ctx.attachments.readImage(upload.ref,signal);
    await stillShared(input,source);
    return {kind:'image',...details,attachment:upload.ref,sourceMessageId:source.sourceId,integrityVerified:true};
  }
  if(!isText(upload)) {
    await stillShared(input,source);
    return {kind:'unsupported',...details,reason:'format',message:copy[language].format,integrityVerified:false};
  }
  const decoder=new TextDecoder('utf-8',{fatal:true}),prefix=[];
  let readBytes=0,keptBytes=0,invalidEncoding=false,binary=false;
  for await(const chunk of ctx.attachments.readFileStream(upload.ref,signal)) {
    checkSignal(signal);
    if(!(chunk instanceof Uint8Array))fail('COMPANION_ATTACHMENT_INVALID');
    readBytes+=chunk.byteLength;
    if(keptBytes<maxBytes) {
      const retained=chunk.subarray(0,maxBytes-keptBytes);
      prefix.push(Buffer.from(retained));keptBytes+=retained.byteLength;
    }
    if(!invalidEncoding) {
      try { if(decoder.decode(chunk,{stream:true}).includes('\0'))binary=true; }
      catch(error) { if(!(error instanceof TypeError))throw error;invalidEncoding=true; }
    }
  }
  if(!invalidEncoding) {
    try { if(decoder.decode().includes('\0'))binary=true; }
    catch(error) { if(!(error instanceof TypeError))throw error;invalidEncoding=true; }
  }
  checkSignal(signal);
  if(readBytes!==upload.ref.bytes)fail('ATTACHMENT_CORRUPT');
  await stillShared(input,source);
  if(invalidEncoding||binary) {
    const reason=invalidEncoding?'encoding':'binary';
    return {kind:'unsupported',...details,reason,message:copy[language][reason],readBytes,integrityVerified:true};
  }
  const truncated=readBytes>keptBytes;
  // A truncated UTF-8 code point stays buffered instead of becoming a fake
  // replacement character; the full decoder above has validated the suffix.
  const text=new TextDecoder('utf-8',{fatal:true}).decode(Buffer.concat(prefix),{stream:truncated});
  return {kind:'text',...details,text,truncated,readBytes,returnedBytes:Buffer.byteLength(text),
    sourceMessageId:source.sourceId,integrityVerified:true};
}

export async function replyAttachment(input) {
  const source=await sharedUpload(input),{upload}=source;
  if(!isImage(upload))fail('COMPANION_IMAGE_REQUIRED');
  await input.ctx.attachments.readImage(upload.ref,input.signal);
  await stillShared(input,source);
  return {id:upload.id,name:upload.name,mediaType:upload.ref.mediaType,bytes:upload.ref.bytes,ref:upload.ref,
    url:'/droy/companions/'+encodeURIComponent(input.companionId)+'/attachments/'+encodeURIComponent(upload.id)};
}
