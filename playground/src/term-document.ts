import type { GoalLocation, TermDocument, TermGoal } from '../bridge/protocol';

export function sameGoal(left: GoalLocation, right: GoalLocation): boolean {
  return (
    left.length === right.length &&
    left.every((step, index) => {
      const other = right[index];
      return typeof step === 'string' ? step === other : typeof other !== 'string' && step.field === other.field;
    })
  );
}

export function documentGoals(document: TermDocument): TermGoal[] {
  switch (document.kind) {
    case 'goal':
      return [document];
    case 'sequence':
      return document.parts.flatMap(documentGoals);
    case 'nest':
    case 'group':
    case 'fold':
      return documentGoals(document.body);
    default:
      return [];
  }
}

export function goalName(document: TermDocument | undefined, location: GoalLocation): string {
  const goal = document && documentGoals(document).find((goal) => sameGoal(goal.location, location));
  return goal ? `?${goal.name}` : '?';
}

type Fold = Extract<TermDocument, { kind: 'fold' }>;
export type TermFragment =
  | Extract<TermDocument, { kind: 'text' }>
  | (TermGoal & { text: string })
  | { kind: 'fold'; document: Fold; collapsed: boolean; goalCount: number; column: number };

interface LayoutOptions {
  columns: number;
  goal: (goal: TermGoal) => TermDocument;
  collapsed?: GoalLocation[];
}

/** Goal annotations participate in line breaking while retaining their interactive occurrence boundaries. */
export function layoutTerm(document: TermDocument, options: LayoutOptions): TermFragment[] {
  type GoalFragment = Extract<TermFragment, { kind: 'goal' }>;
  type Frame = { document: TermDocument; indentation: number; flat: boolean; goal?: GoalFragment };
  const collapsed = (document: Fold) => options.collapsed?.some((path) => sameGoal(path, document.location)) ?? false;
  const foldBody = (document: Fold) => (collapsed(document) ? document.summary : document.body);

  function fits(remaining: number, frames: Frame[]): boolean {
    const pending = [...frames];
    while (remaining >= 0 && pending.length) {
      const frame = pending.pop()!;
      const value = frame.document;
      switch (value.kind) {
        case 'text':
          remaining -= Array.from(value.text).length;
          break;
        case 'goal':
          pending.push({ ...frame, document: options.goal(value) });
          break;
        case 'line':
          if (!frame.flat) return true;
          if (value.flat === null) return false;
          remaining -= value.flat.length;
          break;
        case 'sequence':
          for (let index = value.parts.length - 1; index >= 0; index--)
            pending.push({ ...frame, document: value.parts[index] });
          break;
        case 'nest':
        case 'group':
          pending.push({ ...frame, document: value.body });
          break;
        case 'fold':
          pending.push({ ...frame, document: foldBody(value) });
          break;
      }
    }
    return remaining >= 0;
  }

  const pending: Frame[] = [{ document, indentation: 0, flat: false }];
  const result: TermFragment[] = [];
  let column = 0;
  function text(value: string, goal?: GoalFragment) {
    if (goal) goal.text += value;
    else {
      const previous = result.at(-1);
      if (previous?.kind === 'text') previous.text += value;
      else result.push({ kind: 'text', text: value });
    }
  }
  while (pending.length) {
    const frame = pending.pop()!;
    const value = frame.document;
    switch (value.kind) {
      case 'text':
        text(value.text, frame.goal);
        column += Array.from(value.text).length;
        break;
      case 'goal': {
        const goal: GoalFragment = { ...value, text: '' };
        result.push(goal);
        pending.push({ ...frame, document: options.goal(value), goal });
        break;
      }
      case 'line':
        if (frame.flat && value.flat !== null) {
          text(value.flat, frame.goal);
          column += value.flat.length;
        } else {
          text('\n' + ' '.repeat(frame.indentation), frame.goal);
          column = frame.indentation;
        }
        break;
      case 'sequence':
        for (let index = value.parts.length - 1; index >= 0; index--)
          pending.push({ ...frame, document: value.parts[index] });
        break;
      case 'nest':
        pending.push({ ...frame, document: value.body, indentation: frame.indentation + 2 });
        break;
      case 'group': {
        const flat =
          frame.flat || fits(options.columns - column, [...pending, { ...frame, document: value.body, flat: true }]);
        pending.push({ ...frame, document: value.body, flat });
        break;
      }
      case 'fold':
        result.push({
          kind: 'fold',
          document: value,
          collapsed: collapsed(value),
          goalCount: documentGoals(value.body).length,
          column,
        });
        pending.push({ ...frame, document: foldBody(value) });
        break;
    }
  }
  return result;
}

export function termText(document: TermDocument, columns = 80): string {
  return layoutTerm(document, {
    columns,
    goal: (goal) => ({
      kind: 'sequence',
      parts: [{ kind: 'text', text: '(' }, goalAnnotation(goal), { kind: 'text', text: ')' }],
    }),
  })
    .map((part) => (part.kind === 'fold' ? '' : part.text))
    .join('');
}

export function goalText(goal: TermGoal): string {
  return termText(goalAnnotation(goal), Infinity);
}

export function goalAnnotation(goal: TermGoal): TermDocument {
  return {
    kind: 'group',
    body: {
      kind: 'sequence',
      parts: [
        { kind: 'text', text: `?${goal.name} :` },
        { kind: 'nest', body: { kind: 'sequence', parts: [{ kind: 'line', flat: ' ' }, goal.annotation] } },
      ],
    },
  };
}
