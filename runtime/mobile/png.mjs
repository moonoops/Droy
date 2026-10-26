import { inflateSync, deflateSync } from 'node:zlib';
const signature = Buffer.from([137,80,78,71,13,10,26,10]);
const crcTable = Uint32Array.from({ length: 256 }, (_, i) => { for (let j = 0; j < 8; j++) i = (i & 1) ? (0xedb88320 ^ (i >>> 1)) : i >>> 1; return i >>> 0; });
export function crc32(bytes) { let crc = 0xffffffff; for (const byte of bytes) crc = crcTable[(crc ^ byte) & 255] ^ (crc >>> 8); return (crc ^ 0xffffffff) >>> 0; }
const paeth = (a,b,c) => { const p = a+b-c, x = Math.abs(p-a), y = Math.abs(p-b), z = Math.abs(p-c); return x <= y && x <= z ? a : y <= z ? b : c; };
export function decodePng(input) {
  const data = Buffer.from(input);
  if (data.length > 20 * 1024 * 1024 || !data.subarray(0,8).equals(signature)) throw new Error('仅支持不超过20MB的PNG截图');
  let width, height, channels, offset = 8, ended = false;
  const compressed = [];
  while (offset < data.length) {
    if (offset + 12 > data.length) throw new Error('PNG截断');
    const length = data.readUInt32BE(offset), type = data.toString('ascii',offset+4,offset+8), end = offset + length + 12;
    if (end > data.length) throw new Error('PNG数据长度无效');
    if (crc32(data.subarray(offset+4,end-4)) !== data.readUInt32BE(end-4)) throw new Error('PNG校验失败');
    const chunk = data.subarray(offset+8,end-4);
    if (width === undefined && type !== 'IHDR') throw new Error('PNG缺少头部');
    if (type === 'IHDR') {
      if (width !== undefined || length !== 13) throw new Error('PNG头部无效');
      width = chunk.readUInt32BE(0); height = chunk.readUInt32BE(4);
      if (chunk[8] !== 8 || ![2,6].includes(chunk[9]) || chunk[10] || chunk[11] || chunk[12]) throw new Error('仅支持8bit RGB/RGBA非交错PNG截图');
      channels = chunk[9] === 6 ? 4 : 3;
      if (!width || !height || width > 8192 || height > 8192 || width * height > 16_000_000) throw new Error('PNG尺寸超限');
    } else if (type === 'IDAT') compressed.push(chunk);
    else if (type === 'IEND') { if (length !== 0 || end !== data.length) throw new Error('PNG尾部无效'); ended = true; break; }
    else if (type[0] === type[0].toUpperCase()) throw new Error(`PNG关键块不支持: ${type}`);
    offset = end;
  }
  if (!ended || !compressed.length) throw new Error('PNG不完整');
  const stride = width * channels, expected = (stride+1)*height;
  const inflated = inflateSync(Buffer.concat(compressed), { maxOutputLength: expected });
  if (inflated.length !== expected) throw new Error('PNG像素长度无效');
  const pixels = Buffer.alloc(stride*height);
  for (let y=0; y<height; y++) {
    const inputRow = y*(stride+1)+1, outputRow = y*stride;
    const filter = inflated[inputRow-1];
    if (filter > 4) throw new Error('PNG过滤类型无效');
    if (filter === 0) {
      inflated.copy(pixels, outputRow, inputRow, inputRow+stride);
      continue;
    }
    if (filter === 1) {
      for (let x=0; x<stride; x++) pixels[outputRow+x] =
        (inflated[inputRow+x] + (x >= channels ? pixels[outputRow+x-channels] : 0)) & 255;
    } else if (filter === 2) {
      for (let x=0; x<stride; x++) pixels[outputRow+x] =
        (inflated[inputRow+x] + (y ? pixels[outputRow-stride+x] : 0)) & 255;
    } else if (filter === 3) {
      for (let x=0; x<stride; x++) {
        const left = x >= channels ? pixels[outputRow+x-channels] : 0, up = y ? pixels[outputRow-stride+x] : 0;
        pixels[outputRow+x] = (inflated[inputRow+x] + ((left+up) >>> 1)) & 255;
      }
    } else {
      for (let x=0; x<stride; x++) {
        const left = x >= channels ? pixels[outputRow+x-channels] : 0, up = y ? pixels[outputRow-stride+x] : 0;
        const upperLeft = y && x >= channels ? pixels[outputRow-stride+x-channels] : 0;
        pixels[outputRow+x] = (inflated[inputRow+x] + paeth(left,up,upperLeft)) & 255;
      }
    }
  }
  return { width, height, channels, pixels };
}
function chunk(type, data) { const name = Buffer.from(type); const output = Buffer.alloc(data.length+12); output.writeUInt32BE(data.length); name.copy(output,4); data.copy(output,8); output.writeUInt32BE(crc32(output.subarray(4,output.length-4)),output.length-4); return output; }
export function encodePng({ width, height, channels, pixels }) {
  const header = Buffer.alloc(13); header.writeUInt32BE(width); header.writeUInt32BE(height,4); header[8]=8; header[9]=channels===4?6:2;
  const stride=width*channels, raw=Buffer.alloc((stride+1)*height);
  for(let y=0;y<height;y++) pixels.copy(raw,y*(stride+1)+1,y*stride,(y+1)*stride);
  return Buffer.concat([signature,chunk('IHDR',header),chunk('IDAT',deflateSync(raw)),chunk('IEND',Buffer.alloc(0))]);
}
export function resizePng(image, width, height) {
  width=Math.max(1,Math.min(image.width,Math.floor(width))); height=Math.max(1,Math.min(image.height,Math.floor(height)));
  if (width === image.width && height === image.height) return image;
  const channels=image.channels, source=image.pixels, pixels=Buffer.alloc(width*height*channels);
  for(let y=0;y<height;y++) {
    const sourceRow=Math.floor(y*image.height/height)*image.width*channels, targetRow=y*width*channels;
    for(let x=0;x<width;x++) {
      const src=sourceRow+Math.floor(x*image.width/width)*channels, dst=targetRow+x*channels;
      for(let channel=0;channel<channels;channel++) pixels[dst+channel]=source[src+channel];
    }
  }
  return {width,height,channels,pixels};
}
