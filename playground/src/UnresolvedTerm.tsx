import type { GoalLocation, GoalResult, TermPart } from '../bridge/protocol';
import { Tooltip } from '@mantine/core';
import { CopyButton } from './Inspectors';

export function sameGoal(left: GoalLocation, right: GoalLocation): boolean {
  return (
    left.length === right.length &&
    left.every((step, index) => {
      const other = right[index];
      return typeof step === 'string' ? step === other : typeof other !== 'string' && step.field === other.field;
    })
  );
}

export function goalName(parts: TermPart[] | undefined, location: GoalLocation): string {
  const part = parts?.find((part) => part.kind === 'goal' && sameGoal(part.location, location));
  return part?.kind === 'goal' ? `?${part.name}` : '?';
}

export function UnresolvedTerm({
  parts,
  goals,
  selected,
  onSelect,
}: {
  parts: TermPart[];
  goals: GoalResult[];
  selected: number;
  onSelect: (index: number) => void;
}) {
  const text = parts.map((part) => (part.kind === 'text' ? part.text : `?${part.name} : ${part.type}`)).join('');
  return (
    <section className="unresolved-term" aria-label="Unresolved term">
      <div className="unresolved-heading">
        <h3>Unresolved term</h3>
        <CopyButton text={text} />
      </div>
      <pre className="math-block">
        {parts.map((part, index) => {
          if (part.kind === 'text') return part.text;
          const goalIndex = goals.findIndex((goal) => sameGoal(part.location, goal.request.location));
          const label = `?${part.name} : ${part.type}`;
          const report = goals[goalIndex]?.synthesis;
          const outcome = report?.term
            ? `?${part.name} ≔ ${report.term}`
            : (report?.diagnostic?.message ?? (report ? 'Searching…' : 'Synthesis has not reached this goal.'));
          return (
            <Tooltip
              key={index}
              label={outcome}
              multiline
              maw={440}
              withArrow
              events={{ hover: true, focus: true, touch: true }}
            >
              <button
                className="term-goal"
                aria-label={`Inspect goal ${label}`}
                aria-pressed={goalIndex >= 0 && selected === goalIndex}
                aria-disabled={goalIndex < 0}
                onClick={() => {
                  if (goalIndex >= 0) onSelect(goalIndex);
                }}
              >
                {label} <span aria-hidden="true">{report?.ok ? '✓' : report?.diagnostic ? '×' : '…'}</span>
              </button>
            </Tooltip>
          );
        })}
      </pre>
      {goals[selected] && (
        <div className="goal-result" role="status">
          {goals[selected].synthesis.term ? (
            <code>
              {goalName(parts, goals[selected].request.location)} ≔ {goals[selected].synthesis.term}
            </code>
          ) : (
            (goals[selected].synthesis.diagnostic?.message ?? 'Searching…')
          )}
        </div>
      )}
    </section>
  );
}
