package implicitlang.core

import implicitlang.diagnostic.Error
import implicitlang.util.Result

import scala.annotation.tailrec

enum Value {
  case Integer(value: BigInt)
  case Text(value: String)
  case Boolean(value: scala.Boolean)
  case Record(fields: Map[String, Value])
  case Closure(body: Term, environment: List[Value])
  case TypeClosure(body: Term, environment: List[Value])

  def show: String = this match {
    case Integer(value) => value.toString
    case Text(value) => value
    case Boolean(value) => value.toString
    case Record(fields) => {
      fields.toVector.sortBy(_._1).map { case (label, value) => s"$label = ${value.show}" }.mkString("{", ", ", "}")
    }
    case Closure(_, _) => "<function>"
    case TypeClosure(_, _) => "<polymorphic value>"
  }
}

/** Call-by-value evaluation with immutable environments and an explicit continuation stack. */
object Evaluator {
  private enum Control {
    case Evaluate(term: Term, environment: List[Value])
    case Return(value: Value)
  }

  private enum Frame {
    case Function(argument: Term, environment: List[Value])
    case Argument(function: Value)
    case TypeApplication
    case Binding(body: Term, environment: List[Value])
    case Field(
      label: String, remaining: List[(String, Term)], values: Map[String, Value], environment: List[Value]
    )
    case Projection(label: String)
    case LeftOperand(kind: OpKind, right: Term, environment: List[Value])
    case RightOperand(kind: OpKind, left: Value)
  }

  def evaluate(term: Term, environment: List[Value] = Nil): Result[Value, Error] = {
    run(Control.Evaluate(term, environment), Nil)
  }

  /** Each frame resumes the corresponding call-by-value rule; recursion unfolds without using the host stack. */
  @tailrec
  private def run(control: Control, stack: List[Frame]): Result[Value, Error] = control match {
    case Control.Evaluate(term, environment) => term match {
      case Term.Var(index) => environment.lift(index) match {
        case Some(value) => run(Control.Return(value), stack)
        case None => Result.Err(Error.InvalidTermIndex("runtime", index))
      }
      case Term.Lam(_, body) => run(Control.Return(Value.Closure(body, environment)), stack)
      case Term.TypeLam(body) => run(Control.Return(Value.TypeClosure(body, environment)), stack)
      /*
       * ρ ⊢ t[fix x:A.t/x] ⇓ v
       * ─────────────────────── V-Fix
       * ρ ⊢ fix x:A.t ⇓ v
       */
      case fix @ Term.Fix(_, body) => run(Control.Evaluate(body.instantiate(fix), environment), stack)
      case Term.App(function, argument) => {
        run(Control.Evaluate(function, environment), Frame.Function(argument, environment) :: stack)
      }
      case Term.TypeApp(function, _) => {
        run(Control.Evaluate(function, environment), Frame.TypeApplication :: stack)
      }
      case Term.Let(value, body) => run(Control.Evaluate(value, environment), Frame.Binding(body, environment) :: stack)
      case Term.IntLit(value) => run(Control.Return(Value.Integer(value)), stack)
      case Term.StringLit(value) => run(Control.Return(Value.Text(value)), stack)
      case Term.BoolLit(value) => run(Control.Return(Value.Boolean(value)), stack)
      case Term.Record(fields) => fields.toList match {
        case Nil => run(Control.Return(Value.Record(Map.empty)), stack)
        case (label, value) :: rest => {
          run(Control.Evaluate(value, environment), Frame.Field(label, rest, Map.empty, environment) :: stack)
        }
      }
      case Term.Proj(record, label) => run(Control.Evaluate(record, environment), Frame.Projection(label) :: stack)
      case Term.BinOp(kind, left, right) => {
        run(Control.Evaluate(left, environment), Frame.LeftOperand(kind, right, environment) :: stack)
      }
    }
    case Control.Return(value) => stack match {
      case Nil => Result.Ok(value)
      case Frame.Function(argument, environment) :: rest => {
        run(Control.Evaluate(argument, environment), Frame.Argument(value) :: rest)
      }
      case Frame.Argument(function) :: rest => function match {
        case Value.Closure(body, saved) => {
          run(Control.Evaluate(body, value :: saved), rest)
        }
        case _ => Result.Err(Error.Runtime("Expected a function closure"))
      }
      case Frame.TypeApplication :: rest => value match {
        case Value.TypeClosure(body, saved) => run(Control.Evaluate(body, saved), rest)
        case _ => Result.Err(Error.Runtime("Expected a type closure"))
      }
      case Frame.Binding(body, environment) :: rest => {
        run(Control.Evaluate(body, value :: environment), rest)
      }
      case Frame.Field(label, remaining, values, environment) :: rest => {
        val evaluated = values.updated(label, value)
        remaining match {
          case Nil => run(Control.Return(Value.Record(evaluated)), rest)
          case (nextLabel, nextTerm) :: pending => {
            run(
              Control.Evaluate(nextTerm, environment), Frame.Field(nextLabel, pending, evaluated, environment) :: rest
            )
          }
        }
      }
      case Frame.Projection(label) :: rest => value match {
        case Value.Record(fields) => fields.get(label) match {
          case Some(field) => run(Control.Return(field), rest)
          case None => Result.Err(Error.MissingField(label))
        }
        case _ => Result.Err(Error.Runtime("Expected a record value"))
      }
      case Frame.LeftOperand(kind, right, environment) :: rest => {
        run(Control.Evaluate(right, environment), Frame.RightOperand(kind, value) :: rest)
      }
      case Frame.RightOperand(kind, left) :: rest => primitive(kind, left, value) match {
        case Result.Ok(result) => run(Control.Return(result), rest)
        case Result.Err(error) => Result.Err(error)
      }
    }
  }

  private def primitive(kind: OpKind, left: Value, right: Value): Result[Value, Error] = (kind, left, right) match {
    case (OpKind.Add, Value.Integer(a), Value.Integer(b)) => Result.Ok(Value.Integer(a + b))
    case (OpKind.Sub, Value.Integer(a), Value.Integer(b)) => Result.Ok(Value.Integer(a - b))
    case (OpKind.Mul, Value.Integer(a), Value.Integer(b)) => Result.Ok(Value.Integer(a * b))
    case (OpKind.Eq, Value.Integer(a), Value.Integer(b)) => Result.Ok(Value.Boolean(a == b))
    case (OpKind.Lt, Value.Integer(a), Value.Integer(b)) => Result.Ok(Value.Boolean(a < b))
    case (OpKind.Gt, Value.Integer(a), Value.Integer(b)) => Result.Ok(Value.Boolean(a > b))
    case (OpKind.Concat, Value.Text(a), Value.Text(b)) => Result.Ok(Value.Text(a + b))
    case _ => Result.Err(Error.Runtime(s"Invalid operands for $kind"))
  }
}
