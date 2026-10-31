import { useEffect, useLayoutEffect, useRef, useState } from 'react';
import type { GoalLocation, GoalResult, TermDocument, TermGoal } from '../bridge/protocol';
import { Badge, Group, Tooltip } from '@mantine/core';
import { ChevronDown, ChevronRight, Code2 } from 'lucide-react';
import { CopyButton } from './Inspectors';
import { documentGoals, goalAnnotation, goalName, goalText, layoutTerm, sameGoal, termText } from './term-document';

export function UnresolvedTerm({
  document,
  goals,
  selected,
  stale,
  onSelect,
}: {
  document?: TermDocument;
  goals: GoalResult[];
  selected: number;
  stale: boolean;
  onSelect: (index: number) => void;
}) {
  const container = useRef<HTMLPreElement>(null);
  const selectedMarker = useRef<HTMLSpanElement>(null);
  const [columns, setColumns] = useState(60);
  const [collapsed, setCollapsed] = useState<GoalLocation[]>([]);
  const selectedLocation = goals[selected]?.request.location;

  useEffect(() => {
    const element = container.current;
    if (!element) return;
    const context = window.document.createElement('canvas').getContext('2d')!;
    const measure = () => {
      const style = getComputedStyle(element);
      context.font = style.font;
      const width = element.clientWidth - parseFloat(style.paddingLeft) - parseFloat(style.paddingRight);
      setColumns(Math.max(12, Math.floor(width / context.measureText('0').width)));
    };
    const observer = new ResizeObserver(measure);
    observer.observe(element);
    measure();
    return () => observer.disconnect();
  }, [document]);

  useEffect(() => setCollapsed([]), [document]);
  useEffect(() => {
    if (!document || !selectedLocation) return;
    function containsSelected(document: TermDocument, location: GoalLocation): boolean {
      switch (document.kind) {
        case 'fold':
          return (
            (sameGoal(document.location, location) &&
              documentGoals(document.body).some((goal) => sameGoal(goal.location, selectedLocation!))) ||
            containsSelected(document.body, location)
          );
        case 'sequence':
          return document.parts.some((part) => containsSelected(part, location));
        case 'nest':
        case 'group':
          return containsSelected(document.body, location);
        default:
          return false;
      }
    }
    setCollapsed((previous) => previous.filter((location) => !containsSelected(document, location)));
  }, [document, selectedLocation]);

  useLayoutEffect(() => {
    const viewport = container.current;
    const marker = selectedMarker.current;
    if (!viewport || !marker) return;
    const bounds = viewport.getBoundingClientRect();
    const goal = marker.getBoundingClientRect();
    if (goal.height > bounds.height - 24 || goal.top < bounds.top + 12)
      viewport.scrollTop += goal.top - bounds.top - 12;
    else if (goal.bottom > bounds.bottom - 12) viewport.scrollTop += goal.bottom - bounds.bottom + 12;
  }, [document, selectedLocation, collapsed, columns]);

  function goalDocument(goal: TermGoal): TermDocument {
    return selectedLocation && sameGoal(goal.location, selectedLocation)
      ? goalAnnotation(goal)
      : { kind: 'text', text: `?${goal.name}` };
  }
  const parts = document
    ? layoutTerm(document, {
        columns,
        goal: goalDocument,
        collapsed,
      })
    : [];

  return (
    <section className="unresolved-term" aria-label="Unresolved term">
      <div className="panel-title">
        <Group gap="xs" wrap="nowrap">
          <Code2 size={16} />
          <strong>Unresolved term</strong>
        </Group>
        <Group gap="xs" wrap="nowrap">
          {stale && (
            <Badge color="orange" size="xs" variant="light">
              previous source
            </Badge>
          )}
          {document && <CopyButton text={termText(document, columns)} />}
        </Group>
      </div>
      {document ? (
        <pre className="unresolved-code" ref={container}>
          {parts.map((part, index) => {
            if (part.kind === 'text') return <span key={`text-${index}`}>{part.text}</span>;
            if (part.kind === 'fold')
              return (
                <span key={`fold-${index}`} className="term-fold-anchor">
                  <Tooltip
                    label={`${part.collapsed ? 'Expand' : 'Fold'} ${part.document.label}${part.goalCount ? ` · ${part.goalCount} goals` : ''}`}
                  >
                    <button
                      className="term-fold"
                      style={{ left: `calc(-${part.column}ch - 25px)` }}
                      aria-label={`${part.collapsed ? 'Expand' : 'Fold'} ${part.document.label}`}
                      aria-expanded={!part.collapsed}
                      onClick={() =>
                        setCollapsed((previous) =>
                          part.collapsed
                            ? previous.filter((path) => !sameGoal(path, part.document.location))
                            : [...previous, part.document.location],
                        )
                      }
                    >
                      {part.collapsed ? <ChevronRight size={13} /> : <ChevronDown size={13} />}
                    </button>
                  </Tooltip>
                </span>
              );
            const goalIndex = goals.findIndex((goal) => sameGoal(part.location, goal.request.location));
            const fullLabel = goalText(part);
            const report = goals[goalIndex]?.synthesis;
            const outcome = report?.term
              ? `?${part.name} ≔ ${report.term}`
              : (report?.diagnostic?.message ?? (report ? 'Searching…' : 'Synthesis has not reached this goal.'));
            return (
              <Tooltip
                key={`goal-${part.name}`}
                label={
                  <>
                    <div>{fullLabel}</div>
                    <div>{outcome}</div>
                  </>
                }
                multiline
                maw={440}
                withArrow
                events={{ hover: true, focus: true, touch: true }}
              >
                <span
                  role="button"
                  tabIndex={0}
                  className="term-goal"
                  data-multiline={part.text.includes('\n')}
                  ref={goalIndex >= 0 && selected === goalIndex ? selectedMarker : undefined}
                  aria-label={`Inspect goal ${fullLabel}`}
                  aria-pressed={goalIndex >= 0 && selected === goalIndex}
                  aria-disabled={goalIndex < 0}
                  onFocus={() => {
                    if (goalIndex >= 0 && selected !== goalIndex) onSelect(goalIndex);
                  }}
                  onClick={() => {
                    if (goalIndex >= 0 && selected !== goalIndex) onSelect(goalIndex);
                  }}
                  onKeyDown={(event) => {
                    if (event.key === 'Enter' || event.key === ' ') {
                      event.preventDefault();
                      if (goalIndex >= 0 && selected !== goalIndex) onSelect(goalIndex);
                    }
                  }}
                >
                  {part.text}
                </span>
              </Tooltip>
            );
          })}
        </pre>
      ) : (
        <div className="empty-state">
          <Code2 size={28} />
          <p>Run a program to inspect its synthesis goals.</p>
        </div>
      )}
      {document && goals[selected] && (
        <div className="goal-result" role="status">
          {goals[selected].synthesis.term ? (
            <code>
              {goalName(document, goals[selected].request.location)} ≔ {goals[selected].synthesis.term}
            </code>
          ) : (
            (goals[selected].synthesis.diagnostic?.message ?? 'Searching…')
          )}
        </div>
      )}
    </section>
  );
}
