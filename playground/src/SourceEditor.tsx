import { useEffect, useRef } from 'react';
import Editor, { loader, type OnMount } from '@monaco-editor/react';
import * as monaco from 'monaco-editor/editor/editor.api.js';
import 'monaco-editor/editor/contrib/semanticTokens/browser/documentSemanticTokens.js';
import EditorWorker from 'monaco-editor/editor/editor.worker.js?worker';
import type { Analysis, Language } from '../bridge/protocol';
import { typeIdentifiers } from './highlighting';

globalThis.MonacoEnvironment = { getWorker: () => new EditorWorker() };
loader.config({ monaco });

const languageId = 'implicit';
const keywordHelp: Record<string, string> = {
  summon: 'Request evidence of an ordinary type from the lexical instance context Δ.',
  where: 'Add trait constraints to type parameters. Separate multiple constraints with `and`.',
  implement: 'Provide an implementation of a trait at the top level.',
  instance: 'Declare a named top-level instance. Type parameters and value parameters are supported.',
  given: 'Bind a local instance with an explicit type annotation.',
  implicit: 'Mark this lambda parameter as an instance available to synthesis.',
  using: 'Supply this individual argument explicitly to an implicit parameter.',
  def: 'Declare a top-level definition. Recursive definitions must have a function type.',
  trait: 'Declare a trait used by `where`, `implement`, and qualified member selection.',
  let: 'Bind an ordinary value. A newline may separate the binding from its body.',
};

function registerLanguage(language: Language) {
  if (!monaco.languages.getLanguages().some((language) => language.id === languageId)) {
    monaco.languages.register({ id: languageId, extensions: ['.imp'] });
    monaco.languages.setLanguageConfiguration(languageId, {
      comments: { lineComment: '//', blockComment: ['/*', '*/'] },
      brackets: [
        ['{', '}'],
        ['[', ']'],
        ['(', ')'],
      ],
      autoClosingPairs: [
        { open: '{', close: '}' },
        { open: '[', close: ']' },
        { open: '(', close: ')' },
        { open: '"', close: '"', notIn: ['string'] },
      ],
      surroundingPairs: [
        { open: '{', close: '}' },
        { open: '[', close: ']' },
        { open: '(', close: ')' },
        { open: '"', close: '"' },
      ],
      wordPattern: /[\p{L}_$][\p{L}\p{N}_$]*/u,
    });
  }
  monaco.editor.defineTheme('implicit-light', {
    base: 'vs',
    inherit: true,
    colors: {},
    rules: [{ token: 'type', foreground: '267F99' }],
  });
  monaco.editor.defineTheme('implicit-dark', {
    base: 'vs-dark',
    inherit: true,
    colors: {},
    rules: [{ token: 'type', foreground: '4EC9B0' }],
  });
  const tokenizer = monaco.languages.setMonarchTokensProvider(languageId, {
    unicode: true,
    ...language,
    tokenizer: {
      root: [
        [/\/\*/, 'comment', '@comment'],
        [/\/\/.*$/, 'comment'],
        [/"/, 'string', '@string'],
        [/\d+/, 'number'],
        [
          /[\p{L}_$][\p{L}\p{N}_$]*/u,
          {
            cases: {
              '@primitiveTypes': 'type',
              '@literals': 'constant',
              '@keywords': 'keyword',
              '@default': 'identifier',
            },
          },
        ],
        [/[{}()[\]]/, '@brackets'],
        [/::|~>|=>|->|\+\+|==|[+*=<>-]/, 'operator'],
        [/[,:;.]/, 'delimiter'],
      ],
      comment: [
        [/[^/*]+/, 'comment'],
        [/\*\//, 'comment', '@pop'],
        [/[/*]/, 'comment'],
      ],
      string: [
        [/[^\\"]+/, 'string'],
        [/\\./, 'string.escape'],
        [/"/, 'string', '@pop'],
      ],
    },
  });
  const types = monaco.languages.registerDocumentSemanticTokensProvider(languageId, {
    getLegend: () => ({ tokenTypes: ['type'], tokenModifiers: [] }),
    provideDocumentSemanticTokens: (model) => {
      let line = 0;
      let column = 0;
      const data = typeIdentifiers(model.getValue(), language).flatMap((span) => {
        const position = model.getPositionAt(span.offset);
        const nextLine = position.lineNumber - 1;
        const nextColumn = position.column - 1;
        const token = [nextLine - line, nextColumn - (line === nextLine ? column : 0), span.length, 0, 0];
        line = nextLine;
        column = nextColumn;
        return token;
      });
      return { data: new Uint32Array(data) };
    },
    releaseDocumentSemanticTokens: () => {},
  });
  return {
    dispose: () => {
      tokenizer.dispose();
      types.dispose();
    },
  };
}

export function SourceEditor({
  source,
  onChange,
  analysis,
  language,
  dark,
  wrap,
  fontSize,
  onRun,
  onReady,
}: {
  source: string;
  onChange: (source: string) => void;
  analysis: Analysis | null;
  language: Language;
  dark: boolean;
  wrap: boolean;
  fontSize: number;
  onRun: () => void;
  onReady: (editor: monaco.editor.IStandaloneCodeEditor) => void;
}) {
  const instance = useRef<monaco.editor.IStandaloneCodeEditor | null>(null);
  const latest = useRef({ analysis, onRun, language });
  latest.current = { analysis, onRun, language };
  function showDiagnostics() {
    const model = instance.current?.getModel();
    if (!model) return;
    const diagnostic = latest.current.analysis?.diagnostic;
    const position = diagnostic?.position;
    monaco.editor.setModelMarkers(
      model,
      languageId,
      diagnostic && position
        ? [
            {
              severity: monaco.MarkerSeverity.Error,
              message: diagnostic.message,
              startLineNumber: position.line,
              endLineNumber: position.line,
              startColumn: position.column,
              endColumn: position.column + 1,
            },
          ]
        : [],
    );
  }
  const mount: OnMount = (editor) => {
    instance.current = editor;
    showDiagnostics();
    onReady(editor);
    const completion = monaco.languages.registerCompletionItemProvider(languageId, {
      provideCompletionItems: (model, position) => {
        const word = model.getWordUntilPosition(position);
        const range = new monaco.Range(position.lineNumber, word.startColumn, position.lineNumber, word.endColumn);
        return {
          suggestions: [
            ...latest.current.language.keywords.map((name) => ({
              label: name,
              insertText: name,
              kind: monaco.languages.CompletionItemKind.Keyword,
              range,
              documentation: keywordHelp[name],
            })),
            ...latest.current.language.primitiveTypes.map((name) => ({
              label: name,
              insertText: name,
              kind: monaco.languages.CompletionItemKind.Class,
              range,
            })),
            ...latest.current.language.literals.map((name) => ({
              label: name,
              insertText: name,
              kind: monaco.languages.CompletionItemKind.Value,
              range,
            })),
            ...(latest.current.analysis?.symbols ?? []).map((symbol) => ({
              label: symbol.name,
              insertText: symbol.name,
              detail: symbol.kind,
              range,
              kind:
                symbol.kind === 'def'
                  ? monaco.languages.CompletionItemKind.Function
                  : monaco.languages.CompletionItemKind.Class,
            })),
          ],
        };
      },
    });
    const hover = monaco.languages.registerHoverProvider(languageId, {
      provideHover: (model, position) => {
        const word = model.getWordAtPosition(position);
        return word && keywordHelp[word.word] ? { contents: [{ value: keywordHelp[word.word] }] } : null;
      },
    });
    editor.addAction({
      id: 'implicit.run',
      label: 'Implicit: Run program',
      keybindings: [monaco.KeyMod.CtrlCmd | monaco.KeyCode.Enter],
      run: () => latest.current.onRun(),
    });
    editor.onDidDispose(() => {
      completion.dispose();
      hover.dispose();
      instance.current = null;
    });
  };
  useEffect(showDiagnostics, [analysis]);
  useEffect(() => {
    const tokenizer = registerLanguage(language);
    return () => tokenizer.dispose();
  }, [language]);
  return (
    <Editor
      height="100%"
      language={languageId}
      theme={dark ? 'implicit-dark' : 'implicit-light'}
      value={source}
      onChange={(value) => onChange(value ?? '')}
      onMount={mount}
      options={{
        fontSize,
        fontFamily: "'JetBrains Mono', 'SFMono-Regular', Consolas, monospace",
        fontLigatures: true,
        minimap: { enabled: false },
        padding: { top: 18, bottom: 18 },
        tabSize: 2,
        automaticLayout: true,
        scrollBeyondLastLine: false,
        wordWrap: wrap ? 'on' : 'off',
        lineNumbersMinChars: 3,
        renderLineHighlight: 'line',
        bracketPairColorization: { enabled: true },
        smoothScrolling: true,
        cursorSmoothCaretAnimation: 'on',
        folding: true,
        'semanticHighlighting.enabled': true,
      }}
    />
  );
}
