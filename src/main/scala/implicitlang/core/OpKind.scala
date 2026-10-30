package implicitlang.core

/** Primitive operations have fixed operand and result types. */
enum OpKind {
  case Add, Sub, Mul, Eq, Lt, Gt, Concat

  def operandType: Type = this match {
    case Concat => Type.StringType
    case _ => Type.IntType
  }

  def resultType: Type = this match {
    case Add | Sub | Mul => Type.IntType
    case Eq | Lt | Gt => Type.BoolType
    case Concat => Type.StringType
  }

  override def toString: String = this match {
    case Add => "+"
    case Sub => "-"
    case Mul => "*"
    case Eq => "=="
    case Lt => "<"
    case Gt => ">"
    case Concat => "++"
  }
}
