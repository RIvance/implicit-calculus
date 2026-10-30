package implicitlang.surface

import implicitlang.core.OpKind

enum SurfaceExpr {
  case Var(name: String)
  case Lam(parameter: String, annotation: Option[SurfaceType], body: SurfaceExpr)
  case ImplicitLam(parameter: String, annotation: SurfaceType, body: SurfaceExpr)
  case App(function: SurfaceExpr, argument: SurfaceExpr)
  case ImplicitApp(function: SurfaceExpr, argument: SurfaceExpr)
  case TypeLam(parameter: String, body: SurfaceExpr)
  case TypeApp(function: SurfaceExpr, argument: SurfaceType)
  case Let(name: String, value: SurfaceExpr, body: SurfaceExpr)
  case Given(name: String, annotation: SurfaceType, value: SurfaceExpr, body: SurfaceExpr)
  case Need(annotation: SurfaceType)
  case Ascribe(expression: SurfaceExpr, annotation: SurfaceType)
  case IntLit(value: BigInt)
  case StringLit(value: String)
  case BoolLit(value: Boolean)
  case Record(fields: Map[String, SurfaceExpr])
  /** Elaboration determines whether this selects a record field or applies an implicit method. */
  case Select(receiver: SurfaceExpr, label: String)
  case QualifiedSelect(annotation: SurfaceType, label: String)
  case BinOp(kind: OpKind, left: SurfaceExpr, right: SurfaceExpr)

  /** Free term references respect term binders; type names and field labels do not bind terms. */
  def refersTo(name: String): Boolean = this match {
    case Var(identifier) => identifier == name
    case Lam(parameter, _, body) => parameter != name && body.refersTo(name)
    case ImplicitLam(parameter, _, body) => parameter != name && body.refersTo(name)
    case App(function, argument) => function.refersTo(name) || argument.refersTo(name)
    case ImplicitApp(function, argument) => function.refersTo(name) || argument.refersTo(name)
    case TypeLam(_, body) => body.refersTo(name)
    case TypeApp(function, _) => function.refersTo(name)
    case Let(identifier, value, body) => value.refersTo(name) || (identifier != name && body.refersTo(name))
    case Given(identifier, _, value, body) => value.refersTo(name) || (identifier != name && body.refersTo(name))
    case Ascribe(expression, _) => expression.refersTo(name)
    case Record(fields) => fields.values.exists(_.refersTo(name))
    case Select(receiver, _) => receiver.refersTo(name)
    case BinOp(_, left, right) => left.refersTo(name) || right.refersTo(name)
    case Need(_) | QualifiedSelect(_, _) | IntLit(_) | StringLit(_) | BoolLit(_) => false
  }
}
