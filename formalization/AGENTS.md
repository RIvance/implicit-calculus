# Lean Agent Guide

## Some Guidelines

* **NEVER USE AXIOMS:** Never use axioms in any case! Never use axioms in any case! Never use axioms in any case!
* **Correctness Over Speed:** Correctness and systemic consistency strictly override proof completion speed or minimizing code changes.
* **Handling Incomplete Proofs & Sorries:** If a theorem is genuinely unfinished or blocked by missing infrastructure, explicitly leave a `sorry` or state the missing lemma. **Crucially, leaving a `sorry` does NOT mean you should stop!** Leave the `sorry` there and immediately move on to more important goals. You **MUST NOT** hide logical gaps inside definitional equality or by creating new primitive axioms unnecessarially.
* **Never Stop Working:** Do not just hang there after a `sorry`. The agent should never stop working until it absolutely cannot proceed (e.g., a critical error that would make the entire project inconsistent and there is no known way to fix it). Keep moving forward!
* **No Workarounds:** Workarounds, stubs, and placeholders are strictly prohibited. Never add overly broad judgment rules just to force a proof to succeed.
* **Rule Modification Validation:** Any modification to core code rules or judgments MUST be rigorously verified for consistency with the existing system and strict alignment with the underlying type theory.
* **Refactoring Over Patching:** If forced to choose between a minimal patch rule and a structural refactor that yields a more robust, elegant, and consistent system, you MUST choose the refactor. "Minimal change" is not a valid priority.
* **Authorized Breaking Changes:** You are authorized and expected to break existing proofs if fixing a critical core issue requires it. Correctness and consistency always supersede backward compatibility.

## User Interaction and Task Scope

* **Respect Discussion-Only Requests:** Questions, design discussions, and
  requests explicitly described as exploratory do not authorize file changes.
  Use read-only inspection when it helps the discussion, present alternatives
  and consequences, and wait for an explicit decision before implementing one
  of them. A suggestion to record possible work in a plan is not authorization
  to perform that work immediately.
* **Render Mathematics for Humans:** In user-facing explanations, write
  mathematical expressions with readable Unicode notation such as `∀`, `→`,
  `∧`, `⊢`, and `λ` instead of exposing raw LaTeX commands. Show LaTeX source
  only when the user asks for source or when the literal source text is itself
  under discussion.
* **Show the Equation and Example First:** When asking a calculus-design
  question, show the exact equation or inference rule at issue, for example
  `(λx. e₁) ,, (λx. e₂) = λx. (e₁ ,, e₂)`, followed by a small concrete
  example that makes the decision clear. Explain what each program does in
  plain language. Keep the question focused on the core mathematical issue.
  **Never use unexplained jargon or invented terms without a definition or
  explanation.** Prefer established terminology; define or explain any new
  or unfamiliar term before relying on it. Do not substitute vague labels
  such as "source factoring conditional" for the actual equation and example.

## Mathematical Models and Implementations

Mathematical definitions must reflect the intended abstract objects and laws.
Do not change the intended mathematics solely to accommodate a particular
implementation or proof technique.

## Proof Work

* **Important Work First** If you are working on definition part like syntax and semantics, then you shouldn't be attacted by proof details. In this stage, you should always leave a `sorry` for further implementation instead of diving in to proofs immediately.
* **Continuous Momentum:** Before you ever decide to stop, actively check whether there is more work that can be carried on. Do not stop prematurely just because you reached a minor milestone or encountered a difficult step. Get every possible piece of work done. If you leave a `sorry`, immediately look for the next lemma or the next step in the formalization.
* **Precise Statements:** Prefer precise theorem statements over imprecise rules that can be simply proved. **Some `sorry`s in the early stage are acceptable—this means leave the `sorry` and proceed to other tasks, do NOT use it as a reason to stop working.**
* **Interactive Inspection:** Use Lean's language server and interactive proof-state inspection when possible to guide your tactics.
* **Checkpoints:** Reaching a checkpoint doesn't mean you should stop working; you need to actively find out what remains unfinished.
* **Verification:** If a proof suggestion comes from another agent or an old comment, verify it against the current definitions before using it.

## Reflect & Continuous Working

After drafting any modification, explicitly pause to reflect on your work.

**Proceed to Priority Work:** If everything is okay after your check, you must continue working on the remaining proofs. Do not stop. Always start with the most important or foundational unproven lemma/theorem next.

## Version-Control Checkpoints

Create commits at coherent, verified milestones rather than accumulating one
large uncheckpointed change. A milestone should represent one understandable
unit of progress and should pass the relevant build, tests, linters, or
document compilation before it is committed.

Keep each commit focused. Do not stage unrelated pre-existing changes, and do
not use a checkpoint as a reason to stop while important in-scope work remains.

# Research Documents and Mathematical Writing

## Manuscripts and Progress Records

When a repository contains a research manuscript, write it as a self-contained
presentation of the current theory. A reader must not need the Lean sources,
private notes, or another document to understand its definitions and claims.
Do not insert proof-status reports, migration history, abandoned designs, or
implementation diaries into a paper whose purpose is to present the theory.

If progress, experiments, unresolved questions, or formalization status must
be recorded, use a separate progress document. That document may point to the
manuscript or source tree for traceability, but it must still explain enough
context to be understandable on its own.

## Notation and Binders

Use named binders in papers, specifications, and human-readable derivations.
De Bruijn indices are appropriate inside a formalization, or in a document
whose subject is an indexed representation, binding implementation, or a
canonicity result that depends on that representation. They are not suitable
as the default notation for ordinary object-language programs and judgments
in a research paper or other human-oriented material.

Translate internal indexed syntax back to conventional paper notation rather
than making readers reconstruct binder names mentally.

Reserve inference-rule bars for rules defining judgments. State proved results
(theorems, lemmas, corollaries, and propositions) using standard mathematical
notation, such as equations, implications, or explicit hypotheses and conclusions,
without inference-rule bars.

## Builds and Generated Artifacts

After compiling a paper or other generated document, keep the intended primary
output when the user or repository workflow expects it. In particular, do not
delete a requested PDF merely because it is generated.

Remove disposable compilation artifacts such as `.aux`, `.log`, `.out`,
`.toc`, `.fls`, `.fdb_latexmk`, and temporary synctex files. Do not add such
intermediate files to version control unless the repository explicitly treats
one of them as a source artifact. Prefer an isolated untracked build directory
when the document toolchain supports one.

# Lean Style Guide: PL Metatheory & Type Theory Formalization

When generating, refactoring, or completing Lean 4 code for programming language metatheory, you must strictly adhere to the following guidelines.

## Naming Conventions (Lean 4 Standard)

Lean 4 uses strict naming conventions to cleanly differentiate the universe level of objects. Maintain the following structural casing:

* **Types, Inductives, Type Classes, and Prop/Relations:** `UpperCamelCase` (e.g., `Term`, `HasType`, `Step`, `Equiv`).
* **Value Variables, Functions, Proofs, Lemmas, and Theorems:** `lowerCamelCase` (e.g., `substDimInTerm`, `progress`, `preservation`, `faceSubstDim`).
* **Proof Roles:** Use `lemma` for supporting results and `theorem` for the main mathematical properties. Classify results by their role in the development, not by proof length.
* **Data Constructors and Relation Constructors:** `lowerCamelCase`. Lean automatically namespaces constructors (e.g., `HasType.var`, `Step.beta`), so you do not need clunky prefixing like `T_Var` or `Step_Beta` unless flattening the namespace.

## Notation Flexibility & Inductive Judgments

While the reference paper provides the baseline for syntax, **prioritize readable, valid, and ergonomic Lean code over strict visual alignment.** You are encouraged to invent or improve notations to make the formalization cleaner.

* **Unicode Is Mandatory:** Never write ASCII stand-ins for formal symbols, paper metavariables, or object-language notation. Use Unicode names and notation such as `Σ`, `Δ`, `Ω`, `Γ`, `κ`, `τ`, `σ`, `γ`, `δ`, `⋆`, `≃`, `<:`, `⊢`, `∶`, `⦂`, `⟦_⟧`, `▷`, and `◁`. If an ASCII spelling of a formal symbol would be written in code or comments, automatically convert it to the corresponding Unicode symbol. The only allowed ASCII is Lean-required syntax, keywords, module/file names, ordinary English prose that is not a formal symbol, and the conventional mathematical colon in paper-style inference-rule comments. In those comments, write `x : A ∈ Γ` and `Γ ⊢ e : A`; reserve notations such as `⦂` and `∶` for the corresponding Lean syntax when ordinary `:` would conflict with the parser.
* **Paper Metavariables:** In rules and theorem statements, use the paper's metavariables exactly when Lean permits them: `Σ ; Δ ; Ω ; Γ` for contexts, `κ` for kinds, `φ` for propositions, `τ σ υ ρ` for types, `R S` for rows, `e` for terms, `γ` for equality coercions, and `δ` for structural coercions. Lean rejects `Σ` as an identifier and as a notation binder, so bind the signature as `Ψ` only where a real Lean identifier is required; comments should still use the paper's `Σ`.
* **No Generic Formal Binder Names:** In inference rules, constructor statements, theorem statements, and notation-facing proofs, never use generic ASCII-ish names such as `left`, `right`, `kind`, `src`, `tgt`, `term`, `body`, `core`, `fields`, `row`, or `label` when the paper/comment has metavariables. Write the same metavariables as the rule: for example `∀ {Γ e A}` for a typing rule written `Γ ⊢ e : A`, and `∀ {Γ A B}` for a subtyping rule written `Γ ⊢ A <: B`.
* **Always Prefer Notations:** You must explicitly prefer using defined notations over direct constructor calling. If a notation is available for a term, type, or relation, you should always use it in your proofs, definitions, and theorems rather than writing out the raw constructors or types.
* **Fix Broken Notation:** If a notation causes parsing or elaboration problems, fix the notation declaration, precedence, scoping, or local expression shape. Do not replace notation uses with raw relation names or constructor-style code as a workaround.
* **Reserved Notation Paradigm:** In Lean, it is impossible to use "reserved notation ... where" as in Coq. Instead, define notations using `scoped infix` or `scoped notation` within a `section`.
* **Handling Unbound Variables in Notations:** Use `set_option quotPrecheck false` before the notation definition so Lean doesn't complain about undefined identifiers during the syntax declaration.

**Example of properly scoped inductive relations with notations:**

```lean
section
  set_option quotPrecheck false
  scoped infix:80 " ↪⋆ " => MultiStep

  -- Notice how the notation `↪⋆` and `↪` are used directly in the constructors
  -- instead of `MultiStep` and `Step`.
  inductive MultiStep : Exp → Exp → Prop where
  | refl : e ↪⋆ e
  | step : (e ↪ e') → (e' ↪⋆ e'') → (e ↪⋆ e'')
end

```

> **Note:** If the entire file is dedicated to the semantics definition, you do not have to wrap this in an independent `section` to avoid `set_option quotPrecheck false` leaking to the rest of the project.

## Lean Comments and Rule Documentation

### Section Headers

Never use large, ASCII-art banner comments. They clutter the file and are difficult to maintain. Use Lean's module docstrings (`/-! ... -/`) or standard section headers.

**Bad:**

```lean
/-- ========================================================================= -/
/-- ** Value Application                                                      -/
/-- ========================================================================= -/

```

**Good:**

```lean
/-! ### Value Application -/
-- --- Value Application ---

```

### Inference Rule Documentation

When formalizing typing rules or semantics, declare the original, human-readable inference rule as a comment directly above the constructor. You are also encouraged to write the inductive definition in a shape of the paper-style inference rules.

Files containing paper-style inference rules should set
`set_option linter.style.emptyLine false`. In such files, leave exactly one
empty line between adjacent rule constructors. Keep each rule comment attached
to its constructor: do not put an empty line between the last line of the rule
comment and the constructor itself.

**Example:**

```lean
set_option linter.style.emptyLine false

inductive HasType : Ctx → Exp → Ty → Prop where

-- [ Variable ]
-- x : A ∈ Γ
-- ───────────── Typ-Var
-- Γ ⊢ x : A
| var :
    (Γ ∋ x ⦂ A) →
    Γ ⊢ ($x) ∶ A

-- [ Abstraction ]
-- Γ, x : A ⊢ e : B
-- ───────────────── Typ-Abs
-- Γ ⊢ λx. e : A → B
| abs :
    (Γ ▷ A) ⊢ e ∶ B →
    Γ ⊢ (ƛ e) ∶ (A ⟶ B)

-- [ Application ]
-- Γ ⊢ e₁ : A → B    Γ ⊢ e₂ : A
-- ───────────────────────────── Typ-App
-- Γ ⊢ e₁ e₂ : B
| app :
    Γ ⊢ e₁ ∶ (A ⟶ B) →
    Γ ⊢ e₂ ∶ A →
    Γ ⊢ (e₁ · e₂) ∶ B

```

## Strict Block Indentation (Avoid Deep Alignment)

When writing `match` statements or applying functions with multiple complex arguments, **NEVER use deep, start-of-the-line alignment**. Deep alignment breaks standard indentation rules and shatters when variable names change.

Inline column alignment is only acceptable for short, simple patterns. For complex expressions, wrap to the next line and use standard 2-space block indentation.

**Acceptable (Simple Inline Alignment):**

```lean
match e with
| .lit n     => .lit n
| .var x     => .var x
| .add e₁ e₂ => .add (simplify e₁) (simplify e₂)
| .mul e₁ e₂ => .mul (simplify e₁) (simplify e₂)
| .call f es => .call (simplify f) (es.map simplify)
```

**Bad (Deep Alignment):**

```lean
-- BAD: Indentation depends on the length of the constructor name.
| .branch cond yes no => .branch (simplify cond)
                                 (simplify yes)
                                 (simplify no)
```

**Good (Strict Block Indentation):**

```lean
-- GOOD: Arguments form one consistently indented block.
| .branch cond yes no =>
  {
    .branch
      (simplify cond)
      (simplify yes)
      (simplify no)
  }
| .call function arguments =>
  {
    .call
      (simplify function)
      (arguments.map simplify)
  }
```

## Lean Proof Formatting

Do not put only a few characters on a line. Prefer a natural wider Lean style: keep short tactic blocks inline, group obvious named arguments on the same line, and only break expressions when the break improves readability.

### Explicit Proofs and Simplification

Prefer explicit proofs whose logical steps are visible. Use direct lemma
applications, `exact`, `apply`, `rfl`, `rw`, `change`, `cases`, `constructor`,
or `calc` when they express the argument clearly. Do not replace a short,
readable proof with a simplifier call that hides its structure.

Use `simp only [...]` or `simpa only [...]` when a small, explicit set of
rewrites makes the proof clearer. Keep that set focused. If understanding the
call requires reconstructing a long sequence of transformations, write those
steps explicitly instead. Reserve unrestricted `simp` and `simpa` for cases
where their implicit rewrites are appropriate and improve readability.

Prefer constructive lemmas and avoid introducing classical reasoning merely
to make automation succeed. `simp only` limits the rewrite set; it does not
remove classical dependencies from the lemmas it uses. Inspect
`#print axioms theoremName` when auditing those dependencies.

### Rewrite-List Layout

Keep a short rewrite list entirely inline. If it must span multiple lines,
put the opening `[` at the end of the tactic line, start all entries on the
next line, and align the closing `]` with the tactic. Group related entries
on one line when they fit. Never start the list beside the tactic and then
wrap only its remaining entries.

Bad:

```lean
simp only [List.map_append, List.map_map,
  Function.comp_apply]
```

Preferred when the list fits:

```lean
simp only [List.map_append, List.map_map, Function.comp_apply]
```

When wrapping is necessary, use this layout:

```lean
simp only [
  List.map_append, List.map_map,
  Function.comp_apply
]
```

The same layout rule applies to `simp`, `simpa`, `dsimp`, and `rw` lists,
including their `only` variants where supported.

### Structural Wrapping and Indentation

Aim to keep lines below 120 columns. A slightly longer line is acceptable when
wrapping it would obscure the structure, but genuinely long expressions should
be wrapped. Line breaks are for exposing syntax and proof scope, not merely for
moving an arbitrary suffix onto the next line.

The primary unit of formatting is a semantic object. Treat a complete
proposition, equation, comparison, or ordinary infix expression such as
`a + b = c` as one object and avoid breaking inside it. Prefer boundaries
between semantic objects: a declaration head and its type, a proposition and
its proof, a `calc` equation and its justification, a tactic and the expression
it controls, or an application head and its argument list. Break inside one
same-level semantic object only when it is far beyond 120 columns.

Indent a continuation by one level exactly when it is syntactically nested
inside the construct on the preceding level. Keep sibling expressions at the
same indentation. In particular, operands of the same infix expression,
arguments of the same function application, premises of the same judgment,
and alternatives of the same branch are siblings. Arguments are nested under
the function being applied; the expression following a tactic such as `change`
is nested under that tactic.

Do not split a same-level expression when it fits on one line, and tolerate a
modest excess over 120 columns to keep it intact. When wrapping is necessary,
first use a semantic boundary such as the proof `:=` of a `calc` step, the
`using` clause of a tactic, or the pattern after `with`. Then use genuine
nesting boundaries, such as placing a function's arguments beneath the
application head. Do not mechanically break at the first `=`, `+`, or other
infix operator. Split same-level operands only when the expression is
exceptionally long and semantic or nesting-boundary wraps are insufficient;
this is a last resort, not a preferred formatting device. If such a split is
unavoidable, do not add indentation between the operands.

Argument lists are the only ordinary exception: their sibling arguments may be
placed on separate lines at one shared indentation level because that layout
exposes the application structure. This does not license breaks between any
other same-level semantic objects. Outside argument lists, such a break is only
acceptable when the intact object would be exceptionally long.

Apply this recursively inside declaration parameter types and definition
bodies, not only inside tactics. For a genuinely long binder type, end the
binder line after `x :`, put the type expression one level inside the binder,
and put the type constructor's arguments one further level inside its
application. Return the closing `)` to the binder's indentation; it may be
followed by `:` and a short result type. For example:

```lean
def collectNames
  (entries :
    List
      (String ×
       List (Nat × Bool))
  ) : List String
:= entries.map Prod.fst

def transform
  (xs : List Nat)
  (f : Nat → Nat)
: List Nat := xs.map f
```

The `:=` follows the declaration boundary as well. If anything before it has
already wrapped, put `:=` on a fresh line rather than appending it to the last
continuation line. This test concerns only the declaration head and type before
`:=`; the definition body may itself wrap after an inline `:=`. A fully short
declaration remains fully inline:

```lean
def transform (xs : List Nat) (f : Nat → Nat) : List Nat := xs.map f
```

A one-line declaration head may also keep `:=` before a structurally wrapped
body:

```lean
def transform (xs : List Nat) (f : Nat → Nat) : List Nat :=
  (xs.map f).filter (fun n => n > 0)
```

There is one deliberate alternative for a wrapped declaration: the result
type may return to the declaration's base indentation and carry `:=` on that
same line. In that layout, `: Result` is a new base-level semantic object, not
the tail of the wrapped parameter list:

```lean
def collectNames
  (entries :
    List
      (String ×
       List (Nat × Bool))
  )
: List String := entries.map Prod.fst
```

Do not use this exception with the result type still indented as part of the
wrapped declaration. For example, `(x : Source) : Result := f x` is suitable
only when the entire declaration is a single line; after a multiline head,
write either `) : Result` followed by a fresh `:=` line or the base-indented
`: Result := ...` form above.

For a long body built from nested applications, expose each application scope
and visibly pair multiline parentheses; do not compress the entire type or body
into one opaque line merely because it occurs outside a proof.

A declaration head and its type signature are separate semantic objects. Thus
a long local declaration may use:

```lean
have hpreserved :
  Γ ⊢ substitute σ e ∶ A⟦σ⟧
:= by {
  exact substitutionPreservesTyping hσ he
}
```

The same shape applies to theorem declarations. It is not suggested for a
short declaration: keep `have h : P := by simp` on one line.

When a tactic and its expression do not fit together, put the tactic alone on
its line and show the expression's structure below it. Do not retain the head
of an application beside the tactic while moving only its arguments to a
continuation line.

The application- and infix-wrapping examples immediately below are structural
guides for expressions that are genuinely long. They do not prescribe those
particular line breaks for a short instance of the same syntax. If the complete
expression fits below roughly 120 columns, or only modestly exceeds it and
remains readable, keep it on one line. This qualification does not apply to
multiline proof scopes, delimiter blocks, theorem declarations, or judgment
rules, which have their own scope and layout rules below.

Good `change` layout for a wrapped application:

```lean
change
  List.foldl
    (fun acc entry => acc + weight configuration entry)
    initial
    entries
```

Here the application is nested under `change`, and its arguments are nested
under `List.foldl`. This is the preferred kind of wrapping because each line
break exposes a real scope boundary.

Good layout for a nested function application:

```lean
exact (
  List.Perm.trans
    (List.Perm.map f hxy)
    (List.Perm.append hleft hright)
)
```

The application is nested under `exact`; both arguments are nested under
`List.Perm.trans` and are siblings, so they share one indentation level.

The same rule applies when an argument is itself a wrapped expression:

```lean
change
  List.foldl
    (fun acc x => acc + weight x)
    initial
    items
  = expected
```

Do not keep the application head beside `change` and wrap only fragments of
its arguments:

```lean
change List.foldl
  (fun acc x => acc
    + weight x) initial items = expected
```

Last-resort layout when a same-level infix expression cannot reasonably remain
on one line (only when lines are long):

```lean
change
  List.sum xs     -- assume this is a long line
  + List.sum ys   -- assume this is a long line
  + List.sum zs   -- assume this is a long line
  = total         -- assume this is a long line
```

The operands and relation belong to one infix expression and therefore remain
at the same indentation. Prefer keeping the complete infix expression on one
line; use this form only when it is genuinely too long after nested expressions
have already been formatted clearly.

Good `calc` layout:

```lean
calc
  List.map (fun n => n + 1) (prefix ++ suffix) = List.map (fun n => n + 1) prefix ++ List.map (fun n => n + 1) suffix
    := List.map_append (fun n => n + 1) prefix suffix
  _ = expected := hExpected
```

The first equation is intentionally kept intact even though it is long. Its
proof begins after the meaningful `:=` boundary and is indented because it is
attached to that equation. Do not split the equation at its first `=` merely to
reduce the physical line length.

Keep an application, tactic invocation, `let`, or `simpa ... using ...` on one
line when it fits naturally. If a long application must wrap, put its arguments
in one sibling block as above. Do not write:

```lean
change List.foldl
  step initial items = expected
```

Keep notation delimiters together; for example, do not split
`matrix[i][j]` between `]` and `[`. A line break immediately after an
opening `(`, `[`, `{`, or `⟨` is appropriate for a genuinely delimited block.
Visibly match it with `)`, `]`, `}`, or `⟩` alone at the corresponding
indentation. If the contents of `{` begin on the following line, put the
matching `}` alone on a properly indented line, exactly as for a multiline
`:= by { ... }` proof; never attach it to the final tactic.

```lean
simp [
  List.map_append,
  List.length_append
]

exact ⟨
  hleft,
  hright
⟩

:= by {
  intro h
  exact result h
}
```

Bad closing-brace layout:

```lean
:= by {
  intro h
  exact result h }
```

The theorem-declaration rules below still apply. Typing rules and other
inductive judgments deliberately retain their vertical inference-rule shape.
Before finishing an edit, inspect every modified Lean file and remove arbitrary
breaks, misleading extra indentation, and lines that should have been wrapped
structurally.

Judgment constructors should retain rule-shaped line breaks:

```lean
-- [ Subsumption ]
-- Γ ⊢ e : A    A <: B
-- ─────────────────── Typ-Sub
-- Γ ⊢ e : B
| subsume :
    Γ ⊢ e ∶ A →
    A <: B →
    Γ ⊢ e ∶ B
```

The multiline theorem declaration layout is preferred:

```lean
theorem lengthMapAppend (f : α → β) (xs ys : List α) :
  ((xs ++ ys).map f).length = (xs.map f).length + (ys.map f).length
:= by {
  simp
}
```

Single line style is also suggested when the theorem statement are short:

```lean
theorem appendNil (xs : List α) : xs ++ [] = xs := by simp
```

Bad:
```lean
:= by {
  let mappedLeft :=
    List.map
      (fun n => f (g n))
      (xs ++ ys)
  let mappedRight :=
    List.map (fun n => f (g n)) xs ++
      List.map (fun n => f (g n)) ys
  change mappedLeft =
    mappedRight
  calc
    mappedLeft
        = List.map (fun n => f (g n)) xs ++ List.map (fun n => f (g n)) ys := by {
          simpa [mappedLeft] using
            List.map_append (fun n => f (g n)) xs ys
        }
}
```

Good:
```lean
:= by {
  let mappedLeft := List.map (fun n => f (g n)) (xs ++ ys)
  let mappedRight := List.map (fun n => f (g n)) xs ++ List.map (fun n => f (g n)) ys
  change mappedLeft = mappedRight
  calc
    mappedLeft = List.map (fun n => f (g n)) xs ++ List.map (fun n => f (g n)) ys := by {
      simpa [mappedLeft] using List.map_append (fun n => f (g n)) xs ys
    }
}
```

Bad (unnecessarily fragmented):
```lean
exact List.Perm.trans
  (by
    simpa using hfirst)
  (List.Perm.append
    (by
      simp)
    hsecond)
```

Good (compact but still readable):
```lean
exact List.Perm.trans
  (by { simpa using hfirst })
  (List.Perm.append (by simp) hsecond)
```

Every `by` with more than one tactic or statement must be followed immediately by `{`. Do not use indentation alone to control `by` scope; it is too easy for automated edits to extend or shrink the proof accidentally. The only acceptable unbraced `by` forms are tiny single-statement proofs such as `by rfl`, `by simp`, `by simp [foo]`, or `by omega`.

Short inline tactic arguments such as `(by { cases Γ; rfl })` are acceptable inside larger expressions. Declaration proof openers follow the proposition-layout rule below; small local proof arguments may stay inline, but still need braces when they contain multiple tactics.

Apply the same declaration boundary to local `have` proofs. If the complete
type of a `have` spans more than one line, put `:= by {` on a fresh line at the
same indentation level as `have`. Only a genuinely single-line `have` type may
keep its proof opener inline.

Good:
```lean
have hlength :
  ((xs ++ ys).map f).length
  = (xs.map f).length + (ys.map f).length
:= by {
  simp
}

have h : P := by simp
```

### Proposition and Proof-Opener Layout

Never leave an equality, equivalence, implication, or comparison operator at
the end of a line with its right-hand side starting on the next line. If a
proposition must wrap, put the operator at the beginning of the continuation
or keep the start of the right-hand side beside it. This makes the logical
shape visible and avoids isolated punctuation.

Bad:
```lean
theorem renameSubstitute :
  rename ρ (substitute σ e) =
    substitute (renameSubstitution ρ σ) (rename ρ e)
:= by {
  ...
}
```

Good:
```lean
theorem renameSubstitute :
  rename ρ (substitute σ e)
  = substitute (renameSubstitution ρ σ) (rename ρ e)
:= by {
  ...
}
```

Inline `:= by ...` only when the declaration keyword, complete proposition,
and proof opener all fit on one physical line. If a theorem, lemma, example,
or local `have` type spans multiple lines, put `:= by {` on a fresh line at
the declaration's indentation level. This applies even when the proof itself
is short.

Good:
```lean
theorem lookupHere : (A :: Γ) ∋ 0 ⦂ A := by simp

theorem weakeningPreservesTyping
  (h : Γ ⊢ e ∶ A) :
  (B :: Γ) ⊢ rename Nat.succ e ∶ A
:= by {
  ...
}

have hpreserved :
  (B :: Γ) ⊢ rename Nat.succ e ∶ A
:= by {
  exact weakeningPreservesTyping h
}
```

Do not wrap a function or tactic application whose complete line stays below
120 columns. Keep short applications on one line. If a genuinely long
application needs several lines, parenthesize the whole application and place
its arguments in one standard-indented block; do not leave a short function
name on one line followed by a few short arguments on the next.

Good:
```lean
exact List.Perm.trans hxy hyz

exact (
  List.Perm.trans
    (List.Perm.map f hxy)
    (List.Perm.append (List.Perm.map f hleft) (List.Perm.map f hright))
)
```

The same compactness rule applies after `with`. Keep a short `rcases` pattern
on the `with` line even when the expression being destructured is multiline.

Good:
```lean
rcases (
  And.intro
    (List.Perm.length_eq hxy)
    (List.Perm.length_eq hyz)
) with ⟨hlen₁, hlen₂⟩
```

For short tactic sequences in `·`-style subgoals, keep the tactics on one line
separated by `;` when the entire sequence is simple and readable. Do not split
each trivial tactic onto a separate line merely for uniformity; use multiple
lines when the proof needs more structure.

Good (compact subgoals):
```lean
constructor
· intro h; cases h; rfl
· rintro rfl; exact NaturalEval.fail
```

Bad (unnecessarily fragmented):
```lean
constructor
· intro h
  cases h
  rfl
· rintro rfl
  exact NaturalEval.fail
```

For single-line cases, inline the whole case body when it remains readable. You can use `;` to keep trivial tactic sequences compact.

Bad (Too many short lines):
```lean
| int =>
    intro h₂
    cases h₂
    rfl
```
Good (A single compact line):
```lean
| int => intro h₂; cases h₂; rfl
```

Bad (A short single-line case in a separated line):
```lean
| ann h₂ =>
    simp [ih h₂]
```
Good (inline the short single-line case):
```lean
| ann h₂ => simp [ih h₂]
```

### Multiline Branches and Delimiters

An inline branch is genuinely inline: everything after `=>` must fit on that
line. If the branch body continues onto later lines, either put a line break
immediately after `=>` and give the branch an explicit scope, or end the first
line with an opening delimiter such as `{`, `[`, or `(`. Never let an
apparently inline constructor application drift onto following lines without
an opening delimiter.

Whenever the text following an opening `{` begins on a new line, put the
matching `}` alone on its own line at the indentation of the construct that
opened the block. A closing brace may remain inline only when the entire
braced body is inline.

Good:
```lean
:= by {
  intro h
  exact f h
}

have h := by { exact trivial }
```

Bad:
```lean
:= by {
  intro h
  exact f h }
```

Good (short and genuinely inline):
```lean
case zero => exact Nat.zero_le _
```

Good (a multiline tactic case with an explicit proof scope):
```lean
case cons x xs ih => exact by {
  apply List.Perm.cons
  exact ih
}
```

For recursive equation branches, use `=> by { ... }` when the proof contains
multiple tactics. The single-tactic `by simp [...]` form is a special case: its
simp set may wrap across lines because the tactic begins on the same line as
`by`.

Good:
```lean
| .cons x xs, .cons y ys => by simp [
  List.map_cons, List.length_cons,
  Nat.add_assoc, Nat.add_comm, Nat.add_left_comm
]
```

If there is a line break immediately after `by`, braces are mandatory. Do not
write a multiline indentation-controlled proof after a bare `by`.

For multiline tuple, subtype, and existential constructors, visibly match the
opening and closing angle brackets. Keep the constructor inline only when the
entire expression is short.

Good:
```lean
exact ⟨
  Nat.le_trans h₁ h₂,
  Nat.le_trans h₃ h₄
⟩

exact ⟨h₁, h₂⟩
```

### Scoped Braces for Proofs

For tactic proofs, especially proofs with nested `cases`, `match`, or `induction`, use explicit `{ ... }` scopes. Good scope management prevents accidental indentation drift and makes it clearer which tactics belong to which branch.

Use a scoped first `by` for theorem proofs unless the entire proof is a single statement such as `by rfl` or `by simp [...]`:

```lean
theorem identity (P : Prop) (h : P) : P := by {
  exact h
}

-- or

theorem mapAppend (f : α → β) (xs ys : List α) :
  (xs ++ ys).map f = xs.map f ++ ys.map f
:= by {
  simp
}
```


The `:= by {` proof opener belongs at the end of the declaration line only
when the keyword, complete proposition, and opener all occupy that same line.
Otherwise it starts on a fresh line after the theorem statement. The opening
`{` after the first `by` and the final `}` must be visually aligned at the same
proof level. This keeps the theorem proof syntactically contained and makes it
harder to accidentally continue into the next declaration.

Bad:
```lean
theorem identity (P : Prop) (h : P) : P := by
  exact h

theorem mapAppend (f : α → β) (xs ys : List α) :
    (xs ++ ys).map f = xs.map f ++ ys.map f := by
  simp
```

Good:
```lean
theorem identity (P : Prop) (h : P) : P := by {
  exact h
}

theorem mapAppend (f : α → β) (xs ys : List α) :
  (xs ++ ys).map f = xs.map f ++ ys.map f
:= by {
  simp
}
```

And if the parameter list is very long, you should put them into a new single line with the **SAME** indentication as the type:
Bad:
```
theorem typingContextWellFormed
    -- BAD: The binder is indented too deeply.
    {Γ : Ctx} {e : Exp} {A : Ty} :
  Γ ⊢ e ∶ A →
  WellFormed Γ
:= by {
  ...
}
```
Good:
```
theorem typingContextWellFormed {Γ : Ctx} {e : Exp} {A : Ty} :
  Γ ⊢ e ∶ A →
  WellFormed Γ
:= by {
  ...
}

-- or

theorem typingContextWellFormed
  {Γ : Ctx} {e : Exp} {A : Ty} :
  Γ ⊢ e ∶ A →
  WellFormed Γ
:= by {
  ...
}
```

For case branches, choose between compact inline branches and explicit scoped branches:

* If the case body is a very short linear tactic sequence, inline it with `;`.
  This is preferred for trivial impossible cases such as `intro h; cases h`.
  Do not force this style when the resulting line is long or hard to scan; use a small `{ ... }` block instead.
* If the case body is a single expression or tactic, keep it on the same line after `=>`.
* If the case body contains nested subcases or is long (roughly more than three lines), wrap it in `{ ... }`.
* Inline nested `cases` is acceptable when the whole branch is a single readable line, for example `| some x => cases h with | inl hx => exact hx`.
* Do not wrap tiny single-line branches in `{ ... }`; braces are for scope management in multi-line branches.

Bad (braces around a trivial branch):
```lean
| var hlookup =>
  {
    intro e' hstep
    cases hstep
  }
```

Good (short linear branch inlined):
```lean
| var _    => intro e' hstep; cases hstep
| zero     => intro e' hstep; cases hstep
| true     => intro e' hstep; cases hstep
| abs _ _  => intro e' hstep; cases hstep
```

Good (linear but too long to inline):
```lean
| succ n ih =>
  {
    intro m hEq hBound hPositive
    cases m <;> simp [Nat.succ_eq_add_one, Nat.add_comm] at hEq
  }
```

Bad (single-statement subcase split over multiple lines):
```lean
| succ n =>
    exact False.elim (Nat.succ_ne_zero n hzero)
```

Good (single-statement subcase inlined):
```lean
| succ n => exact False.elim (Nat.succ_ne_zero n hzero)
```

For any case branch with nested subcases or a long body, wrap the branch body in `{ ... }`. Single-line branches should stay inline.

Good:
```lean
theorem stepDeterministic {e e₁ e₂ : Exp} :
  (e ↪ e₁) →
  (e ↪ e₂) →
  e₁ = e₂
:= by {
  intro hstep₁ hstep₂
  induction hstep₁ generalizing e₂ with
  | appFun hstep ih =>
    {
      cases hstep₂ with
      | appFun hstep₂ => simp [ih hstep₂]
      | appArg hval hstep₂ => exact False.elim (valueNoStep hval hstep)
      | beta hval => exact False.elim (valueNoStep Value.abs hstep)
    }
  | appArg hval hstep ih =>
    {
      cases hstep₂ with
      | appFun hstep₂ => exact False.elim (valueNoStep hval hstep₂)
      | appArg _ hstep₂ => simp [ih hstep₂]
      | beta harg => exact False.elim (valueNoStep harg hstep)
    }
  | beta hval =>
    {
      cases hstep₂ with
      | appFun hstep₂ => exact False.elim (valueNoStep Value.abs hstep₂)
      | appArg _ hstep₂ => exact False.elim (valueNoStep hval hstep₂)
      | beta _ => rfl
    }
}
```

Guidelines for this style:

* Put `:= by {` on a fresh line after every multiline theorem statement;
  proof size does not change this declaration boundary.
* Use `{ ... }` around multi-line case bodies and nested branches.
* Never write `by` followed by an indented multi-line proof without braces.
* Keep genuinely short cases inline, especially contradictions or direct rewrites.
* Inline-align adjacent short `cases` alternatives only when it improves scanning.
* Do not put branch braces around every tiny non-`by` proof fragment; use branch braces to manage multi-line scope.

Good mixed example:
```lean
theorem lengthAppend (xs ys : List α) :
  (xs ++ ys).length = xs.length + ys.length
:= by {
  induction xs with
  | nil => rfl
  | cons x xs ih =>
    {
      change Nat.succ (xs ++ ys).length = Nat.succ xs.length + ys.length
      rw [ih]
      simp [Nat.succ_add]
    }
}
```

## File and Folder Structure

Do not put an entire formalization into one monolithic file. Separate object
definitions from proofs, and organize both layers by subject. The hierarchies
in this section are examples, not a mandatory template. Choose only the layers
that correspond to real concepts in the formalization; in particular, not
every project has separate source and target languages, an elaborator, or a
cross-language correspondence theorem.

For a project with distinct static, dynamic, and source-to-target components,
one suitable organization is:

```text
Calculus/
  Syntax/                 -- terms, types, binders, substitutions as data
  Typing/                 -- contexts, well-formedness, subtyping, typing
  Semantics/              -- values, configurations, evaluation, reduction
  Elaboration/            -- source-to-target judgments or algorithms
  Metatheory/
    Syntax/               -- algebra and binding theorems
    Typing/               -- regularity, weakening, substitution, uniqueness
    Semantics/            -- preservation, progress, determinism, observations
    Elaboration/          -- totality, soundness, completeness
    Correspondence/       -- relations between distinct calculi or semantics
```

A foundational type-theory project may instead need only:

```text
Theory/
  Syntax/                 -- expressions, contexts, substitutions as data
  Semantics/              -- judgments and computational behavior
  Examples/               -- optional worked object-language examples
  Metatheory/
    Syntax/               -- algebra and binding theorems
    Semantics/            -- semantic theorems
    ...
```

The directory names describe conceptual ownership, not import convenience:

* **`Syntax/`** contains syntactic objects and operations that define them.
* **`Typing/`** contains static judgments when the project treats its type
  system as an independent layer. Do not place such a layer inside
  `Semantics/` merely because typing is needed by semantic theorems. A
  foundational type theory may instead treat typing, equality, and reduction
  together as its core semantics and omit the separate `Typing/` directory.
* **`Semantics/`** contains definitions of runtime states, values, dispatch,
  evaluation, and reduction, or the core judgments of a foundational type
  theory. It is not a home for substantial preservation or progress proofs.
* **`Elaboration/`** contains elaboration relations and executable compilers
  when the formalization actually has a source-to-target phase.
* **`Examples/`** is optional. Put worked examples there rather than mixing
  them into definition or metatheory modules. If an example contains a complex
  object-language expression, present its readable paper-style expression as
  well as its Lean encoding, following the `Notation and Binders` guidance in
  the document-writing section. The Lean representation alone is not adequate
  documentation for such an example.
* **`Metatheory/`** is reserved for lemmas and theorems, nested under the
  subject whose definitions they analyze. In particular, semantic metatheory
  belongs in `Metatheory/Semantics/`, not in the top-level `Semantics/` folder.

When a layer has multiple semantic or elaboration variants, put each variant
beneath that layer and keep genuinely shared definitions at the layer's root:

```text
Calculus/
  Semantics/
    SmallStep/
    BigStep/
    Common.lean
  Metatheory/
    Semantics/
      SmallStep/
      BigStep/
      Correspondence.lean
```

Do not create a directory containing only one file without a clear expectation
of growth; use `Syntax.lean` or `Reduction.lean` directly in that case. When a
breaking refactor moves a concept, update imports directly. Unless backward
compatibility is an explicit requirement, do not leave forwarding modules,
deprecated aliases, or parallel copies of the old theory.

`Examples/` is an intentional exception to the single-file-directory
guideline: when worked examples are added, keep them under `Examples/` even if
the directory initially contains only one module. This preserves the boundary
between illustrative material and the formalization's definitions and proofs.

Base definition files may contain tiny structural lemmas needed to expose a
usable interface, but substantial induction, regularity, preservation, and
correspondence proofs belong under `Metatheory/`.
