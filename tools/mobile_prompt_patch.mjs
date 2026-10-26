function replaceOnce(source, before, after) {
  if (source.split(before).length !== 2) {
    throw new Error('DSH system prompt assembly changed; review Android prompt adaptation');
  }
  return source.replace(before, after);
}

// Pinned upstream source is additionally checked by prepare_dsh's SHA-256 lock.
// Finalize after complete-prompt selection so minimal/custom modes and direct
// child persona overrides get the same product defaults exactly once.
export function patchSystemPrompt(source) {
  source = "import { finalizeMobileAssembly, resolveMobileLocale } from '@droyagent/mobile/prompt';\n" + source;
  source = replaceOnce(source,
    'if (completeSection === void 0 && !runtimeContextSuppressed) return transformed;',
    'if (completeSection === void 0 && !runtimeContextSuppressed) return finalizeMobileAssembly(transformed, undefined, resolveMobileLocale(this.ctx));');
  return replaceOnce(source,
    '\t\treturn {\n\t\t\t...transformed,\n\t\t\tsections: completeSection === void 0 ? transformed.sections : [completeSection],\n\t\t\tcontexts: runtimeContextSuppressed ? transformed.contexts.filter((entry) => requiredContexts.has(entry.name)) : transformed.contexts\n\t\t};',
    '\t\treturn finalizeMobileAssembly({\n\t\t\t...transformed,\n\t\t\tsections: completeSection === void 0 ? transformed.sections : [completeSection],\n\t\t\tcontexts: runtimeContextSuppressed ? transformed.contexts.filter((entry) => requiredContexts.has(entry.name)) : transformed.contexts\n\t\t}, completeSection?.name, resolveMobileLocale(this.ctx));');
}

/** Titles use a separate LLM request, so the ordinary agent prompt cannot govern them. */
export function patchSessionTitleProvider(source) {
  source = "import { resolveMobileLocale } from '@droyagent/mobile/prompt';\n" + source;
  source = replaceOnce(source, '"Use the language of the messages.",', 'config.droyLocale === "zh" ? "标题使用简体中文；不要因输入资料或旧标题使用英文就改用英文。" : "Write the title in English, regardless of the language of the input messages or old title.",');
  source = replaceOnce(source,
    'Create a concise title for an AI coding-assistant session from the supplied human messages.',
    'Create a concise title for a Droy personal-assistant session from the supplied human messages.');
  // Resolve at generate(), not plugin registration, so saved settings apply live.
  source = replaceOnce(source, 'system: systemPrompt(resolved', 'system: systemPrompt({ ...resolved, droyLocale: resolveMobileLocale(ctx) }');
  if (source.includes('An existing title is supplied as currentTitle.')) {
    source = replaceOnce(source,
      'An existing title is supplied as currentTitle. If it still accurately describes the main topic or task, return it exactly unchanged.',
      'An existing title is supplied as currentTitle. If it is already in the required language and still accurately describes the main topic or task, return it exactly unchanged. Otherwise translate its meaning into the required language; do not change the topic.');
    source = replaceOnce(source,
      'Change the title only when the messages materially change or expand the main topic or task so that the existing title is no longer accurate.',
      'Change the title only to satisfy the required language or when the messages materially change or expand the main topic or task so that the existing title is no longer accurate.');
  }
  return source;
}
