// Fixture model outputs cite their supplied fictional messages. Explicitly
// malformed evidence stays untouched so boundary tests exercise rejection.
export function withMemoryEvidence(raw, messages) {
  let result;
  try { result=typeof raw==='string'?JSON.parse(raw):structuredClone(raw); }
  catch { return raw; }
  if(!result||typeof result!=='object'||Array.isArray(result))return raw;
  for(const field of ['memories','events'])if(Array.isArray(result[field])) {
    result[field]=result[field].map(item=>{
      if(!item||typeof item!=='object'||!Array.isArray(item.sourceIds))return item;
      return {...item,claimType:item.claimType??'statement',
        ...(item.action==='revise'?{revisionReason:item.revisionReason??'explicit_correction'}:{}),
        evidence:item.evidence===undefined?item.sourceIds.map(sourceId=>({sourceId,quote:messages.find(message=>message.id===sourceId)?.text??'Fictional missing source'})):item.evidence};
    });
  }
  return JSON.stringify(result);
}
