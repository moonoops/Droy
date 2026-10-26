import { readFileSync } from 'node:fs';
import { normalizeMobileLocale } from './locale.mjs';

const readPrompt = name => readFileSync(new URL(`./prompts/${name}.md`, import.meta.url), 'utf8').trim();
export const mobilePersonas = Object.freeze({ zh: readPrompt('android-agent'), en: readPrompt('android-agent.en') });
export const companionPersonas = Object.freeze({ zh: readPrompt('default-companion'), en: readPrompt('default-companion.en') });
// Retain the existing exports for migration recognition and external callers.
export const persona = mobilePersonas.zh;
export const defaultCompanionPersona = companionPersonas.zh;
export const mobilePersona = locale => mobilePersonas[normalizeMobileLocale(locale)];
export const companionPersona = locale => companionPersonas[normalizeMobileLocale(locale)];
