package implicitlang.resolution

import implicitlang.`implicit`.{ImplicitTerm, Elaborated}
import implicitlang.core.{Type, Term, TypeChecker}
import implicitlang.diagnostic.Error
import implicitlang.synthesis.{Synthesizer, SynthesisContext}
import implicitlang.util.Result

/** Fill goals and merge the two lexical variable spaces into the ordinary target environment. */
final class Resolver(synthesizer: Synthesizer) {
  private final case class Instance(tpe: Type, term: Term)

  private final case class Context(
    ordinary: List[Instance] = Nil,
    instances: List[Instance] = Nil,
    targetTypes: List[Type] = Nil,
    typeDepth: Int = 0
  ) {
    private def weakenTerms: Context = copy(
      ordinary = ordinary.map(value => value.copy(term = value.term.shiftTerms(1))),
      instances = instances.map(value => value.copy(term = value.term.shiftTerms(1)))
    )

    def bindOrdinary(tpe: Type): Context = {
      val shifted = weakenTerms
      shifted.copy(ordinary = Instance(tpe, Term.Var(0)) :: shifted.ordinary, targetTypes = tpe.erased :: targetTypes)
    }

    def bindInstance(tpe: Type): Context = {
      val shifted = weakenTerms
      shifted.copy(instances = Instance(tpe, Term.Var(0)) :: shifted.instances, targetTypes = tpe.erased :: targetTypes)
    }

    def bindType: Context = {
      def shifted(value: Instance): Instance = Instance(value.tpe.shift(1), value.term.shiftTypes(1))
      copy(
        ordinary = ordinary.map(shifted),
        instances = instances.map(shifted),
        targetTypes = targetTypes.map(_.shift(1)),
        typeDepth = typeDepth + 1
      )
    }
  }

  def resolve(checked: Elaborated): Result[Term, Error] = {
    for {
      term <- resolve(checked.expression, Context())
      _ <- TypeChecker.check(term, checked.tpe.erased)
    } yield term
  }

  private def lookup(values: List[Instance], index: Int, space: String): Result[Term, Error] = {
    values.lift(index) match {
      case Some(value) => Result.Ok(value.term)
      case None => Result.Err(Error.InvalidTermIndex(space, index))
    }
  }

  private def resolve(expression: ImplicitTerm, context: Context): Result[Term, Error] = expression match {
    case ImplicitTerm.Var(index) => lookup(context.ordinary, index, "Γ")
    case ImplicitTerm.Instance(index) => lookup(context.instances, index, "Δ")
    case ImplicitTerm.Lam(annotation, body) => {
      resolve(body, context.bindOrdinary(annotation)).map(Term.Lam(annotation.erased, _))
    }
    /*
     * Γ, x:A; Δ ⊢ c ⟹ t
     * ─────────────────────────── R-Fix
     * Γ; Δ ⊢ fix x:A.c ⟹ fix x:|A|.t
     */
    case ImplicitTerm.Fix(annotation, body) => {
      resolve(body, context.bindOrdinary(annotation)).map(Term.Fix(annotation.erased, _))
    }
    case ImplicitTerm.ImplicitLam(annotation, body) => {
      resolve(body, context.bindInstance(annotation)).map(Term.Lam(annotation.erased, _))
    }
    case ImplicitTerm.App(function, argument) => {
      for {
        resolvedFunction <- resolve(function, context)
        resolvedArgument <- resolve(argument, context)
      } yield Term.App(resolvedFunction, resolvedArgument)
    }
    case ImplicitTerm.ImplicitApp(function, argument) => {
      for {
        resolvedFunction <- resolve(function, context)
        resolvedArgument <- resolve(argument, context)
      } yield Term.App(resolvedFunction, resolvedArgument)
    }
    case ImplicitTerm.TypeLam(body) => resolve(body, context.bindType).map(Term.TypeLam.apply)
    case ImplicitTerm.TypeApp(function, argument) => resolve(function, context).map(Term.TypeApp(_, argument.erased))
    case ImplicitTerm.Let(annotation, value, body) => {
      for {
        resolvedValue <- resolve(value, context)
        resolvedBody <- resolve(body, context.bindOrdinary(annotation))
      } yield Term.Let(resolvedValue, resolvedBody)
    }
    case ImplicitTerm.Impl(annotation, value, body) => {
      for {
        resolvedValue <- resolve(value, context)
        resolvedBody <- resolve(body, context.bindInstance(annotation))
      } yield Term.Let(resolvedValue, resolvedBody)
    }
    case ImplicitTerm.Need(goal) => {
      for {
        construction <- synthesizer.synthesize(goal, SynthesisContext(context.instances.map(_.tpe), context.typeDepth))
        evidence <- checkEvidence(construction, context)
        _ <- if evidence.tpe == goal then Result.Ok(()) else Result.Err(Error.InvalidEvidence(goal, evidence.tpe))
        _ <- TypeChecker.check(evidence.term, goal.erased, context.targetTypes, context.typeDepth)
      } yield evidence.term
    }
    case ImplicitTerm.IntLit(value) => Result.Ok(Term.IntLit(value))
    case ImplicitTerm.StringLit(value) => Result.Ok(Term.StringLit(value))
    case ImplicitTerm.BoolLit(value) => Result.Ok(Term.BoolLit(value))
    case ImplicitTerm.Record(fields) => {
      Result.traverse(fields)(resolve(_, context)).map(Term.Record.apply)
    }
    case ImplicitTerm.Proj(record, label) => resolve(record, context).map(Term.Proj(_, label))
    case ImplicitTerm.BinOp(kind, left, right) => {
      for {
        first <- resolve(left, context)
        second <- resolve(right, context)
      } yield Term.BinOp(kind, first, second)
    }
  }

  private def checkEvidence(expression: ImplicitTerm, context: Context): Result[Instance, Error] = expression match {
    case ImplicitTerm.Instance(index) => context.instances.lift(index) match {
      case Some(instance) => Result.Ok(instance)
      case None => Result.Err(Error.InvalidTermIndex("Δ", index))
    }
    case ImplicitTerm.TypeApp(function, argument) => {
      for {
        _ <- argument.validate(context.typeDepth)
        producer <- checkEvidence(function, context)
        result <- producer.tpe match {
          case Type.Forall(body) => {
            Result.Ok(Instance(body.instantiate(argument), Term.TypeApp(producer.term, argument.erased)))
          }
          case other => Result.Err(Error.ExpectedPolymorphic(other))
        }
      } yield result
    }
    case ImplicitTerm.ImplicitApp(function, argument) => {
      // Γ; Δ ⊢ev p : A ⇒ B    Γ; Δ ⊢ev q : A  /  Γ; Δ ⊢ev p{q} : B   (P-Implicit-App)
      for {
        producer <- checkEvidence(function, context)
        actual <- checkEvidence(argument, context)
        signature <- producer.tpe match {
          case Type.Implicit(parameter, result) => Result.Ok(parameter -> result)
          case other => Result.Err(Error.ExpectedImplicitFunction(other))
        }
        _ <- if signature._1 == actual.tpe then Result.Ok(())
          else Result.Err(Error.ExpectedType(signature._1, actual.tpe))
      } yield Instance(signature._2, Term.App(producer.term, actual.term))
    }
    case other => Result.Err(Error.InvalidEvidenceForm(other))
  }
}
