package implicitlang.synthesis

import implicitlang.`implicit`.ImplicitTerm
import implicitlang.core.Type
import implicitlang.diagnostic.Error
import implicitlang.util.Result

/** Nearest-first Δ types. Type variables 0 .. typeDepth-1 are rigid, not solver metavariables. */
final case class SynthesisContext(instances: List[Type], typeDepth: Int)

trait Synthesizer {
  /**
   * Return a construction using only ImplicitTerm.Instance, TypeApp, and ImplicitApp.
   * Instance indices refer to Δ, never Γ or the merged runtime environment.
   * The resolver checks this restricted grammar, the source type, and the explicit target type.
   */
  def synthesize(goal: Type, context: SynthesisContext): Result[ImplicitTerm, Error]
}
