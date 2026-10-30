package implicitlang

import implicitlang.syntax.{Expr, TypeExpr, OpKind}
import implicitlang.core.{ImplicitTerm, Type, Term, Elaborated}
import implicitlang.parser.Parser
import implicitlang.elaboration.Elaborator
import implicitlang.synthesis.{Synthesizer, SynthesisContext, NaiveSynthesizer, SmtSynthesizer, SynthesisLimits}
import implicitlang.cli.Pipeline
import implicitlang.evaluation.Value

import implicitlang.util.Result

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import scala.util.Using
import scala.util.control.NonFatal

/** Dependency-free executable tests, also wired to `sbt test`. */
object CalculusTests {
  private var passed = 0
  private var failed = 0

  private def test(name: String)(body: => Unit): Unit = {
    try {
      body
      passed += 1
      println(s"PASS $name")
    } catch {
      case NonFatal(error) => {
        failed += 1
        System.err.println(s"FAIL $name: ${error.getMessage}")
        error.printStackTrace()
      }
    }
  }

  private def success[A](result: Result[A, Error]): A = result match {
    case Result.Ok(value) => value
    case Result.Err(error) => throw new AssertionError(s"Unexpected error: $error")
  }

  private def expectFailure[A](result: Result[A, Error])(predicate: Error => Boolean): Unit = result match {
    case Result.Err(error) => assert(predicate(error), s"Unexpected error: $error")
    case Result.Ok(value) => throw new AssertionError(s"Expected failure, received: $value")
  }

  private def run(source: String): Value = success(Pipeline.run(source))

  private def parse(source: String): Expr = success(new Parser().parse(source))

  private def testExamples(directory: Path): Unit = {
    val paths = Using.resource(Files.walk(directory)) { stream =>
      stream.iterator().asScala
        .filter(path => Files.isRegularFile(path) && path.getFileName.toString.endsWith(".imp"))
        .toVector.sortBy(_.toString)
    }
    assert(paths.nonEmpty, s"No .imp examples found in $directory")
    paths.foreach { path =>
      test("example " + directory.relativize(path)) {
        val source = Files.readString(path)
        val expected: Result[String, String] = source.linesIterator.nextOption() match {
          case Some(line) if line.startsWith("// result:") => {
            Result.Ok(line.stripPrefix("// result:").stripPrefix(" "))
          }
          case Some(line) if line.startsWith("// error:") => {
            Result.Err(line.stripPrefix("// error:").stripPrefix(" "))
          }
          case _ => throw new AssertionError("First line must be // result: value or // error: message")
        }
        val actual = Pipeline.run(source).map(_.show).mapError(_.toString)
        assert(actual == expected, s"Expected $expected, received $actual")
      }
    }
  }

  def main(arguments: Array[String]): Unit = {
    test("comma-separated and repeated definition parameters produce the same curried expression") {
      val compact = "def choose[T](a: T, b: T): T = a in choose[Int](21, 99)"
      val curried = "def choose [T] (a: T) (b: T) : T = a in choose [Int] 21 99"
      assert(parse(compact) == parse(curried))
      assert(run(curried) == Value.Integer(21))
      assert(run("def sum(a: Int, b: Int)(c: Int): Int = a + b + c in sum(1, 2)(3)") == Value.Integer(6))
    }

    test("lambda parameter groups are equivalent to nested unary lambdas") {
      val expected = parse("(a: Int) => (b: Int) => a + b")
      Vector("(a: Int, b: Int) => a + b", "(a: Int)(b: Int) => a + b")
        .foreach(source => assert(parse(source) == expected))
      assert(run("((a: Int, b: Int) => a + b) 20 1") == Value.Integer(21))
    }

    test("mixed lambda parameters preserve each binder kind and their source order") {
      val expected = Expr.Lam(
        "a",
        TypeExpr.IntType,
        Expr.ImplicitLam("b", TypeExpr.StringType, Expr.Lam("c", TypeExpr.BoolType, Expr.Var("b")))
      )
      Vector(
        "(a: Int, implicit b: String, c: Bool) => b",
        "(a: Int)(implicit b: String)(c: Bool) => b",
        "(a: Int) => (implicit b: String) => (c: Bool) => b"
      ).foreach(source => assert(parse(source) == expected))
      val compilation = success(Pipeline.compile("(a: Int, implicit b: String, c: Bool) => b"))
      assert(compilation.core.tpe == Type.Arrow(
        Type.IntType,
        Type.Implicit(Type.StringType, Type.Arrow(Type.BoolType, Type.StringType))
      ))
    }

    test("mixed definition parameters support implicit search and explicit overrides") {
      val definition = "def choose[T](a: T, implicit b: T, c: T): T = b"
      val curried = "def choose [T] (a: T) (implicit b: T) (c: T): T = b"
      assert(parse(s"$definition in choose") == parse(s"$curried in choose"))
      val prefix = s"$definition\nimpl selected: Int = 21\n"
      assert(run(prefix + "choose[Int](1, 99)") == Value.Integer(21))
      assert(run(prefix + "choose[Int] 1 99") == Value.Integer(21))
      assert(run(prefix + "choose[Int](1)(using 42)(99)") == Value.Integer(42))
    }

    test("only individually marked parameters enter implicit scope") {
      val source = """
        |let f = (a: Int, implicit b: Int, c: Int) => a + summon[Int] + c
        |f(1)(using 20)(100)
        |""".stripMargin
      assert(run(source) == Value.Integer(121))
      expectFailure(Pipeline.compile("(implicit b: Int, c: String) => summon[String]")) {
        case Error.MissingInstance(Type.StringType) => true
        case _ => false
      }
    }

    test("where constraints and mixed parameter groups compose in declaration order") {
      val source = """
        |def choose[T where T](ignored: T, implicit selected: T)(last: T): T = summon[T]
        |impl fallback: Int = 21
        |choose[Int](0)(using 42)(1)
        |""".stripMargin
      assert(run(source) == Value.Integer(42))
    }

    test("a trailing implicit parameter remains a first-class implicit function") {
      val source = "let f = (a: Int, implicit b: Int) => a + b in f(1)"
      assert(success(Pipeline.compile(source)).core.tpe == Type.Implicit(Type.IntType, Type.IntType))
      assert(run(s"($source)(using 20)") == Value.Integer(21))
      assert(run("def identity(implicit x: Int): Int = x in identity(using 21)") == Value.Integer(21))
    }

    test("implicit type arrows use tilde while lambda bodies retain equals arrows") {
      val source = "let identity: Int ~> Int = (implicit x: Int) => x in identity(using 21)"
      assert(run(source) == Value.Integer(21))
      val expected = TypeExpr.Arrow(
        TypeExpr.IntType,
        TypeExpr.Implicit(TypeExpr.StringType, TypeExpr.Arrow(TypeExpr.BoolType, TypeExpr.IntType))
      )
      assert(parse("f: Int -> String ~> Bool -> Int") == Expr.Ascribe(Expr.Var("f"), expected))
      assert(
        Type.Implicit(Type.IntType, Type.Arrow(Type.StringType, Type.BoolType)).show == "(Int ~> (String -> Bool))"
      )
      Vector("summon[Int => Int]", "(f: Int => Int) => f").foreach { source =>
        expectFailure(new Parser().parse(source)) { case Error.Parse(_, _, _) => true; case _ => false }
      }
    }

    test("lambda syntax needs no fun keyword and record literals remain unambiguous") {
      assert(run("([A, B] => (a: A, implicit b: B) => a)[Int, String](21)(using \"evidence\")") == Value.Integer(21))
      assert(run("let fun = 21 in fun") == Value.Integer(21))
      assert(run("((implicitValue: Int) => implicitValue) 21") == Value.Integer(21))
      assert(run("{ value = 21 }.value") == Value.Integer(21))
      Vector(
        "fun {d: Int} => d",
        "{d: Int} => d",
        "fun (x: Int) => x",
        "fun [A] => (x: A) => x",
        "(implicit: Int) => implicit"
      ).foreach { source =>
        expectFailure(new Parser().parse(source)) { case Error.Parse(_, _, _) => true; case _ => false }
      }
    }

    test("ordinary argument lists and whitespace applications associate to the left") {
      val expected = Expr.App(Expr.App(Expr.Var("f"), Expr.Var("a")), Expr.Var("b"))
      Vector("f(a, b)", "f(a)(b)", "f a b", "f (a, b)", "f(a) b", "f a (b)", "f (a) (b)").foreach { source =>
        assert(parse(source) == expected)
      }
      assert(parse("f [Int] a b") == parse("f[Int](a, b)"))
      assert(parse("f(using d) a b") == parse("f(using d)(a, b)"))
    }

    test("brace blocks and parentheses produce the same expression AST") {
      Vector("21", "1 + 2 * 3", "let x = 20 in x + 1", "let x = 20\nx + 1", "(x: Int) => x", "{ value = 21 }")
        .foreach { body =>
          assert(parse(s"{ $body }") == parse(s"($body)"))
        }
      assert(parse("{ { { 21 } } }") == Expr.IntLit(21))
      assert(run("{ 1 + 2 } * 3") == Value.Integer(9))
    }

    test("brace arguments are ordinary applications including when adjacent to the function") {
      val expected = Expr.App(Expr.Var("f"), Expr.Var("value"))
      Vector("f(value)", "f { value }", "f{value}", "f({ value })").foreach { source =>
        assert(parse(source) == expected)
      }
      assert(parse("f a { b }") == parse("f a (b)"))
      assert(parse("f a{b}") == parse("f a(b)"))
      assert(run("def f(x: Int): Int = x + 1 in f { let value = 20\nvalue }") == Value.Integer(21))
      assert(run("def f(x: Int): Int = x + 1 in f{20}") == Value.Integer(21))
    }

    test("records and brace blocks share ordinary argument parsing") {
      val expected = parse("f({ value = 21 })")
      Vector("f { value = 21 }", "f{value = 21}", "f { { value = 21 } }").foreach { source =>
        assert(parse(source) == expected)
      }
      assert(run("def get(r: { value: Int }): Int = r.value in get{value = 21}") == Value.Integer(21))
      assert(run("{ let r = { value = 21 }\nr }.value") == Value.Integer(21))
      assert(run("{}") == Value.Record(Vector.empty))
    }

    test("brace blocks preserve expression scope without introducing a statement language") {
      val source = """
        |let x = 1
        |let y = {
        |  let x = 20
        |  impl chosen: Int = x + 1
        |  summon[Int]
        |}
        |x + y
        |""".stripMargin
      assert(run(source) == Value.Integer(22))
      expectFailure(Pipeline.compile("let result = { impl chosen: Int = 21\nsummon[Int] }\nsummon[Int]")) {
        case Error.MissingInstance(Type.IntType) => true
        case _ => false
      }
      Vector("{ let x = 21 }", "{ 21; 22 }").foreach { source =>
        expectFailure(new Parser().parse(source)) { case Error.Parse(_, _, _) => true; case _ => false }
      }
    }

    test("using marks each explicit implicit argument independently") {
      val expected = Expr.App(
        Expr.ImplicitApp(Expr.App(Expr.Var("f"), Expr.Var("a")), Expr.Var("b")),
        Expr.Var("c")
      )
      assert(parse("f(a, using b, c)") == expected)
      assert(parse("f(a)(using b)(c)") == expected)
      assert(parse("f(using a, b)") == Expr.App(Expr.ImplicitApp(Expr.Var("f"), Expr.Var("a")), Expr.Var("b")))
      assert(parse("f(using a, using b)") == parse("f(using a)(using b)"))
      assert(run("def f(a: Int, implicit b: Int, c: Int): Int = a + b + c in f(1, using 20, 100)") ==
        Value.Integer(121))
      assert(run("def f(implicit a: Int, b: Int, implicit c: Int): Int = a + b + c in f(using 1, 2, using 3)") ==
        Value.Integer(6))
    }

    test("using arguments can contain brace blocks and explicitly override available evidence") {
      val source = """
        |impl fallback: Int = 99
        |let identity = (implicit x: Int) => x
        |identity(
        |  using {
        |    let answer = 20
        |    answer + 1
        |  }
        |)
        |""".stripMargin
      assert(run(source) == Value.Integer(21))
      expectFailure(Pipeline.compile("((implicit x: Int) => x)(using \"wrong\")")) {
        case Error.ExpectedType(Type.IntType, Type.StringType) => true
        case _ => false
      }
    }

    test("using is reserved only as a complete keyword and must precede an argument") {
      assert(run("let usingValue = 21 in ((x: Int) => x)(usingValue)") == Value.Integer(21))
      assert(parse("f(using /* explicit */ value)") == parse("f(using value)"))
      Vector("let using = 21 in using", "(using: Int) => using", "f(using)", "f using value", "f { using value }")
        .foreach { source =>
          expectFailure(new Parser().parse(source)) { case Error.Parse(_, _, _) => true; case _ => false }
        }
    }

    test("postfix operations bind inside whitespace arguments and application precedes arithmetic") {
      assert(parse("f a.field") == Expr.App(Expr.Var("f"), Expr.Select(Expr.Var("a"), "field")))
      assert(parse("f g(x)") == Expr.App(Expr.Var("f"), Expr.App(Expr.Var("g"), Expr.Var("x"))))
      assert(parse("f g (x)") == Expr.App(Expr.App(Expr.Var("f"), Expr.Var("g")), Expr.Var("x")))
      assert(parse("f g[Int]") == Expr.App(Expr.Var("f"), Expr.TypeApp(Expr.Var("g"), TypeExpr.IntType)))
      assert(run("def twice(x: Int): Int = x * 2 in twice 3 + twice 4 * 5") == Value.Integer(46))
    }

    test("subtraction remains unambiguous beside application and negative literals") {
      Vector("10-2", "10 -2", "10 - 2").foreach(source => assert(run(source) == Value.Integer(8)))
      Vector("f(-1)", "f (-1)").foreach { call =>
        assert(run(s"def f(x: Int): Int = x in $call") == Value.Integer(-1))
      }
      assert(run("def f(x: Int): Int = x in f 10 -2") == Value.Integer(8))
    }

    test("typed let bindings lower to ascribed initializers without changing the expression AST") {
      val expected = Expr.Let("a", Expr.Ascribe(Expr.IntLit(21), TypeExpr.IntType), Expr.Var("a"))
      Vector("let a: Int = 21 in a", "let a = (21: Int) in a", "let a: Int = 21\na").foreach { source =>
        assert(parse(source) == expected)
        assert(run(source) == Value.Integer(21))
      }
      expectFailure(Pipeline.compile("let a: String = 21\na")) {
        case Error.ExpectedType(Type.StringType, Type.IntType) => true
        case _ => false
      }
    }

    test("all declaration forms share explicit and newline continuation boundaries") {
      val declarations = Vector(
        "let a = 21" -> "a",
        "impl a: Int = 21" -> "summon[Int]",
        "def f(x: Int): Int = x" -> "f 21",
        "type N = Int" -> "21: N",
        "trait C = { value: Int }" -> "{ value = 21 }: C",
        "impl Int = 21" -> "summon[Int]",
        "impl { value: Int } = { value = 21 }" -> "summon[{ value: Int }].value",
        "impl identity[A]: A -> A = (x: A) => x" -> "identity[Int](21)",
        "impl [A]: A -> A = (x: A) => x" -> "summon[forall A. A -> A][Int](21)"
      )
      val separators = Vector(" in ", "\n", "\r\n", "\r", "; ", " // comment\n", " /* comment\n */ ")
      declarations.foreach { case (declaration, body) =>
        val expected = parse(s"$declaration in $body")
        separators.foreach { separator =>
          assert(parse(declaration + separator + body) == expected)
        }
      }
    }

    test("same-line declarations require a continuation separator") {
      Vector(
        "let a = 21 a",
        "impl a: Int = 21 summon[Int]",
        "def f(x: Int): Int = x f 21",
        "type N = Int 21",
        "trait C = { value: Int } 21",
        "let a = 21 /* no newline */ a",
        "let a = 21\n",
        "impl a: Int = 21\n"
      ).foreach { source =>
        expectFailure(new Parser().parse(source)) { case Error.Parse(_, _, _) => true; case _ => false }
      }
    }

    test("val is no longer a binding keyword") {
      expectFailure(new Parser().parse("val a = 21\na")) { case Error.Parse(_, _, _) => true; case _ => false }
      assert(run("let val = 21 in val") == Value.Integer(21))
    }

    test("newlines protect a binding body from being consumed as a postfix or whitespace argument") {
      assert(run("let ignored = 99\n((x: Int) => x) 21") == Value.Integer(21))
      assert(run("let ignored = 99\n{ value = 21 }.value") == Value.Integer(21))
      assert(run("let ignored = 99\n-21") == Value.Integer(-21))
      assert(run("let identity = (x: Int) => x\nidentity 21") == Value.Integer(21))
      assert(run("let a = let b = 20\nb + 1\na") == Value.Integer(21))
      assert(run("let a = (let b = 20\nb + 1)\na") == Value.Integer(21))
      assert(run("let a = 21\n\n// body\nin a") == Value.Integer(21))
    }

    test("line breaks are accepted after tokens that require an operand or body") {
      val source = """
        |def sum [T] (a: Int)
        |  (b: Int): Int =
        |  a +
        |  b
        |let value =
        |  sum[Int](
        |    20,
        |    1
        |  )
        |let identity = (x: Int) =>
        |  x
        |identity value
        |""".stripMargin
      assert(run(source) == Value.Integer(21))
    }

    test("complete expressions do not continue application or operators across a newline") {
      Vector("f\nx", "f\n(x)", "f\n[Int]", "f\n{d}", "f\n.field", "1\n+ 2").foreach { source =>
        expectFailure(new Parser().parse(source)) { case Error.Parse(_, _, _) => true; case _ => false }
      }
      assert(parse("f /* argument */ x") == parse("f x"))
      assert(run("let a = 21 /* boundary\n */ a") == Value.Integer(21))
    }

    test("record types and values require comma semicolon or newline field separators") {
      Vector(", ", "; ", "\n", "\r\n", " // next field\n", " /* next\nfield */ ").foreach { separator =>
        val annotation = "{ a: Int" + separator + "b: Int }"
        val record = "{ a = f 20" + separator + "b = f 0 }"
        val source = s"def f(x: Int): Int = x + 1 in let r: $annotation = $record in r.a + r.b"
        assert(run(source) == Value.Integer(22))
      }
      Vector("{ a = 1 b = 2 }", "summon[{ a: Int b: Int }]").foreach { source =>
        expectFailure(new Parser().parse(source)) { case Error.Parse(_, _, _) => true; case _ => false }
      }
      Vector("{}", "{\n}").foreach(source => assert(run(source) == Value.Record(Vector.empty)))
      Vector(",", ";", "\n").foreach { separator =>
        assert(run(s"({ a = 21$separator }: { a: Int$separator }).a") == Value.Integer(21))
      }
    }

    test("parser directly retains a first-class implementation binding") {
      success(new Parser().parse("impl answer: Int = 21 in answer")) match {
        case Expr.Impl("answer", TypeExpr.IntType, Expr.IntLit(value), Expr.Var("answer")) => assert(value == 21)
        case other => throw new AssertionError(s"Unexpected AST: $other")
      }
    }

    test("named and anonymous implementations accept the same expression initializers") {
      val prefix = "let existing = 21 in def identity(x: Int): Int = x in "
      val cases = Vector(
        ("Int", "21", "summon[Int]"),
        ("Int", "existing", "summon[Int]"),
        ("Int", "identity(21)", "summon[Int]"),
        ("Int", "{ let x = 20\nx + 1 }", "summon[Int]"),
        ("Int -> Int", "(x: Int) => x + 1", "summon[Int -> Int](20)"),
        ("Int ~> Int", "(implicit x: Int) => x + 1", "summon[Int ~> Int](using 20)"),
        ("{ value: Int }", "{ value = 21 }", "summon[{ value: Int }].value")
      )
      cases.foreach { case (annotation, initializer, body) =>
        Vector("", "selected: ").foreach { header =>
          assert(run(s"${prefix}impl $header$annotation = $initializer in $body") == Value.Integer(21))
        }
      }
    }

    test("implementation headers distinguish naming and type parameters from applied target types") {
      val prefix = "type Box[A] = { value: A } in "
      assert(run(prefix + "impl Box[Int] = { value = 21 } in Box[Int]::value") == Value.Integer(21))
      assert(run(prefix + "impl Box[Box[Int]] = { value = { value = 21 } } in Box[Box[Int]]::value.value") ==
        Value.Integer(21))
      assert(run(prefix + "impl Box[A]: A -> A = (x: A) => x in Box[Int](21)") == Value.Integer(21))
      assert(run("type N = Int in impl N = 21 in summon[N]") == Value.Integer(21))
      assert(run("impl [A]: A -> A = (x: A) => x in summon[forall A. A -> A][Int](21)") == Value.Integer(21))
    }

    test("named and anonymous generic implementations share where lowering and constraint order") {
      val annotation = "forall A, B. A ~> B ~> { first: A, second: B }"
      val initializer = "{ first = summon[A], second = summon[B] }"
      val body = s"summon[$annotation][Int, String](using 21, using \"Ada\")"
      val explicit = s"impl $annotation = [A, B] => (implicit a: A, implicit b: B) => $initializer in $body"
      val expected = success(Pipeline.compile(explicit)).core
      Vector("", "pair").foreach { name =>
        Vector(" and ", ", ").foreach { separator =>
          val source = s"impl $name[A, B where A${separator}B]: { first: A, second: B } = $initializer in $body"
          assert(success(Pipeline.compile(source)).core == expected)
          assert(run(source) == Value.Record(Vector("first" -> Value.Integer(21), "second" -> Value.Text("Ada"))))
        }
      }
    }

    test("implementation headers can introduce constraints without type parameters") {
      Vector("", "selected").foreach { name =>
        val source = s"impl $name[where Int]: Int = summon[Int] in summon[Int ~> Int](using 21)"
        assert(run(source) == Value.Integer(21))
      }
    }

    test("implementation declarations require equals and a colon after every binder header") {
      Vector(
        "impl named: { value: Int } { value = 21 } in 0",
        "impl { value: Int } { value = 21 } in 0",
        "impl named[A]: { value: A -> A } { value = (x: A) => x } in 0",
        "impl [A]: { value: A -> A } { value = (x: A) => x } in 0",
        "impl [A] A -> A = (x: A) => x in 0",
        "impl named[A] A -> A = (x: A) => x in 0",
        "impl named: = 21 in 0",
        "impl : Int = 21 in 0",
        "impl Int = 21",
        "impl [A]: A -> A = (x: A) => x"
      ).foreach { source =>
        expectFailure(new Parser().parse(source)) { case Error.Parse(_, _, _) => true; case _ => false }
      }
    }

    test("ordinary and implementation binders have separate indices") {
      val parsed = success(new Parser().parse("impl d: Int = 21 in (x: String) => d"))
      val actual = success(new Elaborator().elaborate(parsed)).expression
      assert(actual == ImplicitTerm.Impl(
        Type.IntType,
        ImplicitTerm.IntLit(21),
        ImplicitTerm.Lam(Type.StringType, ImplicitTerm.Instance(0))
      ))
    }

    test("name elaboration inserts a hole without attempting synthesis") {
      val parsed = success(new Parser().parse("summon[Int]"))
      assert(success(new Elaborator().elaborate(parsed)) == Elaborated(ImplicitTerm.Need(Type.IntType), Type.IntType))
      expectFailure(Pipeline.compile("summon[Int]")) { case Error.MissingInstance(_) => true; case _ => false }
    }

    test("ordinary application checks its argument") {
      expectFailure(Pipeline.compile("((x: Int) => x)(\"bad\")")) {
        case Error.ExpectedType(Type.IntType, Type.StringType) => true
        case _ => false
      }
    }

    test("ordinary values are not implicitly registered") {
      expectFailure(Pipeline.compile("let ordinary = 21 in summon[Int]")) {
        case Error.MissingInstance(Type.IntType) => true
        case _ => false
      }
    }

    test("nearest matching implementation takes precedence") {
      assert(run("impl first: Int = 1 in impl second: Int = 2 in summon[Int]") == Value.Integer(2))
    }

    test("instances can be referred to and passed as ordinary values") {
      assert(run("impl value: Int = 21 in ((x: Int) => x)(value)") == Value.Integer(21))
    }

    test("an ordinary parameter can be installed as a local implementation") {
      assert(run("((x: Int) => impl chosen: Int = x in summon[Int])(21)") == Value.Integer(21))
    }

    test("implementation bindings are nonrecursive") {
      Vector("", "self: ").foreach { header =>
        expectFailure(Pipeline.compile(s"impl ${header}Int = summon[Int] in summon[Int]")) {
          case Error.MissingInstance(Type.IntType) => true
          case _ => false
        }
        assert(run(s"impl outer: Int = 21 in impl ${header}Int = summon[Int] in summon[Int]") == Value.Integer(21))
      }
    }

    test("implementation annotations are checked") {
      Vector("", "wrong: ").foreach { header =>
        expectFailure(Pipeline.compile(s"impl ${header}Int = \"no\" in summon[Int]")) {
          case Error.ExpectedType(Type.IntType, Type.StringType) => true
          case _ => false
        }
      }
      Vector("", "wrong").foreach { name =>
        expectFailure(Pipeline.compile(s"impl $name[A where A]: Int = summon[A] in 0")) {
          case Error.ExpectedType(Type.Forall(Type.Implicit(Type.Variable(0), Type.IntType)), _) => true
          case _ => false
        }
      }
    }

    test("anonymous implementations preserve lexical scope and nearest matching selection") {
      val source = """
        |impl Int = 11
        |let captured = (ignored: Int) => summon[Int]
        |impl String = "Ada"
        |impl Int = 21
        |{ before = captured(0), current = summon[Int], text = summon[String] }
        |""".stripMargin
      assert(run(source) == Value.Record(Vector(
        "before" -> Value.Integer(11), "current" -> Value.Integer(21), "text" -> Value.Text("Ada")
      )))
      expectFailure(Pipeline.compile("let result = { impl Int = 21\nsummon[Int] }\nsummon[Int]")) {
        case Error.MissingInstance(Type.IntType) => true
        case _ => false
      }
    }

    test("ordinary binders weaken references to the implementation environment") {
      val source = "impl d: Int = 11 in let ignored = \"x\" in ((x: String) => summon[Int])(\"y\")"
      assert(run(source) == Value.Integer(11))
    }

    test("implementation binders weaken references to the ordinary environment") {
      val source = "let x = 11 in impl d: String = \"a\" in ((implicit q: String) => x)(using \"b\")"
      assert(run(source) == Value.Integer(11))
    }

    test("source name shadowing does not remove an older implementation from search") {
      val source = "impl x: Int = 11 in let x = 100 in summon[Int] + x"
      assert(run(source) == Value.Integer(111))
    }

    test("implicit lambdas make their parameters available to synthesis") {
      assert(run("((implicit d: Int) => summon[Int])(using 21)") == Value.Integer(21))
    }

    test("ordinary function application inserts all leading implicit arguments") {
      val source = "impl d: Int = 21 in ((implicit a: Int) => (implicit b: Int) => (x: Int) => b + x)(1)"
      assert(run(source) == Value.Integer(22))
    }

    test("implicit application is not ordinary application") {
      expectFailure(Pipeline.compile("((x: Int) => x)(using 21)")) {
        case Error.ExpectedImplicitFunction(_) => true
        case _ => false
      }
    }

    test("implicit functions remain first-class when passed through an ordinary arrow") {
      val source = "((f: Int ~> Int) => f(using 21))((implicit x: Int) => x)"
      assert(run(source) == Value.Integer(21))
    }

    test("lookup distinguishes implicit and explicit arrows before erasure") {
      val source = "impl f: Int -> Int = (x: Int) => x in summon[Int ~> Int]"
      expectFailure(Pipeline.compile(source)) { case Error.MissingInstance(_) => true; case _ => false }
    }

    test("a bare implicit value is not forced merely by variable lookup") {
      val compilation = success(Pipeline.compile("(implicit x: Int) => x"))
      assert(compilation.core.tpe == Type.Implicit(Type.IntType, Type.IntType))
      assert(success(compilation.target.infer()) == Type.Arrow(Type.IntType, Type.IntType))
    }

    test("polymorphic identity checks and evaluates") {
      assert(run("([A] => (x: A) => x)[Int](21)") == Value.Integer(21))
    }

    test("type abstraction weakens types in both environments") {
      val source = "([A] => (implicit d: A) => (x: A) => [B] => summon[A])[Int](using 21)(99)[String]"
      assert(run(source) == Value.Integer(21))
    }

    test("polymorphic type substitution avoids capturing a nested type variable") {
      val body = Type.Forall(Type.Arrow(Type.Variable(1), Type.Variable(0)))
      assert(body.instantiate(Type.StringType) == Type.Forall(Type.Arrow(Type.StringType, Type.Variable(0))))
    }

    test("aliases capture their declaration environment rather than later type names") {
      val source = "([A] => type Saved = A in [A] => (x: Saved) => x)[Int][String](21)"
      assert(run(source) == Value.Integer(21))
    }

    test("parameterized aliases preserve captured outer type variables") {
      val source = """
        |([A] => type Pair[B] = { first: A, second: B } in
        |  [C] => (p: Pair[C]) => p.first)[Int][String]({ first = 21, second = "Ada" })
        |""".stripMargin
      assert(run(source) == Value.Integer(21))
    }

    test("record equality does not depend on field order") {
      val source = "((r: { a: Int, b: String }) => r.a)({ b = \"Ada\", a = 21 })"
      assert(run(source) == Value.Integer(21))
    }

    test("duplicate record labels are rejected rather than silently overwritten") {
      expectFailure(Pipeline.compile("{ x = 1, x = 2 }")) { case Error.DuplicateName("x") => true; case _ => false }
    }

    test("alias arity is checked") {
      expectFailure(Pipeline.compile("type Box[A] = { value: A } in summon[Box]")) {
        case Error.AliasArity("Box", 1, 0) => true
        case _ => false
      }
    }

    test("keyword prefixes are not accepted as keyword tokens") {
      expectFailure(new Parser().parse("let x = 1 inner")) { case Error.Parse(_, _, _) => true; case _ => false }
      assert(run("let inner = 21 in inner") == Value.Integer(21))
    }

    test("string escapes and arbitrary precision integers are preserved") {
      assert(run("\"a\\n\\u0042\"") == Value.Text("a\nB"))
      assert(run("123456789012345678901234567890") == Value.Integer(BigInt("123456789012345678901234567890")))
    }

    test("parser constructs a shared binary operator AST directly") {
      val parsed = success(new Parser().parse("1 + 2 * 3"))
      assert(parsed == Expr.BinOp(
        OpKind.Add,
        Expr.IntLit(1),
        Expr.BinOp(OpKind.Mul, Expr.IntLit(2), Expr.IntLit(3))
      ))
      assert(success(new Parser().parse("\"a\" ++ \"b\"")) == Expr.BinOp(
        OpKind.Concat,
        Expr.StringLit("a"),
        Expr.StringLit("b")
      ))
    }

    test("arithmetic has conventional precedence and left associativity") {
      assert(run("1 + 2 * 3") == Value.Integer(7))
      assert(run("10 - 2 - 3") == Value.Integer(5))
      assert(run("(1 + 2) * 3") == Value.Integer(9))
    }

    test("comparison operators return Bool") {
      assert(run("1 + 2 * 3 == 7") == Value.Boolean(true))
      assert(run("2 < 1") == Value.Boolean(false))
      assert(run("3 > 1") == Value.Boolean(true))
      assert(run("true: Bool") == Value.Boolean(true))
    }

    test("string concatenation is ++, not numeric addition") {
      assert(run("\"a\" ++ \"b\" ++ \"c\"") == Value.Text("abc"))
      expectFailure(Pipeline.compile("\"a\" + \"b\"")) {
        case Error.ExpectedType(Type.IntType, Type.StringType) => true
        case _ => false
      }
      expectFailure(Pipeline.compile("\"a\" ++ 1")) {
        case Error.ExpectedType(Type.StringType, Type.IntType) => true
        case _ => false
      }
    }

    test("operators are checked independently in the final calculus") {
      assert(success(Term.BinOp(OpKind.Lt, Term.IntLit(1), Term.IntLit(2)).infer()) == Type.BoolType)
      expectFailure(Term.BinOp(OpKind.Add, Term.StringLit("bad"), Term.IntLit(1)).infer()) {
        case Error.ExpectedType(Type.IntType, Type.StringType) => true
        case _ => false
      }
    }

    test("integer-to-string conversion is not a primitive") {
      expectFailure(Pipeline.compile("showInt(21)")) {
        case Error.UnknownTerm("showInt") => true
        case _ => false
      }
    }

    testExamples(Path.of("examples"))

    test("SMT synthesis chooses the nearest exact implementation") {
      val context = SynthesisContext(List(Type.IntType, Type.IntType), typeDepth = 0)
      assert(success(new SmtSynthesizer().synthesize(Type.IntType, context)) == ImplicitTerm.Instance(0))
    }

    test("SMT synthesis composes ordinary and implicit producers") {
      val source = """
        |impl text: String = "Ada"
        |impl toNumber: String -> Int = (text: String) => 21
        |impl toBool: Int ~> Bool = (implicit number: Int) => number > 0
        |summon[Bool]
        |""".stripMargin
      assert(run(source) == Value.Boolean(true))
    }

    test("SMT synthesis instantiates a producer with a function type") {
      val source = """
        |impl identity[A]: A -> A = (a: A) => a
        |let twice = summon[(Int -> Int) -> (Int -> Int)]
        |twice((n: Int) => n + 1)(20)
        |""".stripMargin
      assert(run(source) == Value.Integer(21))
    }

    test("SMT synthesis preserves rigid variables beneath nested universals") {
      val source = """
        |impl choose[A, B]: A -> B -> A = (a: A, b: B) => a
        |def specialize[T](x: T): T = {
        |  let selected = summon[forall B. T -> B -> T]
        |  selected[String](x, "ignored")
        |}
        |specialize[Int](21)
        |""".stripMargin
      assert(run(source) == Value.Integer(21))
    }

    test("SMT synthesis supports impredicative type arguments") {
      val polymorphic = Type.Forall(Type.Arrow(Type.Variable(0), Type.Variable(0)))
      val wanted = Type.Arrow(polymorphic, polymorphic)
      val found = new SmtSynthesizer().synthesize(wanted, SynthesisContext(List(polymorphic), 0))
      assert(success(found) == ImplicitTerm.TypeApp(ImplicitTerm.Instance(0), polymorphic))
    }

    test("SMT synthesis can apply a function exposed by an inferred type argument") {
      val source = """
        |(implicit produce: forall A. (A -> Int) -> A,
        | implicit handler: (String -> Bool) -> Int,
        | implicit text: String) => summon[Bool]
        |""".stripMargin
      val compilation = success(Pipeline.compile(source))
      assert(success(compilation.target.infer()) == compilation.core.tpe.erased)
    }

    test("SMT synthesis can instantiate a universal exposed after value application") {
      val source = """
        |(implicit produce: forall A. (A -> Int) -> A,
        | implicit handler: (forall B. B -> B) -> Int) => summon[Int -> Int]
        |""".stripMargin
      val compilation = success(Pipeline.compile(source))
      assert(success(compilation.target.infer()) == compilation.core.tpe.erased)
    }

    test("SMT synthesis does not unify distinct rigid type variables") {
      expectFailure(new SmtSynthesizer().synthesize(Type.Variable(1), SynthesisContext(List(Type.Variable(0)), 2))) {
        case Error.MissingInstance(Type.Variable(1)) => true
        case _ => false
      }
    }

    test("bounded polymorphic failure does not claim uninhabitability") {
      val producer = Type.Forall(Type.Implicit(Type.Variable(0), Type.Variable(0)))
      val synthesizer = new SmtSynthesizer(SynthesisLimits(maximumSteps = 2))
      expectFailure(synthesizer.synthesize(Type.StringType, SynthesisContext(List(producer), 0))) {
        case Error.SynthesisLimit(Type.StringType, 2, _) => true
        case _ => false
      }
    }

    test("SMT synthesis rejects cyclic prerequisites without a seed") {
      val context = SynthesisContext(List(Type.Implicit(Type.IntType, Type.IntType)), 0)
      expectFailure(new SmtSynthesizer().synthesize(Type.IntType, context)) {
        case Error.MissingInstance(Type.IntType) => true
        case _ => false
      }
    }

    test("SMT search limits are distinct from missing evidence") {
      val context = SynthesisContext(List(Type.Arrow(Type.StringType, Type.IntType), Type.StringType), 0)
      expectFailure(new SmtSynthesizer(SynthesisLimits(maximumSteps = 2)).synthesize(Type.IntType, context)) {
        case Error.SynthesisLimit(Type.IntType, 2, _) => true
        case _ => false
      }
      assert(success(new SmtSynthesizer().synthesize(Type.IntType, context)) ==
        ImplicitTerm.App(ImplicitTerm.Instance(0), ImplicitTerm.Instance(1)))
    }

    test("SMT type-argument depth limits are explicit") {
      val polymorphic = Type.Forall(Type.Arrow(Type.Variable(0), Type.Variable(0)))
      val argument = Type.Arrow(Type.IntType, Type.IntType)
      val wanted = Type.Arrow(argument, argument)
      val synthesizer = new SmtSynthesizer(SynthesisLimits(maximumSteps = 2, maximumTypeDepth = 1))
      expectFailure(synthesizer.synthesize(wanted, SynthesisContext(List(polymorphic), 0))) {
        case Error.SynthesisLimit(actual, 2, 1) => actual == wanted
        case _ => false
      }
    }

    test("SMT queries preserve their caller's context across success and failure") {
      val synthesizer = new SmtSynthesizer()
      val instances = List(Type.Arrow(Type.StringType, Type.IntType), Type.StringType)
      val context = SynthesisContext(instances, 0)
      val first = success(synthesizer.synthesize(Type.IntType, context))
      expectFailure(synthesizer.synthesize(Type.BoolType, context)) {
        case Error.MissingInstance(Type.BoolType) => true
        case _ => false
      }
      assert(success(synthesizer.synthesize(Type.IntType, context)) == first)
      assert(context.instances == instances && context.typeDepth == 0)
    }

    test("SMT input validation rejects ill-scoped types and noncanonical records") {
      val synthesizer = new SmtSynthesizer()
      expectFailure(synthesizer.synthesize(Type.Variable(0), SynthesisContext(Nil, 0))) {
        case Error.InvalidTypeIndex(0, 0) => true
        case _ => false
      }
      expectFailure(synthesizer.synthesize(Type.IntType, SynthesisContext(List(Type.Variable(1)), 1))) {
        case Error.InvalidTypeIndex(1, 1) => true
        case _ => false
      }
      val record = Type.Record(Vector("z" -> Type.IntType, "a" -> Type.StringType))
      expectFailure(synthesizer.synthesize(record, SynthesisContext(List(record), 0))) {
        case Error.NonCanonicalRecord(Vector("z", "a")) => true
        case _ => false
      }
    }

    test("baseline does not instantiate polymorphic implementations") {
      val generic = Type.Forall(Type.Arrow(Type.Variable(0), Type.Variable(0)))
      val wanted = Type.Arrow(Type.IntType, Type.IntType)
      val context = SynthesisContext(List(generic), typeDepth = 0)
      expectFailure(new NaiveSynthesizer().synthesize(wanted, context)) {
        case Error.MissingInstance(actual) if actual == wanted => true
        case _ => false
      }
    }

    test("baseline does not compose available implementations") {
      val function = Type.Arrow(Type.StringType, Type.IntType)
      val context = SynthesisContext(List(function, Type.StringType), typeDepth = 0)
      expectFailure(new NaiveSynthesizer().synthesize(Type.IntType, context)) {
        case Error.MissingInstance(Type.IntType) => true
        case _ => false
      }
    }

    test("baseline only selects the nearest exact implementation") {
      val context = SynthesisContext(List(Type.IntType, Type.IntType), typeDepth = 0)
      val found = new NaiveSynthesizer().synthesize(Type.IntType, context)
      assert(success(found) == ImplicitTerm.Instance(0))
    }

    test("a replacement synthesizer can compose existing first-class values") {
      val composing = new Synthesizer {
        def synthesize(goal: Type, context: SynthesisContext): Result[ImplicitTerm, Error] = {
          val function = context.instances.indexOf(Type.Arrow(Type.IntType, Type.IntType))
          val argument = context.instances.indexOf(Type.IntType)
          if goal == Type.IntType && function >= 0 && argument >= 0 then {
            Result.Ok(ImplicitTerm.App(ImplicitTerm.Instance(function), ImplicitTerm.Instance(argument)))
          } else {
            new NaiveSynthesizer().synthesize(goal, context)
          }
        }
      }
      val source = "impl f: Int -> Int = (n: Int) => n + 1 in impl n: Int = 21 in summon[Int]"
      assert(success(Pipeline.run(source, composing)) == Value.Integer(22))
    }

    test("the resolver rejects a synthesizer returning a term of the wrong type") {
      val invalid = new Synthesizer {
        def synthesize(goal: Type, context: SynthesisContext): Result[ImplicitTerm, Error] = {
          Result.Ok(ImplicitTerm.Instance(0))
        }
      }
      expectFailure(Pipeline.compile("impl n: Int = 0 in summon[String]", invalid)) {
        case Error.InvalidEvidence(Type.StringType, Type.IntType) => true
        case _ => false
      }
    }

    test("the resolver rejects out-of-scope evidence from a synthesizer") {
      val invalid = new Synthesizer {
        def synthesize(goal: Type, context: SynthesisContext): Result[ImplicitTerm, Error] = {
          Result.Ok(ImplicitTerm.Instance(99))
        }
      }
      expectFailure(Pipeline.compile("summon[Int]", invalid)) {
        case Error.InvalidTermIndex("Δ", 99) => true
        case _ => false
      }
    }

    test("the final checker independently rejects ill-typed applications") {
      val malformed = Term.App(Term.Lam(Type.IntType, Term.Var(0)), Term.StringLit("no"))
      expectFailure(malformed.infer()) { case Error.ExpectedType(_, _) => true; case _ => false }
    }

    test("the final checker rejects free type variables and unerased implicit types") {
      expectFailure(Term.Lam(Type.Variable(0), Term.Var(0)).infer()) {
        case Error.InvalidTypeIndex(0, 0) => true
        case _ => false
      }
      expectFailure(Term.Lam(Type.Implicit(Type.IntType, Type.IntType), Term.Var(0)).infer()) {
        case Error.ImplicitTypeInTarget(_) => true
        case _ => false
      }
    }

    test("declaration syntax parses straight to type, ordinary, and implementation bindings") {
      val source = "trait C[A] = { run: A -> A } in def id(x: Int): Int = x in impl i: Int = id(21) in summon[Int]"
      val ast = success(new Parser().parse(source))
      ast match {
        case Expr.TypeLet("C", Vector("A"), _, Expr.Let("id", _, Expr.Impl("i", _, _, _))) => ()
        case other => throw new AssertionError(s"Unexpected declaration lowering: $other")
      }
      assert(run(source) == Value.Integer(21))
    }

    test("where becomes an implicit lambda without a new declaration representation") {
      val source = "trait C[A] = { run: A -> A } in def f[A where C[A]](x: A): A = x in f"
      val checked = success(new Elaborator().elaborate(success(new Parser().parse(source))))
      checked.tpe match {
        case Type.Forall(Type.Implicit(Type.Record(_), Type.Arrow(Type.Variable(0), Type.Variable(0)))) => ()
        case other => throw new AssertionError(s"Unexpected qualified type: $other")
      }
    }

    test("named instance values remain usable through ordinary function arguments") {
      val source = "impl answer: Int = 21 in def id(x: Int): Int = x in id(answer)"
      assert(run(source) == Value.Integer(21))
    }

    test("type synonyms do not need an extra runtime declaration") {
      val source = "type N = Int in impl answer: N = 21 in summon[Int]"
      assert(run(source) == Value.Integer(21))
    }

    test("typed field projection takes precedence over implicit method sugar") {
      val source = """
        |type Person = { name: String }
        |impl getter: { name: Person -> String } = { name = (p: Person) => "unused" };
        |{ name = "Ada" }.name
        |""".stripMargin
      assert(run(source) == Value.Text("Ada"))
    }

    test("raw selection accepts any receiver without classifying it as a record") {
      assert(parse("21.show") == Expr.Select(Expr.IntLit(21), "show"))
      assert(parse("make(21).value") == Expr.Select(Expr.App(Expr.Var("make"), Expr.IntLit(21)), "value"))
      assert(parse("((x: Int) => x).run") == Expr.Select(
        Expr.Lam("x", TypeExpr.IntType, Expr.Var("x")),
        "run"
      ))
      val expected = Expr.App(
        Expr.Select(Expr.Need(TypeExpr.Named("ToString", Vector(TypeExpr.Named("Person", Vector.empty)))), "toString"),
        Expr.Var("x")
      )
      assert(parse("ToString[Person]::toString(x)") == expected)
      assert(parse("ToString [Person] :: toString x") == expected)
      assert(parse("summon[ToString[Person]].toString(x)") == expected)
    }

    test("elaboration classifies record fields and methods while leaving evidence requests unresolved") {
      val field = success(new Elaborator().elaborate(parse("{ value = 21 }.value")))
      assert(field == Elaborated(
        ImplicitTerm.Proj(ImplicitTerm.Record(Vector("value" -> ImplicitTerm.IntLit(21))), "value"),
        Type.IntType
      ))
      val methodType = Type.Record(Vector("inc" -> Type.Arrow(Type.IntType, Type.IntType)))
      val method = success(new Elaborator().elaborate(parse("(implicit methods: { inc: Int -> Int }) => 21.inc")))
      assert(method.expression == ImplicitTerm.ImplicitLam(
        methodType,
        ImplicitTerm.App(ImplicitTerm.Proj(ImplicitTerm.Need(methodType), "inc"), ImplicitTerm.IntLit(21))
      ))
      val qualified = "type C = { inc: Int -> Int } in C::inc"
      val selected = success(new Elaborator().elaborate(parse(qualified)))
      assert(selected.expression == ImplicitTerm.Proj(ImplicitTerm.Need(methodType), "inc"))
      expectFailure(Pipeline.compile(qualified)) {
        case Error.MissingInstance(actual) => actual == methodType
        case _ => false
      }
    }

    test("type names cannot be used as values or dotted receivers even when evidence is available") {
      val prefix = "trait C[A] = { run: A -> A } in impl C[Int] = { run = (n: Int) => n } in "
      Vector("C", "C[Int]", "C[Int].run", "C[Int].run(21)").foreach { expression =>
        expectFailure(Pipeline.compile(prefix + expression)) {
          case Error.UnknownTerm("C") => true
          case _ => false
        }
      }
      assert(run(prefix + "C[Int]::run(21)") == Value.Integer(21))
      assert(run(prefix + "summon[C[Int]].run(21)") == Value.Integer(21))
    }

    test("qualified selection uses type names while dotted selection uses value names") {
      val source = """
        |type C = { value: Int }
        |impl selected: C = { value = 21 }
        |let C = { value = 99 }
        |{ qualified = C::value, ordinary = C.value }
        |""".stripMargin
      assert(run(source) == Value.Record(Vector("ordinary" -> Value.Integer(99), "qualified" -> Value.Integer(21))))
      expectFailure(Pipeline.compile("let x = { value = 21 } in x::value")) {
        case Error.UnknownType("x") => true
        case _ => false
      }
      val polymorphic = "type C = Int in let C = [A] => { value = 21 } in C[String].value"
      assert(run(polymorphic) == Value.Integer(21))
    }

    test("record receivers with no matching field can use implementation methods") {
      val source = """
        |type Person = { name: String }
        |trait ToString[A] = { toString: A -> String }
        |impl ToString[Person] = { toString = (p: Person) => p.name }
        |{ name = "Ada" }.toString
        |""".stripMargin
      assert(run(source) == Value.Text("Ada"))
    }

    test("selection on a function expression can resolve an implementation method") {
      val source = """
        |type Function = Int -> Int
        |impl methods: { run: Function -> Int } = { run = (f: Function) => f(20) }
        |((x: Int) => x + 1).run
        |""".stripMargin
      assert(run(source) == Value.Integer(21))
    }

    test("a present record field wins even when calling it is invalid") {
      val source = """
        |type Person = { show: Int }
        |impl methods: { show: Person -> (Int -> Int) } = { show = (p: Person) => (n: Int) => n }
        |{ show = 21 }.show(1)
        |""".stripMargin
      expectFailure(Pipeline.compile(source)) {
        case Error.ExpectedFunction(Type.IntType) => true
        case _ => false
      }
    }

    test("explicit type qualification disambiguates method providers") {
      val prefix = """
        |type First = { show: Int -> String }
        |type Second = { show: Int -> String, tag: String }
        |impl first: First = { show = (n: Int) => "first" }
        |impl second: Second = { show = (n: Int) => "second", tag = "tag" }
        |""".stripMargin
      expectFailure(Pipeline.compile(prefix + "21.show")) {
        case Error.AmbiguousMethod("show", _) => true
        case _ => false
      }
      assert(run(prefix + "First::show(21)") == Value.Text("first"))
      assert(run(prefix + "Second::show(21)") == Value.Text("second"))
    }

    test("receiver syntax and explicit class selection have the same meaning") {
      val prefix = "trait C[A] = { value: A -> Int } in impl C[Int] = { value = (n: Int) => n + 1 } in "
      assert(run(prefix + "21.value") == Value.Integer(22))
      assert(run(prefix + "C[Int]::value(21)") == Value.Integer(22))
    }

    test("method sugar rejects multiple distinct candidate evidence types") {
      val source = """
        |impl first: { show: Int -> String } = { show = (n: Int) => "first" }
        |impl second: { show: Int -> String, tag: String } = { show = (n: Int) => "second", tag = "tag" }
        |21.show
        |""".stripMargin
      expectFailure(Pipeline.compile(source)) { case Error.AmbiguousMethod("show", _) => true; case _ => false }
    }

    test("constrained function bodies are checked against their declared result type") {
      val source = "def f(x: Int): String = x in f(21)"
      expectFailure(Pipeline.compile(source)) { case Error.ExpectedType(_, _) => true; case _ => false }
    }

    test("generic where clauses allow both comma and and constraint separators") {
      val source = """
        |trait C[A] = { run: A -> A }
        |def f[A where C[A], Int](x: A): A = C[A]::run(x)
        |impl i: Int = 0
        |impl C[String] = { run = (s: String) => s }
        |f[String]("Ada")
        |""".stripMargin
      assert(run(source) == Value.Text("Ada"))
    }

    test("a synthesized term cannot introduce an arbitrary literal") {
      val invalid = new Synthesizer {
        def synthesize(goal: Type, context: SynthesisContext): Result[ImplicitTerm, Error] = {
          Result.Ok(ImplicitTerm.IntLit(21))
        }
      }
      expectFailure(Pipeline.compile("summon[Int]", invalid)) {
        case Error.InvalidEvidenceForm(ImplicitTerm.IntLit(_)) => true
        case _ => false
      }
    }

    test("a synthesized term cannot read an ordinary variable from Γ") {
      val invalid = new Synthesizer {
        def synthesize(goal: Type, context: SynthesisContext): Result[ImplicitTerm, Error] = {
          Result.Ok(ImplicitTerm.Var(0))
        }
      }
      expectFailure(Pipeline.compile("let x = 21 in summon[Int]", invalid)) {
        case Error.InvalidEvidenceForm(ImplicitTerm.Var(0)) => true
        case _ => false
      }
    }

    test("synthesis validation distinguishes arrow kinds even when their erasures agree") {
      val invalid = new Synthesizer {
        def synthesize(goal: Type, context: SynthesisContext): Result[ImplicitTerm, Error] = {
          Result.Ok(ImplicitTerm.Instance(0))
        }
      }
      expectFailure(Pipeline.compile("impl f: Int -> Int = (n: Int) => n in summon[Int ~> Int]", invalid)) {
        case Error.InvalidEvidence(_, _) => true
        case _ => false
      }
    }

    test("a replacement synthesizer can explicitly instantiate and apply a polymorphic producer") {
      val composing = new Synthesizer {
        def synthesize(goal: Type, context: SynthesisContext): Result[ImplicitTerm, Error] = {
          if goal == Type.StringType then {
            val producer = context.instances.indexWhere {
              case Type.Forall(Type.Implicit(Type.Variable(0), Type.Variable(0))) => true
              case _ => false
            }
            val argument = context.instances.indexOf(Type.StringType)
            Result.Ok(ImplicitTerm.ImplicitApp(
              ImplicitTerm.TypeApp(ImplicitTerm.Instance(producer), Type.StringType),
              ImplicitTerm.Instance(argument)
            ))
          } else {
            new NaiveSynthesizer().synthesize(goal, context)
          }
        }
      }
      val source = """
        |impl id: forall A. A ~> A = [A] => (implicit x: A) => x
        |impl text: String = "Ada"
        |summon[String]
        |""".stripMargin
      assert(success(Pipeline.run(source, composing)) == Value.Text("Ada"))
    }

    test("implementation values can be stored in and returned from ordinary records") {
      assert(run("impl n: Int = 21 in let box = { value = n } in box.value") == Value.Integer(21))
    }

    test("a type parameter shadowing an alias does not become a term reference") {
      expectFailure(Pipeline.compile("type C = Int in [C] => C")) {
        case Error.UnknownTerm("C") => true
        case _ => false
      }
    }

    println(s"$passed passed; $failed failed")
    if failed > 0 then sys.exit(1)
  }
}
