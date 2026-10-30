import type { RunOptions } from '../bridge/protocol';

export interface DocumentState {
  source: string;
  name: string;
}
export interface HistoryEntry extends DocumentState {
  id: string;
  time: number;
  ok: boolean;
  summary: string;
  options: RunOptions;
}

export const draftKey = 'implicit:document:v1';
export const historyKey = 'implicit:history:v1';

export async function sharedDocument(): Promise<DocumentState | undefined> {
  const code = new URLSearchParams(location.hash.slice(1)).get('code');
  if (!code) return undefined;
  const binary = atob(code.replaceAll('-', '+').replaceAll('_', '/'));
  const bytes = Uint8Array.from(binary, (character) => character.charCodeAt(0));
  const stream = new Blob([bytes]).stream().pipeThrough(new DecompressionStream('gzip'));
  const value: unknown = JSON.parse(await new Response(stream).text());
  if (
    !value ||
    typeof value !== 'object' ||
    !('source' in value) ||
    typeof value.source !== 'string' ||
    !('name' in value) ||
    typeof value.name !== 'string'
  )
    throw new Error('The shared document is invalid.');
  return { source: value.source, name: value.name };
}

export async function shareDocument(document: DocumentState): Promise<string> {
  const stream = new Blob([JSON.stringify(document)]).stream().pipeThrough(new CompressionStream('gzip'));
  const bytes = new Uint8Array(await new Response(stream).arrayBuffer());
  const code = btoa(Array.from(bytes, (byte) => String.fromCharCode(byte)).join(''))
    .replaceAll('+', '-')
    .replaceAll('/', '_')
    .replaceAll('=', '');
  const url = new URL(location.href);
  url.hash = new URLSearchParams({ code }).toString();
  return url.href;
}

export function download(name: string, contents: string, type = 'text/plain') {
  const url = URL.createObjectURL(new Blob([contents], { type }));
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = name;
  anchor.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}

export function readDraft(): DocumentState | undefined {
  const stored = localStorage.getItem(draftKey);
  if (!stored) return undefined;
  const value: unknown = JSON.parse(stored);
  if (
    !value ||
    typeof value !== 'object' ||
    !('source' in value) ||
    typeof value.source !== 'string' ||
    !('name' in value) ||
    typeof value.name !== 'string'
  )
    throw new Error('The saved document could not be read.');
  return { source: value.source, name: value.name };
}

export function readHistory(): HistoryEntry[] {
  const stored = localStorage.getItem(historyKey);
  if (!stored) return [];
  const value: unknown = JSON.parse(stored);
  if (
    !Array.isArray(value) ||
    !value.every(
      (entry) =>
        entry &&
        typeof entry.source === 'string' &&
        typeof entry.id === 'string' &&
        typeof entry.name === 'string' &&
        typeof entry.time === 'number' &&
        typeof entry.summary === 'string' &&
        typeof entry.ok === 'boolean' &&
        entry.options,
    )
  ) {
    throw new Error('The saved history could not be read.');
  }
  return value as HistoryEntry[];
}
