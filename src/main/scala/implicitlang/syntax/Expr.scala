package implicitlang.syntax


/** The parser produces these expressions directly, including lexical Impl and TypeLet nodes. */
enum Expr {
  case Var(name: String)
  case Lam(parameter: String, annotation: TypeExpr, body: Expr)
  case ImplicitLam(parameter: String, annotation: TypeExpr, body: Expr)
  case App(function: Expr, argument: Expr)
  case ImplicitApp(function: Expr, argument: Expr)
  case TypeLam(parameter: String, body: Expr)
  case TypeApp(function: Expr, argument: TypeExpr)
  case Let(name: String, value: Expr, body: Expr)
  case Impl(name: String, annotation: TypeExpr, value: Expr, body: Expr)
  case TypeLet(name: String, parameters: Vector[String], definition: TypeExpr, body: Expr)
  case Need(annotation: TypeExpr)
  case Ascribe(expression: Expr, annotation: TypeExpr)
  case IntLit(value: BigInt)
  case StringLit(value: String)
  case BoolLit(value: Boolean)
  case Record(fields: Vector[(String, Expr)])
  case Proj(record: Expr, label: String)
  case BinOp(kind: OpKind, left: Expr, right: Expr)
}

enum TypeExpr {
  case IntType
  case StringType
  case BoolType
  case Named(name: String, arguments: Vector[TypeExpr])
  case Arrow(parameter: TypeExpr, result: TypeExpr)
  case Implicit(parameter: TypeExpr, result: TypeExpr)
  case Forall(parameter: String, body: TypeExpr)
  case Record(fields: Vector[(String, TypeExpr)])
}
