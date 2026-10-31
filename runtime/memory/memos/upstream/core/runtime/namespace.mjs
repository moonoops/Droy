// Generated from MemTensor/MemOS 64388b50e2a15528bcdf032e65a1ac792bcf63ee: core/runtime/namespace.ts
import { createHash } from "node:crypto";
export const DEFAULT_PROFILE_ID = "default";
export function normalizeNamespace(input, fallbackAgent = "unknown") {
    const agentKind = cleanId(input?.agentKind) || String(fallbackAgent || "unknown");
    const profileId = cleanId(input?.profileId) || DEFAULT_PROFILE_ID;
    const workspacePath = cleanPath(input?.workspacePath);
    const workspaceId = cleanId(input?.workspaceId) || (workspacePath ? hashWorkspace(workspacePath) : undefined);
    const sessionKey = cleanText(input?.sessionKey);
    const profileLabel = cleanText(input?.profileLabel);
    return {
        agentKind,
        profileId,
        ...profileLabel ? {
            profileLabel
        } : {},
        ...workspaceId ? {
            workspaceId
        } : {},
        ...workspacePath ? {
            workspacePath
        } : {},
        ...sessionKey ? {
            sessionKey
        } : {}
    };
}
export function namespaceFromHints(agent, hints, fallback) {
    const embedded = asNamespace(hints?.namespace);
    const agentIdentity = cleanId(hints?.agentIdentity);
    const profileId = embedded?.profileId || agentIdentity || deriveHermesProfileId(cleanText(hints?.hermesHome)) || cleanId(hints?.profileId) || fallback?.profileId;
    return normalizeNamespace({
        agentKind: embedded?.agentKind || cleanId(hints?.agentKind) || fallback?.agentKind || agent,
        profileId,
        profileLabel: embedded?.profileLabel || cleanText(hints?.profileLabel) || cleanText(hints?.agentIdentity) || fallback?.profileLabel,
        workspaceId: embedded?.workspaceId || cleanId(hints?.workspaceId) || fallback?.workspaceId,
        workspacePath: embedded?.workspacePath || cleanPath(hints?.workspaceDir) || cleanPath(hints?.agentDir) || cleanPath(hints?.workspacePath) || fallback?.workspacePath,
        sessionKey: embedded?.sessionKey || cleanText(hints?.sessionKey) || fallback?.sessionKey
    }, agent);
}
export function ownerFromNamespace(ns) {
    const normalized = normalizeNamespace(ns, ns.agentKind);
    return {
        ownerAgentKind: normalized.agentKind,
        ownerProfileId: normalized.profileId,
        ownerWorkspaceId: normalized.workspaceId ?? null
    };
}
export function ownerParams(ns, prefix = "owner") {
    const owner = ownerFromNamespace(ns);
    return {
        [`${prefix}_agent_kind`]: owner.ownerAgentKind,
        [`${prefix}_profile_id`]: owner.ownerProfileId,
        [`${prefix}_workspace_id`]: owner.ownerWorkspaceId
    };
}
export function normalizeShareScope(scope) {
    if (scope === "local") return "public";
    if (scope === "public" || scope === "hub") return scope;
    return "private";
}
export function visibilityWhere(ns, alias = "") {
    const col = (name)=>`${alias ? `${alias}.` : ""}${name}`;
    const normalized = normalizeNamespace(ns, ns?.agentKind ?? "unknown");
    const ownerKind = col("owner_agent_kind");
    const ownerProfile = col("owner_profile_id");
    const shareScope = `COALESCE(${col("share_scope")}, 'private')`;
    return {
        sql: `((` + `${ownerKind} = @vis_owner_agent_kind AND ` + `COALESCE(${ownerProfile}, @vis_default_profile_id) = @vis_owner_profile_id` + `) OR ${ownerKind} IS NULL` + ` OR ${ownerKind} = 'unknown'` + ` OR (${shareScope} IN ('local', 'public') AND ${ownerKind} = @vis_owner_agent_kind)` + ` OR ${shareScope} = 'hub')`,
        params: {
            vis_owner_agent_kind: normalized.agentKind,
            vis_owner_profile_id: normalized.profileId,
            vis_default_profile_id: DEFAULT_PROFILE_ID
        }
    };
}
export function ownerWhere(ns, alias = "") {
    const col = (name)=>`${alias ? `${alias}.` : ""}${name}`;
    const normalized = normalizeNamespace(ns, ns?.agentKind ?? "unknown");
    return {
        sql: `${col("owner_agent_kind")} = @owner_agent_kind AND ` + `${col("owner_profile_id")} = @owner_profile_id`,
        params: {
            owner_agent_kind: normalized.agentKind,
            owner_profile_id: normalized.profileId
        }
    };
}
export function isVisibleTo(row, ns) {
    const scope = normalizeShareScope(row.share?.scope);
    if (!row.ownerAgentKind || row.ownerAgentKind === "unknown") {
        return true;
    }
    const normalized = normalizeNamespace(ns, ns.agentKind);
    const sameAgentFramework = row.ownerAgentKind === normalized.agentKind;
    const sameAgent = sameAgentFramework && (row.ownerProfileId ?? DEFAULT_PROFILE_ID) === normalized.profileId;
    if (sameAgent) return true;
    if (scope === "public") return sameAgentFramework;
    if (scope === "hub") return true;
    return false;
}
export function namespaceMeta(ns) {
    const normalized = normalizeNamespace(ns, ns.agentKind);
    return {
        namespace: normalized,
        ownerAgentKind: normalized.agentKind,
        ownerProfileId: normalized.profileId,
        ownerWorkspaceId: normalized.workspaceId ?? null
    };
}
function asNamespace(value) {
    if (!value || typeof value !== "object" || Array.isArray(value)) return null;
    const record = value;
    const agentKind = cleanId(record.agentKind);
    const profileId = cleanId(record.profileId);
    if (!agentKind && !profileId) return null;
    return normalizeNamespace({
        agentKind: agentKind || "unknown",
        profileId: profileId || DEFAULT_PROFILE_ID,
        profileLabel: cleanText(record.profileLabel),
        workspaceId: cleanId(record.workspaceId),
        workspacePath: cleanPath(record.workspacePath),
        sessionKey: cleanText(record.sessionKey)
    });
}
function deriveHermesProfileId(hermesHome) {
    if (!hermesHome) return undefined;
    const normalized = hermesHome.replace(/\\/g, "/").replace(/\/+$/, "");
    const match = /\/profiles\/([^/]+)$/.exec(normalized);
    if (match?.[1]) return cleanId(match[1]);
    if (normalized.endsWith("/.hermes")) return DEFAULT_PROFILE_ID;
    return undefined;
}
function cleanText(value) {
    if (typeof value !== "string") return undefined;
    const trimmed = value.trim();
    return trimmed || undefined;
}
function cleanPath(value) {
    return cleanText(value);
}
function cleanId(value) {
    const trimmed = cleanText(value);
    if (!trimmed) return undefined;
    return trimmed.toLowerCase().replace(/[^a-z0-9_-]+/g, "-").replace(/^-+|-+$/g, "") || undefined;
}
function hashWorkspace(workspacePath) {
    return createHash("sha256").update(workspacePath).digest("hex").slice(0, 16);
}
