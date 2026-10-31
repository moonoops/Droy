import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, readFile, rm, stat, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createServer } from 'node:http';
import { createConfigStore } from '../config.mjs';
import { createSpeechService, createVoiceHandler, createVoiceService } from '../http.mjs';
import { createVoiceTool } from '../voice-tools.mjs';
import { defineTool, validateArgs } from '../../dsh/node_modules/@deepseek-ai/dsh-tools/lib/index.js';

async function storeFixture(t) {
  const home = await mkdtemp(join(tmpdir(), 'droy-voice-'));
  t.after(() => rm(home, { recursive: true, force: true }));
  const values = new Map();
  const credentials = { resolve: async key => values.has(key) ? { value: values.get(key) } : undefined,
    set: async (key, value) => values.set(key, value), unset: async key => values.delete(key) };
  return { home, values, store: createConfigStore({ home, credentials, env: {} }) };
}
const idleRealtime = () => ({ active: false, snapshot: () => ({ status: 'idle' }), stop: async () => ({ status: 'ended' }),
  testConnection: async () => ({ connected: true }) });

test('wake defaults use Droy and Dory and retired name binding cannot override manual words', async t => {
  const { store, home } = await storeFixture(t);
  assert.equal((await store.publicConfig()).entries.standard, true);
  assert.equal((await store.publicConfig()).wake.timing, 'screen_on');
  assert.equal((await store.publicConfig()).wake.chinese, '你好Droy');
  assert.equal((await store.publicConfig()).wake.english, 'Hey Dory');
  await store.save({ entries: { standard: false }, wake: { timing: 'foreground', useCompanionName: true,
    chinese: '你好测试', english: 'Hello Droy' } });
  const restored = createConfigStore({ home, env: {} });
  assert.equal((await restored.publicConfig()).entries.standard, true);
  assert.equal((await restored.read()).wake.useCompanionName, false);
  await restored.save({ wake: { useCompanionName: false } });
  const wake = (await restored.read()).wake;
  assert.equal(wake.timing, 'foreground'); assert.equal(wake.chinese, '你好测试'); assert.equal(wake.english, 'Hello Droy');
  await assert.rejects(restored.save({ wake: { timing: 'unknown' } }), /唤醒时机不支持/);
  await assert.rejects(restored.save({ wake: { enabled: true, useCompanionName: true, chinese: '', english: '' } }), /至少填写一个唤醒词/);
});

test('system speech is default and downloaded local voice previews do not save drafts', async t => {
  const { store } = await storeFixture(t), calls = [];
  assert.equal((await store.read()).tts.provider, 'system');
  assert.equal((await store.read()).tts.voiceId, '');
  const service = createVoiceService({ store, realtime: idleRealtime(),
    fetchImpl: async () => { assert.fail('Local voices must not send speech over the network'); },
    native: async input => {
      calls.push(input);
      if (input.operation === 'tts.models') return { models: [{ provider: 'melo', ready: true }, { provider: 'qwen3', ready: true }] };
      return input.operation === 'voices' ? { voices: [
        { id: 'zh', provider: 'melo', name: '中文女声' },
        { id: 'Serena', provider: 'qwen3', name: '温柔女声' },
        { id: 'system-fixture', provider: 'system', name: '系统测试声音' },
      ] } : { queued: true };
    } });
  const list = await service.run({ operation: 'voices' });
  assert.equal(list.voices[0].id, 'melo:zh'); assert.equal(list.voices[0].voiceId, 'zh');
  assert.equal(list.voices[1].id, 'qwen3:Serena');
  assert.equal(list.voices[2].id, 'system:system-fixture');
  for (const [provider, voiceId] of [['melo', 'zh'], ['qwen3', 'Serena']]) {
    const rate = provider === 'qwen3' ? 1 : .85;
    await service.run({ operation: 'preview_voice', provider, voiceId, rate });
    const preview = calls.filter(row => row.operation === 'speak').at(-1);
    assert.equal(preview.provider, provider); assert.equal(preview.voiceId, voiceId); assert.equal(preview.rate, rate);
    assert.equal((await store.read()).tts.provider, 'system');
    assert.equal((await store.read()).tts.voiceId, '');
  }
  await service.run({ operation: 'set_voice', id: 'melo:zh' });
  assert.equal((await store.read()).tts.provider, 'melo');
  assert.equal((await store.read()).tts.voiceId, 'zh');
  await service.run({ operation: 'set_voice', id: 'system:system-fixture' });
  await service.run({ operation: 'speak', text: '系统测试' });
  assert.equal(calls.filter(row => row.operation === 'speak').at(-1).provider, 'system');
});

test('downloads are explicit and unfinished local models cannot be saved, selected or spoken', async t => {
  const { store } = await storeFixture(t), calls = [];
  let state = 'not_downloaded';
  const models = () => ({ models: [{ provider: 'melo', state, ready: state === 'ready', downloadedBytes: 0, totalBytes: 100 }] });
  const service = createVoiceService({ store, realtime: idleRealtime(), native: async input => {
    calls.push(input);
    if (input.operation === 'tts.models') return models();
    if (input.operation === 'tts.download') { state = 'downloading'; return models(); }
    if (input.operation === 'tts.cancel_download') { state = 'not_downloaded'; return models(); }
    if (input.operation === 'voices') return { voices: [{ provider: 'melo', id: 'zh', name: '中文女声' }] };
    return {};
  } });
  for (const operation of ['not_downloaded', 'downloading', 'verifying', 'failed']) {
    state = operation;
    for (const input of [
      { operation: 'save_config', patch: { tts: { provider: 'melo' } } },
      { operation: 'set_voice', id: 'melo:zh' },
      { operation: 'preview_voice', provider: 'melo' },
      { operation: 'speak', provider: 'melo', text: '测试' },
    ]) await assert.rejects(service.run(input), error => error.code === 'TTS_MODEL_NOT_READY');
  }
  assert.equal((await store.read()).tts.provider, 'system');
  assert.equal(calls.some(row => row.operation === 'speak' || row.operation === 'tts.download'), false);
  await service.run({ operation: 'save_config', patch: { wake: { timing: 'foreground' } } });
  assert.equal((await store.read()).wake.timing, 'foreground');
  assert.equal((await service.run({ operation: 'tts.download', provider: 'melo' })).models[0].state, 'downloading');
  assert.equal((await service.run({ operation: 'tts.cancel_download', provider: 'melo' })).models[0].state, 'not_downloaded');
  assert.deepEqual(calls.filter(row => row.operation === 'tts.download' || row.operation === 'tts.cancel_download'), [
    { operation: 'tts.download', provider: 'melo' }, { operation: 'tts.cancel_download', provider: 'melo' },
  ]);
  state = 'ready';
  await service.run({ operation: 'save_config', patch: { tts: { provider: 'melo' } } });
  assert.equal((await store.read()).tts.voiceId, 'zh');
  await service.run({ operation: 'speak', text: '已下载的声音' });
  assert.equal(calls.filter(row => row.operation === 'speak').at(-1).provider, 'melo');
  state = 'not_downloaded';
  await service.run({ operation: 'save_config', patch: { entries: { standard: false } } });
  await service.run({ operation: 'save_config', patch: { tts: { provider: 'system' } } });
  assert.equal((await store.read()).tts.provider, 'system');
});

async function httpFixture(t, service, authorize = () => true) {
  const server = createServer(createVoiceHandler({ service, authorize }));
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  t.after(() => new Promise(resolve => server.close(resolve)));
  const url = `http://127.0.0.1:${server.address().port}/droy/voice`;
  return { get: () => fetch(url), post: input => fetch(url, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(input) }) };
}

test('credentials are stored separately; a blank field preserves the old key and explicit removal clears it', async t => {
  const { store, home, values } = await storeFixture(t);
  await store.save({ realtime: { provider: 'gemini', key: 'fixture-private-key' } });
  await store.save({ realtime: { key: '' }, entries: { realtimeVoice: true } });
  assert.equal(await store.key('realtime'), 'fixture-private-key');
  assert.equal(values.size, 1);
  const config = await store.publicConfig();
  assert.equal(config.realtime.hasKey, true);
  assert.equal(config.entries.standard, true);
  assert.equal(config.entries.realtimeVoice, false);
  assert.equal((await store.read()).entries.realtimeVoice, true, 'dormant configuration is preserved on disk');
  assert.equal(config.realtime.model, 'gemini-3.8-live');
  assert.equal(JSON.stringify(config).includes('fixture-private-key'), false);
  assert.equal((await readFile(join(home, 'voice/config.json'), 'utf8')).includes('fixture-private-key'), false);
  await store.save({ realtime: { clearKey: true } });
  assert.equal((await store.publicConfig()).realtime.hasKey, false);
});

test('legacy Kokoro settings migrate to system while other profiles and wake settings survive', async t => {
  const { home, store } = await storeFixture(t);
  await store.save({ wake: { timing: 'foreground' }, tts: { rate: .85 } });
  const saved = JSON.parse(await readFile(join(home, 'voice/config.json'), 'utf8'));
  saved.tts = { ...saved.tts, provider: 'kokoro', voiceId: 'zf_001' };
  saved.voices = [
    { id: 'removed', provider: 'kokoro', voiceId: 'zf_001', name: '旧候选声音' },
    { id: 'retained', provider: 'system', voiceId: 'zh-system', name: '系统声音' },
  ];
  await writeFile(join(home, 'voice/config.json'), JSON.stringify(saved));
  const restored = createConfigStore({ home, env: {} });
  const current = await restored.read();
  assert.equal(current.tts.provider, 'system'); assert.equal(current.tts.voiceId, '');
  assert.equal(current.tts.rate, .85); assert.equal(current.wake.timing, 'foreground');
  assert.deepEqual(current.voices.map(row => row.id), ['retained']);
  await restored.save({ tts: { provider: 'melo' } });
  assert.equal((await restored.read()).tts.voiceId, 'zh');
  await restored.save({ tts: { provider: 'qwen3' } });
  assert.equal((await restored.read()).tts.voiceId, 'Serena');
  await restored.save({ tts: { provider: 'system' } });
  assert.equal((await restored.read()).tts.voiceId, '');
  await assert.rejects(restored.save({ tts: { provider: 'kokoro' } }), /语音服务不支持/);
});

test('Qwen resets inherited speed to standard and rejects explicit unsupported speed', async t => {
  const { store } = await storeFixture(t), calls = [];
  await store.save({ tts: { rate: 1.15 } });
  const service = createVoiceService({ store, realtime: idleRealtime(), native: async input => {
    calls.push(input);
    return input.operation === 'tts.models' ? { models: [{ provider: 'qwen3', ready: true }] } : {};
  } });
  await service.run({ operation: 'speak', provider: 'qwen3', text: '标准语速' });
  assert.equal(calls.find(row => row.operation === 'speak').rate, 1);
  await assert.rejects(service.run({ operation: 'speak', provider: 'qwen3', rate: 1.15, text: '不支持的语速' }), /仅支持标准语速/);
  await assert.rejects(service.run({ operation: 'save_config', patch: { tts: { provider: 'qwen3', rate: .85 } } }), /仅支持标准语速/);
  await assert.rejects(service.run({ operation: 'add_voice', voice: { provider: 'qwen3', voiceId: 'Serena', name: '测试', rate: 1.15 } }), /仅支持标准语速/);
  assert.equal((await store.read()).tts.provider, 'system');
  await service.run({ operation: 'save_config', patch: { tts: { provider: 'qwen3' } } });
  assert.equal((await store.read()).tts.rate, 1);
  assert.equal((await store.read()).tts.voiceId, 'Serena');
});

test('authenticated HTTP masks secrets and the retired entry switch does not disable voice access', async t => {
  const { store } = await storeFixture(t); const calls = [];
  const native = async input => { calls.push(input); return { voices: [], ready: true }; };
  const service = createVoiceService({ store, native, realtime: idleRealtime() });
  const http = await httpFixture(t, service);
  await http.post({ operation: 'save_config', patch: { tts: { key: 'fixture-tts-key' }, entries: { standard: false } } });
  const result = await (await http.get()).json();
  assert.equal(result.result.config.tts.hasKey, true);
  assert.equal(JSON.stringify(result).includes('fixture-tts-key'), false);
  const started = await http.post({ operation: 'start', mode: 'standard', sessionId: 'fixture-session' });
  assert.equal(started.status, 200);
  assert.equal(calls.filter(row => row.operation === 'start').length, 1);
});

test('HTTP denial does not read configuration or dispatch native actions', async t => {
  const http = await httpFixture(t, { snapshot() { assert.fail('unauthorized read'); }, run() { assert.fail('unauthorized mutation'); } },
    (_req, res) => { res.writeHead(401); res.end(); return false; });
  assert.equal((await http.get()).status, 401);
  assert.equal((await http.post({ operation: 'start' })).status, 401);
});

test('hidden reference audio and realtime settings remain private and untouched by ordinary voice reads', async t => {
  const { store, home } = await storeFixture(t);
  await store.save({ entries: { realtimeVoice: true, realtimeVideo: true },
    realtime: { provider: 'comni', url: 'wss://voice.example.test' }, wake: { target: 'realtime' } });
  const pcm = Buffer.alloc(16000 * 4);
  const input = { name: 'Fixture reference.wav', data: pcm.toString('base64'),
    sampleRate: 16000, channels: 1, encoding: 'pcm_f32le' };
  await store.setReferenceAudio(input);
  const configFile = join(home, 'voice/config.json'), referenceFile = join(home, 'voice/comni-reference.json');
  const savedConfig = await readFile(configFile, 'utf8'), savedReference = await readFile(referenceFile, 'utf8');
  const service = createVoiceService({ store, native: async () => ({}), realtime: idleRealtime() });
  const http = await httpFixture(t, service);
  const response = await (await http.get()).json(), config = response.result.config;
  assert.equal(config.entries.realtimeVoice, false);
  assert.equal(config.entries.realtimeVideo, false);
  assert.equal(config.wake.target, 'standard');
  assert.equal(config.interruptible, false);
  assert.equal(JSON.stringify(response).includes(input.data), false);
  assert.equal(config.realtime.referenceAudioName, undefined);
  assert.equal(await readFile(configFile, 'utf8'), savedConfig);
  assert.equal(await readFile(referenceFile, 'utf8'), savedReference);
  assert.equal((await stat(referenceFile)).mode & 0o777, 0o600);
  assert.equal((await createConfigStore({ home, env: {} }).referenceAudio()).data, input.data);
  await writeFile(referenceFile, 'retained legacy reference data');
  const reloaded = createConfigStore({ home, env: {} });
  assert.equal((await reloaded.publicConfig()).entries.standard, true, 'dormant reference files cannot break ordinary startup');
  assert.equal(await readFile(referenceFile, 'utf8'), 'retained legacy reference data');
});

test('Comni reference validation rejects wrong formats, alignment, duration and non-finite or unnormalized samples', async t => {
  const { store } = await storeFixture(t);
  const pcm = Buffer.alloc(16000 * 4), base = { name: 'Fixture.wav', data: pcm.toString('base64'), sampleRate: 16000, channels: 1, encoding: 'pcm_f32le' };
  const invalid = [
    { sampleRate: 48000 }, { channels: 2 }, { encoding: 'pcm_s16le' }, { data: 'not base64' },
    { data: Buffer.alloc(32001).toString('base64') }, { data: Buffer.alloc(31996).toString('base64') },
    { data: Buffer.alloc(1920004).toString('base64') },
  ];
  for (const value of [NaN, Infinity, -Infinity, 1.01]) {
    const bytes = Buffer.from(pcm); bytes.writeFloatLE(value); invalid.push({ data: bytes.toString('base64') });
  }
  for (const patch of invalid)
    await assert.rejects(store.setReferenceAudio({ ...base, ...patch }), error => error.code === 'INVALID_REFERENCE_AUDIO');
  assert.equal(await store.referenceAudio(), null);
});

test('paused realtime and voiceprint operations are rejected before configuration, model or native work', async t => {
  const forbidden = () => assert.fail('paused capability must not perform work');
  const service = createVoiceService({ store: { read: forbidden, publicConfig: forbidden, key: forbidden, referenceAudio: forbidden,
    setReferenceAudio: forbidden, removeReferenceAudio: forbidden }, native: forbidden,
    realtime: { active: false, start: forbidden, video: forbidden, testConnection: forbidden } });
  const http = await httpFixture(t, service);
  const operations = ['start_realtime', 'poll_realtime', 'interrupt_realtime', 'send_video', 'set_video', 'claim_tool',
    'tool_response', 'test_connection', 'set_reference_audio', 'remove_reference_audio', 'wake.enroll_start',
    'wake.enroll_poll', 'wake.enroll_cancel'];
  for (const operation of operations) {
    const response = await http.post({ operation, enabled: true });
    assert.equal(response.status, 400, operation);
    assert.equal((await response.json()).error.code, 'FEATURE_UNAVAILABLE', operation);
  }
  await assert.rejects(service.run({ operation: 'start', mode: 'realtime' }), error => error.code === 'FEATURE_UNAVAILABLE');
});

test('legacy wake targets are normalized without disabling ordinary ASR and native wake controls', async t => {
  const { store } = await storeFixture(t), calls = [];
  const service = createVoiceService({ store, native: async input => { calls.push(input); return {}; }, realtime: idleRealtime() });
  await service.run({ operation: 'wake.configure', enabled: true, target: 'realtime' });
  await service.run({ operation: 'wake.claim', owner: 'fixture' });
  await service.run({ operation: 'start', mode: 'standard', sessionId: 'fixture' });
  await service.run({ operation: 'poll' });
  assert.deepEqual(calls.map(input => input.operation), ['wake.configure', 'wake.claim', 'start', 'poll']);
  assert.equal(calls[0].target, 'standard');
});

test('public snapshot never restores a stale realtime session', async t => {
  const { store } = await storeFixture(t);
  const service = createVoiceService({ store, native: async () => ({}), realtime: {
    ...idleRealtime(), snapshot: () => ({ status: 'active', sessionId: 'old-realtime-session' }) } });
  assert.deepEqual((await service.snapshot()).state, { status: 'idle' });
});

test('custom voices persist, can be selected by their exact id, and combine with installed system voices', async t => {
  const { store, home } = await storeFixture(t);
  const native = async () => ({ ready: true, voices: [{ id: 'zh-installed', name: '本机中文', language: 'zh-CN' }] });
  const service = createVoiceService({ store, native, realtime: idleRealtime() });
  const custom = await service.run({ operation: 'add_voice', voice: { provider: 'openai', voiceId: 'fixture-voice', name: '小黑声音', rate: 1.1 } });
  const result = await service.run({ operation: 'set_voice', id: custom.id });
  assert.equal(result.tts.provider, 'openai'); assert.equal(result.tts.voiceId, 'fixture-voice');
  assert.equal((await service.run({ operation: 'voices' })).voices.length, 2);
  const loaded = createConfigStore({ home, env: {} });
  assert.equal((await loaded.read()).voices[0].id, custom.id);
  await service.run({ operation: 'set_voice', id: 'system:zh-installed' });
  assert.equal((await store.read()).tts.voiceId, 'zh-installed');
});

test('remote TTS preserves PCM alignment across network chunks and uses the configured credential', async t => {
  const { store } = await storeFixture(t); const played = [];
  await store.save({ tts: { provider: 'openai', url: 'https://speech.example.test/v1', model: 'fixture-model', voiceId: 'fixture-voice', key: 'fixture-key' } });
  const service = createSpeechService({ store, native: async input => { played.push(input); return {}; },
    fetchImpl: async (url, init) => {
      assert.equal(url.pathname, '/v1/audio/speech');
      assert.equal(init.headers.Authorization, 'Bearer fixture-key');
      assert.equal(JSON.parse(init.body).response_format, 'pcm');
      return new Response(new ReadableStream({ start(controller) {
        controller.enqueue(Uint8Array.from([1, 2, 3])); controller.enqueue(Uint8Array.from([4, 5, 6])); controller.close();
      } }), { headers: { 'Content-Type': 'audio/pcm' } });
    } });
  await service.speak({ text: '测试声音', utteranceId: 'fixture-turn' });
  const output = Buffer.concat(played.filter(input => input.operation === 'play').map(input => Buffer.from(input.audio, 'base64')));
  assert.deepEqual([...output], [1, 2, 3, 4, 5, 6]);
  assert(played.filter(input => input.operation === 'play').every(input => input.sampleRate === 24000));
});

test('a TTS reply retains its voice and credential until the next reply', async t => {
  const { store } = await storeFixture(t); const requests = [];
  await store.save({ tts: { provider: 'openai', url: 'https://speech.example.test/v1', voiceId: 'voice-one', key: 'key-one' } });
  const service = createSpeechService({ store, native: async () => ({}), fetchImpl: async (_url, init) => {
    requests.push({ voice: JSON.parse(init.body).voice, key: init.headers.Authorization });
    return new Response(Uint8Array.from([0, 0]));
  } });
  await service.speak({ text: '第一句', replyId: 'reply-one' });
  await store.save({ tts: { voiceId: 'voice-two', key: 'key-two' } });
  await service.speak({ text: '第二句', replyId: 'reply-one' });
  await service.speak({ text: '新回复', replyId: 'reply-two' });
  assert.deepEqual(requests, [
    { voice: 'voice-one', key: 'Bearer key-one' }, { voice: 'voice-one', key: 'Bearer key-one' },
    { voice: 'voice-two', key: 'Bearer key-two' },
  ]);
});

test('remote TTS paces a fast response and stop rejects delayed audio for the old response id', async t => {
  const { store } = await storeFixture(t); const calls = [], times = [];
  await store.save({ tts: { provider: 'openai', url: 'https://speech.example.test/v1', voiceId: 'fixture' } });
  const service = createSpeechService({ store, native: async input => {
    calls.push(input); if (input.operation === 'play') times.push(performance.now()); return {};
  }, fetchImpl: async () => new Response(Buffer.alloc(72000)) });
  await service.speak({ text: '测试一段较长的声音', replyId: 'fixture-reply' });
  assert.equal(times.length, 3);
  assert(times[2] - times[0] >= 400);
  await service.stop();
  const played = calls.find(input => input.operation === 'play');
  assert(calls.some(input => input.operation === 'clear_playback' && input.responseId === played.responseId));
});

test('stopping remote TTS cancels the in-flight fetch and queued speech never starts', async t => {
  const { store } = await storeFixture(t); let started = 0, entered;
  const fetching = new Promise(resolve => { entered = resolve; });
  await store.save({ tts: { provider: 'openai', url: 'https://speech.example.test/v1', voiceId: 'fixture' } });
  const calls = [];
  const speech = createSpeechService({ store, native: async input => { calls.push(input); return {}; },
    fetchImpl: async (_url, { signal }) => {
      started++; entered();
      return new Promise((_resolve, reject) => signal.addEventListener('abort', () => reject(signal.reason), { once: true }));
    } });
  const first = speech.speak({ text: '第一句话' }), second = speech.speak({ text: '第二句话' });
  await fetching; await speech.stop();
  assert.deepEqual(await first, { cancelled: true }); assert.deepEqual(await second, { cancelled: true });
  assert.equal(started, 1); assert(calls.some(input => input.operation === 'stop_speaking'));
  assert.equal(calls.some(input => input.operation === 'play'), false);
});

test('stop while settings or credentials are resolving prevents late system speech and remote requests', async () => {
  for (const stage of ['settings', 'credentials']) {
    const calls = []; let release, entered;
    const waiting = new Promise(resolve => { entered = resolve; });
    const config = { tts: { provider: stage === 'settings' ? 'system' : 'openai', url: 'https://speech.example.test/v1', voiceId: 'fixture', rate: 1 } };
    const store = {
      read: async () => stage === 'settings' ? new Promise(resolve => { release = () => resolve(config); entered(); }) : config,
      key: async () => new Promise(resolve => { release = () => resolve('fixture-key'); entered(); }),
    };
    const speech = createSpeechService({ store, native: async input => { calls.push(input.operation); return {}; },
      fetchImpl: async () => { calls.push('fetch'); assert.fail('cancelled synthesis must not start'); } });
    const pending = speech.speak({ text: '这段朗读已被取消', replyId: `fixture-${stage}` });
    await waiting; await speech.stop(); release();
    assert.deepEqual(await pending, { cancelled: true });
    assert.deepEqual(calls, ['stop_speaking', 'clear_playback']);
  }
});

test('stop during model readiness lookup prevents late local playback', async t => {
  const { store } = await storeFixture(t), calls = [];
  let release, entered;
  const waiting = new Promise(resolve => { entered = resolve; });
  const speech = createSpeechService({ store, native: async input => {
    calls.push(input);
    if (input.operation === 'tts.models') return new Promise(resolve => { entered(); release = resolve; });
    return {};
  } });
  const pending = speech.speak({ provider: 'melo', text: '已取消的本地朗读' });
  await waiting; await speech.stop();
  release({ models: [{ provider: 'melo', ready: true }] });
  assert.deepEqual(await pending, { cancelled: true });
  assert.equal(calls.some(row => row.operation === 'speak'), false);
});

test('model downloads and status do not wait behind an active speech response', async t => {
  const { store } = await storeFixture(t), calls = [];
  let entered;
  const fetching = new Promise(resolve => { entered = resolve; });
  await store.save({ tts: { provider: 'openai', url: 'https://speech.example.test/v1', voiceId: 'fixture' } });
  const service = createVoiceService({ store, realtime: idleRealtime(), native: async input => {
    calls.push(input.operation); return { models: [{ provider: 'qwen3', ready: false, state: 'downloading' }] };
  }, fetchImpl: async (_url, { signal }) => {
    entered();
    return new Promise((_resolve, reject) => signal.addEventListener('abort', () => reject(signal.reason), { once: true }));
  } });
  const pending = service.run({ operation: 'speak', text: '尚未完成的网络朗读' });
  await fetching;
  await service.run({ operation: 'tts.download', provider: 'qwen3' });
  await service.run({ operation: 'tts.models' });
  assert.deepEqual(calls, ['status', 'tts.download', 'tts.models']);
  await service.run({ operation: 'stop_speaking' });
  assert.deepEqual(await pending, { cancelled: true });
});

test('stopping a preview during profile lookup does not start speech after the lookup resolves', async () => {
  const calls = []; let release, entered;
  const waiting = new Promise(resolve => { entered = resolve; });
  const speech = createSpeechService({ store: { findVoice: async () => new Promise(resolve => { entered(); release = resolve; }) },
    native: async input => { calls.push(input.operation); return {}; } });
  const preview = speech.preview('fixture-voice');
  await waiting; await speech.stop(); release({ provider: 'system', voiceId: 'fixture', rate: 1 });
  assert.deepEqual(await preview, { cancelled: true });
  assert.equal(calls.includes('speak'), false);
});

test('ending a call while its configuration loads prevents late standard capture', async () => {
  const calls = []; let release, entered;
  const waiting = new Promise(resolve => { entered = resolve; });
  const service = createVoiceService({ store: { read: async () => new Promise(resolve => { entered(); release = resolve; }) },
    native: async input => { calls.push(input.operation); return {}; }, realtime: idleRealtime() });
  const starting = service.run({ operation: 'start', sessionId: 'fixture' });
  await waiting;
  await service.run({ operation: 'stop' });
  release({ entries: { standard: true } });
  assert.equal((await starting).reason, 'cancelled_start');
  assert.equal(calls.includes('start'), false);
});

test('realtime speech owns the output and rejects a second TTS voice', async t => {
  const { store } = await storeFixture(t); let spoken = false;
  const service = createVoiceService({ store, native: async () => { spoken = true; }, realtime: { ...idleRealtime(), active: true } });
  await assert.rejects(service.run({ operation: 'preview_voice' }), error => error.code === 'VOICE_BUSY');
  await assert.rejects(service.run({ operation: 'speak', text: '测试' }), error => error.code === 'VOICE_BUSY');
  assert.equal(spoken, false);
});

test('unknown exceptions do not expose their message or credentials over HTTP', async t => {
  const http = await httpFixture(t, { snapshot: async () => { throw new Error('fixture-secret-must-not-leak'); } });
  const response = await http.get();
  assert.equal(response.status, 500);
  assert.equal((await response.text()).includes('fixture-secret-must-not-leak'), false);
});

test('native permission errors keep their code separate from the user message', async t => {
  const http = await httpFixture(t, { run: async () => {
    throw Object.assign(new Error('PERMISSION_DENIED: 音频采集已暂停或对应能力已关闭\n{"fixture":"details"}'), { code: 'PERMISSION_DENIED' });
  } });
  const response = await http.post({ operation: 'start' });
  assert.equal(response.status, 409);
  assert.deepEqual((await response.json()).error, { code: 'PERMISSION_DENIED', message: '音频采集已暂停或对应能力已关闭' });
});

test('plugin disposal suspends wake first and still releases native media after a speech-stop failure', async t => {
  const { store } = await storeFixture(t), calls = [];
  const service = createVoiceService({ store, realtime: idleRealtime(), native: async input => {
    calls.push(input.operation);
    if (input.operation === 'stop_speaking') throw new Error('fixture speech failure');
    return {};
  } });
  await assert.rejects(service.dispose(), AggregateError);
  assert.equal(calls[0], 'wake.suspend'); assert.equal(calls.at(-1), 'dispose');
});

test('voice tool uses configured profiles and requires trusted execution identity', async () => {
  const calls = [], tool = createVoiceTool({ run: async input => { calls.push(input); return { applied: true }; } });
  const exec = { agent: { id: 'fixture-agent' }, signal: new AbortController().signal };
  await tool.execute({ operation: 'set_voice', voiceId: 'fixture-profile' }, exec);
  assert.deepEqual(calls, [{ operation: 'set_voice', id: 'fixture-profile' }]);
  await assert.rejects(tool.execute({ operation: 'preview', voiceId: 'fixture-profile' }, { signal: exec.signal }), /可信调用身份/u);
});

test('voice tool compiles against the real bound DSH schema DSL', () => {
  const definition = createVoiceTool({ run: async () => ({ applied: true }) });
  const tool = defineTool(definition);
  assert.equal(tool.name, 'droy_voice');
  assert.deepEqual(validateArgs(definition.parameters, { operation: 'list_voices' }), []);
  assert.deepEqual(validateArgs(definition.parameters, { operation: 'set_voice', voiceId: 'fixture' }), []);
  assert(validateArgs(definition.parameters, {}).length > 0);
});

test('wake startup identity reaches both system speech and compatible PCM playback', async t => {
  const { store } = await storeFixture(t), calls = [];
  const native = async input => { calls.push(input); return {}; };
  const speech = createSpeechService({ store, native, fetchImpl: async () => new Response(Buffer.alloc(480), { status: 200 }) });
  await speech.speak({ text: '我在', wakeEventId: 17 });
  assert.equal(calls.find(row => row.operation === 'speak').wakeEventId, 17);
  await store.save({ tts: { provider: 'openai', url: 'https://voice.example.test/v1', voiceId: 'fixture' } });
  await speech.speak({ text: '我在', wakeEventId: 18 });
  assert.equal(calls.find(row => row.operation === 'play').wakeEventId, 18);
});

test('one response keeps its selected synthesis profile across assistant attempts', async t => {
  const { store } = await storeFixture(t), calls = [];
  const speech = createSpeechService({ store, native: async input => { calls.push(input); return {}; } });
  await speech.speak({ text: '第一句。', replyId: 'session:1', voiceId: 'first', rate: .85 });
  await store.save({ tts: { voiceId: 'second', rate: 1.15 } });
  await speech.speak({ text: '工具后的回答。', replyId: 'session:1', voiceId: 'second', rate: 1.15 });
  await speech.speak({ text: '下一次回答。', replyId: 'session:2' });
  assert.deepEqual(calls.filter(row => row.operation === 'speak').map(row => [row.voiceId, row.rate]), [['first', .85], ['first', .85], ['second', 1.15]]);
});


test('voice preview follows the current DSH language without changing the saved synthesis profile', async t => {
  const { store } = await storeFixture(t), calls = [];
  let locale = 'en';
  const speech = createSpeechService({ store, getLocale: () => locale, native: async input => { calls.push(input); return {}; } });
  const before = await store.read();
  await speech.preview();
  assert.equal(calls.findLast(row => row.operation === 'speak').text, 'Hi, I am Droy. We can chat using this voice.');
  locale = 'zh';
  await speech.preview();
  assert.equal(calls.findLast(row => row.operation === 'speak').text, '你好，我是 Droy。我们可以用这个声音聊天。');
  assert.deepEqual(await store.read(), before);
});
