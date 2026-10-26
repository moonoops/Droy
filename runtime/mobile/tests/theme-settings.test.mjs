import test from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {createHash} from 'node:crypto';
import vm from 'node:vm';
import {patchThemeSettings} from '../../../tools/mobile_ui_patch.mjs';

const asset='@deepseek-ai/dsh-client-ui-theme/lib/client.js';
const upstream=await readFile(new URL('../../dsh/node_modules/'+asset,import.meta.url),'utf8');
const patched=patchThemeSettings(upstream);
const specStart=upstream.indexOf('const FONT_SIZE_SPECS = {');
const fontSpecs=new Function('FONT_SIZE_FIELD',`${upstream.slice(specStart,upstream.indexOf('\n\t\t};',specStart)+5)};return FONT_SIZE_SPECS;`)('fontSize');
function excerpt(name) {
  const start=patched.indexOf(`function ${name}(`);
  const end=patched.indexOf('\n\t\t//#endregion',start);
  assert.ok(start>=0&&end>start);
  return patched.slice(start,end);
}
function nodes(tree) {
  if(!tree||typeof tree!=='object')return[];
  const children=tree.props?.children;
  return[tree,...(Array.isArray(children)?children:[children]).flatMap(nodes)];
}

function harness() {
  let expanded=false;
  const jsx=(type,props)=>({type,props});
  const react={useState:()=>[expanded,update=>{expanded=typeof update==='function'?update(expanded):update;}],useId:()=>':font-region:'};
  const css=Object.fromEntries(['row','rowText','title','desc','control','stepper','value','arrows','arrow','unit','expander'].map(name=>[name,name]));
  const primitives={Input:'original-input',IconChevronUpOutlineRegular:'up-icon',IconChevronDownOutlineRegular:'down-icon'};
  const factory=new Function('react','react_jsx_runtime','_deepseek_ai_dsh_client_ui_primitives','FontSizeRow_module_css_default',
    'FontFamilyRow_module_css_default','FontSettingsGroup_module_css_default','AppearanceRow_module_css_default','FONT_SIZE_SPECS',
    'normalizeFontFamily','CUBES','clsx',
    `${['FontFamilyRow','FontSizeRow','FontSettingsGroup','AppearanceRow'].map(excerpt).join('\n')};return {FontFamilyRow,FontSizeRow,FontSettingsGroup,AppearanceRow};`);
  return factory(react,{jsx,jsxs:jsx},primitives,css,{field:'family-field'},{group:'font-group'},
    {group:'theme-group',title:'theme-title',cubeRow:'theme-choices',themeCube:'theme-choice',selected:'selected'},fontSpecs,
    value=>value.trim(),['light','dark','system'].map(id=>({id,labelKey:id,Icon:`${id}-icon`})),(...values)=>values.filter(Boolean).join(' '));
}

test('font family markers identify the original labels and input while retaining commit and IME behavior',()=>{
  const {FontFamilyRow}=harness();
  for(const role of ['text','code','terminal']) {
    const writes=[];
    const value='Fictional Sans, sans-serif';
    const row=FontFamilyRow({role,t:key=>key,useStore:select=>select({fontFamilies:{[role]:value}}),setFontFamily:next=>writes.push(next)});
    assert.equal(row.props['data-droy-font-row'],'family');assert.equal(row.props['data-droy-font-role'],role);
    const copy=nodes(row).find(node=>node.props?.['data-droy-font-copy']);
    assert.equal(nodes(copy).find(node=>node.props?.['data-droy-font-title']).props.children,`fontFamily.${role}.title`);
    assert.equal(nodes(copy).find(node=>node.props?.['data-droy-font-description']).props.children,`fontFamily.${role}.description`);
    const input=nodes(row).find(node=>node.props?.['data-droy-font-field']);
    assert.equal(input.type,'original-input');assert.equal(input.props.defaultValue,value);
    assert.equal(input.props.maxLength,200);assert.equal(input.props.autoComplete,'off');
    let blurred=0;
    const target={value:'  Fictional Mono  ',blur(){blurred++;}};
    input.props.onKeyDown({nativeEvent:{isComposing:true,keyCode:229},key:'Enter',currentTarget:target});
    assert.equal(blurred,0);assert.deepEqual(writes,[]);
    input.props.onKeyDown({nativeEvent:{isComposing:false,keyCode:13},key:'Enter',currentTarget:target});assert.equal(blurred,1);
    input.props.onBlur({currentTarget:target});assert.equal(target.value,'Fictional Mono');assert.deepEqual(writes,['Fictional Mono']);
  }
});

test('font size markers preserve the existing step callbacks, limits and advanced toggle',()=>{
  const {FontSizeRow}=harness();
  for(const role of ['text','code','terminal']) {
    const writes=[];let toggles=0;
    const render=value=>FontSizeRow({role,t:key=>key,useStore:select=>select({fontSizes:{[role]:value}}),setFontSize:next=>writes.push(next),
      expander:{open:true,controls:'font-region',onToggle:()=>toggles++}});
    const row=render(14);
    assert.equal(row.props['data-droy-font-row'],'size');assert.equal(row.props['data-droy-font-role'],role);
    for(const marker of ['copy','title','description','control','stepper','value','arrows','unit','expander'])assert.equal(nodes(row).filter(node=>node.props?.[`data-droy-font-${marker}`]).length,1,marker);
    const increase=nodes(row).find(node=>node.props?.['data-droy-font-step']==='increase');
    const decrease=nodes(row).find(node=>node.props?.['data-droy-font-step']==='decrease');
    assert.equal(increase.type,'button');assert.equal(decrease.type,'button');
    increase.props.onClick();decrease.props.onClick();assert.deepEqual(writes,[15,13]);
    assert.equal(nodes(render(fontSpecs[role].max)).find(node=>node.props?.['data-droy-font-step']==='increase').props.disabled,true);
    assert.equal(nodes(render(fontSpecs[role].min)).find(node=>node.props?.['data-droy-font-step']==='decrease').props.disabled,true);
    const expander=nodes(row).find(node=>node.props?.['data-droy-font-expander']);
    assert.equal(expander.props['aria-expanded'],true);assert.equal(expander.props['aria-controls'],'font-region');
    expander.props.onClick();assert.equal(toggles,1);assert.deepEqual(writes,[15,13]);
  }
});

test('font disclosure retains its original mount lifecycle and expands without changing preferences',()=>{
  const Components=harness();
  const writes=[];
  const props={setFontSize:(...args)=>writes.push(args),setFontFamily:(...args)=>writes.push(args)};
  const render=()=>Components.FontSettingsGroup(props);
  let tree=render();
  assert.equal(tree.props['data-droy-font-group'],true);
  assert.equal(nodes(tree).some(node=>node.props?.['data-droy-font-advanced']),false);
  const main=nodes(tree).find(node=>node.type===Components.FontSizeRow);
  assert.equal(main.props.role,'text');assert.equal(main.props.expander.open,false);
  main.props.expander.onToggle();tree=render();
  const advanced=nodes(tree).find(node=>node.props?.['data-droy-font-advanced']);
  assert.equal(advanced.props.id,main.props.expander.controls);
  assert.deepEqual(nodes(advanced).filter(node=>node.type===Components.FontFamilyRow).map(node=>node.props.role),['text','code','terminal']);
  assert.deepEqual(nodes(advanced).filter(node=>node.type===Components.FontSizeRow).map(node=>node.props.role),['code','terminal']);
  nodes(tree).find(node=>node.type===Components.FontSizeRow).props.expander.onToggle();
  assert.equal(nodes(render()).some(node=>node.props?.['data-droy-font-advanced']),false);assert.deepEqual(writes,[]);
});

test('theme markers stay on the original three choice buttons and keep selection ownership',()=>{
  const {AppearanceRow}=harness();
  const selected=[];
  const tree=AppearanceRow({t:key=>key,setTheme:id=>selected.push(id),useStore:select=>select({preference:'dark'})});
  assert.equal(tree.props['data-droy-theme-group'],true);
  const choices=nodes(tree).find(node=>node.props?.['data-droy-theme-choices']);
  const buttons=nodes(choices).filter(node=>node.props?.['data-droy-theme-choice']);
  assert.deepEqual(buttons.map(node=>node.props['data-droy-theme-choice']),['light','dark','system']);
  for(const button of buttons) {
    assert.equal(button.type,'button');
    assert.equal(button.props['aria-pressed'],button.props['data-droy-theme-choice']==='dark');
    button.props.onClick();
  }
  assert.deepEqual(selected,['light','dark','system']);
});

test('theme display seam is syntax valid, version locked and packaged without editing upstream styles',async()=>{
  new vm.Script(patched);
  const lock=JSON.parse(await readFile(new URL('../upstream-patches.lock.json',import.meta.url),'utf8'));
  assert.equal(lock.sha256[asset],createHash('sha256').update(upstream).digest('hex'));
  const prepare=await readFile(new URL('../../../tools/prepare_dsh.mjs',import.meta.url),'utf8');
  assert.ok(prepare.includes(`await patch('${asset}', patchThemeSettings);`));
  assert.deepEqual(patched.match(/const css(?:\$\d+)? = [^\n]+/g),upstream.match(/const css(?:\$\d+)? = [^\n]+/g));
  assert.throws(()=>patchThemeSettings(patched),/already adapted/);
  assert.throws(()=>patchThemeSettings(upstream.replace('function FontFamilyRow(','function changedFontFamilyRow(')),/function FontFamilyRow/);
});
