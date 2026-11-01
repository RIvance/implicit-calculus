import { afterAll, beforeAll, describe, expect, test } from 'vitest';
import { createRunner, analyze } from '@implicit/compiler';
import { renderToString } from 'katex';
import { examples } from './examples';
import type { Constraint, MathFormula, Runner } from '../bridge/protocol';
import { documentGoals, sameGoal, termText } from './term-document';

let runner: Runner;
function formulas(constraint: Constraint): MathFormula[] {
  const statement = constraint.formula;
  return statement ? (statement.premise ? [statement.premise, statement.conclusion] : [statement.conclusion]) : [];
}
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
        for (const attempt of goal.synthesis.attempts) {
          expect(attempt.constraints.filter((value) => value.rule === 'Goal' && value.formula)).toHaveLength(1);
          expect(attempt.assumptions).toHaveLength(1);
          for (const constraint of [...attempt.constraints, ...attempt.refinements]) {
            for (const formula of formulas(constraint)) {
              expect(formula.text).not.toMatch(/\b(?:Forall|Record|Arrow|Implicit|Variable|Field|Empty)\(/);
              expect(formula.text).not.toMatch(/\b(?:type_argument|implicit_argument|well_formed)\(/);
              expect(() => renderToString(formula.latex, { strict: 'error', trust: false })).not.toThrow();
            }
          }
        }
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

  test('constraint notation retains quantified scope, named fields, and symbolic type arguments', async () => {
    const result = await runner.run(
      `instance seed: Int = 73
instance lift[A, B](implicit input: A): { call_with: B -> A } = { call_with = (value: B) => input }
summon[{ call_with: String -> Int }].call_with("hello")`,
      6,
      4,
      10_000,
      'smt',
      true,
      () => {},
    );
    expect(result.value).toBe('73');
    const constraints = result.goals![0].synthesis.attempts.at(-1)!.constraints;
    const text = constraints
      .flatMap(formulas)
      .map((value) => value.text)
      .join('\n');
    const latex = constraints
      .flatMap(formulas)
      .map((value) => value.latex)
      .join('\n');
    expect(text).toContain('∀A. ∀A₁. A ⇒ {call_with: A₁ → A}');
    expect(text).toContain('τ₀ = ∀A. B₀,₀ ⇒ {call_with: A → B₀,₀}');
    expect(text).toContain('h₀ = lift ∧ ℓ₀ = 1');
    expect(text).toContain('h₀ ∈ Δ ∧ 0 ≤ ℓ₀ < 6');
    expect(latex).toContain('B_{0,0}');
    expect(latex).toContain('\\tau_{0}');
    expect(latex).toContain('\\mathtt{call\\_with}');
    expect(latex).toContain('\\mathit{A}_{1}');
    expect(latex).toContain('\\Rightarrow');
    expect(constraints.some((value) => value.formula === null)).toBe(true);
    expect(result.goals![0].synthesis.attempts.at(-1)!.assumptions).toEqual([{ text: 'r = 1', latex: 'r = 1' }]);
    expect(constraints.some((value) => value.smtlib.includes('(Forall (Forall (Implicit'))).toBe(true);
  });

  test('constraint notation preserves ambient type names across streamed and completed reports', async () => {
    const streamed: string[] = [];
    const result = await runner.run(
      `instance pack[A](implicit input: A): { item: A } = { item = input }
def wrapped[User](implicit input: User): { item: User } = summon[{ item: User }]
wrapped`,
      4,
      4,
      10_000,
      'smt',
      false,
      (event) => {
        if (event.kind === 'attempt')
          streamed.push(...event.attempt.constraints.flatMap(formulas).map((value) => value.latex));
      },
    );
    expect(result.ok).toBe(true);
    const constraints = result.goals![0].synthesis.attempts.flatMap((attempt) => attempt.constraints);
    expect(constraints.flatMap(formulas).map((value) => value.latex)).toEqual(streamed);
    expect(constraints.flatMap(formulas).some((value) => value.text.includes('{item: User}'))).toBe(true);
    expect(constraints.flatMap(formulas).every((value) => !value.text.includes('^'))).toBe(true);
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
