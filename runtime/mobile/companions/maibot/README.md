# MaiBot source adaptation / MaiBot 源码适配

`upstream/` retains unmodified sources from [Mai-with-u/MaiBot](https://github.com/Mai-with-u/MaiBot) at commit `fe88ec6b6053e40eaf430cb5ba7ed7e805add2cb` (1.3.16). Each file's Git blob hash, SHA-256 and original URL are recorded in `upstream/manifest.json`. The upstream GPL-3.0 license is retained in `upstream/LICENSE`.

`upstream/` 保存上述提交的完整原文；清单记录 Git blob、SHA-256 和来源，许可证原文保留在同目录。不要直接修改原文来加入 Droy 的差异。

`../maibot-prompts.mjs` assembles the original Planner and Replyer templates for a one-to-one chat, maps available Droy tools, and applies the current response language. The regular Replyer keeps real user/assistant history separate from expression choices, temporary style, reply reference, target/time, attachments and requested reply length. Work keeps the companion's persona and delivers its already-assigned task through `companion_reply` without creating another task. Phone permissions and memory scope are enforced by the runtime, not by these prompts.

适配层使用原版 Planner/Replyer 模板，将群聊场景改成单聊，映射实际可用的 Droy 工具，追加当前语言规则。普通 Replyer 分别传入真实消息、表达选择、临时风格、回复参考、目标与时间、附件和篇幅要求。工作对话保留伙伴性格，通过 `companion_reply` 交付已派工作，不再次委派。权限和记忆隔离由运行时执行。

The Chinese Maimai personality, behavior and base style are extracted verbatim from `PersonalityConfig`; the three JSON data resources contain extracted literals only. English persona/private-chat/alternate-style text is a paired translation where upstream only supplies Chinese defaults. Kaka is Droy's own black-cat character. Existing customized profiles are not replaced by default migrations.

麦麦中文人格、行为和表达风格逐字取自 `PersonalityConfig`；三个 JSON 数据文件只保存提取的原文字面量。上游只有中文默认值的地方另提供成对英文译文，清单注明来源。咔咔是 Droy 自有的黑猫角色。默认值迁移保留用户已改写的字段。

Default execution uses the normal tool-driven Planner and normal Replyer. Temporary alternate styles retain the upstream probability `0`; attention drift and the retro Replyer are off; keyword/regex reaction lists are empty; stickers use independent actions. These options have executable adaptation paths, not placeholder switches. Retro private chat retains its own source template and deliberately omits expression and attention-drift injection, as upstream does. Attached stickers are described only when `useNewSendLogic` is enabled. The raw passive/focus Planner, group retro and document templates are retained as upgrade references; they are not silently substituted into the single-chat runtime. A_memorix source prompts are references, not a claim that its storage engine runs in Droy.

默认走工具调用 Planner 和普通 Replyer；临时风格概率保持原版 `0`，注意力漂移和复古回复关闭，关键词/正则规则为空，表情独立发送。这些选项有实际适配路径。复古私聊沿用自己的模板，按上游行为不注入表达学习和漂移；`useNewSendLogic` 开启才引导附加表情。被保留的 passive/focus Planner、群聊复古、文档模板供升级比对，未默认接入私聊；保留 A_memorix 提示不表示复用了其存储引擎。

For an update, inspect the upstream changes in prompts, config defaults, request assembly and tool semantics together. Import the chosen revision unchanged and refresh hashes, then adapt Droy's builders and affected tests to actual upstream behavior. The adapter may improve with the new evidence; the current file list and helper structure are not a permanent conversion contract. Verify role order, current-language behavior, default/optional mechanisms, work delivery and media references after adaptation. Source integrity tests confirm provenance, not model-output equivalence.

升级时同时比较提示、默认配置、请求组装和工具语义。选定新提交并原样导入、更新哈希，再据实际变化调整构造器和相关测试；可根据新证据优化适配，不把当前文件清单或辅助函数固化成转换约束。验证消息角色顺序、当前语言、默认/可选机制、工作交付和媒体引用。源码完整性测试证明来源，不证明模型输出完全相同。
