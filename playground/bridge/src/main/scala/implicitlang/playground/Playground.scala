package implicitlang.playground

import implicitlang.{Compiler, Compilation, PreparedProgram}
import implicitlang.`implicit`.{DisplayNames, ImplicitPrinter, ImplicitTerm, TermChild, TermLocation}
import implicitlang.core.{Evaluator, Term, Type, Value}
import implicitlang.diagnostic.Error
import implicitlang.resolution.{Resolution, ResolutionReport, SynthesisGoal}
import implicitlang.smt.*
import implicitlang.synthesis.*
import implicitlang.util.Result

import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js
import scala.scalajs.js.annotation.{JSExport, JSExportTopLevel}
import scala.scalajs.js.JSConverters.*

object Playground {
  @JSExportTopLevel("language")
  def language(): js.Object = Presentation.obj(
    "keywords" -> implicitlang.surface.Parser.keywords.toVector.sorted.toJSArray,
    "primitiveTypes" -> implicitlang.surface.Parser.primitiveTypes.toVector.sorted.toJSArray,
    "literals" -> implicitlang.surface.Parser.literals.toVector.sorted.toJSArray
  )

  @JSExportTopLevel("analyze")
  def analyze(source: String): js.Object = Compiler.prepare(source) match {
    case Result.Ok(program) => Presentation.prepared(program)
    case Result.Err(error) => Presentation.failure(error)
  }

  @JSExportTopLevel("createRunner")
  def createRunner(): js.Promise[Runner] = SmtBrowserSolver.create().map(new Runner(_)).toJSPromise
}

/** The host owns this runner and its worker; terminating the worker cancels solving and evaluation. */
final class Runner(solver: SmtBrowserSolver) {
  @JSExport
  def close(): Unit = solver.close()

  @JSExport
  def version: String = solver.version

  @JSExport
  def run(
    source: String, maximumSteps: Int, maximumTypeDepth: Int, timeoutMillis: Int,
    mode: String, evaluate: Boolean, emit: js.Function1[js.Object, Unit]
  ): js.Promise[js.Object] = {
    if maximumSteps <= 0 || maximumTypeDepth <= 0 || timeoutMillis <= 0 || !Set("smt", "exact").contains(mode) then {
      Future.successful(Presentation.obj(
        "ok" -> false,
        "diagnostic" -> Presentation.obj("code" -> "Configuration", "message" -> "Invalid search settings.")
      )).toJSPromise
    } else {
      val started = System.nanoTime()
      val limits = SynthesisLimits(maximumSteps, maximumTypeDepth, timeoutMillis)
      emit(Presentation.obj("kind" -> "stage", "stage" -> "preparing"))
      Compiler.prepare(source) match {
        case Result.Err(error) => Future.successful(Presentation.failure(error)).toJSPromise
        case Result.Ok(prepared) => {
          emit(Presentation.obj("kind" -> "prepared", "analysis" -> Presentation.prepared(prepared)))
          emit(Presentation.obj("kind" -> "stage", "stage" -> "resolving"))
          def search(
            current: SmtSearch, goalIndex: Int, goal: Type, deadline: Long
          ): Future[SynthesisReport] = current match {
            case SmtSearch.Finished(report) => Future.successful(report)
            case SmtSearch.Query(problem, _, previous, resume) => {
              val remaining = ((deadline - System.nanoTime()) / 1000000).toInt
              if remaining <= 0 then {
                Future.successful(SynthesisReport(Result.Err(Error.SynthesisUnknown(goal, "timeout")), previous))
              } else {
                solver.check(problem, remaining).flatMap { response =>
                  val next = resume(remaining, response)
                  val history = next match {
                    case SmtSearch.Finished(report) => report.attempts
                    case SmtSearch.Query(_, _, attempts, _) => attempts
                  }
                  emit(Presentation.obj(
                    "kind" -> "attempt", "goalIndex" -> goalIndex,
                    "attempt" -> Presentation.attempt(history.last, history.size - 1)
                  ))
                  search(next, goalIndex, goal, deadline)
                }
              }
            }
          }
          def resolve(current: Resolution[Compilation], goalIndex: Int): Future[ResolutionReport[Compilation]] = {
            current match {
              case Resolution.Complete(report) => Future.successful(report)
              case Resolution.Request(goal, resume) => {
                emit(Presentation.obj("kind" -> "goal", "goalIndex" -> goalIndex, "goal" -> Presentation.goal(goal)))
                val evidence = if mode == "exact" then {
                  Future.successful(new NaiveSynthesizer().inspect(goal.tpe, goal.context))
                } else {
                  val deadline = System.nanoTime() + limits.timeoutMillis.toLong * 1000000
                  search(SmtSearch.start(goal.tpe, goal.context, limits), goalIndex, goal.tpe, deadline)
                }
                evidence.flatMap { report =>
                  emit(Presentation.obj(
                    "kind" -> "evidence", "goalIndex" -> goalIndex,
                    "report" -> Presentation.synthesis(report, goal.names)
                  ))
                  resolve(resume(report), goalIndex + 1)
                }
              }
            }
          }
          resolve(prepared.resolve, 0).map { report =>
            val value = report.result.flatMap { compilation =>
              if evaluate then {
                emit(Presentation.obj("kind" -> "stage", "stage" -> "evaluating"))
                Evaluator.evaluate(compilation.target).map(Some.apply)
              } else {
                Result.Ok(None)
              }
            }
            Presentation.completed(prepared, report, value, (System.nanoTime() - started) / 1000000.0)
          }.toJSPromise
        }
      }
    }
  }
}
private object Presentation {
  def obj(fields: (String, js.Any)*): js.Object = js.Dictionary[js.Any](fields*).asInstanceOf[js.Object]

  def failure(error: Error, names: DisplayNames = DisplayNames()): js.Object = {
    obj("ok" -> false, "diagnostic" -> diagnostic(error, names))
  }

  def prepared(program: PreparedProgram): js.Object = obj(
    "ok" -> true,
    "type" -> ImplicitPrinter.tpe(program.elaborated.tpe),
    "unresolvedTerm" -> unresolvedTerm(program.elaborated.expression),
    "symbols" -> program.source.declarations.flatMap { declaration =>
      import implicitlang.surface.Declaration
      declaration match {
        case Declaration.TypeAlias(name, _, _) => Some(obj("name" -> name, "kind" -> "type"))
        case Declaration.Trait(name, _, _) => Some(obj("name" -> name, "kind" -> "trait"))
        case Declaration.Def(name, _, _, _, _) => Some(obj("name" -> name, "kind" -> "def"))
        case Declaration.Instance(name, _, _, _) => Some(obj("name" -> name, "kind" -> "instance"))
        case _ => None
      }
    }.toJSArray
  )

  private def location(value: TermLocation): js.Array[js.Any] = value.steps.map[js.Any] {
    case TermChild.Body => "body"
    case TermChild.Value => "value"
    case TermChild.Function => "function"
    case TermChild.Argument => "argument"
    case TermChild.Receiver => "receiver"
    case TermChild.Left => "left"
    case TermChild.Right => "right"
    case TermChild.Field(label) => obj("field" -> label)
  }.toJSArray

  private def unresolvedTerm(value: ImplicitTerm): js.Object = {
    import ImplicitPrinter.Document
    val document = ImplicitPrinter.document(value)
    val reserved = document.goals.flatMap(_.nameHint).toSet
    final case class Names(used: Set[String] = Set.empty, anonymous: Int = 0)

    def render(document: Document, names: Names): (js.Object, Names) = document match {
      case Document.Text(text) => (obj("kind" -> "text", "text" -> text), names)
      case Document.Line(flat) => (obj("kind" -> "line", "flat" -> flat.orNull), names)
      case Document.Goal(path, expectedType, hint) => {
        val (annotation, afterAnnotation) = render(expectedType, names)
        val (name, next) = hint match {
          case Some(base) => {
            val name = if !afterAnnotation.used(base) then base else {
              Iterator.from(1).map(index => s"$base$index")
                .find(name => !afterAnnotation.used(name) && !reserved(name)).get
            }
            (name, afterAnnotation.copy(used = afterAnnotation.used + name))
          }
          case None => {
            def label(index: Int): String = "δ" + index.toString.map(digit => "₀₁₂₃₄₅₆₇₈₉"(digit - '0'))
            val index = Iterator.from(afterAnnotation.anonymous + 1).find { index =>
              !afterAnnotation.used(label(index)) && !reserved(label(index))
            }.get
            val name = label(index)
            (name, Names(afterAnnotation.used + name, index))
          }
        }
        (obj("kind" -> "goal", "location" -> location(path), "name" -> name, "annotation" -> annotation), next)
      }
      case Document.Sequence(parts) => {
        val (rendered, next) = parts.foldLeft((Vector.empty[js.Object], names)) { case ((result, names), part) =>
          val (value, next) = render(part, names)
          (result :+ value, next)
        }
        (obj("kind" -> "sequence", "parts" -> rendered.toJSArray), next)
      }
      case Document.Nest(body) => {
        val (rendered, next) = render(body, names)
        (obj("kind" -> "nest", "body" -> rendered), next)
      }
      case Document.Group(body) => {
        val (rendered, next) = render(body, names)
        (obj("kind" -> "group", "body" -> rendered), next)
      }
      case Document.Fold(path, label, summary, body) => {
        val (renderedSummary, afterSummary) = render(summary, names)
        val (renderedBody, next) = render(body, afterSummary)
        (obj(
          "kind" -> "fold", "location" -> location(path), "label" -> label,
          "summary" -> renderedSummary, "body" -> renderedBody
        ), next)
      }
    }
    render(document, Names())._1
  }

  def goal(value: SynthesisGoal): js.Object = obj(
    "location" -> location(value.location),
    "type" -> ImplicitPrinter.tpe(value.tpe, value.names),
    "typeVariables" -> value.names.types.toJSArray,
    "instances" -> value.context.instances.zipWithIndex.map { case (tpe, index) =>
      obj(
        "index" -> index, "name" -> value.names.instances(index),
        "type" -> ImplicitPrinter.tpe(tpe, value.names),
        "form" -> (tpe match {
          case Type.Forall(_) => "polymorphic"
          case Type.Implicit(_, _) => "implicit function"
          case _ => "value"
        })
      )
    }.toJSArray,
    "ordinary" -> value.ordinary.zipWithIndex.map { case (tpe, index) =>
      obj("name" -> value.names.ordinary(index), "type" -> ImplicitPrinter.tpe(tpe, value.names))
    }.toJSArray
  )

  private def constraint(value: SmtConstraint[ConstraintOrigin], index: Int): js.Object = obj(
    "id" -> index, "rule" -> value.origin.rule.toString, "explanation" -> value.origin.explanation,
    "spine" -> value.origin.spine.fold[js.Any](null)(index => index),
    "formula" -> value.formula.math, "smtlib" -> value.formula.smtlib
  )

  def attempt(value: SynthesisAttempt, index: Int): js.Object = {
    val (status, model, reason, elapsed, statistics) = value.response match {
      case Result.Err(error) => ("error", None, Option(error.cause.getMessage), 0.0, Map.empty[String, String])
      case Result.Ok(response) => response.answer match {
        case SmtAnswer.Sat(model) => ("sat", Some(model), None, response.elapsedMillis, response.statistics)
        case SmtAnswer.Unsat => ("unsat", None, None, response.elapsedMillis, response.statistics)
        case SmtAnswer.Unknown(reason) => ("unknown", None, Some(reason), response.elapsedMillis, response.statistics)
      }
    }
    obj(
      "index" -> index, "spineCount" -> value.spineCount, "status" -> status, "elapsedMillis" -> elapsed,
      "recordLabels" -> value.recordLabels.toJSArray,
      "accepted" -> (status == "sat" && value.refinements.isEmpty),
      "reason" -> reason.fold[js.Any](null)(value => value),
      "constraints" -> value.problem.constraints.zipWithIndex.map(constraint).toJSArray,
      "refinements" -> value.refinements.zipWithIndex.map(constraint).toJSArray,
      "smtlib" -> value.problem.smtlib(value.timeoutMillis, model.nonEmpty),
      "model" -> model.fold[js.Any](null) { model =>
        obj(
          "text" -> model.text,
          "values" -> model.values.toVector.sortBy(_._1).map { case (name, value) =>
            obj("name" -> name, "value" -> modelValue(value))
          }.toJSArray
        )
      },
      "statistics" -> js.Dictionary(statistics.toSeq*)
    )
  }

  private def modelValue(value: SmtValue): js.Object = value match {
    case SmtValue.Integer(value) => obj("kind" -> "integer", "value" -> value.toString)
    case SmtValue.Boolean(value) => obj("kind" -> "boolean", "value" -> value)
    case SmtValue.Constructor(name, arguments) => obj(
      "kind" -> "constructor", "name" -> name, "arguments" -> arguments.map(modelValue).toJSArray
    )
  }

  def synthesis(value: SynthesisReport, names: DisplayNames): js.Object = obj(
    "ok" -> (value.result match { case Result.Ok(_) => true; case _ => false }),
    "term" -> (value.result match {
      case Result.Ok(term) => ImplicitPrinter.term(term, names)
      case _ => null
    }),
    "diagnostic" -> (value.result match {
      case Result.Err(error) => diagnostic(error, names)
      case _ => null
    }),
    "attempts" -> value.attempts.zipWithIndex.map(attempt).toJSArray,
    "evidence" -> value.evidence.fold[js.Any](null) { program =>
      obj(
        "cost" -> program.cost,
        "spines" -> program.spines.zipWithIndex.map { case (spine, index) =>
          obj(
            "index" -> index, "head" -> spine.head, "name" -> names.instances(spine.head),
            "type" -> ImplicitPrinter.tpe(spine.result, names),
            "term" -> ImplicitPrinter.term(program.term(index), names),
            "root" -> (index == program.spines.size - 1),
            "arguments" -> spine.arguments.map {
              case EvidenceArgument.TypeArgument(tpe) => {
                obj("kind" -> "type", "type" -> ImplicitPrinter.tpe(tpe, names))
              }
              case EvidenceArgument.ImplicitArgument(previous) => obj("kind" -> "implicit", "spine" -> previous)
            }.toJSArray
          )
        }.toJSArray
      )
    }
  )

  def completed(
    prepared: PreparedProgram, report: ResolutionReport[Compilation],
    value: Result[Option[Value], Error], elapsed: Double
  ): js.Object = {
    val target = report.result match {
      case Result.Ok(compilation) => Some(coreTerm(compilation.target))
      case _ => None
    }
    obj(
      "ok" -> (value match { case Result.Ok(_) => true; case _ => false }),
      "analysis" -> this.prepared(prepared), "elapsedMillis" -> elapsed,
      "coreTerm" -> target.fold[js.Any](null)(value => value),
      "value" -> (value match {
        case Result.Ok(value) => value.fold[js.Any](null)(value => value.show)
        case _ => null
      }),
      "diagnostic" -> (value match { case Result.Err(error) => diagnostic(error); case _ => null }),
      "goals" -> report.goals.map { value =>
        obj(
          "request" -> goal(value.request), "synthesis" -> synthesis(value.synthesis, value.request.names),
          "validated" -> (value.validation match { case Result.Ok(_) => true; case _ => false })
        )
      }.toJSArray
    )
  }

  private def coreTerm(value: Term, names: DisplayNames = DisplayNames()): String = value match {
    case Term.Var(index) => names.ordinary.lift(index).getOrElse(s"x[$index]")
    case Term.Lam(annotation, body) => {
      val nested = names.bindOrdinary(None)
      s"λ(${nested.ordinary.head}: ${ImplicitPrinter.tpe(annotation, names)}). ${coreTerm(body, nested)}"
    }
    case Term.TypeLam(body) => {
      val nested = names.bindType(None)
      s"Λ${nested.types.head}. ${coreTerm(body, nested)}"
    }
    case Term.App(function, argument) => s"(${coreTerm(function, names)}) (${coreTerm(argument, names)})"
    case Term.TypeApp(function, argument) => s"(${coreTerm(function, names)})[${ImplicitPrinter.tpe(argument, names)}]"
    case Term.Let(value, body) => {
      val nested = names.bindOrdinary(None)
      s"let ${nested.ordinary.head} = ${coreTerm(value, names)} in\n${coreTerm(body, nested)}"
    }
    case Term.Fix(annotation, body) => {
      val nested = names.bindOrdinary(None)
      s"fix ${nested.ordinary.head}: ${ImplicitPrinter.tpe(annotation, names)}. ${coreTerm(body, nested)}"
    }
    case Term.IntLit(value) => value.toString
    case Term.StringLit(value) => js.JSON.stringify(value)
    case Term.BoolLit(value) => value.toString
    case Term.Record(fields) => fields.toVector.sortBy(_._1)
      .map((label, value) => s"$label = ${coreTerm(value, names)}").mkString("{", ", ", "}")
    case Term.Proj(record, label) => s"(${coreTerm(record, names)}).$label"
    case Term.BinOp(kind, left, right) => s"(${coreTerm(left, names)} $kind ${coreTerm(right, names)})"
  }

  private def diagnostic(error: Error, names: DisplayNames = DisplayNames()): js.Object = {
    def tpe(value: Type): String = ImplicitPrinter.tpe(value, names)
    val message = error match {
      case Error.Parse(message, _, _) => message
      case Error.UnknownTerm(name) => s"Unknown value '$name'."
      case Error.MissingParameterType(name) => s"Parameter '$name' needs an annotation or an expected function type."
      case Error.UnknownType(name) => s"Unknown type '$name'."
      case Error.DuplicateName(name) => s"Duplicate name '$name'."
      case Error.TypeArity(name, expected, actual) => s"$name expects $expected type arguments; received $actual."
      case Error.UnknownTrait(name) => s"Unknown trait '$name'."
      case Error.ExpectedTrait(name) => s"'$name' must name a trait."
      case Error.TraitInType(name) => s"Trait '$name' cannot be used as an ordinary type."
      case Error.RecursiveDefinitionType(name, actual) => {
        s"Recursive definition '$name' must have a function type; found ${tpe(actual)}."
      }
      case Error.TypeVariableApplied(name) => s"Type parameter '$name' cannot take type arguments."
      case Error.ExpectedType(expected, actual) => s"Expected ${tpe(expected)}; found ${tpe(actual)}."
      case Error.ExpectedFunction(actual) => s"Expected an ordinary function; found ${tpe(actual)}."
      case Error.ExpectedImplicitFunction(actual) => s"Expected an implicit function; found ${tpe(actual)}."
      case Error.ExpectedPolymorphic(actual) => s"Expected a universally quantified type; found ${tpe(actual)}."
      case Error.ExpectedRecord(actual) => s"Expected a record; found ${tpe(actual)}."
      case Error.MissingField(label) => s"The record has no field '$label'."
      case Error.MissingMethod(label, receiver) => s"No field or applicable method '$label' for ${tpe(receiver)}."
      case Error.AmbiguousMethod(label, _) => s"More than one method '$label' applies; use qualified selection."
      case Error.MissingInstance(goal) => s"No evidence found for ${tpe(goal)}."
      case Error.SynthesisLimit(goal, steps, depth) => {
        s"No evidence for ${tpe(goal)} within cost $steps and type height $depth. A larger search may succeed."
      }
      case Error.SynthesisUnknown(goal, reason) => s"The solver could not decide ${tpe(goal)}: $reason."
      case Error.SynthesisBackend(cause) => s"Solver failure: ${cause.getMessage}"
      case Error.InvalidTypeDepth(depth) => s"Invalid type scope depth $depth."
      case Error.InvalidEvidenceForm(_) => "The candidate uses a term outside the admissible evidence grammar."
      case Error.InvalidTermIndex(space, index) => s"Variable $index is outside $space."
      case Error.InvalidTypeIndex(index, depth) => s"Type variable $index is outside the scope of depth $depth."
      case Error.ImplicitTypeInTarget(actual) => s"The resolved core still contains an implicit type: ${tpe(actual)}."
      case Error.InvalidEvidence(expected, actual) => s"Evidence has type ${tpe(actual)}; expected ${tpe(expected)}."
      case Error.Runtime(message) => message
    }
    val position = error match {
      case Error.Parse(_, line, column) => obj("line" -> line, "column" -> column)
      case _ => null
    }
    obj("code" -> error.productPrefix, "message" -> message, "position" -> position)
  }
}
