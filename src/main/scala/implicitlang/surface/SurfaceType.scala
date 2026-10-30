package implicitlang.surface

enum SurfaceType {
  case IntType
  case StringType
  case BoolType
  case Named(name: String, arguments: Vector[SurfaceType])
  case Arrow(parameter: SurfaceType, result: SurfaceType)
  case Implicit(parameter: SurfaceType, result: SurfaceType)
  case Forall(parameter: String, body: SurfaceType)
  case Record(fields: Map[String, SurfaceType])
}
