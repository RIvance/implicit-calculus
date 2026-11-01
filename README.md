# Implicit implementation calculus

A research implementation of bounded SMT synthesis for implicit evidence in a two-context calculus.
The synthesizer jointly chooses polymorphic type arguments and compositions of available values,
using type instantiation and implicit application.

The Scala 3 implementation includes a surface language whose traits describe record dictionaries.
`implement` declares a top-level implementation;
`instance` declares a named value for implicit search; `given` binds evidence within an expression.

## Run

Use JDK 17 or newer and sbt with access to Maven Central and GitHub releases:

```sh
sbt test
sbt 'runMain implicitlang.cli.Main examples/display.imp'
sbt 'runMain implicitlang.cli.Main examples/first-class.imp --dump'
sbt 'runMain implicitlang.cli.Main examples/polymorphic.imp'
```

The pinned dependencies are Scala 3.3.6, scala-parser-combinators 2.4.0, sbt 1.10.7,
and the **official Z3 4.14.1 Java API and native libraries**.
Tests use a small executable harness rather than an additional testing library.

[The sbt dependency task](project/SmtDependency.scala) downloads an exact archive from
[Z3's official release](https://github.com/Z3Prover/z3/releases/tag/z3-4.14.1), verifies its
committed SHA-256 checksum, and extracts the matching JAR and native libraries into
`target/z3/`. Subsequent builds verify the archive and check the extracted files against it.
A changed archive fails verification. No Z3 binaries are tracked in Git or installed into
system or user directories. Library paths are set only for this project's forked JVMs;
the parent shell and its configuration are untouched. `sbt clean` removes the local download.

Pinned archives cover Linux x86-64 (glibc 2.35+) and ARM64 (glibc 2.34+), macOS x86-64
and ARM64, and Windows x86-64 and x86. The host must supply the native C++ runtime.
On Linux, sbt also locates an available C++ compiler's runtime for the child JVM's library
path, allowing installations outside the system loader's default directories. An unsupported
platform fails explicitly. The checksum pins the Z3 artifact; it does not pin the host OS or JDK.

## Browser playground

The [synthesis workbench](playground/README.md) combines a Monaco editor with interactive
evidence graphs, each goal's context, actual SMT constraints and models, search playback,
configurable bounds, and exact-lookup comparisons. It also supports source sharing, run history,
query and report export, cancellation, and dark mode.

```sh
cd playground
npm ci
npm run dev
```

Use Node.js 22.12 or newer. The Scala.js compiler and Z3 WebAssembly solver run locally in a
browser worker. The playground is a separate sbt/npm project that depends on the shared compiler.
Its README covers production builds, static hosting, and browser tests.

## Surface syntax stays practical

```text
trait ToString[A] = { toString: A -> String }

type Student = { name: String, age: Int }

implement ToString[Student] = {
  toString = (s: Student) => "Student(" ++ s.name ++ ")"
}

def display[A where ToString[A]](x: A): String = x.toString

display[Student]({ name = "Ada", age = 21 })
```

`surface.Parser` produces a `Program`: a sequence of declarations followed by a final
`SurfaceExpr`. `type`, `trait`, `implement`, `instance`, and `def` are top-level declarations.
`SurfaceExpr` contains expressions, including `Let` and `Given` terms with explicit bodies.
Named types and annotations use `SurfaceType`. Declarations precede the final expression
and cannot occur inside it. Elaboration consumes the declaration data separately from the expression tree.

Elaboration processes declarations in order. Type aliases and traits are expanded and
disappear; value declarations become nested bindings around the elaborated final expression.
The result is one `implicit.ImplicitTerm`.

`type` introduces a sequential, nonrecursive alias for any ordinary type. `trait` introduces a
constraint whose definition must expand to a record type. A trait name is allowed in a
`where` clause, an `implement` target, or qualified member access such as `ToString[A]::toString`.
Ordinary annotations, type arguments, `summon`, and explicit implicit parameters use ordinary
types. Explicit dictionaries can use record types or their aliases, as in
[the first-class dictionary example](examples/first-class.imp).

| Form | Target | Name | Scope |
| --- | --- | --- | --- |
| `implement[A where C[A]] D[A] = expression` | A declared trait | Anonymous | Top-level declaration |
| `instance name[A](parameters): R = expression` | Any ordinary type | Required | Top-level declaration |
| `given name: A = expression in body` | Any ordinary type | Required | Expression body |

Type parameters and value parameters are optional on `instance`; type parameters are optional
on `implement`. Initializers can be any expression of the required type, including existing values
and function calls. These evidence bindings are nonrecursive and visible only in their continuation.
A named binding is also available through ordinary term references.

An instance parameter list expands to a curried function type and a lambda initializer:

```text
instance counted[A](value: A, implicit count: Int): Counted[A] = { value = value, count = count }
// equivalent to:
instance counted[A]: A -> (Int ~> Counted[A]) =
  (value: A, implicit count: Int) => { value = value, count = count }
```

A clause such as `[A, B where Repr[A, B] and ToString[B]]` introduces type parameters followed by
implicit dictionary parameters. Constraints must name declared traits and are separated by `and`.
Type arguments inside those references are ordinary types. Type parameter clauses are supported on
`def`, `implement`, and `instance`; `[where C]` introduces constraints without type parameters.
For example:

```text
implement[A, B where Repr[A, B] and ToString[B]] ToString[A] = {
  toString = (x: A) => ToString[B]::toString(Repr[A, B]::repr(x))
}
```

After checking the surface restrictions, elaboration expands each trait to its structural record
type and each type parameter clause to type abstractions followed by implicit abstractions. All evidence bindings
become the calculus's existing implicit binding. The calculus can still quantify over, pass, return,
and synthesize arbitrary types; trait names and declaration kinds do not reach it.

A `def` has an explicit result annotation and may have zero or more parameters. Its full
signature includes type parameters, `where` constraints, and ordinary or implicit parameters.
If its body refers to the definition itself, elaboration binds that signature while checking
the body and wraps the value in a fixpoint:

$$
\mathsf{let}\ f:T = \mathsf{fix}\ f:T.\,v\ \mathsf{in}\ b.
$$

This check respects parameter and local-binding shadowing. Recursive definitions must have
an ordinary or implicit function type after leading universal quantifiers are stripped.
For example, `def x: Int = x` and a self-referencing record definition are rejected. A definition
without a self-reference becomes an ordinary let binding, including definitions of integers
and records. The core fixpoint construct itself accepts any type. Fixpoints can diverge;
the function-type restriction does not establish termination. See
[recursive definitions](examples/recursive-definition.imp) and
[recursive type abstraction with trait evidence](examples/recursive-type-abstraction.imp).

Comma-separated parameters and repeated parameter groups are curried, as are argument lists
and whitespace application:

```text
def f[T](a: A, b: B): R = e    // same as def f [T] (a: A) (b: B) : R = e
(a: A, b: B) => e             // same as (a: A) => (b: B) => e
f(a, b)                      // same as f(a)(b), f a b, or f a (b)
```

Groups can be mixed, as in `def f(a: A, b: B)(c: C): R = e` and `(a: A)(b: B) => e`.
Ordinary lambda annotations are optional: `x => body`, `(x, y) => body`, and
`(x: Int, y) => body` are valid expression forms. Implicit parameters and declaration
parameters require annotations. Mark each implicit parameter individually. The `where`
syntax is available on `def`, `implement`, and `instance`, including
`def display[A where ToString[A]](x: A): String = x.toString`.
Named implicit parameters can also be mixed with ordinary parameters:

```text
(a: A, implicit b: B, c: C) => body
// equivalent to (a: A) => (implicit b: B) => (c: C) => body
def f(a: A, implicit b: B, c: C): R = body
```

Only `b` enters the implicit environment; `a` and `c` remain ordinary parameters.
The function type is `A -> B ~> C -> R`. Calling `f(a, c)` searches for `b`, while
`f(a, using b, c)` (equivalently `f(a)(using b)(c)`) supplies it explicitly.
`using` is a keyword that marks only the following argument: `f(using a, b)` passes `a`
implicitly and `b` ordinarily. Write `f(using a, using b)` to supply both implicitly.
A trailing implicit parameter remains a first-class
implicit function until applied. Type lambdas use `[T] => body`, for example
`[T] => (x: T) => x`. All lambda bodies use `=>`; implicit function types use `~>`.
Mathematical terms use `λ⟨d:A⟩. c` for implicit abstraction, `f⟨d⟩` for implicit
application, and `A ⇒ B` for implicit function types.

Elaboration uses bidirectional typing. An unannotated lambda checks against an expected
function type, supplied by an annotation, a declaration signature, or an argument's parameter
type. This expectation also reaches nested lambdas and record fields. For example,
`let add: Int -> Int -> Int = (x, y) => x + y in add(20, 1)` returns `21`.
An unannotated let synthesizes its initializer's type; later uses do not determine that type.
Thus `let id = x => x in id(21)` reports `MissingParameterType(x)`. A `given` always requires
its type annotation. See [contextual lambdas](examples/contextual-lambdas.imp).

Application associates to the left and binds more tightly than binary operators. Adjacent
postfix operations bind within an argument: `f g(x)` means `f(g(x))`, while `f g (x)` means
`f(g)(x)`. Likewise, `f x.field` passes `x.field` to `f`. Type application remains explicit
with `f[T]`; `f(using e)` supplies an implementation argument explicitly. Use `f(-1)` or `f (-1)`
for a negative argument; `f x - 1` is subtraction. `++` concatenates strings; `+` adds integers.

Ordinary bindings use `let a = e in body` or `let a: A = e in body`. The typed form becomes
a let binding whose initializer is ascribed the ordinary type `A`.
For `let`, `given`, `instance`, `implement`, `def`, `type`, and `trait`, `in` may be replaced by a newline or `;`:

```text
let answer: Int = 21
given selected: Int = answer
summon[Int]
```

A newline after a complete initializer separates it from the body, including a body beginning
with `(` or `{`. Application, projection, and binary operators do not continue across that
newline. Line breaks are allowed where an operand or body is still required, such as after
`=`, `=>`, or `+`, and inside argument lists such as `f(\n a,\n b\n)`.
Comments count as whitespace; newlines inside comments also separate bindings.
Same-line declarations and bindings require `in` or `;`.

Braces group one expression just like parentheses: `{ body }` and `(body)` produce the
same AST. The expression may contain nested bindings:

```text
let answer = {
  let value = 20
  value + 1
}
answer
```

Grouping introduces no node; each `let` or `given` is an expression enclosing its continuation.
`f { body }` and `f{body}` are ordinary applications equivalent to `f(body)`. Explicit implicit arguments use `f(using value)`
and may themselves contain a group, as in `f(using { let x = 20 in x + 1 })`.
Field syntax distinguishes record literals such as `{ value = 21 }` from grouped expressions;
`{}` remains the empty record. A record can be passed as an ordinary argument with
`f { value = 21 }` or as an explicit implicit argument with `f(using { value = 21 })`.

Record types and values require commas, semicolons, or newlines between fields; spaces alone
do not separate fields. A trailing field separator is allowed. Records are immutable maps in
the syntax, both calculi, and evaluated values. Field order does not affect equality; duplicate
labels are rejected by the parser. Display output sorts labels for readability.

## Two term environments

- **Γ** contains ordinary lambda/let bindings.
- **Δ** contains implicit lambda/implementation bindings, nearest binding first.

Type variables have a separate de Bruijn depth, not another evidence environment.
`ImplicitTerm.Var` indexes Γ and `ImplicitTerm.Instance` indexes Δ. The resolver merges them into the
single de Bruijn environment of `Term`, weakening existing references at every binder.
Type abstraction weakens the types in both environments.

A `given` makes a value available in its expression body:

```text
def install(chosen: { toString: String -> String }): String = {
  given active: { toString: String -> String } = chosen in
  ToString[String]::toString("Ada")
}
```

Implementations can be returned, stored in records, passed as ordinary arguments, referred
to by name, or supplied through `f(using e)`. Binding a value with `implement`, `instance`, `given`, or an implicit
lambda makes it available to search. A value in Γ is never implicitly registered.
Implementation bindings are nonrecursive. Functions capture the implementations in their
lexical definition scope; an inner implementation cannot retroactively change a closure.

## Pipeline

```text
text
  -> surface.Parser      -> surface.Program
  -> surface.Elaborator  -> implicit.ImplicitTerm + Type
  -> resolution.Resolver -> core.Term
  -> core.TypeChecker.check    independent explicit-target check
  -> core.Evaluator         call-by-value evaluation
```

Name/type elaboration never invokes the synthesizer. It expands synonyms, resolves names,
checks types, and inserts `ImplicitTerm.Need` nodes. The core distinguishes `A -> B` from `A ~> B`.
Ordinary application inserts missing arguments for leading implicit arrows only; variable
lookup does not eagerly force an implicit function. An explicit `f(using e)` does not search.
Type applications are not inferred and do not automatically pass intervening implicit arrows.

`Compiler.prepare` returns a parsed and elaborated program. Its `resolve` operation suspends
at evidence requests, allowing synchronous JVM execution or asynchronous browser execution
of the same traversal. A resolution report retains every completed goal, its synthesis attempts,
and independent evidence validation, including when a later goal fails. Implicit binders carry
optional source-name hints for mathematical display; binding identity remains index-based.

`ToString[A]::toString(x)` selects a member of an implementation requested for `ToString[A]`.
The parser retains `P::member` as a qualified selection. Elaboration resolves `P` as a trait
reference or an ordinary type, requests its expanded dictionary type, and selects the member.
Types are not term values: a bare `ToString[A]` or `ToString[A].toString` is invalid when
`ToString` names a trait. An explicit dictionary value can be requested using its ordinary
record type, for example `summon[{ toString: A -> String }]`. Ordinary values, including values
with the same spelling as a type, use dotted selection.

Every `x.method` parses as `SurfaceExpr.Select(x, method)` for any receiver expression; the raw
AST has no `Proj`. Elaboration first checks the receiver's type and tries an actual record
field. If found, it emits `ImplicitTerm.Proj`. Otherwise it uses the unique record **type**
in Δ whose `method` field has type `typeOf(x) -> R`, generating a deferred request for that
record type and applying its projected method to `x`. This fallback also applies to records
without the requested field. Equal candidate record types are deduplicated;
distinct candidates are an error. This deliberately limited sugar handles `x.toString`
inside `display` without introducing a class registry or doing evidence search in elaboration.
For a goal that needs more inference, write `ToString[A]::toString(x)` explicitly.

## SMT-based evidence synthesis

```scala
trait Synthesizer {
  def synthesize(goal: Type, context: SynthesisContext): Result[ImplicitTerm, Error]
}
```

The context provides ordered Δ types and the number of rigid type variables. A result is
restricted to `ImplicitTerm.Instance`, `ImplicitTerm.TypeApp`, and `ImplicitTerm.ImplicitApp`.
It uses Δ indices, not the merged runtime indices. The resolver checks both this grammar
and its source type **before** erasing implicit arrows, then checks the translated target.
It rejects references to Γ, new lambdas/literals, unresolved goals, ill-scoped indices,
and explicit/implicit arrow mismatches. The result therefore composes available first-class
implementations instead of inventing arbitrary terms of the desired type.

`SmtSynthesizer` is the default. An exactly matching implementation still selects the nearest
binding. Otherwise Z3 chooses type arguments and composes available values through implicit
application. Ordinary functions can be selected as values, including as implicit dependencies;
their ordinary arguments must be supplied by program expressions. Type arguments may contain functions, records, or
universals; in-scope type variables remain rigid. `examples/needs-smt.imp` now infers the
hidden intermediate type `String` and evaluates to `Ada`.

Search represents evidence as a sequence of application chains, each starting at a Δ
reference and using earlier chains for value arguments. Its cost counts each head reference
and each application once. The default limits are **16 construction steps**, **type-argument
height 8**, and a **10-second search timeout**. These are configurable through `SynthesisLimits`
when constructing a `SmtSynthesizer` for `Pipeline.compile` or `Pipeline.run`. Among composite
solutions, search prefers fewer chains; it does not promise coherence or a particular choice
between different solutions with the same chain count.

`SynthesisLimit` means no evidence was found within the construction and type bounds.
`SynthesisUnknown` preserves Z3's reason, including timeout, and `SynthesisBackend` preserves
a native/API failure's cause. Neither is reported as a missing instance. With an empty Δ,
or after exhaustive search of the finite universal-free fragment, `MissingInstance` is a
proven absence.

`NaiveSynthesizer` remains available as an exact-lookup baseline for comparisons. It performs
no instantiation or composition and is not a fallback for SMT failure.

### Shared solver interface

[The SMT algebra](src/main/scala/implicitlang/smt/Smt.scala) provides immutable, sorted
expressions with Scala operators:

```scala
import implicitlang.smt.*
import implicitlang.smt.Smt.*

val head = SmtConstant("head", SmtSort.Int)
val active = SmtConstant("active", SmtSort.Bool)
val permitted = active.implies((int(0) <= head) && (head < int(3)))
```

`SmtProblem` groups datatype declarations, recursive definitions, constants, constraints,
and requested model observations. Constraint provenance accompanies formulas without changing
their meaning. Both adapters consume the same SMT-LIB representation and return immutable
integer, Boolean, and constructor values; retained problems and models contain no native handles.

`SmtSolver[F]` separates the execution effect from those values: the JVM adapter returns a
`Result` directly, while the Scala.js adapter returns a `Future[Result[...]]`. Unsatisfiability,
solver unknown, and backend failures remain distinct. The browser worker owns its WebAssembly
module; individual checks release their solver and model resources.

`SmtSearch` is the shared pure search state machine. It yields a query or a completed report;
resuming a query records its actual response and either accepts evidence, adds guarded validity
constraints, or grows the spine count. Execution boundaries own deadlines. Reports expose the
actual problems, models, refinements, and evidence spines used by the search, so tools can inspect
the algorithm without reproducing it.

## Organization

The surface, implicit, and core representations have distinct contracts. Parsing preserves
declarations and unresolved names; elaboration produces one term with deferred evidence requests;
resolution supplies evidence and merges the two binding spaces into an explicit core term.

```text
src/main/scala/implicitlang/
  Compiler.scala  prepared programs and compilation results
  surface/        Program.scala, SurfaceExpr.scala, SurfaceType.scala, Parser.scala, Elaborator.scala
  implicit/       ImplicitTerm.scala, ImplicitPrinter.scala
  core/           Type.scala, Term.scala, OpKind.scala, TypeChecker.scala, Evaluator.scala
  diagnostic/     Error.scala
  smt/            Smt.scala
  synthesis/      Synthesizer.scala, NaiveSynthesizer.scala, SmtSearch.scala, SmtTypes.scala
  resolution/     Resolver.scala, Resolution.scala
  util/           Result.scala
src/main/scala-jvm/implicitlang/
  cli/            Main.scala  (Pipeline, Main)
  smt/            SmtNativeSolver.scala
  synthesis/      SmtSynthesizer.scala
src/main/scala-js/implicitlang/smt/SmtBrowserSolver.scala
src/test/scala/implicitlang/CalculusTests.scala
playground/       Scala.js bridge, React application, browser tests
examples/*.imp
```

`core.TypeChecker.infer(term, environment, typeDepth)` returns the type of a fully annotated
core term. `check(term, expected, environment, typeDepth)` validates it against an expected type.
The environment is nearest-first; `typeDepth` counts free type variables. Both operations validate
the entire input context, including unused entries. They enforce the core typing rules for
functions, explicit type abstraction and application, lets, records, primitives, and unrestricted
fixpoints. The resolver uses the same checker for its final result and synthesized evidence.

The evaluator uses standard call-by-value closures over immutable value environments.
A fixpoint unfolds by capture-avoiding substitution:
`fix x:T.e` evaluates `e[fix x:T.e/x]` in the same environment.
The explicit continuation stack and suspended substitution traversals keep deep term evaluation
from consuming the host call stack. Captured environments remain unchanged across evaluations.

## Primitive operators

The same `BinOp(kind, left, right)` node is used in the first AST, unresolved core,
and final calculus. `+`, `-`, `*`, `==`, `<`, `>` have `Int` operands; `++` has `String`
operands. Results are `Int`, `Bool`, and `String` respectively. The parser follows
ordinary arithmetic precedence (`*` before `+` / `-`), followed by `++` and comparisons.
There is no special-purpose `Concat` or `ShowInt` expression.

## Executable examples

`sbt test` recursively discovers all `.imp` files in `examples/`, ordered by path.
Each file's first line specifies its expected outcome:

```text
// result: 21
```

or:

```text
// error: UnknownTerm(missing)
```

The text after the colon and optional separating space must match the CLI's rendered
value or error exactly. String results use their contents without quotes. Missing or malformed
headers, different output, and an unexpected success or failure fail the example's test.
Adding an example requires no Scala test changes.

Tests cover declaration parsing, Γ/Δ separation, scope and shadowing, type weakening,
alias capture, first-class values, explicit implementation arguments, malformed types,
SMT composition and polymorphism, search limits, final checking, and replacement-synthesizer validation.

## Deliberate boundaries

No subtyping, existential types, nominal class identities, modules, forward references,
mutually recursive declarations, higher-kinded types, datatype declarations, automatic generalization,
or general type inference. Definitions are lexical and sequential; use before declaration
is rejected. Trait constraints expand to structural record types, so constraints with identical
expanded dictionaries share evidence. Trait names do not introduce nominal type identities.
Record labels are unique and unordered. Integers are arbitrary precision.
The calculus is pure. Its primitive binary operators are integer `+`, `-`, `*`, `==`, `<`, `>` and
string `++`. The comparison operators return `Bool`. There is no built-in integer-to-string
conversion: a printer for integers is a separate library concern, not a primitive expression.

Dependencies: [Scala 3.3.6](https://www.scala-lang.org/download/3.3.6.html) and
[Scala parser combinators](https://github.com/scala/scala-parser-combinators).
