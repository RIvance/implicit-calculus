package implicitlang.core

import implicitlang.{Check, Error}
import implicitlang.syntax.OpKind

import implicitlang.util.Result

/** Final System F terms. There is no implicit binder, instance scope, or unresolved goal here. */
enum Term {
  case Var(index: Int)
  case Lam(parameterType: Type, body: Term)
  case App(function: Term, argument: Term)
  case TypeLam(body: Term)
  case TypeApp(function: Term, argument: Type)
  case Let(value: Term, body: Term)
  case IntLit(value: BigInt)
  case StringLit(value: String)
  case BoolLit(value: Boolean)
  case Record(fields: Vector[(String, Term)])
  case Proj(record: Term, label: String)
  case BinOp(kind: OpKind, left: Term, right: Term)

  def shiftTerms(amount: Int, cutoff: Int = 0): Term = this match {
    case Var(index) if index >= cutoff => Var(index + amount)
    case Lam(annotation, body) => Lam(annotation, body.shiftTerms(amount, cutoff + 1))
    case App(function, argument) => App(function.shiftTerms(amount, cutoff), argument.shiftTerms(amount, cutoff))
    case TypeLam(body) => TypeLam(body.shiftTerms(amount, cutoff))
    case TypeApp(function, argument) => TypeApp(function.shiftTerms(amount, cutoff), argument)
    case Let(value, body) => Let(value.shiftTerms(amount, cutoff), body.shiftTerms(amount, cutoff + 1))
    case Record(fields) => Record(fields.map { case (label, value) => label -> value.shiftTerms(amount, cutoff) })
    case Proj(record, label) => Proj(record.shiftTerms(amount, cutoff), label)
    case BinOp(kind, left, right) => BinOp(kind, left.shiftTerms(amount, cutoff), right.shiftTerms(amount, cutoff))
    case _ => this
  }

  def shiftTypes(amount: Int, cutoff: Int = 0): Term = this match {
    case Lam(annotation, body) => Lam(annotation.shift(amount, cutoff), body.shiftTypes(amount, cutoff))
    case App(function, argument) => App(function.shiftTypes(amount, cutoff), argument.shiftTypes(amount, cutoff))
    case TypeLam(body) => TypeLam(body.shiftTypes(amount, cutoff + 1))
    case TypeApp(function, argument) => TypeApp(function.shiftTypes(amount, cutoff), argument.shift(amount, cutoff))
    case Let(value, body) => Let(value.shiftTypes(amount, cutoff), body.shiftTypes(amount, cutoff))
    case Record(fields) => Record(fields.map { case (label, value) => label -> value.shiftTypes(amount, cutoff) })
    case Proj(record, label) => Proj(record.shiftTypes(amount, cutoff), label)
    case BinOp(kind, left, right) => BinOp(kind, left.shiftTypes(amount, cutoff), right.shiftTypes(amount, cutoff))
    case _ => this
  }

  /** Independent checking of the explicit target, including arguments and type-variable scope. */
  def infer(environment: List[Type] = Nil, typeDepth: Int = 0): Result[Type, Error] = this match {
    case Var(index) => {
      environment.lift(index) match {
        case Some(tpe) => tpe.validate(typeDepth, allowImplicit = false).map(_ => tpe)
        case None => Result.Err(Error.InvalidTermIndex("target", index))
      }
    }
    case Lam(annotation, body) => {
      for {
        _ <- annotation.validate(typeDepth, allowImplicit = false)
        result <- body.infer(annotation :: environment, typeDepth)
      } yield Type.Arrow(annotation, result)
    }
    case App(function, argument) => {
      function.infer(environment, typeDepth).flatMap {
        case Type.Arrow(parameter, result) => {
          argument.infer(environment, typeDepth).flatMap(actual => Check.same(parameter, actual).map(_ => result))
        }
        case other => Result.Err(Error.ExpectedFunction(other))
      }
    }
    case TypeLam(body) => body.infer(environment.map(_.shift(1)), typeDepth + 1).map(Type.Forall.apply)
    case TypeApp(function, argument) => {
      for {
        _ <- argument.validate(typeDepth, allowImplicit = false)
        functionType <- function.infer(environment, typeDepth)
        result <- functionType match {
          case Type.Forall(body) => Result.Ok(body.instantiate(argument))
          case other => Result.Err(Error.ExpectedPolymorphic(other))
        }
      } yield result
    }
    case Let(value, body) => {
      value.infer(environment, typeDepth).flatMap(tpe => body.infer(tpe :: environment, typeDepth))
    }
    case IntLit(_) => Result.Ok(Type.IntType)
    case StringLit(_) => Result.Ok(Type.StringType)
    case BoolLit(_) => Result.Ok(Type.BoolType)
    case Record(fields) => {
      for {
        _ <- Check.distinct(fields.map(_._1))
        typed <- Result.traverse(fields.sortBy(_._1)) { case (label, value) =>
          value.infer(environment, typeDepth).map(label -> _)
        }
      } yield Type.Record(typed)
    }
    case Proj(record, label) => {
      record.infer(environment, typeDepth).flatMap {
        case Type.Record(fields) => fields.find(_._1 == label) match {
          case Some((_, tpe)) => Result.Ok(tpe)
          case None => Result.Err(Error.MissingField(label))
        }
        case other => Result.Err(Error.ExpectedRecord(other))
      }
    }
    case BinOp(kind, left, right) => {
      for {
        leftType <- left.infer(environment, typeDepth)
        _ <- Check.same(kind.operandType, leftType)
        rightType <- right.infer(environment, typeDepth)
        _ <- Check.same(kind.operandType, rightType)
      } yield kind.resultType
    }
  }
}
