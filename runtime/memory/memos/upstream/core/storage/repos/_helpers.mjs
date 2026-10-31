// Generated from MemTensor/MemOS 64388b50e2a15528bcdf032e65a1ac792bcf63ee: core/storage/repos/_helpers.ts
import { rootLogger } from "../../logger/index.mjs";
import { decodeVector, encodeVector } from "../vector.mjs";
import { normalizeShareScope, ownerFromNamespace, visibilityWhere as runtimeVisibilityWhere } from "../../runtime/namespace.mjs";
export const repoLog = rootLogger.child({
    channel: "storage.repos"
});
export function toJsonText(v) {
    return JSON.stringify(v ?? null);
}
export function fromJsonText(raw, fallback) {
    if (raw === null || raw === undefined) return fallback;
    if (typeof raw !== "string") return fallback;
    try {
        return JSON.parse(raw);
    } catch  {
        return fallback;
    }
}
export function toBlob(v) {
    if (!v) return null;
    return encodeVector(v);
}
export function fromBlob(v) {
    if (v === null || v === undefined) return null;
    if (!(v instanceof Buffer) && !(v instanceof Uint8Array)) return null;
    return decodeVector(v);
}
export function nullable(v) {
    return v === undefined ? null : v;
}
export function buildPageClauses(opts, tsColumn) {
    const newestFirst = opts?.newestFirst !== false;
    const limit = clampLimit(opts?.limit ?? 500);
    const offset = Math.max(opts?.offset ?? 0, 0);
    return `ORDER BY ${tsColumn} ${newestFirst ? "DESC" : "ASC"} LIMIT ${limit} OFFSET ${offset}`;
}
export function clampLimit(n) {
    if (!Number.isFinite(n) || n <= 0) return 500;
    return Math.min(Math.trunc(n), 500);
}
export function timeRangeWhere(range, column) {
    if (!range) return {
        sql: "",
        params: {}
    };
    const params = {};
    const parts = [];
    if (range.fromMs !== undefined) {
        parts.push(`${column} >= @range_from`);
        params.range_from = range.fromMs;
    }
    if (range.toMs !== undefined) {
        parts.push(`${column} <= @range_to`);
        params.range_to = range.toMs;
    }
    return {
        sql: parts.join(" AND "),
        params
    };
}
export function joinWhere(fragments) {
    const parts = fragments.filter((p)=>Boolean(p && p.trim()));
    if (parts.length === 0) return "";
    return `WHERE ${parts.join(" AND ")}`;
}
export function ownerColumns() {
    return [
        "owner_agent_kind",
        "owner_profile_id",
        "owner_workspace_id"
    ];
}
export function ownerParamsFromRow(row) {
    return {
        owner_agent_kind: row.ownerAgentKind ?? "unknown",
        owner_profile_id: row.ownerProfileId ?? "default",
        owner_workspace_id: row.ownerWorkspaceId ?? null
    };
}
export function ownerFieldsFromRaw(r) {
    return {
        ownerAgentKind: r.owner_agent_kind || "unknown",
        ownerProfileId: r.owner_profile_id || "default",
        ownerWorkspaceId: r.owner_workspace_id ?? null
    };
}
export function defaultOwnerFields(ns) {
    return ns ? ownerFromNamespace(ns) : {
        ownerAgentKind: "unknown",
        ownerProfileId: "default",
        ownerWorkspaceId: null
    };
}
export function visibilityWhere(ns, alias = "") {
    return runtimeVisibilityWhere(ns, alias);
}
export function normalizeShareForStorage(scope) {
    return normalizeShareScope(scope);
}
export function rowOr(row, map) {
    if (!row) return null;
    return map(row);
}
