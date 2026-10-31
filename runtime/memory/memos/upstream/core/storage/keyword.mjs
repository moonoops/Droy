// Generated from MemTensor/MemOS 64388b50e2a15528bcdf032e65a1ac792bcf63ee: core/storage/keyword.ts
const PUNCT = /["“”'’(){}\[\]<>«»《》【】（）\\^~!@#$%&*+/=:;,.，。、；：!?？]+/g;
const CJK_RUN = /[\u4e00-\u9fff\u3400-\u4dbf\uF900-\uFAFF]+/g;
const TRIGRAM_MIN = 3;
const PATTERN_MIN = 2;
const MAX_FTS_TOKENS = 12;
const MAX_PATTERN_TERMS = 16;
const MAX_EXACT_IDENTIFIERS = 8;
const MAX_EXACT_IDENTIFIER_CHARS = 128;
const EXACT_IDENTIFIER_TOKEN = /[A-Za-z0-9_:-]{12,}/g;
const CJK_PATTERN_NOISE = /[我你他她它的了呢吗么还记得是有想请问谁哪帮]/u;
export function extractExactIdentifiers(query) {
    const tokens = String(query ?? "").match(EXACT_IDENTIFIER_TOKEN) ?? [];
    const identifiers = new Set();
    for (const token of tokens){
        const normalized = normalizeIdentifierText(token);
        if (normalized.length > MAX_EXACT_IDENTIFIER_CHARS) continue;
        const hasIdentifierShape = /[_:-]/.test(normalized) || /\d/.test(normalized);
        const hasEnoughEntropy = /[a-z]/.test(normalized) && normalized.length >= 16;
        if (!hasIdentifierShape || !hasEnoughEntropy) continue;
        identifiers.add(normalized);
        if (identifiers.size >= MAX_EXACT_IDENTIFIERS) break;
    }
    return [
        ...identifiers
    ];
}
export function normalizeIdentifierText(text) {
    return String(text ?? "").normalize("NFKC").toLowerCase().trim();
}
export function prepareFtsMatch(query, opts = {}) {
    if (!query) return null;
    const cleaned = String(query).replace(PUNCT, " ").trim();
    if (!cleaned) return null;
    const tokenizer = opts.tokenizer ?? "trigram";
    const rough = cleaned.split(/\s+/).filter(Boolean);
    const expanded = [];
    for (const tok of rough){
        if (tokenizer === "cjk") {
            expanded.push(...expandCjkToken(tok));
            continue;
        }
        const cjkRuns = tok.match(CJK_RUN) ?? [];
        let stripped = tok;
        for (const r of cjkRuns){
            stripped = stripped.replace(r, " ");
            if (r.length >= TRIGRAM_MIN) expanded.push(r);
        }
        for (const sub of stripped.split(/\s+/).filter(Boolean)){
            if (sub.length >= TRIGRAM_MIN) expanded.push(sub);
        }
    }
    if (expanded.length === 0) return null;
    const limited = Array.from(new Set(expanded)).slice(0, MAX_FTS_TOKENS);
    const safe = limited.map((t)=>`"${t.replace(/"/g, '""')}"`);
    return safe.join(" ");
}
function expandCjkToken(token) {
    const out = [];
    const cjkRuns = token.match(CJK_RUN) ?? [];
    let stripped = token;
    for (const run of cjkRuns){
        stripped = stripped.replace(run, " ");
        if (run.length === 1) {
            out.push(run);
            continue;
        }
        for(let i = 0; i <= run.length - PATTERN_MIN; i++){
            out.push(run.slice(i, i + PATTERN_MIN));
        }
    }
    for (const sub of stripped.split(/\s+/).filter(Boolean)){
        if (sub.length >= PATTERN_MIN) out.push(sub);
    }
    const hasNonCjk = /[^\u4e00-\u9fff]/.test(token);
    const hasCjk = /[\u4e00-\u9fff\u3400-\u4dbf\uF900-\uFAFF]/.test(token);
    if (token.length >= PATTERN_MIN && hasNonCjk && hasCjk) {
        out.push(token);
    }
    return out;
}
export function extractPatternTerms(query) {
    if (!query) return [];
    const cleaned = String(query).replace(PUNCT, " ");
    const out = new Set();
    for (const tok of cleaned.split(/\s+/).filter(Boolean)){
        if (tok.length === PATTERN_MIN && /[^\u4e00-\u9fff]/.test(tok)) {
            out.add(tok.toLowerCase());
        }
    }
    const runs = cleaned.match(CJK_RUN) ?? [];
    for (const run of runs){
        if (run.length < PATTERN_MIN) continue;
        if (run.length === PATTERN_MIN) {
            out.add(run);
            continue;
        }
        for(let i = 0; i <= run.length - PATTERN_MIN; i++){
            const term = run.slice(i, i + PATTERN_MIN);
            if (!CJK_PATTERN_NOISE.test(term)) out.add(term);
        }
    }
    return Array.from(out).slice(0, MAX_PATTERN_TERMS);
}
export function reciprocalRankScore(rank0, k = 60) {
    return 1 / (k + rank0 + 1);
}
