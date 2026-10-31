// Generated from MemTensor/MemOS 64388b50e2a15528bcdf032e65a1ac792bcf63ee: core/storage/tx.ts
import { rootLogger } from "../logger/index.mjs";
const log = rootLogger.child({
    channel: "storage"
});
export function withRetry(fn, opts = {}) {
    const attempts = opts.attempts ?? 3;
    const delayMs = opts.delayMs ?? 25;
    const label = opts.label ?? "sqlite.op";
    let lastErr = null;
    for(let i = 0; i < attempts; i++){
        try {
            return fn();
        } catch (err) {
            const code = err.code;
            if (code === "SQLITE_BUSY" || code === "SQLITE_LOCKED") {
                lastErr = err;
                if (i < attempts - 1) {
                    log.debug("sqlite.retry", {
                        label,
                        attempt: i + 1,
                        attempts,
                        code
                    });
                    spinFor(delayMs);
                    continue;
                }
            }
            throw err;
        }
    }
    throw lastErr ?? new Error(`[${label}] exhausted retries`);
}
function spinFor(ms) {
    const end = Date.now() + ms;
    while(Date.now() < end){}
}
export function buildInsert(spec) {
    const conflict = spec.onConflict ?? "error";
    const verb = conflict === "replace" ? "INSERT OR REPLACE" : conflict === "ignore" ? "INSERT OR IGNORE" : "INSERT";
    const cols = spec.columns.join(", ");
    const named = spec.columns.map((c)=>`@${c}`).join(", ");
    return `${verb} INTO ${spec.table} (${cols}) VALUES (${named})`;
}
export function buildUpdate(spec) {
    const pk = spec.pk ?? "id";
    const set = spec.columns.filter((c)=>c !== pk).map((c)=>`${c}=@${c}`).join(", ");
    return `UPDATE ${spec.table} SET ${set} WHERE ${pk}=@${pk}`;
}
export function chunkIn(items, chunkSize = 500) {
    if (items.length === 0) return [];
    const out = [];
    for(let i = 0; i < items.length; i += chunkSize)out.push(items.slice(i, i + chunkSize));
    return out;
}
export function buildInClause(n) {
    if (n <= 0) return "IN (NULL)";
    return `IN (${new Array(n).fill("?").join(",")})`;
}
export function withSavepoint(db, name, fn) {
    const safe = sanitizeName(name);
    db.exec(`SAVEPOINT ${safe}`);
    try {
        const result = fn();
        db.exec(`RELEASE SAVEPOINT ${safe}`);
        return result;
    } catch (err) {
        db.exec(`ROLLBACK TO SAVEPOINT ${safe}`);
        db.exec(`RELEASE SAVEPOINT ${safe}`);
        throw err;
    }
}
function sanitizeName(s) {
    if (!/^[a-zA-Z_][a-zA-Z0-9_]*$/.test(s)) {
        throw new Error(`[storage] invalid savepoint name: ${s}`);
    }
    return s;
}
export function isBetterSqliteError(err) {
    return err instanceof Error && typeof err.code === "string";
}
