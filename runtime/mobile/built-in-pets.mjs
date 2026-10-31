import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { normalizeMobileLocale } from './locale.mjs';

const directories = new Map([
  ['default', ''],
  ['8dd37d0d-b6ed-47c2-9952-8f3be20fa6d0', 'marmot'],
  ['cb5c4126-8c3b-41a5-a1bd-3d3b95cdeee2', 'alpaca'],
]);

export const defaultBuiltInRoot = new URL('./assets/pets/', import.meta.url);
export const isBuiltInPet = id => directories.has(id);
export function builtInPetFile(root, id, file) {
  if (!isBuiltInPet(id)) throw new Error('invalid_built_in_pet_id');
  if (!(file === 'pet.json' || /^[A-Za-z0-9_-]+\.png$/.test(file))) throw new Error('invalid_built_in_pet_file');
  return join(root instanceof URL ? fileURLToPath(root) : root, directories.get(id), file);
}

export async function readBuiltInPets(root, readBounded, locale) {
  const base = root instanceof URL ? fileURLToPath(root) : root;
  const catalog = JSON.parse((await readBounded(join(base, 'builtins.json'), 16 * 1024)).toString('utf8'));
  if (catalog.formatVersion !== 1 || !Array.isArray(catalog.pets) || catalog.pets.length !== directories.size)
    throw new Error('invalid_built_in_pet_catalog');
  const seen = new Set(), language = normalizeMobileLocale(locale);
  return catalog.pets.map(row => {
    if (!isBuiltInPet(row.id) || seen.has(row.id) || row.directory !== directories.get(row.id))
      throw new Error('invalid_built_in_pet_catalog');
    seen.add(row.id);
    for (const pair of [row.nameLocales, row.descriptionLocales])
      if (!pair || ['zh', 'en'].some(key => typeof pair[key] !== 'string' || !pair[key].trim()))
        throw new Error('invalid_built_in_pet_locale');
    const url = file => `/droy/pets/asset?id=${encodeURIComponent(row.id)}&file=${encodeURIComponent(file)}`;
    return { id: row.id, name: row.nameLocales[language], nameLocales: row.nameLocales,
      description: row.descriptionLocales[language], descriptionLocales: row.descriptionLocales,
      referenceUrl: url('reference.png'), manifestUrl: url('pet.json'), logoUrl: url('brand-logo.png') };
  });
}
