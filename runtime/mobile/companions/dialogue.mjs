import { createHash } from 'node:crypto';

/** Semantic paragraphs remain intact; fenced code is never split into chat fragments. */
export function splitReply(text) {
  const value = String(text ?? '').trim();
  if (!value) return [];
  const result = [];
  let current = '', fenced = false;
  for (const line of value.split('\n')) {
    if (/^\s*\x60{3}/.test(line)) fenced = !fenced;
    if (!line.trim() && !fenced) {
      if (current.trim()) result.push(current.trim());
      current = '';
    } else current += (current ? '\n' : '') + line;
  }
  if (current.trim()) result.push(current.trim());
  return result;
}

export function localTimeContext(time = new Date(), timeZone = Intl.DateTimeFormat().resolvedOptions().timeZone) {
  if (/^GMT[+-]\d{2}:\d{2}$/.test(timeZone)) timeZone = timeZone.slice(3);
  const parts = Object.fromEntries(new Intl.DateTimeFormat('en-CA', { timeZone, hourCycle: 'h23',
    year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit' })
    .formatToParts(time).filter(part => part.type !== 'literal').map(part => [part.type, part.value]));
  const localEpoch = Date.UTC(Number(parts.year), Number(parts.month) - 1, Number(parts.day),
    Number(parts.hour), Number(parts.minute), Number(parts.second));
  const offsetMinutes = Math.round((localEpoch - Math.floor(time.getTime() / 1000) * 1000) / 60000);
  const absolute = Math.abs(offsetMinutes), pad = value => String(value).padStart(2, '0');
  const utcOffset = (offsetMinutes < 0 ? '-' : '+') + pad(Math.floor(absolute / 60)) + ':' + pad(absolute % 60);
  return { currentTime: `${parts.year}-${parts.month}-${parts.day}T${parts.hour}:${parts.minute}:${parts.second}${utcOffset}`,
    timeZone, utcOffset };
}

export function isQuietTime(quietHours, time = new Date(), timeZone = Intl.DateTimeFormat().resolvedOptions().timeZone) {
  if (quietHours?.enabled === false || !quietHours?.start || !quietHours?.end || quietHours.start === quietHours.end) return false;
  const parse = value => {
    if (!/^(?:[01]\d|2[0-3]):[0-5]\d$/.test(value)) throw new Error('COMPANION_INVALID_QUIET_HOURS');
    const [hours, minutes] = value.split(':').map(Number); return hours * 60 + minutes;
  };
  const start = parse(quietHours.start), end = parse(quietHours.end), minute = parse(localTimeContext(time, timeZone).currentTime.slice(11, 16));
  return start < end ? minute >= start && minute < end : minute >= start || minute < end;
}

export function learningResult(text, messages) {
  const value = JSON.parse(String(text).replace(/^\s*\x60{3}(?:json)?\s*/i, '').replace(/\s*\x60{3}\s*$/, ''));
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('COMPANION_INVALID_LEARNING');
  const users = new Set(messages.filter(message => message.role === 'user').map(message => message.id));
  const shape = {impressions:['text'],expressions:['situation','expression'],jargon:['term','meaning'],memories:['kind','text']};
  const result = {};
  for (const [key, fields] of Object.entries(shape)) {
    if (!Array.isArray(value[key])) throw new Error('COMPANION_INVALID_LEARNING');
    result[key] = value[key].map(item => {
      if (!item || fields.some(field => typeof item[field] !== 'string' || !item[field].trim())
        || !Array.isArray(item.sourceIds) || !item.sourceIds.length || item.sourceIds.some(id => !users.has(id)))
        throw new Error('COMPANION_LEARNING_SOURCE_REQUIRED');
      if (key === 'memories' && !['fact','preference','decision','task_summary'].includes(item.kind))
        throw new Error('COMPANION_INVALID_LEARNING');
      return {...Object.fromEntries(fields.map(field => [field,item[field]])),sourceIds:[...new Set(item.sourceIds)]};
    });
  }
  return result;
}

export function mergeLearning(previous = [], incoming = []) {
  const entries = new Map(previous.map(item => [fingerprint(item), item]));
  for (const item of incoming) entries.set(fingerprint(item), item);
  return [...entries.values()];
}
function fingerprint(item) {
  return createHash('sha256').update(JSON.stringify([item.text, item.situation, item.expression,item.term,item.meaning])).digest('hex');
}

export function withoutSources(state, sourceIds) {
  const forgotten = new Set(sourceIds);
  const result = {...state,summary:'',summaryThrough:null};
  for (const name of ['expressions','jargon','jargonCandidates','impressions']) result[name] = (state[name] ?? []).filter(item =>
    !item.sourceIds?.some(id => forgotten.has(id)));
  return result;
}

export function recallQuery(messages) {
  return messages.filter(message => message.role === 'user').slice(-3).map(message => message.text || '').join(' ').trim().slice(0,512);
}

export function sharedContext(state, memories, messages) {
  return {summary:state.summary || '',impressions:state.impressions ?? [],expressions:state.expressions ?? [],
    jargon:(state.jargon ?? []).filter(item => messages.some(message => message.text?.includes(item.term))),
    memories:memories?.records ?? memories?.items ?? [],messages:messages.map(message => ({
      id:message.id,role:message.role,type:message.type,text:message.text,createdAt:message.createdAt,
      ...(message.replyTo ? {replyTo:message.replyTo}:{}),
      ...(typeof message.quoteReply === 'boolean' ? {quoteReply:message.quoteReply}:{}),
      ...(message.task ? {task:message.task}:{}),
      ...(message.attachments ? {attachments:message.attachments}:{}),
      ...(message.sourceType ? {sourceType:message.sourceType}:{}),
    }))};
}
