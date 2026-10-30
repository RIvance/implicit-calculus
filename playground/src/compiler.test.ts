import { afterAll, beforeAll, describe, expect, test } from 'vitest';
import { createRunner, analyze } from '@implicit/compiler';
import { examples } from './examples';
import type { Runner } from '../bridge/protocol';

let runner: Runner;
beforeAll(async () => {
  runner = await createRunner();
});
afterAll(() => {
  runner?.close();
});

describe('Scala.js compiler with WebAssembly synthesis', () => {
  for (const example of examples) {
    test(example.path, async () => {
      const result = await runner.run(example.source, 16, 8, 10_000, 'smt', true, () => {});
      if (example.expectation === 'result') {
        expect(result.diagnostic).toBeNull();
        expect(result.value).toBe(example.expected);
      } else {
        expect(result.ok).toBe(false);
        expect(result.diagnostic?.code).toBe(example.expected.split('(')[0]);
      }
    });
  }

  test('editor analysis uses the compiler and retains names', () => {
    const result = analyze('let original: Int = 21 in original');
    expect(result.type).toBe('Int');
    expect(result.implicitTerm).toContain('let original:');
    expect(analyze('let value =').diagnostic?.position).toBeTruthy();
  });

  test('real attempts expose models and evidence rather than example annotations', async () => {
    const source = `instance value: Int = 73
instance wrap[A](implicit input: A): { item: A } = { item = input }
summon[{ item: Int }]`;
    const result = await runner.run(source, 4, 4, 10_000, 'smt', true, () => {});
    expect(result.value).toBe('{item = 73}');
    const synthesis = result.goals![0].synthesis;
    expect(synthesis.attempts[0].status).toBe('unsat');
    expect(synthesis.attempts.at(-1)?.model?.values.length).toBeGreaterThan(0);
    expect(synthesis.evidence?.cost).toBeLessThanOrEqual(4);
    expect(result.goals![0].validated).toBe(true);
    const baseline = await runner.run(source, 4, 4, 10_000, 'exact', true, () => {});
    expect(baseline.diagnostic?.code).toBe('MissingInstance');
  });
});
