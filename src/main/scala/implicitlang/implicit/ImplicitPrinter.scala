package implicitlang.`implicit`

import implicitlang.core.Type

/** Nearest-first display names, freshened across Γ and Δ to avoid apparent capture. */
final case class DisplayNames(
  ordinary: List[String] = Nil, instances: List[String] = Nil, types: List[String] = Nil
) {
  private def fresh(hint: Option[String], fallback: String, used: List[String]): String = {
    val base = hint.filter(_.nonEmpty).getOrElse(fallback)
    if !used.contains(base) then base
    else Iterator.from(1).map(index => s"$base$index").find(name => !used.contains(name)).get
  }

  def bindOrdinary(hint: Option[String]): DisplayNames = {
    copy(ordinary = fresh(hint, "x", ordinary ++ instances) :: ordinary)
  }

  def bindInstance(hint: Option[String]): DisplayNames = {
    copy(instances = fresh(hint, "d", ordinary ++ instances) :: instances)
  }

  def bindType(hint: Option[String]): DisplayNames = copy(types = fresh(hint, "A", types) :: types)
}

/** Mathematical rendering of implicit terms; this is not a serialization or a source formatter. */
object ImplicitPrinter {
  def tpe(value: Type, names: DisplayNames = DisplayNames()): String = value match {
    case Type.IntType => "Int"
    case Type.StringType => "String"
    case Type.BoolType => "Bool"
    case Type.Variable(index) => names.types.lift(index).getOrElse(s"α[$index]")
    case Type.Arrow(parameter, result) => s"(${tpe(parameter, names)} → ${tpe(result, names)})"
    case Type.Implicit(parameter, result) => s"(${tpe(parameter, names)} ⇒ ${tpe(result, names)})"
    case Type.Forall(body) => {
      val nested = names.bindType(None)
      s"∀${nested.types.head}. ${tpe(body, nested)}"
    }
    case Type.Record(fields) => {
      fields.toVector.sortBy(_._1).map((label, value) => s"$label: ${tpe(value, names)}").mkString("{", ", ", "}")
    }
  }

  def term(value: ImplicitTerm, names: DisplayNames = DisplayNames()): String = value match {
    case ImplicitTerm.Var(index) => names.ordinary.lift(index).getOrElse(s"x[$index]")
    case ImplicitTerm.Instance(index) => names.instances.lift(index).getOrElse(s"d[$index]")
    case ImplicitTerm.Lam(annotation, body, hint) => {
      val nested = names.bindOrdinary(hint)
      s"λ(${nested.ordinary.head}: ${tpe(annotation, names)}). ${term(body, nested)}"
    }
    case ImplicitTerm.ImplicitLam(annotation, body, hint) => {
      val nested = names.bindInstance(hint)
      s"λ{${nested.instances.head}: ${tpe(annotation, names)}}. ${term(body, nested)}"
    }
    case ImplicitTerm.TypeLam(body, hint) => {
      val nested = names.bindType(hint)
      s"Λ${nested.types.head}. ${term(body, nested)}"
    }
    case ImplicitTerm.App(function, argument) => s"(${term(function, names)}) (${term(argument, names)})"
    case ImplicitTerm.ImplicitApp(function, argument) => s"(${term(function, names)}){${term(argument, names)}}"
    case ImplicitTerm.TypeApp(function, argument) => s"(${term(function, names)})[${tpe(argument, names)}]"
    case ImplicitTerm.Let(annotation, value, body, hint) => {
      val nested = names.bindOrdinary(hint)
      s"let ${nested.ordinary.head}: ${tpe(annotation, names)} = ${term(value, names)} in\n${term(body, nested)}"
    }
    case ImplicitTerm.Impl(annotation, value, body, hint) => {
      val nested = names.bindInstance(hint)
      s"impl ${nested.instances.head}: ${tpe(annotation, names)} = ${term(value, names)} in\n${term(body, nested)}"
    }
    case ImplicitTerm.Fix(annotation, body, hint) => {
      val nested = names.bindOrdinary(hint)
      s"fix ${nested.ordinary.head}: ${tpe(annotation, names)}. ${term(body, nested)}"
    }
    case ImplicitTerm.Need(goal) => s"?${tpe(goal, names)}"
    case ImplicitTerm.IntLit(value) => value.toString
    case ImplicitTerm.StringLit(value) => {
      val escaped = value.flatMap {
        case '\\' => "\\\\"
        case '"' => "\\\""
        case '\n' => "\\n"
        case '\r' => "\\r"
        case '\t' => "\\t"
        case character => character.toString
      }
      s"\"$escaped\""
    }
    case ImplicitTerm.BoolLit(value) => value.toString
    case ImplicitTerm.Record(fields) => {
      fields.toVector.sortBy(_._1).map((label, value) => s"$label = ${term(value, names)}").mkString("{", ", ", "}")
    }
    case ImplicitTerm.Proj(record, label) => s"(${term(record, names)}).$label"
    case ImplicitTerm.BinOp(kind, left, right) => s"(${term(left, names)} $kind ${term(right, names)})"
  }
}
