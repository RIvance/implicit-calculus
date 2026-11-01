import ImplicitSynthesis
import Lean

/-! Audit source declarations and all dependencies reachable from their types and proofs. -/

open Lean Elab Command

run_cmd do
  let env ← getEnv
  let mut checked : Nat := 0
  for (n, _) in env.constants do
    let userName := (privateToUserName? n).getD n
    if !(`ImplicitSynthesis).isPrefixOf userName then continue
    if (← findDeclarationRangesCore? n).isNone then continue
    let dependencies ← collectAxioms n
    unless dependencies.isEmpty do
      throwError "{n} depends on axioms: {dependencies}"
    checked := checked + 1
  if checked == 0 then throwError "The axiom audit found no source declarations."
  logInfo m!"Axiom audit passed for {checked} source declarations and their dependencies."
