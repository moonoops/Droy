import { randomUUID } from 'node:crypto';
import { constants } from 'node:fs';
import { mkdir, readdir, lstat, open, rename, unlink } from 'node:fs/promises';
import { join } from 'node:path';
import { requireMobileSession } from './auth.mjs';
import { resolveMobileLocale } from './locale.mjs';
import { translateBackendText, localizePetResponse } from './backend-i18n.mjs';
import { createPetPersona, petDescription, createDshPetPersonaGenerator, validateGeneratedPersona, PetPersonaError } from './pet-persona.mjs';
import { PET_ACTION_GROUPS, actionPrompt, referencePrompt, brandLogoPrompt, validatePetPng, validateBrandLogoPng, inspectActionAtlas, createPetManifest } from './pet-generation-assets.mjs';

const ID = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const IMAGE_MODELS = new Set(['gpt-image-2.5-flare', 'gpt-image-2.5-sunburst', 'gpt-image-1.5']);
const DEFAULT_MODEL = 'gpt-image-2.5-flare';
const MAX_RESPONSE = 28 * 1024 * 1024;
const IMAGE_FILES = new Set(['reference.png', 'brand-logo.png', ...PET_ACTION_GROUPS.map(group => `${group.id}.png`)]);
const modelName = value => {
  if (!IMAGE_MODELS.has(value)) throw new PetCreationError('MODEL_UNSUPPORTED', '请选择支持透明图片的形象生成模型', 400);
  return value;
};

export class PetCreationError extends Error {
  constructor(code, message, status = 400) { super(message); this.code = code; this.status = status; }
}

async function boundedBody(stream, max) {
  const parts = []; let bytes = 0;
  for await (const chunk of stream) {
    bytes += chunk.length;
    if (bytes > max) throw new PetCreationError('TOO_LARGE', '生成数据过大，请重试', 413);
    parts.push(Buffer.from(chunk));
  }
  return Buffer.concat(parts);
}

/** Credentials are resolved through the existing secret service, never copied to pet files or the client. */
export function createOpenAiPetGenerator({ resolveKey, fetchImpl = fetch, env = process.env } = {}) {
  const model = env.DROY_PET_IMAGE_MODEL || DEFAULT_MODEL;
  async function key() {
    const value = resolveKey ? await resolveKey() : env.DROY_PET_IMAGE_API_KEY ?? env.OPENAI_API_KEY;
    if (value === undefined || value === '') return undefined;
    if (typeof value !== 'string' || !/^[\x21-\x7e]+$/.test(value.trim()))
      throw new PetCreationError('CREDENTIAL_INVALID', '图片服务凭据格式无效，请在模型设置中检查', 503);
    return value.trim();
  }
  return {
    async availability() {
      const configured = Boolean(await key());
      const supported = IMAGE_MODELS.has(model);
      return {
        available: configured && supported, credentialConfigured: configured,
        provider: 'OpenAI', model, supportedModels: [...IMAGE_MODELS],
        configurationMessage: !supported ? '当前图片模型不支持形象透明素材，请检查图片生成模型配置。'
          : configured ? '使用 OpenAI 图片服务，按服务计费。'
            : '尚未配置 OpenAI 图片服务凭据。可先保存描述；请在模型设置中配置 OpenAI 的 OPENAI_API_KEY 后继续。',
      };
    },
    async generate({ prompt, reference, signal, model: requestedModel = model, size = reference ? '1024x1536' : '1024x1024' }) {
      modelName(requestedModel);
      if (!['1024x1024', '1024x1536'].includes(size)) throw new PetCreationError('INVALID_IMAGE_SIZE', '图片尺寸无效');
      const apiKey = await key();
      if (!apiKey) throw new PetCreationError('NOT_CONFIGURED', '尚未配置 OpenAI 图片服务，描述已保存', 503);
      const timeout = AbortSignal.timeout(240000);
      const combined = signal ? AbortSignal.any([signal, timeout]) : timeout;
      const headers = { Authorization: `Bearer ${apiKey}` };
      let body, endpoint;
      if (reference) {
        const form = new FormData();
        form.set('model', requestedModel); form.set('prompt', prompt); form.set('n', '1');
        form.set('background', 'transparent'); form.set('output_format', 'png');
        form.set('quality', 'high'); form.set('size', size);
        form.append('image[]', new Blob([reference], { type: 'image/png' }), 'reference.png');
        body = form; endpoint = 'edits';
      } else {
        headers['Content-Type'] = 'application/json';
        body = JSON.stringify({ model: requestedModel, prompt, n: 1, background: 'transparent', output_format: 'png', quality: 'high', size });
        endpoint = 'generations';
      }
      let response;
      try {
        // No configurable endpoint or redirect: an OpenAI credential cannot be forwarded to an arbitrary host.
        response = await fetchImpl(`https://api.openai.com/v1/images/${endpoint}`, { method: 'POST', headers, body, redirect: 'error', signal: combined });
      } catch {
        if (combined.aborted) throw new PetCreationError('GENERATION_INTERRUPTED', '生成已中断或超时，已完成的素材保留，可重试', 503);
        throw new PetCreationError('SERVICE_UNREACHABLE', '无法连接图片服务，已完成的素材保留，请稍后重试', 503);
      }
      if (!response.ok) {
        await response.body?.cancel();
        const reason = response.status === 401 || response.status === 403 ? '图片服务未授权，请检查 API Key 与模型权限'
          : response.status === 429 ? '图片服务额度不足或请求过多，请检查额度后重试'
            : response.status >= 500 ? '图片服务暂时不可用，请稍后重试' : '图片服务未接受生成请求，请调整描述或检查模型配置';
        throw new PetCreationError('PROVIDER_REJECTED', reason, 502);
      }
      let result;
      try { result = JSON.parse((await boundedBody(response.body, MAX_RESPONSE)).toString('utf8')); }
      catch (error) {
        if (error instanceof PetCreationError) throw error;
        throw new PetCreationError('INVALID_IMAGE_RESPONSE', '图片服务返回无效数据，请重试', 502);
      }
      const encoded = result?.data?.[0]?.b64_json;
      if (typeof encoded !== 'string' || encoded.length > MAX_RESPONSE || encoded.length % 4 !== 0 || !/^[A-Za-z0-9+/]*={0,2}$/.test(encoded))
        throw new PetCreationError('INVALID_IMAGE_RESPONSE', '图片服务未返回有效 PNG 素材，请重试', 502);
      const bytes = Buffer.from(encoded, 'base64');
      try { validatePetPng(bytes); }
      catch (error) { throw new PetCreationError('INVALID_IMAGE', error.message, 502); }
      return bytes;
    },
  };
}

async function readBounded(file, max) {
  const handle = await open(file, constants.O_RDONLY | constants.O_NOFOLLOW);
  try {
    const stat = await handle.stat();
    if (!stat.isFile() || stat.size > max) throw new PetCreationError('INVALID_FILE', '形象文件无效或过大');
    return await handle.readFile();
  } finally { await handle.close(); }
}

async function atomicWrite(file, value) {
  const temporary = `${file}.${randomUUID()}.tmp`;
  const handle = await open(temporary, 'wx', 0o600);
  try { await handle.writeFile(value); await handle.sync(); }
  finally { await handle.close(); }
  try { await rename(temporary, file); }
  catch (error) { await unlink(temporary).catch(() => {}); throw error; }
}

const imageUrl = (id, file) => `/droy/pets/asset?id=${encodeURIComponent(id)}&file=${encodeURIComponent(file)}`;
const writeJson = (file, value) => atomicWrite(file, `${JSON.stringify(value, null, 2)}\n`);
const personaSourceKey = pet => JSON.stringify({ appearance: pet.description, personality: pet.personality });

export function createPetCreationService({ home, generator, personaGenerator, reportError = () => {},
  getLocale = () => process.env.DROY_LOCALE,
  defaultLogoFile = new URL('./assets/brand-logo.png', import.meta.url) }) {
  const root = home ? join(home, 'pets') : null, jobs = new Map();
  let disposed = false;
  let mutation = Promise.resolve();

  async function availability() {
    const image = await generator.availability();
    const text = personaGenerator ? await personaGenerator.availability()
      : { available: false, textProvider: null, textModel: null, configurationMessage: '请先配置用于理解伙伴性格的文本模型。' };
    return { ...image, imageAvailable: image.available, textProvider: text.textProvider, textModel: text.textModel,
      available: image.available && text.available,
      configurationMessage: [!text.available && text.configurationMessage, !image.available && image.configurationMessage].filter(Boolean).join(' ')
        || '性格使用当前文本模型，素材使用 OpenAI 图片服务，按各服务计费。' };
  }

  async function directory(id) {
    if (!root) throw new PetCreationError('HOME_REQUIRED', '形象方案保存位置尚未就绪，请重新打开应用', 503);
    if (id !== undefined && !ID.test(id)) throw new PetCreationError('INVALID_ID', '形象方案编号无效');
    await mkdir(root, { recursive: true, mode: 0o700 });
    if (!(await lstat(root)).isDirectory()) throw new PetCreationError('INVALID_DIRECTORY', '形象方案保存位置无效');
    if (id === undefined) return root;
    const path = join(root, id);
    const stat = await lstat(path).catch(error => {
      if (error.code === 'ENOENT') throw new PetCreationError('NOT_FOUND', '这份形象方案不存在', 404);
      throw error;
    });
    if (!stat.isDirectory()) throw new PetCreationError('INVALID_DIRECTORY', '形象方案保存位置无效');
    return path;
  }

  function view(pet) {
    return {
      id: pet.id, name: pet.name, description: pet.description, personality: pet.personality, persona: pet.persona,
      model: pet.model, status: pet.status, stage: pet.stage, error: pet.error || null,
      personaGenerated: pet.personaGenerated === true,
      referenceUrl: pet.referenceReady ? imageUrl(pet.id, 'reference.png') : null, referenceCurrent: pet.referenceCurrent !== false,
      logoUrl: pet.brandLogoReady && pet.referenceCurrent !== false ? imageUrl(pet.id, 'brand-logo.png') : null,
      manifestUrl: pet.status === 'ready' ? imageUrl(pet.id, 'pet.json') : null,
      completedGroups: Object.keys(pet.groups).length, totalGroups: PET_ACTION_GROUPS.length,
      groups: PET_ACTION_GROUPS.map(group => ({ id: group.id, label: group.label, complete: Boolean(pet.groups[group.id]) })),
      createdAt: pet.createdAt, updatedAt: pet.updatedAt,
    };
  }

  async function save(pet) {
    pet.updatedAt = Date.now();
    await writeJson(join(await directory(pet.id), 'draft.json'), pet);
  }

  async function load(id) {
    const path = await directory(id);
    let pet;
    try { pet = JSON.parse((await readBounded(join(path, 'draft.json'), 512 * 1024)).toString('utf8')); }
    catch (error) {
      if (error instanceof PetCreationError) throw error;
      throw new PetCreationError('INVALID_DRAFT', '形象方案草稿无法读取，请重新创建', 409);
    }
    if (pet.id !== id || !pet.groups || typeof pet.groups !== 'object' || !pet.persona || !IMAGE_MODELS.has(pet.model))
      throw new PetCreationError('INVALID_DRAFT', '形象方案草稿无效，请重新创建', 409);
    // Publishing the complete manifest is the commit point. Recover a crash between this and the draft status write.
    let published;
    try { published = JSON.parse((await readBounded(join(path, 'pet.json'), 512 * 1024)).toString('utf8')); }
    catch (error) { if (error.code !== 'ENOENT') throw new PetCreationError('INVALID_MANIFEST', '形象素材包无法读取，请创建新方案', 409); }
    if (published) {
      if (published.id !== id || published.formatVersion !== 2 || !published.states || !published.atlases)
        throw new PetCreationError('INVALID_MANIFEST', '形象素材包无效，请创建新方案', 409);
      if (published.branding?.logo === 'brand-logo.png') pet.brandLogoReady = true;
      if (pet.status !== 'ready') {
        pet.status = 'ready'; pet.stage = '动作已生成，请预览后使用'; pet.error = null;
        await save(pet);
      }
    }
    if (pet.status.startsWith('generating_') && !jobs.has(id)) {
      pet.status = 'error'; pet.error = '上次生成已中断，已完成的素材保留，可继续重试';
      pet.stage = pet.referenceReady ? '动作生成已中断' : '形象生成已中断';
      await save(pet);
    }
    return pet;
  }

  function assertIdle(id) {
    if (disposed) throw new PetCreationError('STOPPING', '引擎正在停止，请稍后重试', 503);
    if (jobs.size) throw new PetCreationError('GENERATION_BUSY', jobs.has(id) ? '这份形象方案正在生成，请稍等' : '已有形象方案正在生成，请完成后再开始', 409);
  }

  async function start(pet, mode, onlyGroup) {
    assertIdle(pet.id);
    const configured = await availability();
    if (!configured.available) {
      pet.status = pet.referenceReady && pet.referenceCurrent !== false ? 'reference_ready' : 'draft'; pet.stage = '等待配置图片服务'; pet.error = null;
      await save(pet); return view(pet);
    }
    const controller = new AbortController();
    jobs.set(pet.id, controller);
    const needsPersona = mode === 'reference' && (!pet.personaGenerated || pet.personaSource !== personaSourceKey(pet));
    pet.status = mode === 'reference' ? (needsPersona ? 'generating_persona' : 'generating_reference') : 'generating_actions';
    pet.stage = mode === 'reference' ? (needsPersona ? '正在理解性格与设计语气' : '正在生成形象') : '正在生成动作'; pet.error = null;
    try { await save(pet); }
    catch (error) { jobs.delete(pet.id); throw error; }
    const initial = view(pet);
    void (async () => {
      try {
        const path = await directory(pet.id);
        if (mode === 'reference') {
          if (needsPersona) {
            pet.persona = validateGeneratedPersona(await personaGenerator.generate({ description: pet.personality,
              appearance: pet.description, signal: controller.signal }), pet.personality);
            pet.personaGenerated = true; pet.personaSource = personaSourceKey(pet);
            pet.status = 'generating_reference'; pet.stage = '正在生成形象';
            await save(pet);
          }
          const prompt = referencePrompt(pet);
          const bytes = await generator.generate({ prompt, model: pet.model, signal: controller.signal });
          controller.signal.throwIfAborted();
          validatePetPng(bytes);
          await atomicWrite(join(path, 'reference.png'), bytes);
          pet.referenceReady = true; pet.referenceCurrent = true; pet.brandLogoReady = false; pet.groups = {}; pet.status = 'reference_ready'; pet.stage = '形象已生成，请确认后生成品牌标志与动作';
          await writeJson(join(path, 'generation-prompts.json'), { reference: prompt, groups: {} });
        } else {
          const reference = await readBounded(join(path, 'reference.png'), 20 * 1024 * 1024);
          validatePetPng(reference);
          if (!pet.brandLogoReady) {
            controller.signal.throwIfAborted();
            pet.stage = '正在生成品牌标志';
            await save(pet);
            const bytes = await generator.generate({ prompt: brandLogoPrompt(pet), reference, model: pet.model,
              signal: controller.signal, size: '1024x1024' });
            controller.signal.throwIfAborted();
            validateBrandLogoPng(bytes);
            await atomicWrite(join(path, 'brand-logo.png'), bytes);
            pet.brandLogoReady = true;
            await save(pet);
          }
          for (const group of PET_ACTION_GROUPS) {
            if (onlyGroup && group.id !== onlyGroup) continue;
            if (!onlyGroup && pet.groups[group.id]) continue;
            controller.signal.throwIfAborted();
            pet.stage = `正在生成${group.label}（${Object.keys(pet.groups).length}/${PET_ACTION_GROUPS.length}）`;
            await save(pet);
            const prompt = actionPrompt(pet, group);
            const bytes = await generator.generate({ prompt, reference, model: pet.model, signal: controller.signal });
            controller.signal.throwIfAborted();
            const parsed = inspectActionAtlas(bytes, group);
            await atomicWrite(join(path, `${group.id}.png`), bytes);
            pet.groups[group.id] = parsed;
            await save(pet);
          }
          if (PET_ACTION_GROUPS.every(group => pet.groups[group.id])) {
            const manifest = createPetManifest(pet, pet.groups);
            await writeJson(join(path, 'pet.json'), manifest);
            pet.status = 'ready'; pet.stage = '动作已生成，请预览后使用';
          } else { pet.status = 'reference_ready'; pet.stage = '这一组已生成，可继续补齐动作'; }
          await writeJson(join(path, 'generation-prompts.json'), {
            reference: referencePrompt(pet),
            branding: brandLogoPrompt(pet),
            groups: Object.fromEntries(PET_ACTION_GROUPS.filter(group => pet.groups[group.id]).map(group => [group.id, actionPrompt(pet, group)])),
          });
        }
        pet.error = null;
        await save(pet);
      } catch (error) {
        pet.status = 'error'; pet.stage = mode === 'reference' ? '形象未生成' : '动作未全部生成';
        pet.error = error instanceof PetCreationError || error instanceof PetPersonaError ? error.message
          : controller.signal.aborted ? '生成已中断，已完成的素材保留，可继续重试'
            : error.message?.startsWith('图片') || /空帧|越界|动作没有变化/.test(error.message ?? '') ? error.message
              : '素材生成或保存失败，已完成的素材保留，请重试';
        try { await save(pet); } catch { reportError('DROY_PET_DRAFT_SAVE_FAILED'); }
        reportError('DROY_PET_GENERATION_FAILED');
      } finally { jobs.delete(pet.id); }
    })();
    return initial;
  }

  const service = {
    async list() {
      const folder = await directory();
      const names = (await readdir(folder)).filter(name => ID.test(name));
      const pets = [];
      for (const id of names) {
        try { pets.push(view(await load(id))); }
        catch { reportError('DROY_PET_DRAFT_READ_FAILED'); }
      }
      return { ...await availability(), pets: pets.sort((a, b) => b.updatedAt - a.updatedAt) };
    },
    async create(input) {
      assertIdle();
      const description = petDescription(input.description, '外观描述', 1000);
      const personality = petDescription(input.personality ?? '', '性格描述', 1000, true);
      const name = petDescription(input.name || translateBackendText('我的形象方案', getLocale()), '形象方案名称', 40);
      const availability = await generator.availability();
      const model = modelName(input.model || availability.model || DEFAULT_MODEL);
      const folder = await directory();
      if ((await readdir(folder)).filter(name => ID.test(name)).length >= 100)
        throw new PetCreationError('DRAFT_LIMIT', '已保存的形象方案数量达到上限', 409);
      const id = randomUUID();
      await mkdir(join(folder, id), { mode: 0o700 });
      const pet = { id, name, description, personality, persona: createPetPersona(personality, getLocale()), model, groups: {},
        personaGenerated: false, status: 'draft', stage: '描述已保存', referenceReady: false, brandLogoReady: false,
        createdAt: Date.now(), updatedAt: Date.now(), error: null };
      await save(pet);
      return start(pet, 'reference');
    },
    async status(id) { return view(await load(id)); },
    async generate({ id, group }) {
      assertIdle(id);
      const pet = await load(id);
      if (!pet.referenceReady || pet.referenceCurrent === false) throw new PetCreationError('REFERENCE_REQUIRED', '请先按当前描述生成伙伴形象', 409);
      if (pet.status === 'ready') throw new PetCreationError('ALREADY_READY', '这份形象方案已完成；修改时请创建新方案，以保留当前形象', 409);
      if (group !== undefined && !PET_ACTION_GROUPS.some(item => item.id === group)) throw new PetCreationError('INVALID_GROUP', '动作组无效');
      return start(pet, 'actions', group);
    },
    async retry(input) {
      assertIdle(input.id);
      const pet = await load(input.id);
      if (pet.status === 'ready') throw new PetCreationError('ALREADY_READY', '这份形象方案已完成；修改时请创建新方案，以保留当前形象', 409);
      const previousSource = personaSourceKey(pet);
      if (input.name !== undefined) pet.name = petDescription(input.name, '形象方案名称', 40);
      if (input.description !== undefined) pet.description = petDescription(input.description, '外观描述', 1000);
      if (input.personality !== undefined) pet.personality = petDescription(input.personality, '性格描述', 1000, true);
      if (personaSourceKey(pet) !== previousSource) {
        pet.persona = createPetPersona(pet.personality, getLocale());
        pet.personaGenerated = false;
        delete pet.personaSource;
      }
      // Keep the previous image if the new request fails, but never combine its actions with the changed description.
      pet.referenceCurrent = false;
      pet.brandLogoReady = false;
      await unlink(join(await directory(pet.id), 'pet.json')).catch(error => { if (error.code !== 'ENOENT') throw error; });
      return start(pet, 'reference');
    },
    async asset(id, file) {
      const pet = await load(id);
      if (file === 'pet.json') {
        if (pet.status !== 'ready') throw new PetCreationError('NOT_READY', '形象动作尚未完成', 409);
      } else if (!IMAGE_FILES.has(file) || (file === 'reference.png' && !pet.referenceReady)
        || (file === 'brand-logo.png' && (!pet.brandLogoReady || pet.referenceCurrent === false))) {
        throw new PetCreationError('NOT_FOUND', '素材不存在', 404);
      }
      return { body: await readBounded(join(await directory(id), file), file === 'pet.json' ? 512 * 1024 : 20 * 1024 * 1024),
        type: file === 'pet.json' ? 'application/json; charset=utf-8' : 'image/png' };
    },
    async brandLogo(id = 'default') {
      if (id !== 'default' && !ID.test(id)) throw new PetCreationError('INVALID_ID', '形象方案编号无效');
      let bytes;
      if (id !== 'default') {
        const pet = await load(id);
        if (pet.brandLogoReady && pet.referenceCurrent !== false) {
          bytes = await readBounded(join(await directory(id), 'brand-logo.png'), 20 * 1024 * 1024);
        }
      }
      // Earlier complete packages have no brand logo; keep their animation usable
      // and display the built-in Droy mark until a new package is selected.
      bytes ??= await readBounded(defaultLogoFile, 20 * 1024 * 1024);
      validateBrandLogoPng(bytes);
      return { body: bytes, type: 'image/png' };
    },
    dispose() { disposed = true; for (const controller of jobs.values()) controller.abort(); },
    async waitForIdle() { while (jobs.size) await new Promise(resolve => setTimeout(resolve, 10)); },
  };
  for (const name of ['create', 'generate', 'retry']) {
    const run = service[name];
    service[name] = input => {
      const result = mutation.then(() => run(input));
      mutation = result.catch(() => {});
      return result;
    };
  }
  return service;
}

export async function resolvePetImageKey(ctx, env = process.env) {
  const credentials = ctx.get('credentials');
  const resolve = async ref => (await credentials?.resolve(ref))?.value ?? env[ref];
  const dedicated = await resolve('DROY_PET_IMAGE_API_KEY');
  if (dedicated) return dedicated;
  const route = ctx.get('llm')?.listConfigurableProviders?.().find(item => item.provider === 'openai');
  if (route) {
    const section = ctx.get('settings')?.describe({ redactSecrets: true }).find(item => item.ns === route.settingsNs);
    let profile = section?.value;
    for (const segment of route.settingsPath) profile = profile?.[segment];
    let endpoint = profile?.baseURL ?? route.defaultBaseURL;
    for (const ref of route.baseURLEnv ?? []) if (env[ref]) { endpoint = env[ref]; break; }
    let official = false;
    try {
      const url = new URL(endpoint);
      official = url.origin === 'https://api.openai.com' && /^\/(?:v1\/?)?$/.test(url.pathname)
        && !url.username && !url.password && !url.search && !url.hash;
    } catch { /* An unknown route must not donate credentials to the image service. */ }
    if (!official) return undefined;
    if (typeof profile?.apiKeyEnv === 'string' && /^[A-Za-z_][A-Za-z0-9_]*$/.test(profile.apiKeyEnv)) {
      const configured = await resolve(profile.apiKeyEnv);
      if (configured) return configured;
    }
    const record = await credentials?.readRecord?.('llm-pi-ai/openai');
    if (record?.kind === 'api-key') {
      if (record.key) return record.key;
      if (record.env?.OPENAI_API_KEY) return record.env.OPENAI_API_KEY;
    }
    // OAuth grants belong to the chat adapter; they are never sent to Images.
  }
  return resolve('OPENAI_API_KEY');
}

export function installPetGeneration(ctx, options = {}) {
  const generator = options.generator ?? createOpenAiPetGenerator({ resolveKey: () => resolvePetImageKey(ctx) });
  const personaGenerator = options.personaGenerator ?? createDshPetPersonaGenerator(ctx);
  const service = createPetCreationService({ home: options.home ?? process.env.DSH_HOME, generator, personaGenerator,
    getLocale: () => resolveMobileLocale(ctx),
    defaultLogoFile: options.defaultLogoFile,
    reportError: code => ctx.logger.warn(code) });
  ctx.effect(() => () => service.dispose());
  const routes = {
    '/droy/pets': { method: 'GET', run: () => service.list() },
    '/droy/pets/status': { method: 'GET', run: (_, url) => service.status(url.searchParams.get('id')).then(pet => ({ pet })) },
    '/droy/pets/create': { method: 'POST', status: 202, run: data => service.create(data).then(pet => ({ pet })) },
    '/droy/pets/generate': { method: 'POST', status: 202, run: data => service.generate(data).then(pet => ({ pet })) },
    '/droy/pets/retry': { method: 'POST', status: 202, run: data => service.retry(data).then(pet => ({ pet })) },
    '/droy/pets/asset': { method: 'GET', asset: true, run: (_, url) => service.asset(url.searchParams.get('id'), url.searchParams.get('file')) },
    '/droy/branding/logo': { method: 'GET', asset: true, run: (_, url) => service.brandLogo(url.searchParams.get('petId') ?? 'default') },
  };
  for (const [path, route] of Object.entries(routes)) ctx.webServer.register({ kind: 'exact', path, async handler(req, res) {
    if (!requireMobileSession(req, res)) return;
    if (req.method !== route.method) { res.writeHead(405, { Allow: route.method }); res.end(); return; }
    try {
      let data = {};
      if (route.method === 'POST') {
        if (!(req.headers['content-type'] ?? '').startsWith('application/json')) throw new PetCreationError('INVALID_CONTENT_TYPE', '请求格式无效');
        try { data = JSON.parse((await boundedBody(req, 12000)).toString('utf8')); }
        catch (error) {
          if (error instanceof PetCreationError) throw error;
          throw new PetCreationError('INVALID_JSON', '请求内容无效');
        }
        if (!data || typeof data !== 'object' || Array.isArray(data)) throw new PetCreationError('INVALID_INPUT', '请求内容无效');
      }
      const result = await route.run(data, new URL(req.url, 'http://127.0.0.1'));
      res.writeHead(route.status ?? 200, { 'Content-Type': route.asset ? result.type : 'application/json; charset=utf-8',
        'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff' });
      res.end(route.asset ? result.body : JSON.stringify(localizePetResponse(result, resolveMobileLocale(ctx))));
    } catch (error) {
      const known = error instanceof PetCreationError;
      if (!known) ctx.logger.warn('DROY_PET_REQUEST_FAILED');
      res.writeHead(known ? error.status : 400, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' });
      res.end(JSON.stringify({ ok: false, error: { code: known ? error.code : 'REQUEST_FAILED',
        message: translateBackendText(known ? error.message : /描述|名称|性格/.test(error.message ?? '') ? error.message : '形象操作未完成，请重试', resolveMobileLocale(ctx)) } }));
    }
  } });
  return service;
}
