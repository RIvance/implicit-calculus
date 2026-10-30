package implicitlang.smt

import implicitlang.util.Result

import com.microsoft.z3.{Context, Expr, IntNum, Status, Z3Exception}
import com.microsoft.z3.enumerations.Z3_decl_kind

/** Native expressions and model handles live only for the duration of one check. */
final class SmtNativeSolver extends SmtSolver[[A] =>> A] {
  def check[Origin](problem: SmtProblem[Origin], timeoutMillis: Int): Result[SmtResponse, SmtFailure] = {
    require(timeoutMillis > 0)
    val started = System.nanoTime()
    try {
      val native = new Context()
      try {
        val parsed = native.parseSMTLIB2String(problem.parsingScript, null, null, null, null)
        require(parsed.length == problem.constraints.size + problem.observations.size)
        val solver = native.mkSolver()
        solver.add(parsed.take(problem.constraints.size)*)
        val parameters = native.mkParams()
        parameters.add("timeout", timeoutMillis)
        solver.setParameters(parameters)
        val answer = solver.check() match {
          case Status.SATISFIABLE => {
            val model = solver.getModel
            val values = problem.observations.zipWithIndex.map { case (observation, index) =>
              val expression = parsed(problem.constraints.size + index).getArgs()(1)
              observation.name -> decode(model.eval(expression, true))
            }.toMap
            SmtAnswer.Sat(SmtModel(values, model.toString))
          }
          case Status.UNSATISFIABLE => SmtAnswer.Unsat
          case Status.UNKNOWN => SmtAnswer.Unknown(solver.getReasonUnknown)
        }
        val statistics = solver.getStatistics.getEntries.toVector.map(entry => entry.Key -> entry.getValueString).toMap
        Result.Ok(SmtResponse(answer, (System.nanoTime() - started) / 1000000.0, statistics))
      } finally native.close()
    } catch {
      case error: Z3Exception => Result.Err(SmtFailure(error))
      case error: LinkageError => Result.Err(SmtFailure(error))
    }
  }

  private def decode(expression: Expr[?]): SmtValue = expression match {
    case number: IntNum => SmtValue.Integer(BigInt(number.getBigInteger))
    case value if value.isTrue => SmtValue.Boolean(true)
    case value if value.isFalse => SmtValue.Boolean(false)
    case value if value.getFuncDecl.getDeclKind == Z3_decl_kind.Z3_OP_DT_CONSTRUCTOR => {
      SmtValue.Constructor(value.getFuncDecl.getName.toString, value.getArgs.toVector.map(decode))
    }
    case _ => throw new IllegalStateException("Solver returned an unevaluated model observation")
  }
}
