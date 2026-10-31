// Generated from MemTensor/MemOS 64388b50e2a15528bcdf032e65a1ac792bcf63ee: core/storage/repos/traces.ts
import { buildInClause, buildInsert, buildUpdate } from "../tx.mjs";
import { scanAndTopK, topKCosine } from "../vector.mjs";
import { buildPageClauses, fromBlob, fromJsonText, joinWhere, normalizeShareForStorage, nullable, ownerFieldsFromRaw, ownerParamsFromRow, timeRangeWhere, toBlob, toJsonText } from "./_helpers.mjs";
const COLUMNS = [
    "id",
    "episode_id",
    "session_id",
    "owner_agent_kind",
    "owner_profile_id",
    "owner_workspace_id",
    "ts",
    "user_text",
    "agent_text",
    "summary",
    "tool_calls_json",
    "reflection",
    "agent_thinking",
    "value",
    "alpha",
    "r_human",
    "priority",
    "tags_json",
    "error_signatures_json",
    "vec_summary",
    "vec_action",
    "share_scope",
    "share_target",
    "shared_at",
    "turn_id",
    "schema_version"
];
const DEDUP_COLUMNS = [
    "ts",
    "turn_id",
    "user_text",
    "agent_text",
    "tool_calls_json"
];
export function makeTracesRepo(db) {
    const insert = db.prepare(buildInsert({
        table: "traces",
        columns: COLUMNS
    }));
    const upsert = db.prepare(buildInsert({
        table: "traces",
        columns: COLUMNS,
        onConflict: "replace"
    }));
    const updateScalars = db.prepare(buildUpdate({
        table: "traces",
        columns: [
            "id",
            "value",
            "alpha",
            "r_human",
            "priority"
        ]
    }));
    const selectById = db.prepare(`SELECT ${COLUMNS.join(", ")} FROM traces WHERE id=@id`);
    const selectLatestTimestamp = db.prepare(`SELECT ts FROM traces ORDER BY ts DESC, id DESC LIMIT 1`);
    return {
        insert (row) {
            insert.run(rowToParams(row));
        },
        upsert (row) {
            upsert.run(rowToParams(row));
        },
        updateScore (id, scores) {
            updateScalars.run({
                id,
                value: scores.value,
                alpha: scores.alpha,
                r_human: nullable(scores.rHuman ?? null),
                priority: scores.priority
            });
        },
        getById (id) {
            const r = selectById.get({
                id
            });
            if (!r) return null;
            return mapRow(r);
        },
        latestTimestamp () {
            return selectLatestTimestamp.get()?.ts ?? null;
        },
        getManyByIds (ids) {
            if (ids.length === 0) return [];
            const placeholders = buildInClause(ids.length);
            const sql = `SELECT ${COLUMNS.join(", ")} FROM traces WHERE id ${placeholders}`;
            const rows = db.prepare(sql).all(ids);
            return rows.map(mapRow);
        },
        hasAnyNewerThan (ids, ts) {
            if (ids.length === 0) return false;
            const CHUNK_SIZE = 900;
            for(let i = 0; i < ids.length; i += CHUNK_SIZE){
                const chunk = ids.slice(i, i + CHUNK_SIZE);
                const placeholders = buildInClause(chunk.length);
                const sql = `SELECT 1 FROM traces WHERE id ${placeholders} AND ts > ? LIMIT 1`;
                const row = db.prepare(sql).get([
                    ...chunk,
                    ts
                ]);
                if (row) return true;
            }
            return false;
        },
        countExisting (ids) {
            if (ids.length === 0) return 0;
            const dedup = Array.from(new Set(ids));
            const CHUNK_SIZE = 900;
            let total = 0;
            for(let i = 0; i < dedup.length; i += CHUNK_SIZE){
                const chunk = dedup.slice(i, i + CHUNK_SIZE);
                const placeholders = buildInClause(chunk.length);
                const sql = `SELECT COUNT(*) AS n FROM traces WHERE id ${placeholders}`;
                const row = db.prepare(sql).get(chunk);
                total += row?.n ?? 0;
            }
            return total;
        },
        filterExistingIds (ids) {
            if (ids.length === 0) return [];
            const dedup = Array.from(new Set(ids));
            const existing = new Set();
            const CHUNK_SIZE = 900;
            for(let i = 0; i < dedup.length; i += CHUNK_SIZE){
                const chunk = dedup.slice(i, i + CHUNK_SIZE);
                const placeholders = buildInClause(chunk.length);
                const sql = `SELECT id FROM traces WHERE id ${placeholders}`;
                const rows = db.prepare(sql).all(chunk);
                for (const r of rows)existing.add(r.id);
            }
            return dedup.filter((id)=>existing.has(id));
        },
        list (filter = {}) {
            const tr = timeRangeWhere(filter, "ts");
            const fragments = [];
            const params = {
                ...tr.params
            };
            if (filter.sessionId) {
                fragments.push(`session_id = @session_id`);
                params.session_id = filter.sessionId;
            }
            if (filter.episodeId) {
                fragments.push(`episode_id = @episode_id`);
                params.episode_id = filter.episodeId;
            }
            if (filter.ownerAgentKind) {
                fragments.push(`owner_agent_kind = @owner_agent_kind`);
                params.owner_agent_kind = filter.ownerAgentKind;
            }
            if (filter.ownerProfileId) {
                fragments.push(`owner_profile_id = @owner_profile_id`);
                params.owner_profile_id = filter.ownerProfileId;
            }
            if (filter.minAbsValue !== undefined) {
                fragments.push(`abs(value) >= @min_abs_value`);
                params.min_abs_value = filter.minAbsValue;
            }
            if (tr.sql) fragments.push(tr.sql);
            const where = joinWhere(fragments);
            const shouldUseUncappedEpisodeScan = Boolean(filter.episodeId) && filter.limit === undefined && filter.offset === undefined;
            const page = shouldUseUncappedEpisodeScan ? `ORDER BY ts ${filter.newestFirst === false ? "ASC" : "DESC"}` : buildPageClauses(filter, "ts");
            const sql = `SELECT ${COLUMNS.join(", ")} FROM traces ${where} ${page}`;
            return db.prepare(sql).all(params).map(mapRow);
        },
        listAllForEpisode (episodeId) {
            if (!episodeId) return [];
            const sql = `SELECT ${COLUMNS.join(", ")} FROM traces WHERE episode_id = @episode_id ORDER BY ts ASC`;
            const rows = db.prepare(sql).all({
                episode_id: String(episodeId)
            });
            return rows.map(mapRow);
        },
        listDedupRowsForEpisode (episodeId) {
            if (!episodeId) return [];
            const sql = `SELECT ${DEDUP_COLUMNS.join(", ")} FROM traces WHERE episode_id = @episode_id ORDER BY ts ASC`;
            const stmt = db.prepare(sql);
            const rows = [];
            for (const r of stmt.iterate({
                episode_id: String(episodeId)
            })){
                rows.push({
                    ts: r.ts,
                    turnId: r.turn_id,
                    userText: r.user_text,
                    agentText: r.agent_text,
                    toolCalls: fromJsonText(r.tool_calls_json, [])
                });
            }
            return rows;
        },
        count (filter = {}, visibility) {
            const tr = timeRangeWhere(filter, "ts");
            const fragments = [];
            const params = {
                ...tr.params
            };
            if (filter.sessionId) {
                fragments.push(`session_id = @session_id`);
                params.session_id = filter.sessionId;
            }
            if (filter.episodeId) {
                fragments.push(`episode_id = @episode_id`);
                params.episode_id = filter.episodeId;
            }
            if (filter.ownerAgentKind) {
                fragments.push(`owner_agent_kind = @owner_agent_kind`);
                params.owner_agent_kind = filter.ownerAgentKind;
            }
            if (filter.ownerProfileId) {
                fragments.push(`owner_profile_id = @owner_profile_id`);
                params.owner_profile_id = filter.ownerProfileId;
            }
            if (filter.minAbsValue !== undefined) {
                fragments.push(`abs(value) >= @min_abs_value`);
                params.min_abs_value = filter.minAbsValue;
            }
            if (visibility) {
                fragments.push(visibility.sql);
                Object.assign(params, visibility.params);
            }
            if (tr.sql) fragments.push(tr.sql);
            const where = joinWhere(fragments);
            const sql = `SELECT COUNT(*) AS n FROM traces ${where}`;
            const row = db.prepare(sql).get(params);
            return row?.n ?? 0;
        },
        countTurns (filter = {}, visibility) {
            const fragments = [];
            const params = {};
            if (filter.sessionId) {
                fragments.push(`session_id = @session_id`);
                params.session_id = filter.sessionId;
            }
            if (filter.episodeId) {
                fragments.push(`episode_id = @episode_id`);
                params.episode_id = filter.episodeId;
            }
            if (filter.ownerAgentKind) {
                fragments.push(`owner_agent_kind = @owner_agent_kind`);
                params.owner_agent_kind = filter.ownerAgentKind;
            }
            if (filter.ownerProfileId) {
                fragments.push(`owner_profile_id = @owner_profile_id`);
                params.owner_profile_id = filter.ownerProfileId;
            }
            if (visibility) {
                fragments.push(visibility.sql);
                Object.assign(params, visibility.params);
            }
            const where = joinWhere(fragments);
            const sql = `SELECT COUNT(*) AS n FROM (SELECT DISTINCT episode_id, turn_id FROM traces ${where})`;
            const row = db.prepare(sql).get(params);
            return row?.n ?? 0;
        },
        listTurnKeys (filter = {}, visibility) {
            const fragments = [];
            const params = {};
            if (filter.sessionId) {
                fragments.push(`session_id = @session_id`);
                params.session_id = filter.sessionId;
            }
            if (filter.episodeId) {
                fragments.push(`episode_id = @episode_id`);
                params.episode_id = filter.episodeId;
            }
            if (filter.ownerAgentKind) {
                fragments.push(`owner_agent_kind = @owner_agent_kind`);
                params.owner_agent_kind = filter.ownerAgentKind;
            }
            if (filter.ownerProfileId) {
                fragments.push(`owner_profile_id = @owner_profile_id`);
                params.owner_profile_id = filter.ownerProfileId;
            }
            if (visibility) {
                fragments.push(visibility.sql);
                Object.assign(params, visibility.params);
            }
            const where = joinWhere(fragments);
            const limit = Math.max(1, Math.min(10_000, filter.limit ?? 50));
            const offset = Math.max(0, filter.offset ?? 0);
            params.limit = limit;
            params.offset = offset;
            const sql = `SELECT episode_id, turn_id, MAX(ts) as max_ts FROM traces ${where} GROUP BY episode_id, turn_id ORDER BY max_ts DESC LIMIT @limit OFFSET @offset`;
            const rows = db.prepare(sql).all(params);
            return rows.map((r)=>({
                    episodeId: r.episode_id,
                    turnId: r.turn_id,
                    maxTs: r.max_ts
                }));
        },
        listByTurnKeys (keys) {
            if (keys.length === 0) return [];
            const conditions = [];
            const params = {};
            keys.forEach((k, i)=>{
                if (k.episodeId == null) {
                    conditions.push(`(episode_id IS NULL AND turn_id = @turn_${i})`);
                } else {
                    conditions.push(`(episode_id = @ep_${i} AND turn_id = @turn_${i})`);
                    params[`ep_${i}`] = k.episodeId;
                }
                params[`turn_${i}`] = k.turnId;
            });
            const sql = `SELECT ${COLUMNS.join(", ")} FROM traces WHERE ${conditions.join(" OR ")} ORDER BY ts ASC`;
            return db.prepare(sql).all(params).map(mapRow);
        },
        searchByVector (query, k, opts = {}) {
            const kind = opts.kind ?? "summary";
            const vecColumn = kind === "action" ? "vec_action" : "vec_summary";
            const params = {
                ...opts.params ?? {}
            };
            const whereParts = [
                `${vecColumn} IS NOT NULL`
            ];
            if (opts.where) whereParts.push(opts.where);
            if (opts.anyOfTags && opts.anyOfTags.length > 0) {
                const tagOrs = [];
                opts.anyOfTags.forEach((tag, i)=>{
                    const key = `tag_${i}`;
                    params[key] = `"${String(tag).replace(/["\\]/g, "\\$&")}"`;
                    tagOrs.push(`instr(tags_json, @${key}) > 0`);
                });
                whereParts.push(`(${tagOrs.join(" OR ")})`);
            }
            return scanAndTopK(db, "traces", [
                "ts",
                "priority",
                "value",
                "episode_id",
                "session_id",
                "owner_agent_kind",
                "owner_profile_id",
                "owner_workspace_id",
                "tags_json"
            ], query, k, {
                vecColumn,
                where: whereParts.join(" AND "),
                params,
                hardCap: opts.hardCap
            });
        },
        topKAgainstRows (query, rows, k) {
            return topKCosine(query, rows, k);
        },
        searchByText (ftsMatch, k, opts = {}) {
            if (!ftsMatch || k <= 0) return [];
            const params = {
                ...opts.params ?? {},
                match: ftsMatch,
                k: Math.max(1, Math.min(500, Math.floor(k)))
            };
            const extra = opts.where ? `AND (${opts.where})` : "";
            const sql = `
        SELECT t.id          AS id,
               -bm25(traces_fts) AS score,
               t.ts          AS ts,
               t.priority    AS priority,
               t.value       AS value,
               t.episode_id  AS episode_id,
               t.session_id  AS session_id,
               t.owner_agent_kind AS owner_agent_kind,
               t.owner_profile_id AS owner_profile_id,
               t.owner_workspace_id AS owner_workspace_id,
               t.tags_json   AS tags_json,
               t.error_signatures_json AS error_signatures_json
          FROM traces_fts f
          JOIN traces      t ON t.id = f.trace_id
         WHERE traces_fts MATCH @match ${extra}
         ORDER BY rank
         LIMIT @k`;
            const rows = db.prepare(sql).all(params);
            return rows.map((r, idx)=>({
                    id: r.id,
                    score: 1 / (idx + 1),
                    meta: {
                        ts: r.ts,
                        priority: r.priority,
                        value: r.value,
                        episode_id: r.episode_id,
                        session_id: r.session_id,
                        owner_agent_kind: r.owner_agent_kind,
                        owner_profile_id: r.owner_profile_id,
                        owner_workspace_id: r.owner_workspace_id,
                        tags_json: r.tags_json,
                        error_signatures_json: r.error_signatures_json
                    }
                }));
        },
        searchByPattern (terms, k, opts = {}) {
            if (!terms || terms.length === 0 || k <= 0) return [];
            const dedup = Array.from(new Set(terms.map((t)=>String(t).trim()).filter(Boolean)));
            if (dedup.length === 0) return [];
            const params = {
                ...opts.params ?? {},
                k: Math.max(1, Math.min(500, Math.floor(k)))
            };
            const ors = [];
            const matchScores = [];
            const limitedTerms = dedup.slice(0, 16);
            limitedTerms.forEach((t, i)=>{
                const key = `pat_${i}`;
                const escaped = t.replace(/[\\%_]/g, (m)=>`\\${m}`);
                params[key] = `%${escaped}%`;
                const match = `(user_text LIKE @${key} ESCAPE '\\' OR
            agent_text LIKE @${key} ESCAPE '\\' OR
            COALESCE(summary,'') LIKE @${key} ESCAPE '\\' OR
            COALESCE(reflection,'') LIKE @${key} ESCAPE '\\' OR
            tags_json LIKE @${key} ESCAPE '\\')`;
                ors.push(match);
                matchScores.push(`CASE WHEN ${match} THEN 1 ELSE 0 END`);
            });
            const extra = opts.where ? ` AND (${opts.where})` : "";
            const sql = `
        SELECT id, ts, priority, value, episode_id, session_id, tags_json,
               owner_agent_kind, owner_profile_id, owner_workspace_id,
               error_signatures_json,
               (${matchScores.join(" + ")}) AS pattern_matches
          FROM traces
         WHERE (${ors.join(" OR ")})${extra}
         ORDER BY pattern_matches DESC, ts DESC
         LIMIT @k`;
            const rows = db.prepare(sql).all(params);
            return rows.map((r)=>({
                    id: r.id,
                    score: r.pattern_matches / limitedTerms.length,
                    meta: {
                        ts: r.ts,
                        priority: r.priority,
                        value: r.value,
                        episode_id: r.episode_id,
                        session_id: r.session_id,
                        owner_agent_kind: r.owner_agent_kind,
                        owner_profile_id: r.owner_profile_id,
                        owner_workspace_id: r.owner_workspace_id,
                        tags_json: r.tags_json,
                        error_signatures_json: r.error_signatures_json
                    }
                }));
        },
        searchByErrorSignature (anyOfFragments, limit, opts = {}) {
            if (!anyOfFragments || anyOfFragments.length === 0) return [];
            const frags = Array.from(new Set(anyOfFragments)).filter((f)=>typeof f === "string" && f.length >= 6).slice(0, 8);
            if (frags.length === 0) return [];
            const params = {
                ...opts.params ?? {}
            };
            const ors = [];
            frags.forEach((frag, i)=>{
                const key = `sig_${i}`;
                params[key] = `"${frag.replace(/["\\]/g, "\\$&")}"`;
                ors.push(`instr(error_signatures_json, @${key}) > 0`);
            });
            const whereParts = [
                `(${ors.join(" OR ")})`
            ];
            if (opts.where) whereParts.push(opts.where);
            const sql = `SELECT ${COLUMNS.join(", ")} FROM traces WHERE ${whereParts.join(" AND ")} ORDER BY ts DESC LIMIT @limit`;
            params.limit = Math.max(1, Math.min(200, Math.floor(limit)));
            const rows = db.prepare(sql).all(params);
            return rows.map(mapRow);
        },
        deleteById (id) {
            db.prepare(`DELETE FROM traces_fts WHERE trace_id=@id`).run({
                id
            });
            db.prepare(`DELETE FROM traces WHERE id=@id`).run({
                id
            });
        },
        updateBody (id, patch) {
            const sets = [];
            const params = {
                id
            };
            if (patch.summary !== undefined) {
                sets.push("summary = @summary");
                params.summary = patch.summary;
            }
            if (patch.userText !== undefined) {
                sets.push("user_text = @user_text");
                params.user_text = patch.userText;
            }
            if (patch.agentText !== undefined) {
                sets.push("agent_text = @agent_text");
                params.agent_text = patch.agentText;
            }
            if (patch.tags !== undefined) {
                sets.push("tags_json = @tags_json");
                params.tags_json = toJsonText(normalizeTags(patch.tags));
            }
            if (sets.length === 0) return;
            const sql = `UPDATE traces SET ${sets.join(", ")} WHERE id = @id`;
            db.prepare(sql).run(params);
        },
        updateVector (id, field, vec) {
            const column = field === "vecAction" ? "vec_action" : "vec_summary";
            const res = db.prepare(`UPDATE traces SET ${column}=@vec WHERE id=@id`).run({
                id,
                vec: toBlob(vec)
            });
            return res.changes > 0;
        },
        updateReflection (id, patch) {
            db.prepare(`UPDATE traces SET reflection=@reflection, alpha=@alpha WHERE id=@id`).run({
                id,
                reflection: patch.reflection,
                alpha: patch.alpha
            });
        },
        updateShare (id, share) {
            db.prepare(`UPDATE traces SET share_scope=@share_scope, share_target=@share_target, shared_at=@shared_at WHERE id=@id`).run({
                id,
                share_scope: normalizeShareForStorage(share.scope),
                share_target: share.target ?? null,
                shared_at: share.sharedAt ?? null
            });
        }
    };
}
function normalizeSignatures(sigs) {
    if (!sigs || sigs.length === 0) return [];
    const seen = new Set();
    for (const raw of sigs){
        const s = String(raw).trim();
        if (s.length < 6 || s.length > 200) continue;
        seen.add(s);
    }
    return [
        ...seen
    ].slice(0, 4);
}
function normalizeTags(tags) {
    if (!tags || tags.length === 0) return [];
    const seen = new Set();
    for (const t of tags){
        const n = String(t).trim().toLowerCase();
        if (n.length === 0 || n.length > 48) continue;
        seen.add(n);
    }
    return [
        ...seen
    ].sort();
}
function rowToParams(row) {
    return {
        id: row.id,
        episode_id: row.episodeId,
        session_id: row.sessionId,
        ...ownerParamsFromRow(row),
        ts: row.ts,
        user_text: row.userText,
        agent_text: row.agentText,
        summary: row.summary ?? null,
        tool_calls_json: toJsonText(row.toolCalls ?? []),
        reflection: row.reflection ?? null,
        agent_thinking: row.agentThinking ?? null,
        value: row.value,
        alpha: row.alpha,
        r_human: row.rHuman ?? null,
        priority: row.priority,
        tags_json: toJsonText(normalizeTags(row.tags)),
        error_signatures_json: toJsonText(normalizeSignatures(row.errorSignatures)),
        vec_summary: toBlob(row.vecSummary),
        vec_action: toBlob(row.vecAction),
        share_scope: normalizeShareForStorage(row.share?.scope),
        share_target: row.share?.target ?? null,
        shared_at: row.share?.sharedAt ?? null,
        turn_id: row.turnId ?? null,
        schema_version: row.schemaVersion
    };
}
function mapRow(r) {
    return {
        id: r.id,
        episodeId: r.episode_id,
        sessionId: r.session_id,
        ...ownerFieldsFromRaw(r),
        ts: r.ts,
        userText: r.user_text,
        agentText: r.agent_text,
        summary: r.summary ?? null,
        toolCalls: fromJsonText(r.tool_calls_json, []),
        reflection: r.reflection,
        agentThinking: r.agent_thinking ?? null,
        value: r.value,
        alpha: r.alpha,
        rHuman: r.r_human,
        priority: r.priority,
        tags: fromJsonText(r.tags_json, []),
        errorSignatures: fromJsonText(r.error_signatures_json, []),
        vecSummary: fromBlob(r.vec_summary),
        vecAction: fromBlob(r.vec_action),
        share: r.share_scope != null ? {
            scope: normalizeShareForStorage(r.share_scope),
            target: r.share_target,
            sharedAt: r.shared_at
        } : null,
        turnId: r.turn_id,
        schemaVersion: r.schema_version
    };
}
