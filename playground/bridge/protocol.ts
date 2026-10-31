export interface Language {
  keywords: string[];
  primitiveTypes: string[];
  literals: string[];
}

export interface Diagnostic {
  code: string;
  message: string;
  position?: { line: number; column: number } | null;
}

export interface Analysis {
  ok: boolean;
  type?: string;
  unresolvedTerm?: TermPart[];
  symbols?: { name: string; kind: 'type' | 'trait' | 'def' | 'instance' }[];
  diagnostic?: Diagnostic | null;
}

export type GoalLocation = (
  'body' | 'value' | 'function' | 'argument' | 'receiver' | 'left' | 'right' | { field: string }
)[];
export type TermPart =
  { kind: 'text'; text: string } | { kind: 'goal'; location: GoalLocation; name: string; type: string };

export interface Goal {
  location: GoalLocation;
  type: string;
  typeVariables: string[];
  instances: { index: number; name: string; type: string; form: string }[];
  ordinary: { name: string; type: string }[];
}

export interface Constraint {
  id: number;
  rule: string;
  explanation: string;
  spine: number | null;
  formula: string;
  smtlib: string;
}

export type ModelValue =
  | { kind: 'integer'; value: string }
  | { kind: 'boolean'; value: boolean }
  | { kind: 'constructor'; name: string; arguments: ModelValue[] };

export interface Attempt {
  index: number;
  spineCount: number;
  recordLabels: string[];
  status: 'sat' | 'unsat' | 'unknown' | 'error';
  elapsedMillis: number;
  accepted: boolean;
  reason: string | null;
  constraints: Constraint[];
  refinements: Constraint[];
  smtlib: string;
  model: { text: string; values: { name: string; value: ModelValue }[] } | null;
  statistics: Record<string, string>;
}

export interface EvidenceSpine {
  index: number;
  head: number;
  name: string;
  type: string;
  term: string;
  root: boolean;
  arguments: ({ kind: 'type'; type: string } | { kind: 'implicit'; spine: number })[];
}

export interface SynthesisReport {
  ok: boolean;
  term: string | null;
  diagnostic: Diagnostic | null;
  attempts: Attempt[];
  evidence: { cost: number; spines: EvidenceSpine[] } | null;
}

export interface GoalResult {
  request: Goal;
  synthesis: SynthesisReport;
  validated: boolean;
}

export interface RunResult {
  ok: boolean;
  analysis?: Analysis;
  elapsedMillis?: number;
  coreTerm?: string | null;
  value?: string | null;
  diagnostic?: Diagnostic | null;
  goals?: GoalResult[];
}

export interface RunOptions {
  maximumSteps: number;
  maximumTypeDepth: number;
  timeoutMillis: number;
  mode: 'smt' | 'exact';
  evaluate: boolean;
}

export type Progress =
  | { kind: 'stage'; stage: 'preparing' | 'resolving' | 'evaluating' }
  | { kind: 'prepared'; analysis: Analysis }
  | { kind: 'goal'; goalIndex: number; goal: Goal }
  | { kind: 'attempt'; goalIndex: number; attempt: Attempt }
  | { kind: 'evidence'; goalIndex: number; report: SynthesisReport };

export interface Runner {
  readonly version: string;
  run(
    source: string,
    maximumSteps: number,
    maximumTypeDepth: number,
    timeoutMillis: number,
    mode: string,
    evaluate: boolean,
    emit: (event: Progress) => void,
  ): Promise<RunResult>;
  close(): void;
}
