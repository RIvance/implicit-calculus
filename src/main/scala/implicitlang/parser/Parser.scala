package implicitlang.parser

import implicitlang.{Error as LanguageError}
import implicitlang.syntax.{Expr, TypeExpr, OpKind}

import implicitlang.util.Result

import scala.util.parsing.combinator.JavaTokenParsers

/** Combinators construct Expr directly. Declarations are scoped expression constructors, not a module AST. */
final class Parser extends JavaTokenParsers {
  override protected val whiteSpace = """(\s+|//[^\r\n]*|(?s:/\*.*?\*/))+""".r

  private val reserved = Set(
    "let", "def", "impl", "implicit", "using", "trait", "type", "where", "and", "in", "forall",
    "summon", "Int", "String", "Bool", "true", "false"
  )

  private def keyword(word: String): Parser[String] = {
    (java.util.regex.Pattern.quote(word) + """(?![\p{L}\p{N}_$])""").r
  }

  private def name: Parser[String] = ident ^? (
    { case value if !reserved.contains(value) => value },
    value => s"Reserved word '$value' cannot be a name"
  )

  /** Inspect the same trivia that token parsers skip, retaining its declaration boundaries. */
  private lazy val trivia: Parser[String] = Parser { input =>
    val end = handleWhiteSpace(input.source, input.offset)
    Success(input.source.subSequence(input.offset, end).toString, input.drop(end - input.offset))
  }

  private def hasNewline(text: String): Boolean = text.exists(character => character == '\n' || character == '\r')

  private lazy val inlineTrivia: Parser[String] = trivia ^? (
    { case text if !hasNewline(text) => text },
    _ => "Expected continuation on the same line"
  )

  private lazy val newline: Parser[Unit] = trivia ^? (
    { case text if hasNewline(text) => () },
    _ => "Expected a newline"
  )

  private def sameLine[A](parser: => Parser[A]): Parser[A] = inlineTrivia ~> parser

  private def adjacent[A](parser: => Parser[A]): Parser[A] = {
    (trivia ^? { case text if text.isEmpty => () }) ~> parser
  }

  private lazy val fieldSeparator: Parser[Unit] = (("," | ";") ^^^ ()) | newline

  private def fields[A](field: => Parser[A]): Parser[List[A]] = {
    "{" ~> repsep(field, fieldSeparator) <~ opt(fieldSeparator) <~ "}"
  }

  private lazy val tpe: Parser[TypeExpr] = universalType | arrowType

  private lazy val universalType: Parser[TypeExpr] =
    (keyword("forall") ~> rep1sep(name, ",") <~ ".") ~ tpe ^^ {
      case parameters ~ body => parameters.foldRight(body)(TypeExpr.Forall.apply)
    }

  private lazy val arrowType: Parser[TypeExpr] = typeAtom ~ opt(("->" | "~>") ~ tpe) ^^ {
    case parameter ~ Some("->" ~ result) => TypeExpr.Arrow(parameter, result)
    case parameter ~ Some(_ ~ result) => TypeExpr.Implicit(parameter, result)
    case parameter ~ None => parameter
  }

  private lazy val typeAtom: Parser[TypeExpr] =
    (keyword("Int") ^^^ TypeExpr.IntType) |
    (keyword("String") ^^^ TypeExpr.StringType) |
    (keyword("Bool") ^^^ TypeExpr.BoolType) |
    ("(" ~> tpe <~ ")") |
    (fields(name ~ (":" ~> tpe)) ^^ { items =>
      TypeExpr.Record(items.map { case label ~ fieldType => label -> fieldType }.toVector)
    }) |
    (name ~ opt("[" ~> rep1sep(tpe, ",") <~ "]") ^^ {
      case identifier ~ arguments => TypeExpr.Named(identifier, arguments.getOrElse(Nil).toVector)
    })

  private lazy val expression: Parser[Expr] =
    typeBinding | functionBinding | implementation | ordinaryLet | lambda | ascription

  private lazy val continuation: Parser[Expr] =
    (((keyword("in") | ";") ^^^ ()) | newline) ~> expression

  private lazy val typeBinding: Parser[Expr] =
    ((keyword("type") | keyword("trait")) ~> name) ~ opt("[" ~> rep1sep(name, ",") <~ "]") ~
      ("=" ~> tpe) ~ continuation ^^ {
        case identifier ~ parameters ~ definition ~ body =>
          Expr.TypeLet(identifier, parameters.getOrElse(Nil).toVector, definition, body)
      }

  private lazy val genericHeader: Parser[(List[String], List[TypeExpr])] =
    ("[" ~> repsep(name, ",")) ~ opt(keyword("where") ~> rep1sep(tpe, "," | keyword("and"))) <~ "]" ^^ {
      case parameters ~ constraints => parameters -> constraints.getOrElse(Nil)
    }

  // Each parameter constructs its own binder, so mixed lists retain their source order and kind.
  private lazy val parameter: Parser[Expr => Expr] =
    opt(keyword("implicit")) ~ name ~ (":" ~> tpe) ^^ {
      case Some(_) ~ identifier ~ annotation => (body: Expr) => Expr.ImplicitLam(identifier, annotation, body)
      case None ~ identifier ~ annotation => (body: Expr) => Expr.Lam(identifier, annotation, body)
    }

  private lazy val parameters: Parser[List[Expr => Expr]] = "(" ~> rep1sep(parameter, ",") <~ ")"

  private lazy val parameterGroups: Parser[List[Expr => Expr]] = rep1(parameters) ^^ (_.flatten)

  private def abstractParameters(binders: List[Expr => Expr], body: Expr): Expr = {
    binders.foldRight(body)((bind, result) => bind(result))
  }

  private def abstractImplicit(
    header: Option[(List[String], List[TypeExpr])],
    body: Expr
  ): Expr = {
    val (variables, constraints) = header.getOrElse(Nil -> Nil)
    val qualified = constraints.zipWithIndex.foldRight(body) { case ((annotation, index), result) =>
      Expr.ImplicitLam(s"@given$index", annotation, result)
    }
    variables.foldRight(qualified)(Expr.TypeLam.apply)
  }

  private def qualifyType(
    header: Option[(List[String], List[TypeExpr])],
    result: TypeExpr
  ): TypeExpr = {
    val (variables, constraints) = header.getOrElse(Nil -> Nil)
    variables.foldRight(constraints.foldRight(result)(TypeExpr.Implicit.apply))(TypeExpr.Forall.apply)
  }

  private lazy val functionBinding: Parser[Expr] =
    (keyword("def") ~> name) ~ opt(genericHeader) ~ parameterGroups ~ (":" ~> tpe) ~
      ("=" ~> expression) ~ continuation ^^ {
        case identifier ~ header ~ arguments ~ resultType ~ value ~ body => {
          val parameterized = abstractParameters(arguments, Expr.Ascribe(value, resultType))
          Expr.Let(identifier, abstractImplicit(header, parameterized), body)
        }
      }

  // The colon distinguishes a binder header from an anonymous implementation's target type, e.g. C[A].
  private lazy val implementationHeader: Parser[(String, Option[(List[String], List[TypeExpr])])] = (
    (name ~ opt(genericHeader) ^^ { case identifier ~ header => identifier -> header }) |
    (genericHeader ^^ { header => "@anonymous" -> Some(header) })
  ) <~ ":"

  private lazy val implementation: Parser[Expr] =
    (keyword("impl") ~> opt(implementationHeader)) ~ tpe ~ ("=" ~> expression) ~ continuation ^^ {
      case header ~ annotation ~ value ~ body => {
        val (identifier, generics) = header.getOrElse("@anonymous" -> None)
        Expr.Impl(identifier, qualifyType(generics, annotation), abstractImplicit(generics, value), body)
      }
    }

  private lazy val ordinaryLet: Parser[Expr] =
    (keyword("let") ~> name) ~ opt(":" ~> tpe) ~ ("=" ~> expression) ~ continuation ^^ {
      case identifier ~ annotation ~ value ~ body => {
        val checked = annotation.fold(value)(Expr.Ascribe(value, _))
        Expr.Let(identifier, checked, body)
      }
    }

  private lazy val lambda: Parser[Expr] =
    parameterGroups ~ ("=>" ~> expression) ^^ {
      case arguments ~ body => abstractParameters(arguments, body)
    } |
    (("[" ~> rep1sep(name, ",") <~ "]") ~ ("=>" ~> expression) ^^ {
      case variables ~ body => variables.foldRight(body)(Expr.TypeLam.apply)
    })

  private lazy val ascription: Parser[Expr] = comparison ~ opt(sameLine(":" ~> tpe)) ^^ {
    case value ~ Some(annotation) => Expr.Ascribe(value, annotation)
    case value ~ None => value
  }

  private def binary(token: Parser[String], kind: OpKind): Parser[(Expr, Expr) => Expr] = {
    sameLine(token) ^^^ { (left: Expr, right: Expr) => Expr.BinOp(kind, left, right) }
  }

  private lazy val comparison: Parser[Expr] = chainl1(
    concatenation,
    binary("==", OpKind.Eq) | binary("<", OpKind.Lt) | binary(">", OpKind.Gt)
  )

  private lazy val concatenation: Parser[Expr] = chainl1(addition, binary("++", OpKind.Concat))

  private lazy val addition: Parser[Expr] = chainl1(
    multiplication,
    binary("""\+(?!\+)""".r, OpKind.Add) | binary("-", OpKind.Sub)
  )

  private lazy val multiplication: Parser[Expr] = chainl1(application, binary("*", OpKind.Mul))

  private lazy val application: Parser[Expr] = postfix ~ rep(
    sameLine(suffix) | (spacedArgument ^^ { argument => (function: Expr) => Expr.App(function, argument) })
  ) ^^ {
    case initial ~ operations => operations.foldLeft(initial)((value, operation) => operation(value))
  }

  // A bare negative argument would conflict with subtraction; use f(-1) or f (-1).
  private lazy val spacedArgument: Parser[Expr] =
    (inlineTrivia ^? { case text if text.nonEmpty => () }) ~> not("-") ~> postfix

  // Adjacent suffixes belong to an argument: f g(x) = f(g(x)), but f g (x) = f(g)(x).
  private lazy val postfix: Parser[Expr] = atom ~ rep(adjacent(suffix)) ^^ {
    case initial ~ operations => operations.foldLeft(initial)((value, operation) => operation(value))
  }

  // A suffix is an AST transformation, not a second syntax representation.
  private lazy val suffix: Parser[Expr => Expr] =
    (("(" ~> rep1sep(argument, ",") <~ ")") ^^ { applications =>
      (function: Expr) => applications.foldLeft(function)((current, applyArgument) => applyArgument(current))
    }) |
    (("[" ~> rep1sep(tpe, ",") <~ "]") ^^ { arguments =>
      (function: Expr) => arguments.foldLeft(function)(Expr.TypeApp.apply)
    }) |
    (braced ^^ { value => (function: Expr) => Expr.App(function, value) }) |
    (("." ~> name) ^^ { label => (receiver: Expr) => Expr.Select(receiver, label) })

  // Like implicit on parameters, using belongs to one argument rather than the entire list.
  private lazy val argument: Parser[Expr => Expr] = opt(keyword("using")) ~ expression ^^ {
    case Some(_) ~ value => (function: Expr) => Expr.ImplicitApp(function, value)
    case None ~ value => (function: Expr) => Expr.App(function, value)
  }

  private lazy val record: Parser[Expr] =
    fields(name ~ ("=" ~> expression)) ^^ { items =>
      Expr.Record(items.map { case label ~ value => label -> value }.toVector)
    }

  /** Braces either form a record literal or group one expression without adding an AST node. */
  private lazy val braced: Parser[Expr] = record | ("{" ~> expression <~ "}")

  private lazy val atom: Parser[Expr] =
    ((tpe <~ sameLine("::")) ~ name ^^ {
      case annotation ~ label => Expr.Select(Expr.Need(annotation), label)
    }) |
    ((keyword("summon") ~> "[" ~> tpe <~ "]") ^^ Expr.Need.apply) |
    ("(" ~> expression <~ ")") |
    braced |
    (keyword("true") ^^^ Expr.BoolLit(true)) |
    (keyword("false") ^^^ Expr.BoolLit(false)) |
    (stringLiteral ^^ { text => Expr.StringLit(decodeString(text)) }) |
    (wholeNumber ^^ { text => Expr.IntLit(BigInt(text)) }) |
    (name ^^ Expr.Var.apply)

  private def decodeString(text: String): String = {
    val input = text.substring(1, text.length - 1)
    val output = new StringBuilder
    var index = 0
    while index < input.length do {
      if input.charAt(index) != '\\' then {
        output.append(input.charAt(index))
        index += 1
      } else {
        input.charAt(index + 1) match {
          case 'u' => {
            output.append(Integer.parseInt(input.substring(index + 2, index + 6), 16).toChar)
            index += 6
          }
          case escaped => {
            val character = escaped match {
              case 'b' => '\b'
              case 'f' => '\f'
              case 'n' => '\n'
              case 'r' => '\r'
              case 't' => '\t'
              case other => other
            }
            output.append(character)
            index += 2
          }
        }
      }
    }
    output.toString
  }

  def parse(source: String): Result[Expr, LanguageError] = parseAll(expression <~ opt(";"), source) match {
    case Success(value, _) => Result.Ok(value)
    case failure: NoSuccess => {
      Result.Err(LanguageError.Parse(failure.msg, failure.next.pos.line, failure.next.pos.column))
    }
  }
}
