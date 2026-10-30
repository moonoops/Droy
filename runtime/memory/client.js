window.__ModuleLoader__.load({
  id: '@droyagent/memory',
  factory: require => {
    const React = require('react');
    const { Button, Switch } = require('@deepseek-ai/dsh-client-ui-primitives');
    const h = React.createElement;
    let locale;
    const messages = {
      "Droy记忆已关闭。你仍可查看或删除已保存的记录。": "Droy memory is off. You can still view or delete saved records.",
      "记忆服务当前不可用，请检查相关插件是否已启用。": "Memory is unavailable. Check that the required plugins are enabled.",
      "记忆服务尚未就绪，请稍后刷新。": "Memory is not ready. Refresh in a moment.",
      "记忆服务尚未就绪，采集和处理保持关闭。": "Memory is not ready. Collection and processing remain off.",
      "记忆召回已关闭，可在设置中开启。": "Memory recall is off. You can enable it in Settings.",
      "这条记录已发生变化。请刷新后核对，再决定是否修改。": "This record has changed. Refresh and review it before making changes.",
      "记录已有更新，请重新查询。": "Records have changed. Run the query again.",
      "记录不存在或已被删除。": "This record does not exist or has been deleted.",
      "这一段内容较长，请缩小查询范围。": "This content is too long. Narrow the query range.",
      "本轮记忆读取已达到预算，请缩小范围或在新对话继续。": "The memory reading budget for this turn has been reached. Narrow the range or start a new conversation.",
      "关联来源较多，请缩小记录范围。": "Too many linked sources. Narrow the record range.",
      "本次关联来源较多，请缩小范围或新建对话。": "Too many linked sources for this request. Narrow the range or start a new conversation.",
      "当前处理端点未获授权，请在处理授权中检查。": "This processing endpoint is not authorized. Check Processing permissions.",
      "这段对话包含已忘记的内容，请新建对话继续。": "This conversation contains forgotten content. Start a new conversation to continue.",
      "这段旧对话的隐私状态无法确认，请新建对话继续。": "The privacy status of this older conversation cannot be confirmed. Start a new conversation to continue.",
      "记忆插件尚未就绪，请检查插件是否已启用。": "The memory plugin is not ready. Check that it is enabled.",
      "输入不完整或格式有误，请检查后重试。": "The input is incomplete or invalid. Check it and try again.",
      "时间范围无效，请检查开始和结束时间。": "Invalid time range. Check the start and end times.",
      "记忆存储空间不足，请先清理或导出记录。": "Memory storage is full. Clean up or export records first.",
      "当前设备未连接活动和位置记录服务。": "Activity and location recording services are not connected on this device.",
      "当前还没有可供授权的模型处理端点。": "No model processing endpoints are available for authorization yet.",
      "请使用至少 12 个字符的备份密码。": "Use a backup password with at least 12 characters.",
      "备份文件或密码不正确，未导入记录。": "The backup file or password is incorrect. No records were imported.",
      "当前仅支持将备份导入空记忆库，已有记录未被覆盖。": "Backups can currently only be imported into an empty memory library. Existing records were not overwritten.",
      "请先选择需要导出的记录。": "Select the records to export first.",
      "所选记录超过单次备份大小，请缩小范围。": "The selected records exceed the backup size limit. Select fewer records.",
      "备份文件超过 16 MiB，请选择较小的备份。": "The backup file exceeds 16 MiB. Choose a smaller backup.",
      "当前设备尚未连接备份服务。": "The backup service is not connected on this device.",
      "备份结果无效，未打开下载。": "Invalid backup result. The download was not opened.",
      "备份下载已过期，请重新生成。": "The backup download has expired. Create it again.",
      "当前设备尚未连接备份保存入口，请更新 Droy 后重试。": "Backup saving is not available on this device. Update Droy and try again.",
      "当前设备尚未连接整理服务。": "The memory organization service is not connected on this device.",
      "今天的整理预算已用完，内容会保留，稍后再试。": "Today's organization budget has been used. Your content is retained; try again later.",
      "这一片段超过整理大小限制，未发送给模型。": "This segment exceeds the processing size limit and was not sent to a model.",
      "原始片段已到期，无法重新整理。": "The original segment has expired and cannot be processed again.",
      "上次整理被中断，等待你选择是否重试。": "The previous processing was interrupted. Choose whether to retry.",
      "原始片段已有变化，请重新选择需要整理的内容。": "The original segment has changed. Select the content to process again.",
      "整理已停止。": "Organization stopped.",
      "事实": "Fact",
      "偏好": "Preference",
      "决定": "Decision",
      "任务摘要": "Task summary",
      "活动": "Activity",
      "应用活动": "App activity",
      "位置样本": "Location sample",
      "停留候选": "Possible visit",
      "记录缺口": "Recording gap",
      "设备状态": "Device state",
      "事件": "Event",
      "用户陈述": "User statement",
      "工具观察": "Tool observation",
      "执行回执": "Execution receipt",
      "模型推断": "Model inference",
      "用户": "User",
      "Droy 活动期间": "During Droy activity",
      "来源未确定": "Source unknown",
      "已关闭": "Off",
      "已暂停": "Paused",
      "采集中": "Recording",
      "等待系统权限": "Waiting for system permission",
      "暂不具备启用条件": "Requirements not met",
      "服务已中断": "Service interrupted",
      "存储空间受限": "Storage limited",
      "时间未知": "Time unknown",
      "未命名地点": "Unnamed place",
      "城市未解析": "City unresolved",
      "记录": "Record",
      "操作已取消。": "Action cancelled.",
      "操作未完成，请检查状态后重试。{v0}": "The action did not complete. Check the status and try again.{v0}",
      "加密备份已准备好，请点击保存。下载链接仅在本机短时有效。": "Your encrypted backup is ready. Select Save. The download link is valid only on this device for a short time.",
      "备份已恢复到本机记忆库。处理端点仍需在本机单独授权。": "The backup was restored to the local memory library. Processing endpoints still need separate authorization on this device.",
      "记录已更新。包含旧内容的对话可能需要重新开始。": "Record updated. Conversations containing the old content may need to be restarted.",
      "已保存记忆。": "Memory saved.",
      "记录详情": "Record details",
      "新增记忆": "Add memory",
      "类型": "Type",
      "地点名称": "Place name",
      "例如：家、办公室": "For example: home, office",
      "城市（由你确认）": "City (confirmed by you)",
      "未知时留空": "Leave blank if unknown",
      "内容": "Content",
      "写下你希望 Droy 记住的事情": "Write something you want Droy to remember",
      "发生时间": "When it happened",
      "依据": "Evidence",
      "行为归属": "Actor",
      "来源": "Sources",
      "，{v0} 个关联来源": ", {v0} linked sources",
      "{v0} 个直接来源{v1}": "{v0} direct sources{v1}",
      "保留": "Retention",
      "保留至你删除": "Keep until you delete it",
      "到期：{v0}": "Expires: {v0}",
      "定位精度": "Location accuracy",
      "，样本已过旧": "; sample is stale",
      "约 {v0} 米{v1}": "About {v0} m{v1}",
      "坐标": "Coordinates",
      "保存中…": "Saving…",
      "保存纠正": "Save correction",
      "保存记忆": "Save memory",
      "忘记": "Forget",
      "关闭": "Close",
      "忘记这条记录及相关派生内容？此操作不能撤销，含旧记忆的对话可能需要重新开始。": "Forget this record and its derived content? This cannot be undone. Conversations containing the old memory may need to be restarted.",
      "取消": "Cancel",
      "已忘记这条记录。": "Record forgotten.",
      "确认忘记": "Confirm forget",
      "采集设置已更新。": "Recording settings updated.",
      "手机活动与位置": "Phone activity and location",
      "尚未开始记录": "Recording has not started",
      "当前状态：{v0}": "Current status: {v0}",
      "手机活动记录": "Phone activity recording",
      "仅记录你开启的活动来源和允许的应用。位置使用下面的独立开关。": "Record only enabled activity sources and allowed apps. Location has its own switch below.",
      "恢复记录": "Resume recording",
      "暂停所有记录": "Pause all recording",
      "记录允许应用的前台活动，不能证明你始终在注视屏幕。": "Record foreground activity of allowed apps. This does not prove you were looking at the screen throughout.",
      "页面文字": "Page text",
      "仅保存允许应用的部分可见文字，跳过输入和密码字段；默认关闭。": "Save some visible text from allowed apps, excluding input and password fields. Off by default.",
      "记录电量、充电和网络类型的变化。": "Record changes in battery level, charging and network type.",
      "位置记录": "Location recording",
      "独立开关和系统权限；不自动把未知位置命名成城市。": "Separate switch and system permission. Unknown locations are not automatically assigned a city.",
      "请在系统页面管理位置权限。": "Manage location permission on the system screen.",
      "管理位置权限": "Manage location permission",
      "选择记录应用": "Choose apps to record",
      "允许 {v0} 个，排除 {v1} 个": "{v0} allowed, {v1} excluded",
      "查找应用": "Find apps",
      "应用名称": "App name",
      "应用记录范围": "Apps to record",
      "排除": "Exclude",
      "保存应用范围": "Save app selection",
      "排除规则优先。暂停或关闭的时段不会事后补采，麦克风和摄像头不会随此开启。": "Exclusions take priority. Paused or disabled periods are not recorded later. This does not enable the microphone or camera.",
      "模型端点": "Model endpoint",
      "用途：{v0}": "Purpose: {v0}",
      "允许处理的数据": "Data allowed for processing",
      "个人记忆": "Personal memory",
      "活动记录": "Activity records",
      "位置": "Location",
      "派生内容": "Derived content",
      "处理授权已保存。": "Processing permission saved.",
      "允许处理所选数据": "Allow processing selected data",
      "已撤回处理授权。": "Processing permission revoked.",
      "撤回授权": "Revoke permission",
      "整理进度": "Organization progress",
      "，正在处理": "; processing",
      "待整理 {v0} 项，待处理 {v1} 项{v2}。": "{v0} awaiting organization, {v1} need attention{v2}.",
      "尚未开始整理。": "Organization has not started.",
      "上次整理未完成，请检查处理授权或待处理片段。": "The previous processing did not complete. Check processing permissions or segments needing attention.",
      "今日已预留 {v0} 次处理，{v1} 个估算 token。失败和中断不会退回已预留的预算。": "Reserved today: {v0} processing calls and {v1} estimated tokens. Failed or interrupted calls do not refund the reserved budget.",
      "失败的片段不会自动反复重试。明确重试会使用已授权端点，并继续遵守每日预算；丢弃只取消这次整理，不删除原始记录。": "Failed segments are not retried repeatedly. An explicit retry uses authorized endpoints within the daily budget. Discarding cancels this processing only; it does not delete original records.",
      "待处理片段 {v0}": "Segment needing attention {v0}",
      "上次整理未完成，需要你选择后续操作。": "The previous processing did not complete. Choose what to do next.",
      "已提交这次重试，请查看整理进度。": "Retry submitted. Check organization progress.",
      "重试这次整理": "Retry this processing",
      "已丢弃这次整理，原始记录仍按保留设置管理。": "This processing was discarded. Original records remain subject to retention settings.",
      "丢弃这次整理": "Discard this processing",
      "加密备份与恢复": "Encrypted backup and restore",
      "在记忆、活动或地点列表中勾选要备份的记录。备份使用你设置的密码加密，密码不会保存，也不能找回。单个文件最多 16 MiB。": "Select records in Memory, Activity or Places to back up. The backup is encrypted with your password. The password is not saved and cannot be recovered. Maximum file size: 16 MiB.",
      "已选 {v0} 条记录。": "{v0} records selected.",
      "清空备份选择": "Clear backup selection",
      "备份密码（至少 12 个字符）": "Backup password (at least 12 characters)",
      "再次输入密码": "Enter password again",
      "生成选中记录的加密备份": "Create encrypted backup of selected records",
      "保存加密备份": "Save encrypted backup",
      "链接到期：{v0}。在系统文件选择器中选择保存位置后才会写入文件，保存完成前请勿关闭应用。": "Link expires: {v0}. The file is written only after you choose a location in the system file picker. Keep the app open until saving finishes.",
      "从备份恢复": "Restore from backup",
      "目前仅支持恢复到空记忆库，已有记录不会被覆盖。备份中的处理授权不会导入，需要在本机重新选择可信端点。": "Restoring is currently supported only into an empty memory library. Existing records are not overwritten. Processing permissions are not imported; choose trusted endpoints again on this device.",
      "选择加密备份": "Choose encrypted backup",
      "备份密码": "Backup password",
      "正在读取备份…": "Reading backup…",
      "恢复到空记忆库": "Restore to empty memory library",
      "记忆使用": "Memory usage",
      "对话中按需召回": "Recall as needed in conversations",
      "需要个人背景时才查询，不会每句话注入全部记忆。": "Query only when personal context is needed. The full memory library is not added to every message.",
      "召回设置已更新。": "Recall settings updated.",
      "自动整理新增内容": "Automatically organize new content",
      "默认关闭。仅使用已授权端点，在预算内批量处理；处理失败不会切换服务。": "Off by default. Use authorized endpoints only, in batches within the budget. Processing failures do not switch services.",
      "整理设置已更新。": "Organization settings updated.",
      "处理授权": "Processing permissions",
      "记录保存在本机。只有你明确允许的模型端点可以处理所选数据；授权云端端点会向该服务发送相关内容。": "Records are stored on this device. Only model endpoints you explicitly authorize may process selected data. Authorizing a cloud endpoint sends relevant content to that service.",
      "允许模型处理不等于允许对外发送；涉及外发操作会单独确认。": "Permission for model processing does not authorize external sharing. External actions require separate confirmation.",
      "尚无可授权的处理端点。请先在 DSH 中配置模型。": "No processing endpoints are available for authorization. Configure a model in Droy first.",
      "保留与遗忘": "Retention and forgetting",
      "主动保存的记忆保留至你删除。自动页面文字默认保留 48 小时，位置样本 7 天，应用活动 30 天，停留候选 90 天。": "Memories you explicitly save are kept until you delete them. Automatic page text is kept for 48 hours, location samples for 7 days, app activity for 30 days and possible visits for 90 days by default.",
      "到期清理作用于记忆库。已进入对话的副本仍按对话历史管理；明确“忘记”会限制包含旧内容的对话继续处理。": "Expiry cleanup applies to the memory library. Copies already in conversations remain subject to conversation history settings. Explicitly forgetting content restricts further processing of conversations that contain it.",
      "Droy记忆": "Droy memory",
      "让 Droy 记住你提供的信息，并记录你单独授权的手机活动和位置。": "Let Droy remember information you provide and record phone activity and locations you separately authorize.",
      "Droy记忆已开启。": "Droy memory is on.",
      "Droy记忆已关闭。": "Droy memory is off.",
      "已获授权": "Authorized",
      "未获授权": "Not authorized",
      "尚未检查": "Not checked",
      "管理 Droy 记住的事情、活动记录和地点。": "Manage what Droy remembers, activity records and places.",
      "记忆服务暂不可用": "Memory is temporarily unavailable",
      "开启记忆后会初始化本地服务。服务就绪前不会采集或创建记录，已有采集仍可暂停或关闭。": "Enabling memory initializes the local service. No records are collected or created until it is ready. Existing collection can still be paused or turned off.",
      "记忆分类": "Memory categories",
      "记忆": "Memory",
      "地点": "Places",
      "设置": "Settings",
      "查找记录": "Find records",
      "输入关键词": "Enter keywords",
      "从": "From",
      "到": "To",
      "查询": "Search",
      "刷新": "Refresh",
      "正在读取…": "Reading…",
      "范围内有 {v0} 处记录缺口。": "There are {v0} recording gaps in this range.",
      "{v0} 条位置尚未解析城市。": "{v0} locations do not have a resolved city yet.",
      "共 {v0} 条，已显示 {v1} 条。{v2}{v3}": "{v0} total, {v1} shown. {v2}{v3}",
      "这个范围还没有记录。未记录不代表没有发生。": "No records in this range yet. Missing records do not mean nothing happened.",
      "当前无法安全读取记录。请先检查启用条件。": "Records cannot currently be read safely. Check the requirements first.",
      "勾选记录后，到“设置”生成加密备份。": "Select records, then create an encrypted backup in Settings.",
      "备份": "Back up",
      " · 待核实": " · Unverified",
      "加载更多": "Load more",
      "Droy 记忆": "Droy Memory"
};
    const namespace = 'droy.memory';
    const interpolate = (text, params) => text.replace(/\{(\w+)\}/g, (match, name) => params && name in params ? String(params[name]) : match);
    const retainedMessages = Object.entries(messages).flatMap(([source, english]) => [source, english].map(template => {
      const names = [];
      const pattern = template.split(/(\{\w+\})/g).map(part => {
        if (/^\{\w+\}$/.test(part)) { names.push(part.slice(1, -1)); return '([\\s\\S]*?)'; }
        return part.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
      }).join('');
      return { source, english, names, pattern: new RegExp(`^${pattern}$`) };
    }));
    function tr(source, params) {
      if (typeof source !== 'string') return source;
      const english = locale?.getSnapshot().active === 'en';
      if (Object.hasOwn(messages, source)) return interpolate(english ? messages[source] : source, params);
      // Only product status/error surfaces use this path; user records and drafts are never translated.
      for (const message of retainedMessages) {
        const match = message.pattern.exec(source);
        if (match) return interpolate(english ? message.english : message.source,
          Object.fromEntries(message.names.map((name, index) => [name, match[index + 1]])));
      }
      return source;
    }
    function installLocale(ctx) {
      locale = ctx.locale;
      if (!locale) return;
      const dispose = locale.register(namespace, { zh: Object.fromEntries(Object.keys(messages).map(key => [key, key])), en: messages });
      ctx.effect(() => dispose);
    }
    function useLocale() {
      if (locale) React.useSyncExternalStore(listener => locale.subscribe(listener), () => locale.getSnapshot(), () => locale.getSnapshot());
    }

    const styles = /* DROY_MEMORY_STYLES */ '';
    const archiveMaxBytes = 16 * 1024 * 1024;
    const errors = new Proxy({
      MEMORY_DISABLED: tr("Droy记忆已关闭。你仍可查看或删除已保存的记录。"),
      MEMORY_REQUIRED_PLUGIN_MISSING: tr("记忆服务当前不可用，请检查相关插件是否已启用。"),
      MEMORY_PROTECTION_NOT_READY: tr("记忆服务尚未就绪，请稍后刷新。"),
      MEMORY_EXECUTION_NOT_READY: tr("记忆服务尚未就绪，采集和处理保持关闭。"),
      HISTORY_EXECUTION_NOT_READY: tr("记忆服务尚未就绪，请稍后刷新。"),
      MEMORY_RECALL_DISABLED: tr("记忆召回已关闭，可在设置中开启。"),
      MEMORY_CONFLICT: tr("这条记录已发生变化。请刷新后核对，再决定是否修改。"),
      MEMORY_REVISION_CONFLICT: tr("这条记录已发生变化。请刷新后核对，再决定是否修改。"),
      MEMORY_CURSOR_STALE: tr("记录已有更新，请重新查询。"),
      MEMORY_NOT_FOUND: tr("记录不存在或已被删除。"),
      MEMORY_RESULT_TOO_LARGE: tr("这一段内容较长，请缩小查询范围。"),
      MEMORY_CONTEXT_BUDGET: tr("本轮记忆读取已达到预算，请缩小范围或在新对话继续。"),
      MEMORY_SOURCE_LIMIT: tr("关联来源较多，请缩小记录范围。"),
      PRIVACY_SOURCE_LIMIT: tr("本次关联来源较多，请缩小范围或新建对话。"),
      PRIVACY_ENDPOINT_NOT_AUTHORIZED: tr("当前处理端点未获授权，请在处理授权中检查。"),
      PRIVACY_SESSION_FORGOTTEN: tr("这段对话包含已忘记的内容，请新建对话继续。"),
      PRIVACY_SESSION_UNKNOWN: tr("这段旧对话的隐私状态无法确认，请新建对话继续。"),
      MEMORY_FEATURE_UNAVAILABLE: tr("记忆插件尚未就绪，请检查插件是否已启用。"),
      MEMORY_INVALID_REQUEST: tr("输入不完整或格式有误，请检查后重试。"),
      MEMORY_INVALID_INPUT: tr("输入不完整或格式有误，请检查后重试。"),
      MEMORY_INVALID_TIME: tr("时间范围无效，请检查开始和结束时间。"),
      MEMORY_CAPACITY_REACHED: tr("记忆存储空间不足，请先清理或导出记录。"),
      MEMORY_CAPTURE_UNAVAILABLE: tr("当前设备未连接活动和位置记录服务。"),
      MEMORY_ENDPOINTS_UNAVAILABLE: tr("当前还没有可供授权的模型处理端点。"),
      MEMORY_INVALID_PASSPHRASE: tr("请使用至少 12 个字符的备份密码。"),
      MEMORY_ARCHIVE_INVALID: tr("备份文件或密码不正确，未导入记录。"),
      MEMORY_IMPORT_REQUIRES_EMPTY_SPACE: tr("当前仅支持将备份导入空记忆库，已有记录未被覆盖。"),
      MEMORY_EXPORT_SCOPE_REQUIRED: tr("请先选择需要导出的记录。"),
      MEMORY_EXPORT_TOO_LARGE: tr("所选记录超过单次备份大小，请缩小范围。"),
      MEMORY_ARCHIVE_TOO_LARGE: tr("备份文件超过 16 MiB，请选择较小的备份。"),
      MEMORY_ARCHIVE_UNAVAILABLE: tr("当前设备尚未连接备份服务。"),
      MEMORY_ARCHIVE_RESPONSE_INVALID: tr("备份结果无效，未打开下载。"),
      MEMORY_ARCHIVE_EXPIRED: tr("备份下载已过期，请重新生成。"),
      MEMORY_ARCHIVE_SAVE_UNAVAILABLE: tr("当前设备尚未连接备份保存入口，请更新 Droy 后重试。"),
      MEMORY_MAINTENANCE_UNAVAILABLE: tr("当前设备尚未连接整理服务。"),
      MEMORY_EXTRACTION_DAILY_BUDGET: tr("今天的整理预算已用完，内容会保留，稍后再试。"),
      MEMORY_EXTRACTION_INPUT_LIMIT: tr("这一片段超过整理大小限制，未发送给模型。"),
      MEMORY_EXTRACTION_SOURCE_EXPIRED: tr("原始片段已到期，无法重新整理。"),
      MEMORY_EXTRACTION_INTERRUPTED: tr("上次整理被中断，等待你选择是否重试。"),
      MEMORY_SOURCE_CHANGED: tr("原始片段已有变化，请重新选择需要整理的内容。"),
      MEMORY_MAINTENANCE_STOPPED: tr("整理已停止。"),
    }, { get: (values, key) => tr(values[key]) });
    const label = new Proxy({ fact: tr("事实"), preference: tr("偏好"), decision: tr("决定"), task_summary: tr("任务摘要"), event: tr("活动"),
      activity: tr("应用活动"), location: tr("位置样本"), visit: tr("停留候选"), gap: tr("记录缺口"), device: tr("设备状态"), note: tr("事件"),
      user_statement: tr("用户陈述"), tool_observation: tr("工具观察"), execution_receipt: tr("执行回执"), model_inference: tr("模型推断"),
      user: tr("用户"), droy: 'Droy', droy_active: tr("Droy 活动期间"), unknown: tr("来源未确定"),
      off: tr("已关闭"), paused: tr("已暂停"), collecting: tr("采集中"), waiting_permission: tr("等待系统权限"),
      waiting_execution_privacy: tr("暂不具备启用条件"), service_interrupted: tr("服务已中断"), storage_limited: tr("存储空间受限") }, { get: (values, key) => tr(values[key]) });
    const displayTime = value => value ? new Date(value).toLocaleString(locale?.getSnapshot().active === 'en' ? 'en-US' : 'zh-CN') : tr("时间未知");
    const recordText = row => row.payload?.text ?? (['location', 'visit'].includes(row.payload?.eventType)
      ? `${row.payload.placeName || tr("未命名地点")} · ${row.payload.city || tr("城市未解析")}`
      : row.payload?.eventType === 'gap' ? row.payload.reason : row.payload?.appLabel || row.payload?.appId || label[row.payload?.eventType] || label[row.kind] || tr("记录"));

    async function saveMemoryArchive(target, download) {
      const match = /^\/droy\/memory\/archive\/download\/([a-f0-9]{64})$/.exec(download?.downloadUrl ?? '');
      const expiry = new Date(download?.expiresAt).getTime();
      if (!match || !Number.isFinite(expiry)) throw Object.assign(new Error('Invalid archive handle'), { code: 'MEMORY_ARCHIVE_RESPONSE_INVALID' });
      if (Date.now() >= expiry) throw Object.assign(new Error('Expired archive handle'), { code: 'MEMORY_ARCHIVE_EXPIRED' });
      if (typeof target.DroyMobile?.saveMemoryArchive !== 'function') throw Object.assign(new Error('Archive save unavailable'), { code: 'MEMORY_ARCHIVE_SAVE_UNAVAILABLE' });
      return target.DroyMobile.saveMemoryArchive(match[1]);
    }

    function createMemoryController(request, uuid, archiveRequest) {
      let state = { status: null, tab: 'memory', phase: 'loading', busy: false, error: '', notice: '', items: [],
        result: null, selected: null, filters: { text: '', from: '', to: '' }, endpoints: [], apps: [], captureConfig: null, exportIds: [], download: null };
      const listeners = new Set(); let disposed = false, generation = 0, statusGeneration = 0, pendingMutation, busyOperations = 0;
      const update = patch => { if (!disposed) { state = { ...state, ...patch }; for (const listener of listeners) listener(); } };
      const report = error => {
        const code = /^(?:MEMORY|PRIVACY|HISTORY|EXECUTION)_[A-Z0-9_]{1,64}$/.test(error?.code ?? '') ? error.code : '';
        update({ error: errors[code] || (error?.name === 'AbortError' ? tr("操作已取消。") : tr("操作未完成，请检查状态后重试。{v0}", {v0: code ? `（${code}）` : ''})) });
      };
      function queryArgs() {
        const filter = state.filters, args = {};
        if (filter.text.trim()) args.text = filter.text.trim();
        if (filter.from) args.from = new Date(filter.from).toISOString();
        if (filter.to) args.to = new Date(filter.to).toISOString();
        if (state.tab === 'activity') { args.kinds = ['event']; args.eventTypes = ['activity', 'device', 'gap']; }
        if (state.tab === 'places') { args.kinds = ['event']; args.eventTypes = ['location', 'visit']; }
        if (state.tab === 'memory') args.kinds = ['fact', 'preference', 'decision', 'task_summary'];
        return args;
      }
      async function query(append = false) {
        if (!state.status?.managementAvailable || state.tab === 'settings') return;
        const own = ++generation, args = queryArgs(), selectedId = state.selected?.recordId;
        if (append && state.result?.nextCursor) args.cursor = state.result.nextCursor;
        update({ phase: 'loading', error: '' });
        try {
          const result = await request('query', args);
          if (own !== generation || disposed) return;
          const items = append ? [...state.items, ...result.items] : result.items;
          update({ phase: 'ready', items, result, selected: items.find(row => row.recordId === selectedId) ?? null });
        } catch (error) { if (own === generation) { update({ phase: 'error' }); report(error); } }
      }
      async function refresh() {
        const own = ++statusGeneration;
        generation++;
        try {
          const status = await request();
          if (disposed || own !== statusGeneration) return;
          generation++;
          update({ status, captureConfig: status.capture?.configuration ?? state.captureConfig, error: '' });
          if (status.managementAvailable) await query(); else update({ phase: 'ready', items: [], result: null, selected: null, exportIds: [], download: null });
        } catch (error) { if (own === statusGeneration) { update({ phase: 'error' }); report(error); } }
      }
      async function settingsData() {
        try { const endpoints = await request('endpoints'); update({ endpoints: Array.isArray(endpoints) ? endpoints : endpoints.items ?? [] }); }
        catch (error) { report(error); }
      }
      async function mutate(operation, args, notice) {
        const stopping = operation === 'settings' && args.enabled === false || operation === 'capture' &&
          (args.operation === 'pause' || args.operation === 'configure' && (args.args?.paused === true ||
            ['enabled', 'activityEnabled', 'pageTextEnabled', 'deviceStateEnabled', 'locationEnabled'].some(key => args.args?.[key] === false)));
        if (state.busy && !stopping || disposed) return;
        const needsId = ['record', 'revise', 'forget'].includes(operation);
        const fingerprint = JSON.stringify({ operation, args });
        if (needsId && pendingMutation?.fingerprint !== fingerprint) pendingMutation = { fingerprint, operationId: `ui:${uuid()}` };
        busyOperations++; update({ busy: true, error: '', notice: '' });
        try {
          const result = await request(operation, { ...args, ...(needsId ? { operationId: pendingMutation.operationId } : {}) });
          if (needsId && pendingMutation?.fingerprint === fingerprint) pendingMutation = undefined;
          if (operation === 'forget') update({ exportIds: state.exportIds.filter(id => id !== args.recordId), download: null });
          await refresh();
          if (['grant', 'revoke'].includes(operation)) await settingsData();
          update({ notice });
          return result;
        } catch (error) {
          if (operation === 'settings' && args.enabled === true) await refresh();
          report(error);
        }
        finally { busyOperations--; update({ busy: busyOperations > 0 }); }
      }
      async function archive(operation, args) {
        if (state.busy || disposed) return;
        if (!archiveRequest) { report({ code: 'MEMORY_ARCHIVE_UNAVAILABLE' }); return; }
        if (!state.status?.managementAvailable || operation === 'import' && !state.status?.available) { report({ code: 'MEMORY_PROTECTION_NOT_READY' }); return; }
        busyOperations++; update({ busy: true, error: '', notice: '', download: null });
        try {
          const result = await archiveRequest(operation, args);
          if (disposed) return;
          if (operation === 'export') {
            if (!state.status?.managementAvailable) throw { code: 'MEMORY_PROTECTION_NOT_READY' };
            if (typeof result.downloadUrl !== 'string' || !/^\/droy\/memory\/archive\/download\/[a-f0-9]{64}$/.test(result.downloadUrl)) throw { code: 'MEMORY_ARCHIVE_RESPONSE_INVALID' };
            update({ download: { downloadUrl: result.downloadUrl, expiresAt: result.expiresAt }, notice: tr("加密备份已准备好，请点击保存。下载链接仅在本机短时有效。") });
          } else { await refresh(); update({ notice: tr("备份已恢复到本机记忆库。处理端点仍需在本机单独授权。") }); }
          return result;
        } catch (error) { report(error); }
        finally { busyOperations--; update({ busy: busyOperations > 0 }); }
      }
      return {
        subscribe(listener) { listeners.add(listener); return () => listeners.delete(listener); }, getSnapshot: () => state,
        refresh, query, report, mutate, archive,
        chooseExport(recordId, checked) {
          const ids = checked ? [...new Set([...state.exportIds, recordId])] : state.exportIds.filter(id => id !== recordId);
          if (ids.length > 1000) { report({ code: 'MEMORY_EXPORT_TOO_LARGE' }); return; }
          update({ exportIds: ids, download: null });
        },
        clearExport() { update({ exportIds: [], download: null }); },
        setFilters(patch) { update({ filters: { ...state.filters, ...patch } }); },
        async setTab(tab) {
          if (!['memory', 'activity', 'places', 'settings'].includes(tab)) return;
          generation++; update({ tab, selected: null, items: [], result: null, error: '', notice: '', phase: 'ready' });
          if (tab === 'settings') await settingsData(); else await query();
        },
        select(row) { update({ selected: row, notice: '', error: '' }); },
        async loadApps() {
          try { const result = await request('capture', { operation: 'apps', args: {} }); update({ apps: result.items ?? result.apps ?? result }); }
          catch (error) { report(error); }
        },
        dispose() { disposed = true; generation++; listeners.clear(); },
      };
    }

    function apply(ctx) {
      installLocale(ctx);
      const target = window, doc = target.document, abort = new AbortController();
      const request = async (operation, args = {}) => {
        const response = await target.fetch('/droy/memory', { credentials: 'same-origin', cache: 'no-store', redirect: 'error', signal: abort.signal,
          ...(operation ? { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ operation, args }) } : {}) });
        const body = await response.json();
        if (!response.ok || body.ok !== true) throw Object.assign(new Error('Memory request failed'), { code: body.error?.code });
        return body.result;
      };
      const archiveRequest = async (operation, args) => {
        const response = await target.fetch(`/droy/memory/archive/${operation}`, { method: 'POST', credentials: 'same-origin', cache: 'no-store', redirect: 'error', signal: abort.signal,
          headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(args) });
        const body = await response.json();
        if (!response.ok || body.ok !== true) throw Object.assign(new Error('Archive request failed'), { code: body.error?.code });
        return body.result;
      };
      const controller = createMemoryController(request, () => target.crypto.randomUUID(), archiveRequest);
      const useState = () => React.useSyncExternalStore(controller.subscribe, controller.getSnapshot, controller.getSnapshot);
      const action = promise => { void Promise.resolve(promise).catch(controller.report); };
      function Toggle({ title, hint, checked, disabled, change }) {
        return h('div', { className: 'droy-memory-toggle' }, h('div', null, h('span', null, title), hint ? h('p', null, hint) : null),
          h(Switch, { label: title, checked: !!checked, disabled: !!disabled, onChange: change, className: 'droy-memory-touch-switch' }));
      }
      function RecordEditor({ row, busy, readOnly, onClose }) {
        const [text, setText] = React.useState(row?.payload?.text ?? ''), [kind, setKind] = React.useState(row?.kind ?? 'fact');
        const [place, setPlace] = React.useState(row?.payload?.placeName ?? ''), [city, setCity] = React.useState(row?.payload?.city ?? '');
        const [forgetting, setForgetting] = React.useState(false);
        const location = ['location', 'visit'].includes(row?.payload?.eventType), editable = !row || !!row.payload?.text || location;
        const save = () => {
          const payload = location ? { ...row.payload, ...(place.trim() ? { placeName: place.trim() } : {}), ...(city.trim() ? { city: city.trim() } : {}) }
            : { ...row?.payload, text: text.trim() };
          if (location) { if (!place.trim()) delete payload.placeName; if (!city.trim()) delete payload.city; }
          return controller.mutate(row ? 'revise' : 'record', row ? { recordId: row.recordId, expectedRevisionId: row.revisionId, patch: { payload } }
            : { kind, text: text.trim() }, row ? tr("记录已更新。包含旧内容的对话可能需要重新开始。") : tr("已保存记忆。"));
        };
        return h('section', { className: 'droy-memory-editor', 'aria-label': row ? tr("记录详情") : tr("新增记忆"), 'data-droy-memory-editor': true },
          h('h3', null, row ? tr("记录详情") : tr("新增记忆")),
          !row ? h('label', null, tr("类型"), h('select', { value: kind, onChange: event => setKind(event.target.value) },
            ['fact', 'preference', 'decision', 'task_summary'].map(value => h('option', { key: value, value }, label[value])))) : null,
          editable ? location ? h(React.Fragment, null,
            h('label', null, tr("地点名称"), h('input', { value: place, maxLength: 160, onChange: event => setPlace(event.target.value), placeholder: tr("例如：家、办公室") })),
            h('label', null, tr("城市（由你确认）"), h('input', { value: city, maxLength: 160, onChange: event => setCity(event.target.value), placeholder: tr("未知时留空") })))
            : h('label', null, tr("内容"), h('textarea', { value: text, rows: 5, maxLength: 4000, onChange: event => setText(event.target.value), placeholder: tr("写下你希望 Droy 记住的事情") }))
            : h('p', null, recordText(row)),
          row ? h('dl', { className: 'droy-memory-metadata' },
            h('dt', null, tr("发生时间")), h('dd', null, displayTime(row.occurredAt)),
            h('dt', null, tr("依据")), h('dd', null, label[row.assertion] || row.assertion),
            h('dt', null, tr("行为归属")), h('dd', null, label[row.actor] || row.actor),
            h('dt', null, tr("来源")), h('dd', null, tr("{v0} 个直接来源{v1}", {v0: row.sourceRefs?.length || 0, v1: row.lineageRefs?.length ? tr("，{v0} 个关联来源", {v0: row.lineageRefs.length}) : ''})),
            h('dt', null, tr("保留")), h('dd', null, row.retention?.mode === 'manual' ? tr("保留至你删除") : tr("到期：{v0}", {v0: displayTime(row.retention?.expiresAt)})),
            ...(location ? [h('dt', { key: 'accuracy-label' }, tr("定位精度")), h('dd', { key: 'accuracy' }, tr("约 {v0} 米{v1}", {v0: Math.round(row.payload.accuracyMeters), v1: row.payload.stale ? tr("，样本已过旧") : ''})),
              h('dt', { key: 'coordinate-label' }, tr("坐标")), h('dd', { key: 'coordinate' }, `${row.payload.latitude}, ${row.payload.longitude}`)] : [])) : null,
          h('div', { className: 'droy-memory-actions' }, editable ? h(Button, { disabled: readOnly || busy || (!location && !text.trim()), onClick: () => action(save()), 'data-droy-memory-save': true }, busy ? tr("保存中…") : row ? tr("保存纠正") : tr("保存记忆")) : null,
            row ? h(Button, { variant: 'outline', disabled: busy, onClick: () => setForgetting(true), 'data-droy-memory-forget': true }, tr("忘记")) : null,
            h(Button, { variant: 'ghost', disabled: busy, onClick: onClose }, tr("关闭"))),
          forgetting ? h('div', { className: 'droy-memory-confirm', role: 'alert' },
            h('p', null, tr("忘记这条记录及相关派生内容？此操作不能撤销，含旧记忆的对话可能需要重新开始。")),
            h(Button, { variant: 'outline', disabled: busy, onClick: () => setForgetting(false) }, tr("取消")),
            h(Button, { disabled: busy, onClick: () => action(controller.mutate('forget', { recordId: row.recordId, expectedRevisionId: row.revisionId }, tr("已忘记这条记录。"))), 'data-droy-memory-forget-confirm': true }, tr("确认忘记"))) : null);
      }
      function CaptureSettings({ state }) {
        const config = state.status?.capture?.configuration ?? state.captureConfig ?? {}, available = state.status?.available;
        const [allow, setAllow] = React.useState(config.allowedPackages || []);
        const [exclude, setExclude] = React.useState(config.excludedPackages || []), [appSearch, setAppSearch] = React.useState('');
        React.useEffect(() => { setAllow(config.allowedPackages || []); setExclude(config.excludedPackages || []); }, [JSON.stringify(config.allowedPackages), JSON.stringify(config.excludedPackages)]);
        const configure = patch => controller.mutate('capture', { operation: 'configure', args: patch }, tr("采集设置已更新。"));
        const appRows = new Map((Array.isArray(state.apps) ? state.apps : []).filter(app => typeof app.packageName === 'string').map(app => [app.packageName, app]));
        for (const id of [...allow, ...exclude]) if (!appRows.has(id)) appRows.set(id, { packageName: id, label: id });
        const toggleApp = (id, checked, excluded) => {
          if (excluded) { setExclude(previous => checked ? [...new Set([...previous, id])] : previous.filter(value => value !== id)); if (checked) setAllow(previous => previous.filter(value => value !== id)); }
          else { setAllow(previous => checked ? [...new Set([...previous, id])] : previous.filter(value => value !== id)); if (checked) setExclude(previous => previous.filter(value => value !== id)); }
        };
        return h('section', null, h('h3', null, tr("手机活动与位置")),
          h('p', null, tr("当前状态：{v0}", {v0: label[state.status?.capture?.state] || tr("尚未开始记录")})),
          h(Toggle, { title: tr("手机活动记录"), hint: tr("仅记录你开启的活动来源和允许的应用。位置使用下面的独立开关。"), checked: config.enabled, disabled: (state.busy || !available) && !config.enabled, change: value => action(configure({ enabled: value })) }),
          config.enabled || config.locationEnabled ? h(Button, { variant: 'outline', disabled: (state.busy || !available) && config.paused, onClick: () => action(configure({ paused: !config.paused })) }, config.paused ? tr("恢复记录") : tr("暂停所有记录")) : null,
          [['activityEnabled', tr("应用活动"), tr("记录允许应用的前台活动，不能证明你始终在注视屏幕。")],
            ['pageTextEnabled', tr("页面文字"), tr("仅保存允许应用的部分可见文字，跳过输入和密码字段；默认关闭。")],
            ['deviceStateEnabled', tr("设备状态"), tr("记录电量、充电和网络类型的变化。")],
            ['locationEnabled', tr("位置记录"), tr("独立开关和系统权限；不自动把未知位置命名成城市。")]].map(([key, title, hint]) => h(Toggle, { key, title, hint, checked: config[key], disabled: (state.busy || !available) && !config[key], change: value => action(configure({ [key]: value })) })),
          h('div', { className: 'droy-memory-actions' }, h(Button, { variant: 'outline', disabled: state.busy, onClick: () => action(controller.mutate('capture', { operation: 'permission', args: { source: 'location' } }, tr("请在系统页面管理位置权限。"))) }, tr("管理位置权限"))),
          h('div', { className: 'droy-memory-actions' }, h(Button, { variant: 'outline', disabled: state.busy, onClick: () => action(controller.loadApps()) }, tr("选择记录应用")),
            h('span', { className: 'droy-memory-hint' }, tr("允许 {v0} 个，排除 {v1} 个", {v0: allow.length, v1: exclude.length}))),
          appRows.size ? h(React.Fragment, null, h('label', { className: 'droy-memory-field' }, tr("查找应用"), h('input', { type: 'search', value: appSearch, onChange: event => setAppSearch(event.target.value), placeholder: tr("应用名称") })),
            h('div', { className: 'droy-memory-apps', 'aria-label': tr("应用记录范围") }, [...appRows.values()].filter(app => `${app.label || app.name || ''} ${app.packageName}`.toLowerCase().includes(appSearch.toLowerCase())).map(app =>
              h('div', { key: app.packageName, className: 'droy-memory-app' }, h('span', null, app.label || app.name || app.packageName),
                h('label', null, h('input', { type: 'checkbox', checked: allow.includes(app.packageName), disabled: state.busy, onChange: event => toggleApp(app.packageName, event.target.checked, false) }), tr("记录")),
                h('label', null, h('input', { type: 'checkbox', checked: exclude.includes(app.packageName), disabled: state.busy, onChange: event => toggleApp(app.packageName, event.target.checked, true) }), tr("排除")))))) : null,
          h(Button, { variant: 'outline', disabled: state.busy, onClick: () => action(configure({ allowedPackages: allow, excludedPackages: exclude })) }, tr("保存应用范围")),
          h('p', { className: 'droy-memory-hint' }, tr("排除规则优先。暂停或关闭的时段不会事后补采，麦克风和摄像头不会随此开启。")));
      }
      function Endpoint({ row, busy, available }) {
        const [selected, setSelected] = React.useState(row.categories ?? ['memory']);
        return h('article', { className: 'droy-memory-endpoint' }, h('strong', null, row.label || row.model || tr("模型端点")),
          h('p', { className: 'droy-memory-endpoint-url' }, row.url), h('p', null, tr("用途：{v0}", {v0: row.purpose || 'conversation'})),
          h('fieldset', null, h('legend', null, tr("允许处理的数据")), [['memory', tr("个人记忆")], ['activity', tr("活动记录")], ['location', tr("位置")], ['derived', tr("派生内容")]].map(([key, text]) => h('label', { key },
            h('input', { type: 'checkbox', checked: selected.includes(key), disabled: busy, onChange: event => setSelected(event.target.checked ? [...selected, key] : selected.filter(value => value !== key)) }), text))),
          h('div', { className: 'droy-memory-actions' }, h(Button, { disabled: busy || !available || !selected.length,
            onClick: () => action(controller.mutate('grant', { id: row.id, purpose: row.purpose, categories: selected }, tr("处理授权已保存。"))) }, tr("允许处理所选数据")),
            h(Button, { variant: 'outline', disabled: busy, onClick: () => action(controller.mutate('revoke', { id: row.id, purpose: row.purpose }, tr("已撤回处理授权。"))) }, tr("撤回授权"))));
      }
      function MaintenanceSettings({ state }) {
        const maintenance = state.status?.maintenance, rows = maintenance?.heldSources ?? [];
        return h('section', { 'data-droy-memory-maintenance': true }, h('h3', null, tr("整理进度")),
          h('p', null, maintenance ? tr("待整理 {v0} 项，待处理 {v1} 项{v2}。", {v0: maintenance.pending ?? 0, v1: maintenance.held ?? 0, v2: maintenance.busy ? tr("，正在处理") : ''}) : tr("尚未开始整理。")),
          maintenance?.lastCode && !['MEMORY_DISABLED', 'MEMORY_MAINTENANCE_STOPPED'].includes(maintenance.lastCode)
            ? h('p', { role: 'status' }, errors[maintenance.lastCode] || tr("上次整理未完成，请检查处理授权或待处理片段。")) : null,
          maintenance?.budget ? h('p', { className: 'droy-memory-hint' }, tr("今日已预留 {v0} 次处理，{v1} 个估算 token。失败和中断不会退回已预留的预算。", {v0: maintenance.budget.calls ?? 0, v1: maintenance.budget.tokens ?? 0})) : null,
          h('p', { className: 'droy-memory-hint' }, tr("失败的片段不会自动反复重试。明确重试会使用已授权端点，并继续遵守每日预算；丢弃只取消这次整理，不删除原始记录。")),
          rows.map((row, index) => h('article', { key: row.id, className: 'droy-memory-held' },
            h('strong', null, tr("待处理片段 {v0}", {v0: index + 1})), h('p', null, errors[row.code] || tr("上次整理未完成，需要你选择后续操作。")),
            h('div', { className: 'droy-memory-actions' }, h(Button, { disabled: state.busy || !state.status?.available || !state.status?.maintenanceAvailable,
              onClick: () => action(controller.mutate('maintenance', { operation: 'retry', id: row.id }, tr("已提交这次重试，请查看整理进度。"))) }, tr("重试这次整理")),
            h(Button, { variant: 'outline', disabled: state.busy || !state.status?.managementAvailable || !state.status?.maintenanceAvailable,
              onClick: () => action(controller.mutate('maintenance', { operation: 'discard', id: row.id }, tr("已丢弃这次整理，原始记录仍按保留设置管理。"))) }, tr("丢弃这次整理"))))));
      }
      function ArchiveSettings({ state }) {
        const [exportPassword, setExportPassword] = React.useState(''), [confirmPassword, setConfirmPassword] = React.useState('');
        const [importPassword, setImportPassword] = React.useState(''), [file, setFile] = React.useState(null), [reading, setReading] = React.useState(false);
        const fileInput = React.useRef(null), mounted = React.useRef(true), busy = state.busy || reading, canManage = state.status?.archiveAvailable && state.status?.managementAvailable;
        React.useEffect(() => { mounted.current = true; return () => { mounted.current = false; }; }, []);
        const exportNow = async () => {
          try { await controller.archive('export', { recordIds: [...state.exportIds], passphrase: exportPassword }); }
          finally { setExportPassword(''); setConfirmPassword(''); }
        };
        const importNow = async () => {
          if (!file || reading) return;
          if (!Number.isSafeInteger(file.size) || file.size < 1 || file.size > archiveMaxBytes) { controller.report({ code: 'MEMORY_ARCHIVE_TOO_LARGE' }); return; }
          setReading(true);
          try {
            const bytes = new Uint8Array(await file.arrayBuffer());
            if (!mounted.current) return;
            if (bytes.length !== file.size || bytes.length > archiveMaxBytes) throw { code: 'MEMORY_ARCHIVE_INVALID' };
            let binary = '';
            for (let offset = 0; offset < bytes.length; offset += 8192) binary += String.fromCharCode(...bytes.subarray(offset, offset + 8192));
            await controller.archive('import', { archiveBase64: target.btoa(binary), passphrase: importPassword });
          } catch (error) { controller.report(error); }
          finally { setReading(false); setImportPassword(''); setFile(null); if (fileInput.current) fileInput.current.value = ''; }
        };
        return h('section', { className: 'droy-memory-archive', 'data-droy-memory-archive': true }, h('h3', null, tr("加密备份与恢复")),
          h('p', null, tr("在记忆、活动或地点列表中勾选要备份的记录。备份使用你设置的密码加密，密码不会保存，也不能找回。单个文件最多 16 MiB。")),
          h('p', { 'data-droy-memory-export-count': true }, tr("已选 {v0} 条记录。", {v0: state.exportIds.length})),
          state.exportIds.length ? h(Button, { variant: 'ghost', disabled: busy, onClick: controller.clearExport }, tr("清空备份选择")) : null,
          h('label', { className: 'droy-memory-field' }, tr("备份密码（至少 12 个字符）"), h('input', { type: 'password', autoComplete: 'new-password', value: exportPassword,
            maxLength: 256, disabled: busy, onChange: event => setExportPassword(event.target.value) })),
          h('label', { className: 'droy-memory-field' }, tr("再次输入密码"), h('input', { type: 'password', autoComplete: 'new-password', value: confirmPassword,
            maxLength: 256, disabled: busy, onChange: event => setConfirmPassword(event.target.value) })),
          h(Button, { disabled: busy || !canManage || !state.exportIds.length || exportPassword.length < 12 || exportPassword !== confirmPassword,
            onClick: () => action(exportNow()) }, tr("生成选中记录的加密备份")),
          state.download ? h('div', { className: 'droy-memory-download' }, h(Button, { disabled: busy || !canManage,
            onClick: () => action(saveMemoryArchive(target, state.download)) }, tr("保存加密备份")),
            h('p', { className: 'droy-memory-hint' }, tr("链接到期：{v0}。在系统文件选择器中选择保存位置后才会写入文件，保存完成前请勿关闭应用。", {v0: displayTime(state.download.expiresAt)}))) : null,
          h('h4', null, tr("从备份恢复")), h('p', null, tr("目前仅支持恢复到空记忆库，已有记录不会被覆盖。备份中的处理授权不会导入，需要在本机重新选择可信端点。")),
          h('label', { className: 'droy-memory-field' }, tr("选择加密备份"), h('input', { ref: fileInput, type: 'file', accept: '.droy,.droy-memory,application/octet-stream', disabled: busy || !canManage,
            onChange: event => { const selected = event.target.files?.[0] ?? null; if (selected && (selected.size < 1 || selected.size > archiveMaxBytes)) { event.target.value = ''; setFile(null); controller.report({ code: 'MEMORY_ARCHIVE_TOO_LARGE' }); } else setFile(selected); } })),
          h('label', { className: 'droy-memory-field' }, tr("备份密码"), h('input', { type: 'password', autoComplete: 'off', value: importPassword,
            maxLength: 256, disabled: busy, onChange: event => setImportPassword(event.target.value) })),
          h(Button, { disabled: busy || !canManage || !state.status?.available || !file || importPassword.length < 12,
            onClick: () => action(importNow()) }, reading ? tr("正在读取备份…") : tr("恢复到空记忆库")));
      }
      function Settings({ state }) {
        return h('div', { className: 'droy-memory-settings' }, h('section', null, h('h3', null, tr("记忆使用")),
          h(Toggle, { title: tr("对话中按需召回"), hint: tr("需要个人背景时才查询，不会每句话注入全部记忆。"), checked: state.status?.settings?.recallEnabled,
            disabled: state.busy || (!state.status?.available && !state.status?.settings?.recallEnabled),
            change: value => action(controller.mutate('settings', { recallEnabled: value }, tr("召回设置已更新。"))) }),
          h(Toggle, { title: tr("自动整理新增内容"), hint: tr("默认关闭。仅使用已授权端点，在预算内批量处理；处理失败不会切换服务。"), checked: state.status?.settings?.automaticExtraction,
            disabled: state.busy || (!state.status?.available && !state.status?.settings?.automaticExtraction),
            change: value => action(controller.mutate('settings', { automaticExtraction: value }, tr("整理设置已更新。"))) })),
          h(CaptureSettings, { state }), h(MaintenanceSettings, { state }), h('section', null, h('h3', null, tr("处理授权")),
            h('p', null, tr("记录保存在本机。只有你明确允许的模型端点可以处理所选数据；授权云端端点会向该服务发送相关内容。")),
            h('p', null, tr("允许模型处理不等于允许对外发送；涉及外发操作会单独确认。")),
            state.endpoints.length ? state.endpoints.map(row => h(Endpoint, { key: row.id + ':' + row.purpose, row, busy: state.busy, available: state.status?.available }))
              : h('p', { className: 'droy-memory-empty' }, tr("尚无可授权的处理端点。请先在 DSH 中配置模型。"))),
          null);
      }
      function MemorySettings() {
        useLocale();
        const state = useState();
        React.useEffect(() => { action(controller.setTab('settings')); action(controller.refresh()); }, []);
        React.useEffect(() => {
          const panel = doc.querySelector('[data-droy-settings-panel]');
          if (!panel) return;
          const reveal = () => {
            const field = doc.activeElement;
            if (field?.matches('input,textarea') && field.closest('[data-droy-memory]'))
              field.scrollIntoView({ block: 'nearest', inline: 'nearest' });
          };
          const observer = new target.ResizeObserver(reveal);
          observer.observe(panel); panel.addEventListener('focusin', reveal);
          target.visualViewport?.addEventListener('resize', reveal);
          return () => { observer.disconnect(); panel.removeEventListener('focusin', reveal); target.visualViewport?.removeEventListener('resize', reveal); };
        }, []);
        return h('div', { className: 'droy-memory', 'data-droy-memory': true },
          h(Toggle, { title: tr("Droy记忆"), checked: state.status?.settings?.enabled, disabled: !state.status || state.busy && !state.status.settings.enabled,
            hint: tr("让 Droy 记住你提供的信息，并记录你单独授权的手机活动和位置。"),
            change: value => action(controller.mutate('settings', { enabled: value }, value ? tr("Droy记忆已开启。") : tr("Droy记忆已关闭。"))) }),
          state.status && !state.status.gate.ready ? h('div', { className: 'droy-memory-blocked', role: 'status', 'data-droy-memory-blocked': true },
            h('strong', null, tr("记忆服务暂不可用")), h('p', null,
              tr("开启记忆后会初始化本地服务。服务就绪前不会采集或创建记录，已有采集仍可暂停或关闭。"))) : null,
          state.error ? h('p', { className: 'droy-memory-error', role: 'alert' }, tr(state.error)) : null,
          state.notice ? h('p', { className: 'droy-memory-notice', role: 'status' }, tr(state.notice)) : null,
          h(Settings, { state }));
      }
      const style = doc.createElement('style'); style.id = 'droy-memory-style'; style.textContent = styles; doc.head.appendChild(style);
      ctx.slots.inject('settings.section', () => ctx.slots.register({ name: 'settings.section', id: 'droy-memory', order: 17, label: () => tr("Droy 记忆") }, MemorySettings));
      ctx.effect(() => () => { abort.abort(); controller.dispose(); style.remove(); });
    }
    return { apply, inject: ['locale', 'slots'], createMemoryController, saveMemoryArchive };
  },
});
