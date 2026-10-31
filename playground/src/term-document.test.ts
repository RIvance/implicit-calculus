import { describe, expect, test } from 'vitest';
import { analyze } from '@implicit/compiler';
import { documentGoals, goalAnnotation, goalText, layoutTerm, termText } from './term-document';

describe('mathematical term presentation', () => {
  test('precedence preserves application, projection, arithmetic, and function types', () => {
    for (const [source, expected] of [
      ['(x: Int) => x - (x - 1)', 'λ(x: Int). x - (x - 1)'],
      ['(x: Int) => (x + 1) * 2', 'λ(x: Int). (x + 1) * 2'],
      ['(f: Int -> Int) => f(1 + 2)', 'λ(f: Int → Int). f (1 + 2)'],
      ['(r: { x: Int }) => r.x + 1', 'λ(r: {x: Int}). r.x + 1'],
      ['(f: (Int -> Int) -> Int) => f(x => x)', 'λ(f: (Int → Int) → Int). f (λ(x: Int). x)'],
    ]) {
      const analysis = analyze(source);
      expect(analysis.diagnostic).toBeUndefined();
      expect(termText(analysis.unresolvedTerm!, 120)).toBe(expected);
    }
    expect(analyze('(f: (Int -> Int) -> Int) => f').type).toBe('((Int → Int) → Int) → (Int → Int) → Int');
  });

  test('narrow layouts break at structure, preserve goals, and leave the document intact', () => {
    const document = analyze(`instance seed: Int = 1
let transform = (value: { first: Int, second: Int }) =>
  { first = value.first + summon[Int], second = value.second + summon[Int] }
transform({ first = 20, second = 40 })`).unresolvedTerm!;
    const retained = structuredClone(document);
    const wide = termText(document, 120);
    const narrow = termText(document, 40);
    expect(narrow.split('\n').length).toBeGreaterThan(wide.split('\n').length);
    expect(narrow.split('\n').every((line) => line.length <= 40)).toBe(true);
    expect(narrow.replace(/\s+/g, '')).toBe(wide.replace(/\s+/g, ''));
    expect(narrow).toContain('\n  ');
    for (const columns of [30, 60, 120]) {
      const fragments = layoutTerm(document, {
        columns,
        goal: (goal) => ({ kind: 'text', text: `?${goal.name}` }),
      });
      expect(fragments.filter((part) => part.kind === 'goal')).toEqual(
        documentGoals(document).map((goal) => expect.objectContaining(goal)),
      );
    }
    expect(document).toEqual(retained);
  });

  test('folds hide binding values without hiding later goals or losing copyable contents', () => {
    const document = analyze('let first = summon[Int] in summon[String]').unresolvedTerm!;
    const goals = documentGoals(document);
    const folded = layoutTerm(document, {
      columns: 60,
      goal: (goal) => ({ kind: 'text', text: `?${goal.name}` }),
      collapsed: [[]],
    });
    expect(folded.filter((part) => part.kind === 'goal')).toEqual([expect.objectContaining(goals[1])]);
    expect(folded).toContainEqual(expect.objectContaining({ kind: 'fold', collapsed: true, goalCount: 1 }));
    expect(termText(document)).toContain('?first : Int');
    expect(termText(document)).toContain('?δ₁ : String');
  });

  test('abstraction spines keep their binders together and preserve their scopes', () => {
    const document = analyze(`def apply[A](implicit default: A)(f: A -> A): A = f(default)
apply`).unresolvedTerm!;
    const printed = termText(document, 60);
    expect(printed).toContain('ΛA. λ{default: A}. λ(f: A → A).');
    expect(printed).toContain('f default\nin\napply');
    const shadowed = analyze('(x: Int) => (x: Int) => (y: Int) => x - y').unresolvedTerm!;
    expect(termText(shadowed)).toBe('λ(x: Int). λ(x1: Int). λ(y: Int). x1 - y');
  });

  test('multiline goal annotations retain type grouping in the term and in isolation', () => {
    const document = analyze('summon[{ call: (Int -> Int) -> Int, seed: Int }].call(x => x)').unresolvedTerm!;
    const goal = documentGoals(document)[0];
    const compact = goalText(goal);
    const expanded = termText(goalAnnotation(goal), 32);
    expect(expanded).toContain('\n');
    expect(expanded.split('\n').every((line) => line.length <= 32)).toBe(true);
    expect(expanded.replace(/\s+/g, '')).toBe(compact.replace(/\s+/g, ''));
    const printed = termText(document, 36);
    expect(printed).toContain('(?δ₁ :\n');
    expect(printed).toContain('(Int → Int) → Int');
    expect(printed).toContain('}).call');
    expect(printed).toContain('(λ(x: Int). x)');
    const interactive = layoutTerm(document, { columns: 36, goal: goalAnnotation });
    const displayed = interactive.map((part) => (part.kind === 'fold' ? '' : part.text)).join('');
    expect(displayed.split('\n').every((line) => line.length <= 36)).toBe(true);
    expect(displayed).toContain('}.call');
    expect(interactive.filter((part) => part.kind === 'goal')).toEqual([expect.objectContaining(goal)]);
  });

  test('application arguments form one continuation block when an application breaks', () => {
    const document = analyze('(f: Int -> Int -> Int -> Int) => f(123456, 234567, 345678)').unresolvedTerm!;
    const printed = termText(document, 20);
    expect(termText(document, 30)).toContain('f 123456 234567 345678');
    expect(printed).toContain('\n  f\n    123456\n    234567\n    345678');
    expect(printed.replace(/\s+/g, '')).toBe(termText(document, 120).replace(/\s+/g, ''));
  });
});
