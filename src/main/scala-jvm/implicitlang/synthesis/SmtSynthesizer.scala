package implicitlang.synthesis

import implicitlang.`implicit`.ImplicitTerm
import implicitlang.core.Type
import implicitlang.diagnostic.Error
import implicitlang.smt.{SmtNativeSolver, SmtSolver}
import implicitlang.util.Result

/** Executes the shared search with a synchronous solver. */
final class SmtSynthesizer(
  val limits: SynthesisLimits = SynthesisLimits(), solver: SmtSolver[[A] =>> A] = new SmtNativeSolver
) extends Synthesizer {
  def synthesize(goal: Type, context: SynthesisContext): Result[ImplicitTerm, Error] = inspect(goal, context).result

  override def inspect(goal: Type, context: SynthesisContext): SynthesisReport = {
    val deadline = System.nanoTime() + limits.timeoutMillis.toLong * 1000000
    @annotation.tailrec
    def run(search: SmtSearch): SynthesisReport = search match {
      case SmtSearch.Finished(report) => report
      case SmtSearch.Query(problem, _, history, resume) => {
        val remaining = ((deadline - System.nanoTime()) / 1000000).toInt
        if remaining <= 0 then SynthesisReport(Result.Err(Error.SynthesisUnknown(goal, "timeout")), history)
        else run(resume(remaining, solver.check(problem, remaining)))
      }
    }
    run(SmtSearch.start(goal, context, limits))
  }
}
