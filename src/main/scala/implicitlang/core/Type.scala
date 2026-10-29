package implicitlang.core

import implicitlang.{Check, Error}

import implicitlang.util.Result

/** Type variables use de Bruijn indices; record fields have distinct, sorted labels. */
enum Type {
  case IntType
  case StringType
  case BoolType
  case Variable(index: Int)
  case Arrow(parameter: Type, result: Type)
  case Implicit(parameter: Type, result: Type)
  case Forall(body: Type)
  case Record(fields: Vector[(String, Type)])

  def shift(amount: Int, cutoff: Int = 0): Type = this match {
    case Variable(index) if index >= cutoff => Variable(index + amount)
    case Arrow(parameter, result) => Arrow(parameter.shift(amount, cutoff), result.shift(amount, cutoff))
    case Implicit(parameter, result) => Implicit(parameter.shift(amount, cutoff), result.shift(amount, cutoff))
    case Forall(body) => Forall(body.shift(amount, cutoff + 1))
    case Record(fields) => Record(fields.map { case (label, tpe) => label -> tpe.shift(amount, cutoff) })
    case _ => this
  }

  private def substitute(index: Int, replacement: Type, depth: Int = 0): Type = this match {
    case Variable(current) if current == index + depth => replacement.shift(depth)
    case Arrow(parameter, result) => {
      Arrow(parameter.substitute(index, replacement, depth), result.substitute(index, replacement, depth))
    }
    case Implicit(parameter, result) => {
      Implicit(parameter.substitute(index, replacement, depth), result.substitute(index, replacement, depth))
    }
    case Forall(body) => Forall(body.substitute(index, replacement, depth + 1))
    case Record(fields) => {
      Record(fields.map { case (label, tpe) => label -> tpe.substitute(index, replacement, depth) })
    }
    case _ => this
  }

  /** Substitute for the outermost bound type variable and remove its binder. */
  def instantiate(argument: Type): Type = substitute(0, argument.shift(1)).shift(-1)

  def erased: Type = this match {
    case Arrow(parameter, result) => Arrow(parameter.erased, result.erased)
    case Implicit(parameter, result) => Arrow(parameter.erased, result.erased)
    case Forall(body) => Forall(body.erased)
    case Record(fields) => Record(fields.map { case (label, tpe) => label -> tpe.erased })
    case _ => this
  }

  def validate(depth: Int, allowImplicit: Boolean = true): Result[Unit, Error] = this match {
    case Variable(index) if index < 0 || index >= depth => Result.Err(Error.InvalidTypeIndex(index, depth))
    case Arrow(parameter, result) => {
      parameter.validate(depth, allowImplicit).flatMap(_ => result.validate(depth, allowImplicit))
    }
    case Implicit(_, _) if !allowImplicit => Result.Err(Error.ImplicitTypeInTarget(this))
    case Implicit(parameter, result) => {
      parameter.validate(depth, allowImplicit).flatMap(_ => result.validate(depth, allowImplicit))
    }
    case Forall(body) => body.validate(depth + 1, allowImplicit)
    case Record(fields) => {
      for {
        _ <- Check.distinct(fields.map(_._1))
        _ <- Result.traverse(fields)(field => field._2.validate(depth, allowImplicit))
      } yield ()
    }
    case _ => Result.Ok(())
  }

  def show: String = this match {
    case IntType => "Int"
    case StringType => "String"
    case BoolType => "Bool"
    case Variable(index) => s"^$index"
    case Arrow(parameter, result) => s"(${parameter.show} -> ${result.show})"
    case Implicit(parameter, result) => s"(${parameter.show} ~> ${result.show})"
    case Forall(body) => s"(forall. ${body.show})"
    case Record(fields) => fields.map { case (label, tpe) => s"$label: ${tpe.show}" }.mkString("{", ", ", "}")
  }
}
