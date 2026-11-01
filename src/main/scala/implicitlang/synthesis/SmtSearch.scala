package implicitlang.synthesis

import implicitlang.`implicit`.ImplicitTerm
import implicitlang.core.Type
import implicitlang.diagnostic.Error
import implicitlang.smt.*
import implicitlang.smt.Smt.*
import implicitlang.util.Result

/** Bounds on the total size of a spine program and the height of inferred type arguments. */
final case class SynthesisLimits(maximumSteps: Int = 16, maximumTypeDepth: Int = 8, timeoutMillis: Int = 10000) {
  require(maximumSteps > 0, "Synthesis needs at least one construction step")
  require(maximumTypeDepth > 0, "Type arguments need positive depth")
  require(timeoutMillis > 0, "Synthesis needs a positive timeout")
}

enum EvidenceArgument {
  case TypeArgument(tpe: Type)
  case ImplicitArgument(spine: Int)
}

final case class EvidenceSpine(head: Int, arguments: Vector[EvidenceArgument], result: Type)
final case class EvidenceProgram(spines: Vector[EvidenceSpine]) {
  require(spines.nonEmpty)
  require(spines.zipWithIndex.forall { case (spine, index) =>
    spine.head >= 0 && spine.arguments.forall {
      case EvidenceArgument.TypeArgument(_) => true
      case EvidenceArgument.ImplicitArgument(previous) => previous >= 0 && previous < index
    }
  })

  val cost: Int = spines.map(spine => 1 + spine.arguments.size).sum

  def term(index: Int = spines.size - 1): ImplicitTerm = {
    val spine = spines(index)
    spine.arguments.foldLeft[ImplicitTerm](ImplicitTerm.Instance(spine.head)) {
      case (function, EvidenceArgument.TypeArgument(tpe)) => ImplicitTerm.TypeApp(function, tpe)
      case (function, EvidenceArgument.ImplicitArgument(previous)) => ImplicitTerm.ImplicitApp(function, term(previous))
    }
  }
}

enum ConstraintRule {
  case Scope, ResultType, TypeApplication, ImplicitApplication, Stop, Elimination, Goal, Search, TypeValidity
}

final case class ConstraintOrigin(rule: ConstraintRule, explanation: String, spine: Option[Int])

private[synthesis] def constraint(
  rule: ConstraintRule, explanation: String, formula: SmtExpr[Boolean], spine: Option[Int] = None
): SmtConstraint[ConstraintOrigin] = SmtConstraint(ConstraintOrigin(rule, explanation, spine), formula)

final case class SynthesisAttempt(
  spineCount: Int,
  problem: SmtProblem[ConstraintOrigin],
  recordLabels: Vector[String],
  timeoutMillis: Int,
  response: Result[SmtResponse, SmtFailure],
  refinements: Vector[SmtConstraint[ConstraintOrigin]]
)

final case class SynthesisReport(
  result: Result[ImplicitTerm, Error],
  attempts: Vector[SynthesisAttempt] = Vector.empty,
  evidence: Option[EvidenceProgram] = None
)

/**
 * A solver-independent search continuation. Advancing a query produces a successor; retained queries
 * and reports never change. JVM and browser interpreters differ only in how they execute a check.
 * The interpreter owns the deadline and supplies the actual check timeout with its response.
 */
enum SmtSearch {
  case Finished(report: SynthesisReport)
  case Query(
    problem: SmtProblem[ConstraintOrigin], spineCount: Int,
    history: Vector[SynthesisAttempt],
    resume: (Int, Result[SmtResponse, SmtFailure]) => SmtSearch
  )
}

object SmtSearch {
  def start(goal: Type, context: SynthesisContext, limits: SynthesisLimits = SynthesisLimits()): SmtSearch = {
    val valid = for {
      _ <- if context.typeDepth >= 0 then Result.Ok(()) else Result.Err(Error.InvalidTypeDepth(context.typeDepth))
      _ <- goal.validate(context.typeDepth)
      _ <- Result.traverse(context.instances.toVector)(_.validate(context.typeDepth))
    } yield ()

    valid match {
      case Result.Err(error) => SmtSearch.Finished(SynthesisReport(Result.Err(error)))
      case Result.Ok(_) => {
        val exact = context.instances.indexOf(goal)
        if exact >= 0 then {
          val evidence = EvidenceProgram(Vector(EvidenceSpine(exact, Vector.empty, goal)))
          SmtSearch.Finished(SynthesisReport(Result.Ok(evidence.term()), evidence = Some(evidence)))
        } else if context.instances.isEmpty then {
          SmtSearch.Finished(SynthesisReport(Result.Err(Error.MissingInstance(goal))))
        } else {
          val inputTypes = (goal +: context.instances.toVector).flatMap(subtypes)
          val labels = inputTypes.collect { case Type.Record(fields) => fields.keys }.flatten.distinct.sorted
          // In the ∀-free fragment, least-height proofs depend on strictly smaller heights.
          // For k distinct subtypes, at most k spines of at most k nodes suffice.
          val completeBound = if inputTypes.exists { case Type.Forall(_) => true; case _ => false } then None
            else Some(inputTypes.distinct.size.toLong * inputTypes.distinct.size)
          new EvidenceQuery(goal, context, labels, limits, completeBound).start()
        }
      }
    }
  }

  private def subtypes(tpe: Type): Vector[Type] = tpe +: (tpe match {
    case Type.Arrow(parameter, result) => subtypes(parameter) ++ subtypes(result)
    case Type.Implicit(parameter, result) => subtypes(parameter) ++ subtypes(result)
    case Type.Forall(body) => subtypes(body)
    case Type.Record(fields) => fields.values.toVector.flatMap(subtypes)
    case _ => Vector.empty
  })
}

/**
 * p ::= d | p[A] | p⟨p⟩. A spine eliminates one Δ head from left to right, using earlier spines
 * as implicit arguments. Known type structure is partially evaluated; inferred structure follows
 * the same typing rules symbolically. No ordinary application or fresh value is introduced.
 */
private final class EvidenceQuery(
  goal: Type, context: SynthesisContext, labels: Vector[String], limits: SynthesisLimits, completeBound: Option[Long]
) {
  private val types = new SmtTypes(labels)
  private val maximumSteps = completeBound.fold(limits.maximumSteps)(_.min(limits.maximumSteps).toInt)
  private val root = SmtConstant("root", SmtSort.Int)
  private sealed trait ArgumentValue
  private val argumentSort: SmtSort.Data[ArgumentValue] = SmtSort.Data("Argument")
  private val typeArgument = SmtConstructor("TypeArgument", argumentSort, Vector("type_argument" -> types.sort))
  private val implicitArgument = SmtConstructor(
    "ImplicitArgument", argumentSort, Vector("implicit_argument" -> SmtSort.Int)
  )
  private val argumentDatatype = SmtDatatype(argumentSort, Vector(typeArgument, implicitArgument))
  private type Argument = SmtConstant[ArgumentValue]
  private final case class Spine(
    head: SmtConstant[BigInt], length: SmtConstant[BigInt], result: SmtConstant[types.TypeValue],
    arguments: Vector[Argument]
  ) {
    def constants: Vector[SmtConstant[?]] = Vector(head, length, result) ++ arguments
    def observations: Vector[SmtObservation] = {
      constants.map(value => SmtObservation(value.name, value)) ++ arguments.map { argument =>
        SmtObservation(
          s"valid_${argument.name}",
          types.validArgument(typeValue(argument), context.typeDepth, limits.maximumTypeDepth)
        )
      }
    }
  }
  private enum CurrentType {
    case Known(tpe: Type, bindings: Vector[types.TypeExpression])
    case Inferred(value: types.TypeExpression)
  }
  import CurrentType.*

  private final case class Formulas(
    constants: Vector[SmtConstant[?]], constraints: Vector[SmtConstraint[ConstraintOrigin]]
  ) {
    def ++(other: Formulas): Formulas = Formulas(constants ++ other.constants, constraints ++ other.constraints)
  }

  private def reference(argument: Argument): SmtExpr[BigInt] = implicitArgument.field(0, SmtSort.Int)(argument)
  private def typeValue(argument: Argument): types.TypeExpression = typeArgument.field(0, types.sort)(argument)
  private def inScope(value: SmtExpr[BigInt], size: Int): SmtExpr[Boolean] = (int(0) <= value) && (value < int(size))

  private def lookup(reference: SmtExpr[BigInt], previous: Vector[Spine]): types.TypeExpression = {
    require(previous.nonEmpty)
    previous.zipWithIndex.tail.foldLeft[types.TypeExpression](previous.head.result) {
      case (otherwise, (spine, index)) => choose(reference === int(index), spine.result, otherwise)
    }
  }
  private def encoded(current: CurrentType): types.TypeExpression = current match {
    case Known(tpe, bindings) => types.encode(tpe, bindings, context.typeDepth)
    case Inferred(value) => value
  }
  private def normalized(current: CurrentType): CurrentType = current match {
    case Known(Type.Variable(index), bindings) if index < bindings.size => Inferred(bindings(index))
    case _ => current
  }

  private def extend(previous: Vector[Spine]): (Spine, Formulas) = {
    val index = previous.size
    val spine = Spine(
      SmtConstant(s"head_$index", SmtSort.Int), SmtConstant(s"length_$index", SmtSort.Int),
      SmtConstant(s"result_$index", types.sort),
      Vector.tabulate(maximumSteps - 1)(step => SmtConstant(s"argument_${index}_$step", argumentSort))
    )
    val active = int(index) <= root
    def rule(kind: ConstraintRule, explanation: String, formula: SmtExpr[Boolean]): SmtConstraint[ConstraintOrigin] = {
      constraint(kind, explanation, formula, Some(index))
    }
    val domains = rule(
      ConstraintRule.Scope, "An active spine chooses an instance head and a bounded argument count.",
      active.implies(inScope(spine.head, context.instances.size) && inScope(spine.length, maximumSteps))
    )
    def valueArgument(argument: Argument, expected: types.TypeExpression): SmtExpr[Boolean] = {
      if previous.isEmpty then {
        bool(false)
      } else {
        val ref = reference(argument)
        all(Vector(implicitArgument.is(argument), inScope(ref, previous.size), lookup(ref, previous) === expected))
      }
    }
    val rules = context.instances.zipWithIndex.foldLeft(Formulas(Vector.empty, Vector.empty)) {
      case (formulas, (tpe, head)) => {
        val chosen = active && (spine.head === int(head))
        def continue(current: CurrentType, step: Int): Formulas = {
          // dⱼ:Aⱼ ∈ Δ / Δ ⊢ dⱼ:Aⱼ                                        P-Instance
          val stop = rule(
            ConstraintRule.ResultType, "Stopping at this position fixes the spine's result type.",
            (chosen && (spine.length === int(step))).implies(spine.result === encoded(current))
          )
          val next = if step == spine.arguments.size then {
            Formulas(Vector.empty, Vector.empty)
          } else {
            val argument = spine.arguments(step)
            val applied = chosen && (spine.length > int(step))
            normalized(current) match {
              // Δ ⊢ p:∀α.A    ⊢ B type / Δ ⊢ p[B]:A[B/α]                         P-Type-App
              case Known(Type.Forall(body), bindings) => {
                val premise = rule(
                  ConstraintRule.TypeApplication, "A universal type is eliminated by a type argument.",
                  applied.implies(typeArgument.is(argument))
                )
                val remainder = continue(Known(body, typeValue(argument) +: bindings), step + 1)
                Formulas(Vector.empty, Vector(premise)) ++ remainder
              }
              // Δ ⊢ p:A⇒B    Δ ⊢ q:A / Δ ⊢ p⟨q⟩:B                              P-Implicit-App
              case Known(Type.Implicit(parameter, result), bindings) => {
                val premise = rule(
                  ConstraintRule.ImplicitApplication,
                  "An implicit parameter is supplied by an earlier spine of the required type.",
                  applied.implies(valueArgument(argument, encoded(Known(parameter, bindings))))
                )
                val remainder = if previous.isEmpty then Formulas(Vector.empty, Vector.empty)
                  else continue(Known(result, bindings), step + 1)
                Formulas(Vector.empty, Vector(premise)) ++ remainder
              }
              case Known(_, _) => Formulas(Vector.empty, Vector(rule(
                ConstraintRule.Stop, "This type has no admissible evidence elimination.", !applied
              )))
              case Inferred(value) => {
                val result = SmtConstant(s"inferred_${index}_${head}_$step", types.sort)
                val application = if previous.isEmpty then {
                  bool(false)
                } else {
                  val ref = reference(argument)
                  all(Vector(
                    implicitArgument.is(argument), inScope(ref, previous.size),
                    value === types.implicitFunction(lookup(ref, previous), result)
                  ))
                }
                val instantiation = all(Vector(
                  typeArgument.is(argument), types.polymorphic(value),
                  result === types.instantiate(value, typeValue(argument))
                ))
                val premise = rule(
                  ConstraintRule.Elimination, "An inferred head type must admit a type or implicit application.",
                  applied.implies(instantiation || application)
                )
                Formulas(Vector(result), Vector(premise)) ++ continue(Inferred(result), step + 1)
              }
            }
          }
          Formulas(Vector.empty, Vector(stop)) ++ next
        }
        formulas ++ continue(Known(tpe, Vector.empty), 0)
      }
    }
    (spine, Formulas(spine.constants, Vector(domains)) ++ rules)
  }

  def start(): SmtSearch = append(
    Vector.empty, Formulas(Vector(root), Vector(constraint(
      ConstraintRule.Scope, "The root selects one of the bounded spines.", inScope(root, maximumSteps)
    ))), Vector.empty
  )

  private def append(
    previous: Vector[Spine], formulas: Formulas, attempts: Vector[SynthesisAttempt]
  ): SmtSearch = {
    val (spine, rules) = extend(previous)
    val spines = previous :+ spine
    val selected = root === int(previous.size)
    val cost = sum(spines.map(value => value.length + int(1)))
    val target = constraint(
      ConstraintRule.Goal, "The root has the requested type; total construction cost stays within the bound.",
      selected.implies((cost <= int(maximumSteps)) && (spine.result === types.encode(goal))), Some(previous.size)
    )
    check(spines, formulas ++ rules ++ Formulas(Vector.empty, Vector(target)), attempts)
  }

  private def check(
    spines: Vector[Spine], formulas: Formulas, attempts: Vector[SynthesisAttempt]
  ): SmtSearch = {
    def finish(result: Result[ImplicitTerm, Error], trace: Vector[SynthesisAttempt]): SmtSearch = {
      SmtSearch.Finished(SynthesisReport(result, trace))
    }
    val rootConstraint = constraint(
      ConstraintRule.Search, "This check searches a program with this many spines.", root === int(spines.size - 1)
    )
    val problem = SmtProblem(
      types.datatypes :+ argumentDatatype, types.definitions, formulas.constants,
      formulas.constraints :+ rootConstraint, spines.flatMap(_.observations)
    )
    SmtSearch.Query(problem, spines.size, attempts, (timeout, response) => {
      require(timeout > 0)
      val refinements = response match {
        case Result.Ok(SmtResponse(SmtAnswer.Sat(model), _, _)) => argumentObligations(model, spines)
        case _ => Vector.empty
      }
      val trace = attempts :+ SynthesisAttempt(spines.size, problem, labels, timeout, response, refinements)
      response match {
        case Result.Err(failure) => finish(Result.Err(Error.SynthesisBackend(failure.cause)), trace)
        case Result.Ok(reply) => reply.answer match {
          case SmtAnswer.Sat(model) if refinements.nonEmpty => {
            check(spines, formulas ++ Formulas(Vector.empty, refinements), trace)
          }
          case SmtAnswer.Sat(model) => {
            val program = decode(model, spines)
            SmtSearch.Finished(SynthesisReport(Result.Ok(program.term()), trace, Some(program)))
          }
          case SmtAnswer.Unknown(reason) => finish(Result.Err(Error.SynthesisUnknown(goal, reason)), trace)
          case SmtAnswer.Unsat if spines.size < maximumSteps => append(spines, formulas, trace)
          case SmtAnswer.Unsat if completeBound.exists(_ <= maximumSteps) => {
            finish(Result.Err(Error.MissingInstance(goal)), trace)
          }
          case SmtAnswer.Unsat => {
            finish(Result.Err(Error.SynthesisLimit(goal, limits.maximumSteps, limits.maximumTypeDepth)), trace)
          }
        }
      }
    })
  }

  /**
   * A model remains provisional until every used type argument satisfies scope and height.
   * A violation adds the full guarded premise for that position, excluding no valid proof.
   */
  private def argumentObligations(model: SmtModel, spines: Vector[Spine]): Vector[SmtConstraint[ConstraintOrigin]] = {
    spines.zipWithIndex.flatMap { case (spine, index) =>
      spine.arguments.take(integer(model(spine.length.name))).zipWithIndex.flatMap { case (argument, step) =>
        model(argument.name) match {
          case SmtValue.Constructor("TypeArgument", _) => model(s"valid_${argument.name}") match {
            case SmtValue.Boolean(true) => Vector.empty
            case SmtValue.Boolean(false) => Vector(constraint(
              ConstraintRule.TypeValidity,
              "Every used type argument is well scoped, canonical, and within the height bound.",
              all(Vector(int(index) <= root, spine.length > int(step), typeArgument.is(argument))).implies(
                types.validArgument(typeValue(argument), context.typeDepth, limits.maximumTypeDepth)
              ), Some(index)
            ))
            case _ => throw new IllegalStateException("Solver did not evaluate a ground type-validity predicate")
          }
          case _ => Vector.empty
        }
      }
    }
  }

  private def integer(value: SmtValue): Int = value match {
    case SmtValue.Integer(number) if number.isValidInt => number.toInt
    case _ => throw new IllegalStateException("Solver returned a non-numeral evidence reference")
  }

  private def decode(model: SmtModel, spines: Vector[Spine]): EvidenceProgram = {
    EvidenceProgram(spines.map { spine =>
      val arguments = spine.arguments.take(integer(model(spine.length.name))).map { argument =>
        model(argument.name) match {
          case SmtValue.Constructor("TypeArgument", Vector(value)) => EvidenceArgument.TypeArgument(types.decode(value))
          case SmtValue.Constructor("ImplicitArgument", Vector(value)) => {
            EvidenceArgument.ImplicitArgument(integer(value))
          }
          case _ => throw new IllegalStateException("Solver returned a value outside the argument datatype")
        }
      }
      EvidenceSpine(integer(model(spine.head.name)), arguments, types.decode(model(spine.result.name)))
    })
  }
}
