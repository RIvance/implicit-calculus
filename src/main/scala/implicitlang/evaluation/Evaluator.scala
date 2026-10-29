package implicitlang.evaluation

import implicitlang.Error
import implicitlang.core.Term
import implicitlang.syntax.OpKind

import implicitlang.util.Result

enum Value {
  case Integer(value: BigInt)
  case Text(value: String)
  case Boolean(value: scala.Boolean)
  case Record(fields: Vector[(String, Value)])
  case Closure(body: Term, environment: List[Value])
  case TypeClosure(body: Term, environment: List[Value])

  def show: String = this match {
    case Integer(value) => value.toString
    case Text(value) => value
    case Boolean(value) => value.toString
    case Record(fields) => fields.map { case (label, value) => s"$label = ${value.show}" }.mkString("{", ", ", "}")
    case Closure(_, _) => "<function>"
    case TypeClosure(_, _) => "<polymorphic value>"
  }
}

/** Call-by-value closure semantics. Types are checked before evaluation and not inspected at runtime. */
object Evaluator {
  def evaluate(term: Term, environment: List[Value] = Nil): Result[Value, Error] = term match {
    case Term.Var(index) => environment.lift(index) match {
      case Some(value) => Result.Ok(value)
      case None => Result.Err(Error.InvalidTermIndex("runtime", index))
    }
    case Term.Lam(_, body) => Result.Ok(Value.Closure(body, environment))
    case Term.TypeLam(body) => Result.Ok(Value.TypeClosure(body, environment))
    case Term.App(function, argument) => {
      for {
        callable <- evaluate(function, environment)
        actual <- evaluate(argument, environment)
        result <- callable match {
          case Value.Closure(body, saved) => evaluate(body, actual :: saved)
          case _ => Result.Err(Error.Runtime("Expected a function closure"))
        }
      } yield result
    }
    case Term.TypeApp(function, _) => {
      evaluate(function, environment).flatMap {
        case Value.TypeClosure(body, saved) => evaluate(body, saved)
        case _ => Result.Err(Error.Runtime("Expected a type closure"))
      }
    }
    case Term.Let(value, body) => evaluate(value, environment).flatMap(actual => evaluate(body, actual :: environment))
    case Term.IntLit(value) => Result.Ok(Value.Integer(value))
    case Term.StringLit(value) => Result.Ok(Value.Text(value))
    case Term.BoolLit(value) => Result.Ok(Value.Boolean(value))
    case Term.Record(fields) => {
      Result.traverse(fields.sortBy(_._1)) { case (label, value) =>
        evaluate(value, environment).map(label -> _)
      }.map(Value.Record.apply)
    }
    case Term.Proj(record, label) => {
      evaluate(record, environment).flatMap {
        case Value.Record(fields) => fields.find(_._1 == label) match {
          case Some((_, value)) => Result.Ok(value)
          case None => Result.Err(Error.MissingField(label))
        }
        case _ => Result.Err(Error.Runtime("Expected a record value"))
      }
    }
    case Term.BinOp(kind, left, right) => {
      for {
        first <- evaluate(left, environment)
        second <- evaluate(right, environment)
        result <- (kind, first, second) match {
          case (OpKind.Add, Value.Integer(a), Value.Integer(b)) => Result.Ok(Value.Integer(a + b))
          case (OpKind.Sub, Value.Integer(a), Value.Integer(b)) => Result.Ok(Value.Integer(a - b))
          case (OpKind.Mul, Value.Integer(a), Value.Integer(b)) => Result.Ok(Value.Integer(a * b))
          case (OpKind.Eq, Value.Integer(a), Value.Integer(b)) => Result.Ok(Value.Boolean(a == b))
          case (OpKind.Lt, Value.Integer(a), Value.Integer(b)) => Result.Ok(Value.Boolean(a < b))
          case (OpKind.Gt, Value.Integer(a), Value.Integer(b)) => Result.Ok(Value.Boolean(a > b))
          case (OpKind.Concat, Value.Text(a), Value.Text(b)) => Result.Ok(Value.Text(a + b))
          case _ => Result.Err(Error.Runtime(s"Invalid operands for $kind"))
        }
      } yield result
    }
  }
}
