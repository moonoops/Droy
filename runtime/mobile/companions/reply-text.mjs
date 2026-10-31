// A past replyer prompt put transport metadata in assistant examples. Strip only
// that exact leading envelope; ordinary brackets, code, and user text stay intact.
const uuid = '[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}';
const timestamp = '\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?(?:Z|[+-]\\d{2}:\\d{2})';
const envelope = new RegExp('^\\[msg_id=' + uuid + ' ' + timestamp + '(?: (?:reply_target|quote)=' + uuid + ')?\\]\\r?\\n', 'i');
export function companionReplyText(text) {
  return typeof text === 'string' ? text.replace(envelope, '') : text;
}
export function companionVisibleMessage(message) {
  return message?.role === 'assistant' && typeof message.text === 'string'
    ? { ...message, text: companionReplyText(message.text) } : message;
}
