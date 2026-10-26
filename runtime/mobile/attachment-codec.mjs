import { AttachmentError } from '@deepseek-ai/dsh-attachment';
import { randomUUID } from 'node:crypto';
import { mkdir, readFile, rm, writeFile } from 'node:fs/promises';
import { join } from 'node:path';

export async function normalizeAndroidImage(input, limits, target, {
  home, port = process.env.DROY_BRIDGE_PORT, token = process.env.DROY_BRIDGE_TOKEN, fetchImpl = fetch,
} = {}) {
  if (!home || !/^\d+$/.test(String(port)) || Number(port) < 1 || Number(port) > 65535 || !token || token.length < 32)
    throw new AttachmentError('Android image decoder is not configured.', 'ATTACHMENT_WRITE_FAILED');
  const id = randomUUID(), root = join(home, 'attachments', 'native');
  const source = join(root, `${id}.source`), output = join(root, `${id}.png`);
  await mkdir(root, { recursive: true, mode: 0o700 });
  try {
    await writeFile(source, input.data, { flag: 'wx', mode: 0o600 });
    const response = await fetchImpl(`http://127.0.0.1:${port}/attachments/normalize`, {
      method: 'POST', headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json; charset=utf-8' },
      body: JSON.stringify({ id, mediaType: input.mediaType,
        limits: { maxImageBytes: limits.maxImageBytes, maxImagePixels: limits.maxImagePixels, maxImageDimension: limits.maxImageDimension }, target }),
      redirect: 'error', signal: AbortSignal.timeout(60_000),
    });
    const envelope = await response.json();
    if (!response.ok || envelope.ok !== true) {
      const code = envelope.error?.code === 'REJECTED' ? 'INVALID_IMAGE' : 'ATTACHMENT_WRITE_FAILED';
      throw new AttachmentError(envelope.error?.message ?? 'Android image decoding failed.', code);
    }
    return { ...envelope.result, data: await readFile(output) };
  } finally {
    await Promise.all([rm(source, { force: true }), rm(output, { force: true })]);
  }
}
