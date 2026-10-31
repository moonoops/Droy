import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, readFile, writeFile, mkdir, readdir, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createPetCreationService } from '../pet-generation.mjs';
import { builtInPetFile, isBuiltInPet } from '../built-in-pets.mjs';
import { validatePetPng, validateBrandLogoPng } from '../pet-generation-assets.mjs';

const builtInRoot = new URL('../../../app/src/main/assets/pet/', import.meta.url);
const ids = ['default', '8dd37d0d-b6ed-47c2-9952-8f3be20fa6d0', 'cb5c4126-8c3b-41a5-a1bd-3d3b95cdeee2'];
const unavailable = { async availability() { return { available: false, model: 'gpt-image-2.5-flare' }; },
  async generate() { assert.fail('Built-in artwork must never request model generation'); } };

test('built-in catalog and complete pixel artwork work without home, models or generated drafts', async () => {
  let language = 'zh';
  const service = createPetCreationService({ builtInRoot, generator: unavailable, getLocale: () => language });
  const chinese = await service.list();
  assert.equal(chinese.available, false);
  assert.deepEqual(chinese.pets, []);
  assert.deepEqual(chinese.builtIns.map(pet => pet.id), ids);
  assert.deepEqual(chinese.builtIns.map(pet => pet.name), ['黑猫', '土拨鼠', '羊驼']);
  language = 'en';
  assert.deepEqual((await service.list()).builtIns.map(pet => pet.name), ['Black cat', 'Marmot', 'Alpaca']);
  for (const pet of chinese.builtIns) {
    assert.equal((await service.status(pet.id)).status, 'ready');
    for (const field of ['nameLocales', 'descriptionLocales']) {
      assert.ok(pet[field].zh.trim()); assert.ok(pet[field].en.trim());
    }
    const document = JSON.parse((await service.asset(pet.id, 'pet.json')).body);
    assert.equal(document.id, pet.id); assert.equal(document.rendering.style, 'pixel');
    let totalPixels = 0;
    for (const atlas of Object.values(document.atlases)) {
      const response = await service.asset(pet.id, atlas.file), image = validatePetPng(response.body);
      assert.equal(response.type, 'image/png');
      assert.equal(image.width, atlas.width); assert.equal(image.height, atlas.height);
      totalPixels += image.width * image.height;
      for (const { sourceRect: [x, y, width, height] } of atlas.frames) {
        assert.ok(x >= 0 && y >= 0 && width > 0 && height > 0 && x + width <= image.width && y + height <= image.height);
      }
    }
    assert.ok(totalPixels <= 32_000_000);
    const logo = await service.brandLogo(pet.id);
    assert.deepEqual(logo.body, (await service.asset(pet.id, 'brand-logo.png')).body);
    validateBrandLogoPng(logo.body);
    validatePetPng((await service.asset(pet.id, 'reference.png')).body);
    for (const state of Object.values(document.states)) {
      assert.ok(state.labelLocales.zh && state.labelLocales.en);
      assert.equal(state.frames.length, state.durationsMs.length);
    }
  }
  const alpaca = JSON.parse((await service.asset(ids[2], 'pet.json')).body);
  assert.deepEqual(alpaca.effects.spitScreen.frames, [0, 1, 2, 3, 4, 5]);
  assert.equal(alpaca.effects.spitScreen.atlas, 'screenEffect');
  assert.ok(alpaca.states.spitForward && alpaca.states.spitScreen && alpaca.states.neckStretch);
});

test('reserved built-ins are immutable and do not replace or enumerate generated-pet files', async t => {
  const home = await mkdtemp(join(tmpdir(), 'droy-built-in-pets-'));
  t.after(() => rm(home, { recursive: true, force: true }));
  const folder = join(home, 'pets', ids[1]);
  await mkdir(folder, { recursive: true });
  const original = 'fictional local draft retained unchanged\n';
  await writeFile(join(folder, 'draft.json'), original);
  const service = createPetCreationService({ home, builtInRoot, generator: unavailable });
  const custom = await service.create({ name: '测试形象', description: '虚构灰色兔子' });
  assert.deepEqual((await service.list()).pets.map(pet => pet.id), [custom.id]);
  for (const id of ids) {
    await assert.rejects(service.generate({ id }), error => error.code === 'BUILT_IN_READ_ONLY');
    await assert.rejects(service.retry({ id, description: 'replacement' }), error => error.code === 'BUILT_IN_READ_ONLY');
  }
  assert.equal(await readFile(join(folder, 'draft.json'), 'utf8'), original);
  assert.equal((await service.status(custom.id)).status, 'draft');
  assert.deepEqual((await readdir(folder)), ['draft.json']);
});

test('built-in asset requests remain inside their fixed allowlist and return current pixel logos', async () => {
  const service = createPetCreationService({ builtInRoot, generator: unavailable });
  for (const id of ids) for (const file of ['../pet.json', 'builtins.json', 'marmot/reference.png', 'reference.png?secret=x', '/private.png', 'draft.json'])
    await assert.rejects(service.asset(id, file), error => error.code === 'NOT_FOUND');
  for (const id of [null, undefined, '', '../default', 'default/..']) assert.equal(isBuiltInPet(id), false);
  assert.throws(() => builtInPetFile(builtInRoot, '../default', 'reference.png'));
  assert.throws(() => builtInPetFile(builtInRoot, 'default', '../pet.json'));
  const oldLogo = await readFile(new URL('../assets/brand-logo.png', import.meta.url));
  assert.notDeepEqual((await service.brandLogo('default')).body, oldLogo);
});
