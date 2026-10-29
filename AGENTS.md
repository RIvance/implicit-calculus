# AGENTS.md

## Primary Objectives

### Build Complete Components, Incrementally

Do not frame implementation work as an MVP, prototype, spike, demo path, or temporary minimal subset unless the task explicitly calls for one. The default goal is a complete, coherent implementation built incrementally by finishing real architectural components one at a time.

Incremental work is expected, but each increment should be a production-quality part of the final system:

- **No throwaway paths:** Do not add prototype-only logic, temporary semantic shortcuts, hard-coded partial tables, fake implementations, or special cases that are expected to be replaced later.
- **No patch fixes:** Do not fix defects with narrow patches, compatibility shims, test-shaped branches, or special cases aimed only at making one failing test pass. Repair the underlying component so the fix naturally applies to the affected behavior class.
- **Complete component slices:** When implementing a component, establish its real abstraction boundary, including its data model, contracts, error handling, tests, and integration points where applicable.
- **Step-by-step integration:** Prefer finishing one real capability at a time over wiring together a shallow end-to-end path containing incomplete components.
- **Explicit incompleteness:** It is acceptable to stage feature coverage, but missing functionality should be explicit. Do not silently substitute simplified or incorrect behavior.
- **Design alignment:** Follow the project's established architecture and design documentation. If implementation pressure reveals that the design should change, change it deliberately and document the decision rather than introducing an undocumented shortcut.

When a requested change is intentionally exploratory or temporary, keep that status explicit and isolate it from production architecture.

### Establish the Contract Before Choosing the Implementation

Identify the valid inputs, promised outputs, recoverable failures, observable
effects, and invariants of the component being changed. Include evaluation
order, identity, and state lifetime when callers can observe them. Determine
which component establishes each precondition and which component may rely on
it afterward.

Do not change a contract merely because a different implementation is easier.
If the existing contract is incorrect or cannot express the requested behavior,
resolve that design issue explicitly before implementing a different contract.
Do not substitute a placeholder result, silently discard a failed check, or
report success for a capability that has not been implemented.

### Generic Design Over Ad Hoc Paths

Design the codebase around generic contracts, reusable representations, and
clear ownership boundaries rather than special cases for particular callers,
tests, interfaces, platforms, or deployment environments.

### No Ad-Hoc Patches

Ad-hoc patches are not acceptable. Trace a defect to the operation that first
creates an invalid state or makes an incorrect assumption. Repair that
operation's contract or implementation so the whole affected behavior class is
handled uniformly. If a general contract is missing, introduce it in the owning
abstraction.

Do not add case-by-case mappings, hard-coded registrations, caller-specific
branches, test-shaped exceptions, duplicated metadata, or downstream fallback
values merely to address the examples currently visible.

When a boundary guarantees an invariant, downstream components should use that
guarantee. If the invariant can be violated by legitimate input, enforce it at
the boundary instead of repeating defensive checks throughout the system.
Checks that protect distinct trust boundaries remain the responsibility of
those boundaries.

The size of the diff is not a reason to retain an incorrect representation or
duplicate an algorithm. Replace an abstraction when its contract no longer
matches its responsibility, and update its callers to use the replacement.


## Design Judgment and Instruction Intent

### Understand the Intent Behind Instructions

**Understand the intent behind instructions rather than mechanically turning them into names, features, files, or additional implementation work.**

Instructions describe requirements, constraints, or desired properties. They should guide design decisions, not automatically become part of the project's identity, architecture, or implementation. Consider what the user actually wants to achieve and how the requested change fits into the existing project.

Examples:

- **Compiler diagnostics** (BAD): Suppose the user asks you to eliminate compiler warnings. Instead of addressing the warnings, you add a build script that filters warning messages from compiler output. The build now appears warning-free, but the underlying problems remain, and future warnings are hidden. The mechanism superficially satisfies the requested outcome while making the project's diagnostics less reliable.

- **Implementation strategy** (BAD): Suppose the user asks you to make an interpreter stack-safe by replacing recursive evaluation with an iterative implementation. You remove the language's recursive-function construct because the instruction mentioned avoiding recursion. The interpreter may now be implemented iteratively, but the language has unnecessarily lost an important feature. Recursion in the implementation and recursion in the interpreted language are different concerns.


### Do Not Invent Unnecessary Mechanisms to Demonstrate Compliance

**Do not introduce mechanisms or make arbitrary changes merely to demonstrate that an instruction has been followed. More importantly, never make decisions that negatively affect the project while technically satisfying the literal wording of a requirement. Judge whether an addition genuinely contributes to the project, considering its goals, context, and overall design.**

Examples:

- **API key handling** (BAD): Suppose the user asks you not to hardcode a private API key in the application's source code. You create a shell script that embeds the actual key to search those source files, then commit the script to Git. Technically, the instruction was followed: the key was not hardcoded in the specified application source files. However, you have committed the private key to the repository, defeating the obvious purpose of the requirement. The checking script itself is not necessarily problematic; a temporary, untracked script might be entirely appropriate. The failure is making an obviously harmful decision while satisfying the instruction literally.

- **Parser replacement** (BAD): Suppose the user asks you to replace a parsing library in a command-line tool that outputs JSON for other programs to consume. You successfully replace the parser, but also add `println("Now using the new parser!")` to the program's standard output to demonstrate the change. The parser works, and the instruction was followed, but the additional message corrupts the JSON output and breaks programs that consume it. The message serves no purpose in the application's functionality; it was introduced solely to advertise compliance.

- **Incidental technology constraint** (BAD): Suppose the user asks you not to use Python in a project. You correctly implement everything in another language, but also rename the project to `Calculator - Python Free`, add "This project does not use Python" to the README, and introduce a check_no_python.sh script into the build. If Python avoidance is merely an implementation constraint rather than a meaningful project goal, these additions contribute nothing and make the project less coherent.

- **Fundamental correctness property** (GOOD): Suppose the user is developing a Lean formalization whose results must not depend on axioms. In this case, adding a script or CI check that detects axiom dependencies can be a valuable improvement. Axiom-freedom is directly related to the project's correctness guarantees, and automated verification helps preserve them. The fact that the requirement is phrased negatively does not make verification unnecessary.

### Judge Changes by Their Contribution to the Whole Project

**Evaluate design decisions by their actual contribution to the project, not by whether they are explicitly requested, optional, or technically compliant.**

Optional improvements are welcome when they contribute positively to the project. Better abstractions, clearer organization, stronger tests, and useful architectural improvements should be considered even when they are not explicitly requested.

Conversely, a change can be undesirable even if it satisfies every explicit requirement. Avoid arbitrary modifications that introduce inconsistency, damage abstractions, unnecessarily break interfaces, complicate the implementation, or otherwise negatively affect the project.

Consider the project's purpose, architecture, conventions, and overall quality. A decision that makes sense in isolation may still be inappropriate in the broader context.

**Exercise genuine engineering judgment. Do not make changes merely to reflect, acknowledge, or demonstrate compliance with instructions. Make changes because they serve a meaningful purpose and contribute positively to the project as a whole.**


## Architecture and Component Boundaries

### Respect Component Responsibilities

Each component should have a well-defined responsibility and communicate through explicit contracts.

Do not allow one subsystem to reach into another subsystem's internals merely because doing so is convenient.

Examples of the general principle:

- parsing or input handling should not perform unrelated domain operations;
- validation should not depend on presentation formatting;
- persistence should not dictate core domain representations;
- rendering or output components should not reconstruct internal processing logic;
- user interfaces should not duplicate domain logic;
- tests should not reproduce private implementation pipelines;
- error handling should rely on structured information rather than parsing human-readable messages.

When two components need to interact, prefer a small explicit interface over knowledge of each other's internal representation.

### Give Stages Distinct Contracts

For every processing stage, identify the representation it accepts, the work it
owns, and the invariant it establishes for the next stage. Split a process
across stages when those stages have different responsibilities, lifetimes, or
invariants. Do not split identical work solely because inputs arrived through
different syntax, interfaces, or callers.

Equivalent inputs must reach the same operation once their differences no
longer affect domain meaning. A later stage should not repeat an earlier
stage's interpretation or validation because the earlier stage did not expose
its result through an adequate contract.

When several stages need the same algorithm over representations with different
invariants, share the algorithm through an interface that expresses its actual
requirements. Do not translate one stage's entire representation into another
stage's representation merely to reuse an implementation.

### Keep Caller Policy Outside the Core

Core representations and algorithms must express established domain meaning,
not the conventions or workarounds of a particular way of supplying input.
Boundary components own user-facing syntax, defaults, input recovery,
configuration, and presentation policy. They must translate those concerns into
explicit domain values before passing them inward.

- Do not carry an input-side fallback, placeholder, or interpretation choice
  into the core when the boundary can establish the actual value.
- Core operations must not reinterpret user input or reconstruct boundary policy
  to determine the identity or meaning of their inputs.
- Keep input descriptions distinct from established domain entities. Merely
  renaming an input field does not repair a misplaced responsibility.
- Diagnostic metadata may accompany domain values without participating in
  normal semantic decisions.
- When input cannot be converted into a valid domain value, report that failure
  at the owning boundary instead of passing a fabricated value downstream.

Core operations must expose their domain result and structured failures without
deciding a particular caller's tolerance, retry, fallback, or presentation
policy. A caller may use a permissive approximation for an advisory operation,
but that approximation must remain in the caller's policy layer and must not
weaken the authoritative operation's validation rules.

If an operation can fail while deciding a condition, preserve the distinction
between a negative answer and an inability to determine the answer. Returning
only `false` for both loses information that another caller may need. The
policy-owning caller may deliberately map particular failures to a negative
answer, a retry, or another documented outcome.

### Provide One Domain Operation for Equivalent Callers

When multiple interfaces need the same capability, expose that capability from
its domain owner. Boundary adapters may translate input, output, or transport
formats; they must not duplicate the domain algorithm or reconstruct a private
sequence of internal operations.

Do not add a separate internal implementation for a test harness, editor,
command-line tool, browser, or another caller when they require the same domain
behavior. If the shared API cannot express an actual caller requirement,
repair that API instead of giving the caller access to private state.

### Dependency Direction

Dependencies should follow the conceptual layering of the system.

- Lower-level/core components must not depend on higher-level presentation or orchestration components.
- Infrastructure-specific concerns should be introduced at boundaries.
- Dependency cycles should be avoided.
- Shared functionality should live at the lowest layer that conceptually owns it, not merely at the first location where it happens to be needed.
- Do not create a generic "utils" layer as a dumping ground for functionality whose ownership is unclear.

Core domain operations must not read process configuration, perform I/O, manage
workers, or choose filesystem and network policies unless that behavior is part
of the operation's domain contract. Supply such dependencies explicitly at the
boundary that owns the effect.

## Representation Discipline

Use representations that make invalid or unintended states difficult to express and that preserve the distinctions relevant to each phase or subsystem.

### Use Domain-Appropriate Representations

Use one authoritative representation for the same semantic concept within the
same role and phase. Reuse the existing representation when its meaning and
invariants match. Do not introduce a parallel type, synchronized metadata map,
or wrapper that merely renames the same information for a different caller.

Do not force substantially different concepts into one universal representation merely to reduce the number of types or files.

When information changes meaning or invariants across stages, separate representations may be appropriate. Reuse containers and infrastructure when their semantics are genuinely shared, but do not conflate concepts solely because their current structures look similar.

For example, unvalidated input and a validated domain value have different
contracts even when some fields are identical. The conversion between them
must establish the validated value's invariants. Constructors and public update
operations must preserve those invariants afterward.

Do not use impossible type arguments, sentinel values, or flags solely to
disable variants that cannot exist in a particular phase. Give that phase a
representation that expresses its actual states. Sharing containers and
algorithms does not require retaining semantically invalid variants.

Prefer:

- explicit domain types;
- algebraic data types or equivalent closed representations for closed alternatives;
- typed identifiers where identity matters;
- structured errors and diagnostics;
- explicit state transitions;
- immutable values where practical;
- scoped state rather than hidden shared state.

### Separate Provisional Information from Established Facts

A guess, preview, approximation, or incomplete result must not become an
authoritative value merely because it is available. Specify the validation
required before a provisional result can be committed. Keep any remaining
obligations attached to the computation that must discharge them.

Represent unknown information explicitly when it is a legitimate state. Do not
replace it with a convenient concrete value that downstream code will treat as
known. Successful completion must establish every guarantee promised by the
operation, including obligations that are not visible in its returned value.

Diagnostic and provenance metadata may accompany domain values, but must not
determine semantic identity or alter an algorithm's answer unless that metadata
is explicitly part of the domain contract.

### Avoid Primitive Obsession

Do not represent semantic concepts using raw strings, integers, booleans, or loosely structured collections when a dedicated representation would encode important invariants.

In particular:

- **Closed sets:** Represent genuinely finite sets of modes, states, categories, or variants with an enum, tagged union, sum type, or equivalent typed abstraction.
- **Open identifiers:** Do not turn naturally extensible identifiers into closed enumerations. User-defined names, plugin identifiers, external service names, paths, protocol values, and similar open sets should remain appropriately typed open values.
- **Semantic identities:** Do not use incidental numeric positions or array indexes as persistent semantic identities unless positional identity is genuinely part of the model.
- **Structured state:** Avoid parallel arrays, synchronized lookup tables, or multiple unrelated maps representing different pieces of one conceptual entity.

Identity allocation must prevent collisions among entities that can coexist or
be compared, including entities retained in snapshots or created during
speculation. Keep fresh-identity generation explicit and deterministic when
the contract permits it; do not hide a process-wide counter inside an otherwise
pure operation.

### No String-Based Semantic Computation

Do not convert structured semantic values to display strings in order to perform internal computation.

Avoid using pretty-printed or serialized forms for:

- equality;
- ordering;
- hashing;
- matching;
- dispatch;
- normalization;
- identity;
- map or set keys;
- state transitions.

Operate on the structured representation directly.

Strings are appropriate where text is genuinely the data, including source text, user-provided strings, external identifiers, diagnostics, logging, serialization, debugging, and presentation.

### Encapsulate Semantic Entities

When a domain contains a family of related entities, each entity should have a clear abstraction boundary that owns its relevant data, metadata, invariants, and behavior.

Do not split one concept across parallel structures such as:

- a name table;
- a metadata table;
- a dispatch function;
- an arity or capability table;
- a validation function;
- an execution function;

when these pieces must always evolve together.

Adding a new entity should normally extend one coherent abstraction rather than require synchronized edits across unrelated parts of the codebase.

### Put Behavior at the Owning Boundary

Behavior intrinsic to a representation should live with or immediately adjacent to that representation.

Examples include:

- construction and validated construction;
- decomposition and structural queries;
- invariant checking;
- normalization intrinsic to the representation;
- metadata access;
- operations whose meaning depends only on that representation.

Behavior connecting two distinct representations or subsystems belongs at their architectural boundary.

Do not create separate service objects or helper modules merely to move behavior away from the type or module that naturally owns it.

### Use Abstraction Appropriately

Choose the mechanism appropriate to the language and problem:

- data types for structured data;
- enums/tagged unions/sum types for closed alternatives;
- classes or structs for entities with cohesive state and behavior;
- interfaces, traits, protocols, or typeclasses for genuine contracts and substitution boundaries;
- modules or namespaces for cohesive functionality;
- pure functions for transformations without meaningful object identity;
- builders only when construction is sufficiently complex to justify them.

An abstraction must own an invariant, remove duplicated behavior, or express a
variation that callers actually need. A forwarding layer that adds no such
contract makes ownership harder to follow. Small pure helper functions do not
need a class or service object solely to give them a place to live.

Use instance methods when one value owns the operation. Keep symmetric
relations between peer values neutral instead of arbitrarily choosing one
operand as their owner.

## State, Mutation, and Speculation

### Use Immutable Values and Explicit Successors

Core state transitions should be functions from an input state to a result and
a successor state. Thread accumulators, identity supplies, and other evolving
domain values through those transitions. Use immutable accumulators with folds
or tail recursion for collection processing; do not use mutable builders or
side-effecting local collectors in core algorithms.

A retained state must continue to describe the same facts after another
operation succeeds or fails. An immutable record containing a mutable object
does not provide this guarantee. Neither a state-passing wrapper nor a promise
to stop mutating the object makes the retained state immutable.

Use an immutable domain representation directly when it can express the
transition. Do not maintain equivalent mutable and immutable domain models
solely to support a mutation-and-snapshot protocol that the immutable model
does not need.

### Contain Mutation at Its Owner

If an operation uses mutation, identify the mutable data's owner, lifetime, and
allowed aliases. An operation-local implementation may mutate temporary data
only when callers cannot observe that data and it cannot change any retained
input or previously returned result.

When callers require a snapshot, return values that cannot be changed through
another reference. Copying only the outer container is insufficient if its
elements retain mutable aliases. Do not return a mutable worker, builder, or
session as though it were an immutable domain value.

Do not use mutable global storage, hidden singleton state, or implicit shared
registries for domain computation. Process-wide external resources must have
an explicit lifecycle and be passed through the boundary responsible for them.
Measured performance does not create an exception to the restrictions on
semantics, type systems, module systems, frontends, and parsers below.

### Make Speculative Isolation Part of the Contract

A speculative operation starts from the caller's established facts and
identities. It must be able to use those facts without changing the caller's
retained state. Identities allocated for tentative entities must not collide
with existing identities.

Define what success commits and what failure discards. Tentative assignments,
warnings, and other accumulated results must not leak from an abandoned
computation into another branch. Preserve the structured failure itself so the
caller can decide how to handle it.

Rolling back an identity supply is valid only when references to the discarded
identities cannot later be confused with newly allocated entities. Returning a
small result, such as a Boolean, does not by itself establish isolation of the
state used to compute it.

Use independent immutable successor states when possible. If speculation
includes external effects, give those effects an explicit commit, rollback, or
compensation contract; discarding an in-memory value does not undo I/O.

## Source Organization

Organize source code according to conceptual ownership rather than arbitrary file-size rules or mechanical one-type-per-file conventions.

- Keep cohesive definitions together.
- Split modules when they contain independently meaningful concepts or become difficult to navigate.
- Avoid both giant catch-all modules and forests of trivial files.
- Keep tightly coupled builders, helpers, and representations together unless they have substantial independent behavior.
- Use directories/packages/modules to communicate architectural domains.
- Keep public surfaces deliberate; do not expose implementation details merely because doing so is convenient.
- Follow the language ecosystem's standard module and package conventions unless the project explicitly establishes different ones.

Repository structure should make architectural boundaries easier to understand, not merely make files shorter.

## Error and Diagnostic Discipline

Errors should be structured at the point where they originate and rendered for humans only at presentation boundaries.

- Do not infer machine-readable error information from formatted error strings.
- Preserve structured context needed for diagnostics.
- Distinguish internal invariant failures from expected user-facing errors.
- Avoid swallowing errors or silently substituting fallback behavior unless fallback is explicitly part of the contract.
- Error messages should describe the actual failure and, where useful, provide actionable context.
- Do not expose implementation details unnecessarily in user-facing diagnostics.
- Preserve underlying causes when propagating errors across abstraction boundaries.

Use the project's explicit result type for recoverable failures in pure logic.
Do not turn a normal domain failure into an exception or convert it to a
generic error that loses the failed operation's structured context. Boundary
code may translate error types, but the translated error must retain the
distinctions the next component needs.

An internal invariant failure means the implementation violated its contract;
an invalid user input is an expected domain failure. Keep these categories
separate. Catching both and returning the same fallback value hides defects and
prevents callers from implementing their own failure policy.

## Testing Discipline

Tests should validate behavior at the most appropriate stable boundary.

### Test Behavior, Not Implementation Accidents

Prefer tests that exercise public or intentionally stable interfaces.

Do not make tests unnecessarily dependent on:

- private helper functions;
- internal data layout;
- incidental ordering;
- debug formatting;
- temporary implementation details;
- duplicated internal algorithms.

A refactoring that preserves the component's contract should not require widespread test changes.

### Use the Appropriate Test Level

Use different test levels for different purposes:

- **Unit tests:** Small, isolated behavior with a meaningful local contract.
- **Component/integration tests:** Interaction between related components through their public or stable interfaces.
- **System/end-to-end tests:** User-visible behavior across the complete relevant pipeline.
- **Regression tests:** Minimal cases demonstrating previously incorrect behavior and preventing recurrence.

Prefer the highest-level test that expresses the intended semantic behavior clearly, supplemented by lower-level tests where they improve localization, coverage, or edge-case precision.

### Fix Code, Not Tests

Do not change an established expected result merely because the implementation currently produces something different.

When a test fails:

1. determine the intended contract;
2. determine whether the implementation or expectation is wrong;
3. fix the incorrect side;
4. document intentional contract changes.

Do not add test-specific production branches.

### Regression Tests Should Generalize

A regression test should capture the underlying behavior class, not merely reproduce an accidental input.

When fixing a bug, consider:

- the minimal reproducing case;
- nearby edge cases;
- equivalent forms of the same behavior;
- boundary conditions;
- interactions with adjacent components.

If a fix only works for the exact failing example, reconsider whether the underlying defect has actually been repaired.

For stateful or persistent APIs, test retained inputs after both successful and
failed operations, independent branches derived from the same state, and the
documented commit or rollback behavior. If unrelated input or allocation order
must not affect a result, test that independence explicitly.

### Test Infrastructure Must Remain External

Test harnesses may orchestrate public APIs and provide fixtures, mocks, fakes, generators, or environment setup, but they should not duplicate private production logic.

If testing requires reconstructing substantial internal behavior, the production API or component boundary may be wrong.

## Implementation Discipline

### Follow Local Language Conventions

Match the established source style and language idioms of the surrounding
code. In particular:

- preserve the project's delimiter and formatting style rather than mixing
  substantially different syntax styles;
- keep very short expressions and pattern-match branches inline; use readable
  multiline formatting for long cases, with 120 columns as the maximum rather
  than a target; when a `case` pattern spans lines, align its closing delimiter
  and `=>` with the opening `case`;
- when code or comments mirror a formal specification, preserve its Unicode
  notation instead of replacing the symbols with ASCII transliterations;
- prefer complete, descriptive names, reserving abbreviations for terms that
  are genuinely conventional in the language or domain;
- in pure functional logic, represent recoverable failures with the project's
  explicit result type instead of a generic disjunction; exceptions remain
  available at imperative boundaries and for violated internal invariants;
- place reusable, cross-cutting abstractions in neutral utility packages
  rather than inside a feature package;
- before referring to a repository path from versioned documentation, verify
  that the referenced path is version-controlled; do not expose ignored or
  local-only inputs through committed documentation;
- combine object-oriented ownership with functional transformations: keep
  behavior intrinsic to a representation on that representation's API, use
  an instance method only when one value meaningfully owns the operation, and
  keep symmetric peer relations neutral rather than arbitrarily privileging
  one operand as the receiver.

### Understand Before Modifying

Before making a nontrivial change:

- identify the component that owns the behavior;
- inspect its public contract and neighboring components;
- locate existing tests;
- understand the relevant representations and invariants;
- check design documentation and established conventions;
- determine whether the requested behavior already exists elsewhere.

Do not start by editing the first function associated with the observed symptom.

### Replace Superseded Representations and Behavior

Before introducing an abstraction, check whether an existing component already
owns the responsibility. If it does, extend or repair that component's contract.
If the old abstraction cannot express the required behavior without duplicated
state or exceptions to its invariants, replace it and migrate its callers.

When a replacement subsumes the old behavior, remove the old implementation,
adapters, flags, registrations, and metadata that exist only to support it. Do
not keep equivalent old and new paths merely to avoid changing call sites.

If compatibility is an actual requirement, identify the callers or stored data
that need it. Keep translation at the compatibility boundary, use the same
domain implementation underneath, and specify the condition under which the
compatibility path can be removed. An unexplained legacy branch is not a
compatibility contract.

### Make Information Loss an Explicit Design Decision

Additional metadata or a nonessential capability must not force duplicate
representations of the same semantic object. Removing such information can be
the correct design decision, but identify what is lost and which operations or
diagnostics become less precise. If an existing contract promises that
capability, changing the contract must be part of the design decision.

Do not discard information needed to establish semantic validity, discharge pending
obligations, preserve identity, or distinguish required failure cases. A
simpler representation is acceptable only when it still enforces the agreed
semantic guarantees.

## Documentation and Comments

Comments should explain information that is not obvious from the code itself.

Useful comments include:

- invariants;
- architectural intent;
- non-obvious semantic choices;
- reasons for unusual implementation decisions;
- external constraints;
- subtle edge cases;
- intentionally deferred work.

When code directly implements a formal rule, place the complete rule near its
implementation: show its premises, rule name, and conclusion using the
formalism's native notation. A rule identifier alone is not sufficient.

Avoid comments that merely restate syntax or narrate straightforward code.

Public APIs and domain representations should be documented when their contract, invariants, or semantics are not self-evident.

When implementation and design documentation disagree, resolve the disagreement deliberately. Do not allow documentation to silently become historical fiction.

Explain the conditions under which a rule applies and what behavior it requires.
For a stateful API, document ownership, retained-value guarantees, and success
and failure transitions. For an algorithmic transformation, document the
assumptions and preservation argument. Labels such as "safe", "clean", or
"appropriate" do not specify those conditions. Use the detail each rule needs
instead of shortening every explanation to fit a uniform template.

## Theoretical Correctness and Optimization

### Distinguish Theoretical Guarantees from Implementation Defects

In the priorities below, correctness means theoretical soundness and
completeness with respect to the specified model:

- Soundness means that an accepted judgment, result, or transformation is
  justified by that model. A transformation must preserve the program behavior
  that the model requires it to preserve.
- Completeness means that the algorithm covers every case promised by its
  specification. An optimization must not introduce additional rejection of
  valid inputs, omit required cases, or impose an undocumented restriction on
  the supported language.

Completeness is relative to the stated algorithm and its supported domain. Do
not claim completeness for a deliberately incomplete algorithm. State its
limits, and do not introduce further incompleteness as a performance shortcut.
This requirement does not mean that an optimizer must discover every possible
optimization or that an editor must offer every conceivable suggestion.

Freedom from implementation defects is a baseline requirement for every
component, including plugins and playgrounds. Lower priority for theoretical
guarantees never permits known bugs, broken contracts, data corruption, or
incorrect results. Tests address implementation behavior; passing tests alone
does not establish theoretical soundness or completeness.

### Priorities by Component Responsibility

**Language semantics and type systems have the highest priority for theoretical
soundness and completeness.** Their rules define program meaning and which
judgments are valid. Keep implementations directly connected to those rules.
Do not trade any theoretical guarantee for speed, allocation reduction, or a
more convenient implementation. Every optimization requires a soundness
argument and preservation of the algorithm's stated completeness guarantees.

**Module systems also give theoretical correctness very high priority, below
semantics and type systems.** Preserve the specified rules for binding,
identity, visibility, dependency handling, and composition. An optimization
must not make results depend on incidental loading order, omit a required
dependency, or change which programs can be linked or accepted. The lower
relative priority does not permit sacrificing any of these guarantees.

**Compiler frontends give theoretical correctness very high priority while
also treating performance as an important design concern.** They have more
reason to pursue optimization than the semantic and type-system foundations,
but only theoretically sound optimizations are allowed. Preserve accepted
programs, rejected programs, and the meaning of translated results according to
the specified analysis. Do not weaken validation or use an incomplete shortcut
to make compilation faster.

**Parsers give theoretical correctness lower relative priority than the
preceding components.** They must still recognize the specified grammar and
construct the specified representation, including precedence and grouping.
Performance work must not change the recognized language, silently skip input,
or replace grammar rules with approximations. Lower relative priority does not
authorize a loss of soundness or completeness.

For semantics, type systems, module systems, frontends, and parsers, no
optimization may sacrifice any specified theoretical guarantee. Ad hoc
exceptions, fabricated successful results, skipped checks, and test-shaped
shortcuts are prohibited. Performance pressure does not make these techniques
acceptable.

**Compiler backends, such as IR-to-LLVM translation, may use more aggressive
optimization than frontends.** Target-specific lowering and more elaborate
optimization passes are permitted when their applicability conditions and the
source and target semantics justify them. Aggressiveness concerns the choice
and scope of transformations; it does not permit miscompilation, unsupported
assumptions, or knowingly unsound rewrites. Preserve the observable behavior
required by the source IR and respect the target's execution model.

**Editor plugins, playgrounds, and other user-facing tooling prioritize useful
functionality over establishing formal soundness and completeness for their
own interaction behavior.** They must still implement their declared behavior
without known defects. Usability and responsiveness can guide their design
without requiring a formal model of every UI interaction. Their priorities do
not weaken the guarantees of semantic operations they call: an editor must not
modify type checking rules to produce a convenient answer.

These categories describe responsibilities, not directories, deployment
targets, or callers. A type checker used by a backend or packaged inside a
plugin remains type-system code and retains the strongest requirements. When
responsibilities overlap, apply the stricter rules to the shared operation.

### Justifying an Optimization

Before optimizing semantics, a type system, a module system, a frontend, or a
parser, establish why the transformation is theoretically sound and preserves
the component's specified completeness guarantees. Explain the model and
assumptions, the invariants preserved by each transformed step, and the
preservation of results, domain failures, effects, and observable ordering.
Address termination when changing recursion or scheduling. Benchmarks and
claims that a technique is standard do not replace this reasoning.

For example, a trampoline may replace recursion to avoid exhausting the host
call stack when its explicit continuations implement the same evaluation order,
state transitions, and error propagation as the recursive algorithm. Explain
that correspondence and why every supported computation remains expressible.
Merely calling an implementation a trampoline is not a justification.

### Cache and Pool Restrictions

Caches, memoization tables, object pools, and memory pools are prohibited in
semantics, type systems, module systems, frontends, and parsers. This prohibition
applies to operation-local, shared, and global instances, including ones
introduced through a helper or dependency on behalf of those components.
Encapsulation, immutable cached entries, measured performance benefits, or a
soundness argument do not create an exception to this ban.

Distinguish algorithm-required state from performance storage. A set of visited
nodes required by a cycle-detection rule is part of that algorithm's semantics.
A table of derived answers retained to avoid recomputing them is a cache.
Calling a cache a context, session, or snapshot does not change its role.

Backend and tooling components may use performance mechanisms allowed by their
contracts. Measure the relevant cost before introducing them. For a cache,
specify ownership, lifetime, validity conditions, and invalidation behavior.
For a pool, specify exclusive ownership during use, release conditions, and
the state reset required before reuse. These mechanisms must not cause
miscompilation, stale authoritative results, or another implementation defect.

Do not move an optimization into a wrapper to evade the restrictions. A tooling
cache of presentation assets and a cache that supplies answers to a type checker
serve different responsibilities; packaging the latter in a plugin does not
make it permissible.

Performance requirements do not waive the theoretical guarantees or the cache
and pool prohibition for the components to which it applies. If the requested
performance cannot be met within those rules, identify that conflict instead
of introducing a forbidden optimization. Do not choose a known pathological
algorithm when an established alternative preserves the same contract and
invariants without the pathological behavior.

## Concurrency and Asynchrony

Use concurrency when it reflects actual independent work or satisfies a demonstrated performance/latency requirement.

- Do not introduce concurrency merely to make architecture appear scalable.
- Make ownership and synchronization explicit.
- Avoid shared mutable state where message passing, immutable values, or isolated ownership is sufficient.
- Propagate cancellation and failures according to the surrounding platform's conventions.
- Do not block asynchronous execution contexts with long-running synchronous work unless explicitly appropriate.
- Make ordering guarantees explicit when observable behavior depends on them.

Parallel work must not share mutable domain state without an explicit ownership
and synchronization contract. Do not parallelize dependent state transitions
and assume their results can be merged afterward. Define how partial failure
and cancellation affect published results and outstanding work.

Introducing concurrency is subject to the component's theoretical guarantees
and optimization restrictions above. Demonstrating that tests usually pass
under one schedule does not establish that a transformation preserves its
contract under all permitted schedules.

## Extension Follows Ownership

Adding a member of a supported family should extend the component that owns
that family. Related changes inside that component may be necessary, but a new
member must not require synchronized edits to unrelated name tables, metadata
maps, dispatch services, and validation code. Such synchronization indicates
that the entity's definition or behavior has been split across owners.

Introduce interchangeable implementations when callers already require that
variation, and state the contract every implementation must satisfy. Do not add
plugin frameworks, registries, or abstract service layers solely for imagined
future callers. Preserve the direct representation when it already contains
the information needed to perform the operation.
