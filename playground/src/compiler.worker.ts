import { expose, releaseProxy, type Remote } from 'comlink';
import { analyze, createRunner, language } from '@implicit/compiler';
import type { Progress, RunOptions } from '../bridge/protocol';

function createWorkerApi() {
  const runner = createRunner();
  return {
    language,
    async ready() {
      return { version: (await runner).version };
    },
    analyze,
    async run(source: string, options: RunOptions, callback: (event: Progress) => void) {
      const emit = callback as Remote<(event: Progress) => void>;
      try {
        return await (
          await runner
        ).run(
          source,
          options.maximumSteps,
          options.maximumTypeDepth,
          options.timeoutMillis,
          options.mode,
          options.evaluate,
          (event) => {
            void emit(event);
          },
        );
      } finally {
        emit[releaseProxy]();
      }
    },
  };
}

export type WorkerApi = ReturnType<typeof createWorkerApi>;
expose(createWorkerApi());
