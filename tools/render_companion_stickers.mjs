import {createRequire} from 'node:module';
import {readdir} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';

// Editable, original vector sources are the source of truth; PNG is the native UI format.
const require=createRequire(new URL('../runtime/dsh/package.json',import.meta.url));
const sharp=require('sharp');
const directory=new URL('../runtime/mobile/companions/assets/',import.meta.url);
for(const name of (await readdir(new URL('source/',directory))).filter(name=>/^sticker-[a-z-]+\.svg$/.test(name)).sort()) {
  await sharp(fileURLToPath(new URL('source/'+name,directory))).resize(256,256)
    .png({compressionLevel:9}).toFile(fileURLToPath(new URL(name.replace(/\.svg$/,'.png'),directory)));
}
