package implicitlang.core

import scala.util.control.TailCalls.{TailRec, done, tailcall}

/** System F with records and unrestricted fixpoints; implicit arguments have been resolved. */
enum Term {
  case Var(index: Int)
  case Lam(parameterType: Type, body: Term)
  case App(function: Term, argument: Term)
  case TypeLam(body: Term)
  case TypeApp(function: Term, argument: Type)
  case Let(value: Term, body: Term)
  case Fix(valueType: Type, body: Term)
  case IntLit(value: BigInt)
  case StringLit(value: String)
  case BoolLit(value: Boolean)
  case Record(fields: Map[String, Term])
  case Proj(record: Term, label: String)
  case BinOp(kind: OpKind, left: Term, right: Term)

  def shiftTerms(amount: Int, cutoff: Int = 0): Term = shifted(amount, 0, cutoff, 0).result

  def shiftTypes(amount: Int, cutoff: Int = 0): Term = shifted(0, amount, 0, cutoff).result

  /** Substitute for the outermost term binder and remove it, avoiding capture by either kind of binder. */
  def instantiate(argument: Term): Term = {
    transform(
      (index, termDepth, typeDepth) => {
        if index == termDepth then argument.shifted(termDepth, typeDepth, 0, 0)
        else done(Var(if index > termDepth then index - 1 else index))
      },
      (annotation, _) => annotation
    ).result
  }

  private def shifted(termAmount: Int, typeAmount: Int, termCutoff: Int, typeCutoff: Int): TailRec[Term] = {
    if termAmount == 0 && typeAmount == 0 then done(this)
    else transform(
      (index, termDepth, _) => done(Var(if index >= termCutoff + termDepth then index + termAmount else index)),
      (annotation, typeDepth) => {
        if typeAmount == 0 then annotation else annotation.shift(typeAmount, typeCutoff + typeDepth)
      }
    )
  }

  /**
   * Track term and type binders separately. Replacement terms are already transformed by the caller.
   * Suspended child traversals preserve the recursive equations without consuming the host stack.
   */
  private def transform(
    variable: (Int, Int, Int) => TailRec[Term],
    annotation: (Type, Int) => Type,
    termDepth: Int = 0,
    typeDepth: Int = 0
  ): TailRec[Term] = {
    def visit(child: Term, terms: Int = termDepth, types: Int = typeDepth): TailRec[Term] = {
      tailcall(child.transform(variable, annotation, terms, types))
    }
    this match {
      case Var(index) => variable(index, termDepth, typeDepth)
      case Lam(tpe, body) => visit(body, termDepth + 1).map(Lam(annotation(tpe, typeDepth), _))
      case Fix(tpe, body) => visit(body, termDepth + 1).map(Fix(annotation(tpe, typeDepth), _))
      case App(function, argument) => {
        for {
          transformedFunction <- visit(function)
          transformedArgument <- visit(argument)
        } yield App(transformedFunction, transformedArgument)
      }
      case TypeLam(body) => visit(body, types = typeDepth + 1).map(TypeLam.apply)
      case TypeApp(function, argument) => visit(function).map(TypeApp(_, annotation(argument, typeDepth)))
      case Let(value, body) => {
        for {
          transformedValue <- visit(value)
          transformedBody <- visit(body, termDepth + 1)
        } yield Let(transformedValue, transformedBody)
      }
      case Record(fields) => {
        fields.foldLeft[TailRec[Map[String, Term]]](done(Map.empty)) { case (accumulated, (label, value)) =>
          for {
            transformedFields <- accumulated
            transformed <- visit(value)
          } yield transformedFields.updated(label, transformed)
        }.map(Record.apply)
      }
      case Proj(record, label) => visit(record).map(Proj(_, label))
      case BinOp(kind, left, right) => {
        for {
          first <- visit(left)
          second <- visit(right)
        } yield BinOp(kind, first, second)
      }
      case IntLit(_) | StringLit(_) | BoolLit(_) => done(this)
    }
  }
}
