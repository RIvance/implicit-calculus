import type { Analysis, Language, Runner } from './protocol';

export function analyze(source: string): Analysis;
export function language(): Language;
export function createRunner(): Promise<Runner>;
