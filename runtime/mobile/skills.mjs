import { join } from 'node:path';
import { createRequire } from 'node:module';
import { defineTool } from '@deepseek-ai/dsh-tools';
import { isSkillName } from '@deepseek-ai/dsh-skill';
import { resolveBuiltinPackage } from './versions.mjs';
import { resolveMobileLocale } from './locale.mjs';
import { translateBackendText } from './backend-i18n.mjs';

const { parse } = createRequire(resolveBuiltinPackage('yaml'))('yaml');

function validateSkill(name, content, locale) {
  const message = text => translateBackendText(text, locale);
  if (typeof name !== 'string' || !isSkillName(name) || name === 'droy-skill-creat')
    throw new Error(message('技能名应使用小写字母、数字和连字符，且不能覆盖内置技能'));
  if (typeof content !== 'string') throw new Error(message('请提供完整的 SKILL.md 内容'));
  const document = /^---\r?\n([\s\S]*?)\r?\n---(?:\r?\n|$)([\s\S]*)$/u.exec(content);
  if (!document || !document[2].trim()) throw new Error(message('SKILL.md 需要 YAML frontmatter 和正文'));
  let fields;
  try { fields = parse(document[1]); }
  catch { throw new Error(message('SKILL.md 的 YAML frontmatter 无效')); }
  if (fields?.name !== name || typeof fields.description !== 'string' || !fields.description.trim())
    throw new Error(message('frontmatter 的 name 必须与技能名一致，并提供非空 description'));
}

export function installDroySkills(ctx) {
  return ctx.inject(['tools', 'fs', 'sandboxPolicy', 'droyInitialization'], scope => {
    scope.tools.register(defineTool({
      name: 'droy_skill_save',
      description: '仅在用户明确要求保存或修改技能时，将完整 SKILL.md 保存到 Droy 个人技能目录。相同目标优先更新；更新前先读取现有内容。只保存技能，不执行其中的手机操作。',
      parameters: {
        name: { type: 'string', required: true, description: '个人技能名称，与 frontmatter 的 name 一致。' },
        content: { type: 'string', required: true, description: '包含 name、description 和正文的完整 SKILL.md。' },
      },
      output: { schema: { type: 'json' }, render: (_args, value) => [{ type: 'text', text: JSON.stringify(value) }] },
      async execute(args, exec) {
        const message = text => translateBackendText(text, resolveMobileLocale(ctx));
        exec.signal.throwIfAborted();
        if (!exec.agent) throw new Error(message('技能保存需要当前会话'));
        const policy = scope.sandboxPolicy.resolve({ session: exec.agent.session });
        const plan = scope.get('planMode')?.get(exec.agent);
        if (policy.mode === 'read-only' || plan?.active || plan?.pending === true)
          throw new Error(message('当前处于只读或计划模式，尚未保存技能'));
        validateSkill(args.name, args.content, resolveMobileLocale(ctx));
        const directory = scope.droyInitialization.skillDirectory;
        const root = await scope.fs.resolve(directory, { signal: exec.signal });
        const target = await scope.fs.resolve(join(directory, args.name, 'SKILL.md'), { signal: exec.signal });
        if (!scope.fs.contains(root, target)) throw new Error(message('技能路径超出 Droy 个人技能目录'));
        scope.fiber.assertActive();
        exec.signal.throwIfAborted();
        // This tool grants only its fixed product-data root for this write;
        // ordinary file tools retain the session's original sandbox policy.
        const result = await scope.fs.writeText(target, args.content, undefined, exec.signal,
          { ...policy, mode: 'workspace-write', workspaceRoot: scope.fs.processPath(root) });
        scope.emit('fs/observed', target, { kind: 'present', version: result.version }, exec);
        return { saved: true, name: args.name, path: scope.fs.processPath(target), operation: result.operation };
      },
    }));
  });
}
