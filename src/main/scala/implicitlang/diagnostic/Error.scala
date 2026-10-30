package implicitlang.diagnostic

import implicitlang.`implicit`.ImplicitTerm
import implicitlang.core.Type

enum Error {
  case Parse(message: String, line: Int, column: Int)
  case UnknownTerm(name: String)
  case MissingParameterType(name: String)
  case UnknownType(name: String)
  case DuplicateName(name: String)
  case TypeArity(name: String, expected: Int, actual: Int)
  case UnknownTrait(name: String)
  case ExpectedTrait(name: String)
  case TraitInType(name: String)
  case RecursiveDefinitionType(name: String, actual: Type)
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
  case SynthesisLimit(goal: Type, maximumSteps: Int, maximumTypeDepth: Int)
  case SynthesisUnknown(goal: Type, reason: String)
  case SynthesisBackend(cause: Throwable)
  case InvalidTypeDepth(depth: Int)
  case InvalidEvidenceForm(expression: ImplicitTerm)
  case InvalidTermIndex(space: String, index: Int)
  case InvalidTypeIndex(index: Int, depth: Int)
  case ImplicitTypeInTarget(tpe: Type)
  case InvalidEvidence(expected: Type, actual: Type)
  case Runtime(message: String)
}
