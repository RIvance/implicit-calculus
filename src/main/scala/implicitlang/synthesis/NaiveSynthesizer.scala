package implicitlang.synthesis

import implicitlang.`implicit`.ImplicitTerm
import implicitlang.core.Type
import implicitlang.diagnostic.Error
import implicitlang.util.Result

/** Baseline: nearest matching instance in Δ. No instantiation or composition. */
final class NaiveSynthesizer extends Synthesizer {
  def synthesize(goal: Type, context: SynthesisContext): Result[ImplicitTerm, Error] = {
    inspect(goal, context).result
  }

  override def inspect(goal: Type, context: SynthesisContext): SynthesisReport = {
    val index = context.instances.indexOf(goal)
    if index >= 0 then {
      val evidence = EvidenceProgram(Vector(EvidenceSpine(index, Vector.empty, goal)))
      SynthesisReport(Result.Ok(evidence.term()), evidence = Some(evidence))
    } else SynthesisReport(Result.Err(Error.MissingInstance(goal)))
  }
}
