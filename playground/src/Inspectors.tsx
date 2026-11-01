import { useMemo, useState } from 'react';
import { Accordion, Badge, Button, Group, Pagination, SegmentedControl, Select, Table, TextInput } from '@mantine/core';
import { Copy, Download, Search, Table2 } from 'lucide-react';
import { renderToString } from 'katex';
import type { Attempt, Constraint, MathFormula, ModelValue } from '../bridge/protocol';
import { download } from './document';
import { subscript } from './EvidenceGraph';

function Mathematics({ latex }: { latex: string }) {
  const html = renderToString(latex, { trust: false, strict: 'error', output: 'htmlAndMathml' });
  return <div className="constraint-math" dangerouslySetInnerHTML={{ __html: html }} />;
}

function statementParts(formula: Constraint['formula']): MathFormula[] {
  return formula ? (formula.premise ? [formula.premise, formula.conclusion] : [formula.conclusion]) : [];
}

function ConstraintFormula({ formula }: { formula: Constraint['formula'] }) {
  if (!formula) return null;
  return (
    <div className="constraint-statement">
      {formula.premise && (
        <div className="constraint-premise">
          <span>When</span>
          <Mathematics latex={formula.premise.latex} />
        </div>
      )}
      <Mathematics latex={formula.conclusion.latex} />
    </div>
  );
}

function ConstraintNotation({ attempt }: { attempt: Attempt }) {
  return (
    <div className="constraint-notation">
      <div className="constraint-query">
        <span>
          {attempt.spineCount} spines · root z{subscript(attempt.spineCount - 1)}
        </span>
        {attempt.assumptions.map((formula, index) => (
          <Mathematics key={index} latex={formula.latex} />
        ))}
      </div>
      <div className="constraint-symbols">
        <span>
          <Mathematics latex={'\\tau_i'} /> type of zᵢ
        </span>
        <span>
          <Mathematics latex="h_i" /> chosen instance
        </span>
        <span>
          <Mathematics latex={'\\ell_i'} /> arguments supplied
        </span>
      </div>
      <details>
        <summary>Argument and type notation</summary>
        <dl>
          <dt>
            <Mathematics latex="a_{i,j}=[B_{i,j}]" />
          </dt>
          <dd>Position j of zᵢ supplies the type Bᵢ,ⱼ. Positions start at 0.</dd>
          <dt>
            <Mathematics latex={'a_{i,j}=\\langle z_{k_{i,j}}\\rangle'} />
          </dt>
          <dd>Position j supplies evidence from the earlier spine kᵢ,ⱼ.</dd>
          <dt>
            <Mathematics latex="T[B]" />
          </dt>
          <dd>Instantiate the outer ∀ of T with B, avoiding capture.</dd>
          <dt>
            <Mathematics latex={'\\operatorname{Poly}(T)'} />
          </dt>
          <dd>T begins with a universal quantifier.</dd>
          <dt>
            <Mathematics latex={'\\operatorname{Valid}_{q,H}(B)'} />
          </dt>
          <dd>B is well formed under q type binders, has height at most H, and uses this query’s record labels.</dd>
          <dt>
            <Mathematics latex={'\\tau_{i,j}^{f}'} />
          </dt>
          <dd>Residual type after j arguments when zᵢ starts from f.</dd>
          <dt>
            <Mathematics latex={'\\uparrow_c^d T'} />
          </dt>
          <dd>Raise the free type indices in T by d above cutoff c, preserving locally bound variables.</dd>
          <dt>
            <Mathematics latex={'\\bot'} />
          </dt>
          <dd>This combination of choices is impossible.</dd>
        </dl>
      </details>
    </div>
  );
}

export function CopyButton({ text, label = 'Copy' }: { text: string; label?: string }) {
  const [status, setStatus] = useState<'idle' | 'copied' | 'failed'>('idle');
  return (
    <Button
      variant="subtle"
      size="compact-xs"
      leftSection={<Copy size={13} />}
      onClick={async () => {
        try {
          await navigator.clipboard.writeText(text);
          setStatus('copied');
        } catch {
          setStatus('failed');
        }
        setTimeout(() => setStatus('idle'), 1800);
      }}
    >
      {status === 'copied' ? 'Copied' : status === 'failed' ? 'Copy unavailable' : label}
    </Button>
  );
}

export function ConstraintExplorer({
  attempt,
  spine,
  setSpine,
}: {
  attempt: Attempt;
  spine: number | null;
  setSpine: (index: number | null) => void;
}) {
  const [search, setSearch] = useState('');
  const [rule, setRule] = useState<string | null>(null);
  const [notation, setNotation] = useState('math');
  const [page, setPage] = useState(1);
  const filtered = useMemo(
    () =>
      attempt.constraints.filter((constraint) => {
        const text = `${statementParts(constraint.formula)
          .map((part) => part.text)
          .join(' ')} ${constraint.smtlib} ${constraint.explanation} ${constraint.rule}`.toLowerCase();
        return (
          (notation === 'smt' || constraint.formula !== null) &&
          (!rule || constraint.rule === rule) &&
          (spine === null || constraint.spine === spine) &&
          text.includes(search.toLowerCase())
        );
      }),
    [attempt, rule, spine, search, notation],
  );
  const currentPage = Math.min(page, Math.max(1, Math.ceil(filtered.length / 25)));
  return (
    <div className="inspector">
      {notation === 'math' && <ConstraintNotation attempt={attempt} />}
      <div className="inspector-toolbar">
        <TextInput
          aria-label="Search constraints"
          placeholder="Search constraints…"
          value={search}
          onChange={(event) => {
            setSearch(event.currentTarget.value);
            setPage(1);
          }}
          leftSection={<Search size={14} />}
        />
        <Select
          aria-label="Constraint rule"
          placeholder="All rules"
          clearable
          value={rule}
          onChange={(value) => {
            setRule(value);
            setPage(1);
          }}
          data={[...new Set(attempt.constraints.map((value) => value.rule))]}
        />
        <Select
          aria-label="Constraint spine"
          placeholder="All spines"
          clearable
          value={spine === null ? null : String(spine)}
          onChange={(value) => {
            setSpine(value === null ? null : Number(value));
            setPage(1);
          }}
          data={Array.from({ length: attempt.spineCount }, (_, index) => ({
            value: String(index),
            label: `z${subscript(index)}`,
          }))}
        />
      </div>
      <Group justify="space-between" mb="md">
        <span className="muted small">
          {filtered.length} of{' '}
          {attempt.constraints.filter((value) => notation === 'smt' || value.formula !== null).length} constraints
        </span>
        <SegmentedControl
          size="xs"
          value={notation}
          onChange={setNotation}
          data={[
            { value: 'math', label: 'Mathematics' },
            { value: 'smt', label: 'SMT-LIB' },
          ]}
        />
      </Group>
      <div className="constraint-list">
        {filtered.slice((currentPage - 1) * 25, currentPage * 25).map((constraint) => (
          <article className="constraint-card" key={constraint.id}>
            <Group justify="space-between" gap="xs">
              <Group gap="xs">
                <span className="constraint-number">{constraint.id + 1}</span>
                <Badge variant="light" size="xs" color={constraint.rule === 'TypeValidity' ? 'orange' : 'blue'}>
                  {constraint.rule}
                </Badge>
                {constraint.spine !== null && <span className="muted small">z{subscript(constraint.spine)}</span>}
              </Group>
              <CopyButton
                text={
                  notation === 'math'
                    ? statementParts(constraint.formula)
                        .map((part) => part.latex)
                        .join(' \\Longrightarrow ')
                    : constraint.smtlib
                }
                label={notation === 'math' ? 'Copy LaTeX' : 'Copy'}
              />
            </Group>
            <p>{constraint.explanation}</p>
            {notation === 'math' ? <ConstraintFormula formula={constraint.formula} /> : <pre>{constraint.smtlib}</pre>}
          </article>
        ))}
        {filtered.length === 0 && (
          <div className="empty-state compact">
            <Search size={24} />
            <p>No constraints match these filters.</p>
          </div>
        )}
      </div>
      {filtered.length > 25 && (
        <Pagination mt="md" size="sm" value={currentPage} onChange={setPage} total={Math.ceil(filtered.length / 25)} />
      )}
      <div className="inspector-footer">
        <Button
          size="xs"
          variant="light"
          leftSection={<Download size={14} />}
          onClick={() => download(`goal-attempt-${attempt.index + 1}.smt2`, attempt.smtlib)}
        >
          Export complete SMT problem
        </Button>
        <span>Includes datatype declarations and recursive definitions.</span>
      </div>
    </div>
  );
}

function modelText(value: ModelValue): string {
  if (value.kind === 'boolean' || value.kind === 'integer') return String(value.value);
  return value.arguments.length ? `${value.name}(${value.arguments.map(modelText).join(', ')})` : value.name;
}

export function ModelExplorer({ attempt }: { attempt: Attempt }) {
  const [search, setSearch] = useState('');
  const [notation, setNotation] = useState('structured');
  const [page, setPage] = useState(1);
  const model = attempt.model;
  if (!model)
    return (
      <div className="empty-state">
        <Table2 size={30} />
        <h3>{attempt.status === 'unsat' ? 'No model for this attempt' : 'No model returned'}</h3>
        <p>{attempt.status === 'unsat' ? 'The constraints for this spine count are unsatisfiable.' : attempt.reason}</p>
        <p>Select another attempt to inspect a model the solver found.</p>
      </div>
    );
  const values = model.values.filter((value) =>
    `${value.name} ${modelText(value.value)}`.toLowerCase().includes(search.toLowerCase()),
  );
  const currentPage = Math.min(page, Math.max(1, Math.ceil(values.length / 30)));
  return (
    <div className="inspector">
      <Group justify="space-between" mb="md">
        <Badge color={attempt.accepted ? 'teal' : 'orange'} variant="light">
          {attempt.accepted ? 'Accepted model' : 'Provisional model'}
        </Badge>
        <SegmentedControl
          size="xs"
          value={notation}
          onChange={setNotation}
          data={[
            { value: 'structured', label: 'Assignments' },
            { value: 'raw', label: 'Raw model' },
          ]}
        />
      </Group>
      {!attempt.accepted && (
        <div className="callout warning">
          This model needs {attempt.refinements.length} type-validity refinement
          {attempt.refinements.length === 1 ? '' : 's'}. It is not accepted evidence.
        </div>
      )}
      {attempt.refinements.length > 0 && (
        <Accordion variant="separated" mb="md">
          <Accordion.Item value="refinements">
            <Accordion.Control>Added type-validity constraints</Accordion.Control>
            <Accordion.Panel>
              {attempt.refinements.map((constraint) => (
                <article className="constraint-card" key={constraint.id}>
                  <p>{constraint.explanation}</p>
                  <ConstraintFormula formula={constraint.formula} />
                </article>
              ))}
            </Accordion.Panel>
          </Accordion.Item>
        </Accordion>
      )}
      {attempt.recordLabels.length > 0 && (
        <Accordion variant="separated" mb="md">
          <Accordion.Item value="labels">
            <Accordion.Control>Record label encoding</Accordion.Control>
            <Accordion.Panel>
              <div className="label-vocabulary">
                {attempt.recordLabels.map((label, index) => (
                  <code key={label}>
                    {index} ↦ {label}
                  </code>
                ))}
              </div>
            </Accordion.Panel>
          </Accordion.Item>
        </Accordion>
      )}
      {notation === 'raw' ? (
        <>
          <Group justify="flex-end">
            <CopyButton text={model.text} />
          </Group>
          <pre className="code-block">{model.text}</pre>
        </>
      ) : (
        <>
          <TextInput
            mb="md"
            aria-label="Search model"
            placeholder="Search assignments…"
            leftSection={<Search size={14} />}
            value={search}
            onChange={(event) => {
              setSearch(event.currentTarget.value);
              setPage(1);
            }}
          />
          <div className="model-table">
            <Table striped highlightOnHover withTableBorder verticalSpacing="sm">
              <Table.Thead>
                <Table.Tr>
                  <Table.Th>Variable</Table.Th>
                  <Table.Th>Model value</Table.Th>
                </Table.Tr>
              </Table.Thead>
              <Table.Tbody>
                {values.slice((currentPage - 1) * 30, currentPage * 30).map((binding) => (
                  <Table.Tr key={binding.name}>
                    <Table.Td>
                      <code>{binding.name}</code>
                    </Table.Td>
                    <Table.Td>
                      <code>{modelText(binding.value)}</code>
                    </Table.Td>
                  </Table.Tr>
                ))}
              </Table.Tbody>
            </Table>
          </div>
          {values.length > 30 && (
            <Pagination
              mt="md"
              size="sm"
              value={currentPage}
              onChange={setPage}
              total={Math.ceil(values.length / 30)}
            />
          )}
        </>
      )}
      <p className="muted small">
        Assignments include unused argument slots. The accepted evidence graph shows the chosen spine lengths and
        applications.
      </p>
    </div>
  );
}

export function SearchTrace({
  attempts,
  selected,
  onSelect,
}: {
  attempts: Attempt[];
  selected: number;
  onSelect: (index: number) => void;
}) {
  return (
    <div className="inspector trace-view">
      <p className="muted">
        Search grows the number of spines. A satisfiable model is checked for type validity before its evidence is
        accepted.
      </p>
      {attempts.map((attempt, index) => (
        <button
          key={index}
          className={`trace-attempt ${selected === index ? 'trace-selected' : ''}`}
          onClick={() => onSelect(index)}
        >
          <span className={`trace-dot ${attempt.status}`} />
          <div>
            <strong>
              Attempt {index + 1}{' '}
              <span className="muted">
                · {attempt.spineCount} spine{attempt.spineCount === 1 ? '' : 's'}
              </span>
            </strong>
            <p>
              {attempt.status === 'sat'
                ? attempt.accepted
                  ? 'Model accepted; evidence reconstructed.'
                  : `${attempt.refinements.length} validity obligations added.`
                : attempt.status === 'unsat'
                  ? 'No model at this spine count.'
                  : attempt.reason}
            </p>
          </div>
          <div className="trace-timing">
            <Badge
              variant="light"
              color={attempt.status === 'sat' ? 'teal' : attempt.status === 'unsat' ? 'gray' : 'orange'}
            >
              {attempt.status}
            </Badge>
            <span>{attempt.elapsedMillis.toFixed(1)} ms</span>
          </div>
        </button>
      ))}
      {attempts[selected] && (
        <Accordion variant="separated" mt="lg">
          <Accordion.Item value="statistics">
            <Accordion.Control>Solver statistics for attempt {selected + 1}</Accordion.Control>
            <Accordion.Panel>
              <Table>
                <Table.Tbody>
                  {Object.entries(attempts[selected].statistics).map(([name, value]) => (
                    <Table.Tr key={name}>
                      <Table.Td>{name}</Table.Td>
                      <Table.Td>{value}</Table.Td>
                    </Table.Tr>
                  ))}
                </Table.Tbody>
              </Table>
            </Accordion.Panel>
          </Accordion.Item>
        </Accordion>
      )}
    </div>
  );
}
