package implicitlang.parser

import implicitlang.Error
import implicitlang.syntax.{Expr, TypeExpr, OpKind}

import implicitlang.util.Result

import scala.util.parsing.combinator.JavaTokenParsers

/** Combinators construct Expr directly. Declarations are scoped expression constructors, not a module AST. */
final class Parser extends JavaTokenParsers {
  override protected val whiteSpace = """(\s+|//[^\r\n]*|(?s:/\*.*?\*/))+""".r

  private val reserved = Set(
    "fun", "let", "val", "def", "impl", "trait", "type", "where", "and", "in", "forall",
    "summon", "Int", "String", "Bool", "true", "false"
  )

  private def keyword(word: String): Parser[String] = {
    (java.util.regex.Pattern.quote(word) + """(?![\p{L}\p{N}_$])""").r
  }

  private def name: Parser[String] = ident ^? (
    { case value if !reserved.contains(value) => value },
    value => s"Reserved word '$value' cannot be a name"
  )

  private lazy val tpe: Parser[TypeExpr] = universalType | arrowType

  private lazy val universalType: Parser[TypeExpr] =
    (keyword("forall") ~> rep1sep(name, ",") <~ ".") ~ tpe ^^ {
      case parameters ~ body => parameters.foldRight(body)(TypeExpr.Forall.apply)
    }

  private lazy val arrowType: Parser[TypeExpr] = typeAtom ~ opt(("->" | "=>") ~ tpe) ^^ {
    case parameter ~ Some("->" ~ result) => TypeExpr.Arrow(parameter, result)
    case parameter ~ Some(_ ~ result) => TypeExpr.Implicit(parameter, result)
    case parameter ~ None => parameter
  }

  private lazy val typeAtom: Parser[TypeExpr] =
    (keyword("Int") ^^^ TypeExpr.IntType) |
    (keyword("String") ^^^ TypeExpr.StringType) |
    (keyword("Bool") ^^^ TypeExpr.BoolType) |
    ("(" ~> tpe <~ ")") |
    (("{" ~> rep((name ~ (":" ~> tpe)) <~ opt("," | ";")) <~ "}") ^^ { fields =>
      TypeExpr.Record(fields.map { case label ~ fieldType => label -> fieldType }.toVector)
    }) |
    (name ~ opt("[" ~> rep1sep(tpe, ",") <~ "]") ^^ {
      case identifier ~ arguments => TypeExpr.Named(identifier, arguments.getOrElse(Nil).toVector)
    })

  private lazy val expression: Parser[Expr] =
    typeBinding | functionBinding | implementation | ordinaryLet | lambda | ascription

  private lazy val continuation: Parser[Expr] = opt(keyword("in") | ";") ~> expression

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

  private lazy val parameters: Parser[List[(String, TypeExpr)]] =
    "(" ~> rep1sep(name ~ (":" ~> tpe), ",") <~ ")" ^^ { items =>
      items.map { case identifier ~ annotation => identifier -> annotation }
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
    (keyword("def") ~> name) ~ opt(genericHeader) ~ parameters ~ (":" ~> tpe) ~
      ("=" ~> expression) ~ continuation ^^ {
        case identifier ~ header ~ arguments ~ resultType ~ value ~ body => {
          val ordinary = arguments.foldRight[Expr](Expr.Ascribe(value, resultType)) {
            case ((parameter, annotation), result) => Expr.Lam(parameter, annotation, result)
          }
          Expr.Let(identifier, abstractImplicit(header, ordinary), body)
        }
      }

  private lazy val implementation: Parser[Expr] = keyword("impl") ~> (namedImplementation | anonymousImplementation)

  private lazy val namedImplementation: Parser[Expr] =
    name ~ opt(genericHeader) ~ (":" ~> tpe) ~ (("=" ~> expression) | record) ~ continuation ^^ {
      case identifier ~ header ~ annotation ~ value ~ body => {
        Expr.Impl(identifier, qualifyType(header, annotation), abstractImplicit(header, value), body)
      }
    }

  private lazy val anonymousImplementation: Parser[Expr] = tpe ~ record ~ continuation ^^ {
    case annotation ~ value ~ body => Expr.Impl("@anonymous", annotation, value, body)
  }

  private lazy val ordinaryLet: Parser[Expr] =
    (keyword("let") ~> valueBinding(keyword("in") ~> expression)) |
    (keyword("val") ~> valueBinding(continuation))

  private def valueBinding(rest: => Parser[Expr]): Parser[Expr] = {
    name ~ opt(":" ~> tpe) ~ ("=" ~> expression) ~ rest ^^ {
      case identifier ~ annotation ~ value ~ body => {
        val checked = annotation.fold(value)(Expr.Ascribe(value, _))
        Expr.Let(identifier, checked, body)
      }
    }
  }

  private lazy val lambda: Parser[Expr] =
    (opt(keyword("fun")) ~> parameters) ~ ("=>" ~> expression) ^^ {
      case arguments ~ body => arguments.foldRight(body) {
        case ((parameter, annotation), result) => Expr.Lam(parameter, annotation, result)
      }
    } |
    (keyword("fun") ~> (
      (("[" ~> rep1sep(name, ",") <~ "]") ~ ("=>" ~> expression) ^^ {
        case variables ~ body => variables.foldRight(body)(Expr.TypeLam.apply)
      }) |
      (("{" ~> name) ~ (":" ~> tpe <~ "}") ~ ("=>" ~> expression) ^^ {
        case identifier ~ annotation ~ body => Expr.ImplicitLam(identifier, annotation, body)
      })
    ))

  private lazy val ascription: Parser[Expr] = comparison ~ opt(":" ~> tpe) ^^ {
    case value ~ Some(annotation) => Expr.Ascribe(value, annotation)
    case value ~ None => value
  }

  private def binary(token: Parser[String], kind: OpKind): Parser[(Expr, Expr) => Expr] = {
    token ^^^ { (left: Expr, right: Expr) => Expr.BinOp(kind, left, right) }
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

  private lazy val multiplication: Parser[Expr] = chainl1(postfix, binary("*", OpKind.Mul))

  private lazy val postfix: Parser[Expr] = atom ~ rep(suffix) ^^ {
    case initial ~ operations => operations.foldLeft(initial)((value, operation) => operation(value))
  }

  // A suffix is an AST transformation, not a second syntax representation.
  private lazy val suffix: Parser[Expr => Expr] =
    (("(" ~> rep1sep(expression, ",") <~ ")") ^^ { arguments =>
      (function: Expr) => arguments.foldLeft(function)(Expr.App.apply)
    }) |
    (("[" ~> rep1sep(tpe, ",") <~ "]") ^^ { arguments =>
      (function: Expr) => arguments.foldLeft(function)(Expr.TypeApp.apply)
    }) |
    (("{" ~> expression <~ "}") ^^ { argument => (function: Expr) => Expr.ImplicitApp(function, argument) }) |
    (("." ~> name) ^^ { label => (receiver: Expr) => Expr.Proj(receiver, label) })

  private lazy val record: Parser[Expr] =
    ("{" ~> rep((name ~ ("=" ~> expression)) <~ opt("," | ";")) <~ "}") ^^ { fields =>
      Expr.Record(fields.map { case label ~ value => label -> value }.toVector)
    }

  private lazy val atom: Parser[Expr] =
    ((keyword("summon") ~> "[" ~> tpe <~ "]") ^^ Expr.Need.apply) |
    ("(" ~> expression <~ ")") |
    record |
    ("{" ~> expression <~ "}") |
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
      if input(index) != '\\' then {
        output.append(input(index))
        index += 1
      } else {
        input(index + 1) match {
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

  def parse(source: String): Result[Expr, implicitlang.Error] = parseAll(expression <~ opt(";"), source) match {
    case Success(value, _) => Result.Ok(value)
    case failure: NoSuccess => {
      Result.Err(Error.Parse(failure.msg, failure.next.pos.line, failure.next.pos.column))
    }
  }
}
