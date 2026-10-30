package implicitlang.surface

import implicitlang.core.OpKind
import implicitlang.diagnostic.{Error as LanguageError}
import implicitlang.util.Result

import scala.util.parsing.combinator.JavaTokenParsers

object Parser {
  val keywords: Set[String] = Set(
    "let", "def", "implement", "instance", "given", "implicit", "using", "trait", "type", "where", "and", "in",
    "forall", "summon", "Int", "String", "Bool", "true", "false"
  )
}

/** A program has a declaration prefix and a final expression; let and given bind within expressions. */
final class Parser extends JavaTokenParsers {
  override protected val whiteSpace = """(\s+|//[^\r\n]*|/\*[\s\S]*?\*/)+""".r

  private def keyword(word: String): Parser[String] = {
    (java.util.regex.Pattern.quote(word) + """(?![\p{L}\p{N}_$])""").r
  }

  private def name: Parser[String] = ident ^? (
    { case value if !implicitlang.surface.Parser.keywords.contains(value) => value },
    value => s"Reserved word '$value' cannot be a name"
  )

  /** Inspect the same trivia that token parsers skip, retaining its expression boundaries. */
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

  private def fields[A](field: => Parser[(String, A)]): Parser[Map[String, A]] = {
    ("{" ~> repsep(field, fieldSeparator) <~ opt(fieldSeparator) <~ "}") ^? (
      { case items if items.map(_._1).distinct.size == items.size => items.toMap },
      _ => "Record field labels must be distinct"
    )
  }

  private lazy val traitReference: Parser[TraitRef] =
    name ~ opt("[" ~> rep1sep(tpe, ",") <~ "]") ^^ {
      case identifier ~ arguments => TraitRef(identifier, arguments.getOrElse(Nil).toVector)
    }

  private lazy val tpe: Parser[SurfaceType] = universalType | arrowType

  private lazy val universalType: Parser[SurfaceType] =
    (keyword("forall") ~> rep1sep(name, ",") <~ ".") ~ tpe ^^ {
      case parameters ~ body => parameters.foldRight(body)(SurfaceType.Forall.apply)
    }

  private lazy val arrowType: Parser[SurfaceType] = typeAtom ~ opt(("->" | "~>") ~ tpe) ^^ {
    case parameter ~ Some("->" ~ result) => SurfaceType.Arrow(parameter, result)
    case parameter ~ Some(_ ~ result) => SurfaceType.Implicit(parameter, result)
    case parameter ~ None => parameter
  }

  private lazy val typeAtom: Parser[SurfaceType] =
    (keyword("Int") ^^^ SurfaceType.IntType) |
    (keyword("String") ^^^ SurfaceType.StringType) |
    (keyword("Bool") ^^^ SurfaceType.BoolType) |
    ("(" ~> tpe <~ ")") |
    (fields(name ~ (":" ~> tpe) ^^ { case label ~ fieldType => label -> fieldType }) ^^ SurfaceType.Record.apply) |
    (traitReference ^^ { reference => SurfaceType.Named(reference.name, reference.arguments) })

  private lazy val separator: Parser[Unit] = ((keyword("in") | ";") ^^^ ()) | newline

  private lazy val expression: Parser[SurfaceExpr] = ordinaryLet | givenBinding | lambda | ascription

  private lazy val program: Parser[Program] = rep(declaration <~ separator) ~ expression ^^ {
    case declarations ~ body => Program(declarations.toVector, body)
  }

  private lazy val declaration: Parser[Declaration] =
    typeDeclaration | functionDeclaration | implementation | instanceDeclaration

  private lazy val typeDeclaration: Parser[Declaration] =
    (keyword("type") | keyword("trait")) ~ name ~ opt("[" ~> rep1sep(name, ",") <~ "]") ~
      ("=" ~> tpe) ^^ {
        case kind ~ identifier ~ parameters ~ definition => {
          val names = parameters.getOrElse(Nil).toVector
          if kind == "trait" then Declaration.Trait(identifier, names, definition)
          else Declaration.TypeAlias(identifier, names, definition)
        }
      }

  private lazy val typeParameters: Parser[TypeParameters] =
    ("[" ~> repsep(name, ",")) ~ opt(keyword("where") ~> rep1sep(traitReference, keyword("and"))) <~ "]" ^^ {
      case parameters ~ constraints => TypeParameters(parameters.toVector, constraints.getOrElse(Nil).toVector)
    }

  // Each parameter constructs its own binder, so mixed lists retain their source order and kind.
  private lazy val parameter: Parser[Parameter] =
    opt(keyword("implicit")) ~ name ~ (":" ~> tpe) ^^ {
      case Some(_) ~ identifier ~ annotation => Parameter(identifier, annotation, ParameterKind.Implicit)
      case None ~ identifier ~ annotation => Parameter(identifier, annotation, ParameterKind.Ordinary)
    }

  private lazy val parameters: Parser[List[Parameter]] = "(" ~> rep1sep(parameter, ",") <~ ")"

  private def abstractParameters(binders: List[Parameter], body: SurfaceExpr): SurfaceExpr = {
    binders.foldRight(body)((parameter, result) => parameter.abstractValue(result))
  }

  private lazy val functionDeclaration: Parser[Declaration] =
    (keyword("def") ~> name) ~ opt(typeParameters) ~ rep(parameters) ~ (":" ~> tpe) ~ ("=" ~> expression) ^^ {
      case identifier ~ typeParameters ~ arguments ~ resultType ~ value => {
        Declaration.Def(
          identifier, typeParameters.getOrElse(TypeParameters.empty), arguments.flatten.toVector, resultType, value
        )
      }
    }

  private lazy val implementation: Parser[Declaration] =
    (keyword("implement") ~> opt(typeParameters)) ~ traitReference ~ ("=" ~> expression) ^^ {
      case typeParameters ~ annotation ~ value => {
        Declaration.Implement(typeParameters.getOrElse(TypeParameters.empty), annotation, value)
      }
    }

  private lazy val instanceDeclaration: Parser[Declaration] =
    (keyword("instance") ~> name) ~ opt(typeParameters) ~ rep(parameters) ~ (":" ~> tpe) ~ ("=" ~> expression) ^^ {
      case identifier ~ typeParameters ~ groups ~ resultType ~ value => {
        val arguments = groups.flatten
        val annotation = arguments.foldRight(resultType)((parameter, result) => parameter.functionType(result))
        Declaration.Instance(
          identifier, typeParameters.getOrElse(TypeParameters.empty), annotation, abstractParameters(arguments, value)
        )
      }
    }

  private lazy val givenBinding: Parser[SurfaceExpr] =
    (keyword("given") ~> name) ~ (":" ~> tpe) ~ ("=" ~> expression) ~ (separator ~> expression) ^^ {
      case identifier ~ annotation ~ value ~ body => SurfaceExpr.Given(identifier, annotation, value, body)
    }

  private lazy val ordinaryLet: Parser[SurfaceExpr] =
    (keyword("let") ~> name) ~ opt(":" ~> tpe) ~ ("=" ~> expression) ~ (separator ~> expression) ^^ {
      case identifier ~ annotation ~ value ~ body => {
        val initializer = annotation.fold(value)(SurfaceExpr.Ascribe(value, _))
        SurfaceExpr.Let(identifier, initializer, body)
      }
    }

  private lazy val lambdaParameter: Parser[SurfaceExpr => SurfaceExpr] =
    ((keyword("implicit") ~> name) ~ (":" ~> tpe) ^^ {
      case identifier ~ annotation => (body: SurfaceExpr) => SurfaceExpr.ImplicitLam(identifier, annotation, body)
    }) |
    (name ~ opt(":" ~> tpe) ^^ {
      case identifier ~ annotation => (body: SurfaceExpr) => SurfaceExpr.Lam(identifier, annotation, body)
    })

  private lazy val lambda: Parser[SurfaceExpr] =
    (rep1("(" ~> rep1sep(lambdaParameter, ",") <~ ")") ~ ("=>" ~> expression) ^^ {
      case groups ~ body => groups.flatten.foldRight(body)((bind, result) => bind(result))
    }) |
    (name ~ ("=>" ~> expression) ^^ { case parameter ~ body => SurfaceExpr.Lam(parameter, None, body) }) |
    (("[" ~> rep1sep(name, ",") <~ "]") ~ ("=>" ~> expression) ^^ {
      case variables ~ body => variables.foldRight(body)(SurfaceExpr.TypeLam.apply)
    })

  private lazy val ascription: Parser[SurfaceExpr] = comparison ~ opt(sameLine(":" ~> tpe)) ^^ {
    case value ~ Some(annotation) => SurfaceExpr.Ascribe(value, annotation)
    case value ~ None => value
  }

  private def binary(token: Parser[String], kind: OpKind): Parser[(SurfaceExpr, SurfaceExpr) => SurfaceExpr] = {
    sameLine(token) ^^^ { (left: SurfaceExpr, right: SurfaceExpr) => SurfaceExpr.BinOp(kind, left, right) }
  }

  private lazy val comparison: Parser[SurfaceExpr] = chainl1(
    concatenation,
    binary("==", OpKind.Eq) | binary("<", OpKind.Lt) | binary(">", OpKind.Gt)
  )

  private lazy val concatenation: Parser[SurfaceExpr] = chainl1(addition, binary("++", OpKind.Concat))

  private lazy val addition: Parser[SurfaceExpr] = chainl1(
    multiplication,
    binary("""\+(?!\+)""".r, OpKind.Add) | binary("-", OpKind.Sub)
  )

  private lazy val multiplication: Parser[SurfaceExpr] = chainl1(application, binary("*", OpKind.Mul))

  private lazy val application: Parser[SurfaceExpr] = postfix ~ rep(
    sameLine(suffix) | (spacedArgument ^^ { argument =>
      (function: SurfaceExpr) => SurfaceExpr.App(function, argument)
    })
  ) ^^ {
    case initial ~ operations => operations.foldLeft(initial)((value, operation) => operation(value))
  }

  // A bare negative argument would conflict with subtraction; use f(-1) or f (-1).
  private lazy val spacedArgument: Parser[SurfaceExpr] =
    (inlineTrivia ^? { case text if text.nonEmpty => () }) ~> not("-") ~> postfix

  // Adjacent suffixes belong to an argument: f g(x) = f(g(x)), but f g (x) = f(g)(x).
  private lazy val postfix: Parser[SurfaceExpr] = atom ~ rep(adjacent(suffix)) ^^ {
    case initial ~ operations => operations.foldLeft(initial)((value, operation) => operation(value))
  }

  // A suffix is an AST transformation, not a second syntax representation.
  private lazy val suffix: Parser[SurfaceExpr => SurfaceExpr] =
    (("(" ~> rep1sep(argument, ",") <~ ")") ^^ { applications =>
      (function: SurfaceExpr) => applications.foldLeft(function)((current, applyArgument) => applyArgument(current))
    }) |
    (("[" ~> rep1sep(tpe, ",") <~ "]") ^^ { arguments =>
      (function: SurfaceExpr) => arguments.foldLeft(function)(SurfaceExpr.TypeApp.apply)
    }) |
    (braced ^^ { value => (function: SurfaceExpr) => SurfaceExpr.App(function, value) }) |
    (("." ~> name) ^^ { label => (receiver: SurfaceExpr) => SurfaceExpr.Select(receiver, label) })

  // Like implicit on parameters, using belongs to one argument rather than the entire list.
  private lazy val argument: Parser[SurfaceExpr => SurfaceExpr] = opt(keyword("using")) ~ expression ^^ {
    case Some(_) ~ value => (function: SurfaceExpr) => SurfaceExpr.ImplicitApp(function, value)
    case None ~ value => (function: SurfaceExpr) => SurfaceExpr.App(function, value)
  }

  private lazy val record: Parser[SurfaceExpr] =
    fields(name ~ ("=" ~> expression) ^^ { case label ~ value => label -> value }) ^^ SurfaceExpr.Record.apply

  /** Braces either form a record literal or group one expression without adding an AST node. */
  private lazy val braced: Parser[SurfaceExpr] = record | ("{" ~> expression <~ "}")

  private lazy val atom: Parser[SurfaceExpr] =
    ((tpe <~ sameLine("::")) ~ name ^^ {
      case annotation ~ label => SurfaceExpr.QualifiedSelect(annotation, label)
    }) |
    ((keyword("summon") ~> "[" ~> tpe <~ "]") ^^ SurfaceExpr.Need.apply) |
    ("(" ~> expression <~ ")") |
    braced |
    (keyword("true") ^^^ SurfaceExpr.BoolLit(true)) |
    (keyword("false") ^^^ SurfaceExpr.BoolLit(false)) |
    (stringLiteral ^^ { text => SurfaceExpr.StringLit(decodeString(text)) }) |
    (wholeNumber ^^ { text => SurfaceExpr.IntLit(BigInt(text)) }) |
    (name ^^ SurfaceExpr.Var.apply)

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

  def parse(source: String): Result[Program, LanguageError] = parseAll(program <~ opt(";"), source) match {
    case Success(value, _) => Result.Ok(value)
    case failure: NoSuccess => {
      Result.Err(LanguageError.Parse(failure.msg, failure.next.pos.line, failure.next.pos.column))
    }
  }
}
