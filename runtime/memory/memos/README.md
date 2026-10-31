# MemOS companion storage / 伙伴记忆存储

Droy embeds the storage and retrieval modules from MemTensor/MemOS commit
`64388b50e2a15528bcdf032e65a1ac792bcf63ee` (local plugin 2.0.16-beta.1).
This integration executes the upstream migrations, trace repository, FTS5
search, CJK short-term matching and cosine vector retrieval. It does not enable
the full MemoryCore capture/reflection/skill pipeline, cloud sync, Viewer,
telemetry or a second extraction loop. Existing Droy learning is the sole
extraction caller during this integration stage.

Droy 嵌入上述固定版本的 MemOS 存储与检索实现，实际运行其迁移、trace 仓库、
FTS5、中文短词和向量检索。本阶段没有启用完整 MemoryCore 的自动提炼、反思、
技能生成、云同步、Viewer 或遥测；Droy 现有学习入口仍是唯一的事实提炼调用方。

## Runtime / 运行方式

`MemosMemoryStore` executes SQLite in a dedicated Node worker. Its database is
bound to one immutable `companionId` and `memorySpaceId`. Droy's Android Node
`node:sqlite` implements the pinned `StorageDb` facade, so neither
`better-sqlite3` nor desktop ONNX libraries are packaged by this module.
Embedding vectors are supplied by the host, tagged with the exact model
fingerprint. Queries never compare different model versions. Missing vectors
yield explicitly labelled lexical results.

数据库在独立 Worker 内运行，固定绑定伙伴和记忆空间。Android 使用 Node 自带
SQLite 适配上游 StorageDb，不引入桌面 SQLite 或 ONNX 二进制。宿主提供向量及
精确模型指纹；模型不同的向量不参与比较，没有向量时明确返回文本检索状态。

Current text is stored in MemOS `traces`; product metadata, evidence, revisions,
source fences and receipts remain Droy-owned tables in the same database.
Corrections and suppression retain historical content but remove its active
retrieval/index eligibility. Permanent source forgetting removes the source's
connected records, dependent records, evidence, old revision bodies, FTS rows
and vectors. This low-level source-wide operation must not be exposed as an
arbitrarily narrow topic deletion: the product service owns scope authorization.

当前正文由 MemOS trace 保存，产品元数据、来源证据、修订、防重建标记和操作回执
位于同一个数据库。纠正和停止使用保留历史，立即停止旧内容的召回与向量使用。
底层永久遗忘按来源关联清理记录、派生项、证据、历史正文及索引；产品层必须先确定
该来源范围，不能把这个操作冒充为只删除某一句话的精确主题删除。

## Migration / 迁移

Legacy Droy `memory.sqlite` schema version 1 is migrated in place. Additive
upstream schema installation is followed by one transaction that imports trace
content, removes duplicate current text from product metadata, preserves record
and revision IDs, receipts, source fences and origin counters, and marks version
2. Legacy store code refuses to reopen version 2. No second backup database is
created. The existing exclusive owner lock and durable identity marker remain
in use. A failed import leaves version-1 record bodies intact.

旧版记忆库原位迁移：先增加上游表，再在同一事务中迁入正文、保留身份和修订、
回执及删除标记，最后标为版本 2。不会额外复制一个可能遗留已删内容的备份库。
沿用已有独占锁与持久身份文件；旧入口拒绝版本 2，导入事务失败保留旧正文。

## Updating the pinned sources / 更新固定源码

Obtain a clean checkout of the pinned commit, then run:

```sh
node tools/prepare_memos.mjs /path/to/MemOS-checkout
node --test runtime/memory/tests/memos-store.test.mjs
```

The preparation script verifies the checkout commit and source bytes, strips
TypeScript types with Node's compiler and rewrites relative module extensions.
`upstream/manifest.json` records original and generated SHA-256 hashes. The two
explicit Droy adapters are `core/storage/connection.mjs` and
`core/logger/index.mjs`; the latter prevents private memory diagnostics from
creating upstream buffers, files or broadcasts. Storage errors still propagate.

准备脚本核对 commit 和原文件内容，生成可直接在 Android Node 运行的模块，并记录
原文件与生成文件哈希。两个明确的 Droy 适配点是 SQLite 连接和私有日志出口；
禁用上游日志落盘、缓冲和广播不影响数据库错误的正常传播。

The upstream repository's Apache-2.0 license is retained at `upstream/LICENSE`.
Its local package metadata declares MIT; preserve both the upstream source
provenance and repository license when distributing these selected files.

保留上游仓库 Apache-2.0 许可证。其本地包元数据标记 MIT；分发本次选取文件时
应保留来源记录与仓库许可证，不删除或改写上游声明。
