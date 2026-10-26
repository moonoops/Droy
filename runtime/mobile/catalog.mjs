import { readFileSync } from 'node:fs';
export const capabilityCatalog = JSON.parse(readFileSync(new URL('./assets/droy-capabilities.json', import.meta.url), 'utf8'));
const actions = new Set(), names = new Set(), groups = new Set(capabilityCatalog.groups.map(group => group.id));
for (const tool of capabilityCatalog.tools) {
  if (actions.has(tool.action) || names.has(tool.name) || !tool.permissions.every(id => groups.has(id))) throw new Error('Droy 能力目录重复或权限无效');
  for (const permissions of Object.values(tool.operationPermissions ?? {})) {
    if (!Array.isArray(permissions) || !permissions.every(id => groups.has(id))) throw new Error('Droy 操作权限目录无效');
  }
  actions.add(tool.action); names.add(tool.name);
}
export const toolDefinitions = capabilityCatalog.tools;
export const allowedToolNames = names;
