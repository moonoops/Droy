import test from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import { readFile } from 'node:fs/promises';

const source = (await readFile(new URL('../client.js', import.meta.url), 'utf8'))
  .replace('/* DROY_VOICE_ADAPTER */', 'function createDshVoiceAdapter() { return {}; }')
  .replace('/* DROY_VOICE_CONTROLLER */', 'function createVoiceController() { return window.controller; }');

async function fixture({ rejectWake = false, onRequest, models: initialModels, fakeTimers = false, initialLocale } = {}) {
  const values = [], dependencies = [], effects = [], cleanups = [], registrations = [], requests = [], modelRequests = [], errors = [];
  let hook = 0, client;
  let localeSnapshot = { active: initialLocale || 'zh', revision: 0 };
  const dictionaries = new Map(), localeListeners = new Set();
  const locale = initialLocale ? {
    getSnapshot: () => localeSnapshot,
    subscribe(listener) { localeListeners.add(listener); return () => localeListeners.delete(listener); },
    register(namespace, entries) { dictionaries.set(namespace, entries); return () => dictionaries.delete(namespace); },
  } : undefined;
  const setLocale = active => { localeSnapshot = { active, revision: localeSnapshot.revision + 1 }; for (const listener of localeListeners) listener(); };
  const config = { entries: { standard: true }, tts: { provider: 'system', voiceId: 'system-voice', rate: 1 },
    wake: { enabled: false, chinese: '你好小黑', english: 'Hey Droy', target: 'standard', timing: 'screen_on', useCompanionName: false },
    voices: [{ id: 'saved-voice', name: '已保存声音', provider: 'openai', voiceId: 'custom-id' }] };
  let voiceLoads = 0;
  const models = initialModels || [{ provider: 'melo', name: 'MeloTTS 中文', state: 'ready', ready: true, downloadedBytes: 178257920, totalBytes: 178257920 }];
  const snapshot = { config, status: 'idle', voices: [{ id: 'system-voice', name: '系统示例', provider: 'system' },
    { id: 'melo:zh', voiceId: 'zh', name: '中文女声', provider: 'melo' }] };
  const controller = {
    getSnapshot: () => snapshot, subscribe: () => () => {}, initialize() {}, dispose() {}, openSettings() {}, closeSettings() {}, loadVoices() { voiceLoads++; },
    async toggleWake(enabled) { requests.push({ operation: 'toggleWake', enabled }); },
    inputStatus: () => ({ ready: true, state: 'ready', message: '离线识别已就绪' }),
    async saveConfig(patch) { requests.push({ operation: 'save_config', patch }); return { config }; },
    async request(operation, input) {
      (operation === 'tts.models' ? modelRequests : requests).push({ operation, ...input });
      if (rejectWake && operation === 'wake.configure') throw new Error('唤醒词暂不支持');
      return onRequest?.(operation, input) ?? (operation === 'tts.models' ? { models } : {});
    },
    report(error) { errors.push(error); },
  };
  const React = {
    createElement: (type, props, ...children) => ({ type, props: props || {}, children: children.flat(Infinity) }),
    Fragment: 'fragment',
    useSyncExternalStore: (_subscribe, get) => get(),
    useState(initial) {
      const index = hook++;
      if (!(index in values)) values[index] = typeof initial === 'function' ? initial() : initial;
      return [values[index], value => { values[index] = typeof value === 'function' ? value(values[index]) : value; }];
    },
    useRef(initial) {
      const index = hook++;
      if (!(index in values)) values[index] = { current: initial };
      return values[index];
    },
    useEffect(effect, deps) {
      const index = hook++;
      if (!dependencies[index] || deps.some((value, n) => value !== dependencies[index][n])) {
        dependencies[index] = deps; effects.push(() => { cleanups[index]?.(); cleanups[index] = effect(); });
      }
    },
  };
  const timers = new Map(); let timerId = 0;
  const doc = { createElement: () => ({ remove() {} }), head: { appendChild() {} }, querySelector: () => null, addEventListener() {} };
  const window = { controller, document: doc, btoa() {},
    setTimeout: fakeTimers ? callback => { const id = ++timerId; timers.set(id, callback); return id; } : setTimeout,
    clearTimeout: fakeTimers ? id => timers.delete(id) : clearTimeout, addEventListener() {},
    __ModuleLoader__: { load({ factory }) { client = factory(name => {
      if (name === 'react') return React;
      if (name === '@deepseek-ai/dsh-client-ui-primitives') return { Button: 'button', Switch: 'switch' };
      if (name === '@deepseek-ai/dsh-experimental-client-ui-voice-input') return {
        createDroyVoiceInput: async () => ({ readiness: { subscribe: () => () => {}, getSnapshot: () => ({ connected: true }) } }),
      };
      throw new Error(`Unexpected dependency ${name}`);
    }); } } };
  vm.runInNewContext(source, { window });
  await client.apply({ locale, slots: { inject: (_name, setup) => setup(), register: (options, component) => registrations.push({ options, component }) }, effect() {} });
  const settings = registrations.find(row => row.options.name === 'settings.section').component;
  const dock = registrations.find(row => row.options.name === 'conversation.input.dock').component;
  function render(component = settings) {
    let tree;
    for (let n = 0; n < 3; n++) {
      hook = 0; tree = component({ renderSlot: () => null });
      if (!effects.length) return tree;
      while (effects.length) effects.shift()();
    }
    return tree;
  }
  return { render, dock, snapshot, config, requests, setLocale, dictionaries, modelRequests, errors, voiceLoads: () => voiceLoads, timers, async tick() { const [id, callback] = timers.entries().next().value; timers.delete(id); await callback(); }, dispose: () => cleanups.forEach(cleanup => cleanup?.()) };
}

function nodes(tree) {
  if (!tree || typeof tree !== 'object') return [];
  // These view helpers have no hooks; expand them to inspect actual labels and controls.
  if (typeof tree.type === 'function' && ['Field', 'Toggle', 'Feedback'].includes(tree.type.name))
    return nodes(tree.type(tree.props));
  return [tree, ...tree.children.flatMap(nodes)];
}

test('voice settings explain engine versus voice and remove custom-voice management without deleting profiles', async () => {
  const f = await fixture(), before = JSON.stringify(f.config.voices);
  const tree = nodes(f.render());
  assert.ok(tree.some(node => (node.type?.name === 'Choice' ? node.props.label : node.props['aria-label']) === '发声方式'));
  assert.ok(tree.some(node => (node.type?.name === 'Choice' ? node.props.label : node.props['aria-label']) === '朗读音色'));
  assert.equal(tree.some(node => node.children.includes('我的音色') || node.children.includes('添加音色')), false);
  assert.equal(tree.some(node => node.props['aria-label'] === '音色名称'), false);
  assert.equal(JSON.stringify(f.config.voices), before);
  assert.equal(f.requests.length, 0);
});

test('voice dock keeps the listening status accessible without stacking a visible label under the waveform', async () => {
  const f = await fixture(); f.snapshot.mode = 'standard'; f.snapshot.status = 'active';
  const tree = nodes(f.render(f.dock));
  const signal = tree.find(node => node.props.className === 'droy-voice-signal');
  assert.equal(signal.props['aria-label'], '正在听');
  assert.equal(signal.props.title, '正在听');
  assert.equal(signal.children.length, 1);
  assert.equal(signal.children[0].type.name, 'Wave');
  assert.equal(tree.filter(node => node.type === 'button').length, 3);
});

test('switching speech engines clears the engine-specific voice selection while keeping other draft settings', async () => {
  const f = await fixture();
  let tree = nodes(f.render());
  tree.find(node => (node.type?.name === 'Choice' ? node.props.label : node.props['aria-label']) === '语速').props.onChange('1.15');
  tree.find(node => (node.type?.name === 'Choice' ? node.props.label : node.props['aria-label']) === '发声方式').props.onChange('openai');
  tree = nodes(f.render());
  assert.equal(tree.find(node => (node.type?.name === 'Choice' ? node.props.label : node.props['aria-label']) === '朗读音色').props.value, '');
  assert.equal(tree.find(node => (node.type?.name === 'Choice' ? node.props.label : node.props['aria-label']) === '语速').props.value, 1.15);
  assert.equal(f.config.tts.voiceId, 'system-voice');
});

test('companion-name wake disables manual fields while preserving their values and saving the timing', async () => {
  const f = await fixture();
  let tree = nodes(f.render());
  const timing = tree.find(node => (node.type?.name === 'Choice' ? node.props.label : node.props['aria-label']) === '唤醒时机');
  assert.deepEqual(Array.from(timing.props.options, option => option.value), ['screen_on', 'foreground']);
  timing.props.onChange('foreground');
  tree.find(node => node.type === 'switch' && node.props.label === '使用 Droy 伙伴名称唤醒').props.onChange(true);
  tree = nodes(f.render());
  assert.equal(tree.find(node => node.props['aria-label'] === '中文唤醒词').props.disabled, true);
  assert.equal(tree.find(node => node.props['aria-label'] === '英文唤醒词').props.disabled, true);
  assert.equal(tree.find(node => node.props['aria-label'] === '中文唤醒词').props.value, '你好小黑');
  assert.equal(tree.some(node => node.props['data-droy-voice-wake-prepare']), false);
  tree.find(node => node.props['data-droy-voice-save']).props.onClick();
  await new Promise(resolve => setImmediate(resolve));
  assert.deepEqual(f.requests.map(request => request.operation), ['wake.configure', 'save_config']);
  const savedWake = f.requests[1].patch.wake;
  assert.equal(savedWake.timing, 'foreground');
  assert.equal(savedWake.useCompanionName, true);
  assert.equal(savedWake.chinese, '你好小黑');
  assert.equal(savedWake.english, 'Hey Droy');
  tree = nodes(f.render());
  tree.find(node => node.type === 'switch' && node.props.label === '使用 Droy 伙伴名称唤醒').props.onChange(false);
  assert.equal(nodes(f.render()).find(node => node.props['aria-label'] === '中文唤醒词').props.disabled, false);
});

test('native wake validation failure does not save an unsupported configuration', async () => {
  const f = await fixture({ rejectWake: true });
  nodes(f.render()).find(node => node.props['data-droy-voice-save']).props.onClick();
  await new Promise(resolve => setImmediate(resolve));
  assert.deepEqual(f.requests.map(request => request.operation), ['wake.configure']);
  assert.equal(f.errors.length, 1);
  assert.equal(f.errors[0].message, '唤醒词暂不支持');
  assert.equal(nodes(f.render()).find(node => node.props['data-droy-voice-save']).props.disabled, false);
});

test('voice choices follow the selected engine and honor an existing or bundled default', async () => {
  const f = await fixture();
  let tree = nodes(f.render());
  const choices = () => tree.find(node => (node.type?.name === 'Choice' ? node.props.label : node.props['aria-label']) === '朗读音色');
  assert.deepEqual(Array.from(choices().props.options, option => option.value), ['', 'system-voice']);
  assert.equal(choices().props.value, 'system-voice');
  tree.find(node => (node.type?.name === 'Choice' ? node.props.label : node.props['aria-label']) === '发声方式').props.onChange('melo');
  tree = nodes(f.render());
  assert.deepEqual(Array.from(choices().props.options, option => option.value), ['', 'zh']);
  assert.equal(choices().props.value, '');
  tree.find(node => (node.type?.name === 'Choice' ? node.props.label : node.props['aria-label']) === '发声方式').props.onChange('openai');
  assert.equal(nodes(f.render()).some(node => node.props['data-droy-voice-preview']), false);
  const fresh = await fixture();
  fresh.config.tts = { provider: 'melo', voiceId: 'zh', rate: 1 };
  assert.equal(nodes(fresh.render()).find(node => (node.type?.name === 'Choice' ? node.props.label : node.props['aria-label']) === '朗读音色').props.value, 'zh');
});

test('preview uses unsaved local voice and rate without saving settings, and queued playback remains stoppable', async () => {
  let finishStatus;
  const f = await fixture({ onRequest(operation) {
    if (operation === 'preview_voice') return { queued: true };
    if (operation === 'status') return new Promise(resolve => { finishStatus = resolve; });
  } });
  let tree = nodes(f.render());
  await new Promise(resolve => setImmediate(resolve));
  tree.find(node => (node.type?.name === 'Choice' ? node.props.label : node.props['aria-label']) === '发声方式').props.onChange('melo');
  tree = nodes(f.render());
  tree.find(node => (node.type?.name === 'Choice' ? node.props.label : node.props['aria-label']) === '朗读音色').props.onChange('zh');
  tree.find(node => (node.type?.name === 'Choice' ? node.props.label : node.props['aria-label']) === '语速').props.onChange('1.15');
  nodes(f.render()).find(node => node.props['data-droy-voice-preview']).props.onClick();
  await new Promise(resolve => setImmediate(resolve));
  tree = nodes(f.render());
  assert.deepEqual(f.requests[0], { operation: 'preview_voice', provider: 'melo', voiceId: 'zh', rate: 1.15 });
  assert.equal(tree.find(node => node.props['data-droy-voice-preview']).children[0], '停止试听');
  assert.deepEqual(f.requests.map(request => request.operation), ['preview_voice', 'status']);
  assert.equal(f.config.tts.provider, 'system');
  finishStatus({ speaking: false });
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(nodes(f.render()).find(node => node.props['data-droy-voice-preview']).children[0], '试听');
  f.dispose();
});

test('preview can stop during preparation without a late response restarting the preview', async () => {
  let completePreview;
  const f = await fixture({ onRequest(operation) {
    if (operation === 'preview_voice') return new Promise(resolve => { completePreview = resolve; });
  } });
  nodes(f.render()).find(node => node.props['data-droy-voice-preview']).props.onClick();
  let button = nodes(f.render()).find(node => node.props['data-droy-voice-preview']);
  assert.equal(button.props['aria-busy'], true);
  button.props.onClick();
  completePreview({ queued: true });
  await new Promise(resolve => setImmediate(resolve));
  button = nodes(f.render()).find(node => node.props['data-droy-voice-preview']);
  assert.equal(button.children[0], '试听');
  assert.deepEqual(f.requests.map(request => request.operation), ['preview_voice', 'stop_speaking']);
  f.dispose();
});

test('speech output errors end preview and report the speech error separately from input errors', async () => {
  const f = await fixture({ onRequest(operation) {
    if (operation === 'status') return { speaking: false, speechError: '内置声音初始化失败', error: '旧的输入错误' };
  } });
  nodes(f.render()).find(node => node.props['data-droy-voice-preview']).props.onClick();
  await new Promise(resolve => setImmediate(resolve));
  f.render();
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(f.errors[0].message, '内置声音初始化失败');
  assert.equal(nodes(f.render()).find(node => node.props['data-droy-voice-preview']).children[0], '试听');
  f.dispose();
});

test('leaving voice settings stops the preview owned by that settings page', async () => {
  let completePreview;
  const f = await fixture({ onRequest(operation) {
    if (operation === 'preview_voice') return new Promise(resolve => { completePreview = resolve; });
  } });
  nodes(f.render()).find(node => node.props['data-droy-voice-preview']).props.onClick();
  f.dispose();
  completePreview({ queued: true });
  await new Promise(resolve => setImmediate(resolve));
  assert.deepEqual(f.requests.map(request => request.operation), ['preview_voice', 'stop_speaking']);
});

const flush = () => new Promise(resolve => setImmediate(resolve));
const selectEngine = (f, provider) => nodes(f.render()).find(node => (node.type?.name === 'Choice' ? node.props.label : node.props['aria-label']) === '发声方式').props.onChange(provider);
const downloadedModel = (state = 'not_downloaded') => ({ provider: 'melo', name: 'MeloTTS 中文', state,
  ready: state === 'ready', downloadedBytes: state === 'ready' ? 178257920 : 0, totalBytes: 178257920 });

test('selecting an uninstalled engine offers an explicit sized download and prevents preview or saving it', async () => {
  const f = await fixture({ models: [downloadedModel()] });
  f.render(); await flush(); selectEngine(f, 'melo');
  let tree = nodes(f.render());
  assert.deepEqual(Array.from(tree.find(node => node.type?.name === 'Choice' && node.props.label === '发声方式').props.options, option => option.value), ['system', 'melo', 'qwen3', 'openai']);
  assert.equal(tree.find(node => node.props['data-droy-voice-model']).props['data-droy-voice-model-state'], 'not_downloaded');
  assert.equal(tree.find(node => node.props['data-droy-voice-download']).children[0], '下载资源（170.0 MB）');
  assert.equal(tree.find(node => node.props['data-droy-voice-preview']).props.disabled, true);
  assert.equal(tree.find(node => node.props['data-droy-voice-save']).props.disabled, true);
  tree.find(node => node.props['data-droy-voice-preview']).props.onClick();
  tree.find(node => node.props['data-droy-voice-save']).props.onClick();
  await flush();
  assert.deepEqual(f.requests, []);
  selectEngine(f, 'system'); tree = nodes(f.render());
  assert.equal(tree.find(node => node.props['data-droy-voice-save']).props.disabled, false);
  assert.equal(tree.find(node => node.props['data-droy-voice-preview']).props.disabled, false);
  f.dispose();
});

test('download progresses through verification to ready before enabling preview and save', async () => {
  let model = downloadedModel();
  const f = await fixture({ fakeTimers: true, onRequest(operation) {
    if (operation === 'tts.download') model = { ...model, state: 'downloading' };
    if (operation.startsWith('tts.')) return { models: [structuredClone(model)] };
  } });
  f.render(); await flush(); selectEngine(f, 'melo');
  nodes(f.render()).find(node => node.props['data-droy-voice-download']).props.onClick();
  await flush();
  let tree = nodes(f.render());
  assert.equal(f.requests[0].operation, 'tts.download');
  assert.equal(f.requests[0].provider, 'melo');
  assert.equal(tree.find(node => node.props['data-droy-voice-download']).children[0], '取消下载');
  assert.equal(f.timers.size, 1);
  model = { ...model, state: 'verifying', downloadedBytes: model.totalBytes };
  await f.tick(); tree = nodes(f.render());
  assert.equal(tree.find(node => node.props['data-droy-voice-model']).props['data-droy-voice-model-state'], 'verifying');
  assert.equal(tree.find(node => node.props['data-droy-voice-save']).props.disabled, true);
  model = downloadedModel('ready'); await f.tick(); tree = nodes(f.render());
  assert.equal(tree.find(node => node.props['data-droy-voice-preview']).props.disabled, false);
  assert.equal(tree.find(node => node.props['data-droy-voice-save']).props.disabled, false);
  assert.equal(tree.some(node => node.props['data-droy-voice-download']), false);
  assert.equal(f.timers.size, 0); assert.equal(f.voiceLoads(), 2);
  assert.equal(f.config.tts.provider, 'system');
  f.dispose();
});

test('cancelling a download leaves the engine unavailable and permits an explicit retry', async () => {
  let model = downloadedModel('downloading');
  const f = await fixture({ fakeTimers: true, onRequest(operation) {
    if (operation === 'tts.cancel_download') model = downloadedModel();
    if (operation.startsWith('tts.')) return { models: [structuredClone(model)] };
  } });
  f.render(); await flush(); selectEngine(f, 'melo');
  nodes(f.render()).find(node => node.props['data-droy-voice-download']).props.onClick();
  await flush(); const tree = nodes(f.render());
  assert.equal(f.requests[0].operation, 'tts.cancel_download');
  assert.equal(tree.find(node => node.props['data-droy-voice-preview']).props.disabled, true);
  assert.equal(tree.find(node => node.props['data-droy-voice-download']).children[0], '下载资源（170.0 MB）');
  assert.equal(f.timers.size, 0); f.dispose();
});

test('failed downloads show their error and retry without claiming resources are ready', async () => {
  const model = { ...downloadedModel('failed'), error: '资源校验失败' };
  const f = await fixture({ models: [model] });
  f.render(); await flush(); selectEngine(f, 'melo'); const tree = nodes(f.render());
  assert.ok(tree.some(node => node.children.includes('资源校验失败')));
  assert.equal(tree.find(node => node.props['data-droy-voice-download']).children[0], '重新下载');
  assert.equal(tree.find(node => node.props['data-droy-voice-save']).props.disabled, true); f.dispose();
});

test('missing native provider stays unavailable without fabricated downloads or voices', async () => {
  const f = await fixture(); f.render(); await flush(); selectEngine(f, 'qwen3');
  const tree = nodes(f.render());
  assert.ok(tree.some(node => node.children.includes('当前版本尚未提供此语音资源。')));
  assert.equal(tree.some(node => node.props['data-droy-voice-download']), false);
  assert.equal(tree.find(node => node.props['data-droy-voice-save']).props.disabled, true);
  assert.equal(tree.find(node => node.props['data-droy-voice-preview']).props.disabled, true);
  assert.deepEqual(f.requests, []); f.dispose();
});

test('enabling wake saves only wake settings while an uninstalled speech engine remains an unsaved draft', async () => {
  const f = await fixture({ models: [downloadedModel()] });
  f.render(); await flush(); selectEngine(f, 'melo');
  nodes(f.render()).find(node => node.type === 'switch' && node.props.label === '语音唤醒').props.onChange(true);
  await flush();
  assert.deepEqual(f.requests.map(row => row.operation), ['wake.configure', 'save_config', 'toggleWake']);
  assert.equal('tts' in f.requests[1].patch, false);
  assert.equal(f.requests[2].enabled, true);
  assert.equal(nodes(f.render()).find(node => (node.type?.name === 'Choice' ? node.props.label : node.props['aria-label']) === '发声方式').props.value, 'melo');
  assert.equal(f.config.tts.provider, 'system'); f.dispose();
});

test('leaving settings stops polling but leaves an explicit user download running', async () => {
  const f = await fixture({ models: [downloadedModel('downloading')], fakeTimers: true });
  f.render(); await flush(); f.render();
  assert.equal(f.timers.size, 1); f.dispose();
  assert.equal(f.timers.size, 0);
  assert.equal(f.requests.some(row => row.operation === 'tts.cancel_download'), false);
});


test('Qwen uses its native default voice and fixed standard rate without changing other engines', async () => {
  const f = await fixture({ models: [{ ...downloadedModel('ready'), provider: 'qwen3' }] });
  f.snapshot.voices.push(...['Serena', 'Vivian', 'Uncle_Fu'].map(voiceId => ({ id: `qwen3:${voiceId}`, provider: 'qwen3', voiceId, name: voiceId })));
  f.render(); await flush();
  nodes(f.render()).find(node => (node.type?.name === 'Choice' ? node.props.label : node.props['aria-label']) === '语速').props.onChange('1.15');
  selectEngine(f, 'melo');
  assert.equal(nodes(f.render()).find(node => (node.type?.name === 'Choice' ? node.props.label : node.props['aria-label']) === '语速').props.value, 1.15);
  selectEngine(f, 'qwen3'); let tree = nodes(f.render());
  assert.equal(tree.find(node => (node.type?.name === 'Choice' ? node.props.label : node.props['aria-label']) === '语速').props.value, 1);
  assert.equal(tree.find(node => (node.type?.name === 'Choice' ? node.props.label : node.props['aria-label']) === '语速').props.disabled, true);
  assert.equal(tree.find(node => (node.type?.name === 'Choice' ? node.props.label : node.props['aria-label']) === '朗读音色').props.value, 'Serena');
  assert.equal(tree.find(node => node.props['data-droy-voice-preview']).props.disabled, false);
  tree.find(node => node.props['data-droy-voice-save']).props.onClick(); await flush();
  assert.equal(f.requests.find(row => row.operation === 'save_config').patch.tts.rate, 1);
  selectEngine(f, 'system'); tree = nodes(f.render());
  assert.equal(tree.find(node => (node.type?.name === 'Choice' ? node.props.label : node.props['aria-label']) === '语速').props.disabled, false);
  f.dispose();
});


test('a wake-only configuration refresh preserves the unsaved speech-engine draft', async () => {
  const f = await fixture({ models: [downloadedModel()] });
  f.render(); await flush(); selectEngine(f, 'melo'); f.render();
  f.config.wake = { ...f.config.wake, timing: 'foreground' };
  const tree = nodes(f.render());
  assert.equal(tree.find(node => (node.type?.name === 'Choice' ? node.props.label : node.props['aria-label']) === '发声方式').props.value, 'melo');
  assert.equal(tree.find(node => (node.type?.name === 'Choice' ? node.props.label : node.props['aria-label']) === '唤醒时机').props.value, 'foreground');
  assert.equal(tree.find(node => node.props['data-droy-voice-save']).props.disabled, true);
  f.dispose();
});


test('changed wake timing and keywords can be saved independently while the chosen speech engine is not downloaded', async () => {
  const f = await fixture({ models: [downloadedModel()] });
  f.config.wake.enabled = true;
  f.render(); await flush(); selectEngine(f, 'melo');
  let tree = nodes(f.render());
  assert.equal(tree.some(node => node.props['data-droy-voice-save-wake']), false);
  tree.find(node => (node.type?.name === 'Choice' ? node.props.label : node.props['aria-label']) === '唤醒时机').props.onChange('foreground');
  tree.find(node => node.props['aria-label'] === '中文唤醒词').props.onChange({ target: { value: '你好多瑞' } });
  tree = nodes(f.render());
  assert.equal(tree.find(node => node.props['data-droy-voice-save']).props.disabled, true);
  const wakeSave = tree.find(node => node.props['data-droy-voice-save-wake']);
  assert.equal(wakeSave.props.disabled, false); wakeSave.props.onClick(); await flush();
  assert.deepEqual(f.requests.map(row => row.operation), ['wake.configure', 'save_config']);
  assert.equal(f.requests[0].enabled, true);
  const saved = f.requests[1].patch;
  assert.equal('tts' in saved, false);
  assert.equal(saved.wake.timing, 'foreground'); assert.equal(saved.wake.chinese, '你好多瑞');
  f.config.wake = { ...f.config.wake, ...saved.wake };
  tree = nodes(f.render());
  assert.equal(tree.some(node => node.props['data-droy-voice-save-wake']), false);
  assert.equal(tree.find(node => (node.type?.name === 'Choice' ? node.props.label : node.props['aria-label']) === '发声方式').props.value, 'melo');
  assert.ok(tree.some(node => node.children.includes('唤醒设置已保存，朗读方式未切换。')));
  assert.equal(f.config.tts.provider, 'system');
  f.dispose();
});


test('switching the shared DSH language updates the mounted voice settings and retains unsaved fields', async () => {
  const f = await fixture({ initialLocale: 'zh' });
  let tree = nodes(f.render());
  assert.ok(tree.some(node => node.children.includes('语音设置')));
  const select = tree.find(node => (node.type?.name === 'Choice' ? node.props.label : node.props['aria-label']) === '发声方式');
  select.props.onChange('openai');
  tree = nodes(f.render());
  const url = tree.find(node => node.props['aria-label'] === 'TTS 地址');
  url.props.onChange({ target: { value: 'https://voice.example.test/v1' } });
  f.setLocale('en');
  tree = nodes(f.render());
  assert.ok(tree.some(node => node.children.includes('Voice settings')));
  assert.equal(tree.find(node => node.props['aria-label'] === 'TTS URL').props.value, 'https://voice.example.test/v1');
  assert.equal(tree.find(node => node.type?.name === 'Choice' && node.props.label === 'Speech provider').props.value, 'openai');
  const dictionary = f.dictionaries.get('droy.voice');
  assert.deepEqual(Object.keys(dictionary.zh), Object.keys(dictionary.en));
  assert.ok(Object.values(dictionary.en).every(value => typeof value === 'string' && value.length > 0));
  f.setLocale('zh');
  tree = nodes(f.render());
  assert.equal(tree.find(node => node.props['aria-label'] === 'TTS 地址').props.value, 'https://voice.example.test/v1');
  assert.equal(f.requests.length, 0);
});

test('speech dropdown stays in the page and closes only after choosing or leaving its focus group', async () => {
  const f = await fixture();
  const choice = nodes(f.render()).find(node => node.type?.name === 'Choice' && node.props.label === '发声方式');
  const tree = choice.type(choice.props), summary = tree.children.find(node => node?.type === 'summary');
  assert.equal(tree.type, 'details'); assert.equal(summary.props['aria-haspopup'], 'listbox');
  assert.equal(summary.props['data-value'], 'system');
  const list = tree.children.find(node => node?.props?.role === 'listbox');
  assert.equal(list.children.length, 4);
  assert.equal(list.children.find(node => node.props['data-value'] === 'system').props['aria-selected'], true);
  let focused = false;
  const details = { open: true, querySelector: () => ({ focus() { focused = true; } }) };
  list.children.find(node => node.props['data-value'] === 'melo').props.onClick({ currentTarget: { closest: () => details } });
  assert.equal(details.open, false); assert.equal(focused, true);
  assert.equal(nodes(f.render()).find(node => node.type?.name === 'Choice' && node.props.label === '发声方式').props.value, 'melo');
  details.open = true;
  tree.props.onBlur({ currentTarget: { contains: () => true, set open(value) { details.open = value; } }, relatedTarget: {} });
  assert.equal(details.open, true);
  tree.props.onBlur({ currentTarget: { contains: () => false, set open(value) { details.open = value; } }, relatedTarget: null });
  assert.equal(details.open, false);
  f.dispose();
});
