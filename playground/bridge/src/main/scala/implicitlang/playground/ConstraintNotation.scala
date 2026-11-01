package implicitlang.playground

import implicitlang.`implicit`.DisplayNames
import implicitlang.smt.*
import implicitlang.smt.Smt.*
import implicitlang.synthesis.{ConstraintRule, SynthesisAttempt}

/** Presentation of a query's structured formulas, with its record vocabulary and lexical type scope. */
private[playground] final class ConstraintNotation(attempt: SynthesisAttempt, names: DisplayNames) {
  import ConstraintNotation.*

  private val labels = attempt.recordLabels
  private val fixed = attempt.problem.constraints.collect {
    case constraint if constraint.origin.rule == ConstraintRule.Search => constraint.formula
  }.collect {
    case Application("=", _, Vector(constant: SmtConstant[?], IntegerLiteral(value))) => (constant, value)
  }

  val assumptions: Vector[Formula] = fixed.map { (constant, value) =>
    val variable = symbol(constant)
    Formula(s"${variable.text} = $value", s"${variable.latex} = $value")
  }

  /** Fixed search choices are premises of this view; discharged clauses remain in the SMT-LIB view. */
  def apply(expression: SmtExpr[Boolean]): Option[Statement] = {
    if truth(expression).contains(true) then None
    else expression match {
      case Application("=>", _, Vector(premise, conclusion)) if truth(premise).contains(true) => {
        Some(Statement(None, render(conclusion, names).formula))
      }
      case Application("=>", _, Vector(premise, conclusion)) => {
        Some(Statement(Some(render(premise, names).formula), render(conclusion, names).formula))
      }
      case Application("not", _, Vector(premise)) => {
        Some(Statement(Some(render(premise, names).formula), Formula("⊥", "\\bot")))
      }
      case _ => Some(Statement(None, render(expression, names).formula))
    }
  }

  private def integer(expression: SmtExpr[?]): Option[BigInt] = expression match {
    case IntegerLiteral(value) => Some(value)
    case constant: SmtConstant[?] => fixed.collectFirst { case (`constant`, value) => value }
    case _ => None
  }

  private def truth(expression: SmtExpr[?]): Option[Boolean] = expression match {
    case BooleanLiteral(value) => Some(value)
    case Application("not", _, Vector(value)) => truth(value).map(!_)
    case Application("and", _, arguments) => {
      val values = arguments.map(truth)
      if values.contains(Some(false)) then Some(false)
      else if values.forall(_.contains(true)) then Some(true) else None
    }
    case Application("or", _, arguments) => {
      val values = arguments.map(truth)
      if values.contains(Some(true)) then Some(true)
      else if values.forall(_.contains(false)) then Some(false) else None
    }
    case Application("=>", _, Vector(premise, conclusion)) => (truth(premise), truth(conclusion)) match {
      case (Some(false), _) | (_, Some(true)) => Some(true)
      case (Some(true), value) => value
      case _ => None
    }
    case Application(operator @ ("=" | "<" | ">" | "<=" | ">="), _, Vector(left, right)) => {
      for {
        first <- integer(left)
        second <- integer(right)
      } yield operator match {
        case "=" => first == second
        case "<" => first < second
        case ">" => first > second
        case "<=" => first <= second
        case ">=" => first >= second
      }
    }
    case _ => None
  }

  private def instance(index: Int): Rendered = {
    val name = names.instances(index)
    Rendered(name, s"\\mathtt{${escape(name)}}")
  }

  private def symbol(constant: SmtConstant[?]): Rendered = QueryVariable.read(constant) match {
    case Some(QueryVariable.Root) => Rendered("r", "r")
    case Some(QueryVariable.Head(spine)) => indexed("h", spine)
    case Some(QueryVariable.Length(spine)) => indexed("ℓ", spine)
    case Some(QueryVariable.ResultType(spine)) => indexed("τ", spine)
    case Some(QueryVariable.Argument(spine, position)) => indexed("a", spine, position)
    case Some(QueryVariable.IntermediateType(spine, head, position)) => {
      val tpe = indexed("τ", spine, position + 1)
      val chosen = instance(head)
      Rendered(s"${tpe.text}[${chosen.text}]", s"${tpe.latex}^{\\scriptscriptstyle ${chosen.latex}}")
    }
    case None => identifier(constant.name)
  }

  private def argumentPart(expression: SmtExpr[?], isType: Boolean, scope: DisplayNames): Rendered = {
    QueryVariable.read(expression) match {
      case Some(QueryVariable.Argument(spine, position)) => indexed(if isType then "B" else "k", spine, position)
      case _ => call(
        if isType then Rendered("type", "\\operatorname{type}") else Rendered("ref", "\\operatorname{ref}"),
        Vector(render(expression, scope))
      )
    }
  }

  private def conjunction(arguments: Vector[SmtExpr[?]], scope: DisplayNames): Rendered = {
    def clauses(remaining: Vector[SmtExpr[?]]): Vector[Rendered] = remaining.headOption match {
      case Some(Application("<=", _, Vector(IntegerLiteral(lower), value))) => {
        val upperBound = remaining.tail.zipWithIndex.collectFirst {
          case (Application("<", _, Vector(`value`, IntegerLiteral(upper))), index) => (upper, index + 1)
        }
        upperBound match {
          case Some((upper, index)) => {
            val operand = render(value, scope)
            val range = QueryVariable.read(value) match {
              case Some(QueryVariable.Head(_)) if lower == 0 && upper == names.instances.size => {
                Rendered(s"${operand.text} ∈ Δ", s"${operand.latex} \\in \\Delta", 40)
              }
              case _ => Rendered(s"$lower ≤ ${operand.text} < $upper", s"$lower \\le ${operand.latex} < $upper", 40)
            }
            range +: clauses(remaining.tail.patch(index - 1, Vector.empty, 1))
          }
          case None => render(remaining.head, scope, 31) +: clauses(remaining.tail)
        }
      }
      case Some(value) => render(value, scope, 31) +: clauses(remaining.tail)
      case None => Vector.empty
    }
    val values = clauses(arguments)
    if values.size == 1 then values.head else join(values, " ∧ ", " \\land ").copy(precedence = 30)
  }

  private def render(expression: SmtExpr[?], scope: DisplayNames, context: Int = 0): Rendered = {
    def recur(value: SmtExpr[?], precedence: Int = 0): Rendered = render(value, scope, precedence)
    def infix(operator: String, latex: String, arguments: Vector[SmtExpr[?]], precedence: Int): Rendered = {
      val operands = arguments.map(recur(_, precedence + 1))
      join(operands, s" $operator ", s" $latex ").copy(precedence = precedence)
    }
    def arrow(parameter: SmtExpr[?], result: SmtExpr[?], implicitArrow: Boolean): Rendered = {
      val left = recur(parameter, 81)
      val right = recur(result, 80)
      val symbol = if implicitArrow then "⇒" else "→"
      val latex = if implicitArrow then "\\Rightarrow" else "\\to"
      Rendered(s"${left.text} $symbol ${right.text}", s"${left.latex} $latex ${right.latex}", 80)
    }

    val rendered = truth(expression) match {
      case Some(true) => Rendered("⊤", "\\top")
      case Some(false) => Rendered("⊥", "\\bot")
      case None => expression match {
        case constant: SmtConstant[?] => symbol(constant)
        case BooleanLiteral(value) => Rendered(if value then "⊤" else "⊥", if value then "\\top" else "\\bot")
        case IntegerLiteral(value) => Rendered(value.toString, value.toString)
        case ConstructorTest(constructor, value) if constructor.name == "TypeArgument" => {
          val argument = recur(value)
          val tpe = argumentPart(value, true, scope)
          Rendered(s"${argument.text} = [${tpe.text}]", s"${argument.latex} = [${tpe.latex}]", 40)
        }
        case ConstructorTest(constructor, value) if constructor.name == "ImplicitArgument" => {
          val argument = recur(value)
          val reference = argumentPart(value, false, scope)
          Rendered(
            s"${argument.text} = ⟨z[${reference.text}]⟩",
            s"${argument.latex} = \\langle z_{${reference.latex}}\\rangle", 40
          )
        }
        case ConstructorTest(constructor, value) => {
          val predicate = if constructor.name == "Forall" then Rendered("Poly", "\\operatorname{Poly}")
            else Rendered(s"is${constructor.name}", s"\\operatorname{is${escape(constructor.name)}}")
          call(predicate, Vector(recur(value)))
        }
        case Application("and", _, arguments) => {
          val meaningful = flatten("and", arguments).filterNot(value => truth(value).contains(true))
          conjunction(meaningful, scope)
        }
        case Application("or", _, arguments) => {
          val meaningful = flatten("or", arguments).filterNot(value => truth(value).contains(false))
          if meaningful.size == 1 then recur(meaningful.head)
          else infix("∨", "\\lor", meaningful, 20)
        }
        case Application("=>", _, Vector(left, right)) => {
          val premise = recur(left, 11)
          val conclusion = recur(right, 10)
          Rendered(
            s"${premise.text} ⟹ ${conclusion.text}", s"${premise.latex} \\Longrightarrow ${conclusion.latex}", 10
          )
        }
        case Application("not", _, Vector(value)) => {
          val operand = recur(value, 60)
          Rendered(s"¬${operand.text}", s"\\neg ${operand.latex}", 60)
        }
        case Application("=", _, Vector(head, IntegerLiteral(index)))
            if QueryVariable.read(head).exists(_.isInstanceOf[QueryVariable.Head]) &&
              index.isValidInt && index >= 0 && index < names.instances.size => {
          val selected = recur(head)
          val chosen = instance(index.toInt)
          Rendered(s"${selected.text} = ${chosen.text}", s"${selected.latex} = ${chosen.latex}", 40)
        }
        case Application("=", _, arguments) => infix("=", "=", arguments, 40)
        case Application("<", _, arguments) => infix("<", "<", arguments, 40)
        case Application(">", _, arguments) => infix(">", ">", arguments, 40)
        case Application("<=", _, arguments) => infix("≤", "\\le", arguments, 40)
        case Application(">=", _, arguments) => infix("≥", "\\ge", arguments, 40)
        case Application("+", _, arguments) => infix("+", "+", flatten("+", arguments), 50)
        case Application("-", _, Vector(value)) => {
          val operand = recur(value, 61)
          Rendered(s"−${operand.text}", s"-${operand.latex}", 60)
        }
        case Application("-", _, arguments) => infix("−", "-", arguments, 50)
        case Application("ite", _, Vector(condition, yes, no)) => {
          val predicate = recur(condition)
          val whenTrue = recur(yes)
          val whenFalse = recur(no)
          Rendered(
            s"if ${predicate.text} then ${whenTrue.text} else ${whenFalse.text}",
            s"\\begin{cases}${whenTrue.latex} & \\text{if } ${predicate.latex} \\\\ " +
              s"${whenFalse.latex} & \\text{otherwise}\\end{cases}"
          )
        }
        case Application("Integer", SmtSort.Data("Type"), Vector()) => Rendered("Int", "\\mathsf{Int}")
        case Application("String", SmtSort.Data("Type"), Vector()) => Rendered("String", "\\mathsf{String}")
        case Application("Boolean", SmtSort.Data("Type"), Vector()) => Rendered("Bool", "\\mathsf{Bool}")
        case Application("Variable", SmtSort.Data("Type"), Vector(IntegerLiteral(index))) => {
          scope.types.lift(index.toInt).filter(_ => index.isValidInt && index >= 0) match {
            case Some(name) => typeName(name)
            case None => Rendered(s"^$index", s"\\underline{$index}")
          }
        }
        case Application("Variable", SmtSort.Data("Type"), Vector(index)) => {
          val value = recur(index)
          Rendered(s"^(${value.text})", s"\\underline{${value.latex}}")
        }
        case Application("Arrow", SmtSort.Data("Type"), Vector(parameter, result)) => arrow(parameter, result, false)
        case Application("Implicit", SmtSort.Data("Type"), Vector(parameter, result)) => arrow(parameter, result, true)
        case Application("Forall", SmtSort.Data("Type"), Vector(body)) => {
          val nested = scope.bindType(None)
          val binder = typeName(nested.types.head)
          val result = render(body, nested, 70)
          Rendered(s"∀${binder.text}. ${result.text}", s"\\forall ${binder.latex}.\\, ${result.latex}", 70)
        }
        case Application("Record", SmtSort.Data("Type"), Vector(fields)) => {
          val (entries, tail) = recordFields(fields, scope)
          val body = join(entries, ", ", ",\\;\\allowbreak ")
          val remainder = tail.fold(Rendered("", ""))(value => Rendered(s" | ${value.text}", s" \\mid ${value.latex}"))
          Rendered(s"{${body.text}${remainder.text}}", s"\\{${body.latex}${remainder.latex}\\}")
        }
        case Application("type_argument", _, Vector(argument)) => argumentPart(argument, true, scope)
        case Application("implicit_argument", _, Vector(argument)) => argumentPart(argument, false, scope)
        case Application("well_formed", _, Vector(value, depth, height)) => {
          val tpe = recur(value)
          val scopeDepth = recur(depth)
          val bound = recur(height)
          Rendered(
            s"Valid[${scopeDepth.text}, ${bound.text}](${tpe.text})",
            s"\\operatorname{Valid}_{${scopeDepth.latex},${bound.latex}}(${tpe.latex})"
          )
        }
        case Application("shift", SmtSort.Data("Type"), Vector(value, cutoff, amount)) => {
          val tpe = recur(value, 100)
          val lower = recur(cutoff)
          val upper = recur(amount)
          Rendered(
            s"↑[${lower.text}, ${upper.text}] ${tpe.text}",
            s"\\uparrow^{${upper.latex}}_{${lower.latex}} ${tpe.latex}"
          )
        }
        case Application("instantiate", SmtSort.Data("Type"), Vector(
          Application("body", _, Vector(polymorphic)), argument, IntegerLiteral(zero)
        )) if zero == 0 => {
          val tpe = recur(polymorphic, 100)
          val parameter = recur(argument)
          Rendered(s"${tpe.text}[${parameter.text}]", s"${tpe.latex}[${parameter.latex}]")
        }
        case Application("instantiate", SmtSort.Data("Type"), Vector(value, argument, depth)) => {
          val index = recur(depth)
          call(Rendered(s"I[${index.text}]", s"I_{${index.latex}}"), Vector(recur(value), recur(argument)))
        }
        case Application(operator, _, arguments) => {
          call(Rendered(operator, s"\\operatorname{${escape(operator)}}"), arguments.map(recur(_)))
        }
      }
    }
    if rendered.precedence < context then Rendered(s"(${rendered.text})", s"(${rendered.latex})") else rendered
  }

  private def recordFields(
    fields: SmtExpr[?], scope: DisplayNames
  ): (Vector[Rendered], Option[Rendered]) = fields match {
    case Application("Empty", SmtSort.Data("Fields"), Vector()) => (Vector.empty, None)
    case Application("Field", SmtSort.Data("Fields"), Vector(label, value, tail)) => {
      val name = label match {
        case IntegerLiteral(index) if index.isValidInt && index >= 0 && index < labels.size => {
          val label = labels(index.toInt)
          Rendered(label, s"\\mathtt{${escape(label)}}")
        }
        case _ => call(Rendered("label", "\\operatorname{label}"), Vector(render(label, scope)))
      }
      val tpe = render(value, scope)
      val (rest, remainder) = recordFields(tail, scope)
      (Rendered(s"${name.text}: ${tpe.text}", s"${name.latex}: ${tpe.latex}") +: rest, remainder)
    }
    case _ => (Vector.empty, Some(render(fields, scope)))
  }
}

private[playground] object ConstraintNotation {
  final case class Formula(text: String, latex: String)
  final case class Statement(premise: Option[Formula], conclusion: Formula)

  private enum QueryVariable {
    case Root
    case Head(spine: Int)
    case Length(spine: Int)
    case ResultType(spine: Int)
    case Argument(spine: Int, position: Int)
    case IntermediateType(spine: Int, head: Int, position: Int)
  }

  private object QueryVariable {
    private val head = "head_([0-9]+)".r
    private val length = "length_([0-9]+)".r
    private val result = "result_([0-9]+)".r
    private val argument = "argument_([0-9]+)_([0-9]+)".r
    private val inferred = "inferred_([0-9]+)_([0-9]+)_([0-9]+)".r

    def read(expression: SmtExpr[?]): Option[QueryVariable] = expression match {
      case SmtConstant("root", SmtSort.Int) => Some(Root)
      case SmtConstant(head(index), SmtSort.Int) => Some(Head(index.toInt))
      case SmtConstant(length(index), SmtSort.Int) => Some(Length(index.toInt))
      case SmtConstant(result(index), SmtSort.Data("Type")) => Some(ResultType(index.toInt))
      case SmtConstant(argument(spine, position), SmtSort.Data("Argument")) => {
        Some(Argument(spine.toInt, position.toInt))
      }
      case SmtConstant(inferred(spine, head, position), SmtSort.Data("Type")) => {
        Some(IntermediateType(spine.toInt, head.toInt, position.toInt))
      }
      case _ => None
    }
  }

  private final case class Rendered(text: String, latex: String, precedence: Int = 100) {
    def formula: Formula = Formula(text, latex)
  }

  private def escape(value: String): String = value.flatMap {
    case '\\' => "\\backslash "
    case character @ ('_' | '{' | '}' | '#' | '$' | '%' | '&') => s"\\$character"
    case '^' => "\\textasciicircum "
    case '~' => "\\textasciitilde "
    case character => character.toString
  }

  private val numberedType = "(.+?)([0-9]+)".r

  private def subscript(value: String): String = value.map {
    case character if character.isDigit => "₀₁₂₃₄₅₆₇₈₉"(character - '0')
    case character => character
  }

  private def typeName(name: String): Rendered = name match {
    case numberedType(base, index) => Rendered(s"$base${subscript(index)}", s"\\mathit{${escape(base)}}_{$index}")
    case _ => Rendered(name, s"\\mathit{${escape(name)}}")
  }

  private def indexed(name: String, indices: Int*): Rendered = {
    val latex = name match {
      case "τ" => "\\tau"
      case "ℓ" => "\\ell"
      case _ => name
    }
    Rendered(s"$name${subscript(indices.mkString(","))}", s"${latex}_{${indices.mkString(",")}}")
  }

  private def identifier(name: String): Rendered = {
    val parts = name.split('_').toVector
    val indices = parts.reverse.takeWhile(value => value.nonEmpty && value.forall(_.isDigit)).reverse
    if indices.isEmpty then Rendered(name, s"\\texttt{${escape(name)}}")
    else {
      val base = parts.dropRight(indices.size).mkString("_")
      Rendered(s"$base${subscript(indices.mkString(","))}", s"\\texttt{${escape(base)}}_{${indices.mkString(",")}}")
    }
  }

  private def join(values: Vector[Rendered], separator: String, latexSeparator: String): Rendered = {
    Rendered(values.map(_.text).mkString(separator), values.map(_.latex).mkString(latexSeparator))
  }

  private def call(function: Rendered, arguments: Vector[Rendered]): Rendered = {
    if arguments.isEmpty then function
    else {
      val body = join(arguments, ", ", ",\\,\\allowbreak ")
      Rendered(s"${function.text}(${body.text})", s"${function.latex}(${body.latex})")
    }
  }

  private def flatten(operator: String, values: Vector[SmtExpr[?]]): Vector[SmtExpr[?]] = values.flatMap {
    case Application(`operator`, _, arguments) => flatten(operator, arguments)
    case value => Vector(value)
  }
}
