package implicitlang.core

import implicitlang.syntax.OpKind

/** Two independent de Bruijn spaces: Var indexes Γ, Instance indexes Δ. */
enum ImplicitTerm {
  case Var(index: Int)
  case Instance(index: Int)
  case Lam(parameterType: Type, body: ImplicitTerm)
  case ImplicitLam(parameterType: Type, body: ImplicitTerm)
  case App(function: ImplicitTerm, argument: ImplicitTerm)
  case ImplicitApp(function: ImplicitTerm, argument: ImplicitTerm)
  case TypeLam(body: ImplicitTerm)
  case TypeApp(function: ImplicitTerm, argument: Type)
  case Let(valueType: Type, value: ImplicitTerm, body: ImplicitTerm)
  case Impl(valueType: Type, value: ImplicitTerm, body: ImplicitTerm)
  case Need(goal: Type)
  case IntLit(value: BigInt)
  case StringLit(value: String)
  case BoolLit(value: Boolean)
  case Record(fields: Vector[(String, ImplicitTerm)])
  case Proj(record: ImplicitTerm, label: String)
  case BinOp(kind: OpKind, left: ImplicitTerm, right: ImplicitTerm)
}

final case class Elaborated(expression: ImplicitTerm, tpe: Type)
