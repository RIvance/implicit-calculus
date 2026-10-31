package implicitlang.`implicit`

import implicitlang.core.{Type, OpKind}

/**
 * Two independent de Bruijn spaces: Var indexes Γ, Instance indexes Δ.
 * Name hints retain source spelling and evidence origins for presentation; binding and typing use only indices.
 */
enum ImplicitTerm {
  case Var(index: Int)
  case Instance(index: Int)
  case Lam(parameterType: Type, body: ImplicitTerm, nameHint: Option[String] = None)
  case ImplicitLam(parameterType: Type, body: ImplicitTerm, nameHint: Option[String] = None)
  case App(function: ImplicitTerm, argument: ImplicitTerm)
  case ImplicitApp(function: ImplicitTerm, argument: ImplicitTerm)
  case TypeLam(body: ImplicitTerm, nameHint: Option[String] = None)
  case TypeApp(function: ImplicitTerm, argument: Type)
  case Let(valueType: Type, value: ImplicitTerm, body: ImplicitTerm, nameHint: Option[String] = None)
  case Fix(valueType: Type, body: ImplicitTerm, nameHint: Option[String] = None)
  case Impl(valueType: Type, value: ImplicitTerm, body: ImplicitTerm, nameHint: Option[String] = None)
  case Need(goal: Type, nameHint: Option[String] = None)
  case IntLit(value: BigInt)
  case StringLit(value: String)
  case BoolLit(value: Boolean)
  case Record(fields: Map[String, ImplicitTerm])
  case Proj(record: ImplicitTerm, label: String)
  case BinOp(kind: OpKind, left: ImplicitTerm, right: ImplicitTerm)
}

final case class Elaborated(expression: ImplicitTerm, tpe: Type)

/** Identifies an occurrence within one elaborated term, independently of record traversal order. */
final case class TermLocation(steps: Vector[TermChild] = Vector.empty) {
  def /(child: TermChild): TermLocation = TermLocation(steps :+ child)
}

enum TermChild {
  case Body, Value, Function, Argument, Receiver, Left, Right
  case Field(label: String)
}
