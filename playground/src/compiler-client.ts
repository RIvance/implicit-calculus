import { proxy, releaseProxy, wrap, type Remote } from 'comlink';
import workerModule from './compiler.worker?worker&url';
import solverPackage from 'z3-solver/package.json';
import type { WorkerApi } from './compiler.worker';
import type { Progress, RunOptions } from '../bridge/protocol';

export class Interrupted extends Error {
  constructor(
    readonly code: 'Cancelled' | 'ExecutionTimeout',
    message: string,
  ) {
    super(message);
  }
}

/** Owns one compiler worker and its solver threads; cancellation discards the entire execution. */
export class CompilerClient {
  private readonly worker: Worker;
  private readonly remote: Remote<WorkerApi>;
  private readonly lifetime = new AbortController();
  readonly language: Promise<{ keywords: string[] }>;
  readonly ready: Promise<{ version: string }>;

  constructor() {
    if (!crossOriginIsolated || typeof SharedArrayBuffer === 'undefined') {
      throw new Error('The browser could not enable shared memory for the solver. Reload the playground to retry.');
    }
    const assets = new URL(`${import.meta.env.BASE_URL}z3/${solverPackage.version}/`, location.href).href;
    const loader = new URL('z3-built.js', assets).href;
    const module = new URL(workerModule, location.href).href;
    // The Emscripten loader must remain a standalone script for its pthread workers.
    const bootstrap = `
      self.global = self;
      importScripts(${JSON.stringify(loader)});
      const initialize = self.initZ3;
      self.initZ3 = (options) => initialize({
        ...options,
        locateFile: (file) => new URL(file, ${JSON.stringify(assets)}).href,
        mainScriptUrlOrBlob: ${JSON.stringify(loader)}
      });
      import(${JSON.stringify(module)})
        .then(() => self.postMessage({ kind: 'compiler-ready' }))
        .catch(error => self.postMessage({ kind: 'compiler-error', message: String(error) }));
    `;
    const url = URL.createObjectURL(new Blob([bootstrap], { type: 'text/javascript' }));
    this.worker = new Worker(url, { name: 'implicit-compiler' });
    this.remote = wrap<WorkerApi>(this.worker);
    const loaded = new Promise<void>((resolve, reject) => {
      const receive = (event: MessageEvent) => {
        if (event.data?.kind === 'compiler-ready' || event.data?.kind === 'compiler-error') {
          this.worker.removeEventListener('message', receive);
          URL.revokeObjectURL(url);
          if (event.data.kind === 'compiler-ready') resolve();
          else reject(new Error(event.data.message));
        }
      };
      this.worker.addEventListener('message', receive);
      this.worker.addEventListener('error', (event) => {
        URL.revokeObjectURL(url);
        const failure = new Error(event.message || 'The compiler worker could not start.');
        reject(failure);
        this.dispose(failure);
      });
      this.lifetime.signal.addEventListener('abort', () => URL.revokeObjectURL(url), { once: true });
    });
    const startupTimeout = setTimeout(
      () => this.dispose(new Error('The solver took too long to load. Try reloading.')),
      30_000,
    );
    this.language = this.interruptible(loaded.then(() => this.remote.language()));
    this.ready = this.interruptible(loaded.then(() => this.remote.ready())).finally(() => clearTimeout(startupTimeout));
  }

  private interruptible<T>(operation: Promise<T>): Promise<T> {
    const signal = this.lifetime.signal;
    if (signal.aborted) return Promise.reject(signal.reason);
    return new Promise<T>((resolve, reject) => {
      const interrupt = () => reject(signal.reason);
      signal.addEventListener('abort', interrupt, { once: true });
      operation.then(resolve, reject).finally(() => signal.removeEventListener('abort', interrupt));
    });
  }

  async analyze(source: string) {
    await this.ready;
    return this.interruptible(this.remote.analyze(source));
  }

  async run(source: string, options: RunOptions, emit: (event: Progress) => void, executionTimeoutMillis: number) {
    await this.ready;
    const timeout = setTimeout(
      () =>
        this.dispose(
          new Interrupted(
            'ExecutionTimeout',
            `Execution stopped after ${executionTimeoutMillis / 1000} seconds. No final result was established.`,
          ),
        ),
      executionTimeoutMillis,
    );
    try {
      return await this.interruptible(this.remote.run(source, options, proxy(emit)));
    } finally {
      clearTimeout(timeout);
    }
  }

  dispose(reason: Error = new Interrupted('Cancelled', 'Execution cancelled.')) {
    if (this.lifetime.signal.aborted) return;
    this.lifetime.abort(reason);
    this.remote[releaseProxy]();
    this.worker.terminate();
  }
}
