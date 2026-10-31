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
      const markers = result.analysis?.unresolvedTerm?.filter((part) => part.kind === 'goal') ?? [];
      for (const goal of result.goals ?? []) {
        expect(markers).toContainEqual(
          expect.objectContaining({ location: goal.request.location, type: goal.request.type }),
        );
      }
    });
  }

  test('editor analysis uses the compiler and retains names', () => {
    const result = analyze('let original: Int = 21 in original');
    expect(result.type).toBe('Int');
    expect(result.unresolvedTerm).toContainEqual(
      expect.objectContaining({ kind: 'text', text: 'let original: Int = ' }),
    );
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

  test('unresolved goals retain binder names and remain distinct across identical expected types', async () => {
    const source = 'instance seed: Int = 41\nlet a = summon[Int] in (summon[Int] + summon[Int])';
    const parts = analyze(source).unresolvedTerm!;
    const markers = parts.filter((part) => part.kind === 'goal');
    expect(markers.map((part) => part.name)).toEqual(['a', 'Int', 'Int1']);
    expect(markers.map((part) => part.type)).toEqual(['Int', 'Int', 'Int']);
    const result = await runner.run(source, 4, 4, 10_000, 'smt', true, () => {});
    expect(result.value).toBe('82');
    expect(result.goals?.map((goal) => goal.request.location)).toEqual(markers.map((part) => part.location));
  });

  test('record goal locations survive display sorting, lexical scopes, and partial resolution', async () => {
    const source = `instance seed: Int = 41
{ zebra = summon[Int], alpha = summon[String], middle = summon[Int],
  last = summon[Int], first = summon[Int], beta = summon[Int] }`;
    const markers = analyze(source).unresolvedTerm!.filter((part) => part.kind === 'goal');
    expect(markers.map((part) => part.name)).toEqual(['alpha', 'beta', 'first', 'last', 'middle', 'zebra']);
    const result = await runner.run(source, 1, 2, 10_000, 'exact', true, () => {});
    expect(result.ok).toBe(false);
    for (const goal of result.goals!) {
      expect(markers).toContainEqual(
        expect.objectContaining({ location: goal.request.location, type: goal.request.type }),
      );
    }
    expect(result.goals!.at(-1)?.request.location).toEqual(markers.find((part) => part.name === 'alpha')?.location);
    expect(result.analysis?.unresolvedTerm).toEqual(analyze(source).unresolvedTerm);
  });

  test('goal names retain trait origins through inlining and applications', () => {
    const source = `trait ToString[A] = { toString: A -> String }
implement ToString[String] = { toString = (text: String) => text }
def display[A where ToString[A]](x: A): String = x.toString
display[String]("Ada")`;
    const result = analyze(source);
    expect(result.ok).toBe(true);
    expect(result.unresolvedTerm!.filter((part) => part.kind === 'goal').map((part) => part.name)).toEqual([
      'ToString',
      'ToString1',
    ]);
    const qualified = analyze('trait Show = { value: Int }\nShow::value');
    expect(qualified.unresolvedTerm).toContainEqual(expect.objectContaining({ kind: 'goal', name: 'Show' }));
  });

  test('implicit parameter names survive type application, aliases, record selection, and partial application', () => {
    const result = analyze(`def counted[A](implicit count: Int)(value: A): A = value
let alias = counted[Int]
let functions = { run = alias }
functions.run(41)`);
    expect(result.ok).toBe(true);
    expect(result.unresolvedTerm).toContainEqual(expect.objectContaining({ kind: 'goal', name: 'count', type: 'Int' }));
    const partial = analyze(`def named(x: Int, implicit suffix: String)(y: Int): Int = y
let applied = named(1)
applied(2)`);
    expect(partial.ok).toBe(true);
    expect(partial.unresolvedTerm).toContainEqual(
      expect.objectContaining({ kind: 'goal', name: 'suffix', type: 'String' }),
    );
  });
});
