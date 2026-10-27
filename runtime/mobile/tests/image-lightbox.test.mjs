import test from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {createHash} from 'node:crypto';
import {patchImageLightbox} from '../../../tools/mobile_ui_patch.mjs';

const asset='@deepseek-ai/dsh-web-frontend/dist/assets/index-BXV-oxvX.js';
const upstream=await readFile(new URL('../../dsh/node_modules/'+asset,import.meta.url),'utf8');
const patched=patchImageLightbox(upstream);
const component=patched.slice(patched.indexOf('function lw('),patched.indexOf('function QF(',patched.indexOf('function lw(')));

function nodes(tree) {
  if(!tree||typeof tree!=='object')return[];
  const children=tree.props?.children;
  return[tree,...(Array.isArray(children)?children:[children]).flatMap(nodes)];
}

function mount({mobile=true,native=false,id='preview-1'}={}) {
  const effects=[],listeners=new Map(),registrations=[],opened=[],restored=[],disposals=[];
  class Element {constructor(name){this.name=name;this.isConnected=true;this.focusCalls=0;}focus(){this.focusCalls++;document.activeElement=this;}}
  const previous=new Element('previous'),fallback=new Element('main-menu');
  const document={documentElement:{hasAttribute:name=>mobile&&name==='data-droy-mobile'},body:{name:'body'},activeElement:previous,
    querySelector:selector=>selector==='[data-droy-menu]'?fallback:null};
  const window={
    DroyNative:native?{}:undefined,
    DroyTouch:{bindMenu:(owner,options)=>{registrations.push({owner,options});return()=>disposals.push(owner);},menuOpened:owner=>opened.push(owner),
      restoreFocus:(from,to,owner)=>restored.push({from,to,owner})},
    addEventListener:(type,callback)=>{if(!listeners.has(type))listeners.set(type,new Set());listeners.get(type).add(callback);},
    removeEventListener:(type,callback)=>listeners.get(type)?.delete(callback)
  };
  const react={useRef:value=>({current:value}),useId:()=>id,useEffect:effect=>effects.push(effect)};
  const jsx=(type,props)=>({type,props});
  const portals=[];
  const make=new Function('C','Sn','d','Cs','ru','window','document','HTMLElement',`${component};return lw;`);
  const Lightbox=make(react,{createPortal:(tree,target)=>{portals.push(target);return tree;}},{jsx,jsxs:jsx},
    {backdrop:'backdrop',stage:'stage',mask:'mask',image:'image',close:'close'},'close-icon',window,document,Element);
  let closed=0;
  const close=()=>{closed++;};
  const tree=Lightbox({src:'blob:fictional-preview',alt:'fixture.png',labels:{dialog:'preview',close:'close'},onClose:close});
  const root=new Element('original-portal-root'),closeButton=new Element('original-close');
  tree.props.ref.current=root;
  nodes(tree).find(node=>node.type==='button').props.ref.current=closeButton;
  const cleanups=effects.map(effect=>effect()).filter(Boolean);
  return {tree,root,closeButton,previous,fallback,window,document,portals,listeners,registrations,opened,restored,disposals,
    get closed(){return closed;},unmount(){root.isConnected=false;tree.props.ref.current=null;for(const cleanup of cleanups)cleanup();}};
}

test('mobile image preview keeps the shared original portal, labels, image and close controls',()=>{
  const view=mount();
  assert.deepEqual(view.portals,[view.document.body]);
  assert.equal(view.tree.props.role,'dialog');assert.equal(view.tree.props['aria-modal'],'true');
  assert.equal(view.tree.props['data-droy-attachment-lightbox'],true);
  const stage=nodes(view.tree).find(node=>node.props?.className==='stage');
  const image=nodes(view.tree).find(node=>node.type==='img');
  const close=nodes(view.tree).find(node=>node.type==='button');
  assert.equal(stage.props['data-droy-attachment-stage'],true);
  assert.equal(image.props['data-droy-attachment-image'],true);
  assert.equal(image.props.src,'blob:fictional-preview');assert.equal(image.props.alt,'fixture.png');
  assert.equal(close.props['data-droy-attachment-close'],true);assert.equal(close.props['aria-label'],'close');
  assert.equal(view.closeButton.focusCalls,1);
  close.props.onClick();assert.equal(view.closed,1);
  const escape={key:'Escape',stopPropagation(){this.stopped=true;}};
  for(const listener of view.listeners.get('keydown'))listener(escape);
  assert.equal(escape.stopped,true);assert.equal(view.closed,2);
  view.unmount();
});

test('image preview registers its actual root and original close callback, including late controller readiness',()=>{
  const view=mount({id:'one'});
  const owner=view.registrations[0];
  assert.equal(owner.owner,'attachment-lightbox:one');
  assert.equal(owner.options.getElement(),view.root);assert.equal(owner.options.isOpen(),true);
  owner.options.close();assert.equal(view.closed,1);
  for(const listener of view.listeners.get('droy-touch-ready'))listener();
  assert.equal(view.registrations.length,2);assert.deepEqual(view.disposals,['attachment-lightbox:one']);
  assert.deepEqual(view.opened,['attachment-lightbox:one','attachment-lightbox:one']);
  view.unmount();
  assert.equal(owner.options.isOpen(),false);
  assert.equal(view.listeners.get('droy-touch-ready').size,0);assert.equal(view.listeners.get('keydown').size,0);
  assert.deepEqual(view.disposals,['attachment-lightbox:one','attachment-lightbox:one']);
  assert.deepEqual(view.restored,[{from:view.previous,to:view.fallback,owner:view.root}]);
});

test('desktop image preview preserves upstream focus and event ownership without mobile presentation markers',()=>{
  const view=mount({mobile:false});
  assert.equal(view.registrations.length,0);assert.equal(view.tree.props['data-droy-attachment-lightbox'],undefined);
  for(const node of nodes(view.tree))for(const key of Object.keys(node.props??{}))if(key.startsWith('data-droy-attachment-'))assert.equal(node.props[key],undefined);
  view.unmount();
  assert.equal(view.previous.focusCalls,1);assert.equal(view.restored.length,0);
  const native=mount({mobile:false,native:true});assert.equal(native.registrations.length,1);native.unmount();
});

test('shared image component patch is locked, unique, and included in packaging',async()=>{
  const lock=JSON.parse(await readFile(new URL('../upstream-patches.lock.json',import.meta.url),'utf8'));
  assert.equal(lock.sha256[asset],createHash('sha256').update(upstream).digest('hex'));
  assert.equal(patched.split('data-droy-attachment-lightbox').length,2);
  assert.throws(()=>patchImageLightbox(patched),/already adapted/);
  assert.throws(()=>patchImageLightbox(upstream.replace('function lw({src:e,alt:t,labels:r,onClose:i}){','function upstreamChanged(){')),/shared image lightbox/);
  const prepare=await readFile(new URL('../../../tools/prepare_dsh.mjs',import.meta.url),'utf8');
  assert.ok(prepare.includes(`await patch('${asset}', patchImageLightbox);`));
});
