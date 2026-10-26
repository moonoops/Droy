import { readFileSync } from 'node:fs';
const english = JSON.parse(readFileSync(new URL('./assets/tool-copy.en.json', import.meta.url), 'utf8'));

/** Translate owned schema prose only; identifiers, values, and third-party copy stay literal. */
export function localizeToolDescriptions(tools, locale) {
  if (locale !== 'en') return tools;
  function visit(value) {
    if (Array.isArray(value)) {
      const items = value.map(visit);
      return items.every((item, index) => item === value[index]) ? value : items;
    }
    if (!value || typeof value !== 'object') return value;
    let changed = false;
    const entries = Object.entries(value).map(([key, item]) => {
      const translated = key === 'description' && typeof item === 'string' ? english[item] ?? item : visit(item);
      if (translated !== item) changed = true;
      return [key, translated];
    });
    return changed ? Object.fromEntries(entries) : value;
  }
  return visit(tools);
}
