import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, readFile, writeFile, readdir, rm, symlink } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { Readable } from 'node:stream';
import { createPetPersona, createDshPetPersonaGenerator, validateGeneratedPersona, DEFAULT_COMPANION_DESCRIPTION } from '../pet-persona.mjs';
import { createOpenAiPetGenerator, createPetCreationService, installPetGeneration, PetCreationError, resolvePetImageKey } from '../pet-generation.mjs';
import { PET_ACTION_GROUPS, inspectActionAtlas, actionPrompt, brandLogoPrompt, validateBrandLogoPng, validatePetPng } from '../pet-generation-assets.mjs';
import { encodePng, decodePng } from '../png.mjs';

const input = { name: '测试猫', description: '纯黑黄色眼睛的小猫，左耳戴一个蓝色耳环', personality: '温柔，话少，有一点傲娇，关心我' };
function png(rows = 1, still = false, opaque = false) {
  const width = 256, height = Math.max(256, rows * 64), channels = 4;
  const pixels = Buffer.alloc(width * height * 4);
  if (opaque) for (let i = 3; i < pixels.length; i += 4) pixels[i] = 255;
  for (let row = 0; row < rows; row++) for (let col = 0; col < 4; col++) {
    const x0 = col * 64 + 12, y0 = Math.floor(row * height / rows) + 12;
    for (let y = y0; y < y0 + 28; y++) for (let x = x0; x < x0 + 26 + (still ? 0 : col); x++) {
      const index = (y * width + x) * 4;
      pixels[index] = 20 + row; pixels[index + 1] = 40; pixels[index + 2] = 60; pixels[index + 3] = 255;
    }
  }
  return encodePng({ width, height, channels, pixels });
}
function mockGenerator({ available = true, failGroup } = {}) {
  const calls = [];
  return {
    calls,
    async availability() { return { available, credentialConfigured: available, provider: 'MOCK — no external inference', model: 'gpt-image-2.5-flare' }; },
    async generate(args) {
      calls.push(args);
      if (!args.reference) return png();
      if (args.prompt.includes('mobile app and in-app brand logo')) return png();
      const group = PET_ACTION_GROUPS.find(item => args.prompt.includes(`Row 1: ${item.states[0]},`));
      assert.ok(group, 'prompt follows known grid contract');
      if (failGroup?.(group.id)) throw new PetCreationError('MOCK_FAILURE', '模拟生成失败', 502);
      return png(group.states.length * 2);
    },
  };
}
const mockPersonaGenerator = () => ({
  async availability() { return { available: true, textProvider: 'mock', textModel: 'scripted-persona' }; },
  async generate({ description }) { return createPetPersona(description); },
});
async function fixture(t, generator = mockGenerator(), personaGenerator = mockPersonaGenerator()) {
  const home = await mkdtemp(join(tmpdir(), 'droy-pet-generation-'));
  const defaultLogoFile = join(home, 'default-brand-logo.png');
  await writeFile(defaultLogoFile, png());
  const service = createPetCreationService({ home, generator, personaGenerator, defaultLogoFile });
  t.after(async () => { service.dispose(); await service.waitForIdle(); await rm(home, { recursive: true, force: true }); });
  return { home, service, generator };
}

test('personality keeps freeform direction and affects local voice without rewriting task facts', () => {
  const reserved = createPetPersona(input.personality), playful = createPetPersona('活泼热情，喜欢开玩笑');
  assert.equal(reserved.description, input.personality);
  assert.equal(reserved.temperament, 'calm');
  assert.equal(reserved.traits.tsundere, true);
  assert.equal(playful.temperament, 'playful');
  assert.notDeepEqual(reserved.voice, playful.voice);
  assert.ok(Object.values(reserved.voice).flat().every(text => text.includes('{name}') && !text.includes('%')));
  assert.match(actionPrompt({ ...input, persona: reserved }, PET_ACTION_GROUPS[0]), /温柔，话少，有一点傲娇，关心我/);
});

test('the shipped black-cat persona and the creation default use the same concise companion voice', async () => {
  const shipped=JSON.parse(await readFile(new URL('../../../app/src/main/assets/pet/pet.json',import.meta.url),'utf8'));
  const defaultPersona=createPetPersona();
  assert.equal(defaultPersona.description,DEFAULT_COMPANION_DESCRIPTION);
  assert.deepEqual(shipped.persona,defaultPersona);
  assert.equal(defaultPersona.traits.terse,true);
  assert.ok(defaultPersona.voice.working.every(line=>[...line].length<=24&&!/完成|成功|提交|购买/.test(line)));
});

test('missing image configuration preserves an editable draft without calling the model', async t => {
  const { service, generator } = await fixture(t, mockGenerator({ available: false }));
  const pet = await service.create(input);
  assert.equal(pet.status, 'draft'); assert.equal(generator.calls.length, 0);
  const updated = await service.retry({ id: pet.id, personality: '安静稳重', description: '一只灰色小狗' });
  assert.equal(updated.status, 'draft'); assert.equal(updated.personality, '安静稳重');
  assert.equal((await service.list()).pets.length, 1);
  assert.equal(generator.calls.length, 0);
});

test('reference approval precedes all action generation and complete package includes rich bilateral states', async t => {
  const { service, generator, home } = await fixture(t);
  const started = await service.create(input);
  assert.equal(started.status, 'generating_persona');
  await service.waitForIdle();
  const reference = await service.status(started.id);
  assert.equal(reference.status, 'reference_ready'); assert.equal(generator.calls.length, 1);
  assert.equal(reference.manifestUrl, null);
  await assert.rejects(service.asset(started.id, 'pet.json'), error => error.code === 'NOT_READY');
  await service.generate({ id: started.id }); await service.waitForIdle();
  const ready = await service.status(started.id);
  assert.equal(ready.status, 'ready'); assert.equal(ready.completedGroups, PET_ACTION_GROUPS.length);
  assert.equal(generator.calls.length, PET_ACTION_GROUPS.length + 2);
  assert.ok(generator.calls.slice(1).every(call => Buffer.isBuffer(call.reference)));
  assert.equal(generator.calls[1].size, '1024x1024');
  assert.ok(ready.logoUrl?.endsWith('brand-logo.png'));
  const manifest = JSON.parse((await service.asset(started.id, 'pet.json')).body);
  assert.equal(manifest.formatVersion, 2); assert.equal(manifest.persona.description, input.personality);
  assert.deepEqual(manifest.branding, { logo: 'brand-logo.png' });
  assert.deepEqual((await service.brandLogo(started.id)).body, (await service.asset(started.id, 'brand-logo.png')).body);
  assert.deepEqual(manifest.variants.enter, ['enterPeek', 'enterWalk', 'enterJump']);
  for (const state of ['idle', 'tapTilt', 'petShy', 'enterJump', 'exitHide']) {
    assert.equal(manifest.states[state].facing, 'left');
    assert.equal(manifest.states[manifest.states[state].rightState].facing, 'right');
    assert.equal(manifest.states[state].mirrorSafe, false);
  }
  assert.equal(manifest.states.peekLeft.facing, 'right'); assert.equal(manifest.states.peekRight.facing, 'left');
  const frameRects = manifest.atlases.movement.frames.slice(0, 4).map(frame => frame.sourceRect.slice(2));
  assert.ok(frameRects.every(rect => JSON.stringify(rect) === JSON.stringify(frameRects[0])), 'one animation uses stable crop bounds');
  assert.ok((await readdir(join(home, 'pets', ready.id))).includes('generation-prompts.json'));
  await assert.rejects(service.retry({ id: ready.id }), error => error.code === 'ALREADY_READY');
  await assert.rejects(service.generate({ id: ready.id, group: 'movement' }), error => error.code === 'ALREADY_READY');
});

test('one failed group preserves reference and completed groups; retry does not regenerate the successes', async t => {
  let shouldFail = true;
  const generator = mockGenerator({ failGroup: group => group === 'task' && shouldFail });
  const { service } = await fixture(t, generator);
  const pet = await service.create(input); await service.waitForIdle();
  await service.generate({ id: pet.id }); await service.waitForIdle();
  const failed = await service.status(pet.id);
  assert.equal(failed.status, 'error'); assert.equal(failed.completedGroups, 2); assert.ok(failed.referenceUrl);
  shouldFail = false;
  await service.generate({ id: pet.id }); await service.waitForIdle();
  assert.equal((await service.status(pet.id)).status, 'ready');
  assert.equal(generator.calls.filter(call => call.prompt.includes('Row 1: idle,')).length, 1);
  assert.equal(generator.calls.filter(call => call.prompt.includes('mobile app and in-app brand logo')).length, 1);
});

test('a failed logo preserves the approved reference and retry generates only missing branding and actions', async t => {
  const generator = mockGenerator(), original = generator.generate;
  let fail = true;
  generator.generate = async args => {
    if (fail && args.prompt.includes('mobile app and in-app brand logo')) {
      generator.calls.push(args);
      throw new PetCreationError('MOCK_FAILURE', '模拟品牌标志生成失败', 502);
    }
    return original(args);
  };
  const { service } = await fixture(t, generator);
  const pet = await service.create(input); await service.waitForIdle();
  const reference = (await service.asset(pet.id, 'reference.png')).body;
  await service.generate({ id: pet.id }); await service.waitForIdle();
  const failed = await service.status(pet.id);
  assert.equal(failed.status, 'error'); assert.equal(failed.logoUrl, null); assert.equal(failed.completedGroups, 0);
  await assert.rejects(service.asset(pet.id, 'pet.json'), error => error.code === 'NOT_READY');
  fail = false;
  await service.generate({ id: pet.id }); await service.waitForIdle();
  assert.equal((await service.status(pet.id)).status, 'ready');
  assert.equal(generator.calls.filter(call => !call.reference).length, 1);
  assert.deepEqual((await service.asset(pet.id, 'reference.png')).body, reference);
});

test('legacy completed packages retain their identity and use the default logo without rewriting their manifest', async t => {
  const { service, home } = await fixture(t);
  const pet = await service.create(input); await service.waitForIdle();
  await service.generate({ id: pet.id }); await service.waitForIdle();
  const directory = join(home, 'pets', pet.id), manifestFile = join(directory, 'pet.json'), draftFile = join(directory, 'draft.json');
  const manifest = JSON.parse(await readFile(manifestFile, 'utf8')); delete manifest.branding;
  const draft = JSON.parse(await readFile(draftFile, 'utf8')); delete draft.brandLogoReady;
  await writeFile(manifestFile, JSON.stringify(manifest)); await writeFile(draftFile, JSON.stringify(draft));
  await rm(join(directory, 'brand-logo.png'));
  const before = await readFile(manifestFile);
  assert.equal((await service.status(pet.id)).status, 'ready');
  assert.equal((await service.status(pet.id)).logoUrl, null);
  assert.deepEqual((await service.brandLogo(pet.id)).body, (await service.brandLogo()).body);
  assert.deepEqual(await readFile(manifestFile), before);
  await assert.rejects(service.generate({ id: pet.id }), error => error.code === 'ALREADY_READY');
});

test('branding assets enforce PNG bounds, square dimensions and fixed UUID locations', async t => {
  const { service, home } = await fixture(t);
  const pet = await service.create(input); await service.waitForIdle();
  await service.generate({ id: pet.id }); await service.waitForIdle();
  for (const id of ['../outside', '/tmp/secret', 'default/../../outside'])
    await assert.rejects(service.brandLogo(id), error => error.code === 'INVALID_ID');
  const file = join(home, 'pets', pet.id, 'brand-logo.png');
  await rm(file); await symlink(join(home, 'default-brand-logo.png'), file);
  await assert.rejects(service.brandLogo(pet.id), error => error.code === 'ELOOP');
  assert.throws(() => validateBrandLogoPng(png(6)), /正方形/);
  assert.match(brandLogoPrompt({ ...input, persona: createPetPersona(input.personality) }), /head or deliberate upper-body/);
});

test('failed reference revision retains the old image but cannot combine it with the new description', async t => {
  const generator = mockGenerator(), { service } = await fixture(t, generator);
  const pet = await service.create(input); await service.waitForIdle();
  const original = (await service.asset(pet.id, 'reference.png')).body;
  generator.generate = async () => { throw new Error('fictional-private-provider-body'); };
  await service.retry({ id: pet.id, description: '另一只白色宠物' }); await service.waitForIdle();
  const failed = await service.status(pet.id);
  assert.equal(failed.status, 'error'); assert.equal(failed.referenceCurrent, false);
  assert.ok(failed.referenceUrl); assert.doesNotMatch(failed.error, /fictional-private/);
  assert.deepEqual((await service.asset(pet.id, 'reference.png')).body, original);
  await assert.rejects(service.generate({ id: pet.id }), error => error.code === 'REFERENCE_REQUIRED');
});

test('grid validator rejects duplicate static frames, nontransparent images and clipped cells', () => {
  const group = PET_ACTION_GROUPS[0];
  assert.throws(() => inspectActionAtlas(png(8, true), group), /动作没有变化/);
  assert.throws(() => inspectActionAtlas(png(8, false, true), group), /透明背景/);
  assert.throws(() => inspectActionAtlas(Buffer.from('not a png'), group), /PNG/);
});

test('PNG corner alpha must exactly match native acceptance, including almost transparent pixels', () => {
  const group = PET_ACTION_GROUPS[0];
  for (const corner of ['top-left', 'bottom-right']) for (const alpha of [1, 32, 255]) {
    const image = decodePng(png(group.states.length * 2));
    image.pixels[corner === 'top-left' ? 3 : image.pixels.length - 1] = alpha;
    const bytes = encodePng(image);
    assert.throws(() => inspectActionAtlas(bytes, group), /左上角和右下角需要完全透明/, `${corner} alpha=${alpha}`);
  }
  assert.doesNotThrow(() => inspectActionAtlas(png(group.states.length * 2), group));
});

test('a native-incompatible action corner cannot publish an immutable ready candidate', async t => {
  const generator = mockGenerator(), generate = generator.generate;
  generator.generate = async args => {
    const bytes = await generate(args);
    if (!args.reference) return bytes;
    const image = decodePng(bytes); image.pixels[3] = 1;
    return encodePng(image);
  };
  const { service, home } = await fixture(t, generator);
  const pet = await service.create(input); await service.waitForIdle();
  await service.generate({ id: pet.id }); await service.waitForIdle();
  const failed = await service.status(pet.id);
  assert.equal(failed.status, 'error'); assert.equal(failed.completedGroups, 0);
  assert.match(failed.error, /完全透明/);
  await assert.rejects(service.asset(pet.id, 'pet.json'), error => error.code === 'NOT_READY');
  assert.ok(!(await readdir(join(home, 'pets', pet.id))).includes('pet.json'));
});

test('draft identifiers and asset names cannot escape the pets directory, including symlinked drafts', async t => {
  const { service, home } = await fixture(t, mockGenerator({ available: false }));
  const pet = await service.create(input);
  await assert.rejects(service.status('../outside'), error => error.code === 'INVALID_ID');
  await assert.rejects(service.asset(pet.id, '../../private.json'), error => error.code === 'NOT_FOUND');
  const id = '12345678-1234-4123-8123-123456789abc';
  await symlink(home, join(home, 'pets', id));
  await assert.rejects(service.status(id), error => error.code === 'INVALID_DIRECTORY');
});

test('recovered in-progress draft is marked interrupted instead of silently resending a paid request', async t => {
  const { service, home, generator } = await fixture(t, mockGenerator({ available: false }));
  const pet = await service.create(input);
  const file = join(home, 'pets', pet.id, 'draft.json');
  const saved = JSON.parse(await readFile(file, 'utf8')); saved.status = 'generating_reference';
  await writeFile(file, JSON.stringify(saved));
  assert.equal((await service.status(pet.id)).status, 'error'); assert.equal(generator.calls.length, 0);
});

test('concurrent creation is refused while the first model request remains active', async t => {
  let release;
  const gate = new Promise(resolve => { release = resolve; });
  const generator = mockGenerator(); generator.generate = async () => { await gate; return png(); };
  const { service } = await fixture(t, generator);
  const first = await service.create(input);
  await assert.rejects(service.create(input), error => error.code === 'GENERATION_BUSY');
  assert.match((await service.status(first.id)).status, /^generating_/);
  release(); await service.waitForIdle();
});

test('pendingWork observes the same queued creation and generation Promises through final draft persistence without cancellation', async t => {
  const available = Promise.withResolvers(), entered = Promise.withResolvers(), generating = Promise.withResolvers(), image = Promise.withResolvers();
  const generator = mockGenerator(), original = generator.availability; let checks = 0, signal;
  generator.availability = async () => { if (++checks === 1) { entered.resolve(); await available.promise; } return original(); };
  generator.generate = async value => { signal = value.signal; generating.resolve(); await image.promise; return png(); };
  const { service, home } = await fixture(t, generator);
  assert.deepEqual(service.pendingWork(), []);
  const caller = service.create(input); await entered.promise;
  const queued = service.pendingWork(); assert.ok(Object.isFrozen(queued)); assert.deepEqual(queued, [caller]);
  assert.equal(service.pendingWork()[0], caller);
  let idle = false; const waiting = service.waitForIdle().then(() => { idle = true; });
  await new Promise(resolve => setImmediate(resolve)); assert.equal(idle, false);
  available.resolve(); const receipt = await caller; await generating.promise;
  assert.match(receipt.status, /^generating_/);
  const pending = service.pendingWork(); assert.equal(pending.length, 1); assert.notEqual(pending[0], caller);
  assert.equal(service.pendingWork()[0], pending[0]); assert.equal(signal.aborted, false); assert.equal(idle, false);
  image.resolve(); await waiting;
  assert.deepEqual(service.pendingWork(), []);
  const saved = JSON.parse(await readFile(join(home, 'pets', receipt.id, 'draft.json'), 'utf8'));
  assert.equal(saved.status, 'reference_ready'); assert.equal(saved.referenceReady, true);
  assert.equal(signal.aborted, false);
});

test('failed generation and rejected creation remove their real handles after the existing durable failure path completes', async t => {
  const generating = Promise.withResolvers(), image = Promise.withResolvers(), generator = mockGenerator();
  generator.generate = async () => { generating.resolve(); return image.promise; };
  const { service, home } = await fixture(t, generator);
  const receipt = await service.create(input); await generating.promise;
  const pending = service.pendingWork()[0]; assert.ok(pending instanceof Promise);
  image.reject(new Error('synthetic-private-image-failure')); await pending; await service.waitForIdle();
  assert.deepEqual(service.pendingWork(), []);
  const saved = JSON.parse(await readFile(join(home, 'pets', receipt.id, 'draft.json'), 'utf8'));
  assert.equal(saved.status, 'error'); assert.equal(JSON.stringify(saved).includes('synthetic-private-image-failure'), false);
  const invalid = service.create({ ...input, description: '' });
  assert.equal(service.pendingWork()[0], invalid); await assert.rejects(invalid); await service.waitForIdle();
  assert.deepEqual(service.pendingWork(), []);
});

test('OpenAI adapter binds credentials to official origin, asks for alpha, and uses edit reference', async () => {
  const calls = [];
  const adapter = createOpenAiPetGenerator({ resolveKey: async () => 'fictional-image-key', env: {}, fetchImpl: async (url, options) => {
    calls.push({ url, options }); return new Response(JSON.stringify({ data: [{ b64_json: png().toString('base64') }] }));
  } });
  assert.equal((await adapter.availability()).available, true);
  await adapter.generate({ prompt: 'fictional pet' });
  await adapter.generate({ prompt: 'fictional animation', reference: png() });
  await adapter.generate({ prompt: 'fictional brand logo', reference: png(), size: '1024x1024' });
  assert.equal(calls[0].url, 'https://api.openai.com/v1/images/generations');
  assert.equal(calls[0].options.redirect, 'error');
  const body = JSON.parse(calls[0].options.body);
  assert.equal(body.background, 'transparent'); assert.equal(body.output_format, 'png');
  assert.equal(calls[1].url, 'https://api.openai.com/v1/images/edits');
  assert.equal(calls[1].options.body.get('image[]').type, 'image/png');
  assert.equal(calls[1].options.body.get('model'), 'gpt-image-2.5-flare');
  assert.equal(calls[1].options.body.get('size'), '1024x1536');
  assert.equal(calls[2].options.body.get('size'), '1024x1024');
  await assert.rejects(adapter.generate({ prompt: 'test', size: 'arbitrary' }), error => error.code === 'INVALID_IMAGE_SIZE');
});

test('provider failures and invalid credentials do not expose remote response or secret values', async () => {
  const adapter = createOpenAiPetGenerator({ env: { OPENAI_API_KEY: 'fictional-do-not-echo' }, fetchImpl: async () =>
    new Response('fictional-do-not-echo and private diagnostics', { status: 403 }) });
  await assert.rejects(adapter.generate({ prompt: 'test' }), error => {
    assert.doesNotMatch(error.message, /fictional|private diagnostics/); return error.code === 'PROVIDER_REJECTED';
  });
  const invalid = createOpenAiPetGenerator({ env: { OPENAI_API_KEY: 'fictional\ninvalid' } });
  await assert.rejects(invalid.availability(), error => error.code === 'CREDENTIAL_INVALID' && !error.message.includes('fictional'));
});

test('all creation routes require authenticated local session before accessing data', async t => {
  const home = await mkdtemp(join(tmpdir(), 'droy-pet-routes-')), routes = new Map();
  t.after(() => rm(home, { recursive: true, force: true }));
  const ctx = { webServer: { register(route) { routes.set(route.path, route.handler); } }, effect() {}, logger: { warn() {} } };
  installPetGeneration(ctx, { home, generator: mockGenerator({ available: false }), personaGenerator: mockPersonaGenerator() });
  for (const [url, handler] of routes) {
    const req = Readable.from([]); Object.assign(req, { url, method: url.endsWith('create') ? 'POST' : 'GET', headers: { host: 'evil.example' } });
    let status, result;
    await handler(req, { writeHead(value) { status = value; }, end(value) { result = value; } });
    assert.equal(status, 401, url); assert.match(result, /未授权|session unauthorized/);
  }
});

test('logo route serves only authenticated local PNGs with no-store and rejects cross-origin and mutation requests', async t => {
  const home = await mkdtemp(join(tmpdir(), 'droy-brand-routes-')), routes = new Map();
  const defaultLogoFile = join(home, 'default-logo.png'); await writeFile(defaultLogoFile, png());
  const previousToken = process.env.DROY_WEB_TOKEN;
  process.env.DROY_WEB_TOKEN = 'fictional-droy-brand-route-session-token';
  const ctx = { webServer: { register(route) { routes.set(route.path, route.handler); } }, effect() {}, logger: { warn() {} } };
  const generator = mockGenerator(), original = generator.generate;
  generator.generate = args => args.prompt.includes('mobile app and in-app brand logo') ? png(2) : original(args);
  const service = installPetGeneration(ctx, { home, generator, personaGenerator: mockPersonaGenerator(), defaultLogoFile });
  t.after(async () => {
    service.dispose(); await service.waitForIdle(); await rm(home, { recursive: true, force: true });
    if (previousToken === undefined) delete process.env.DROY_WEB_TOKEN; else process.env.DROY_WEB_TOKEN = previousToken;
  });
  const pet = await service.create(input); await service.waitForIdle();
  await service.generate({ id: pet.id }); await service.waitForIdle();
  async function request(query = '', method = 'GET', extraHeaders = {}) {
    const req = Readable.from([]);
    Object.assign(req, { url: `/droy/branding/logo${query}`, method, headers: { host: '127.0.0.1:9876',
      origin: 'http://127.0.0.1:9876', cookie: `droy_session=${process.env.DROY_WEB_TOKEN}`, ...extraHeaders } });
    const result = {};
    await routes.get('/droy/branding/logo')(req, {
      writeHead(status, headers) { Object.assign(result, { status, headers }); }, end(body) { result.body = body; },
    });
    return result;
  }
  const builtIn = await request(), custom = await request(`?petId=${pet.id}`);
  assert.equal(builtIn.status, 200); assert.deepEqual(builtIn.body, png());
  assert.equal(custom.status, 200); assert.deepEqual(custom.body, png(2));
  assert.equal(custom.headers['Content-Type'], 'image/png'); assert.equal(custom.headers['Cache-Control'], 'no-store');
  assert.equal(custom.headers['X-Content-Type-Options'], 'nosniff');
  assert.equal((await request('', 'GET', { origin: 'https://evil.example' })).status, 401);
  assert.equal((await request('', 'GET', { cookie: '' })).status, 401);
  assert.equal((await request('', 'POST')).status, 405);
  const invalid = await request('?petId=..%2Foutside');
  assert.equal(invalid.status, 400); assert.equal(JSON.parse(invalid.body).error.code, 'INVALID_ID');
});

test('DSH selected text model creates freeform voices, and those voices reach the final manifest', async t => {
  const requests = [];
  const scriptedVoice = {
    temperament: 'calm', movementDescription: '像一位文雅书生，先作揖，再缓步入场。',
    voice: { working: ['{name}尚在细察，君且稍候。', '{name}正理此事，容我片刻。'], idle: ['{name}在此，愿听君言。', '{name}伴君静坐。'] },
  };
  const llm = { async *stream(options) {
    requests.push(options);
    yield { type: 'text-delta', index: 0, text: JSON.stringify(scriptedVoice) };
    yield { type: 'finish', reason: { kind: 'stop' } };
  } };
  const ctx = { llm, get(name) { return name === 'llm' ? llm : name === 'agentDefaultModel'
    ? { currentSelection: () => ({ provider: 'configured-example', model: 'selected-text-model', reasoningEffort: 'low' }) } : undefined; } };
  const personaGenerator = createDshPetPersonaGenerator(ctx);
  const { service } = await fixture(t, mockGenerator(), personaGenerator);
  const pet = await service.create({ ...input, personality: '一位文言书生，话语简洁，待人有礼' });
  await service.waitForIdle();
  const candidate = await service.status(pet.id);
  assert.equal(candidate.personaGenerated, true);
  assert.deepEqual(candidate.persona.voice, scriptedVoice.voice);
  await service.generate({ id: pet.id }); await service.waitForIdle();
  const manifest = JSON.parse((await service.asset(pet.id, 'pet.json')).body);
  assert.deepEqual(manifest.persona.voice, scriptedVoice.voice);
  assert.equal(requests.length, 1); assert.equal(requests[0].provider, 'configured-example');
  assert.match(requests[0].messages[0].content[0].text, /文言书生/);
  assert.equal(requests[0].tools, undefined, 'persona generator has no execution tools');
  assert.throws(() => validateGeneratedPersona({ ...scriptedVoice, voice: { ...scriptedVoice.voice,
    working: ['{name}已经提交成功。', '{name}正在处理。'] } }, '书生'), /互动表达/);
});

test('missing text model retains draft and never falls back to a generic generated persona', async t => {
  const missing = createDshPetPersonaGenerator({ get() { return undefined; } });
  const { service, generator } = await fixture(t, mockGenerator(), missing);
  const pet = await service.create(input);
  assert.equal(pet.status, 'draft'); assert.equal(pet.personaGenerated, false); assert.equal(generator.calls.length, 0);
  assert.equal((await service.list()).available, false);
});

test('text model failure preserves the original description and starts no image generation', async t => {
  const llm = { async *stream() { yield { type: 'finish', reason: { kind: 'error', error: { message: 'fictional private diagnostic' } } }; } };
  const persona = createDshPetPersonaGenerator({ llm, get(name) { return name === 'llm' ? llm : name === 'agentDefaultModel'
    ? { currentSelection: () => ({ provider: 'mock', model: 'failing-persona' }) } : undefined; } });
  const { service, generator } = await fixture(t, mockGenerator(), persona);
  const pet = await service.create({ ...input, personality: '冷幽默机器人，说话极短' }); await service.waitForIdle();
  const failed = await service.status(pet.id);
  assert.equal(failed.status, 'error'); assert.equal(failed.personaGenerated, false);
  assert.equal(failed.personality, '冷幽默机器人，说话极短'); assert.equal(generator.calls.length, 0);
  assert.doesNotMatch(failed.error, /fictional private/);
});

test('retrying a failed image with unchanged inputs preserves the generated freeform voice without another text call', async t => {
  const requests = [];
  const voice = { working: ['{name}尚在细察，君且稍候。', '{name}正理此事，容我片刻。'],
    idle: ['{name}在此，愿听君言。', '{name}伴君静坐。'] };
  const personaGenerator = { ...mockPersonaGenerator(), async generate(args) {
    requests.push(args); return { temperament: 'calm', description: args.description, voice };
  } };
  const generator = mockGenerator(), generate = generator.generate;
  let fail = true;
  generator.generate = async args => { if (fail) throw new Error('mock image outage'); return generate(args); };
  const { service } = await fixture(t, generator, personaGenerator);
  const data = { ...input, personality: '文言书生，简洁有礼' };
  const pet = await service.create(data); await service.waitForIdle();
  assert.equal((await service.status(pet.id)).status, 'error');
  assert.equal(requests.length, 1);
  fail = false;
  await service.retry({ id: pet.id, ...data }); await service.waitForIdle();
  const retried = await service.status(pet.id);
  assert.equal(retried.status, 'reference_ready'); assert.equal(retried.personaGenerated, true);
  assert.deepEqual(retried.persona.voice, voice); assert.equal(requests.length, 1);
});

test('appearance or personality edits invalidate generated persona and old reference groups before regeneration', async t => {
  const requests = [];
  const personaGenerator = { ...mockPersonaGenerator(), async generate(args) {
    requests.push({ appearance: args.appearance, description: args.description });
    return { temperament: 'calm', voice: {
      working: [`{name}正在细察第${requests.length}种角色。`, `{name}正静心处理第${requests.length}种角色。`],
      idle: [`{name}以第${requests.length}种角色陪伴。`, `{name}用第${requests.length}种角色向你问好。`],
    } };
  } };
  const generator = mockGenerator(), originalGenerate = generator.generate;
  const { service } = await fixture(t, generator, personaGenerator);
  const pet = await service.create(input); await service.waitForIdle();
  await service.generate({ id: pet.id, group: 'idle-touch' }); await service.waitForIdle();
  const originalImage = (await service.asset(pet.id, 'reference.png')).body;
  generator.generate = async () => { throw new Error('mock image outage'); };
  await service.retry({ id: pet.id, description: '一只戴眼镜的小狗', personality: input.personality }); await service.waitForIdle();
  const failed = await service.status(pet.id);
  assert.equal(requests.length, 2); assert.equal(requests[1].appearance, '一只戴眼镜的小狗');
  assert.equal(failed.referenceCurrent, false); assert.equal(failed.completedGroups, 1);
  assert.deepEqual((await service.asset(pet.id, 'reference.png')).body, originalImage);
  await assert.rejects(service.generate({ id: pet.id }), error => error.code === 'REFERENCE_REQUIRED');
  generator.generate = originalGenerate;
  await service.retry({ id: pet.id, description: '一只戴眼镜的小狗', personality: input.personality }); await service.waitForIdle();
  assert.equal(requests.length, 2, 'the new dog persona survives image retries');
  assert.equal((await service.status(pet.id)).completedGroups, 0, 'new reference clears the old cat action groups');
  await service.retry({ id: pet.id, personality: '冷幽默机器人，话语简短' }); await service.waitForIdle();
  const changed = await service.status(pet.id);
  assert.equal(requests.length, 3); assert.equal(requests[2].description, '冷幽默机器人，话语简短');
  assert.equal(changed.personaGenerated, true); assert.match(changed.persona.voice.working[0], /第3种/);
});

test('a published manifest makes the candidate immutable even after a crash before draft-ready save', async t => {
  const { service, home } = await fixture(t);
  const pet = await service.create(input); await service.waitForIdle();
  await service.generate({ id: pet.id }); await service.waitForIdle();
  const file = join(home, 'pets', pet.id, 'draft.json');
  const draft = JSON.parse(await readFile(file, 'utf8')); draft.status = 'generating_actions';
  await writeFile(file, JSON.stringify(draft));
  await assert.rejects(service.retry({ id: pet.id }), error => error.code === 'ALREADY_READY');
  assert.equal((await service.status(pet.id)).status, 'ready');
});

test('image bounds are rejected from IHDR before decoding an oversized image', () => {
  const bytes = png(); bytes.writeUInt32BE(8192, 16); bytes.writeUInt32BE(8192, 20);
  assert.throws(() => validatePetPng(bytes), /尺寸超限/, 'size rejection precedes even CRC/DEFLATE decode');
});

test('OpenAI API-key record is reused only for an official route; OAuth and custom-host keys are refused', async () => {
  let record = { kind: 'api-key', key: 'fictional-record-secret' }, endpoint = 'https://api.openai.com/v1';
  const services = {
    credentials: { async resolve() { return undefined; }, async readRecord(key) { assert.equal(key, 'llm-pi-ai/openai'); return record; } },
    llm: { listConfigurableProviders: () => [{ provider: 'openai', defaultBaseURL: 'https://api.openai.com/v1', settingsNs: 'pi-ai', settingsPath: ['providers', 'openai'] }] },
    settings: { describe: () => [{ ns: 'pi-ai', value: { providers: { openai: { baseURL: endpoint } } } }] },
  };
  const ctx = { get: name => services[name] };
  assert.equal(await resolvePetImageKey(ctx, {}), 'fictional-record-secret');
  record = { kind: 'grant', payload: { access_token: 'fictional-oauth-secret' } };
  assert.equal(await resolvePetImageKey(ctx, {}), undefined);
  endpoint = 'https://custom.example/v1'; record = { kind: 'api-key', key: 'fictional-private-host-secret' };
  assert.equal(await resolvePetImageKey(ctx, {}), undefined);
});
