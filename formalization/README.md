# Implicit → core formalization

This Lean development formalizes resolution from the implicit calculus to the
explicit core calculus, together with a mathematical model of bounded SMT
synthesis. The judgments are inductive relations. Types, binding operations,
and evidence shapes are ordinary inductive data and structural functions.

With Lean 4.34.1 available, build from this directory:

```sh
lake build
```

The project uses Lean's standard library and has no external package
dependencies. The default build also checks the example derivations and runs
[the axiom audit](Audit.lean). The audit discovers imported project declarations
with source locations and checks their transitive dependencies, including the
types and proofs of theorems. Any axiom dependency, including `sorryAx`,
`propext`, or choice, fails the build.

## Calculi and records

The implicit calculus includes integers, ordinary and implicit functions,
type abstraction and application, ordinary and instance lets, requests,
records, projections, and unrestricted fixpoints. The core has explicit
functions and applications, polymorphism, lets, records, projections, and
fixpoints. Evidence is restricted to

> p ::= d | p[B] | p⟨r⟩

An ordinary function may itself be selected as evidence. Evidence synthesis
cannot apply it to an ordinary argument. Ordinary application remains a term
construct in both calculi.

Type variables and the two source variable spaces use de Bruijn indices.
Resolution embeds ordinary bindings and instance bindings into the single
core context, adjusting both embeddings beneath each binder.

Records use association lists. A well-formed record **type** has strictly
increasing labels, giving an unambiguous representation of an unordered finite
map. Record **terms** may enumerate their fields in any order. Their typing
rules match fields by label and require exactly the type's labels.

The record proofs establish:

- `Canonical.uniqueField`: a label has a unique field type.
- `Canonical.ext`: canonical rows containing the same fields are equal.
- `ImplicitTyping.permuteRecord` and `CoreTyping.permuteRecord`: permuting
  record fields preserves typing.

## Proved results

Here `|A|` erases implicit arrows into ordinary arrows. Context embeddings
preserve lookup and assign each source binding its erased type.

| Result | Statement | Proofs |
| --- | --- | --- |
| Binding compatibility | Erasure commutes with renaming, substitution, and type instantiation; well-formed types erase to well-formed types. | [Types](ImplicitSynthesis/Metatheory/Types.lean) |
| Resolution preserves typing | Γ; Δ ⊢ c : A and c ⇝ t imply H ⊢ t : \|A\| under typed context embeddings. | [Resolution](ImplicitSynthesis/Metatheory/Resolution.lean) |
| Evidence lowering | Every evidence term has a unique lowering; typed evidence lowers to a core term of the erased type. | [Resolution](ImplicitSynthesis/Metatheory/Resolution.lean) |
| Bounded model correspondence | A shared program π is a bounded, well-typed witness iff some satisfying model has program π. | [Synthesis](ImplicitSynthesis/Metatheory/Synthesis.lean) |
| Oracle soundness | `sat` supplies typed evidence and a typed core term; `unsat` excludes witnesses within the query's bounds. | [Synthesis](ImplicitSynthesis/Metatheory/Synthesis.lean) |
| Validity refinement | Refinement preserves all complete models, excludes the rejected model, and performs at most as many refining steps as there are outstanding obligations. | [SMT](ImplicitSynthesis/Metatheory/SMT.lean) |
| Bounded ambiguity | Given a first witness, excluding its evidence shape yields either a distinct witness on `sat`, or uniqueness of that shape within the bounds on `unsat`. | [Synthesis](ImplicitSynthesis/Metatheory/Synthesis.lean) |

The preservation theorem covers all term constructors, including records,
polymorphism, instance lets, and fixpoints. Resolution does not assume that its
output is well typed. Each request requires checked evidence; the theorem then
establishes typing of the complete output term.

## What the SMT correspondence means

A program consists of instance-headed application spines. Each elimination
supplies either a type argument or a reference to an earlier spine. References
share evidence without adding instances to the source context. Program cost
counts each spine head and each elimination once.

[The constraint model](ImplicitSynthesis/Synthesis.lean) records the chosen
program, intermediate type traces, and result types. Its local constraints
check head lookup, universal instantiation, implicit-arrow elimination, and
the requested result type. Type arguments must be well formed and satisfy the
query's height bound. Decoding separately expands spine references into
evidence; it does not inspect the type traces.

For a query φ and a shared program π, `boundedCorrespondence` proves

> Witness(φ, π) ↔ ∃ μ. μ.program = π ∧ Holds(φ, μ).

Both sides use the same cost and type-height bounds. Internally, program and
argument lists are stored most recent first, so earlier dependencies lie in
the tail. This representation makes cyclic references impossible in a
satisfying model.

The [SMT oracle interface](ImplicitSynthesis/SMT.lean) is a typeclass with a
response relation and proofs of `sat` and `unsat` soundness. Theorems are
conditional on an oracle satisfying that interface. No concrete solver is
assumed or implemented. `unknown` carries no existence or absence conclusion;
the interface imposes neither completeness nor termination.

`OracleSearch.sound` connects those responses to the evidence judgment, and
`OracleSearch.resolvesNeed` connects successful synthesis to request resolution
and core typing.

Refinement is proved for an arbitrary finite family of validity obligations.
Each refining step moves one violated obligation from the outstanding list
into the query. The step bound concerns these transitions, not solver runtime.

Ambiguity queries exclude the decoded evidence **shape**: instance identities,
type-application positions, and implicit-application structure. Type-argument
annotations are omitted. Decoding and projection are proved deterministic, so
alternative sharing arrangements or irrelevant model assignments do not count
as distinct solutions. Shape uniqueness is distinct from semantic equivalence
of dictionary implementations.

## Examples and scope

[The example derivations](ImplicitSynthesis/Examples.lean) cover polymorphic
identity, capture-avoiding instantiation, both kinds of variable crossing a
binder, local instance selection, record reordering, unrestricted fixpoints,
a polymorphic synthesized witness, two competing instances, and the exclusion
of ordinary application from evidence search.

The verified correspondence concerns bounded, well-typed **shared programs**.
It does not prove that every arbitrary evidence tree can be represented within
any particular bound. Labels are unrestricted natural numbers; the paper's
finite-label reduction has not been formalized.

Also outside this development are operational semantics and their
progress/preservation proofs, behavioral coherence, complexity and
undecidability reductions, and correctness of the concrete Scala implementation
or its SMT serialization. Parsing and surface elaboration are outside the
implicit → core boundary. The results above concern the relational calculus
and the mathematical constraint model.
