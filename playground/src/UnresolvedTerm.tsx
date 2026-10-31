import { useEffect, useLayoutEffect, useRef, useState } from 'react';
import type { GoalLocation, GoalResult, TermDocument, TermGoal } from '../bridge/protocol';
import { Badge, Group, Popover, Tooltip } from '@mantine/core';
import { useReducedMotion } from '@mantine/hooks';
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
  const selectionAnchor = useRef<{ location: GoalLocation; top: number } | null>(null);
  const previewTimer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  const [columns, setColumns] = useState(60);
  const [collapsed, setCollapsed] = useState<GoalLocation[]>([]);
  const [preview, setPreview] = useState<GoalLocation | null>(null);
  const reducedMotion = useReducedMotion();
  const selectedLocation = goals[selected]?.request.location;

  function previewGoal(location: GoalLocation, immediate = false) {
    clearTimeout(previewTimer.current);
    if (immediate || preview) setPreview(location);
    else previewTimer.current = setTimeout(() => setPreview(location), 180);
  }

  function dismissPreview(delay = 120) {
    clearTimeout(previewTimer.current);
    if (delay === 0) setPreview(null);
    else previewTimer.current = setTimeout(() => setPreview(null), delay);
  }

  function activateGoal(index: number, location: GoalLocation, marker: HTMLSpanElement) {
    if (index < 0) {
      previewGoal(location, true);
      return;
    }
    dismissPreview(0);
    if (selected === index) return;
    selectionAnchor.current = { location: goals[index].request.location, top: marker.getClientRects()[0].top };
    onSelect(index);
  }

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

  useEffect(() => {
    setCollapsed([]);
    setPreview(null);
    selectionAnchor.current = null;
    clearTimeout(previewTimer.current);
    return () => clearTimeout(previewTimer.current);
  }, [document]);
  useEffect(() => {
    if (!preview) return;
    const dismiss = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        clearTimeout(previewTimer.current);
        setPreview(null);
      }
    };
    window.addEventListener('keydown', dismiss, true);
    return () => window.removeEventListener('keydown', dismiss, true);
  }, [preview]);
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
    const anchor = selectionAnchor.current;
    selectionAnchor.current = null;
    if (anchor && selectedLocation && sameGoal(anchor.location, selectedLocation)) {
      // Keep the activated occurrence in place when the previous annotation closes above it.
      viewport.scrollTop += marker.getClientRects()[0].top - anchor.top;
    }
    const bounds = viewport.getBoundingClientRect();
    const goal = marker.getBoundingClientRect();
    let offset = 0;
    if (goal.height > bounds.height - 24 || goal.top < bounds.top + 12) offset = goal.top - bounds.top - 12;
    else if (goal.bottom > bounds.bottom - 12) offset = goal.bottom - bounds.bottom + 12;
    if (offset) viewport.scrollTo({ top: viewport.scrollTop + offset, behavior: reducedMotion ? 'instant' : 'smooth' });
  }, [document, selectedLocation, collapsed, columns, reducedMotion]);

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
        <pre className="unresolved-code" ref={container} onScroll={() => dismissPreview(0)}>
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
            const active = goalIndex >= 0 && selected === goalIndex;
            const previewing = preview !== null && sameGoal(part.location, preview);
            const name = `?${part.name}`;
            const outcome = report?.term
              ? `${name} ≔ ${report.term}`
              : (report?.diagnostic?.message ?? (report ? 'Searching…' : 'Synthesis has not reached this goal.'));
            return (
              <Popover
                key={`goal-${part.name}`}
                opened={previewing}
                onDismiss={() => dismissPreview(0)}
                position="top-start"
                offset={10}
                width={380}
                withRoles={false}
                transitionProps={{
                  transition: {
                    in: { opacity: 1, transform: 'translateY(0)' },
                    out: { opacity: 0, transform: 'translateY(4px)' },
                    transitionProperty: 'opacity, transform',
                  },
                  duration: reducedMotion ? 0 : 140,
                  exitDuration: 0,
                  timingFunction: 'cubic-bezier(0.16, 1, 0.3, 1)',
                }}
              >
                <span
                  role="button"
                  tabIndex={0}
                  className="term-goal"
                  ref={active ? selectedMarker : undefined}
                  aria-label={`Inspect goal ${fullLabel}`}
                  aria-pressed={active}
                  aria-disabled={goalIndex < 0}
                  aria-describedby={previewing ? `goal-preview-${part.name}` : undefined}
                  data-previewing={previewing}
                  onPointerEnter={(event) => {
                    if (event.pointerType !== 'touch') previewGoal(part.location);
                  }}
                  onPointerLeave={() => dismissPreview()}
                  onFocus={(event) => {
                    if (event.currentTarget.matches(':focus-visible')) previewGoal(part.location, true);
                  }}
                  onBlur={() => dismissPreview(0)}
                  onClick={(event) => activateGoal(goalIndex, part.location, event.currentTarget)}
                  onKeyDown={(event) => {
                    if (event.key === 'Enter' || event.key === ' ') {
                      event.preventDefault();
                      activateGoal(goalIndex, part.location, event.currentTarget);
                    } else if (event.key === 'Escape') dismissPreview(0);
                  }}
                >
                  <Popover.Target>
                    <span className="term-goal-name">{name}</span>
                  </Popover.Target>
                  {active && <span className="term-goal-annotation">{part.text.slice(name.length)}</span>}
                </span>
                <Popover.Dropdown
                  id={`goal-preview-${part.name}`}
                  role="tooltip"
                  aria-label={`${fullLabel} ${outcome}`}
                  className="goal-preview"
                  onPointerEnter={() => previewGoal(part.location, true)}
                  onPointerLeave={() => dismissPreview()}
                >
                  <div className="goal-preview-heading">
                    <span>Expected type</span>
                    <span className="goal-preview-status" data-outcome={report?.term ? 'resolved' : 'pending'}>
                      {report?.term
                        ? 'Resolved'
                        : report?.diagnostic
                          ? 'Unresolved'
                          : report
                            ? 'Searching'
                            : 'Not reached'}
                    </span>
                  </div>
                  <pre className="goal-preview-type">{termText(goalAnnotation(part), 44)}</pre>
                  <div className="goal-preview-result">
                    {report?.term && <span className="goal-preview-label">Synthesized evidence</span>}
                    <code>{outcome}</code>
                  </div>
                </Popover.Dropdown>
              </Popover>
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
        <div className="goal-result" role="status" aria-live="polite" aria-atomic="true">
          <div key={goalName(document, goals[selected].request.location)} className="goal-result-content">
            {goals[selected].synthesis.term ? (
              <code>
                {goalName(document, goals[selected].request.location)} ≔ {goals[selected].synthesis.term}
              </code>
            ) : (
              (goals[selected].synthesis.diagnostic?.message ?? 'Searching…')
            )}
          </div>
        </div>
      )}
    </section>
  );
}
