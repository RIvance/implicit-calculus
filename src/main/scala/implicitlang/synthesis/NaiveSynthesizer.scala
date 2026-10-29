package implicitlang.synthesis

import implicitlang.util.Result

import implicitlang.Error
import implicitlang.core.{ImplicitTerm, Type}

/** Baseline: nearest matching instance in Δ. No instantiation or composition. */
final class NaiveSynthesizer extends Synthesizer {
  def synthesize(goal: Type, context: SynthesisContext): Result[ImplicitTerm, Error] = {
    val index = context.instances.indexOf(goal)
    if index >= 0 then Result.Ok(ImplicitTerm.Instance(index)) else Result.Err(Error.MissingInstance(goal))
  }
}
