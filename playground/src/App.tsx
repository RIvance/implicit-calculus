import { useCallback, useEffect, useRef, useState } from 'react';
import {
  Accordion,
  ActionIcon,
  Alert,
  Badge,
  Button,
  Drawer,
  Group,
  Loader,
  Menu,
  Modal,
  NumberInput,
  Select,
  Slider,
  Switch,
  Tabs,
  TextInput,
  Tooltip,
  useMantineColorScheme,
} from '@mantine/core';
import { useMediaQuery } from '@mantine/hooks';
import { Group as PanelGroup, Panel, Separator } from 'react-resizable-panels';
import {
  BookOpen,
  Check,
  ChevronLeft,
  ChevronRight,
  Code2,
  Command,
  Download,
  Ellipsis,
  FileCode2,
  FolderOpen,
  HelpCircle,
  History,
  ListFilter,
  Moon,
  Pause,
  Play,
  RotateCcw,
  Search,
  Share2,
  ShieldCheck,
  SlidersHorizontal,
  Square,
  Sun,
  Upload,
} from 'lucide-react';
import type { editor } from 'monaco-editor';
import type { Analysis, GoalResult, Language, Progress, RunOptions, RunResult } from '../bridge/protocol';
import { CompilerClient, Interrupted } from './compiler-client';
import { examples } from './examples';
import { download, draftKey, historyKey, shareDocument, type DocumentState, type HistoryEntry } from './document';
import { SourceEditor } from './SourceEditor';
import { UnresolvedTerm, goalName } from './UnresolvedTerm';
import { Library } from './Library';
import { EvidenceGraph } from './EvidenceGraph';
import { ConstraintExplorer, CopyButton, ModelExplorer, SearchTrace } from './Inspectors';
import { ResearchGuide } from './ResearchGuide';

const defaults: RunOptions = {
  maximumSteps: 16,
  maximumTypeDepth: 8,
  timeoutMillis: 10_000,
  mode: 'smt',
  evaluate: true,
};
type VisibleGoal = GoalResult & { pending?: boolean };
type RuntimeState = 'loading' | 'ready' | 'idle' | 'failed';

export function App({
  initial,
  initialHistory,
  initialNotice,
}: {
  initial: DocumentState;
  initialHistory: HistoryEntry[];
  initialNotice?: string;
}) {
  const [document, setDocument] = useState(initial);
  const [analysis, setAnalysis] = useState<Analysis | null>(null);
  const [options, setOptions] = useState(defaults);
  const [executionTimeout, setExecutionTimeout] = useState(60_000);
  const [result, setResult] = useState<RunResult | null>(null);
  const [runSource, setRunSource] = useState<string | null>(null);
  const [runOptions, setRunOptions] = useState<RunOptions | null>(null);
  const [runAnalysis, setRunAnalysis] = useState<Analysis | null>(null);
  const [saving, setSaving] = useState<'saving' | 'saved' | 'failed'>('saving');
  const [goals, setGoals] = useState<VisibleGoal[]>([]);
  const [selectedGoal, setSelectedGoal] = useState(0);
  const [selectedAttempt, setSelectedAttempt] = useState(0);
  const [selectedSpine, setSelectedSpine] = useState<number | null>(null);
  const [researchTab, setResearchTab] = useState('evidence');
  const [followLatest, setFollowLatest] = useState(true);
  const [playing, setPlaying] = useState(false);
  const [busy, setBusy] = useState(false);
  const [phase, setPhase] = useState('');
  const [runtime, setRuntime] = useState<RuntimeState>('loading');
  const [solverVersion, setSolverVersion] = useState('');
  const [language, setLanguage] = useState<Language>({ keywords: [], primitiveTypes: [], literals: [] });
  const [notice, setNotice] = useState(initialNotice ?? '');
  const [libraryOpen, setLibraryOpen] = useState(false);
  const [guideOpen, setGuideOpen] = useState(false);
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [shareUrl, setShareUrl] = useState('');
  const [history, setHistory] = useState<HistoryEntry[]>(initialHistory);
  const [wrap, setWrap] = useState(true);
  const [fontSize, setFontSize] = useState(14);
  const client = useRef<CompilerClient | null>(null);
  const activeRun = useRef(0);
  const source = useRef(document.source);
  source.current = document.source;
  const editor = useRef<editor.IStandaloneCodeEditor | null>(null);
  const upload = useRef<HTMLInputElement | null>(null);
  const { colorScheme, toggleColorScheme } = useMantineColorScheme();
  const stacked = useMediaQuery('(max-width: 1000px)');

  const connect = useCallback(() => {
    client.current?.dispose();
    setRuntime('loading');
    try {
      const next = new CompilerClient();
      client.current = next;
      next.language
        .then((info) => {
          if (client.current === next) setLanguage(info);
        })
        .catch((error: unknown) => {
          if (client.current === next) setNotice(error instanceof Error ? error.message : String(error));
        });
      next.ready
        .then((info) => {
          if (client.current === next) {
            setRuntime('ready');
            setSolverVersion(info.version);
          }
        })
        .catch((error: unknown) => {
          if (client.current === next) {
            setRuntime('failed');
            setNotice(error instanceof Error ? error.message : String(error));
          }
        });
      return next;
    } catch (error) {
      client.current = null;
      setRuntime('failed');
      setNotice(error instanceof Error ? error.message : String(error));
      return null;
    }
  }, []);

  useEffect(() => {
    connect();
    return () => {
      activeRun.current += 1;
      client.current?.dispose();
    };
  }, [connect]);

  useEffect(() => {
    setSaving('saving');
    const save = () => {
      try {
        localStorage.setItem(draftKey, JSON.stringify(document));
        setSaving('saved');
      } catch {
        setSaving('failed');
        setNotice('The browser could not save this draft. Export the source to keep a copy.');
      }
    };
    const timer = setTimeout(save, 300);
    window.addEventListener('pagehide', save);
    return () => {
      clearTimeout(timer);
      window.removeEventListener('pagehide', save);
    };
  }, [document]);

  useEffect(() => {
    setAnalysis(null);
    if (runtime !== 'ready' || !client.current) return;
    const current = client.current;
    let cancelled = false;
    const timer = setTimeout(() => {
      current
        .analyze(document.source)
        .then((analysis) => {
          if (!cancelled) setAnalysis(analysis);
        })
        .catch((error: unknown) => {
          if (!cancelled && !(error instanceof Interrupted))
            setNotice(error instanceof Error ? error.message : String(error));
        });
    }, 350);
    return () => {
      cancelled = true;
      clearTimeout(timer);
    };
  }, [document.source, runtime]);

  const goalIndex = Math.min(selectedGoal, Math.max(0, goals.length - 1));
  const goal = goals[goalIndex];
  const attempts = goal?.synthesis.attempts ?? [];
  const attemptIndex = Math.min(selectedAttempt, Math.max(0, attempts.length - 1));
  const attempt = attempts[attemptIndex];
  const stale = runSource !== null && runSource !== document.source;
  const selectedExample = examples.find((example) => example.path === document.name);
  const editedExample = selectedExample && selectedExample.source !== document.source;
  const checkedCount = goals.filter((goal) => goal.validated).length;
  const totalAttempts = goals.reduce((sum, goal) => sum + goal.synthesis.attempts.length, 0);

  useEffect(() => {
    if (followLatest) setSelectedAttempt(Math.max(0, attempts.length - 1));
  }, [followLatest, attempts.length, goalIndex]);
  useEffect(() => {
    if (!playing) return;
    if (attemptIndex >= attempts.length - 1) {
      setPlaying(false);
      return;
    }
    const timer = setTimeout(() => setSelectedAttempt((index) => index + 1), 850);
    return () => clearTimeout(timer);
  }, [playing, attemptIndex, attempts.length]);

  function record(document: DocumentState, options: RunOptions, result: RunResult) {
    const entry: HistoryEntry = {
      ...document,
      id: crypto.randomUUID(),
      time: Date.now(),
      options,
      ok: result.ok,
      summary: result.value ?? result.diagnostic?.message ?? 'Compiled successfully',
    };
    setHistory((previous) => {
      const next = [entry, ...previous].slice(0, 20);
      try {
        localStorage.setItem(historyKey, JSON.stringify(next));
      } catch {
        setNotice('Run history could not be saved in this browser.');
      }
      return next;
    });
  }

  const run = useCallback(async () => {
    if (busy) return;
    const worker = runtime === 'ready' || runtime === 'loading' ? client.current : connect();
    if (!worker) return;
    const identity = ++activeRun.current;
    const snapshot = { ...document };
    const settings = { ...options };
    setRunOptions(settings);
    setRunAnalysis(null);
    setBusy(true);
    setResult(null);
    setRunSource(snapshot.source);
    setGoals([]);
    setSelectedGoal(0);
    setSelectedAttempt(0);
    setSelectedSpine(null);
    setFollowLatest(true);
    setPlaying(false);
    setPhase('Resolving evidence');

    const progress = (event: Progress) => {
      if (activeRun.current !== identity) return;
      if (event.kind === 'stage') {
        setPhase(
          { preparing: 'Parsing and elaborating', resolving: 'Resolving evidence', evaluating: 'Evaluating' }[
            event.stage
          ],
        );
      } else if (event.kind === 'prepared') {
        setRunAnalysis(event.analysis);
        if (source.current === snapshot.source) setAnalysis(event.analysis);
      } else if (event.kind === 'goal') {
        setGoals((previous) => {
          const next = [...previous];
          next[event.goalIndex] = {
            request: event.goal,
            validated: false,
            pending: true,
            synthesis: { ok: false, term: null, diagnostic: null, attempts: [], evidence: null },
          };
          return next;
        });
      } else if (event.kind === 'attempt') {
        setGoals((previous) =>
          previous.map((goal, index) =>
            index === event.goalIndex
              ? {
                  ...goal,
                  synthesis: { ...goal.synthesis, attempts: [...goal.synthesis.attempts, event.attempt] },
                }
              : goal,
          ),
        );
      } else {
        setGoals((previous) =>
          previous.map((goal, index) =>
            index === event.goalIndex
              ? {
                  ...goal,
                  synthesis: event.report,
                  pending: false,
                }
              : goal,
          ),
        );
      }
    };
    let acceptingProgress = true;
    try {
      const completed = await worker.run(
        snapshot.source,
        settings,
        (event) => {
          if (acceptingProgress) progress(event);
        },
        executionTimeout,
      );
      if (activeRun.current !== identity) return;
      setResult(completed);
      setRunAnalysis(completed.analysis ?? null);
      if (completed.analysis && source.current === snapshot.source) setAnalysis(completed.analysis);
      setGoals(completed.goals ?? []);
      record(snapshot, settings, completed);
    } catch (error) {
      if (activeRun.current !== identity) return;
      const diagnostic = {
        code: error instanceof Interrupted ? error.code : 'ExecutionFailure',
        message: error instanceof Error ? error.message : String(error),
      };
      setResult({ ok: false, diagnostic });
      worker.dispose();
      client.current = null;
      setRuntime('idle');
    } finally {
      acceptingProgress = false;
      if (activeRun.current === identity) {
        setBusy(false);
        setPhase('');
      }
    }
  }, [busy, runtime, connect, document, options, executionTimeout]);

  function cancel() {
    activeRun.current += 1;
    client.current?.dispose();
    client.current = null;
    setBusy(false);
    setRuntime('idle');
    setPlaying(false);
    setPhase('');
    setResult({
      ok: false,
      diagnostic: { code: 'Cancelled', message: 'Execution cancelled. Completed search attempts remain available.' },
    });
  }
  function open(next: DocumentState) {
    setDocument({ name: next.name, source: next.source });
    setLibraryOpen(false);
  }
  function chooseGoal(index: number) {
    setSelectedGoal(index);
    setSelectedSpine(null);
    setFollowLatest(true);
    setPlaying(false);
  }
  function chooseAttempt(index: number) {
    setFollowLatest(false);
    setPlaying(false);
    setSelectedAttempt(index);
  }
  function clearHistory() {
    setHistory([]);
    try {
      localStorage.removeItem(historyKey);
    } catch {
      setNotice('The browser could not clear saved history.');
    }
  }
  const library = (
    <Library
      selected={document.name}
      onOpen={open}
      history={history}
      clearHistory={clearHistory}
      onNew={() => open({ name: 'untitled.imp', source: '' })}
    />
  );
  const diagnostic = result?.diagnostic ?? (!busy ? analysis?.diagnostic : null);

  return (
    <div className="application">
      <header className="masthead">
        <a className="brand" href={import.meta.env.BASE_URL} aria-label="Implicit home">
          <span className="brand-symbol">λ</span>
          <span>
            <strong>
              implicit<span className="brand-period">.</span>
            </strong>
            <small>SYNTHESIS WORKBENCH</small>
          </span>
        </a>
        <div className="masthead-caption">A language, its evidence, and the search in between.</div>
        <Group gap="xs">
          <div
            className={`runtime-status ${runtime}`}
            title={
              solverVersion
                ? `Z3 ${solverVersion}; computation runs in this browser.`
                : 'Preparing the compiler and solver'
            }
          >
            {runtime === 'loading' ? <Loader size={12} /> : <span className="status-dot" />}
            <span>
              {runtime === 'ready'
                ? 'Local solver ready'
                : runtime === 'loading'
                  ? 'Loading solver'
                  : runtime === 'failed'
                    ? 'Solver unavailable'
                    : 'Solver idle'}
            </span>
          </div>
          {runtime === 'failed' && (
            <Button size="compact-xs" variant="light" onClick={connect}>
              Retry
            </Button>
          )}
          <Tooltip label="Research guide">
            <ActionIcon
              aria-label="Open research guide"
              variant="subtle"
              color="gray"
              onClick={() => setGuideOpen(true)}
            >
              <HelpCircle size={19} />
            </ActionIcon>
          </Tooltip>
          <Tooltip label="Toggle color scheme">
            <ActionIcon aria-label="Toggle color scheme" variant="subtle" color="gray" onClick={toggleColorScheme}>
              {colorScheme === 'dark' ? <Sun size={18} /> : <Moon size={18} />}
            </ActionIcon>
          </Tooltip>
        </Group>
      </header>
      {notice && (
        <Alert className="workspace-notice" color="orange" withCloseButton onClose={() => setNotice('')}>
          {notice}
        </Alert>
      )}
      <div className="workspace">
        <aside className="sidebar">
          {library}
          <div className="sidebar-footer">
            <BookOpen size={14} />
            <button onClick={() => setGuideOpen(true)}>How synthesis works</button>
          </div>
        </aside>
        <main className="workbench">
          <PanelGroup
            orientation={stacked ? 'vertical' : 'horizontal'}
            className="workspace-panels"
            id={stacked ? 'stacked' : 'wide'}
          >
            <Panel id="editor" defaultSize="45%" minSize={stacked ? '450px' : '300px'} className="source-panel">
              <div className="panel-title editor-title">
                <Group gap={8} wrap="nowrap" className="source-document">
                  <Tooltip label="Programs">
                    <ActionIcon
                      className="library-trigger"
                      size="sm"
                      variant="subtle"
                      color="gray"
                      aria-label="Programs"
                      onClick={() => setLibraryOpen(true)}
                    >
                      <FolderOpen size={15} />
                    </ActionIcon>
                  </Tooltip>
                  <FileCode2 size={15} className="source-file-icon" />
                  <strong title={document.name}>{document.name}</strong>
                  {editedExample && <span className="unsaved-dot" title="Edited example" />}
                </Group>
                <Group gap={4} wrap="nowrap" className="editor-actions">
                  <Tooltip label="Find in source">
                    <ActionIcon
                      variant="subtle"
                      color="gray"
                      size="sm"
                      aria-label="Find in source"
                      onClick={() => void editor.current?.getAction('actions.find')?.run()}
                    >
                      <Search size={14} />
                    </ActionIcon>
                  </Tooltip>
                  <Menu position="bottom-end" withinPortal>
                    <Menu.Target>
                      <ActionIcon variant="subtle" color="gray" size="sm" aria-label="Source actions">
                        <Ellipsis size={16} />
                      </ActionIcon>
                    </Menu.Target>
                    <Menu.Dropdown>
                      <Menu.Item
                        leftSection={<Command size={14} />}
                        onClick={() => void editor.current?.getAction('editor.action.quickCommand')?.run()}
                      >
                        Editor command palette
                      </Menu.Item>
                      {selectedExample && (
                        <Menu.Item
                          leftSection={<RotateCcw size={14} />}
                          onClick={() => setDocument({ name: selectedExample.path, source: selectedExample.source })}
                        >
                          Restore original example
                        </Menu.Item>
                      )}
                      <Menu.Divider />
                      <Menu.Item leftSection={<Upload size={14} />} onClick={() => upload.current?.click()}>
                        Import source
                      </Menu.Item>
                      <Menu.Item
                        leftSection={<Download size={14} />}
                        onClick={() => download(document.name.split('/').at(-1) ?? 'program.imp', document.source)}
                      >
                        Export source
                      </Menu.Item>
                      <Menu.Item
                        leftSection={<Share2 size={14} />}
                        onClick={() => {
                          shareDocument(document)
                            .then(setShareUrl)
                            .catch(() =>
                              setNotice('The browser could not create a share link. Export the source instead.'),
                            );
                        }}
                      >
                        Share program
                      </Menu.Item>
                    </Menu.Dropdown>
                  </Menu>
                  <Tooltip label={`Search settings · N ${options.maximumSteps} · H ${options.maximumTypeDepth}`}>
                    <ActionIcon
                      aria-label="Search settings"
                      variant="subtle"
                      size="sm"
                      color="gray"
                      onClick={() => setSettingsOpen(true)}
                    >
                      <SlidersHorizontal size={15} />
                    </ActionIcon>
                  </Tooltip>
                  {busy ? (
                    <Button size="xs" color="red" variant="light" leftSection={<Square size={13} />} onClick={cancel}>
                      Stop
                    </Button>
                  ) : (
                    <Button
                      size="xs"
                      color="teal"
                      aria-label={options.evaluate ? 'Run program' : 'Compile'}
                      leftSection={<Play size={14} fill="currentColor" />}
                      onClick={() => void run()}
                    >
                      {options.evaluate ? 'Run' : 'Compile'}
                    </Button>
                  )}
                </Group>
              </div>
              <div className="source-editor">
                <SourceEditor
                  source={document.source}
                  onChange={(source) => setDocument((previous) => ({ ...previous, source }))}
                  analysis={analysis}
                  language={language}
                  dark={colorScheme === 'dark'}
                  wrap={wrap}
                  fontSize={fontSize}
                  onRun={() => void run()}
                  onReady={(instance) => {
                    editor.current = instance;
                  }}
                />
              </div>
              <div className="source-status">
                <span>
                  {document.source.split('\n').length} lines ·{' '}
                  {saving === 'saved'
                    ? 'draft saved locally'
                    : saving === 'saving'
                      ? 'saving draft…'
                      : 'draft not saved'}
                </span>
                <span>
                  {analysis?.ok ? (
                    <>
                      <Check size={12} /> Elaborated
                    </>
                  ) : analysis?.diagnostic ? (
                    'Diagnostic available'
                  ) : (
                    'Editing'
                  )}
                </span>
              </div>
              <section className="output-panel" aria-label="Program output">
                <div className="output-heading">
                  <Group gap="xs">
                    <Code2 size={14} />
                    <strong>Result</strong>
                    {stale && (
                      <Badge size="xs" variant="light" color="orange">
                        source changed
                      </Badge>
                    )}
                  </Group>
                  {busy ? (
                    <Group gap="xs">
                      <Loader size={12} />
                      <span>{phase}</span>
                    </Group>
                  ) : (
                    result?.elapsedMillis !== undefined && <span>{result.elapsedMillis.toFixed(1)} ms</span>
                  )}
                </div>
                {result?.ok ? (
                  <>
                    <pre data-testid="program-value" className="program-value">
                      {result.value ?? 'Compiled successfully'}
                    </pre>
                    <div className="output-type">
                      <span>:</span>
                      <code>{result.analysis?.type}</code>
                      <CopyButton text={result.value ?? result.coreTerm ?? ''} />
                    </div>
                  </>
                ) : diagnostic ? (
                  <div className="diagnostic" role="alert">
                    <Badge color={diagnostic.code === 'Cancelled' ? 'gray' : 'orange'} variant="light" size="xs">
                      {diagnostic.code}
                    </Badge>
                    <p>{diagnostic.message}</p>
                    {diagnostic.position && (
                      <button
                        onClick={() => {
                          const position = {
                            lineNumber: diagnostic.position!.line,
                            column: diagnostic.position!.column,
                          };
                          editor.current?.setPosition(position);
                          editor.current?.revealPositionInCenter(position);
                          editor.current?.focus();
                        }}
                      >
                        Line {diagnostic.position.line}, column {diagnostic.position.column}
                      </button>
                    )}
                  </div>
                ) : (
                  <p className="output-placeholder">
                    {busy
                      ? 'Checking the program and resolving its evidence…'
                      : 'Run this program to evaluate its result and inspect synthesis.'}
                  </p>
                )}
                {selectedExample && !editedExample && (
                  <div className="expected-output">
                    <span>Example expects {selectedExample.expectation}</span>
                    <code>{selectedExample.expected}</code>
                  </div>
                )}
              </section>
            </Panel>
            <Separator className="panel-separator" />
            <Panel id="research" minSize={stacked ? '560px' : '360px'} className="research-panel">
              <div className="panel-title">
                <Group gap="xs">
                  <ListFilter size={16} />
                  <strong>Synthesis explorer</strong>
                </Group>
                <Group gap="xs">
                  {stale && (
                    <Badge color="orange" size="xs" variant="light">
                      previous source
                    </Badge>
                  )}
                  <Badge color="gray" size="xs" variant="light">
                    {goals.length} goals
                  </Badge>
                  <Tooltip label="Export synthesis report">
                    <ActionIcon
                      variant="subtle"
                      color="gray"
                      size="sm"
                      aria-label="Export synthesis report"
                      disabled={!result}
                      onClick={() =>
                        download(
                          'synthesis-report.json',
                          JSON.stringify({ source: runSource, options: runOptions, result, goals }, null, 2),
                          'application/json',
                        )
                      }
                    >
                      <Download size={14} />
                    </ActionIcon>
                  </Tooltip>
                </Group>
              </div>
              {goals.length > 0 && runAnalysis?.unresolvedTerm && (
                <UnresolvedTerm
                  parts={runAnalysis.unresolvedTerm}
                  goals={goals}
                  selected={goalIndex}
                  onSelect={(index) => {
                    chooseGoal(index);
                    setResearchTab('evidence');
                  }}
                />
              )}
              {goals.length > 0 && (
                <div className="goal-picker">
                  <Select
                    aria-label="Select synthesis goal"
                    value={String(goalIndex)}
                    onChange={(value) => chooseGoal(Number(value))}
                    data={goals.map((goal, index) => ({
                      value: String(index),
                      label: `${goalName(runAnalysis?.unresolvedTerm, goal.request.location)} : ${goal.request.type}`,
                    }))}
                    searchable
                  />
                  <Badge
                    color={goal?.validated ? 'teal' : goal?.pending ? 'blue' : goal?.synthesis.ok ? 'blue' : 'orange'}
                    variant="light"
                  >
                    {goal?.validated
                      ? 'Checked'
                      : goal?.pending
                        ? 'Searching'
                        : goal?.synthesis.ok
                          ? 'Constructed'
                          : 'Unresolved'}
                  </Badge>
                </div>
              )}
              {goal && (
                <Accordion className="context-accordion" variant="default">
                  <Accordion.Item value="scope">
                    <Accordion.Control>
                      <span className="goal-judgment">
                        Δ ⊢ {goalName(runAnalysis?.unresolvedTerm, goal.request.location)} : {goal.request.type}
                      </span>
                      <span className="context-count">{goal.request.instances.length} instances in scope</span>
                    </Accordion.Control>
                    <Accordion.Panel>
                      <div className="scope-section">
                        <strong>
                          Instance context Δ <span>nearest first</span>
                        </strong>
                        {goal.request.instances.length === 0 && (
                          <p className="muted small">The instance context is empty.</p>
                        )}
                        {goal.request.instances.map((instance) => (
                          <div
                            className={`scope-binding ${goal.synthesis.evidence?.spines.find((spine) => spine.index === selectedSpine)?.head === instance.index ? 'scope-highlight' : ''}`}
                            key={instance.index}
                          >
                            <span>Δ[{instance.index}]</span>
                            <code>{instance.name}</code>
                            <code>{instance.type}</code>
                          </div>
                        ))}
                      </div>
                      {(goal.request.ordinary.length > 0 || goal.request.typeVariables.length > 0) && (
                        <div className="scope-section">
                          <strong>Surrounding scope</strong>
                          {goal.request.typeVariables.length > 0 && (
                            <p className="small">
                              Rigid type variables: <code>{goal.request.typeVariables.join(', ')}</code>
                            </p>
                          )}
                          {goal.request.ordinary.map((binding, index) => (
                            <div className="scope-binding ordinary" key={index}>
                              <span>Γ[{index}]</span>
                              <code>{binding.name}</code>
                              <code>{binding.type}</code>
                            </div>
                          ))}
                          {goal.request.ordinary.length > 0 && (
                            <p className="muted small">Ordinary bindings are outside the instance search context.</p>
                          )}
                        </div>
                      )}
                    </Accordion.Panel>
                  </Accordion.Item>
                </Accordion>
              )}
              <Tabs
                value={researchTab}
                onChange={(value) => setResearchTab(value ?? 'evidence')}
                className="research-tabs"
              >
                <Tabs.List>
                  <Tabs.Tab value="evidence">Evidence</Tabs.Tab>
                  <Tabs.Tab value="constraints">Constraints</Tabs.Tab>
                  <Tabs.Tab value="model">Model</Tabs.Tab>
                  <Tabs.Tab value="trace">Trace</Tabs.Tab>
                  <Tabs.Tab value="terms">Terms</Tabs.Tab>
                </Tabs.List>
                {attempts.length > 0 && researchTab !== 'evidence' && researchTab !== 'terms' && (
                  <div className="attempt-player">
                    <Group gap={3}>
                      <ActionIcon
                        variant="subtle"
                        size="sm"
                        aria-label="Previous attempt"
                        disabled={attemptIndex === 0}
                        onClick={() => chooseAttempt(attemptIndex - 1)}
                      >
                        <ChevronLeft size={16} />
                      </ActionIcon>
                      <ActionIcon
                        variant="light"
                        size="sm"
                        aria-label={playing ? 'Pause search playback' : 'Play search playback'}
                        disabled={attempts.length < 2}
                        onClick={() => {
                          setFollowLatest(false);
                          if (attemptIndex === attempts.length - 1) setSelectedAttempt(0);
                          setPlaying((value) => !value);
                        }}
                      >
                        {playing ? <Pause size={13} /> : <Play size={13} />}
                      </ActionIcon>
                      <ActionIcon
                        variant="subtle"
                        size="sm"
                        aria-label="Next attempt"
                        disabled={attemptIndex === attempts.length - 1}
                        onClick={() => chooseAttempt(attemptIndex + 1)}
                      >
                        <ChevronRight size={16} />
                      </ActionIcon>
                    </Group>
                    <Slider
                      aria-label="Search attempt"
                      min={0}
                      max={Math.max(1, attempts.length - 1)}
                      value={attemptIndex}
                      disabled={attempts.length < 2}
                      onChange={chooseAttempt}
                      label={(value) => `Attempt ${value + 1}`}
                    />
                    <span>
                      {attemptIndex + 1} / {attempts.length}
                    </span>
                    <Badge size="xs" color={attempt?.status === 'sat' ? 'teal' : 'gray'} variant="light">
                      {attempt?.status}
                    </Badge>
                  </div>
                )}
                <Tabs.Panel value="evidence">
                  {goal ? (
                    <EvidenceGraph
                      goal={goal}
                      selected={selectedSpine}
                      onSelect={setSelectedSpine}
                      onConstraints={(index) => {
                        setSelectedSpine(index);
                        setResearchTab('constraints');
                      }}
                    />
                  ) : (
                    <div className="research-welcome">
                      <div className="welcome-symbol">Δ ⊢ ?</div>
                      <h2>Make implicit evidence visible.</h2>
                      <p>
                        Edit a program, run it, and follow each goal from its lexical context to a solver model and a
                        checked construction.
                      </p>
                      <div className="welcome-features">
                        <span>
                          <ShieldCheck size={16} /> Checked evidence
                        </span>
                        <span>
                          <SlidersHorizontal size={16} /> Search bounds
                        </span>
                        <span>
                          <History size={16} /> Model refinement
                        </span>
                      </div>
                      {result?.ok && (
                        <div className="callout">
                          This program has no implicit goals. Its core term is available in Terms.
                        </div>
                      )}
                      <Button variant="light" mt="lg" onClick={() => setGuideOpen(true)}>
                        Explore the research
                      </Button>
                    </div>
                  )}
                </Tabs.Panel>
                <Tabs.Panel value="constraints">
                  {attempt ? (
                    <ConstraintExplorer attempt={attempt} spine={selectedSpine} setSpine={setSelectedSpine} />
                  ) : (
                    <NoQuery goal={goal} />
                  )}
                </Tabs.Panel>
                <Tabs.Panel value="model">
                  {attempt ? <ModelExplorer attempt={attempt} /> : <NoQuery goal={goal} />}
                </Tabs.Panel>
                <Tabs.Panel value="trace">
                  {attempts.length > 0 ? (
                    <SearchTrace attempts={attempts} selected={attemptIndex} onSelect={chooseAttempt} />
                  ) : (
                    <NoQuery goal={goal} />
                  )}
                </Tabs.Panel>
                <Tabs.Panel value="terms">
                  <div className="inspector term-inspector">
                    <Group justify="space-between">
                      <h3>Core term</h3>
                      <CopyButton text={result?.coreTerm ?? ''} />
                    </Group>
                    <p className="muted small">Resolved evidence is explicit. Core binders use fresh display names.</p>
                    <pre className="math-block">
                      {result?.coreTerm ?? 'The core term becomes available after all goals are resolved and checked.'}
                    </pre>
                  </div>
                </Tabs.Panel>
              </Tabs>
            </Panel>
          </PanelGroup>
          <footer className="workbench-footer">
            <span>{goals.length > 0 ? `${checkedCount} goals checked · ${totalAttempts} SMT checks` : ''}</span>
            <span>
              Ctrl / ⌘ + Enter to run <span className="toolbar-divider">·</span> Z3 {solverVersion || '…'}
            </span>
          </footer>
        </main>
      </div>
      <Drawer opened={libraryOpen} onClose={() => setLibraryOpen(false)} title="Programs" size="sm">
        {library}
      </Drawer>
      <Drawer opened={guideOpen} onClose={() => setGuideOpen(false)} title="Research guide" position="right" size="lg">
        <ResearchGuide
          onOpenExamples={() => {
            setGuideOpen(false);
            setLibraryOpen(true);
          }}
        />
      </Drawer>
      <Modal
        opened={settingsOpen}
        onClose={() => setSettingsOpen(false)}
        title="Search & editor settings"
        centered
        size="md"
      >
        <div className="settings-grid">
          <NumberInput
            label="Construction cost N"
            description="Total heads and applications in a spine program"
            value={options.maximumSteps}
            min={1}
            max={64}
            allowDecimal={false}
            onChange={(value) => setOptions((previous) => ({ ...previous, maximumSteps: Number(value) || 1 }))}
          />
          <NumberInput
            label="Type argument height H"
            description="Maximum height of inferred type arguments"
            value={options.maximumTypeDepth}
            min={1}
            max={32}
            allowDecimal={false}
            onChange={(value) => setOptions((previous) => ({ ...previous, maximumTypeDepth: Number(value) || 1 }))}
          />
          <NumberInput
            label="Solver budget per goal (ms)"
            value={options.timeoutMillis}
            min={100}
            max={120_000}
            step={1000}
            allowDecimal={false}
            onChange={(value) => setOptions((previous) => ({ ...previous, timeoutMillis: Number(value) || 100 }))}
          />
          <NumberInput
            label="Execution timeout (ms)"
            description="Stops the worker, including nonterminating evaluation"
            value={executionTimeout}
            min={1000}
            max={300_000}
            step={1000}
            allowDecimal={false}
            onChange={(value) => setExecutionTimeout(Number(value) || 1000)}
          />
        </div>
        <Switch
          mt="lg"
          label="Evaluate after compilation"
          checked={options.evaluate}
          onChange={(event) => {
            const evaluate = event.currentTarget.checked;
            setOptions((previous) => ({ ...previous, evaluate }));
          }}
        />
        <Switch
          mt="md"
          label="Wrap long source lines"
          checked={wrap}
          onChange={(event) => setWrap(event.currentTarget.checked)}
        />
        <NumberInput
          mt="md"
          label="Editor font size"
          min={11}
          max={24}
          value={fontSize}
          onChange={(value) => setFontSize(Number(value) || 14)}
        />
        <Group justify="space-between" mt="xl">
          <Button
            variant="subtle"
            color="gray"
            onClick={() => {
              setOptions(defaults);
              setExecutionTimeout(60_000);
              setFontSize(14);
              setWrap(true);
            }}
          >
            Restore defaults
          </Button>
          <Button color="teal" onClick={() => setSettingsOpen(false)}>
            Done
          </Button>
        </Group>
      </Modal>
      <Modal opened={!!shareUrl} onClose={() => setShareUrl('')} title="Share this program" centered>
        <p className="muted">The link contains the source. Opening it restores this document.</p>
        <TextInput
          aria-label="Program share link"
          value={shareUrl}
          readOnly
          onFocus={(event) => event.currentTarget.select()}
        />
        <Group justify="flex-end" mt="md">
          <CopyButton text={shareUrl} label="Copy link" />
        </Group>
      </Modal>
      <input
        ref={upload}
        type="file"
        accept=".imp,text/plain"
        hidden
        onChange={(event) => {
          const file = event.currentTarget.files?.[0];
          if (file)
            file
              .text()
              .then((source) => open({ name: file.name, source }))
              .catch(() => setNotice('The selected file could not be read.'));
          event.currentTarget.value = '';
        }}
      />
    </div>
  );
}

function NoQuery({ goal }: { goal?: VisibleGoal }) {
  return (
    <div className="empty-state">
      <Search size={28} />
      <h3>{goal?.synthesis.ok ? 'Resolved from the instance context' : 'No SMT query to inspect'}</h3>
      <p>
        {goal?.synthesis.ok
          ? 'A nearest matching instance already has the requested type. Its construction is shown in Evidence.'
          : (goal?.synthesis.diagnostic?.message ?? 'Run a program and select a goal to inspect its search.')}
      </p>
    </div>
  );
}
