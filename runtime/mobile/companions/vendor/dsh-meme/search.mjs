// Adapted from yyh-001/dsh-meme memes.js at
// 8992a2e8daa169ae85cf762e06d02d0ea96332bb; see LICENSE.
// The upstream keyword ranking and Chinese bigram fallback are retained.
export const moodTags = Object.freeze({
  happy: ['happy', 'like', 'meow', 'givemoney', 'color'],
  angry: ['angry', 'fool', 'baka'], sad: ['sad', 'sigh'], shy: ['shy'],
  confused: ['confused', 'surprised', 'see'],
  daily: ['daily', 'sleep', 'morning', 'work', 'cpu', 'reply'],
});

export function keywordTokens(query) {
  const raw = String(query || '').trim().toLowerCase();
  return raw ? [...new Set(raw.split(/[\s,，、;；/|]+/).filter(Boolean))] : [];
}
function biGrams(tokens) {
  const out = [];
  for (const token of tokens) {
    if (token.length < 3 || !/[\u4e00-\u9fff]/.test(token)) continue;
    for (let i = 0; i + 2 <= token.length; i++) out.push(token.slice(i, i + 2));
  }
  return [...new Set(out)];
}
const rowHay = row => `${row.tag || ''} ${row.caption || ''} ${row.keywords || ''} ${row.file_name || ''}`.toLowerCase();
export function sampleRows(rows, limit) {
  const copy = rows.slice();
  for (let i = copy.length - 1; i > 0; i--) {
    const j = Math.floor(Math.random() * (i + 1));
    [copy[i], copy[j]] = [copy[j], copy[i]];
  }
  return copy.slice(0, limit);
}
export function searchRows(rows, query, limit = 8) {
  const tokens = keywordTokens(query);
  if (!tokens.length) return [];
  const scan = words => rows.map(row => ({ row, score: words.reduce((n, word) => n + Number(rowHay(row).includes(word)), 0) }))
    .filter(hit => hit.score > 0);
  let hits = scan(tokens);
  if (!hits.length) hits = scan(biGrams(tokens));
  if (!hits.length) return [];
  const best = Math.max(...hits.map(hit => hit.score));
  return sampleRows(hits.filter(hit => hit.score === best).map(hit => hit.row), limit);
}
