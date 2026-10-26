import { VoiceError } from './config.mjs';

export function decodeBase64(value, maxBytes = 4 * 1024 * 1024) {
  if (typeof value !== 'string' || value.length > Math.ceil(maxBytes / 3) * 4 + 4
      || value.length % 4 || !/^[A-Za-z0-9+/]*={0,2}$/u.test(value))
    throw new VoiceError('INVALID_MEDIA', '音频或画面数据格式无效');
  return Buffer.from(value, 'base64');
}

export function pcm16ToFloat32(bytes) {
  if (bytes.length % 2) throw new VoiceError('INVALID_PCM', '音频数据未对齐');
  const values = new Float32Array(bytes.length / 2);
  for (let i = 0; i < values.length; i++) values[i] = bytes.readInt16LE(i * 2) / 32768;
  return values;
}
export function float32ToPcm16(bytes) {
  if (bytes.length % 4) throw new VoiceError('INVALID_PCM', '音频数据未对齐');
  const out = Buffer.alloc(bytes.length / 2);
  for (let i = 0; i < bytes.length / 4; i++) {
    const value = bytes.readFloatLE(i * 4);
    if (!Number.isFinite(value)) throw new VoiceError('INVALID_PCM', '语音服务返回了无效音频');
    out.writeInt16LE(Math.round(Math.max(-1, Math.min(1, value)) * (value < 0 ? 32768 : 32767)), i * 2);
  }
  return out;
}
export function floatsToBytes(values) {
  const out = Buffer.alloc(values.length * 4);
  for (let i = 0; i < values.length; i++) out.writeFloatLE(values[i], i * 4);
  return out;
}
export function floatsToPcm16(values) { return float32ToPcm16(floatsToBytes(values)); }

/** Retain fractional sample position between native chunks to avoid clock drift. */
export function createResampler(inputRate, outputRate) {
  if (![inputRate, outputRate].every(value => Number.isInteger(value) && value >= 8000 && value <= 192000))
    throw new VoiceError('INVALID_SAMPLE_RATE', '音频采样率不支持');
  const step = inputRate / outputRate;
  let samples = new Float32Array(), position = 0;
  return {
    push(chunk) {
      const joined = new Float32Array(samples.length + chunk.length);
      joined.set(samples); joined.set(chunk, samples.length);
      const out = [];
      while (position + 1 < joined.length) {
        const start = Math.floor(position), fraction = position - start;
        out.push(joined[start] * (1 - fraction) + joined[start + 1] * fraction);
        position += step;
      }
      const consumed = Math.min(Math.floor(position), joined.length);
      samples = joined.slice(consumed); position -= consumed;
      return Float32Array.from(out);
    },
  };
}
