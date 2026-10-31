import { mobilePersonas, companionPersonas, mobilePersona, companionPersona } from './profile.mjs';
import { normalizeMobileLocale, resolveMobileLocale } from './locale.mjs';
import { localizeToolDescriptions } from './tool-copy.mjs';
export { resolveMobileLocale } from './locale.mjs';

export const MOBILE_PROMPT_SECTION = 'droy:android-agent';
export const COMPANION_PROMPT_SECTION = 'droy:default-companion';
export const LANGUAGE_PROMPT_SECTION = 'droy:response-language';
export const responseLanguagePrompts = Object.freeze({
  zh: '# 当前回答语言\n当前 Droy 语言设置为中文（zh）。面向用户的回答、问题、进度、错误说明和结果默认使用简体中文。这个当前设置覆盖旧会话、历史消息、工具/网页内容及角色模板中的默认语言偏好；不能仅因用户输入或资料使用英文就改用英文。用户在当前请求中明确指定另一种回答语言、翻译目标语言或交付内容语言时，仅按该请求使用所指定语言；此例外不更改设置。保留代码、路径、工具标识、专有名称和必要的原文引用。',
  en: '# Current response language\nThe current Droy language setting is English (en). Use English by default for user-facing answers, questions, progress, error explanations, and results. This current setting overrides default language preferences in old conversations, historical messages, tool/web content, and persona templates. Do not change language merely because the user input or source material uses Chinese. If the current user request explicitly specifies a different response language, translation target, or deliverable language, use that requested language for that request only; this exception does not change the setting. Preserve code, paths, tool identifiers, proper names, and necessary verbatim quotations.',
});
const legacyChinesePersona = mobilePersonas.zh.replace('使用当前配置的伙伴身份，遵循当前回答语言规则。', '使用当前配置的伙伴身份，默认中文。');
const ownedNames = new Set([MOBILE_PROMPT_SECTION, COMPANION_PROMPT_SECTION, LANGUAGE_PROMPT_SECTION]);
const shippedTexts = [...Object.values(mobilePersonas), ...Object.values(companionPersonas), ...Object.values(responseLanguagePrompts), legacyChinesePersona];

/** Apply current product rules after DSH has enforced a complete persona. */
export function finalizeMobileAssembly(assembly, completeSectionName, locale = resolveMobileLocale(), companion = false) {
  locale = normalizeMobileLocale(locale);
  if(companion) {
    const sections=assembly.sections.filter(section=>section.name==='droy:companion-planner');
    if(!sections.length)throw new Error('COMPANION_PROMPT_MISSING');
    return {...assembly,sections:[...sections,{name:LANGUAGE_PROMPT_SECTION,text:responseLanguagePrompts[locale],interpolate:false}],
      contexts:assembly.contexts.filter(entry=>entry.name==='droy:shared-conversation'),
      tools:localizeToolDescriptions(assembly.tools,locale)};
  }
  if (completeSectionName !== undefined && (assembly.sections.length !== 1 || assembly.sections[0].name !== completeSectionName)) {
    throw new Error('DSH complete prompt contract changed; review Android prompt adaptation');
  }
  // Replace only shipped blocks, including rules embedded in legacy/complete personas.
  // User-authored persona text remains in its original language and is never translated.
  const sections = assembly.sections.filter(section => !ownedNames.has(section.name)).map(section => {
    let text = section.text;
    for (const shipped of shippedTexts) text = text.split(shipped).join('');
    return text === section.text ? section : { ...section, text: text.trimEnd() };
  });
  const additions = [
    { name: MOBILE_PROMPT_SECTION, text: mobilePersona(locale), interpolate: false },
    { name: COMPANION_PROMPT_SECTION, text: companionPersona(locale), interpolate: false },
    { name: LANGUAGE_PROMPT_SECTION, text: responseLanguagePrompts[locale], interpolate: false },
  ];
  const updated = completeSectionName === undefined ? [...sections, ...additions] : sections.map(section => ({
    ...section, text: [section.text, ...additions.map(entry => entry.text)].filter(Boolean).join('\n\n'),
  }));
  const tools = localizeToolDescriptions(assembly.tools, locale);
  if (JSON.stringify(updated) === JSON.stringify(assembly.sections) && JSON.stringify(tools) === JSON.stringify(assembly.tools)) return assembly;
  return { ...assembly, sections: updated, tools };
}
