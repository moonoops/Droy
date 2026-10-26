/** Droy ships Chinese and English; other installation languages use English. */
export function normalizeMobileLocale(value) {
  return typeof value === 'string' && /^zh(?:[-_]|$)/i.test(value) ? 'zh' : 'en';
}

/** Read the same live Host preference used by DSH's General language row. */
export function resolveMobileLocale(ctx) {
  const settings = typeof ctx?.get === 'function' ? ctx.get('settings') : ctx?.settings;
  const preference = typeof settings?.describe === 'function'
    ? settings.describe().find(entry => entry.ns === 'locale')?.value?.preference : undefined;
  return normalizeMobileLocale(preference ?? process.env.DROY_LOCALE);
}
