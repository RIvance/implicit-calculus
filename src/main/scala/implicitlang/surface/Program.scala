package implicitlang.surface

/** Declarations are sequential; the final expression supplies the program's result. */
final case class Program(declarations: Vector[Declaration], expression: SurfaceExpr)

enum Declaration {
  case TypeAlias(name: String, parameters: Vector[String], definition: SurfaceType)
  case Trait(name: String, parameters: Vector[String], definition: SurfaceType)
  case Implement(typeParameters: TypeParameters, target: TraitRef, value: SurfaceExpr)
  case Instance(name: String, typeParameters: TypeParameters, annotation: SurfaceType, value: SurfaceExpr)
  case Def(
    name: String, typeParameters: TypeParameters, parameters: Vector[Parameter],
    resultType: SurfaceType, body: SurfaceExpr
  )
}

enum ParameterKind {
  case Ordinary, Implicit
}

final case class Parameter(name: String, annotation: SurfaceType, kind: ParameterKind) {
  def functionType(result: SurfaceType): SurfaceType = kind match {
    case ParameterKind.Ordinary => SurfaceType.Arrow(annotation, result)
    case ParameterKind.Implicit => SurfaceType.Implicit(annotation, result)
  }

  def abstractValue(body: SurfaceExpr): SurfaceExpr = kind match {
    case ParameterKind.Ordinary => SurfaceExpr.Lam(name, Some(annotation), body)
    case ParameterKind.Implicit => SurfaceExpr.ImplicitLam(name, annotation, body)
  }
}

final case class TraitRef(name: String, arguments: Vector[SurfaceType])

final case class TypeParameters(names: Vector[String], constraints: Vector[TraitRef])

object TypeParameters {
  val empty: TypeParameters = TypeParameters(Vector.empty, Vector.empty)
}
