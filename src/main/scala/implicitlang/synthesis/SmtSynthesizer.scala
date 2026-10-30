package implicitlang.synthesis

import com.microsoft.z3.{BoolExpr, Constructor, Context, DatatypeSort, Expr, IntNum, IntSort, Model, Sort, Status}
import com.microsoft.z3.Z3Exception
import implicitlang.Error
import implicitlang.core.{ImplicitTerm, Type}
import implicitlang.util.Result

/** Bounds on the total size of a spine program and the height of its inferred type arguments. */
final case class SynthesisLimits(maximumSteps: Int = 16, maximumTypeDepth: Int = 8, timeoutMillis: Int = 10000) {
  require(maximumSteps > 0, "Synthesis needs at least one construction step")
  require(maximumTypeDepth > 0, "Type arguments need positive depth")
  require(timeoutMillis > 0, "Synthesis needs a positive timeout")
}

/**
 * Bounded inhabitation of p ::= d | p[A] | p p | p{p}, using the official Z3 Java API.
 * A nearest exact Δ reference wins; otherwise any checked construction within the bounds may win.
 * Limits and solver unknown do not prove uninhabitability. Every request owns and closes its native
 * context; returned values contain only the calculus's immutable representation.
 */
final class SmtSynthesizer(val limits: SynthesisLimits = SynthesisLimits()) extends Synthesizer {
  def synthesize(goal: Type, context: SynthesisContext): Result[ImplicitTerm, Error] = {
    for {
      _ <- if context.typeDepth >= 0 then Result.Ok(()) else Result.Err(Error.InvalidTypeDepth(context.typeDepth))
      _ <- goal.validate(context.typeDepth)
      _ <- Result.traverse(context.instances.toVector)(_.validate(context.typeDepth))
      result <- search(goal, context)
    } yield result
  }

  private def search(goal: Type, context: SynthesisContext): Result[ImplicitTerm, Error] = {
    val exact = context.instances.indexOf(goal)
    if exact >= 0 then Result.Ok(ImplicitTerm.Instance(exact))
    else if context.instances.isEmpty then Result.Err(Error.MissingInstance(goal))
    else {
      val inputTypes = (goal +: context.instances.toVector).flatMap(subtypes)
      val labels = inputTypes.collect { case Type.Record(fields) => fields.map(_._1) }.flatten.distinct.sorted
      // In the ∀-free fragment, each proof type is a subtype of Δ. Choose a least-height
      // proof for each inhabited type: its dependencies have smaller heights. There are at
      // most k such proofs, each with a head spine of at most k nodes, hence cost at most k².
      val completeBound = if inputTypes.exists { case Type.Forall(_) => true; case _ => false } then None
        else {
          val typeCount = inputTypes.distinct.size.toLong
          Some(typeCount * typeCount)
        }
      try {
        val native = new Context()
        try new EvidenceQuery(native, goal, context, labels, limits, completeBound).solve()
        finally native.close()
      } catch {
        case error: Z3Exception => Result.Err(Error.SynthesisBackend(error))
        case error: LinkageError => Result.Err(Error.SynthesisBackend(error))
      }
    }
  }

  private def subtypes(tpe: Type): Vector[Type] = tpe +: (tpe match {
    case Type.Arrow(parameter, result) => subtypes(parameter) ++ subtypes(result)
    case Type.Implicit(parameter, result) => subtypes(parameter) ++ subtypes(result)
    case Type.Forall(body) => subtypes(body)
    case Type.Record(fields) => fields.flatMap(field => subtypes(field._2))
    case _ => Vector.empty
  })
}

/**
 * A spine starts at a Δ reference and eliminates its type from left to right. Its value arguments
 * refer only to earlier spines. Flattening the function side of any admissible evidence gives this
 * representation; sharing finished arguments may reduce its cost. Known type structure is interpreted
 * directly, and an inferred type at the head uses the same rules symbolically. This partial evaluation
 * changes the formula, not the admissible constructions or the type-argument grammar.
 */
private final class EvidenceQuery(
  native: Context,
  goal: Type,
  context: SynthesisContext,
  labels: Vector[String],
  limits: SynthesisLimits,
  completeBound: Option[Long]
) {
  private val types = new SmtTypes(native, labels)
  private val solver = native.mkSolver()
  private val deadline = System.nanoTime() + limits.timeoutMillis.toLong * 1000000
  private val maximumSteps = completeBound.fold(limits.maximumSteps)(_.min(limits.maximumSteps).toInt)
  private val root = native.mkIntConst("root")

  private def constructor(name: String, field: String, sort: Sort): Constructor[Object] = {
    native.mkConstructor[Object](name, s"is_$name", Array(field), Array(sort), Array(0))
  }
  private val typeArgument = constructor("TypeArgument", "type_argument", types.sort)
  private val explicitArgument = constructor("ExplicitArgument", "explicit_argument", native.getIntSort)
  private val implicitArgument = constructor("ImplicitArgument", "implicit_argument", native.getIntSort)
  private val argumentSort = native.mkDatatypeSort("Argument", Array(typeArgument, explicitArgument, implicitArgument))
  private type Argument = Expr[DatatypeSort[Object]]
  private final case class Spine(
    head: Expr[IntSort], length: Expr[IntSort], result: types.TypeExpression, arguments: Vector[Argument]
  )
  private sealed trait CurrentType
  private final case class Known(tpe: Type, bindings: Vector[types.TypeExpression]) extends CurrentType
  private final case class Inferred(value: types.TypeExpression) extends CurrentType

  private def is(constructor: Constructor[Object], argument: Argument): BoolExpr = {
    constructor.getTesterDecl.apply(argument).asInstanceOf[BoolExpr]
  }
  private def reference(constructor: Constructor[Object], argument: Argument): Expr[IntSort] = {
    constructor.getAccessorDecls()(0).apply(argument).asInstanceOf[Expr[IntSort]]
  }
  private def typeValue(argument: Argument): types.TypeExpression = {
    typeArgument.getAccessorDecls()(0).apply(argument).asInstanceOf[types.TypeExpression]
  }
  private def inScope(reference: Expr[IntSort], size: Int): BoolExpr = {
    native.mkAnd(native.mkLe(native.mkInt(0), reference), native.mkLt(reference, native.mkInt(size)))
  }
  private def lookup(reference: Expr[IntSort], previous: Vector[Spine]): types.TypeExpression = {
    require(previous.nonEmpty)
    previous.zipWithIndex.tail.foldLeft(previous.head.result) { case (otherwise, (spine, index)) =>
      native.mkITE(native.mkEq(reference, native.mkInt(index)), spine.result, otherwise)
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

  private def extend(previous: Vector[Spine]): Spine = {
    val index = previous.size
    val spine = Spine(
      native.mkIntConst(s"head_$index"), native.mkIntConst(s"length_$index"),
      native.mkConst(s"result_$index", types.sort),
      Vector.tabulate(maximumSteps - 1)(step => native.mkConst(s"argument_${index}_$step", argumentSort))
    )
    val active = native.mkLe(native.mkInt(index), root)
    solver.add(native.mkImplies(active, native.mkAnd(
      inScope(spine.head, context.instances.size),
      inScope(spine.length, maximumSteps)
    )))

    def valueArgument(
      constructor: Constructor[Object], argument: Argument, expected: types.TypeExpression
    ): BoolExpr = {
      if previous.isEmpty then native.mkFalse()
      else {
        val ref = reference(constructor, argument)
        native.mkAnd(
          is(constructor, argument), inScope(ref, previous.size), native.mkEq(lookup(ref, previous), expected)
        )
      }
    }
    context.instances.zipWithIndex.foreach { case (tpe, head) =>
      // dⱼ:Aⱼ ∈ Δ  /  Δ ⊢ dⱼ : Aⱼ                                      (P-Instance)
      val chosen = native.mkAnd(active, native.mkEq(spine.head, native.mkInt(head)))
      def continue(current: CurrentType, step: Int): Unit = {
        val value = encoded(current)
        solver.add(native.mkImplies(
          native.mkAnd(chosen, native.mkEq(spine.length, native.mkInt(step))),
          native.mkEq(spine.result, value)
        ))
        if step < spine.arguments.size then {
          val argument = spine.arguments(step)
          val applied = native.mkAnd(chosen, native.mkGt(spine.length, native.mkInt(step)))
          normalized(current) match {
            // Δ ⊢ p : ∀α.A    ⊢ B type  /  Δ ⊢ p[B] : A[B/α]            (P-Type-App)
            case Known(Type.Forall(body), bindings) => {
              solver.add(native.mkImplies(applied, is(typeArgument, argument)))
              continue(Known(body, typeValue(argument) +: bindings), step + 1)
            }
            // Δ ⊢ p : A → B    Δ ⊢ q : A  /  Δ ⊢ p q : B               (P-App)
            case Known(Type.Arrow(parameter, result), bindings) => {
              solver.add(native.mkImplies(
                applied, valueArgument(explicitArgument, argument, encoded(Known(parameter, bindings)))
              ))
              if previous.nonEmpty then continue(Known(result, bindings), step + 1)
            }
            // Δ ⊢ p : A ⇒ B    Δ ⊢ q : A  /  Δ ⊢ p{q} : B               (P-Implicit-App)
            case Known(Type.Implicit(parameter, result), bindings) => {
              solver.add(native.mkImplies(
                applied, valueArgument(implicitArgument, argument, encoded(Known(parameter, bindings)))
              ))
              if previous.nonEmpty then continue(Known(result, bindings), step + 1)
            }
            case Known(_, _) => solver.add(native.mkNot(applied))
            case Inferred(value) => {
              val result = native.mkConst(s"inferred_${index}_${head}_$step", types.sort)
              def application(constructor: Constructor[Object]): BoolExpr = {
                if previous.isEmpty then native.mkFalse()
                else {
                  val ref = reference(constructor, argument)
                  val parameter = lookup(ref, previous)
                  val function = if constructor == implicitArgument then types.implicitFunction(parameter, result)
                    else types.explicit(parameter, result)
                  native.mkAnd(is(constructor, argument), inScope(ref, previous.size), native.mkEq(value, function))
                }
              }
              solver.add(native.mkImplies(applied, native.mkOr(
                native.mkAnd(
                  is(typeArgument, argument), types.polymorphic(value),
                  native.mkEq(result, types.instantiate(value, typeValue(argument)))
                ),
                application(explicitArgument), application(implicitArgument)
              )))
              continue(Inferred(result), step + 1)
            }
          }
        }
      }
      continue(Known(tpe, Vector.empty), 0)
    }
    spine
  }

  def solve(): Result[ImplicitTerm, Error] = {
    solver.add(inScope(root, maximumSteps))
    def append(previous: Vector[Spine]): Result[ImplicitTerm, Error] = {
      val spine = extend(previous)
      val spines = previous :+ spine
      val selected = native.mkEq(root, native.mkInt(previous.size))
      val cost = native.mkAdd(spines.map(spine => native.mkAdd(spine.length, native.mkInt(1)))*)
      solver.add(native.mkImplies(selected, native.mkAnd(
        native.mkLe(cost, native.mkInt(maximumSteps)),
        native.mkEq(spine.result, types.encode(goal))
      )))
      check(spines)
    }
    def check(spines: Vector[Spine]): Result[ImplicitTerm, Error] = {
      val last = spines.size - 1
      val remaining = (deadline - System.nanoTime()) / 1000000
      if remaining <= 0 then Result.Err(Error.SynthesisUnknown(goal, "timeout"))
      else {
        val parameters = native.mkParams()
        parameters.add("timeout", remaining.toInt)
        solver.setParameters(parameters)
        solver.check(native.mkEq(root, native.mkInt(last))) match {
          case Status.SATISFIABLE => {
            val model = solver.getModel
            val obligations = argumentObligations(model, spines)
            if obligations.isEmpty then Result.Ok(decode(model, spines, last))
            else {
              solver.add(obligations*)
              check(spines)
            }
          }
          case Status.UNKNOWN => Result.Err(Error.SynthesisUnknown(goal, solver.getReasonUnknown))
          case Status.UNSATISFIABLE if spines.size < maximumSteps => append(spines)
          case Status.UNSATISFIABLE if completeBound.exists(_ <= maximumSteps) => {
            Result.Err(Error.MissingInstance(goal))
          }
          case Status.UNSATISFIABLE => {
            Result.Err(Error.SynthesisLimit(goal, limits.maximumSteps, limits.maximumTypeDepth))
          }
        }
      }
    }
    append(Vector.empty)
  }

  /**
   * A model is provisional until every used type argument satisfies P-Type-App's well-formedness
   * premise and the height bound. Each violation adds the full guarded constraint for that position,
   * so refinement excludes no valid proof and can revisit a position at most once. This avoids
   * unfolding recursive validity predicates for arguments that the proof never uses.
   */
  private def argumentObligations(model: Model, spines: Vector[Spine]): Vector[BoolExpr] = {
    spines.zipWithIndex.flatMap { case (spine, index) =>
      val length = integer(model.eval(spine.length, true))
      spine.arguments.take(length).zipWithIndex.flatMap { case (argument, step) =>
        val value = model.eval(argument, true)
        if value.getFuncDecl != typeArgument.ConstructorDecl() then Vector.empty
        else {
          val valid = types.validArgument(
            value.getArgs()(0).asInstanceOf[types.TypeExpression], context.typeDepth, limits.maximumTypeDepth
          )
          val checked = model.eval(valid, true)
          if checked.isTrue then Vector.empty
          else if checked.isFalse then Vector(native.mkImplies(
            native.mkAnd(
              native.mkLe(native.mkInt(index), root),
              native.mkGt(spine.length, native.mkInt(step)), is(typeArgument, argument)
            ),
            types.validArgument(typeValue(argument), context.typeDepth, limits.maximumTypeDepth)
          ))
          else throw new IllegalStateException("Z3 did not evaluate a ground type-validity predicate")
        }
      }
    }
  }

  private def integer(value: Expr[?]): Int = value match {
    case number: IntNum => number.getInt
    case _ => throw new IllegalStateException("Z3 returned a non-numeral evidence reference")
  }

  private def decode(model: Model, spines: Vector[Spine], index: Int): ImplicitTerm = {
    val spine = spines(index)
    val head = integer(model.eval(spine.head, true))
    val length = integer(model.eval(spine.length, true))
    spine.arguments.take(length).foldLeft[ImplicitTerm](ImplicitTerm.Instance(head)) { (function, argument) =>
      val value = model.eval(argument, true)
      val declaration = value.getFuncDecl
      val operand = value.getArgs()(0)
      if declaration == typeArgument.ConstructorDecl() then ImplicitTerm.TypeApp(function, types.decode(operand))
      else {
        val earlier = integer(operand)
        require(earlier >= 0 && earlier < index, "Z3 evidence is not an acyclic construction")
        val evidence = decode(model, spines, earlier)
        if declaration == explicitArgument.ConstructorDecl() then ImplicitTerm.App(function, evidence)
        else if declaration == implicitArgument.ConstructorDecl() then ImplicitTerm.ImplicitApp(function, evidence)
        else throw new IllegalStateException("Z3 returned a value outside the evidence datatype")
      }
    }
  }
}
