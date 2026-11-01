package implicitlang.`implicit`

import implicitlang.core.{OpKind, Type}

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
  /** Groups offer flat or indented layouts; goal occurrences survive either layout and folding. */
  enum Document {
    case Text(value: String)
    case Goal(location: TermLocation, expectedType: Document, nameHint: Option[String])
    case Line(flat: Option[String])
    case Sequence(parts: Vector[Document])
    case Nest(body: Document)
    case Group(body: Document)
    case Fold(location: TermLocation, label: String, summary: Document, body: Document)

    def ++(other: Document): Document = Sequence(Vector(this, other))
    def nested: Document = Nest(this)
    def grouped: Document = Group(this)

    def goals: Vector[Goal] = this match {
      case goal: Goal => Vector(goal)
      case Sequence(parts) => parts.flatMap(_.goals)
      case Nest(body) => body.goals
      case Group(body) => body.goals
      case Fold(_, _, _, body) => body.goals
      case _ => Vector.empty
    }

    /** Unbounded text rendering keeps mandatory breaks and expands every fold. */
    def plain: String = {
      def render(document: Document, indentation: Int): String = document match {
        case Text(value) => value
        case Goal(_, expectedType, _) => s"(? : ${expectedType.plain})"
        case Line(Some(flat)) => flat
        case Line(None) => "\n" + " " * indentation
        case Sequence(parts) => parts.map(render(_, indentation)).mkString
        case Nest(body) => render(body, indentation + 2)
        case Group(body) => render(body, indentation)
        case Fold(_, _, _, body) => render(body, indentation)
      }
      render(this, 0)
    }
  }

  import Document.*

  private val line: Document = Line(Some(" "))
  private val softline: Document = Line(Some(""))
  private val hardline: Document = Line(None)
  private def text(value: String): Document = Text(value)
  private def parentheses(value: Document, required: Boolean): Document = {
    if required then text("(") ++ value ++ text(")") else value
  }
  private def separated(values: Vector[Document], separator: Document): Document = {
    Sequence(values.zipWithIndex.flatMap { (value, index) =>
      if index == 0 then Vector(value) else Vector(separator, value)
    })
  }
  private def record(fields: Vector[Document]): Document = {
    if fields.isEmpty then text("{}")
    else (text("{") ++ (softline ++ separated(fields, text(",") ++ line)).nested ++ softline ++ text("}")).grouped
  }

  def tpe(value: Type, names: DisplayNames = DisplayNames()): String = typeDocument(value, names).plain

  private def typeDocument(value: Type, names: DisplayNames, context: Int = 0): Document = {
    val precedence = value match {
      case Type.Forall(_) => 0
      case Type.Arrow(_, _) | Type.Implicit(_, _) => 1
      case _ => 2
    }
    def arrow(parameter: Type, symbol: String, result: Type): Document = {
      def tail(value: Type): Document = value match {
        case Type.Arrow(parameter, result) => typeDocument(parameter, names, 2) ++ text(" →") ++ line ++ tail(result)
        case Type.Implicit(parameter, result) => typeDocument(parameter, names, 2) ++ text(" ⇒") ++ line ++ tail(result)
        case _ => typeDocument(value, names, 1)
      }
      (typeDocument(parameter, names, 2) ++ text(s" $symbol") ++ (line ++ tail(result)).nested).grouped
    }
    val document = value match {
      case Type.IntType => text("Int")
      case Type.StringType => text("String")
      case Type.BoolType => text("Bool")
      case Type.Variable(index) => text(names.types.lift(index).getOrElse(s"α[$index]"))
      case Type.Arrow(parameter, result) => arrow(parameter, "→", result)
      case Type.Implicit(parameter, result) => arrow(parameter, "⇒", result)
      case Type.Forall(body) => {
        val nested = names.bindType(None)
        text(s"∀${nested.types.head}. ") ++ typeDocument(body, nested)
      }
      case Type.Record(fields) => record(fields.toVector.sortBy(_._1).map { (label, value) =>
        (text(s"$label:") ++ (line ++ typeDocument(value, names)).nested).grouped
      })
    }
    parentheses(document, precedence < context)
  }

  def term(value: ImplicitTerm, names: DisplayNames = DisplayNames()): String = document(value, names).plain

  def document(value: ImplicitTerm, names: DisplayNames = DisplayNames()): Document = {
    render(value, names, TermLocation(), None)
  }

  private def operatorPrecedence(kind: OpKind): Int = kind match {
    case OpKind.Eq | OpKind.Lt | OpKind.Gt => 1
    case OpKind.Concat => 2
    case OpKind.Add | OpKind.Sub => 3
    case OpKind.Mul => 4
  }

  private def render(
    value: ImplicitTerm, names: DisplayNames, location: TermLocation, hint: Option[String], context: Int = 0
  ): Document = {
    val precedence = value match {
      case ImplicitTerm.Lam(_, _, _) | ImplicitTerm.ImplicitLam(_, _, _) | ImplicitTerm.TypeLam(_, _) |
          ImplicitTerm.Let(_, _, _, _) | ImplicitTerm.InstanceLet(_, _, _, _) | ImplicitTerm.Fix(_, _, _) => 0
      case ImplicitTerm.BinOp(kind, _, _) => operatorPrecedence(kind)
      case ImplicitTerm.App(_, _) => 5
      case ImplicitTerm.ImplicitApp(_, _) | ImplicitTerm.TypeApp(_, _) | ImplicitTerm.Proj(_, _) => 6
      case _ => 7
    }
    def child(value: ImplicitTerm, step: TermChild, context: Int = 0): Document = {
      render(value, names, location / step, None, context)
    }
    def abstraction(value: ImplicitTerm, names: DisplayNames, path: TermLocation): (Vector[Document], Document) = {
      def bind(prefix: Document, body: ImplicitTerm, nested: DisplayNames): (Vector[Document], Document) = {
        val (prefixes, result) = abstraction(body, nested, path / TermChild.Body)
        (prefix +: prefixes, result)
      }
      value match {
        case ImplicitTerm.Lam(annotation, body, nameHint) => {
          val nested = names.bindOrdinary(nameHint)
          bind(text(s"λ(${nested.ordinary.head}: ") ++ typeDocument(annotation, names) ++ text(")."), body, nested)
        }
        case ImplicitTerm.ImplicitLam(annotation, body, nameHint) => {
          val nested = names.bindInstance(nameHint)
          bind(text(s"λ⟨${nested.instances.head}: ") ++ typeDocument(annotation, names) ++ text("⟩."), body, nested)
        }
        case ImplicitTerm.TypeLam(body, nameHint) => {
          val nested = names.bindType(nameHint)
          bind(text(s"Λ${nested.types.head}."), body, nested)
        }
        case _ => (Vector.empty, render(value, names, path, None))
      }
    }
    def application(value: ImplicitTerm, path: TermLocation): (Document, Vector[Document]) = value match {
      case ImplicitTerm.App(function, argument) => {
        val (head, arguments) = application(function, path / TermChild.Function)
        (head, arguments :+ render(argument, names, path / TermChild.Argument, None, 6))
      }
      case _ => (render(value, names, path, None, 5), Vector.empty)
    }
    def binding(
      keyword: String, name: String, nested: DisplayNames, annotation: Type,
      value: ImplicitTerm, body: ImplicitTerm, nameHint: Option[String]
    ): Document = {
      val header = (
        text(s"$keyword $name:") ++ (line ++ typeDocument(annotation, names)).nested ++ line ++ text("=")
      ).grouped
      val bound = render(value, names, location / TermChild.Value, nameHint.map(_ => name))
      val declaration = (header ++ (line ++ bound).nested ++ line ++ text("in")).grouped
      Fold(location, name, (header ++ text(" … in")).grouped, declaration) ++ hardline ++
        render(body, nested, location / TermChild.Body, None)
    }
    val document = value match {
      case ImplicitTerm.Var(index) => text(names.ordinary.lift(index).getOrElse(s"x[$index]"))
      case ImplicitTerm.Instance(index) => text(names.instances.lift(index).getOrElse(s"d[$index]"))
      case ImplicitTerm.Lam(_, _, _) | ImplicitTerm.ImplicitLam(_, _, _) | ImplicitTerm.TypeLam(_, _) => {
        val (prefixes, body) = abstraction(value, names, location)
        (separated(prefixes, line).grouped ++ (line ++ body).nested).grouped
      }
      case ImplicitTerm.App(_, _) => {
        val (head, arguments) = application(value, location)
        (head ++ (line ++ separated(arguments, line)).nested).grouped
      }
      case ImplicitTerm.ImplicitApp(function, argument) => {
        (child(function, TermChild.Function, 6) ++ text("⟨") ++
          (softline ++ child(argument, TermChild.Argument)).nested ++ softline ++ text("⟩")).grouped
      }
      case ImplicitTerm.TypeApp(function, argument) => {
        (child(function, TermChild.Function, 6) ++ text("[") ++
          (softline ++ typeDocument(argument, names)).nested ++ softline ++ text("]")).grouped
      }
      case ImplicitTerm.Let(annotation, value, body, nameHint) => {
        val nested = names.bindOrdinary(nameHint)
        binding("let", nested.ordinary.head, nested, annotation, value, body, nameHint)
      }
      case ImplicitTerm.InstanceLet(annotation, value, body, nameHint) => {
        val nested = names.bindInstance(nameHint)
        binding("instance", nested.instances.head, nested, annotation, value, body, nameHint)
      }
      case ImplicitTerm.Fix(annotation, body, nameHint) => {
        val nested = names.bindOrdinary(nameHint)
        val prefix = text(s"fix ${nested.ordinary.head}: ") ++ typeDocument(annotation, names) ++ text(".")
        (prefix ++ (line ++ render(body, nested, location / TermChild.Body, None)).nested).grouped
      }
      case ImplicitTerm.Need(goal, nameHint) => Goal(location, typeDocument(goal, names), hint.orElse(nameHint))
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
      case ImplicitTerm.Record(fields) => record(fields.toVector.sortBy(_._1).map { (label, value) =>
        val field = render(value, names, location / TermChild.Field(label), Some(label))
        (text(s"$label =") ++ (line ++ field).nested).grouped
      })
      case ImplicitTerm.Proj(receiver, label) => child(receiver, TermChild.Receiver, 6) ++ text(s".$label")
      case ImplicitTerm.BinOp(kind, left, right) => {
        (child(left, TermChild.Left, precedence) ++
          (line ++ text(s"$kind ") ++ child(right, TermChild.Right, precedence + 1)).nested).grouped
      }
    }
    parentheses(document, precedence < context)
  }

}
