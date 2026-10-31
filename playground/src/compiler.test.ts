import { afterAll, beforeAll, describe, expect, test } from 'vitest';
import { createRunner, analyze } from '@implicit/compiler';
import { examples } from './examples';
import type { Runner } from '../bridge/protocol';
import { documentGoals, sameGoal, termText } from './term-document';

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
      const markers = result.analysis?.unresolvedTerm ? documentGoals(result.analysis.unresolvedTerm) : [];
      for (const goal of result.goals ?? []) {
        const marker = markers.find((marker) => sameGoal(marker.location, goal.request.location));
        expect(marker).toBeDefined();
        expect(termText(marker!.annotation, Infinity)).toBe(goal.request.type);
      }
    });
  }

  test('editor analysis uses the compiler and retains names', () => {
    const result = analyze('let original: Int = 21 in original');
    expect(result.type).toBe('Int');
    expect(termText(result.unresolvedTerm!)).toContain('let original: Int = 21 in');
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
    const markers = documentGoals(parts);
    expect(markers.map((part) => part.name)).toEqual(['a', 'δ₁', 'δ₂']);
    expect(markers.map((part) => termText(part.annotation, Infinity))).toEqual(['Int', 'Int', 'Int']);
    const result = await runner.run(source, 4, 4, 10_000, 'smt', true, () => {});
    expect(result.value).toBe('82');
    expect(result.goals?.map((goal) => goal.request.location)).toEqual(markers.map((part) => part.location));
  });

  test('record goal locations survive display sorting, lexical scopes, and partial resolution', async () => {
    const source = `instance seed: Int = 41
{ zebra = summon[Int], alpha = summon[String], middle = summon[Int],
  last = summon[Int], first = summon[Int], beta = summon[Int] }`;
    const markers = documentGoals(analyze(source).unresolvedTerm!);
    expect(markers.map((part) => part.name)).toEqual(['alpha', 'beta', 'first', 'last', 'middle', 'zebra']);
    const result = await runner.run(source, 1, 2, 10_000, 'exact', true, () => {});
    expect(result.ok).toBe(false);
    for (const goal of result.goals!) {
      const marker = markers.find((marker) => sameGoal(marker.location, goal.request.location));
      expect(marker).toBeDefined();
      expect(termText(marker!.annotation, Infinity)).toBe(goal.request.type);
    }
    expect(result.goals!.at(-1)?.request.location).toEqual(markers.find((part) => part.name === 'alpha')?.location);
    expect(result.analysis?.unresolvedTerm).toEqual(analyze(source).unresolvedTerm);
  });

  test('unnamed trait requests receive compact names after inlining and applications', () => {
    const source = `trait ToString[A] = { toString: A -> String }
implement ToString[String] = { toString = (text: String) => text }
def display[A where ToString[A]](x: A): String = x.toString
display[String]("Ada")`;
    const result = analyze(source);
    expect(result.ok).toBe(true);
    expect(documentGoals(result.unresolvedTerm!).map((part) => part.name)).toEqual(['δ₁', 'δ₂']);
    const qualified = analyze('trait Show = { value: Int }\nShow::value');
    expect(documentGoals(qualified.unresolvedTerm!)).toContainEqual(expect.objectContaining({ name: 'δ₁' }));
  });

  test('implicit parameter names survive type application, aliases, record selection, and partial application', () => {
    const result = analyze(`def counted[A](implicit count: Int)(value: A): A = value
let alias = counted[Int]
let functions = { run = alias }
functions.run(41)`);
    expect(result.ok).toBe(true);
    expect(documentGoals(result.unresolvedTerm!)).toContainEqual(
      expect.objectContaining({ kind: 'goal', name: 'count' }),
    );
    const partial = analyze(`def named(x: Int, implicit suffix: String)(y: Int): Int = y
let applied = named(1)
applied(2)`);
    expect(partial.ok).toBe(true);
    expect(documentGoals(partial.unresolvedTerm!)).toContainEqual(
      expect.objectContaining({ kind: 'goal', name: 'suffix' }),
    );
  });
});
