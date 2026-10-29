package implicitlang.elaboration

import implicitlang.{Check, Error}
import implicitlang.syntax.{Expr, TypeExpr}
import implicitlang.core.{Type, ImplicitTerm, Elaborated}

import implicitlang.util.Result

/** Name resolution, alias expansion, checking, and goal insertion. No instance search occurs here. */
final class Elaborator {
  private enum Space {
    case Ordinary, Instance
  }

  private final case class Binding(name: String, tpe: Type, space: Space)
  private final case class Alias(arity: Int, body: Type, declarationDepth: Int)

  private final case class Scope(
    typeNames: List[String] = Nil,
    bindings: List[Binding] = Nil,
    aliases: Map[String, Alias] = Map.empty
  ) {
    def bind(name: String, tpe: Type, space: Space): Scope = {
      copy(bindings = Binding(name, tpe, space) :: bindings)
    }

    def bindType(name: String): Scope = {
      copy(typeNames = name :: typeNames, bindings = bindings.map(binding => binding.copy(tpe = binding.tpe.shift(1))))
    }

    def lookup(name: String): Result[Elaborated, Error] = {
      val position = bindings.indexWhere(_.name == name)
      if position < 0 then {
        Result.Err(Error.UnknownTerm(name))
      } else {
        val binding = bindings(position)
        val index = bindings.take(position).count(_.space == binding.space)
        val expression = binding.space match {
          case Space.Ordinary => ImplicitTerm.Var(index)
          case Space.Instance => ImplicitTerm.Instance(index)
        }
        Result.Ok(Elaborated(expression, binding.tpe))
      }
    }
  }

  def elaborate(expression: Expr): Result[Elaborated, Error] = elaborate(expression, Scope())

  private def resolveType(expression: TypeExpr, scope: Scope): Result[Type, Error] = expression match {
    case TypeExpr.IntType => Result.Ok(Type.IntType)
    case TypeExpr.StringType => Result.Ok(Type.StringType)
    case TypeExpr.BoolType => Result.Ok(Type.BoolType)
    case TypeExpr.Named(name, arguments) => {
      val index = scope.typeNames.indexOf(name)
      if index >= 0 then {
        if arguments.isEmpty then Result.Ok(Type.Variable(index)) else Result.Err(Error.TypeVariableApplied(name))
      } else {
        scope.aliases.get(name) match {
          case None => Result.Err(Error.UnknownType(name))
          case Some(alias) if alias.arity != arguments.size => {
            Result.Err(Error.AliasArity(name, alias.arity, arguments.size))
          }
          case Some(alias) => {
            Result.traverse(arguments)(resolveType(_, scope)).map { actuals =>
              // Parameters occupy the first arity slots; only captured variables are weakened.
              val lifted = alias.body.shift(scope.typeNames.size - alias.declarationDepth, alias.arity)
              val quantified = (0 until alias.arity).foldLeft(lifted)((body, _) => Type.Forall(body))
              actuals.foldLeft(quantified) { (current, argument) =>
                current match {
                  case Type.Forall(body) => body.instantiate(argument)
                  case _ => throw new IllegalStateException("Alias arity invariant violated")
                }
              }
            }
          }
        }
      }
    }
    case TypeExpr.Arrow(parameter, result) => {
      for {
        from <- resolveType(parameter, scope)
        to <- resolveType(result, scope)
      } yield Type.Arrow(from, to)
    }
    case TypeExpr.Implicit(parameter, result) => {
      for {
        from <- resolveType(parameter, scope)
        to <- resolveType(result, scope)
      } yield Type.Implicit(from, to)
    }
    case TypeExpr.Forall(parameter, body) => resolveType(body, scope.bindType(parameter)).map(Type.Forall.apply)
    case TypeExpr.Record(fields) => {
      for {
        _ <- Check.distinct(fields.map(_._1))
        resolved <- Result.traverse(fields.sortBy(_._1)) { case (label, tpe) =>
          resolveType(tpe, scope).map(label -> _)
        }
      } yield Type.Record(resolved)
    }
  }

  private def exposeExplicitArrow(value: Elaborated): Elaborated = value.tpe match {
    case Type.Implicit(parameter, result) => {
      exposeExplicitArrow(Elaborated(ImplicitTerm.ImplicitApp(value.expression, ImplicitTerm.Need(parameter)), result))
    }
    case _ => value
  }

  private def elaborate(expression: Expr, scope: Scope): Result[Elaborated, Error] = expression match {
    case Expr.Var(name) => scope.lookup(name)
    case Expr.Lam(name, annotation, body) => {
      for {
        parameter <- resolveType(annotation, scope)
        result <- elaborate(body, scope.bind(name, parameter, Space.Ordinary))
      } yield Elaborated(ImplicitTerm.Lam(parameter, result.expression), Type.Arrow(parameter, result.tpe))
    }
    /*
     * Γ; Δ, d:A ⊢ e:B ⇝ c
     * ───────────────────────────────── E-Implicit-Lambda
     * Γ; Δ ⊢ λ{d:A}.e : A ⇒ B ⇝ λ{d:A}.c
     */
    case Expr.ImplicitLam(name, annotation, body) => {
      for {
        parameter <- resolveType(annotation, scope)
        result <- elaborate(body, scope.bind(name, parameter, Space.Instance))
      } yield Elaborated(ImplicitTerm.ImplicitLam(parameter, result.expression), Type.Implicit(parameter, result.tpe))
    }
    case Expr.App(function, argument) => {
      elaborate(function, scope).map(exposeExplicitArrow).flatMap { prepared =>
        prepared.tpe match {
          case Type.Arrow(parameter, result) => {
            for {
              actual <- elaborate(argument, scope)
              _ <- Check.same(parameter, actual.tpe)
            } yield Elaborated(ImplicitTerm.App(prepared.expression, actual.expression), result)
          }
          case other => Result.Err(Error.ExpectedFunction(other))
        }
      }
    }
    case Expr.ImplicitApp(function, argument) => {
      elaborate(function, scope).flatMap { prepared =>
        prepared.tpe match {
          case Type.Implicit(parameter, result) => {
            for {
              actual <- elaborate(argument, scope)
              _ <- Check.same(parameter, actual.tpe)
            } yield Elaborated(ImplicitTerm.ImplicitApp(prepared.expression, actual.expression), result)
          }
          case other => Result.Err(Error.ExpectedImplicitFunction(other))
        }
      }
    }
    case Expr.TypeLam(parameter, body) => {
      elaborate(body, scope.bindType(parameter)).map { result =>
        Elaborated(ImplicitTerm.TypeLam(result.expression), Type.Forall(result.tpe))
      }
    }
    case Expr.TypeApp(function, argument) => {
      for {
        prepared <- elaborate(function, scope)
        actual <- resolveType(argument, scope)
        result <- prepared.tpe match {
          case Type.Forall(body) => {
            Result.Ok(Elaborated(ImplicitTerm.TypeApp(prepared.expression, actual), body.instantiate(actual)))
          }
          case other => Result.Err(Error.ExpectedPolymorphic(other))
        }
      } yield result
    }
    case Expr.Let(name, value, body) => {
      for {
        checked <- elaborate(value, scope)
        result <- elaborate(body, scope.bind(name, checked.tpe, Space.Ordinary))
      } yield Elaborated(ImplicitTerm.Let(checked.tpe, checked.expression, result.expression), result.tpe)
    }
    /*
     * Γ; Δ ⊢ value:A ⇝ c₁    Γ; Δ, d:A ⊢ body:B ⇝ c₂
     * ─────────────────────────────────────────────────── E-Impl
     * Γ; Δ ⊢ impl d:A = value in body : B ⇝ impl d:A = c₁ in c₂
     */
    case Expr.Impl(name, annotation, value, body) => {
      for {
        declared <- resolveType(annotation, scope)
        checked <- elaborate(value, scope)
        _ <- Check.same(declared, checked.tpe)
        result <- elaborate(body, scope.bind(name, declared, Space.Instance))
      } yield Elaborated(ImplicitTerm.Impl(declared, checked.expression, result.expression), result.tpe)
    }
    case Expr.TypeLet(name, parameters, definition, body) => {
      for {
        _ <- Check.distinct(parameters)
        _ <- if scope.typeNames.contains(name) then Result.Err(Error.DuplicateName(name)) else Result.Ok(())
        parameterScope = parameters.foldLeft(scope)((current, parameter) => current.bindType(parameter))
        template <- resolveType(definition, parameterScope)
        alias = Alias(parameters.size, template, scope.typeNames.size)
        result <- elaborate(body, scope.copy(aliases = scope.aliases.updated(name, alias)))
      } yield result
    }
    case Expr.Need(annotation) => resolveType(annotation, scope).map(tpe => Elaborated(ImplicitTerm.Need(tpe), tpe))
    case Expr.Ascribe(value, annotation) => {
      for {
        expected <- resolveType(annotation, scope)
        checked <- elaborate(value, scope)
        _ <- Check.same(expected, checked.tpe)
      } yield checked
    }
    case Expr.IntLit(value) => Result.Ok(Elaborated(ImplicitTerm.IntLit(value), Type.IntType))
    case Expr.StringLit(value) => Result.Ok(Elaborated(ImplicitTerm.StringLit(value), Type.StringType))
    case Expr.BoolLit(value) => Result.Ok(Elaborated(ImplicitTerm.BoolLit(value), Type.BoolType))
    case Expr.Record(fields) => {
      for {
        _ <- Check.distinct(fields.map(_._1))
        checked <- Result.traverse(fields.sortBy(_._1)) { case (label, value) =>
          elaborate(value, scope).map(label -> _)
        }
      } yield Elaborated(
        ImplicitTerm.Record(checked.map { case (label, value) => label -> value.expression }),
        Type.Record(checked.map { case (label, value) => label -> value.tpe })
      )
    }
    /*
     * Γ; Δ ⊢ e:{ℓⱼ:Aⱼ}ⱼ∈J ⇝ c    k ∈ J
     * ────────────────────────────────── E-Project
     * Γ; Δ ⊢ e.ℓₖ:Aₖ ⇝ c.ℓₖ
     *
     * Γ; Δ ⊢ e:A ⇝ c    ℓ ∉ labels(A)    ℳΔ,A,ℓ = {(P,B)}
     * ─────────────────────────────────────────────────── E-Method
     * Γ; Δ ⊢ e.ℓ:B ⇝ (?P).ℓ c
     */
    case Expr.Select(receiver, label) => {
      elaborate(receiver, scope).flatMap { checked =>
        val field = checked.tpe match {
          case Type.Record(fields) => fields.find(_._1 == label)
          case _ => None
        }
        field match {
          case Some((_, tpe)) => Result.Ok(Elaborated(ImplicitTerm.Proj(checked.expression, label), tpe))
          case None => elaborateMethod(checked, label, scope)
        }
      }
    }
    case Expr.BinOp(kind, left, right) => {
      for {
        first <- elaborate(left, scope)
        _ <- Check.same(kind.operandType, first.tpe)
        second <- elaborate(right, scope)
        _ <- Check.same(kind.operandType, second.tpe)
      } yield Elaborated(ImplicitTerm.BinOp(kind, first.expression, second.expression), kind.resultType)
    }
  }

  /** Infer a method's evidence TYPE from Δ; defer the actual evidence choice to the synthesizer. */
  private def elaborateMethod(receiver: Elaborated, label: String, scope: Scope): Result[Elaborated, Error] = {
    val candidates = scope.bindings.collect {
      case Binding(_, recordType @ Type.Record(fields), Space.Instance) => {
        fields.collectFirst {
          case (`label`, Type.Arrow(parameter, result)) if parameter == receiver.tpe => recordType -> result
        }
      }
    }.flatten.distinct
    candidates match {
      case (recordType, result) :: Nil => {
        val method = ImplicitTerm.Proj(ImplicitTerm.Need(recordType), label)
        Result.Ok(Elaborated(ImplicitTerm.App(method, receiver.expression), result))
      }
      case Nil => Result.Err(Error.MissingMethod(label, receiver.tpe))
      case _ => Result.Err(Error.AmbiguousMethod(label, candidates.map(_._1).toVector))
    }
  }

}
