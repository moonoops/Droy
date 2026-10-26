import test from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import { readUpstreamProfile, mobileProfilePatches, applyEntryPatches } from '../../../tools/mobile_profile.mjs';
import { fileURLToPath } from 'node:url';

const packageRoot=new URL('../../dsh/node_modules/@deepseek-ai/dsh-client-ui-attachment/',import.meta.url);
const client=await readFile(new URL('lib/client.js',packageRoot),'utf8');
function excerpt(name) {
  const start=client.indexOf(`function ${name}(`);
  const end=client.indexOf('\n\t\t}',start)+'\n\t\t}'.length;
  assert.ok(start>=0 && end>start);
  return client.slice(start,end);
}

function nodes(tree) {
  if(!tree||typeof tree!=='object')return[];
  const children=tree.props?.children;
  return[tree,...(Array.isArray(children)?children:[children]).flatMap(nodes)];
}

test('mobile inherits attachment presentation and its client dependencies from upstream', async () => {
  const upstream=await readUpstreamProfile(fileURLToPath(new URL('../../dsh/node_modules/',import.meta.url)));
  const entries=applyEntryPatches(upstream,mobileProfilePatches(upstream),message=>assert.fail(message));
  for(const id of ['ui-attachment','ui-chat','ui-conversation','ui-renderer','ui-trajectory','ui-tool','locale']){
    assert.equal(entries.find(row=>row.id===id)?.name,upstream.find(row=>row.id===id)?.name,id);
    assert.notEqual(entries.find(row=>row.id===id)?.disabled,true,id);
  }
  const manifest=JSON.parse(await readFile(new URL('package.json',packageRoot),'utf8'));
  assert.deepEqual(manifest.dsh.client.inject.sort(),[
    '@deepseek-ai/dsh-client-locale','@deepseek-ai/dsh-client-ui-chat','@deepseek-ai/dsh-client-ui-conversation',
    '@deepseek-ai/dsh-client-ui-renderer','@deepseek-ai/dsh-client-ui-trajectory','@deepseek-ai/dsh-client-ui-tool'].sort());
  const host=await import(new URL('lib/index.js',packageRoot));
  assert.doesNotThrow(()=>host.apply(new Proxy({},{get(){throw new Error('presentation row must not access Host capabilities');}})));
});

test('original attachment plugin registers preview slots without another file, model or native entry point', () => {
  const registrations=[],factories=[];
  const slots={registerFactory:(spec,Component)=>factories.push({spec,Component}),register:(spec,Component)=>registrations.push({spec,Component}),inject:(_name,setup)=>setup()};
  const apply=new Function('MessageImagesContent','ComposerAttachments','MessageImages',`${excerpt('apply')};return apply;`)('images-content','composer-attachments','message-images');
  apply({slots});
  assert.deepEqual(registrations.map(entry=>entry.spec.name),[
    'conversation.input.attachments','conversation.chat.message.images','conversation.chat.user.images','conversation.trajectory.images','tool.call.images']);
  assert.equal(registrations[0].Component,'composer-attachments');
  assert.deepEqual(factories.map(entry=>entry.spec.name),['conversation.images.content']);
});

test('original composer image rail renders the same preview object and removes its original draft id', () => {
  const jsx=(type,props)=>({type,props});
  const changes=[],removals=[],effects=[];
  const attachment={id:'fictional-image',kind:'image',file:{name:'fixture.png'},previewUrl:'blob:fixture-preview'};
  const react={useState:value=>[value,next=>changes.push(next)],useRef:value=>({current:value}),useCallback:fn=>fn,useMemo:fn=>fn(),useEffect:fn=>effects.push(fn)};
  const Component=new Function('react','react_jsx_runtime','ComposerAttachments_module_css_default','_deepseek_ai_dsh_client_ui_primitives','AttachmentRail','DropOverlay','FileCard','attachmentRailLabels','lightboxLabels','dropOverlayLabels','installDocumentDropEvents',
    `${excerpt('ComposerAttachments')};return ComposerAttachments;`)(react,{jsx,jsxs:jsx,Fragment:'fragment'},{rail:'rail',imageItem:'image',thumbnail:'thumbnail',remove:'remove'},
      {IconCloseFillRegular:'close'},'attachment-rail','drop-overlay','file-card',()=>({}),()=>({}),()=>({}),()=>{throw new Error('test does not dispatch a file drop');});
  const tree=Component({attachments:[attachment],canAcceptDrop:true,onAddFiles(){throw new Error('rendering must not intake another file');},onRemoveAttachment:id=>removals.push(id),uploads:{},onRetryFile(){},t:key=>key});
  const rail=nodes(tree).find(node=>node.type==='attachment-rail');
  assert.equal(rail.props.items[0].attachment,attachment);
  const item=rail.props.renderItem(rail.props.items[0]);
  const img=nodes(item).find(node=>node.type==='img');
  assert.equal(img.props.src,attachment.previewUrl);assert.equal(img.props.alt,'fixture.png');
  nodes(item).find(node=>node.props?.className==='thumbnail').props.onClick();
  assert.equal(changes.at(-1),attachment);
  nodes(item).find(node=>node.props?.className==='remove').props.onClick();
  assert.deepEqual(removals,['fictional-image']);
});
