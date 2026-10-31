# Synthesis workbench

A browser playground for the language and its bounded evidence search. The React interface uses
Monaco for editing, Mantine for controls, and React Flow with Dagre for evidence graphs. The
Scala.js bridge runs the shared compiler and the official Z3 WebAssembly package in a worker.

## Run

Use Node.js 22.12 or newer, JDK 17 or newer, and sbt. From this directory:

```sh
npm ci
npm run dev
```

Open the local URL printed by Vite. `dev` links the Scala.js compiler and prepares the local
solver assets before starting Vite. React changes reload automatically. After changing Scala,
run `npm run compiler:dev` and reload the page.

For a production build and local preview:

```sh
npm run build
npm run preview
```

The build uses the full Scala.js linker, checks TypeScript, and writes the static site to `dist/`.
The pinned npm lockfile supplies Z3 4.14.1, matching the JVM dependency. The build prepares
the solver's loader, WebAssembly files, and browser isolation helper automatically. The
browser loads these assets from the same host as the site.

## Explore a program

- Open **Examples** in the editor header to browse the automatically discovered [project examples](../examples)
  and filter expected values or errors. Example headings describe expected
  outcomes; displayed execution results come from running the source.
- Edit with syntax highlighting, completion, keyword help, parser diagnostics, find, folding,
  and Monaco's command palette. Run and search settings are in the editor header; its source actions
  menu provides new documents, import, export, sharing, and restore. Press Ctrl/⌘ + Enter to run.
- Select each synthesis goal and inspect its lexical Δ, surrounding Γ, and rigid type variables.
  Select a graph node to inspect the instance head, inferred type arguments, implicit dependencies,
  and corresponding constraints. Pan, zoom, and use the minimap to navigate larger constructions.
- Filter actual constraints by rule, spine, or text. Switch between Unicode and SMT-LIB and
  export a complete solver query, including datatype declarations and recursive definitions.
- Inspect structured assignments or the raw model. The model view identifies the record-label
  encoding and any additional type-validity constraints required by a provisional candidate.
- Step through or play back search attempts, inspect solver statistics, and adjust construction
  and type-height bounds.
- Read the formatted unresolved term in its own column and fold bindings to navigate longer programs.
  Goals retain explicit names; unnamed goals use δ subscripts. Hover for the expected type and result,
  or select a goal to expand its annotation and inspect its synthesis in the right column.
- Inspect the core term, compile without evaluation, export the source or complete
  report, and share source through a compressed URL fragment.
  The current draft is stored in this browser. Source edits mark existing results as stale.

The interface supports resizable panels, narrow screens, dark mode, adjustable editor font
size, and line wrapping. The research guide explains evidence construction and search bounds.

## Results and bounds

Each search report is produced by the compiler. A satisfiable SMT model remains provisional
until its used type arguments satisfy scope and height requirements. Accepted evidence is
then checked by the resolver, and the resulting core term passes the independent core checker.
The goal's **Checked** indicator records that validation; **Accepted model** records the
earlier synthesis step. Model assignments can include unused argument slots.

A matching instance can resolve a goal without an SMT query. Bound exhaustion, proven absence in
the finite fragment, solver unknown, backend failure, and cancellation remain distinct outcomes.
Composite solutions need not be unique, so different backends or runs may find different
valid constructions.

The per-goal search budget covers query construction, checks, and refinement. The execution
timeout bounds the whole worker run, including evaluation. **Stop** terminates that worker
and its solver threads; the next run creates a new worker. Completed attempts remain visible
after interruption, but an interrupted run has no final checked result. These execution controls
are host policy and do not change the calculus's unrestricted fixpoint semantics.

Parser diagnostics have source positions. Later type and synthesis diagnostics currently
describe the failure without a source span.

## Architecture

The dependency direction is `playground → compilerJS`; the shared compiler has no playground
dependency. [The sbt build](../build.sbt) selects common Scala sources plus the platform adapter:

- [Compiler](../src/main/scala/implicitlang/Compiler.scala) owns preparation and resolution.
- [Resolution](../src/main/scala/implicitlang/resolution/Resolution.scala) suspends at evidence
  goals, retains lexical display names, and preserves completed goals after a later failure.
- [SmtSearch](../src/main/scala/implicitlang/synthesis/SmtSearch.scala) owns immutable query
  construction, model validation, refinement, and evidence reconstruction.
- [The SMT algebra](../src/main/scala/implicitlang/smt/Smt.scala) represents sorted formulas,
  datatypes, recursive definitions, observations, and immutable model values. JVM and browser
  adapters execute the same SMT-LIB problem through `SmtSolver[F]`.
- [The bridge](bridge/src/main/scala/implicitlang/playground/Playground.scala) interprets the
  resumable compiler operations asynchronously and renders their structured results according
  to [the TypeScript protocol](bridge/protocol.ts).
- [The worker client](src/compiler-client.ts) owns loading, Comlink transport, and interruption.
  The React interface consumes reports; it does not implement synthesis or type checking.

Name hints belong to implicit binders and are freshened for display. Binding and typing use
de Bruijn indices. Core binders contain indices only; the workbench generates names while rendering them.

## Hosting

Upload the contents of `dist/` to a static host such as GitHub Pages. The build uses relative
asset paths, so it also works below a project path such as `/implicit-synthesis/`.

To check the same files locally with Python's ordinary static server:

```sh
python -m http.server 8000 --directory dist
```

Open `http://localhost:8000`. Deployment requires HTTPS, which GitHub Pages provides;
localhost also works for development. The browser's background-worker support must be enabled.

The site prepares the browser automatically on its first visit and reloads once. The bundled
`coi-serviceworker` helper enables the shared memory needed by the solver, so the static host
does not need custom headers or an application backend. The helper is scoped to this site's
directory and passes requests through to the static host; it does not maintain an offline cache.

## Verification

```sh
npm test
npm run test:e2e
```

The compiler tests discover the example directory and run the Scala.js/WebAssembly pipeline.
Playwright exercises editing, real synthesis, query export, graphs, models, playback,
sharing, cancellation, bound changes, execution timeouts, and responsive layouts.
Set `PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH` to an existing Chromium executable, or install
Playwright's Chromium with a project-local browser directory:

```sh
PLAYWRIGHT_BROWSERS_PATH=.cache/browsers npm exec playwright install chromium
PLAYWRIGHT_BROWSERS_PATH=.cache/browsers npm run test:e2e
```

After `npm run build`, use `PLAYWRIGHT_PREVIEW=1 npm run test:e2e` to test the production site.
To test files served by an existing static host, set `PLAYWRIGHT_BASE_URL` to its URL, including
the project path and trailing slash, for example `http://localhost:8000/research/`.
Run `sbt test` from the repository root for the JVM compiler and solver suite.
Use `npm run format` or `npm run format:check` for the frontend's formatting conventions.
