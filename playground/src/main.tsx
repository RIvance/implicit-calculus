import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { createTheme, MantineProvider } from '@mantine/core';
import { App } from './App';
import { examples } from './examples';
import { readDraft, sharedDocument } from './document';
import '@mantine/core/styles.css';
import './styles.css';

const theme = createTheme({
  primaryColor: 'teal',
  defaultRadius: 'md',
  fontFamily: 'Inter, ui-sans-serif, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif',
  fontFamilyMonospace: '"JetBrains Mono", "SFMono-Regular", Consolas, monospace',
  headings: { fontFamily: 'inherit', fontWeight: '650' },
});

const isolationAttempt = 'implicit:browser-setup';

async function prepareBrowser(): Promise<boolean> {
  if (crossOriginIsolated) {
    sessionStorage.removeItem(isolationAttempt);
    return true;
  }
  if (!isSecureContext) {
    throw new Error('Open the playground over HTTPS, or use localhost for a local preview.');
  }
  if (!('serviceWorker' in navigator)) {
    throw new Error('This browser has disabled the background worker needed to run the solver on a static site.');
  }
  const workerUrl = new URL(`${import.meta.env.BASE_URL}coi-serviceworker.js`, location.href).href;
  if (sessionStorage.getItem(isolationAttempt) === workerUrl) {
    throw new Error(
      'The browser could not enable the solver. Open the playground directly in its own tab and allow service workers.',
    );
  }
  document.getElementById('root')!.textContent = 'Preparing the Z3 backend…';
  const registration = await navigator.serviceWorker.register(workerUrl, { updateViaCache: 'none' });
  const worker = registration.installing ?? registration.waiting ?? registration.active;
  if (!worker) throw new Error('The browser could not start the playground setup.');
  await new Promise<void>((resolve, reject) => {
    const finish = (error?: Error) => {
      clearTimeout(timeout);
      worker.removeEventListener('statechange', changed);
      if (error) reject(error);
      else resolve();
    };
    const changed = () => {
      if (worker.state === 'activated') finish();
      else if (worker.state === 'redundant') finish(new Error('The browser stopped the playground setup.'));
    };
    const timeout = setTimeout(() => finish(new Error('Browser setup took too long. Please retry.')), 15_000);
    worker.addEventListener('statechange', changed);
    changed();
  });
  sessionStorage.setItem(isolationAttempt, workerUrl);
  location.reload();
  return false;
}

async function start() {
  if (!(await prepareBrowser())) return;
  const first = examples.find((example) => example.expectation === 'result');
  let initial = { name: first?.path ?? 'untitled.imp', source: first?.source ?? '' };
  let initialNotice: string | undefined;
  try {
    initial = (await sharedDocument()) ?? readDraft() ?? initial;
  } catch (error) {
    initialNotice = error instanceof Error ? error.message : 'The saved or shared document could not be read.';
  }
  createRoot(document.getElementById('root')!).render(
    <StrictMode>
      <MantineProvider theme={theme} defaultColorScheme="light">
        <App initial={initial} initialNotice={initialNotice} />
      </MantineProvider>
    </StrictMode>,
  );
}
void start().catch((error: unknown) => {
  const container = document.getElementById('root')!;
  container.setAttribute('role', 'alert');
  container.textContent = error instanceof Error ? error.message : 'The playground could not start.';
  const retry = document.createElement('button');
  retry.textContent = 'Retry';
  retry.addEventListener('click', () => {
    sessionStorage.removeItem(isolationAttempt);
    location.reload();
  });
  container.appendChild(retry);
});
