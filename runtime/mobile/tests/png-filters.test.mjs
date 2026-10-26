import test from 'node:test';
import assert from 'node:assert/strict';
import { deflateSync } from 'node:zlib';
import { crc32, decodePng, resizePng } from '../png.mjs';

function chunk(name, data) {
  const out = Buffer.alloc(data.length + 12);
  out.writeUInt32BE(data.length); out.write(name, 4); data.copy(out, 8);
  out.writeUInt32BE(crc32(out.subarray(4, -4)), out.length - 4);
  return out;
}

test('PNG scanline filters recover the same reference RGB pixels', () => {
  const signature = Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]);
  const header = Buffer.from([0, 0, 0, 3, 0, 0, 0, 2, 8, 2, 0, 0, 0]);
  const first = [1, 2, 3, 4, 5, 6, 7, 8, 9];
  const expected = Buffer.from([...first, 11, 12, 13, 14, 15, 16, 17, 18, 19]);
  const filteredRows = [
    [11, 12, 13, 14, 15, 16, 17, 18, 19],
    [11, 12, 13, 3, 3, 3, 3, 3, 3],
    [10, 10, 10, 10, 10, 10, 10, 10, 10],
    [11, 11, 12, 7, 7, 7, 7, 7, 7],
    [10, 10, 10, 3, 3, 3, 3, 3, 3],
  ];
  for (let filter = 0; filter <= 4; filter++) {
    const png = Buffer.concat([signature, chunk('IHDR', header),
      chunk('IDAT', deflateSync(Buffer.from([0, ...first, filter, ...filteredRows[filter]]))), chunk('IEND', Buffer.alloc(0))]);
    assert.deepEqual(decodePng(png).pixels, expected, `filter ${filter}`);
  }
});

test('same-size PNG resize reuses validated pixels', () => {
  const image = { width: 2, height: 1, channels: 4, pixels: Buffer.from([1, 2, 3, 0, 4, 5, 6, 255]) };
  assert.equal(resizePng(image, 2, 1), image);
  assert.deepEqual(resizePng(image, 1, 1).pixels, image.pixels.subarray(0, 4));
});

for (const channels of [3, 4]) test(`${channels === 3 ? 'RGB' : 'RGBA'} downsampling preserves exact nearest-neighbor channel bytes`, () => {
  const image = { width: 7, height: 5, channels,
    pixels: Buffer.from(Array.from({ length: 7 * 5 * channels }, (_, index) => (index * 37 + 17) % 256)) };
  const sampled = resizePng(image, 3.9, 2.9);
  const expected = Buffer.concat([0, 2].flatMap(y => [0, 2, 4].map(x => {
    const offset = (y * 7 + x) * channels;
    return image.pixels.subarray(offset, offset + channels);
  })));
  assert.equal(sampled.width, 3); assert.equal(sampled.height, 2); assert.equal(sampled.channels, channels);
  assert.deepEqual(sampled.pixels, expected);
  assert.deepEqual(resizePng(image, 0, 0).pixels, image.pixels.subarray(0, channels));
  assert.equal(resizePng(image, 99, 99), image);
});
