package implicitlang.core

import implicitlang.diagnostic.Error
import implicitlang.util.Result

/** Syntax-directed typing for explicit System F terms, records, primitives, and unrestricted fixpoints. */
object TypeChecker {
  /** All binding types are well formed at typeDepth and contain only explicit arrows. */
  private final case class Context(environment: List[Type], typeDepth: Int) {
    def bind(tpe: Type): Context = copy(environment = tpe :: environment)

    def bindType: Context = Context(environment.map(_.shift(1)), typeDepth + 1)
  }

  private object Context {
    def validated(environment: List[Type], typeDepth: Int): Result[Context, Error] = {
      if typeDepth < 0 then Result.Err(Error.InvalidTypeDepth(typeDepth))
      else {
        environment.foldLeft[Result[Unit, Error]](Result.Ok(())) { (checked, tpe) =>
          checked.flatMap(_ => tpe.validate(typeDepth, allowImplicit = false))
        }.map(_ => Context(environment, typeDepth))
      }
    }
  }

  /** Infer a term's type in a nearest-first environment with typeDepth free type variables. */
  def infer(term: Term, environment: List[Type] = Nil, typeDepth: Int = 0): Result[Type, Error] = {
    Context.validated(environment, typeDepth).flatMap(infer(term, _))
  }

  /** Check exact type equality, validating the entire context and expected type before checking the term. */
  def check(term: Term, expected: Type, environment: List[Type] = Nil, typeDepth: Int = 0): Result[Unit, Error] = {
    for {
      context <- Context.validated(environment, typeDepth)
      _ <- expected.validate(typeDepth, allowImplicit = false)
      _ <- check(term, expected, context)
    } yield ()
  }

  private def check(term: Term, expected: Type, context: Context): Result[Unit, Error] = {
    infer(term, context).flatMap { actual =>
      if expected == actual then Result.Ok(()) else Result.Err(Error.ExpectedType(expected, actual))
    }
  }

  private def infer(term: Term, context: Context): Result[Type, Error] = term match {
    /*
     * z:T ∈ H
     * ───────── T-Var
     * H ⊢ z:T
     */
    case Term.Var(index) => context.environment.lift(index) match {
      case Some(tpe) => Result.Ok(tpe)
      case None => Result.Err(Error.InvalidTermIndex("target", index))
    }
    /*
     * H ⊢ T type    H,z:T ⊢ t:U
     * ───────────────────────── T-Lambda
     * H ⊢ λ(z:T).t : T → U
     */
    case Term.Lam(annotation, body) => {
      for {
        _ <- annotation.validate(context.typeDepth, allowImplicit = false)
        result <- infer(body, context.bind(annotation))
      } yield Type.Arrow(annotation, result)
    }
    /*
     * H ⊢ t₁:T → U    H ⊢ t₂:T
     * ───────────────────────── T-App
     * H ⊢ t₁ t₂:U
     */
    case Term.App(function, argument) => infer(function, context).flatMap {
      case Type.Arrow(parameter, result) => check(argument, parameter, context).map(_ => result)
      case other => Result.Err(Error.ExpectedFunction(other))
    }
    /*
     * H,α ⊢ t:T    α ∉ FV(H)
     * ─────────────────────── T-Type-Lambda
     * H ⊢ Λα.t : ∀α.T
     */
    case Term.TypeLam(body) => infer(body, context.bindType).map(Type.Forall.apply)
    /*
     * H ⊢ t:∀α.T    H ⊢ U type
     * ───────────────────────── T-Type-App
     * H ⊢ t[U] : T[U/α]
     */
    case Term.TypeApp(function, argument) => {
      for {
        _ <- argument.validate(context.typeDepth, allowImplicit = false)
        functionType <- infer(function, context)
        result <- functionType match {
          case Type.Forall(body) => Result.Ok(body.instantiate(argument))
          case other => Result.Err(Error.ExpectedPolymorphic(other))
        }
      } yield result
    }
    /*
     * H ⊢ t₁:T    H,z:T ⊢ t₂:U
     * ───────────────────────── T-Let
     * H ⊢ let z = t₁ in t₂:U
     */
    case Term.Let(value, body) => infer(value, context).flatMap(tpe => infer(body, context.bind(tpe)))
    /*
     * H ⊢ T type    H,z:T ⊢ t:T
     * ───────────────────────── T-Fix
     * H ⊢ fix z:T.t : T
     */
    case Term.Fix(annotation, body) => {
      for {
        _ <- annotation.validate(context.typeDepth, allowImplicit = false)
        _ <- check(body, annotation, context.bind(annotation))
      } yield annotation
    }
    /*
     * n ∈ ℤ              s a string          b ∈ {true,false}
     * ───────── T-Int    ─────────── T-String ──────────────── T-Bool
     * H ⊢ n:Int          H ⊢ s:String        H ⊢ b:Bool
     */
    case Term.IntLit(_) => Result.Ok(Type.IntType)
    case Term.StringLit(_) => Result.Ok(Type.StringType)
    case Term.BoolLit(_) => Result.Ok(Type.BoolType)
    /*
     * H ⊢ tⱼ:Tⱼ  (j ∈ J)
     * ─────────────────────────── T-Record
     * H ⊢ {ℓⱼ=tⱼ}ⱼ∈J : {ℓⱼ:Tⱼ}ⱼ∈J
     */
    case Term.Record(fields) => Result.traverse(fields)(infer(_, context)).map(Type.Record.apply)
    /*
     * H ⊢ t:{ℓⱼ:Tⱼ}ⱼ∈J    k ∈ J
     * ─────────────────────────── T-Project
     * H ⊢ t.ℓₖ:Tₖ
     */
    case Term.Proj(record, label) => infer(record, context).flatMap {
      case Type.Record(fields) => fields.get(label) match {
        case Some(tpe) => Result.Ok(tpe)
        case None => Result.Err(Error.MissingField(label))
      }
      case other => Result.Err(Error.ExpectedRecord(other))
    }
    /*
     * H ⊢ t₁:arg(⊙)    H ⊢ t₂:arg(⊙)
     * ─────────────────────────────── T-BinOp
     * H ⊢ t₁ ⊙ t₂:res(⊙)
     */
    case Term.BinOp(kind, left, right) => {
      for {
        _ <- check(left, kind.operandType, context)
        _ <- check(right, kind.operandType, context)
      } yield kind.resultType
    }
  }
}
