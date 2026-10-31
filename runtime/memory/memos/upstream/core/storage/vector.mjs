// Generated from MemTensor/MemOS 64388b50e2a15528bcdf032e65a1ac792bcf63ee: core/storage/vector.ts
import { rootLogger } from "../logger/index.mjs";
const log = rootLogger.child({
    channel: "storage.vector"
});
const FLOAT32_BYTES = 4;
export function encodeVector(vec) {
    if (!(vec instanceof Float32Array)) {
        throw new Error("[storage.vector] encodeVector expects Float32Array");
    }
    return Buffer.from(vec.buffer, vec.byteOffset, vec.byteLength);
}
export function decodeVector(buf) {
    if (!buf) return null;
    if (buf.byteLength === 0) return new Float32Array(0);
    if (buf.byteLength % FLOAT32_BYTES !== 0) {
        throw new Error(`[storage.vector] decoded buffer is not aligned to float32 (${buf.byteLength} bytes)`);
    }
    const view = new Uint8Array(buf);
    const copy = new Uint8Array(view.byteLength);
    copy.set(view);
    return new Float32Array(copy.buffer, 0, view.byteLength / FLOAT32_BYTES);
}
export function dot(a, b) {
    if (a.length !== b.length) {
        throw new Error(`[storage.vector] dimension mismatch: ${a.length} vs ${b.length}`);
    }
    let s = 0;
    for(let i = 0; i < a.length; i++)s += a[i] * b[i];
    return s;
}
export function norm2(a) {
    let s = 0;
    for(let i = 0; i < a.length; i++)s += a[i] * a[i];
    return s;
}
export function cosine(a, b) {
    const d = dot(a, b);
    const na = Math.sqrt(norm2(a));
    const nb = Math.sqrt(norm2(b));
    if (na === 0 || nb === 0) return 0;
    return d / (na * nb);
}
export function cosinePrenormed(a, aNorm, b, bNorm2) {
    if (aNorm === 0 || bNorm2 === 0) return 0;
    return dot(a, b) / (aNorm * Math.sqrt(bNorm2));
}
export function topKCosine(query, rows, k) {
    if (k <= 0 || rows.length === 0) return [];
    const qNorm = Math.sqrt(norm2(query));
    if (qNorm === 0) return [];
    const heap = [];
    for (const row of rows){
        if (row.vec.length === 0) continue;
        if (row.vec.length !== query.length) {
            log.warn("search.dim_mismatch", {
                expected: query.length,
                got: row.vec.length,
                rowId: String(row.id)
            });
            continue;
        }
        if (row.norm2 === undefined) row.norm2 = norm2(row.vec);
        const score = cosinePrenormed(query, qNorm, row.vec, row.norm2);
        pushBounded(heap, {
            id: row.id,
            score,
            meta: row.meta
        }, k);
    }
    heap.sort((a, b)=>b.score - a.score);
    return heap;
}
function pushBounded(heap, item, k) {
    if (heap.length < k) {
        heap.push(item);
        siftUp(heap, heap.length - 1);
        return;
    }
    if (item.score <= heap[0].score) return;
    heap[0] = item;
    siftDown(heap, 0);
}
function siftUp(heap, i) {
    while(i > 0){
        const p = i - 1 >> 1;
        if (heap[p].score <= heap[i].score) break;
        [heap[p], heap[i]] = [
            heap[i],
            heap[p]
        ];
        i = p;
    }
}
function siftDown(heap, i) {
    const n = heap.length;
    for(;;){
        const l = 2 * i + 1;
        const r = 2 * i + 2;
        let smallest = i;
        if (l < n && heap[l].score < heap[smallest].score) smallest = l;
        if (r < n && heap[r].score < heap[smallest].score) smallest = r;
        if (smallest === i) break;
        [heap[i], heap[smallest]] = [
            heap[smallest],
            heap[i]
        ];
        i = smallest;
    }
}
export const DEFAULT_SCAN_HARD_CAP = 5_000;
export function scanAndTopK(db, table, selectExtra, query, k, opts) {
    if (k <= 0 || query.length === 0) return [];
    const { vecColumn, norm2Column, where, params, hardCap } = opts;
    const cap = hardCap ?? DEFAULT_SCAN_HARD_CAP;
    const cols = [
        "id",
        vecColumn,
        ...norm2Column ? [
            norm2Column
        ] : [],
        ...selectExtra
    ];
    const sql = [
        `SELECT ${cols.join(", ")} FROM ${table}`,
        where ? `WHERE ${where}` : "",
        `LIMIT ${cap}`
    ].filter(Boolean).join(" ");
    const qNorm = Math.sqrt(norm2(query));
    if (qNorm === 0) return [];
    const buildMeta = selectExtra.length > 0 ? (row)=>Object.fromEntries(selectExtra.map((c)=>[
                c,
                row[c]
            ])) : (_row)=>undefined;
    const heap = [];
    const stmt = db.prepare(sql);
    const iter = params === undefined ? stmt.iterate() : stmt.iterate(params);
    for (const r of iter){
        const vec = decodeVector(r[vecColumn]);
        if (!vec) continue;
        if (vec.length === 0 || vec.length !== query.length) {
            if (vec.length !== 0) {
                log.warn("search.dim_mismatch", {
                    expected: query.length,
                    got: vec.length,
                    rowId: String(r["id"])
                });
            }
            continue;
        }
        const rowNorm2 = norm2Column ? r[norm2Column] ?? norm2(vec) : norm2(vec);
        const score = cosinePrenormed(query, qNorm, vec, rowNorm2);
        if (heap.length < k) {
            heap.push({
                id: String(r["id"]),
                score,
                meta: buildMeta(r)
            });
            siftUp(heap, heap.length - 1);
        } else if (score > heap[0].score) {
            heap[0] = {
                id: String(r["id"]),
                score,
                meta: buildMeta(r)
            };
            siftDown(heap, 0);
        }
    }
    heap.sort((a, b)=>b.score - a.score);
    return heap;
}
