function replaceOnce(source, from, to) {
  if (source.split(from).length !== 2) throw new Error('DSH locale persistence contract changed');
  return source.replace(from, to);
}

// Publish only an accepted selection so UI, native copy and the next model
// request cannot disagree while the settings write is still in flight.
export function patchLocalePersistence(source) {
  source = replaceOnce(source, 'setLocale(id) {\n\t\t\t\tconst match', 'async setLocale(id) {\n\t\t\t\tconst match');
  source = replaceOnce(source,
    '\t\t\t\tthis.preference = match.id;\n\t\t\t\tif (this.snapshot.active !== match.id) this.publish(match.id, true);\n\t\t\t\tthis.host?.set(LOCALE_PREFERENCE_FIELD, match.id);',
    `\t\t\t\tif (this.host && !await this.host.set(LOCALE_PREFERENCE_FIELD, match.id)) {
\t\t\t\t\tthrow new Error(this.snapshot.active === "zh" ? "语言未保存，请重试" : "Language was not saved. Please try again.");
\t\t\t\t}
\t\t\t\tthis.preference = match.id;
\t\t\t\tif (this.snapshot.active !== match.id) this.publish(match.id, true);`);
  source = replaceOnce(source, 'const [open, setOpen] = (0, react.useState)(false);',
    'const [open, setOpen] = (0, react.useState)(false);\n\t\t\tconst [languageError, setLanguageError] = (0, react.useState)("");');
  source = replaceOnce(source, 'children: t("language.title")',
    'children: [t("language.title"), languageError && (0, react_jsx_runtime.jsx)("div", { role: "alert", children: languageError })]');
  source = replaceOnce(source,
    'onSelect: (id) => {\n\t\t\t\t\t\tsetLocale(id);\n\t\t\t\t\t\tsetOpen(false);\n\t\t\t\t\t}',
    `onSelect: async (id) => {
\t\t\t\t\t\tsetLanguageError("");
\t\t\t\t\t\ttry { await setLocale(id); setOpen(false); }
\t\t\t\t\t\tcatch (error) { setLanguageError(error.message); }
\t\t\t\t\t}`);
  source = replaceOnce(source, 'return { setLocale: (id) => {', 'return { setLocale: async (id) => {');
  return replaceOnce(source, 'locale.setLocale(id);', `const previous = locale.getSnapshot().active;
\t\t\t\t\tawait locale.setLocale(id);
\t\t\t\t\tif (bridge !== undefined) {
\t\t\t\t\t\tif (previous === locale.getSnapshot().active) await bridge.onChange(locale.getSnapshot().active);
\t\t\t\t\t\telse await bridge.whenSynced?.();
\t\t\t\t\t}`);
}
