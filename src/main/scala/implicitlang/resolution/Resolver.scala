package implicitlang.resolution

import implicitlang.`implicit`.{ImplicitTerm, Elaborated, DisplayNames, TermChild, TermLocation}
import implicitlang.core.{Type, Term, TypeChecker}
import implicitlang.diagnostic.Error
import implicitlang.synthesis.SynthesisContext
import implicitlang.util.Result

/** Fill goals and merge the two lexical variable spaces into the ordinary target environment. */
final class Resolver {
  private final case class Instance(tpe: Type, term: Term)

  private final case class Context(
    ordinary: List[Instance] = Nil,
    instances: List[Instance] = Nil,
    targetTypes: List[Type] = Nil,
    typeDepth: Int = 0,
    names: DisplayNames = DisplayNames()
  ) {
    private def weakenTerms: Context = copy(
      ordinary = ordinary.map(value => value.copy(term = value.term.shiftTerms(1))),
      instances = instances.map(value => value.copy(term = value.term.shiftTerms(1)))
    )

    def bindOrdinary(tpe: Type, hint: Option[String]): Context = {
      val shifted = weakenTerms
      shifted.copy(
        ordinary = Instance(tpe, Term.Var(0)) :: shifted.ordinary, targetTypes = tpe.erased :: targetTypes,
        names = names.bindOrdinary(hint)
      )
    }

    def bindInstance(tpe: Type, hint: Option[String]): Context = {
      val shifted = weakenTerms
      shifted.copy(
        instances = Instance(tpe, Term.Var(0)) :: shifted.instances, targetTypes = tpe.erased :: targetTypes,
        names = names.bindInstance(hint)
      )
    }

    def bindType(hint: Option[String]): Context = {
      def shifted(value: Instance): Instance = Instance(value.tpe.shift(1), value.term.shiftTypes(1))
      copy(
        ordinary = ordinary.map(shifted),
        instances = instances.map(shifted),
        targetTypes = targetTypes.map(_.shift(1)),
        typeDepth = typeDepth + 1,
        names = names.bindType(hint)
      )
    }
  }

  def resolve(checked: Elaborated): Resolution[Term] = {
    for {
      term <- resolve(checked.expression, Context())
      _ <- Resolution.fromResult(TypeChecker.check(term, checked.tpe.erased))
    } yield term
  }

  private def lookup(values: List[Instance], index: Int, space: String): Result[Term, Error] = {
    values.lift(index) match {
      case Some(value) => Result.Ok(value.term)
      case None => Result.Err(Error.InvalidTermIndex(space, index))
    }
  }

  private def resolve(
    expression: ImplicitTerm, context: Context, location: TermLocation = TermLocation()
  ): Resolution[Term] = expression match {
    case ImplicitTerm.Var(index) => Resolution.fromResult(lookup(context.ordinary, index, "Γ"))
    case ImplicitTerm.Instance(index) => Resolution.fromResult(lookup(context.instances, index, "Δ"))
    case ImplicitTerm.Lam(annotation, body, hint) => {
      resolve(body, context.bindOrdinary(annotation, hint), location / TermChild.Body)
        .map(Term.Lam(annotation.erased, _))
    }
    /*
     * Γ, x:A; Δ ⊢ c ⟹ t
     * ─────────────────────────── R-Fix
     * Γ; Δ ⊢ fix x:A.c ⟹ fix x:|A|.t
     */
    case ImplicitTerm.Fix(annotation, body, hint) => {
      resolve(body, context.bindOrdinary(annotation, hint), location / TermChild.Body)
        .map(Term.Fix(annotation.erased, _))
    }
    case ImplicitTerm.ImplicitLam(annotation, body, hint) => {
      resolve(body, context.bindInstance(annotation, hint), location / TermChild.Body)
        .map(Term.Lam(annotation.erased, _))
    }
    case ImplicitTerm.App(function, argument) => {
      for {
        resolvedFunction <- resolve(function, context, location / TermChild.Function)
        resolvedArgument <- resolve(argument, context, location / TermChild.Argument)
      } yield Term.App(resolvedFunction, resolvedArgument)
    }
    case ImplicitTerm.ImplicitApp(function, argument) => {
      for {
        resolvedFunction <- resolve(function, context, location / TermChild.Function)
        resolvedArgument <- resolve(argument, context, location / TermChild.Argument)
      } yield Term.App(resolvedFunction, resolvedArgument)
    }
    case ImplicitTerm.TypeLam(body, hint) => {
      resolve(body, context.bindType(hint), location / TermChild.Body).map(Term.TypeLam.apply)
    }
    case ImplicitTerm.TypeApp(function, argument) => {
      resolve(function, context, location / TermChild.Function).map(Term.TypeApp(_, argument.erased))
    }
    case ImplicitTerm.Let(annotation, value, body, hint) => {
      for {
        resolvedValue <- resolve(value, context, location / TermChild.Value)
        resolvedBody <- resolve(body, context.bindOrdinary(annotation, hint), location / TermChild.Body)
      } yield Term.Let(resolvedValue, resolvedBody)
    }
    case ImplicitTerm.InstanceLet(annotation, value, body, hint) => {
      for {
        resolvedValue <- resolve(value, context, location / TermChild.Value)
        resolvedBody <- resolve(body, context.bindInstance(annotation, hint), location / TermChild.Body)
      } yield Term.Let(resolvedValue, resolvedBody)
    }
    case ImplicitTerm.Need(goal, _) => {
      val request = SynthesisGoal(
        goal, SynthesisContext(context.instances.map(_.tpe), context.typeDepth),
        context.ordinary.map(_.tpe), context.names, location
      )
      Resolution.Request(request, report => {
        val checked = for {
          construction <- report.result
          evidence <- checkEvidence(construction, context)
          _ <- if evidence.tpe == goal then Result.Ok(()) else Result.Err(Error.InvalidEvidence(goal, evidence.tpe))
          _ <- TypeChecker.check(evidence.term, goal.erased, context.targetTypes, context.typeDepth)
        } yield evidence.term
        val resolved = ResolvedGoal(request, report, checked.map(_ => ()))
        Resolution.Complete(ResolutionReport(checked, Vector(resolved)))
      })
    }
    case ImplicitTerm.IntLit(value) => Resolution.fromResult(Result.Ok(Term.IntLit(value)))
    case ImplicitTerm.StringLit(value) => Resolution.fromResult(Result.Ok(Term.StringLit(value)))
    case ImplicitTerm.BoolLit(value) => Resolution.fromResult(Result.Ok(Term.BoolLit(value)))
    case ImplicitTerm.Record(fields) => {
      Resolution.traverse(fields) { (label, value) =>
        resolve(value, context, location / TermChild.Field(label))
      }.map(Term.Record.apply)
    }
    case ImplicitTerm.Proj(record, label) => {
      resolve(record, context, location / TermChild.Receiver).map(Term.Proj(_, label))
    }
    case ImplicitTerm.BinOp(kind, left, right) => {
      for {
        first <- resolve(left, context, location / TermChild.Left)
        second <- resolve(right, context, location / TermChild.Right)
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
      // Γ; Δ ⊢ev p : A ⇒ B    Γ; Δ ⊢ev q : A  /  Γ; Δ ⊢ev p⟨q⟩ : B   (P-Implicit-App)
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
