import { AttachmentError, requestImageDimensions } from '@deepseek-ai/dsh-attachment';
import { LocalAttachmentStore } from '@deepseek-ai/dsh-attachment-local';
import { createHash, randomUUID } from 'node:crypto';
import { createReadStream } from 'node:fs';
import { mkdir, open, readFile, rm } from 'node:fs/promises';
import { dirname, join, resolve } from 'node:path';
import { decodePng, encodePng, resizePng } from './png.mjs';
import { normalizeAndroidImage } from './attachment-codec.mjs';
import { publishExclusive } from './publish.mjs';

const hash = bytes => createHash('sha256').update(bytes).digest('hex');
const digestPattern = /^sha256:([a-f0-9]{64})$/;
const legacyPattern = /^[a-f0-9]{64}$/;

function fileName(value = 'file') {
  if (typeof value !== 'string') throw new AttachmentError('Invalid attachment name.', 'INVALID_ATTACHMENT_REF');
  let name = value.slice(Math.max(value.lastIndexOf('/'), value.lastIndexOf('\\')) + 1)
    .replace(/[\u0000-\u001f\u007f]/g, '').replace(/[<>:"|?*]/g, '_').trim().replace(/[. ]+$/u, '');
  if (/^(?:con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\.|$)/iu.test(name)) name = `_${name}`;
  let prefix = '';
  for (const character of name) {
    if (Buffer.byteLength(prefix + character) > 255) break;
    prefix += character;
  }
  return prefix.replace(/[. ]+$/u, '') || 'file';
}

function referenceDigest(ref) {
  const digest = digestPattern.exec(ref?.attachmentId)?.[1];
  if (!digest || !Number.isSafeInteger(ref.bytes) || ref.bytes < 0)
    throw new AttachmentError('Invalid attachment reference.', 'INVALID_ATTACHMENT_REF');
  return digest;
}

async function ensureDirectory(path, boundary) {
  await mkdir(path, { recursive: true, mode: 0o700 });
  // New parent entries must survive a crash before a session can cite the object.
  for (let current = resolve(path); current !== resolve(boundary); current = dirname(current)) {
    const parent = dirname(current);
    if (parent === current) throw new Error('Attachment directory is outside DSH_HOME');
    const fd = await open(parent, 'r');
    try { await fd.sync(); } finally { await fd.close(); }
  }
}

function plainPng(data) {
  for (let offset = 8; offset + 12 <= data.length;) {
    const length = data.readUInt32BE(offset), type = data.toString('ascii', offset + 4, offset + 8);
    if (!['IHDR', 'IDAT', 'IEND'].includes(type)) return false;
    offset += length + 12;
  }
  return true;
}

function boundedPng(image, target, signal, encoded) {
  signal?.throwIfAborted();
  const ratio = Math.min(1, target.width / image.width, target.height / image.height);
  const resized = resizePng(image, image.width * ratio, image.height * ratio);
  // Only reuse bytes after full decoding and limits validation, never by PNG headers alone.
  if (encoded && resized === image && encoded.length <= target.maxBytes) return { image, data: encoded };
  image = resized;
  let data = encodePng(image);
  while (data.length > target.maxBytes && Math.max(image.width, image.height) > 1) {
    signal?.throwIfAborted();
    image = resizePng(image, image.width * 0.75, image.height * 0.75);
    data = encodePng(image);
  }
  if (data.length > target.maxBytes) throw new AttachmentError('Image cannot fit within the configured byte limit.', 'IMAGE_TOO_LARGE');
  return { image, data };
}

async function readImageBytes(store, ref, signal) {
  signal?.throwIfAborted();
  const path = store.imageHostPath(ref);
  let data;
  try { data = await readFile(path, { signal }); }
  catch (error) {
    signal?.throwIfAborted();
    throw new AttachmentError(error.code === 'ENOENT' ? 'Image attachment is missing.' : 'Unable to read image attachment.',
      error.code === 'ENOENT' ? 'ATTACHMENT_NOT_FOUND' : 'ATTACHMENT_READ_FAILED', { cause: error });
  }
  const digest = legacyPattern.test(ref.attachmentId) ? ref.attachmentId : referenceDigest(ref);
  if (hash(data) !== digest || data.length !== ref.bytes)
    throw new AttachmentError('截图完整性校验失败', 'ATTACHMENT_CORRUPT');
  return data;
}

async function readDecodedImage(store, ref, signal) {
  const data = await readImageBytes(store, ref, signal);
  let image;
  try { image = decodePng(data); }
  catch (error) { throw new AttachmentError('Stored image is invalid.', 'ATTACHMENT_CORRUPT', { cause: error }); }
  if (image.width !== ref.width || image.height !== ref.height || ref.mediaType !== 'image/png')
    throw new AttachmentError('截图元数据不一致', 'ATTACHMENT_CORRUPT');
  signal?.throwIfAborted();
  return { ref, data, image };
}

// Retain upstream policy/configuration, replacing only native image processing
// and hard-link publication, which are unavailable in Android app storage.
export default class MobileAttachments extends LocalAttachmentStore {
  constructor(ctx, config = {}) {
    super(ctx, { ...config, dshHome: config.dshHome ?? process.env.DSH_HOME });
    this.home = dirname(dirname(this.root));
    this.legacyRoot = join(this.home, 'attachments', 'droy-png-v1');
  }

  async prepareImage(input) {
    this.validateImageBatch([input]);
    if (input.data.byteLength > this.imageLimits.maxImageBytes)
      throw new AttachmentError('Image exceeds the configured byte limit.', 'IMAGE_TOO_LARGE');
    if (input.data.byteLength === 0) throw new AttachmentError('Image is empty.', 'INVALID_IMAGE');
    const bytes = Buffer.from(input.data);
    let source;
    if (input.mediaType === 'image/png' && plainPng(bytes)) {
      try { source = decodePng(bytes); }
      catch { /* Other valid PNG encodings are decoded by Android below. */ }
    }
    let normalized, originalWidth, originalHeight;
    if (source) {
      if (source.width * source.height > this.imageLimits.maxImagePixels)
        throw new AttachmentError('Image exceeds the configured pixel limit.', 'IMAGE_TOO_MANY_PIXELS');
      if (Math.max(source.width, source.height) > this.imageLimits.maxImageDimension)
        throw new AttachmentError('Image exceeds the configured side limit.', 'IMAGE_DIMENSION_TOO_LARGE');
      originalWidth = source.width; originalHeight = source.height;
      const budget = requestImageDimensions(source.width, source.height, this.normalizationPolicy.maxPixels);
      const ratio = Math.min(1, this.normalizationPolicy.maxDimension / Math.max(budget.width, budget.height));
      normalized = boundedPng(source, { width: budget.width * ratio, height: budget.height * ratio, maxBytes: this.normalizationPolicy.maxBytes }, undefined, bytes);
    } else {
      const native = await this.normalizeImage(input);
      const image = decodePng(native.data);
      if (native.mediaType !== 'image/png' || native.width !== image.width || native.height !== image.height || native.bytes !== native.data.length)
        throw new AttachmentError('Native image metadata does not match its bytes.', 'ATTACHMENT_WRITE_FAILED');
      originalWidth = native.originalWidth; originalHeight = native.originalHeight;
      if (!Number.isSafeInteger(originalWidth) || !Number.isSafeInteger(originalHeight) || originalWidth < image.width || originalHeight < image.height)
        throw new AttachmentError('Native image source dimensions are invalid.', 'ATTACHMENT_WRITE_FAILED');
      if (originalWidth * originalHeight > this.imageLimits.maxImagePixels || Math.max(originalWidth, originalHeight) > this.imageLimits.maxImageDimension
        || image.width * image.height > this.normalizationPolicy.maxPixels || Math.max(image.width, image.height) > this.normalizationPolicy.maxDimension)
        throw new AttachmentError('Native image dimensions exceed the configured limits.', 'ATTACHMENT_WRITE_FAILED');
      normalized = boundedPng(image, { width: image.width, height: image.height, maxBytes: this.normalizationPolicy.maxBytes }, undefined, native.data);
    }
    const { image, data } = normalized;
    const ref = { attachmentId: `sha256:${hash(data)}`, mediaType: 'image/png', bytes: data.length, width: image.width, height: image.height };
    if (input.name !== undefined) ref.name = fileName(input.name);
    if (image.width !== originalWidth || image.height !== originalHeight)
      ref.originalDimensions = { width: originalWidth, height: originalHeight };
    return { ref, data };
  }

  normalizeImage(input) {
    return normalizeAndroidImage(input, this.imageLimits, this.normalizationPolicy, { home: this.home });
  }

  async validateImage(input) { await this.compression.run(() => this.prepareImage(input)); }

  async saveImages(inputs) {
    this.validateImageBatch(inputs);
    const prepared = await Promise.all(inputs.map(input => this.compression.run(() => this.prepareImage(input))));
    const refs = [];
    for (const image of prepared) refs.push(await this.commitImage(image));
    return refs;
  }

  async saveImage(input) {
    return this.commitImage(await this.compression.run(() => this.prepareImage(input)));
  }

  async commitImage({ ref, data }) {
    // Matching the fresh, validated object's hash also verifies an existing object's dimensions.
    await this.publish([data], this.imageHostPath(ref), async () => { await readImageBytes(this, ref); });
    return ref;
  }

  imageHostPath(ref) {
    if (legacyPattern.test(ref?.attachmentId)) return join(this.legacyRoot, `${ref.attachmentId}.png`);
    referenceDigest(ref);
    return super.imageHostPath(ref);
  }

  async readImage(ref, signal) {
    const { data } = await readDecodedImage(this, ref, signal);
    return { ref, data };
  }

  async readImageRequest(ref, target, signal) {
    for (const key of ['width', 'height', 'maxBytes']) {
      if (!Number.isSafeInteger(target?.[key]) || target[key] < 1)
        throw new AttachmentError('Invalid image request target.', 'INVALID_ATTACHMENT_REF');
    }
    return this.compression.run(async () => {
      const stored = await readDecodedImage(this, ref, signal);
      const { image, data } = boundedPng(stored.image, target, signal, stored.data);
      const identity = JSON.stringify(['droy-png-request-v2', ref.attachmentId, target.width, target.height, target.maxBytes, hash(data)]);
      return { variantId: `sha256:${hash(identity)}`, attachment: ref, data, mediaType: 'image/png', bytes: data.length,
        width: image.width, height: image.height, depth: 'uchar', space: 'srgb', hasAlpha: image.channels === 4 };
    });
  }

  fileHostPath(ref) {
    const digest = referenceDigest(ref);
    if (typeof ref.name !== 'string' || ref.name !== fileName(ref.name))
      throw new AttachmentError('Invalid file attachment name.', 'INVALID_ATTACHMENT_REF');
    return join(this.root, 'files', digest.slice(0, 2), digest, ref.name);
  }

  saveFile(input) { return this.saveFileStream({ ...input, data: [input.data] }); }

  async saveFileStream(input) {
    const name = fileName(input.name);
    let ref;
    await this.publish(input.data, (digest, bytes) => {
      ref = { attachmentId: `sha256:${digest}`, bytes, name };
      return this.fileHostPath(ref);
    }, async () => { for await (const _chunk of this.readFileStream(ref, input.signal)) { /* Verify every stored byte. */ } }, input.signal);
    return ref;
  }

  async *readFileStream(ref, signal) {
    signal?.throwIfAborted();
    const expected = referenceDigest(ref);
    const stream = createReadStream(this.fileHostPath(ref), { highWaterMark: 65536, signal });
    const digest = createHash('sha256');
    let bytes = 0;
    try {
      for await (const chunk of stream) {
        signal?.throwIfAborted();
        digest.update(chunk); bytes += chunk.length;
        yield chunk;
      }
    } catch (error) {
      signal?.throwIfAborted();
      throw new AttachmentError(error.code === 'ENOENT' ? 'File attachment is missing.' : 'Unable to read file attachment.',
        error.code === 'ENOENT' ? 'ATTACHMENT_NOT_FOUND' : 'ATTACHMENT_READ_FAILED', { cause: error });
    } finally { stream.destroy(); }
    signal?.throwIfAborted();
    if (bytes !== ref.bytes || digest.digest('hex') !== expected)
      throw new AttachmentError('Stored file attachment failed integrity verification.', 'ATTACHMENT_CORRUPT');
  }

  async publish(chunks, destination, verifyExisting, signal) {
    signal?.throwIfAborted();
    const staging = join(this.root, 'tmp');
    await ensureDirectory(staging, this.home);
    const temporary = join(staging, `${randomUUID()}.tmp`);
    try {
      const fd = await open(temporary, 'wx', 0o600);
      const digest = createHash('sha256');
      let bytes = 0;
      try {
        for await (const chunk of chunks) {
          signal?.throwIfAborted();
          if (!(chunk instanceof Uint8Array)) throw new TypeError('Attachment streams must contain byte chunks');
          await fd.writeFile(chunk); digest.update(chunk); bytes += chunk.byteLength;
        }
        await fd.sync();
      } finally { await fd.close(); }
      signal?.throwIfAborted();
      const path = typeof destination === 'function' ? destination(digest.digest('hex'), bytes) : destination;
      await ensureDirectory(dirname(path), this.home);
      try { await publishExclusive(temporary, path); }
      catch (error) {
        if (error.code !== 'EEXIST') throw error;
        await verifyExisting();
      }
      signal?.throwIfAborted();
    } finally { await rm(temporary, { force: true }); }
  }
}
