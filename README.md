# Implicit implementation calculus

A deliberately small, expression-based Scala 3 implementation of a two-context calculus.
The readable `trait`, `impl`, `def`, and `where` syntax is retained. There is no module AST,
module system, global instance registry, or separate concrete/surface parse tree.

## Run

Use JDK 17 or newer and sbt with access to Maven Central:

```sh
sbt test
sbt 'runMain implicitlang.cli.Main examples/display.imp'
sbt 'runMain implicitlang.cli.Main examples/first-class.imp --dump'
sbt 'runMain implicitlang.cli.Main examples/polymorphic.imp'
```

The pinned dependencies are Scala 3.3.6, scala-parser-combinators 2.4.0, and sbt 1.10.7.
Tests use a small executable harness rather than an additional testing library.

**Verification status:** the PDF has been compiled and its rendered pages inspected.
Scala compilation and the executable tests have NOT been run in the authoring environment:
Java is available, but Scala/sbt are absent and the Maven repository is not reachable from this container. The expected
results below are specifications, not claims of observed executions.

## Surface syntax stays practical

```text
trait ToString[A] = { toString: A -> String }

type Student = { name: String, age: Int }

impl student: ToString[Student] {
  toString = (s: Student) => "Student(" ++ s.name ++ ")"
}

def display[A where ToString[A]](x: A): String = x.toString

display[Student]({ name = "Ada", age = 21 })
```

`Parser` builds `Expr` directly. A file is a sequence of declarations followed by a final
expression; declarations become nested expression nodes. For example:

```text
def display[A where ToString[A]](x: A): String = body
rest
```

becomes the same `Expr` as:

```text
let display = fun [A] => fun {printer: ToString[A]} => (x: A) => (body: String)
in rest
```

The generated implementation parameter has an unspellable internal name, not `printer`.
`trait` and `type` introduce lexical, nonrecursive type synonyms. Both are represented by
`Expr.TypeLet`; trait names disappear by expansion to ordinary record types.
Named and anonymous implementations are accepted:

```text
impl printer: ToString[String] { toString = (s: String) => s }
impl ToString[String] { toString = (s: String) => s }
```

A generic implementation is an ordinary polymorphic value with implicit arrows:

```text
impl via[A, B where Repr[A, B] and ToString[B]]: ToString[A] {
  toString = (x: A) => ToString[B].toString(Repr[A, B].repr(x))
}
```

`and` and commas both separate constraints. Record fields may be separated by commas,
semicolons, or whitespace. `++` concatenates strings; `+` is integer addition. Function/type application
is explicit: `f(x)`, `f[A]`; `f{e}` supplies an implementation argument explicitly.
Use parentheses to disambiguate ascriptions and `;` at a declaration boundary when the
next expression starts with a postfix delimiter such as `(`, `[` or `{`.

## Two term environments

- **Γ** contains ordinary lambda/let bindings.
- **Δ** contains implicit lambda/implementation bindings, nearest binding first.

Type variables have a separate de Bruijn depth, not another evidence environment.
`ImplicitTerm.Var` indexes Γ and `ImplicitTerm.Instance` indexes Δ. The resolver merges them into the
single de Bruijn environment of `Term`, weakening existing references at every binder.
Type abstraction weakens the types in both environments.

An `impl` is a *scoped binding of a value*, not a global registration:

```text
def install(chosen: ToString[String]): String = {
  impl active: ToString[String] = chosen in
  ToString[String].toString("Ada")
}
```

Implementations can be returned, stored in records, passed as ordinary arguments, referred
to by name, or supplied through `f{e}`. Only binding a value with `impl` (or an implicit
lambda) makes it available to search. A value in Γ is never implicitly registered.
Implementation bindings are nonrecursive. Functions capture the implementations in their
lexical definition scope; an inner implementation cannot retroactively change a closure.

## Pipeline

```text
text
  -> Parser       -> Expr                 named expression AST, including Impl and TypeLet
  -> Elaborator   -> ImplicitTerm + Type  two variable spaces, implicit arrows, Need goals
  -> Resolver     -> Term                 ordinary System F with records; no implicit syntax
  -> Term.infer                           independent explicit-target check
  -> Evaluator                            call-by-value closure semantics
```

Name/type elaboration never invokes the synthesizer. It expands synonyms, resolves names,
checks types, and inserts `ImplicitTerm.Need` nodes. The core distinguishes `A -> B` from `A => B`.
Ordinary application inserts missing arguments for leading implicit arrows only; variable
lookup does not eagerly force an implicit function. An explicit `f{e}` does not search.
Type applications are not inferred and do not automatically pass intervening implicit arrows.

`ToString[A]` in term position denotes an evidence request for the expanded type, unless a
term binding shadows that alias name. `summon[ToString[A]]` is the explicit equivalent.
`x.method` first tries an actual record field. Otherwise it uses the unique record **type**
in Δ whose `method` field has type `typeOf(x) -> R`, generating a deferred request for that
record type and applying the method to `x`. Equal candidate record types are deduplicated;
distinct candidates are an error. This deliberately limited sugar handles `x.toString`
inside `display` without introducing a class registry or doing evidence search in elaboration.
For a goal that needs more inference, write `ToString[A].toString(x)` explicitly.

## Replaceable synthesis

```scala
trait Synthesizer {
  def synthesize(goal: Type, context: SynthesisContext): Result[ImplicitTerm, Error]
}
```

The context provides ordered Δ types and the number of rigid type variables. A result is
restricted to `ImplicitTerm.Instance`, `ImplicitTerm.TypeApp`, `ImplicitTerm.App`, and `ImplicitTerm.ImplicitApp`.
It uses Δ indices, not the merged runtime indices. The resolver checks both this grammar
and its source type **before** erasing implicit arrows, then checks the translated target.
It rejects references to Γ, new lambdas/literals, unresolved goals, ill-scoped indices,
and explicit/implicit arrow mismatches. The result therefore composes available first-class
implementations instead of inventing arbitrary terms of the desired type.

`NaiveSynthesizer` scans Δ from nearest to farthest and returns the first exactly equal type.
That is the entire baseline: no polymorphic instantiation, recursive prerequisite search,
head matching, backtracking, or ambiguity analysis. **Even when a generic producer would
be easy to instantiate by matching, the baseline intentionally fails.** This preserves
an experimental separation from later synthesis algorithms; it does not establish that
SMT is the only way to improve the resolver. Polymorphic producers remain first-class
and can already be applied explicitly and installed with `impl`. Discovering their type
arguments and compositions is intentionally left to a replacement SMT synthesizer.

## Organization

The calculus is small; the project is not flattened. Each package owns a distinct
semantic responsibility. The parser returns `Expr` directly (no concrete-syntax AST),
and declarations are scoped expressions rather than a module system.

```text
src/main/scala/implicitlang/
  syntax/         Expr.scala, OpKind.scala
  parser/         Parser.scala
  core/           Type.scala, ImplicitTerm.scala, Term.scala
  elaboration/    Elaborator.scala
  synthesis/      Synthesizer.scala, NaiveSynthesizer.scala
  resolution/     Resolver.scala
  evaluation/     Evaluator.scala
  cli/            Main.scala  (Pipeline, Main)
  Error.scala     errors and small checking helpers
src/main/scala/implicitlang/util/Result.scala
src/test/scala/implicitlang/CalculusTests.scala
docs/calculus.tex
docs/calculus.pdf
examples/*.imp
```

The syntax, typing/elaboration, evidence search, lowering, and runtime are separate
without introducing modules, a framework, or extra AST layers. Cross-cutting recoverable errors use `implicitlang.util.Result[T,E]`.

## Primitive operators

The same `BinOp(kind, left, right)` node is used in the first AST, unresolved core,
and final calculus. `+`, `-`, `*`, `==`, `<`, `>` have `Int` operands; `++` has `String`
operands. Results are `Int`, `Bool`, and `String` respectively. The parser follows
ordinary arithmetic precedence (`*` before `+` / `-`), followed by `++` and comparisons.
There is no special-purpose `Concat` or `ShowInt` expression.

## Examples and expected results

| File | Expected result |
| --- | --- |
| `display.imp` | `Student(Ada)` |
| `first-class.imp` | `value = Ada` |
| `lexical.imp` | `outer: Ada / inner: Ada` |
| `explicit-override.imp` | `explicit: Ada` |
| `polymorphic.imp` | `Ada` |
| `operators.imp` | record with integer, boolean, and string results |
| `needs-smt.imp` | `MissingInstance`; hidden intermediate type is not solved by the placeholder |

Tests cover declaration parsing, Γ/Δ separation, scope and shadowing, type weakening,
alias capture, first-class values, explicit implementation arguments, malformed types,
final checking, and replacement-synthesizer validation.

## Deliberate boundaries

No subtyping, existential types, nominal class identities, modules, forward references,
recursive bindings, higher-kinded types, datatype declarations, automatic generalization,
or general type inference. Definitions are lexical and sequential; use before declaration
is rejected. Traits are transparent record synonyms, so structurally identical trait types
are equal. Record labels are unique and canonicalized. Integers are arbitrary precision.
The calculus is pure. Its primitive binary operators are integer `+`, `-`, `*`, `==`, `<`, `>` and
string `++`. The comparison operators return `Bool`. There is no built-in integer-to-string
conversion: a printer for integers is a separate library concern, not a primitive expression.

## Formal specification

`docs/calculus.tex` specifies syntax, parser equations, Γ/Δ elaboration, admissible evidence,
resolution/erasure, target typing, and call-by-value evaluation. Rules use alpha-renamed
names; implementation indices are explained separately. No mechanized metatheory is claimed.
Rebuild the PDF with:

```sh
cd docs
pdflatex -interaction=nonstopmode -halt-on-error calculus.tex
pdflatex -interaction=nonstopmode -halt-on-error calculus.tex
```

Dependencies: [Scala 3.3.6](https://www.scala-lang.org/download/3.3.6.html) and
[Scala parser combinators](https://github.com/scala/scala-parser-combinators).
