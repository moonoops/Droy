import { readFile,writeFile,copyFile,chmod,mkdir,lstat,readdir,realpath } from 'node:fs/promises';
import { join,resolve } from 'node:path';
import { pathToFileURL,fileURLToPath } from 'node:url';

const allowed=new Set(['agent-default-model','llm-pi-ai','llm-deepseek','llm-deepseek-account','locale']);
const fail=code=>{throw Object.assign(new Error(code),{code});};
const regular=async path=>{try{const stat=await lstat(path);if(!stat.isFile()||stat.isSymbolicLink())fail('EVALUATION_CONFIG_NOT_REGULAR');return true;}catch(error){if(error.code==='ENOENT')return false;throw error;}};

export async function prepareEvaluationHome({runtime,original,home,directory}) {
  if(resolve(original)===resolve(home))fail('EVALUATION_HOME_NOT_ISOLATED');
  const files=await readdir(home);
  if(files.some(name=>name!=='.companion-evaluation-owner'))fail('EVALUATION_HOME_NOT_EMPTY');
  const yaml=await import(pathToFileURL(join(runtime,'node_modules/yaml/dist/index.js')));
  const parse=async path=>{
    if(!await regular(path))return [];
    const document=yaml.parseDocument(await readFile(path,'utf8'),{logLevel:'silent',
      customTags:[{tag:'tag:yaml.org,2002:js',resolve:value=>({__evaluationDynamicJs:value})}]});
    if(document.errors.length||document.warnings.length)fail('EVALUATION_CONFIG_PARSE');
    return document.toJSON();
  };
  const shipped=await parse(join(runtime,'node_modules/@deepseek-ai/dsh-base/cordis.patch.yml'));
  let selection={};
  const shippedDefault=shipped.flatMap(row=>row.insert??[]).find(row=>row.id==='agent-default-model');
  if(shippedDefault?.config)selection={...shippedDefault.config};
  const rows=[];
  for(const path of [join(original,'profiles/mobile/cordis.patch.yml'),join(original,'cordis.patch.yml')]) {
    const patch=await parse(path);if(!Array.isArray(patch))fail('EVALUATION_PATCH_SHAPE');
    for(const row of patch.flatMap(row=>row.insert??[row])) {
      if(!allowed.has(row.id))continue;
      if(!row.config||typeof row.config!=='object'||Array.isArray(row.config))fail('EVALUATION_MODEL_CONFIG_SHAPE');
      if(JSON.stringify(row.config).includes('__evaluationDynamicJs'))fail('EVALUATION_DYNAMIC_MODEL_CONFIG');
      rows.push({id:row.id,config:row.config,...(typeof row.disabled==='boolean'?{disabled:row.disabled}:{})});
      // Cordis replaces an entry's config; omitted reasoning effort must not
      // survive from an earlier layer. AgentDefaultModel has no route defaults.
      if(row.id==='agent-default-model')selection={...row.config};
    }
  }
  if(typeof selection.provider!=='string'||!selection.provider||typeof selection.model!=='string'||!selection.model)
    fail('EVALUATION_CURRENT_MODEL_REQUIRED');
  if(selection.reasoningEffort!==undefined&&typeof selection.reasoningEffort!=='string')fail('EVALUATION_MODEL_CONFIG_SHAPE');
  selection={provider:selection.provider,model:selection.model,
    ...(selection.reasoningEffort===undefined?{}:{reasoningEffort:selection.reasoningEffort})};
  for(const name of ['.credentials.yaml','.env']) {
    const source=join(original,name);
    if(await regular(source)){await copyFile(source,join(home,name));await chmod(join(home,name),0o600);}
  }
  const profile=join(home,'profiles/mobile');await mkdir(profile,{recursive:true});
  rows.push({insert:[{id:'companion-current-model-evaluation',name:join(directory,'companion-model-evaluation-plugin.mjs')}]});
  await writeFile(join(profile,'cordis.patch.yml'),yaml.stringify(rows),{mode:0o600});
  const core=await readFile(join(directory,'companion-model-evaluation-core.mjs'),'utf8');
  await writeFile(join(directory,'evaluation-core.mjs'),core.replaceAll("'../runtime/mobile/","'"+pathToFileURL(join(runtime,'mobile')).href+'/'));
  await writeFile(join(directory,'expected-model.json'),JSON.stringify(selection));
}
// Android's /data/user/0 alias may differ from Node's canonical import URL.
// Compare filesystem identity so an executed preparer cannot silently do nothing.
if(process.argv[1]&&await realpath(fileURLToPath(import.meta.url))===await realpath(resolve(process.argv[1]))) {
  const [runtime,original,home,directory]=process.argv.slice(2);
  try{await prepareEvaluationHome({runtime,original,home,directory});}
  catch(error){process.stderr.write(String(error.code??'EVALUATION_PREPARATION_FAILED')+'\n');process.exitCode=1;}
}
