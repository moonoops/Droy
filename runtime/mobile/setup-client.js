function setupPermissionFor(preference) {
  return ({ allowObserve: 'accessibility', allowControl: 'accessibility', allowScreenshot: 'accessibility',
    allowCalendar: 'calendar', allowContacts: 'contacts', allowNotifications: 'notificationAccess',
    allowUsage: 'usage', allowPrivacyUsage: 'usage', allowDiagnostics: 'usage',
    allowAudioMicrophone: 'microphone', allowSettingsWrite: 'brightness',
    allowFiles: 'file', allowFileExport: 'directory' })[preference] || null;
}

function installSetupClient({ ctx, React, h, Input, target, doc, bridge, controller, useState,
  actionButton, Permissions, BrandMark, PetStudio }) {
  let disposed = false;
  const requests = new Set();
  const style = doc.createElement('style');
  style.textContent = `
.droy-setup{position:fixed;inset:0;bottom:var(--droy-ime-bottom,0px);box-sizing:border-box;display:flex;flex-direction:column;background:var(--dsw-alias-bg-base);color:var(--dsw-alias-label-primary);padding:var(--droy-safe-top,0px) var(--droy-safe-right,0px) 0 var(--droy-safe-left,0px);pointer-events:auto;overflow:hidden;font-size:var(--dsh-content-font-size,15px);line-height:1.6}
.droy-setup-head,.droy-setup-main,.droy-setup-footer-inner{width:100%;max-width:42rem;margin:0 auto;box-sizing:border-box}
.droy-setup-head{padding:18px 24px 12px;flex-shrink:0}.droy-setup-progress{display:flex;gap:5px;margin-bottom:10px}.droy-setup-progress span{height:3px;flex:1;background:var(--dsw-alias-border-l2);border-radius:2px}.droy-setup-progress span[data-active]{background:var(--dsw-alias-label-primary)}.droy-setup-caption{margin:0;font-size:.82em;color:var(--dsw-alias-label-secondary)}
.droy-setup-scroll{flex:1;min-height:0;overflow:auto;overscroll-behavior:contain}.droy-setup-main{padding:8px 24px 28px;overflow-wrap:anywhere}.droy-setup-main h1{font-size:1.65em;font-weight:600;line-height:1.3;letter-spacing:-.02em;margin:8px 0 16px}.droy-setup-main h2{font-size:1.12em;line-height:1.45;margin:24px 0 8px}.droy-setup-main p{margin:10px 0}.droy-setup-description{color:var(--dsw-alias-label-secondary)}
.droy-setup-footer{flex-shrink:0;border-top:1px solid var(--dsw-alias-border-l2);background:var(--dsw-alias-bg-base);padding-bottom:max(var(--droy-safe-bottom,0px),10px)}.droy-setup-footer-inner{display:flex;flex-wrap:wrap;gap:10px;padding:12px 24px 2px}.droy-setup-footer .droy-button{flex:1;min-width:90px;min-height:48px;height:auto;white-space:normal}.droy-setup .droy-actions{gap:10px}.droy-setup .droy-button{height:auto;min-height:48px;max-width:100%;white-space:normal;line-height:1.5;overflow-wrap:anywhere}
.droy-setup-choice{display:flex;align-items:flex-start;gap:12px;padding:16px 0;border-bottom:1px solid var(--dsw-alias-border-l2);cursor:pointer;min-height:48px}.droy-setup-choice input{flex-shrink:0;width:20px;height:20px;margin-top:3px;accent-color:var(--dsw-alias-label-primary)}.droy-setup-choice strong,.droy-setup-choice small{display:block}.droy-setup-choice small{font-size:.88em;color:var(--dsw-alias-label-secondary);margin-top:4px}.droy-setup-choice:focus-within{outline:2px solid var(--dsw-alias-label-primary);outline-offset:3px}
.droy-setup-field{display:flex;flex-direction:column;gap:7px;margin:16px 0}.droy-setup-field>.droy-input{flex:none;box-sizing:border-box;width:100%;min-width:0;min-height:48px}.droy-setup-field input{scroll-margin-block:8px;box-sizing:border-box;width:100%;min-width:0;min-height:48px;font:inherit;line-height:1.5}.droy-setup-field textarea,.droy-setup-select{box-sizing:border-box;width:100%;min-width:0;min-height:48px;padding:10px 12px;border:1px solid var(--dsw-alias-border-l2);border-radius:8px;background:var(--dsw-alias-bg-base);color:var(--dsw-alias-label-primary);font:inherit;line-height:1.5}.droy-setup-field textarea{scroll-margin-block:8px;min-height:112px;resize:vertical}.droy-setup-field input:focus-visible,.droy-setup-field textarea:focus-visible,.droy-setup-select:focus-visible{outline:2px solid var(--dsw-alias-label-primary);outline-offset:2px}
.droy-setup-check{padding:12px 0;border-bottom:1px solid var(--dsw-alias-border-l2)}.droy-setup-check strong{display:block;font-weight:500}.droy-setup-check p{font-size:.88em;margin:3px 0}.droy-setup-check[data-status=failed]{border-left:3px solid var(--dsw-alias-state-danger-primary);padding-left:10px}.droy-setup-result{padding:12px 0;font-weight:500}.droy-setup .droy-permissions{font-size:1em}.droy-setup-identity{width:100%;min-width:0}.droy-setup-main details{margin:18px 0}.droy-setup-main summary{cursor:pointer;min-height:48px;display:flex;align-items:center}
[data-droy-setup-model-settings] button{min-height:48px;min-width:48px;height:auto;max-width:100%;white-space:normal;line-height:1.5}
[data-droy-setup-model-settings] [class$="_section"]>h2:first-child,[data-droy-setup-model-settings] [class$="_section"]>p:nth-child(2){display:none}
[data-droy-setup-model-settings] [class$="_rowHead"],[data-droy-setup-model-settings] [class$="_editorActions"]{flex-wrap:wrap}
[data-droy-setup-model-settings] input,[data-droy-setup-model-settings] select{min-height:48px;min-width:0;max-width:100%;box-sizing:border-box}
@media(max-width:360px){.droy-setup-head{padding:14px 16px 8px}.droy-setup-main{padding:6px 16px 20px}.droy-setup-footer-inner{padding-left:16px;padding-right:16px}}
@media(max-height:420px){.droy-setup-head{padding-top:6px;padding-bottom:4px}.droy-setup-progress{margin-bottom:4px}.droy-setup-footer-inner{padding-top:6px}.droy-setup-main{padding-top:0}.droy-setup-main h1{font-size:1.35em}}
@media(orientation:landscape){html[data-droy-ime-visible="true"] .droy-setup{display:block;overflow:auto}html[data-droy-ime-visible="true"] .droy-setup-head{display:none}html[data-droy-ime-visible="true"] .droy-setup-scroll{overflow:visible}html[data-droy-ime-visible="true"] .droy-setup-main{padding-top:8px}html[data-droy-ime-visible="true"] .droy-setup-field textarea{min-height:calc(1.5em + 24px);height:clamp(calc(1.5em + 24px),calc(100dvh - var(--droy-ime-bottom,0px) - var(--droy-safe-top,0px) - 16px),112px);resize:none}}
`;
  doc.head.appendChild(style);

  async function api(path, body) {
    const abort = new target.AbortController();
    requests.add(abort);
    const timer = target.setTimeout(() => abort.abort(), 65000);
    try {
      const response = await target.fetch(`/droy/setup/${path}`, {
        method: body === undefined ? 'GET' : 'POST', credentials: 'same-origin', cache: 'no-store',
        headers: body === undefined ? {} : { 'Content-Type': 'application/json' },
        body: body === undefined ? undefined : JSON.stringify(body), signal: abort.signal,
      });
      const result = await response.json();
      if (!response.ok || result.ok !== true) throw new Error(result.message || droyText("检查未完成，请重试"));
      return result;
    } finally { target.clearTimeout(timer); requests.delete(abort); }
  }

  function Identity({ snapshot, prefix = 'droy-setup', onSaved, onDraft, includeName = true }) {
    useDroyLocale();
    const identity = snapshot.state?.setup?.identity || {};
    const saved = { companionName: identity.companionName || snapshot.state?.petName || 'Droy',
      userAddress: identity.userAddress || '', personality: identity.personality || '' };
    const [draft, setDraft] = React.useState(saved);
    React.useEffect(() => setDraft(saved), [saved.companionName, saved.userAddress, saved.personality]);
    React.useEffect(() => { onDraft?.(draft); }, [draft.companionName, draft.userAddress, draft.personality]);
    const pending = snapshot.pending !== null;
    const field = (key, label, limit, multiline = false, hint = '') => h('label', { className: 'droy-setup-field', htmlFor: `${prefix}-${key === 'companionName' ? 'name' : key === 'userAddress' ? 'address' : 'personality'}` },
      h('span', null, label), h(multiline ? 'textarea' : Input, {
        id: `${prefix}-${key === 'companionName' ? 'name' : key === 'userAddress' ? 'address' : 'personality'}`,
        className: 'droy-input', value: draft[key], maxLength: limit, disabled: pending,
        placeholder: hint, onChange: event => setDraft(value => ({ ...value, [key]: event.target.value })),
      }));
    const save = async () => {
      try { await controller.run('setupUpdate', draft); onSaved?.(); }
      catch { /* The shared settings feedback retains the save error. */ }
    };
    const dirty = Object.keys(saved).some(key => draft[key] !== saved[key]);
    return h('div', { className: 'droy-setup-identity', 'data-droy-general': 'companionIdentity' },
      includeName ? field('companionName', droyText("Droy 的名称"), 40) : null,
      field('userAddress', droyText("Droy 怎么称呼你"), 40, false, droyText("可留空，例如：小林")),
      field('personality', droyText("性格"), 1000, true, droyText("留空使用当前形象的默认性格")),
      h('p', { className: 'droy-description' }, droyText("之后可在设置中修改。")),
      onSaved ? null : h('div', { className: 'droy-actions' }, actionButton({ 'data-droy-setup-save-identity': true,
        disabled: pending || !draft.companionName.trim() || (!dirty && !onSaved), onClick: save }, onSaved ? droyText("保存并继续") : droyText("保存个性化配置"))),
      snapshot.error ? h('p', { className: 'droy-error', role: 'alert' }, droyText(snapshot.error)) : null);
  }

  function IdentitySettings() {
    useDroyLocale();
    const snapshot = useState();
    return h('section', { className: 'droy-row droy-setup-identity' }, h('div', { className: 'droy-row-copy' },
      h('h3', { className: 'droy-section-title' }, droyText("伙伴名字、称呼与性格")), h(Identity, { snapshot, prefix: 'droy-identity' })));
  }

  function InstallationSettings() {
    useDroyLocale();
    const snapshot = useState();
    const components = snapshot.state?.setupComponents || {};
    const [result, setResult] = React.useState(null), [pending, setPending] = React.useState(false), [error, setError] = React.useState('');
    const run = async action => {
      if (pending) return;
      setPending(true); setError('');
      try { const value = await action(); if (!disposed) { if (value?.state) setResult(value.state); await controller.refresh(); } }
      catch (failure) { if (!disposed) setError(failure.message || droyText("检查未完成，请重试")); }
      finally { if (!disposed) setPending(false); }
    };
    const component = operation => run(async () => {
      await controller.run('setupUpdate', { shizukuChoice: operation === 'download' ? 'install' : 'use' });
      await controller.run('setupComponent', { operation });
    });
    React.useEffect(() => {
      if (components.downloadState !== 'downloading') return;
      const timer = target.setInterval(() => controller.refresh().catch(() => {}), 1000);
      return () => target.clearInterval(timer);
    }, [components.downloadState]);
    return h('section', { className: 'droy-row', 'data-droy-general': 'installation' }, h('div', { className: 'droy-row-copy' },
      h('details', null, h('summary', { className: 'droy-row-title' }, droyText("安装与运行环境")),
        h('p', { className: 'droy-description' }, droyText("检查当前运行环境、模型验证和已选能力。覆盖升级会保留配置，不重复首次引导。")),
        h('div', { className: 'droy-actions' },
          actionButton({ disabled: pending, 'data-droy-installation-verify': true, onClick: () => run(() => api('verify', {})) }, droyText("检查运行状态")),
          actionButton({ disabled: pending, 'data-droy-installation-model-test': true, onClick: () => run(() => api('model-test', {})) }, droyText("测试当前模型"))),
        h('p', { className: 'droy-description' }, droyText("模型测试最多发送两次不含个人内容的短请求，提供商可能计费。")),
        ...(result?.checks || []).filter(item => item.status !== 'skipped').map(item => h('p', { key: item.id, className: 'droy-description' },
          `${item.status === 'passed' ? droyText("已通过") : droyText("待配置")} · ${droyText(item.label)}: ${droyText(item.detail)}`)),
        h('h3', { className: 'droy-section-title' }, droyText("Shizuku（可选）")),
        h('p', { className: 'droy-description' }, components.installed ? droyText("已安装 {0}；运行和授权状态见权限设置。", [components.versionName]) : droyText("尚未安装。核心对话和无障碍手机操作无需此组件。")),
        h('div', { className: 'droy-actions' }, components.installed
          ? actionButton({ disabled: pending, onClick: () => component('open') }, droyText("打开 Shizuku"))
          : components.downloadState === 'ready' ? actionButton({ disabled: pending, onClick: () => component('install') }, droyText("打开系统安装"))
          : actionButton({ disabled: pending || components.downloadState === 'downloading', onClick: () => component('download') }, components.downloadState === 'downloading' ? droyText("正在下载…") : droyText("同意从官方来源下载 Shizuku")),
          actionButton({ disabled: pending, onClick: () => run(async () => {
            if (components.downloadState === 'downloading') await controller.run('setupComponent', { operation: 'cancel' });
            await controller.run('setupUpdate', { shizukuChoice: 'skip' });
          }) }, droyText("不将 Shizuku 列为必需"))),
        error || components.error ? h('p', { className: 'droy-error', role: 'alert' }, droyText(error || components.error)) : null)));
  }

  function DefaultModel() {
    useDroyLocale();
    const modelFieldId = React.useId();
    const [remote, setRemote] = React.useState(null), [model, setModel] = React.useState('');
    const [pending, setPending] = React.useState(false), [error, setError] = React.useState('');
    const alive = React.useRef(true), revision = React.useRef(0);
    const busy = pending;
    const checkPassed = value => value?.passed === true;
    const modelCheck = remote?.modelVerification || {};
    const updateRemote = result => {
      if (!alive.current) return;
      revision.current++;
      setRemote(previous => ({ ...previous, ...result }));
      if (result.models) {
        const selected = result.models.find(item => item.provider === result.selection?.provider && item.model === result.selection?.model);
        setModel(selected ? JSON.stringify([selected.provider, selected.model]) : '');
      }
    };
    const reload = async () => {
      const current = ++revision.current;
      try {
        const result = await api('status');
        if (current === revision.current) { updateRemote(result); setError(''); }
      } catch (failure) { if (current === revision.current) throw failure; }
    };
    const run = async operation => {
      if (pending) return;
      setPending(true); setError('');
      try { await operation(); }
      catch (failure) { if (alive.current) setError(failure.message || droyText("检查未完成，请重试")); }
      finally { if (alive.current) setPending(false); }
    };
    React.useEffect(() => {
      alive.current = true;
      const refresh = () => reload().catch(failure => { if (alive.current) setError(failure.message); });
      const unsubscribers = ['settings/document-updated', 'credentials/reference-updated', 'credentials/record-updated', 'llm/adapters-updated']
        .map(event => ctx.remote?.$on(event, refresh));
      refresh();
      return () => { alive.current = false; for (const unsubscribe of unsubscribers) unsubscribe?.(); };
    }, []);
    return h('section', { 'data-droy-default-model': true },
      h('label', { className: 'droy-setup-field', htmlFor: modelFieldId }, droyText("默认模型"),
        h('select', { id: modelFieldId, className: 'droy-setup-select', 'data-droy-setup-model': true, value: model, disabled: busy,
          onChange: event => { setModel(event.target.value); setRemote(value => value ? { ...value, modelVerification: {}, state: { ...value.state, modelVerification: {} } } : value); } },
        h('option', { value: '' }, remote?.models?.length ? droyText("请选择模型") : droyText("配置模型后刷新")),
        ...(remote?.models || []).map(item => h('option', { key: `${item.provider}/${item.model}`, value: JSON.stringify([item.provider, item.model]) }, `${item.providerName || item.provider} · ${item.name || item.model}`)))),
      h('div', { className: 'droy-actions' }, actionButton({ disabled: busy, 'data-droy-setup-model-refresh': true,
        onClick: () => run(reload) }, droyText("刷新模型列表")),
      actionButton({ disabled: busy || !model, 'data-droy-setup-model-test': true,
        onClick: () => run(async () => {
          const [provider, selectedModel] = JSON.parse(model);
          setRemote(value => value ? { ...value, modelVerification: {} } : value);
          try { updateRemote(await api('model-test', { provider, model: selectedModel })); }
          finally { await controller.refresh(); target.dispatchEvent(new target.CustomEvent('droy-model-updated')); }
        }) }, pending ? droyText("正在检查模型…") : droyText("设为默认并测试连接"))),
      h('p', { className: 'droy-setup-description' }, droyText("测试发送两条简短请求，可能产生费用。")),
      checkPassed(modelCheck) ? h('p', { className: 'droy-setup-result', role: 'status' }, modelCheck.toolsPassed || modelCheck.toolCallingPassed
        ? droyText("模型回复与工具调用验证通过。") : droyText("模型对话验证通过；手机操作还需要支持工具调用的模型。")) : null,
      error ? h('p', { className: 'droy-error', role: 'alert' }, droyText(error)) : null);
  }

  function Setup() {
    const steps = [droyText("欢迎"), droyText("模型配置"), droyText("环境准备"), droyText("个性化"), droyText("权限与验证"), droyText("欢迎使用")];

    useDroyLocale();
    const snapshot = useState();
    const setup = snapshot.state?.setup;
    const active = setup?.required === true;
    const step = Math.min(6, Math.max(1, setup?.step || 1));
    const [remote, setRemote] = React.useState(null), [pending, setPending] = React.useState(false), [error, setError] = React.useState('');
    const [mode, setMode] = React.useState(setup?.modeSelected ? setup.mode : 'assistant');
    const [leaving, setLeaving] = React.useState(false);
    const [identityDraft, setIdentityDraft] = React.useState(null);
    const alive = React.useRef(true), remoteRevision = React.useRef(0);
    const busy = pending || snapshot.pending !== null;
    const updateRemote = result => {
      if (!alive.current || disposed) return;
      remoteRevision.current++; setRemote(result);
    };
    const run = async operation => {
      if (pending) return;
      setPending(true); setError('');
      try { const value = await operation(); if (alive.current) { await controller.refresh(); return value; } }
      catch (failure) { if (alive.current) setError(failure.name === 'AbortError' ? droyText("检查已中止，请重试") : failure.message || droyText("操作未完成，请重试")); }
      finally { if (alive.current) setPending(false); }
    };
    const change = fields => controller.run('setupUpdate', fields);
    const navigate = next => run(async () => { await change({ step: next }); });
    React.useEffect(() => { alive.current = true; return () => { alive.current = false; }; }, []);
    React.useEffect(() => {
      if (!active) return;
      if (setup.modeSelected) setMode(setup.mode);
    }, [active, setup?.mode, setup?.modeSelected]);
    React.useEffect(() => {
      if (!active || ![2, 5].includes(step)) return;
      let current = true;
      const reload = () => {
        const revision = ++remoteRevision.current;
        return api('status').then(value => { if (current && revision === remoteRevision.current) { updateRemote(value); setError(''); } },
          failure => { if (current && revision === remoteRevision.current) setError(failure.message); });
      };
      const visible = () => { if (doc.visibilityState === 'visible') reload(); };
      const unsubscribers = ['settings/document-updated', 'credentials/reference-updated', 'credentials/record-updated', 'llm/adapters-updated']
        .map(event => ctx.remote?.$on(event, reload));
      target.addEventListener('droy-model-updated', reload);
      reload(); target.addEventListener('focus', reload); doc.addEventListener('visibilitychange', visible);
      return () => { current = false; for (const unsubscribe of unsubscribers) unsubscribe?.(); target.removeEventListener('droy-model-updated', reload); target.removeEventListener('focus', reload); doc.removeEventListener('visibilitychange', visible); };
    }, [active, step]);
    React.useEffect(() => {
      if (!active || step !== 3 || snapshot.state?.setupComponents?.downloadState !== 'downloading') return;
      const timer = target.setInterval(() => controller.refresh().catch(() => {}), 1000);
      return () => target.clearInterval(timer);
    }, [active, step, snapshot.state?.setupComponents?.downloadState]);
    React.useEffect(() => {
      if (!active || step !== 5 || !(snapshot.state?.setupDeviceTest?.running || snapshot.state?.setupRootTest?.running)) return;
      const timer = target.setInterval(() => controller.refresh().catch(() => {}), 600);
      return () => target.clearInterval(timer);
    }, [active, step, snapshot.state?.setupDeviceTest?.running, snapshot.state?.setupRootTest?.running]);
    React.useEffect(() => {
      target.dispatchEvent(new target.CustomEvent('droy-setup-step'));
      if (!active) return;
      const region = doc.querySelector('[data-droy-setup-scroll]');
      if (region) region.scrollTop = 0;
      const heading = doc.querySelector('[data-droy-setup-title]');
      heading?.focus({ preventScroll: true });
    }, [active, step]);
    React.useEffect(() => {
      if (!active) return;
      const root = doc.querySelector('[data-droy-setup]');
      if (!root) return;
      const observer = new target.ResizeObserver(() => {
        const field = doc.activeElement;
        if (doc.documentElement.getAttribute('data-droy-ime-visible') === 'true'
            && field?.matches('input,textarea') && root.contains(field)) field.scrollIntoView({ block: 'nearest', inline: 'nearest' });
      });
      observer.observe(root);
      return () => observer.disconnect();
    }, [active]);
    if (!active || leaving) return null;
    const state = remote?.state || setup;
    const modelCheck = state.modelVerification || setup.modelVerification || {};
    const environment = setup.environment || {};
    const components = snapshot.state?.setupComponents || {};
    const shizuku = environment.shizuku || {};
    const checks = (state.checks || []).filter(item => item.status !== 'skipped');
    const checkPassed = value => value?.passed === true;
    const choices = [
      ['assistant', droyText("手机助手（推荐）"), droyText("需要 Droy 替你操作手机或其他 App，将打开必要权限。")],
      ['dialogue', droyText("仅对话"), droyText("不打开权限，稍后可在设置中按需修改。")],
      ['all', droyText("打开所有权限"), droyText("允许 Droy 获取所有数据权限。")],
    ];
    const checkList = checks.map(item => h('div', { key: item.id, className: 'droy-setup-check', 'data-droy-setup-check': item.id, 'data-status': item.status },
      h('strong', null, `${item.status === 'passed' ? '✓ ' : item.status === 'failed' || item.status === 'pending' ? droyText("待完成 · ") : ''}${droyText(item.label || item.id)}`),
      item.detail ? h('p', { className: 'droy-setup-description' }, droyText(item.detail)) : null,
      item.permission ? actionButton({ disabled: busy, onClick: () => run(() => controller.run('requestPermission', { name: item.permission })) }, droyText("去配置")) : null));
    let body;
    if (step === 1) body = h(React.Fragment, null,
      h(BrandMark, { size: 80 }), h('h1', { tabIndex: -1, 'data-droy-setup-title': true }, droyText("你好，我是 Droy")),
      h('h2', null, droyText("你想先怎样使用？")), ...choices.map(([id, title, detail]) => h('label', { key: id, className: 'droy-setup-choice' },
        h('input', { type: 'radio', name: 'droy-setup-mode', value: id, checked: mode === id, disabled: busy,
          'data-droy-setup-mode': id, onChange: () => setMode(id) }), h('span', null, h('strong', null, title), h('small', null, detail)))),
      h('p', { className: 'droy-setup-description' }, droyText("读取的信息会发送给所选模型。系统权限将在后续步骤确认。")));
    if (step === 2) body = h(React.Fragment, null,
      h('h1', { tabIndex: -1, 'data-droy-setup-title': true }, droyText("设置你的模型")),
      h('div', { 'data-droy-setup-model-settings': true }));
    if (step === 3) body = h(React.Fragment, null,
      h('h1', { tabIndex: -1, 'data-droy-setup-title': true }, droyText("准备运行环境")),
      h('div', { 'data-droy-setup-environment': true },
        h('div', { className: 'droy-setup-check' }, h('strong', null, droyText("本地运行环境")), h('p', null, snapshot.state.runtimeReady ? droyText("已就绪") : snapshot.state.runtimeStatus || droyText("正在准备…"))),
        h('div', { className: 'droy-setup-check' }, h('strong', null, droyText("手机与系统")), h('p', null, `Android API ${environment.androidApi || '—'} · ${environment.abi || 'ARM64'}`))),
      h('h2', null, droyText("Shizuku 增强服务（可选）")),
      h('p', { className: 'droy-setup-description' }, droyText("增强手机操作能力，可稍后配置。")),
      h('p', { 'data-droy-setup-shizuku-state': true, role: 'status' }, !components.installed ? droyText("尚未安装") : shizuku.authorized
        ? droyText("已安装 · 服务已授权") : shizuku.running ? droyText("已安装 · 服务运行中，待给 Droy 授权") : droyText("已安装 · 服务未启动")),
      h('label', { className: 'droy-setup-choice' }, h('input', { type: 'radio', name: 'droy-setup-shizuku',
        checked: !setup.shizukuChoice || setup.shizukuChoice === 'skip', disabled: busy, 'data-droy-setup-shizuku': 'skip',
        onChange: () => run(async () => {
          if (components.downloadState === 'downloading') await controller.run('setupComponent', { operation: 'cancel' });
          await change({ shizukuChoice: 'skip' });
        }) }), h('span', null, h('strong', null, droyText("暂不使用")), h('small', null, droyText("之后可在设置中继续配置。")))),
      h('p', { className: 'droy-setup-description' }, droyText("来源：Shizuku 官方 GitHub{0}", [components.componentVersion ? droyText("，版本 {0}", [components.componentVersion]) : ''])),
      components.error ? h('p', { className: 'droy-error', role: 'alert' }, droyText(components.error)) : null,
      h('div', { className: 'droy-actions' }, !components.installed && components.downloadState !== 'ready'
        ? actionButton({ 'data-droy-setup-component': 'download', disabled: busy || components.downloadState === 'downloading',
          onClick: () => run(async () => { await change({ shizukuChoice: 'install' }); await controller.run('setupComponent', { operation: 'download' }); }) },
          components.downloadState === 'downloading' ? droyText("正在下载 {0}%", [Math.round((components.downloadBytes || 0) / Math.max(1, components.totalBytes || 1) * 100)]) : droyText("同意下载 Shizuku")) : null,
        !components.installed && components.downloadState === 'ready' ? actionButton({ 'data-droy-setup-component': 'install', disabled: busy,
          onClick: () => run(() => controller.run('setupComponent', { operation: 'install' })) }, droyText("打开系统安装")) : null,
        components.installed ? actionButton({ 'data-droy-setup-component': 'open', disabled: busy,
          onClick: () => run(async () => { await change({ shizukuChoice: 'use' }); await controller.run('setupComponent', { operation: 'open' }); }) }, droyText("打开 Shizuku 并启动服务")) : null,
        actionButton({ disabled: busy, onClick: () => run(() => controller.refresh()) }, droyText("重新检查"))),
      setup.shizukuChoice && setup.shizukuChoice !== 'skip' ? h('details', { open: !environment.developerOptions },
        h('summary', null, droyText("如何开启开发者选项和无线调试")),
        h('p', null, droyText("打开“关于手机”，连续点击版本号七次，按系统提示验证身份；返回系统设置，打开开发者选项，再按 Shizuku 指引启用无线调试并配对。不同品牌的菜单位置可能不同。")),
        h('div', { className: 'droy-actions' }, actionButton({ onClick: () => run(() => controller.run('requestPermission', { name: 'aboutPhone' })) }, droyText("打开关于手机")),
          actionButton({ onClick: () => run(() => controller.run('requestPermission', { name: 'development' })) }, droyText("打开开发者选项")))) : null);
    if (step === 4) body = h(React.Fragment, null,
      h('h1', { tabIndex: -1, 'data-droy-setup-title': true }, droyText("认识你的 Droy")), h(BrandMark, { size: 72 }),
      h(Identity, { snapshot, onDraft: setIdentityDraft, onSaved: () => navigate(5) }),
      h('details', null, h('summary', null, droyText("选择或定制伙伴形象")), h(PetStudio, { snapshot })));
    if (step === 5) body = h(React.Fragment, null,
      h('h1', { tabIndex: -1, 'data-droy-setup-title': true }, droyText("确认权限与可用能力")),
      h('p', { className: 'droy-setup-description' }, droyText("完成所选权限，或关闭不需要的功能。")),
      h('div', { className: 'droy-actions' }, actionButton({ 'data-droy-setup-dialogue': true, disabled: busy,
        onClick: () => run(async () => { await change({ mode: 'dialogue' }); updateRemote(await api('verify', {})); }) }, droyText("先使用对话，关闭手机能力"))),
      h(Permissions, { onboarding: true }),
      h('details', null, h('summary', null, droyText("无障碍开关无法开启？")),
        h('p', { className: 'droy-setup-description' }, droyText("部分 Android 13 及以上系统会限制侧载应用的敏感设置。确认安装来源可信后，打开 Droy 的应用信息，在右上角菜单选择“允许受限设置”，再返回无障碍设置。菜单名称和位置可能因手机品牌不同。")),
        actionButton({ disabled: busy, onClick: () => run(() => controller.run('requestPermission', { name: 'appDetails' })) }, droyText("打开 Droy 应用信息"))),
      h('h2', null, droyText("安装自检")),
      h('p', { className: 'droy-setup-description' }, droyText("在内置练习页测试手机操作。")),
      h('div', { className: 'droy-actions' }, setup.capabilities?.allowObserve || setup.capabilities?.allowControl
        ? actionButton({ 'data-droy-setup-device-test': true, disabled: busy || snapshot.state.setupDeviceTest?.running,
          onClick: () => run(() => controller.run('setupDeviceTest', {}, false)) }, snapshot.state.setupDeviceTest?.running ? droyText("正在验证手机操作…") : droyText("验证手机操作")) : null,
        actionButton({ 'data-droy-setup-verify': true, disabled: busy,
          onClick: () => run(async () => updateRemote(await api('verify', {}))) }, pending ? droyText("正在检查…") : droyText("检查所选功能"))),
      setup.capabilities?.allowRoot ? h('div', null,
        h('p', { className: 'droy-setup-description' }, droyText("请在 Root 管理器中确认授权。")),
        actionButton({ 'data-droy-setup-root-test': true, disabled: busy || snapshot.state.setupRootTest?.running,
          onClick: () => run(() => controller.run('setupRootTest', {})) }, snapshot.state.setupRootTest?.running ? droyText("正在等待 Root 授权…") : droyText("请求并验证 Root 授权")),
        snapshot.state.setupRootTest?.error ? h('p', { className: 'droy-error', role: 'alert' }, droyText(snapshot.state.setupRootTest.error)) : null) : null,
      snapshot.state.setupDeviceTest?.error ? h('p', { className: 'droy-error', role: 'alert' }, droyText(snapshot.state.setupDeviceTest.error)) : null,
      h('div', { 'aria-live': 'polite' }, ...checkList));
    if (step === 6) body = h(React.Fragment, null,
      h(BrandMark, { size: 88 }), h('h1', { tabIndex: -1, 'data-droy-setup-title': true }, droyText("{0}欢迎使用 {1}", [setup.identity?.userAddress ? `${setup.identity.userAddress}${droyLocale?.getSnapshot().active === 'en' ? ', ' : '，'}` : '', setup.identity?.companionName || 'Droy'])),
      h('p', null, droyText("配置完成，开始和 Droy 对话吧。")),
      h('p', { className: 'droy-setup-description' }, setup.mode === 'dialogue'
        ? droyText("手机操作尚未开启。需要时，可在“设置 → 权限设置”中开启并完成授权。")
        : droyText("已开启的能力和系统权限，可随时在“设置 → 权限设置”中调整。")));
    const next = () => run(async () => {
      if (step === 1) await change({ mode, step: 2 });
      else if (step === 5) {
        const result = await api('verify', {}); updateRemote(result);
        if (result.state?.canComplete !== true) throw new Error(droyText("还有选定功能未通过检查，请先配置或关闭该功能"));
        await change({ step: 6 });
      } else if (step === 4) await change({ ...(identityDraft || {}), step: 5 });
      else await change({ step: step + 1 });
    });
    const finished = () => run(async () => {
      updateRemote(await api('complete', {}));
      setLeaving(true);
    });
    return h('main', { className: 'droy-setup', 'data-droy-setup': true, 'data-droy-setup-step': step, 'aria-label': droyText("Droy 安装配置：{0}", [steps[step - 1]]) },
      h('header', { className: 'droy-setup-head' },
        h('div', { className: 'droy-setup-progress', 'aria-hidden': true }, ...steps.map((_, index) => h('span', { key: index, 'data-active': index < step ? '' : undefined }))),
        h('p', { className: 'droy-setup-caption', 'aria-live': 'polite' }, `${step} / 6 · ${steps[step - 1]}`)),
      h('div', { className: 'droy-setup-scroll', 'data-droy-setup-scroll': true }, h('div', { className: 'droy-setup-main' }, body,
        error || snapshot.error ? h('p', { className: 'droy-error', role: 'alert', 'data-droy-setup-error': true }, droyText(error || snapshot.error)) : null)),
      h('footer', { className: 'droy-setup-footer' }, h('div', { className: 'droy-setup-footer-inner' },
        step > 1 ? actionButton({ disabled: busy, 'data-droy-setup-back': true, onClick: () => navigate(step - 1) }, droyText("上一步")) : null,
        step === 6 ? actionButton({ disabled: busy, 'data-droy-setup-finish': true, onClick: finished }, pending ? droyText("正在确认…") : droyText("开始使用"))
          : actionButton({ disabled: busy || step === 2 && !checkPassed(modelCheck) || step === 3 && !snapshot.state.runtimeReady,
            'data-droy-setup-next': true, onClick: next }, step === 1 ? droyText("开始配置") : step === 4 ? droyText("保存并继续") : step === 5 ? droyText("完成检查并继续") : droyText("下一步")))));
  }

  ctx.slots.inject('settings.models.footer', () => ctx.slots.register({ name: 'settings.models.footer', id: 'droy-default-model', order: 100 }, DefaultModel));
  ctx.slots.inject('shell.overlay', () => ctx.slots.register({ name: 'shell.overlay', id: 'droy-setup', order: 100 }, Setup));
  ctx.slots.inject('settings.general.item', () => ctx.slots.register({ name: 'settings.general.item', id: 'droy-companion-identity', order: 81.5 }, IdentitySettings));
  ctx.slots.inject('settings.general.item', () => ctx.slots.register({ name: 'settings.general.item', id: 'droy-installation', order: 99 }, InstallationSettings));
  const onFocus = () => {
    if (controller.getSnapshot().state?.setup?.required) controller.refresh().catch(() => {});
  };
  target.addEventListener('focus', onFocus);
  return {
    handleBack() {
      const setup = controller.getSnapshot().state?.setup;
      if (!setup?.required || !doc.querySelector('[data-droy-setup]')) return false;
      if (setup.step > 1) { controller.run('setupUpdate', { step: setup.step - 1 }).catch(() => {}); return true; }
      return false;
    },
    dispose() { disposed = true; for (const request of requests) request.abort(); requests.clear(); style.remove(); target.removeEventListener('focus', onFocus); },
  };
}
