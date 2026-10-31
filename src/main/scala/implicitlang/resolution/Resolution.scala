package implicitlang.resolution

import implicitlang.`implicit`.{DisplayNames, TermLocation}
import implicitlang.core.Type
import implicitlang.diagnostic.Error
import implicitlang.synthesis.{SynthesisContext, SynthesisReport, Synthesizer}
import implicitlang.util.Result

final case class SynthesisGoal(
  tpe: Type, context: SynthesisContext, ordinary: List[Type], names: DisplayNames,
  location: TermLocation = TermLocation()
)
final case class ResolvedGoal(request: SynthesisGoal, synthesis: SynthesisReport, validation: Result[Unit, Error])
final case class ResolutionReport[+A](result: Result[A, Error], goals: Vector[ResolvedGoal])

/** A lexical traversal suspended only at evidence requests; completed goals survive later failures. */
enum Resolution[+A] {
  case Complete(report: ResolutionReport[A])
  case Request(goal: SynthesisGoal, resume: SynthesisReport => Resolution[A])

  def map[B](function: A => B): Resolution[B] = flatMap(value => Resolution.fromResult(Result.Ok(function(value))))

  def flatMap[B](function: A => Resolution[B]): Resolution[B] = this match {
    case Complete(ResolutionReport(Result.Ok(value), goals)) => function(value).prepend(goals)
    case Complete(ResolutionReport(Result.Err(error), goals)) => Complete(ResolutionReport(Result.Err(error), goals))
    case Request(goal, resume) => Request(goal, report => resume(report).flatMap(function))
  }

  private def prepend(goals: Vector[ResolvedGoal]): Resolution[A] = this match {
    case Complete(report) => Complete(report.copy(goals = goals ++ report.goals))
    case Request(goal, resume) => Request(goal, report => resume(report).prepend(goals))
  }

  /** Interpret the same traversal with a synchronous synthesizer. */
  final def run(synthesizer: Synthesizer): ResolutionReport[A] = {
    @annotation.tailrec
    def loop(current: Resolution[A]): ResolutionReport[A] = current match {
      case Complete(report) => report
      case Request(goal, resume) => loop(resume(synthesizer.inspect(goal.tpe, goal.context)))
    }
    loop(this)
  }
}

object Resolution {
  def fromResult[A](result: Result[A, Error]): Resolution[A] = {
    Resolution.Complete(ResolutionReport(result, Vector.empty))
  }

  def traverse[K, A, B](values: Map[K, A])(function: (K, A) => Resolution[B]): Resolution[Map[K, B]] = {
    values.foldLeft(fromResult[Map[K, B]](Result.Ok(Map.empty))) { case (accumulated, (key, value)) =>
      accumulated.flatMap(items => function(key, value).map(result => items.updated(key, result)))
    }
  }
}
