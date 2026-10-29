package implicitlang.util

enum Result[+T, +E] {
  case Ok(value: T)
  case Err(error: E)

  def map[U](function: T => U): Result[U, E] = this match {
    case Ok(value) => Ok(function(value))
    case Err(error) => Err(error)
  }

  def flatMap[U, F >: E](function: T => Result[U, F]): Result[U, F] = this match {
    case Ok(value) => function(value)
    case Err(error) => Err(error)
  }

  def mapError[F](function: E => F): Result[T, F] = this match {
    case Ok(value) => Ok(value)
    case Err(error) => Err(function(error))
  }
}

object Result {
  def traverse[A, B, E](values: Vector[A])(function: A => Result[B, E]): Result[Vector[B], E] = {
    values.foldLeft[Result[Vector[B], E]](Ok(Vector.empty)) { (accumulated, value) =>
      accumulated.flatMap(items => function(value).map(items :+ _))
    }
  }
}
