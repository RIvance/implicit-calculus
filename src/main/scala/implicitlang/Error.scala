package implicitlang

import implicitlang.core.{ImplicitTerm, Type}

import implicitlang.util.Result

enum Error {
  case Parse(message: String, line: Int, column: Int)
  case UnknownTerm(name: String)
  case UnknownType(name: String)
  case DuplicateName(name: String)
  case AliasArity(name: String, expected: Int, actual: Int)
  case TypeVariableApplied(name: String)
  case ExpectedType(expected: Type, actual: Type)
  case ExpectedFunction(actual: Type)
  case ExpectedImplicitFunction(actual: Type)
  case ExpectedPolymorphic(actual: Type)
  case ExpectedRecord(actual: Type)
  case MissingField(label: String)
  case MissingMethod(label: String, receiverType: Type)
  case AmbiguousMethod(label: String, candidates: Vector[Type])
  case MissingInstance(goal: Type)
  case InvalidEvidenceForm(expression: ImplicitTerm)
  case InvalidTermIndex(space: String, index: Int)
  case InvalidTypeIndex(index: Int, depth: Int)
  case ImplicitTypeInTarget(tpe: Type)
  case InvalidEvidence(expected: Type, actual: Type)
  case Runtime(message: String)
}

private[implicitlang] object Check {
  def distinct(names: Vector[String]): Result[Unit, Error] = {
    names.groupBy(identity).collectFirst { case (name, occurrences) if occurrences.size > 1 => name } match {
      case Some(name) => Result.Err(Error.DuplicateName(name))
      case None => Result.Ok(())
    }
  }

  def same(expected: Type, actual: Type): Result[Unit, Error] = {
    if expected == actual then Result.Ok(()) else Result.Err(Error.ExpectedType(expected, actual))
  }
}
