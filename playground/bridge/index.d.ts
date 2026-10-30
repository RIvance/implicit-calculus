import type { Analysis, Runner } from './protocol';

export function analyze(source: string): Analysis;
export function language(): { keywords: string[] };
export function createRunner(): Promise<Runner>;
