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
  enum Part {
    case Text(value: String)
    case Goal(location: TermLocation, expectedType: String, nameHint: Option[String])
  }

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

  def term(value: ImplicitTerm, names: DisplayNames = DisplayNames()): String = {
    document(value, names).map {
      case Part.Text(text) => text
      case Part.Goal(_, expectedType, _) => s"?$expectedType"
    }.mkString
  }

  /** Goal parts retain their occurrence and scoped type for interactive presentations. */
  def document(value: ImplicitTerm, names: DisplayNames = DisplayNames()): Vector[Part] = {
    render(value, names, TermLocation(), None)
  }

  private def text(value: String): Vector[Part] = Vector(Part.Text(value))

  private def render(
    value: ImplicitTerm, names: DisplayNames, location: TermLocation, hint: Option[String]
  ): Vector[Part] = value match {
    case ImplicitTerm.Var(index) => text(names.ordinary.lift(index).getOrElse(s"x[$index]"))
    case ImplicitTerm.Instance(index) => text(names.instances.lift(index).getOrElse(s"d[$index]"))
    case ImplicitTerm.Lam(annotation, body, hint) => {
      val nested = names.bindOrdinary(hint)
      text(s"λ(${nested.ordinary.head}: ${tpe(annotation, names)}). ") ++
      render(body, nested, location / TermChild.Body, None)
    }
    case ImplicitTerm.ImplicitLam(annotation, body, hint) => {
      val nested = names.bindInstance(hint)
      text(s"λ{${nested.instances.head}: ${tpe(annotation, names)}}. ") ++
      render(body, nested, location / TermChild.Body, None)
    }
    case ImplicitTerm.TypeLam(body, hint) => {
      val nested = names.bindType(hint)
      text(s"Λ${nested.types.head}. ") ++ render(body, nested, location / TermChild.Body, None)
    }
    case ImplicitTerm.App(function, argument) => {
      text("(") ++ render(function, names, location / TermChild.Function, None) ++ text(") (") ++
      render(argument, names, location / TermChild.Argument, None) ++ text(")")
    }
    case ImplicitTerm.ImplicitApp(function, argument) => {
      val parameterHint = function match {
        case ImplicitTerm.ImplicitLam(_, _, hint) => hint
        case _ => None
      }
      text("(") ++ render(function, names, location / TermChild.Function, None) ++ text("){") ++
      render(argument, names, location / TermChild.Argument, parameterHint) ++ text("}")
    }
    case ImplicitTerm.TypeApp(function, argument) => {
      text("(") ++ render(function, names, location / TermChild.Function, None) ++ text(s")[${tpe(argument, names)}]")
    }
    case ImplicitTerm.Let(annotation, value, body, hint) => {
      val nested = names.bindOrdinary(hint)
      text(s"let ${nested.ordinary.head}: ${tpe(annotation, names)} = ") ++
      render(value, names, location / TermChild.Value, hint.map(_ => nested.ordinary.head)) ++ text(" in\n") ++
      render(body, nested, location / TermChild.Body, None)
    }
    case ImplicitTerm.Impl(annotation, value, body, hint) => {
      val nested = names.bindInstance(hint)
      text(s"impl ${nested.instances.head}: ${tpe(annotation, names)} = ") ++
      render(value, names, location / TermChild.Value, hint.map(_ => nested.instances.head)) ++ text(" in\n") ++
      render(body, nested, location / TermChild.Body, None)
    }
    case ImplicitTerm.Fix(annotation, body, hint) => {
      val nested = names.bindOrdinary(hint)
      text(s"fix ${nested.ordinary.head}: ${tpe(annotation, names)}. ") ++
      render(body, nested, location / TermChild.Body, None)
    }
    case ImplicitTerm.Need(goal, nameHint) => Vector(Part.Goal(location, tpe(goal, names), hint.orElse(nameHint)))
    case ImplicitTerm.IntLit(value) => text(value.toString)
    case ImplicitTerm.StringLit(value) => {
      val escaped = value.flatMap {
        case '\\' => "\\\\"
        case '"' => "\\\""
        case '\n' => "\\n"
        case '\r' => "\\r"
        case '\t' => "\\t"
        case character => character.toString
      }
      text(s"\"$escaped\"")
    }
    case ImplicitTerm.BoolLit(value) => text(value.toString)
    case ImplicitTerm.Record(fields) => {
      val parts = fields.toVector.sortBy(_._1).map { (label, value) =>
        text(s"$label = ") ++ render(value, names, location / TermChild.Field(label), Some(label))
      }
      text("{") ++ parts.zipWithIndex.flatMap { (part, index) =>
        (if index == 0 then Vector.empty else text(", ")) ++ part
      } ++ text("}")
    }
    case ImplicitTerm.Proj(record, label) => {
      text("(") ++ render(record, names, location / TermChild.Receiver, None) ++ text(s").$label")
    }
    case ImplicitTerm.BinOp(kind, left, right) => {
      text("(") ++ render(left, names, location / TermChild.Left, None) ++ text(s" $kind ") ++
      render(right, names, location / TermChild.Right, None) ++ text(")")
    }
  }
}
