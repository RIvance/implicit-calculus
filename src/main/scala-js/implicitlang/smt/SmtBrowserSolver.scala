package implicitlang.smt

import implicitlang.util.Result

import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal
import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport
import scala.scalajs.js.JSConverters.*

@js.native
@JSImport("z3-solver", JSImport.Namespace)
private object SmtBindings extends js.Object {
  def init(): js.Promise[js.Dynamic] = js.native
  val Z3_decl_kind: js.Dynamic = js.native
}

/** One browser worker owns the module. Checks are sequential and each releases its solver and model. */
final class SmtBrowserSolver private (api: js.Dynamic)(using ExecutionContext) extends SmtSolver[Future] {
  private var closed = false

  def version: String = api.getVersionString().asInstanceOf[String]

  /** Release idle worker threads. The owner must await outstanding checks before closing. */
  def close(): Unit = {
    if !closed then {
      api.em.PThread.terminateAllThreads()
      closed = true
    }
  }

  def check[Origin](problem: SmtProblem[Origin], timeoutMillis: Int): Future[Result[SmtResponse, SmtFailure]] = {
    require(timeoutMillis > 0)
    require(!closed, "The solver module is closed")
    val started = System.nanoTime()
    val context = js.Dynamic.newInstance(api.Context)("synthesis")
    val parsed = js.Dynamic.newInstance(context.Solver)()
    val solver = js.Dynamic.newInstance(context.Solver)()
    try {
      parsed.fromString(problem.parsingScript)
      val assertions = parsed.assertions()
      require(assertions.length().asInstanceOf[Int] == problem.constraints.size + problem.observations.size)
      (0 until problem.constraints.size).foreach(index => solver.add(assertions.get(index)))
      solver.set("timeout", timeoutMillis)
      solver.check().asInstanceOf[js.Promise[String]].toFuture.map { status =>
        val answer = status match {
          case "sat" => {
            val model = solver.model()
            try {
              val values = problem.observations.zipWithIndex.map { case (observation, index) =>
                val expression = assertions.get(problem.constraints.size + index).arg(1)
                observation.name -> decode(context, model.eval(expression, true))
              }.toMap
              SmtAnswer.Sat(SmtModel(values, model.sexpr().asInstanceOf[String]))
            } finally model.release()
          }
          case "unsat" => SmtAnswer.Unsat
          case "unknown" => {
            SmtAnswer.Unknown(api.Z3.solver_get_reason_unknown(context.ptr, solver.ptr).asInstanceOf[String])
          }
          case _ => throw new IllegalStateException(s"Unexpected solver status: $status")
        }
        Result.Ok(SmtResponse(answer, (System.nanoTime() - started) / 1000000.0, statistics(context, solver)))
      }.recover {
        case error: js.JavaScriptException => Result.Err(SmtFailure(error))
      }.andThen { case _ => solver.release(); parsed.release() }
    } catch {
      case error: js.JavaScriptException => {
        solver.release()
        parsed.release()
        Future.successful(Result.Err(SmtFailure(error)))
      }
      case NonFatal(error) => {
        solver.release()
        parsed.release()
        Future.failed(error)
      }
    }
  }

  private def decode(context: js.Dynamic, expression: js.Dynamic): SmtValue = {
    if context.isIntVal(expression).asInstanceOf[Boolean] then {
      SmtValue.Integer(BigInt(expression.value().toString))
    } else if context.isTrue(expression).asInstanceOf[Boolean] then {
      SmtValue.Boolean(true)
    } else if context.isFalse(expression).asInstanceOf[Boolean] then {
      SmtValue.Boolean(false)
    } else if expression.decl().kind() == SmtBindings.Z3_decl_kind.Z3_OP_DT_CONSTRUCTOR then {
      val children = Vector.tabulate(expression.numArgs().asInstanceOf[Int]) { index =>
        decode(context, expression.arg(index))
      }
      SmtValue.Constructor(expression.decl().name().asInstanceOf[String], children)
    } else {
      throw new IllegalStateException("Solver returned an unevaluated model observation")
    }
  }

  private def statistics(context: js.Dynamic, solver: js.Dynamic): Map[String, String] = {
    val native = api.Z3
    val stats = native.solver_get_statistics(context.ptr, solver.ptr)
    native.stats_inc_ref(context.ptr, stats)
    try {
      Vector.tabulate(native.stats_size(context.ptr, stats).asInstanceOf[Int]) { index =>
        val key = native.stats_get_key(context.ptr, stats, index).asInstanceOf[String]
        val value = if native.stats_is_uint(context.ptr, stats, index).asInstanceOf[Boolean] then {
          native.stats_get_uint_value(context.ptr, stats, index)
        } else {
          native.stats_get_double_value(context.ptr, stats, index)
        }
        key -> value.toString
      }.toMap
    } finally native.stats_dec_ref(context.ptr, stats)
  }
}

object SmtBrowserSolver {
  /** The browser host loads the package's standalone WebAssembly loader before initialization. */
  def create()(using ExecutionContext): Future[SmtBrowserSolver] = {
    SmtBindings.init().toFuture.map(new SmtBrowserSolver(_))
  }
}
